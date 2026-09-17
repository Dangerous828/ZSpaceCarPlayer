package com.ktools.zspacecarplayer.crash;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.ktools.zspacecarplayer.BuildConfig;

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
 *
 * 会话标记同时是 v3 原生引擎的熔断依据：连续多次可归因崩溃即强制回退系统 MediaPlayer
 * （{@link NativeEngineGuard}），否则车机会在现场陷进「一放歌就闪退」的死循环，
 * 而用户不会自己去设置页关引擎。
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
    /** v3 原生引擎连续崩溃计数与熔断判定，策略见 {@link NativeEngineGuard} */
    private static final String PREF_ENGINE_CRASH_COUNT = "engine_v3_crash_count";
    private static final String PREF_ENGINE_AUTO_DISABLED = "engine_v3_auto_disabled";
    private static final String PREF_ENGINE_AUTO_DISABLED_AT = "engine_v3_auto_disabled_at";

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
    /**
     * 本地开发凭据回退 (2026-09-10): BuildConfig 由项目根 local.properties 注入
     * (该文件已 gitignore, 不进版本库)。填了用真实上报地址, 没填用占位符。
     */
    private static String devOr(String buildConfigValue, String placeholder) {
        return (buildConfigValue == null || buildConfigValue.isEmpty()) ? placeholder : buildConfigValue;
    }

    public static final String DEFAULT_ENDPOINT =
            devOr(BuildConfig.CRASH_ENDPOINT, "https://your-jellyfin.example.com/crash");

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

    /**
     * 本次会话是否启用了 v3 原生引擎。写进会话标记，下次启动据此把崩溃归因到引擎上
     * （{@link NativeEngineGuard}）；系统引擎下的崩溃不许连累 v3。
     */
    private static volatile boolean sV3EngineActive = false;

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
    /** 会话标记前缀 "pid|启动时刻"，引擎切换时据此重写标记而不必重新取 pid */
    private volatile String sessionMarkerHead;

    private CrashMonitor(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.store = new CrashReportStore(new File(appContext.getFilesDir(), REPORT_DIR));
        this.uploader = new CrashUploader(appContext, prefs.getString(PREF_ENDPOINT, DEFAULT_ENDPOINT));
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
        try {
            // 落盘滚动诊断日志: logcat 与内存面包屑都会随车机重启丢失, 关键事件必须落盘
            DiagLog.init(appContext.getFilesDir());
        } catch (Throwable t) {
            Log.w(TAG, "DiagLog init failed, disk diagnostics disabled", t);
        }
        installUncaughtHandler();
        startMainThreadWatchdog();
        scheduleUpload(UPLOAD_DELAY_AFTER_START_MS);
        Log.i(TAG, "installed, endpoint=" + uploader.getEndpoint());
    }

    // ------------------------------------------------------------------ //
    //  业务侧接口
    // ------------------------------------------------------------------ //

    /** 记一条面包屑（崩溃前发生了什么）。同时落盘到滚动诊断日志, 重启后仍可取证。 */
    public static void breadcrumb(String tag, String message) {
        CrashBreadcrumbs.record(tag, message);
        DiagLog.log(tag, message);
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
                    if (sV3EngineActive) {
                        // 标记被删后下次启动无从归因，v3 期间的 Java 崩溃只能在此刻计入熔断
                        applyEngineGuard(CrashReport.KIND_JAVA_CRASH, true);
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
        sessionMarkerHead = android.os.Process.myPid() + "|" + System.currentTimeMillis();
        writeMarkerBody(sessionMarkerHead + "|" + engineField());
    }

    /**
     * 会话内引擎切换（设置页改偏好后下一首起播时惰性重建）时重写标记，让「上次死的时候
     * 在用哪个引擎」始终是最新的——归因错了，熔断就会误伤好引擎或放过坏引擎。
     */
    public static void markV3EngineActive(boolean active) {
        sV3EngineActive = active;
        CrashMonitor monitor = sInstance;
        if (monitor != null) {
            monitor.rewriteSessionMarker();
        }
    }

    private void rewriteSessionMarker() {
        String head = sessionMarkerHead;
        if (head != null) {
            writeMarkerBody(head + "|" + engineField());
        }
    }

    private static String engineField() {
        return sV3EngineActive ? "v3" : "sys";
    }

    private void writeMarkerBody(String body) {
        FileOutputStream fos = null;
        try {
            File f = markerFile();
            fos = new FileOutputStream(f);
            Writer w = new OutputStreamWriter(fos, "UTF-8");
            w.write(body);
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
            // 上次会话干净收尾（Java 崩溃处理链已删标记）：连续崩溃计数清零，
            // 但熔断判定保持——要恢复 v3 得由用户在设置页显式重开
            clearEngineCrashCount();
            return;
        }
        String markerBody = readTextFile(marker, 64);
        deleteSessionMarker();

        // 标记格式 "pid|startWallMs|engine"：据此算出上次进程活了多久、当时在用哪个引擎
        String previousPid = "";
        long previousSessionMs = -1L;
        boolean previousV3Active = false;
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
            if (parts.length >= 3) {
                previousV3Active = "v3".equals(parts[2]);
            }
        }

        String logcat = readLogcat();
        // 证据必须归属到上次那个 pid：车机上第三方进程崩出 SIGPIPE/SIGSEGV 太常见，
        // 全文子串匹配会把别人的崩溃记到 v3 引擎头上（判定见 NativeEngineGuard）
        String kind;
        String confidence;
        if (NativeEngineGuard.hasFatalSignalForPid(logcat, previousPid)) {
            kind = CrashReport.KIND_NATIVE_CRASH;
            confidence = "high";
        } else if (NativeEngineGuard.hasFatalExceptionForPid(logcat, previousPid)) {
            // Java handler 写盘失败（磁盘满等）时的兜底
            kind = CrashReport.KIND_JAVA_CRASH;
            confidence = "medium";
        } else {
            kind = CrashReport.KIND_ABNORMAL_EXIT;
            confidence = "low";
        }
        if (previousPid.length() == 0 && !"low".equals(confidence)) {
            // 标记里没有 pid，上面的匹配退化成全文判定，证据强度必须降级
            confidence = confidence + "/pid_unmatched";
        }

        CrashReport report = newReport(kind);
        report.put("previousPid", previousPid);
        report.put("previousSessionMs", previousSessionMs);
        report.put("previousEngineV3", previousV3Active);
        report.put("evidenceConfidence", confidence);
        report.setLogcat(logcat);
        report.setThreadDump(CrashReport.dumpAllThreads());
        File written = store.write(report);
        Log.w(TAG, "previous session ended abnormally -> " + kind
                + " confidence=" + confidence + " afterMs=" + previousSessionMs
                + " v3Engine=" + previousV3Active + " file=" + written);
        applyEngineGuard(kind, previousV3Active);
    }

    // ------------------------------------------------------------------ //
    //  v3 原生引擎熔断（策略见 NativeEngineGuard）
    // ------------------------------------------------------------------ //

    /**
     * 按上次会话的死法更新连续崩溃计数，达到阈值即熔断。
     *
     * 用 {@code commit()} 而不是 {@code apply()}：Java 崩溃路径上进程正在死，
     * 异步写盘大概率来不及落地，下次启动就白丢一次计数。
     */
    private void applyEngineGuard(String kind, boolean v3Active) {
        try {
            int recorded = prefs.getInt(PREF_ENGINE_CRASH_COUNT, 0);
            boolean alreadyDisabled = prefs.getBoolean(PREF_ENGINE_AUTO_DISABLED, false);
            int next = NativeEngineGuard.nextCrashCount(kind, v3Active, recorded);
            boolean disable = NativeEngineGuard.shouldAutoDisable(next, alreadyDisabled);
            long latchedAt = prefs.getLong(PREF_ENGINE_AUTO_DISABLED_AT, 0L);
            prefs.edit()
                    .putInt(PREF_ENGINE_CRASH_COUNT, next)
                    .putBoolean(PREF_ENGINE_AUTO_DISABLED, disable)
                    .putLong(PREF_ENGINE_AUTO_DISABLED_AT,
                            disable ? (latchedAt != 0L ? latchedAt : System.currentTimeMillis()) : 0L)
                    .commit();
            if (disable && !alreadyDisabled) {
                Log.e(TAG, "v3 native engine circuit-broken after " + next
                        + " attributed crashes; forcing system MediaPlayer until user re-enables");
                breadcrumb("engine", "auto-disabled after " + next + " crashes");
            } else if (disable) {
                Log.w(TAG, "engine guard still latched (streak reset to " + next
                        + "); system MediaPlayer stays until user re-enables");
            } else if (next > 0) {
                Log.w(TAG, "engine crash count=" + next + "/" + NativeEngineGuard.CRASH_LIMIT
                        + " kind=" + kind);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyEngineGuard failed", t);
        }
    }

    private void clearEngineCrashCount() {
        if (prefs.getInt(PREF_ENGINE_CRASH_COUNT, 0) != 0) {
            prefs.edit().putInt(PREF_ENGINE_CRASH_COUNT, 0).commit();
        }
    }

    /** v3 引擎是否已被崩溃熔断强制关闭（设置页据此显示与恢复） */
    public static boolean isEngineAutoDisabled() {
        CrashMonitor monitor = sInstance;
        return monitor != null && monitor.prefs.getBoolean(PREF_ENGINE_AUTO_DISABLED, false);
    }

    /** 当前连续崩溃计数（诊断/设置页展示用） */
    public static int getEngineCrashCount() {
        CrashMonitor monitor = sInstance;
        return monitor == null ? 0 : monitor.prefs.getInt(PREF_ENGINE_CRASH_COUNT, 0);
    }

    /**
     * 用户在设置页显式重开 v3：清空熔断与计数，给一轮全新预算。
     * 不自动恢复——熔断的意义就在于「机器自己不再尝试已经崩过三次的路径」。
     */
    public static void resetEngineGuard() {
        CrashMonitor monitor = sInstance;
        if (monitor == null) {
            return;
        }
        monitor.prefs.edit()
                .putBoolean(PREF_ENGINE_AUTO_DISABLED, false)
                .putLong(PREF_ENGINE_AUTO_DISABLED_AT, 0L)
                .putInt(PREF_ENGINE_CRASH_COUNT, 0)
                .commit();
        breadcrumb("engine", "guard reset by user re-enable");
        Log.i(TAG, "engine guard reset by user");
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

    /** 手动上报的场景标签: 设置页「立即上报诊断日志」按钮。 */
    public static final String KIND_MANUAL_DIAG = "manual_diag";

    /**
     * 手动立即上报诊断现场 (设置页按钮触发)。
     *
     * 与崩溃上报不同: 不落盘 {@link CrashReportStore}、不参与退避 —— 这是用户主动
     * 点按钮的一次性动作, 当场就要结论。构造一份与崩溃同构的报告 (设备/版本/面包屑/
     * logcat 尾巴/播放上下文) 并附上 DiagLog 滚动日志内容, 走同一端点同一 ack 校验。
     *
     * @param source 触发入口标识 (如 settings_page / playback_bar), 写进报告便于服务端区分
     * @return true 表示服务端确认收下; false 表示本次没传出去 (网络/端点/TLS/ack).
     *         失败也可再点一次, 不设退避。
     */
    public boolean uploadDiagnosticsNow(String source) {
        if (!prefs.getBoolean(PREF_ENABLED, true)) {
            Log.w(TAG, "manual diag upload skipped: upload disabled");
            return false;
        }
        CrashReport report = newReport(KIND_MANUAL_DIAG);
        if (source != null) {
            report.put("triggerSource", source);
        }
        String diag = DiagLog.content();
        if (diag != null && diag.trim().length() > 0) {
            report.put("diagLog", diag);
        }
        // 手动上报也带上 crash 缓冲区的 logcat 尾巴 (崩溃信号行在重启后仍保留, 是现场铁证)
        report.setLogcat(readLogcat());
        String body = report.toJson();
        if (body.length() > CrashUploader.MAX_BODY_BYTES) {
            Log.w(TAG, "manual diag body too large (" + body.length() + " chars), dropping logcat");
            report.setLogcat("");
            body = report.toJson();
        }
        // upload() 对超限 body 的语义是「让调用方删文件」——手动上报没有本地文件可删,
        // 必须自己确认体积, 否则用户会看到"上报成功"而现场根本没发出去
        if (body.length() > CrashUploader.MAX_BODY_BYTES) {
            Log.w(TAG, "manual diag body still too large (" + body.length() + " chars), aborting");
            return false;
        }
        boolean ok = uploader.upload(body);
        Log.i(TAG, "manual diag upload -> " + ok + " (bytes=" + body.length() + ")");
        if (!ok) {
            CrashMonitor.breadcrumb("diag", "manual upload failed");
        }
        return ok;
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
     *
     * 主缓冲区在车机上会混进大量导航等系统进程的日志（NaviServer/phone/CAN...），
     * 白占上传体积也稀释现场，所以按行白名单过滤：只留本应用子系统 + 音频链路
     * 关键系统 tag 的行。crash 缓冲区不过滤——信号行没有 tag，且是崩溃定性铁证。
     */
    private String readLogcat() {
        StringBuilder sb = new StringBuilder(8192);
        String fromCrashBuffer = execLogcat(new String[]{"logcat", "-b", "crash", "-d", "-t", "400"});
        if (fromCrashBuffer != null && fromCrashBuffer.trim().length() > 0) {
            sb.append(fromCrashBuffer.trim());
        }
        // 行数从 300 提到 1500: 过滤会去掉大部分无关行, 提量后过滤完仍保留足够时间跨度
        String mainTail = execLogcat(new String[]{"logcat", "-d", "-t", "1500"});
        if (mainTail != null && mainTail.trim().length() > 0) {
            String filtered = filterLogcatLines(mainTail);
            if (filtered.trim().length() > 0) {
                if (sb.length() > 0) {
                    sb.append("\n--- main buffer ---\n");
                }
                sb.append(filtered.trim());
            }
        }
        return sb.toString();
    }

    /**
     * 按 tag 白名单过滤 logcat 行。格式 "… V/tag: msg"，tag 在最后一个 '/' 与 ':' 之间；
     * 用整行 contains 判断，避免 tag 大小写/附加字段差异漏判。
     */
    private static String filterLogcatLines(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (String line : raw.split("\\n")) {
            if (line.length() == 0) {
                continue;
            }
            if (LOG_KEEP_ANY.matcher(line).find()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 保留的行: 本应用各子系统 + 音频/媒体链路关键系统 tag。整行匹配, 大小写不敏感。 */
    private static final java.util.regex.Pattern LOG_KEEP_ANY = java.util.regex.Pattern.compile(
            "(?i)(AudioPlayerService|DspAudioTrackPlayer|AndroidMediaPlayer|HttpProxyServer|"
          + "BufferedHttpSource|BufferingPolicy|MainActivity|UpdateChecker|UpdateHttp|ApkDownloader|"
          + "UpdateInstaller|SystemMuteMonitor|MusicVolumeIntent|CarRemoteControlClient|GeelyAmpWake|"
          + "MediaButtonReceiver|BootReceiver|NativeDsp|NativeLosslessDecoder|CacheSizeManager|SongDao|"
          + "DiagLog|CrashMonitor|CrashBreadcrumbs|CrashUploader|CrashReportStore|"
          + "AudioTrack|AudioFlinger|AudioManager|MediaPlayer|MediaCodec|PlaybackStateMachine"
          + "|OkHttp|okhttp|JellyfinApiClient)");

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
