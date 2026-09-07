package com.ktools.zspacecarplayer.crash;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 车机崩溃监视：捕获 → 同步落盘 → 下次启动上传云端。
 *
 * 覆盖三类现场，缺一不可：
 * <ol>
 * <li><b>Java 未捕获异常</b>：{@link Thread#setDefaultUncaughtExceptionHandler}，
 *     进程正在死，所以写盘必须同步 + fsync，任何排队/异步都来不及。</li>
 * <li><b>native 致命信号</b>（SIGSEGV，本次车机闪退就是这类）：Java 侧根本没有
 *     handler 会跑。改用会话标记文件——启动时写、Java 崩溃处理完删；下次启动若
 *     标记还在，说明上次进程没走完正常路径，再抓 {@code logcat -b crash} 找
 *     "Fatal signal" 定性。</li>
 * <li><b>主线程卡死</b>（本次「点设置进不去页面」就是这类）：进程活着、没有异常，
 *     只有 UI 不动。后台线程每 2s 往主 Looper 投一个 ping，超过阈值没回应就抓
 *     全线程栈存档——这份快照能直接看出 main 停在哪把锁上。</li>
 * </ol>
 *
 * 上传失败不丢报告：留在本地按指数退避重试，目录最多存 20 份。
 * 端点可用 SharedPreferences 覆盖（{@code adb shell} 改即可），便于自测。
 */
public final class CrashMonitor {

    private static final String TAG = "CrashMonitor";

    public static final String PREF_NAME = "crash_monitor";
    public static final String PREF_ENDPOINT = "crash_upload_endpoint";
    public static final String PREF_ENABLED = "crash_upload_enabled";
    public static final String PREF_DEVICE_ID = "crash_device_id";
    private static final String PREF_NEXT_ATTEMPT_AT = "crash_upload_next_attempt_at";
    private static final String PREF_FAILURE_STREAK = "crash_upload_failure_streak";
    private static final String PREF_LAST_BLOCKED_REPORT_AT = "crash_last_blocked_report_at";

    /**
     * 默认收集端点。与曲库同域，走已经在 8600 上验证过的 Caddy 入口。
     *
     * 必须是 https：http 会被 Caddy 301 到 https，而 OkHttp 跟随跳转时会把 POST
     * 降级成 GET，静态兜底页对 GET 回 200 —— 报告"上传成功"却被删，一份都收不到。
     *
     * 这个路径还得由反代接到真正的收件服务，并回 {@code {"ok":true}} 形式的 JSON ack；
     * 否则 upload() 一律判失败，报告留在本地（{@link CrashReportStore#MAX_FILES} 份封顶），
     * 上传按 1h→24h 退避重试。宁可攒着，也不能假装送到了。
     */
    public static final String DEFAULT_ENDPOINT = "https://your-jellyfin.example.com/crash";

    private static final String REPORT_DIR = "crash_reports";
    private static final String SESSION_MARKER = "crash_session_active";

    /** 主线程心跳间隔 */
    private static final long MAIN_PING_INTERVAL_MS = 2000L;
    /** 心跳连续多久没回应算卡死。要显著大于一次正常的重活（如列表刷新），否则误报。 */
    private static final long MAIN_BLOCK_THRESHOLD_MS = 6000L;
    /** 两份卡死报告之间的最小间隔，防止长时间卡死把存储刷满 */
    private static final long BLOCKED_REPORT_MIN_GAP_MS = 10 * 60 * 1000L;
    /** 启动后延迟上传，避开冷启动的鉴权与列表拉取抢带宽 */
    private static final long UPLOAD_DELAY_AFTER_START_MS = 12_000L;

    /** 上传退避：1h 起，翻倍，封顶 24h */
    private static final long BACKOFF_BASE_MS = 60 * 60 * 1000L;
    private static final long BACKOFF_MAX_MS = 24 * BACKOFF_BASE_MS;

    private static final int LOGCAT_MAX_BYTES = 64 * 1024;

    private static volatile CrashMonitor sInstance;

    /** 业务侧随时可写的现场上下文（当前曲目、引擎、缓冲百分比等），进每份报告 */
    private static final Map<String, String> sContext = new LinkedHashMap<String, String>();

    private final Context appContext;
    private final CrashReportStore store;
    private final CrashUploader uploader;
    private final SharedPreferences prefs;
    private final String deviceId;
    private final String versionName;
    private final int versionCode;
    private final long installedAtRealtime = SystemClock.elapsedRealtime();

    private volatile Thread.UncaughtExceptionHandler previousHandler;
    private volatile long pingSeq = 0L;
    private volatile long pingAckSeq = 0L;
    private boolean installed = false;

    private CrashMonitor(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.store = new CrashReportStore(new File(appContext.getFilesDir(), REPORT_DIR));
        this.uploader = new CrashUploader(prefs.getString(PREF_ENDPOINT, DEFAULT_ENDPOINT));
        this.deviceId = obtainDeviceId();
        String vn = "unknown";
        int vc = -1;
        try {
            PackageInfo pi = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            vn = pi.versionName;
            vc = pi.versionCode;
        } catch (Throwable ignored) {}
        this.versionName = vn;
        this.versionCode = vc;
    }

    /**
     * 安装监视器。必须在 Application.onCreate 里尽早调用——越早安装，越能捕获
     * 启动阶段的崩溃。重复调用无副作用。
     */
    public static void install(Context context) {
        if (context == null) {
            return;
        }
        CrashMonitor monitor = sInstance;
        if (monitor == null) {
            synchronized (CrashMonitor.class) {
                monitor = sInstance;
                if (monitor == null) {
                    monitor = new CrashMonitor(context);
                    sInstance = monitor;
                }
            }
        }
        monitor.installInternal();
    }

    public static CrashMonitor getInstance() {
        return sInstance;
    }

    private synchronized void installInternal() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            inspectPreviousSession();
        } catch (Throwable t) {
            Log.w(TAG, "inspectPreviousSession failed", t);
        }
        writeSessionMarker();
        installUncaughtHandler();
        startMainThreadWatchdog();
        scheduleUpload(UPLOAD_DELAY_AFTER_START_MS);
        Log.i(TAG, "installed, endpoint=" + uploader.getEndpoint());
    }

    // ------------------------------------------------------------------ //
    //  业务侧接口
    // ------------------------------------------------------------------ //

    /** 记一条面包屑（崩溃前发生了什么） */
    public static void breadcrumb(String tag, String message) {
        CrashBreadcrumbs.record(tag, message);
    }

    /** 写入/更新一个现场上下文字段，之后每份报告都会带上 */
    public static void putContext(String key, String value) {
        if (key == null) {
            return;
        }
        synchronized (sContext) {
            if (value == null) {
                sContext.remove(key);
            } else {
                sContext.put(key, value);
            }
        }
    }

    public static void putContext(String key, int value) {
        putContext(key, Integer.toString(value));
    }

    public static void putContext(String key, long value) {
        putContext(key, Long.toString(value));
    }

    public static void putContext(String key, boolean value) {
        putContext(key, value ? "true" : "false");
    }

    // ------------------------------------------------------------------ //
    //  Java 崩溃
    // ------------------------------------------------------------------ //

    private void installUncaughtHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    CrashReport report = newReport(CrashReport.KIND_JAVA_CRASH);
                    report.put("threadName", thread.getName());
                    report.put("exceptionClass", ex.getClass().getName());
                    report.put("exceptionMessage", String.valueOf(ex.getMessage()));
                    report.setStackTrace(CrashReport.stackTraceOf(ex));
                    report.setThreadDump(CrashReport.dumpAllThreads());
                    if (store.write(report) != null) {
                        // 标记已单独成报，删掉会话标记避免下次启动重复报 abnormal_exit
                        deleteSessionMarker();
                    }
                } catch (Throwable ignored) {
                    // 崩溃处理链自己再崩就没有下一层了，静默
                } finally {
                    Thread.UncaughtExceptionHandler prev = previousHandler;
                    if (prev != null) {
                        prev.uncaughtException(thread, ex);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  会话标记：native 崩溃 / 异常退出
    // ------------------------------------------------------------------ //

    private void writeSessionMarker() {
        FileOutputStream fos = null;
        try {
            File f = markerFile();
            fos = new FileOutputStream(f);
            Writer w = new OutputStreamWriter(fos, "UTF-8");
            w.write(android.os.Process.myPid() + "|" + System.currentTimeMillis());
            w.flush();
            fos.getFD().sync();
        } catch (Throwable t) {
            Log.w(TAG, "write session marker failed", t);
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void deleteSessionMarker() {
        File f = markerFile();
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "delete session marker failed");
        }
    }

    private File markerFile() {
        return new File(appContext.getFilesDir(), SESSION_MARKER);
    }

    /**
     * 上次进程没走正常路径（native 崩溃 / 被杀 / Java handler 自己失败）。
     * 靠 logcat 的 crash 缓冲区定性——该缓冲区跨进程重启保留，
     * "Fatal signal 11 (SIGSEGV)" 这类行就是 native 崩溃的铁证。
     */
    private void inspectPreviousSession() {
        File marker = markerFile();
        if (!marker.exists()) {
            return;
        }
        String markerBody = readTextFile(marker, 64);
        deleteSessionMarker();

        // 标记格式 "pid|startWallMs"：据此算出上次进程活了多久
        String previousPid = "";
        long previousSessionMs = -1L;
        if (markerBody != null) {
            String[] parts = markerBody.trim().split("\\|");
            if (parts.length >= 1) {
                previousPid = parts[0];
            }
            if (parts.length >= 2) {
                try {
                    previousSessionMs = System.currentTimeMillis() - Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
        }

        String logcat = readLogcat();
        String kind;
        String confidence;
        if (containsFatalSignal(logcat)) {
            kind = CrashReport.KIND_NATIVE_CRASH;
            confidence = "high";
        } else if (logcat.contains("FATAL EXCEPTION")) {
            // Java handler 写盘失败（磁盘满等）时的兜底
            kind = CrashReport.KIND_JAVA_CRASH;
            confidence = "medium";
        } else {
            kind = CrashReport.KIND_ABNORMAL_EXIT;
            confidence = "low";
        }

        CrashReport report = newReport(kind);
        report.put("previousPid", previousPid);
        report.put("previousSessionMs", previousSessionMs);
        report.put("evidenceConfidence", confidence);
        report.setLogcat(logcat);
        report.setThreadDump(CrashReport.dumpAllThreads());
        File written = store.write(report);
        Log.w(TAG, "previous session ended abnormally -> " + kind
                + " confidence=" + confidence + " afterMs=" + previousSessionMs
                + " file=" + written);
    }

    private static boolean containsFatalSignal(String logcat) {
        if (logcat == null) {
            return false;
        }
        return logcat.contains("Fatal signal") || logcat.contains("SIGSEGV")
                || logcat.contains("SIGABRT") || logcat.contains("tombstone");
    }

    // ------------------------------------------------------------------ //
    //  主线程卡死看门狗
    // ------------------------------------------------------------------ //

    private void startMainThreadWatchdog() {
        final Thread mainThread = Looper.getMainLooper().getThread();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                long lastAckAt = SystemClock.elapsedRealtime();
                boolean reportedThisEpisode = false;
                while (true) {
                    final long seq = ++pingSeq;
                    try {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                pingAckSeq = seq;
                            }
                        });
                    } catch (Throwable t) {
                        return; // Looper 已退出，进程要没了
                    }
                    try {
                        Thread.sleep(MAIN_PING_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (pingAckSeq >= seq) {
                        lastAckAt = SystemClock.elapsedRealtime();
                        reportedThisEpisode = false;
                        continue;
                    }
                    long blockedFor = SystemClock.elapsedRealtime() - lastAckAt;
                    if (blockedFor >= MAIN_BLOCK_THRESHOLD_MS && !reportedThisEpisode) {
                        // 一次卡死只报一份：主线程一直不回应时不要每 2s 刷一份
                        reportedThisEpisode = true;
                        reportMainThreadBlocked(mainThread, blockedFor);
                    }
                }
            }
        }, "CrashMonitor-MainWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void reportMainThreadBlocked(Thread mainThread, long blockedForMs) {
        long now = System.currentTimeMillis();
        long lastAt = prefs.getLong(PREF_LAST_BLOCKED_REPORT_AT, 0L);
        if (now - lastAt < BLOCKED_REPORT_MIN_GAP_MS) {
            Log.w(TAG, "main thread blocked " + blockedForMs + "ms, report suppressed by rate limit");
            return;
        }
        prefs.edit().putLong(PREF_LAST_BLOCKED_REPORT_AT, now).apply();

        StringBuilder mainStack = new StringBuilder(512);
        try {
            for (StackTraceElement e : mainThread.getStackTrace()) {
                mainStack.append("  at ").append(e.toString()).append('\n');
            }
        } catch (Throwable t) {
            mainStack.append("  (unavailable: ").append(t).append(")\n");
        }

        CrashReport report = newReport(CrashReport.KIND_MAIN_THREAD_BLOCKED);
        report.put("blockedForMs", blockedForMs);
        report.put("mainThreadState", String.valueOf(mainThread.getState()));
        report.setStackTrace(mainStack.toString());
        report.setThreadDump(CrashReport.dumpAllThreads());
        report.setLogcat(readLogcat());
        File written = store.write(report);
        Log.e(TAG, "MAIN THREAD BLOCKED " + blockedForMs + "ms, report=" + written);
        if (written != null) {
            // 进程还活着，可以马上把这份送出去（现场越新鲜越有价值）
            scheduleUpload(3000L);
        }
    }

    // ------------------------------------------------------------------ //
    //  报告组装
    // ------------------------------------------------------------------ //

    private CrashReport newReport(String kind) {
        String id = Long.toHexString(System.currentTimeMillis())
                + "-" + Integer.toHexString(new Random().nextInt(0xFFFF));
        CrashReport report = new CrashReport(id, kind, System.currentTimeMillis());
        report.put("deviceId", deviceId);
        report.put("appVersionName", versionName);
        report.put("appVersionCode", versionCode);
        report.put("androidVersion", Build.VERSION.RELEASE);
        report.put("sdkInt", Build.VERSION.SDK_INT);
        report.put("manufacturer", Build.MANUFACTURER);
        report.put("model", Build.MODEL);
        report.put("deviceUptimeMs", SystemClock.elapsedRealtime());
        report.put("processUptimeMs", SystemClock.elapsedRealtime() - installedAtRealtime);
        synchronized (sContext) {
            for (Map.Entry<String, String> e : sContext.entrySet()) {
                report.put("ctx_" + e.getKey(), e.getValue());
            }
        }
        report.addBreadcrumbs(CrashBreadcrumbs.snapshot());
        return report;
    }

    private String obtainDeviceId() {
        String existing = prefs.getString(PREF_DEVICE_ID, null);
        if (existing != null && existing.length() > 0) {
            return existing;
        }
        // 每次安装随机生成一个短标识，只用于把同一台车机的多份报告串起来，不含任何个人信息
        String generated = Long.toHexString(System.currentTimeMillis())
                + Integer.toHexString(new Random().nextInt());
        prefs.edit().putString(PREF_DEVICE_ID, generated).apply();
        return generated;
    }

    // ------------------------------------------------------------------ //
    //  上传（含退避）
    // ------------------------------------------------------------------ //

    private void scheduleUpload(final long delayMs) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    return;
                }
                uploadPending();
            }
        }, "CrashMonitor-Upload");
        t.setDaemon(true);
        t.start();
    }

    /** 把积压的报告逐份上传；端点不可用就退避，下次启动再试。 */
    void uploadPending() {
        if (!prefs.getBoolean(PREF_ENABLED, true)) {
            return;
        }
        long nextAt = prefs.getLong(PREF_NEXT_ATTEMPT_AT, 0L);
        if (System.currentTimeMillis() < nextAt) {
            Log.i(TAG, "upload deferred by backoff until " + nextAt);
            return;
        }
        List<File> files = store.pending();
        if (files.isEmpty()) {
            clearBackoff();
            return;
        }
        boolean anyFailure = false;
        for (File f : files) {
            String body = store.read(f);
            if (body == null) {
                store.delete(f); // 读不出来（半截文件）就别一直占着位置
                continue;
            }
            if (uploader.upload(body)) {
                store.delete(f);
            } else {
                anyFailure = true;
                break; // 端点本身不可用，剩下的没必要继续打
            }
        }
        if (anyFailure) {
            applyBackoff();
        } else {
            clearBackoff();
        }
    }

    private void applyBackoff() {
        int streak = prefs.getInt(PREF_FAILURE_STREAK, 0) + 1;
        long delay = BACKOFF_BASE_MS;
        for (int i = 1; i < streak && delay < BACKOFF_MAX_MS; i++) {
            delay *= 2;
        }
        delay = Math.min(delay, BACKOFF_MAX_MS);
        prefs.edit()
                .putInt(PREF_FAILURE_STREAK, streak)
                .putLong(PREF_NEXT_ATTEMPT_AT, System.currentTimeMillis() + delay)
                .apply();
        Log.w(TAG, "upload backoff: streak=" + streak + " nextInMs=" + delay);
    }

    private void clearBackoff() {
        if (prefs.getInt(PREF_FAILURE_STREAK, 0) != 0 || prefs.getLong(PREF_NEXT_ATTEMPT_AT, 0L) != 0L) {
            prefs.edit().putInt(PREF_FAILURE_STREAK, 0).putLong(PREF_NEXT_ATTEMPT_AT, 0L).apply();
        }
    }

    /** 覆盖上传端点（自测 / 换收集机时用），下次启动生效 */
    public void setEndpoint(String endpoint) {
        prefs.edit().putString(PREF_ENDPOINT, endpoint).apply();
    }

    public void setUploadEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    public int getPendingCount() {
        return store.pending().size();
    }

    // ------------------------------------------------------------------ //
    //  logcat / 文件工具
    // ------------------------------------------------------------------ //

    /**
     * 抓 logcat。Android 4.1 起应用只能读到自己的日志（按 UID 过滤，所以重启后的
     * 新进程仍能看到上一个进程留下的行），正好够用。
     *
     * 两个缓冲区都要：crash 缓冲区跨进程重启保留，是 native 崩溃定性的铁证；但它
     * 只有信号行。而 native 崩溃时内存里的面包屑环随进程一起没了，Java 侧也没有
     * 栈——主缓冲区尾巴就成了还原"崩溃前播放器在干什么"的唯一通道。
     */
    private String readLogcat() {
        StringBuilder sb = new StringBuilder(8192);
        String fromCrashBuffer = execLogcat(new String[]{"logcat", "-b", "crash", "-d", "-t", "400"});
        if (fromCrashBuffer != null && fromCrashBuffer.trim().length() > 0) {
            sb.append(fromCrashBuffer.trim());
        }
        String mainTail = execLogcat(new String[]{"logcat", "-d", "-t", "300"});
        if (mainTail != null && mainTail.trim().length() > 0) {
            if (sb.length() > 0) {
                sb.append("\n--- main buffer ---\n");
            }
            sb.append(mainTail.trim());
        }
        return sb.toString();
    }

    private String execLogcat(String[] command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);
            String out = readBounded(process.getInputStream(), LOGCAT_MAX_BYTES);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static String readBounded(InputStream in, int maxBytes) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[4096];
        try {
            int n;
            int total = 0;
            while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) > 0) {
                bos.write(buf, 0, n);
                total += n;
            }
        } catch (Throwable ignored) {
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        try {
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readTextFile(File f, int maxBytes) {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            return readBounded(in, maxBytes);
        } catch (Throwable t) {
            return null;
        }
    }
}
