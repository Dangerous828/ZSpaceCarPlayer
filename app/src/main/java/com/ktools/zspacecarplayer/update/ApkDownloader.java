package com.ktools.zspacecarplayer.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 远程升级 (2026-09-12): 带进度地把 APK 流式下载到应用私有缓存, 并做完整性校验。
 *
 * 关键设计:
 *  - 落到 {@code getCacheDir()/updates/<fileName>}, 先写 {@code .part} 再重命名。半截文件
 *    永远不叫最终名字, 因此「下载被打断 → 下次误装残包」这条路径从根上不存在。
 *  - SHA-256 边下边算 (MessageDigest 是 API 1 就有的), 不需要下载完再读一遍 3MB 文件,
 *    车机 eMMC 上这一遍能省好几秒。
 *  - 校验失败 (字节数或摘要不符) 一律删文件 + 抛 {@link VerifyException}, 绝不把文件交给安装器。
 *  - 进度回调按步长节流 (见 {@link #shouldReportProgress}), 否则 8KB 一次会把主线程刷爆。
 *  - 线程契约与 UpdateChecker 一致: 网络在 OkHttp 派发线程, 回调切主线程, 不阻塞 UI。
 */
public final class ApkDownloader {

    private static final String TAG = "ApkDownloader";

    /** getCacheDir() 下的子目录名, 与 res/xml/file_paths.xml 的 cache-path 对应 */
    public static final String UPDATE_DIR = "updates";

    private static final int BUFFER_BYTES = 8192;
    private static final int CONNECT_TIMEOUT_S = 15;
    /** 弱网下两次 read 之间的上限; 不是总时长, 3MB 的包慢网也能下完 */
    private static final int READ_TIMEOUT_S = 30;
    private static final int SHA256_HEX_LEN = 64;
    /** 进度节流的最小步长: 与「总量的 1%」取较大者 */
    static final long PROGRESS_MIN_STEP_BYTES = 64 * 1024;
    /** 落盘前要求的额外空闲空间: 校验/rename 期间不能把 cache 分区写到 0 */
    private static final long FREE_SPACE_MARGIN_BYTES = 4 * 1024 * 1024;
    /** 文件名长度上限, 防止服务端塞一个几百字符的名字把文件系统顶爆 */
    private static final int MAX_FILE_NAME_LEN = 80;

    /**
     * 清理半截文件的后台执行器。用静态单线程而不是每次 new Thread:
     * purgePartialDownloads 在每次打开设置页时都会被调用, 车主来回点几下就是几个线程。
     * daemon + 最低优先级, 不与播放/下载抢 CPU。
     */
    private static final java.util.concurrent.ExecutorService HOUSEKEEPING =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "ApkDownloader-Housekeeping");
                            t.setDaemon(true);
                            t.setPriority(Thread.MIN_PRIORITY);
                            return t;
                        }
                    });

    /** 完整性校验不通过。抛出前文件已删除 */
    public static final class VerifyException extends IOException {
        public VerifyException(String message) {
            super(message);
        }
    }

    /** 用户/页面切换导致的主动取消, UI 据此静默处理 (不弹错误) */
    public static final class CancelledException extends IOException {
        public CancelledException(String message) {
            super(message);
        }
    }

    /** 下载回调, 保证在主线程 */
    public interface Listener {
        /** @param totalBytes 总字节数; -1 表示服务端与清单都没给出, 此时只能显示已下载量 */
        void onProgress(long downloadedBytes, long totalBytes);

        /** 下载完成且 SHA-256 + 大小双校验通过 */
        void onSuccess(File apkFile);

        void onError(Exception e);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile OkHttpClient client;
    private volatile Call inFlight;
    private volatile boolean cancelled;
    private volatile boolean running;

    public ApkDownloader(Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 开始下载。同一时刻只允许一个任务: 车主连点「立即下载」时第二次直接拒掉,
     * 免得两份流同时往同一个 .part 上写。
     */
    public void download(UpdateManifest manifest, final Listener listener) {
        if (listener == null) {
            Log.w(TAG, "download() without listener, ignored");
            return;
        }
        if (manifest == null) {
            postError(listener, new IllegalStateException("没有可下载的版本清单"));
            return;
        }
        if (appContext == null) {
            postError(listener, new IllegalStateException("上下文不可用, 无法下载安装包"));
            return;
        }
        if (running) {
            postError(listener, new IllegalStateException("安装包正在下载中, 请稍候"));
            return;
        }
        // 每次新任务都清掉取消标记: 上一次「关掉对话框触发的 cancel」不能连累这一次
        cancelled = false;
        running = true;

        final long declaredSize = manifest.getSizeBytes();
        final File dir = new File(appContext.getCacheDir(), UPDATE_DIR);
        final String name = safeFileName(manifest.getFileName(), manifest.getApkUrl());
        final File dest = new File(dir, name);
        final File part = new File(dir, name + ".part");

        long usable = usableSpaceOf(appContext.getCacheDir());
        if (declaredSize > 0 && usable >= 0 && usable < declaredSize + FREE_SPACE_MARGIN_BYTES) {
            running = false;
            String msg = "车机存储空间不足: 需要约 " + humanSize(declaredSize + FREE_SPACE_MARGIN_BYTES)
                    + ", 可用 " + humanSize(usable);
            Log.w(TAG, msg);
            postError(listener, new IOException(msg));
            return;
        }

        Log.i(TAG, "download begin url=" + manifest.getApkUrl() + " size=" + declaredSize
                + " sha256=" + manifest.getSha256() + " dest=" + dest + " usable=" + usable);
        Request request = new Request.Builder()
                .url(manifest.getApkUrl())
                .header("User-Agent", "ZSpaceCarPlayer-updater")
                .get()
                .build();
        Call call = ensureClient().newCall(request);
        inFlight = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                running = false;
                deleteQuietly(part);
                if (cancelled) {
                    Log.i(TAG, "download cancelled by user");
                    postError(listener, new CancelledException("下载已取消"));
                } else {
                    Log.w(TAG, "download failed: " + e);
                    postError(listener, new Exception(UpdateChecker.friendlyError(e), e));
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    File apk = transfer(response, manifest, part, dest, listener);
                    running = false;
                    Log.i(TAG, "download verified ok: " + apk + " (" + apk.length() + " bytes)");
                    postSuccess(listener, apk);
                } catch (Throwable t) {
                    running = false;
                    deleteQuietly(part);
                    if (t instanceof CancelledException) {
                        Log.i(TAG, "download cancelled mid-stream");
                        postError(listener, (Exception) t);
                    } else {
                        Log.w(TAG, "download rejected: " + t);
                        postError(listener, t instanceof Exception
                                ? (Exception) t
                                : new Exception(UpdateChecker.friendlyError(t), t));
                    }
                } finally {
                    closeQuietly(response);
                }
            }
        });
    }

    /** 取消下载: 置标记 + 断掉在飞的请求, 残留 .part 由失败分支清理 */
    public void cancel() {
        cancelled = true;
        Call call = inFlight;
        if (call != null && !call.isCanceled()) {
            Log.i(TAG, "cancel requested");
            call.cancel();
        }
    }

    /**
     * 清掉上一次被打断留下的 .part 半截文件。
     *
     * 只删 .part, 不删已完成的 APK: 那个包可能正等着车主在系统安装界面上点「安装」
     * (安装界面是另一个进程, 我们这边看不到它的状态), 删了就白下一趟 3MB。
     * 半截文件则是纯粹的垃圾 —— 每次下载都从新 .part 开始写。
     *
     * 文件 IO 一律丢到后台线程: 本方法会在设置页打开时被调用, 那正是主线程。
     */
    public void purgePartialDownloads() {
        if (appContext == null) {
            return;
        }
        HOUSEKEEPING.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(appContext.getCacheDir(), UPDATE_DIR);
                    File[] files = dir.listFiles();
                    if (files == null) {
                        return;
                    }
                    for (File f : files) {
                        if (f.isFile() && f.getName().endsWith(".part") && deleteQuietly(f)) {
                            Log.i(TAG, "purged partial download: " + f.getName()
                                    + " (" + f.length() + " bytes)");
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "purgePartialDownloads failed: " + t);
                }
            }
        });
    }

    // ------------------------------------------------------------ 下载 + 校验

    /**
     * 落盘 + 校验。包级可见是为了让单测能直接驱动这条真实流程 (真文件、真 MessageDigest、
     * 真 OkHttp ResponseBody), 不必为了测校验而起 Context。
     *
     * 失败清理收在这里而不是交给调用方: 「校验失败的包绝不留在盘上」是硬不变量,
     * 依赖调用方记得删就迟早会漏。此处流已在 transferInternal 的 finally 里关完, 可以安全删。
     */
    File transfer(Response response, UpdateManifest manifest, File part, File dest,
                  Listener listener) throws IOException {
        try {
            return transferInternal(response, manifest, part, dest, listener);
        } catch (IOException e) {
            deleteQuietly(part);
            deleteQuietly(dest);
            throw e;
        }
    }

    private File transferInternal(Response response, UpdateManifest manifest, File part, File dest,
                                  Listener listener) throws IOException {
        int code = response.code();
        if (code >= 300 && code < 400) {
            throw new UpdateChecker.HttpException(code, "安装包地址被重定向 (HTTP " + code
                    + "), 请确认清单里的 apkUrl");
        }
        if (code < 200 || code >= 300) {
            throw new UpdateChecker.HttpException(code, "下载安装包失败: HTTP " + code);
        }
        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("安装包响应为空");
        }
        // SPA 兜底页会对任何未知路径回 200 text/html (2026-09-12 现网实测)。
        // 早挡一下省得白下一趟; 真正的闸门仍是下面的 sha256。
        // Content-Type 要同时看响应头与 body 的 MediaType: 正常服务器走 header,
        // 但个别反代/单测构造的响应只有 body.contentType() 上有值, 漏看任何一边
        // 都会把兜底网页当成 APK 落盘。
        String contentType = response.header("Content-Type");
        MediaType bodyType = body.contentType();
        if (contentType == null && bodyType != null) {
            contentType = bodyType.toString();
        }
        if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
            throw new IOException("下载地址返回的是网页而非安装包 (Content-Type=text/html), "
                    + "请确认服务端已放置该 APK");
        }

        long declared = manifest.getSizeBytes();
        long total = declared > 0 ? declared : body.contentLength();

        if (!part.getParentFile().exists() && !part.getParentFile().mkdirs()) {
            throw new IOException("无法创建下载目录: " + part.getParentFile());
        }
        deleteQuietly(part);
        deleteQuietly(dest);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            // SHA-256 是 JCA 必备算法, 走到这里说明 ROM 被裁剪过; 没有摘要就绝不安装
            throw new IOException("车机不支持 SHA-256, 无法校验安装包: " + e);
        }

        long written = 0;
        long lastReported = -1;
        InputStream in = body.byteStream();
        OutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(part), BUFFER_BYTES);
            byte[] buf = new byte[BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) {
                    throw new CancelledException("下载已取消");
                }
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                written += n;
                if (declared > 0 && written > declared) {
                    // 比清单声明的还大: 服务端给的显然不是那个包, 立刻止损别把 cache 写满
                    throw new VerifyException("下载字节数已超过清单声明的 " + declared + " 字节, 已中止");
                }
                if (shouldReportProgress(lastReported, written, total)) {
                    lastReported = written;
                    postProgress(listener, written, total);
                }
            }
            out.flush();
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }

        String actualSha = toHex(digest.digest());
        Log.i(TAG, "downloaded " + written + "/" + total + " bytes sha256=" + actualSha
                + " expected=" + manifest.getSha256());
        // 收尾再报一次进度, 让 UI 一定走到 100% (节流可能把最后一段吞掉)
        postProgress(listener, written, total > 0 ? total : written);

        if (!isSizeOk(declared, written)) {
            throw new VerifyException("安装包大小不符: 清单声明 " + declared + " 字节, 实收 "
                    + written + " 字节 (文件已删除)");
        }
        if (!verifySha256(manifest.getSha256(), actualSha)) {
            throw new VerifyException("SHA-256 校验失败, 安装包可能已损坏或被篡改 (文件已删除)");
        }
        Log.i(TAG, "sha256 verified OK, renaming " + part.getName() + " -> " + dest.getName());
        if (!part.renameTo(dest)) {
            throw new IOException("安装包保存失败: 无法重命名到 " + dest);
        }
        return dest;
    }

    // ------------------------------------------------------------ 纯函数 (可单测)

    /**
     * 摘要比对, 大小写不敏感。
     * 服务端手填 sha256 时大小写都可能, 而 MessageDigest 输出我们统一转小写;
     * 这里两边都归一化, 并顺手挡掉长度不对的期望值 (清单缺字段时不能当成「校验通过」)。
     */
    public static boolean verifySha256(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        String a = expectedHex.trim().toLowerCase(Locale.US);
        String b = actualHex.trim().toLowerCase(Locale.US);
        if (a.length() != SHA256_HEX_LEN || b.length() != SHA256_HEX_LEN) {
            return false;
        }
        // 定长比较, 逐位异或累积: 与 String.equals 等价但不提前短路
        int diff = 0;
        for (int i = 0; i < SHA256_HEX_LEN; i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /**
     * 大小校验。expected &lt;= 0 表示清单没声明大小, 此时以 sha256 为唯一凭据, 放行。
     * 声明了就必须一字节不差 —— 少一字节说明流被截断, 多一字节说明拿到的不是同一个包。
     */
    public static boolean isSizeOk(long expectedBytes, long actualBytes) {
        if (expectedBytes <= 0) {
            return true;
        }
        return expectedBytes == actualBytes;
    }

    /** 计算字节的 SHA-256 十六进制小写串 (单测用来造期望值/自校验) */
    public static String sha256Hex(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    /** 从 URL 取文件名: 去掉 query/fragment, 取最后一段路径 */
    public static String fileNameFromUrl(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            // 末尾就是 '/' (目录形态) 时得到空串, 由 safeFileName 兜底成 update.apk
            s = s.substring(slash + 1);
        }
        return s;
    }

    /**
     * 把服务端给的文件名洗成安全的本地名。
     *
     * 清单是远端可写的, fileName 里塞 "../../x" 就能让文件落到 cacheDir 之外;
     * 塞 "/" 或奇怪字符也会让 FileProvider 的 path 匹配失败。这里一律:
     * 只取最后一段、白名单字符替换、强制 .apk 后缀、限长, 兜底 "update.apk"。
     */
    public static String safeFileName(String manifestFileName, String apkUrl) {
        String candidate = (manifestFileName == null) ? "" : manifestFileName.trim();
        if (candidate.length() == 0) {
            candidate = fileNameFromUrl(apkUrl);
        }
        if (candidate == null) {
            candidate = "";
        }
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (slash >= 0) {
            candidate = candidate.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(candidate.length() + 4);
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String name = sb.toString();
        while (name.startsWith(".")) {
            // ".", ".." 与隐藏文件一律不接受
            name = name.substring(1);
        }
        if (name.length() == 0) {
            name = "update";
        }
        if (name.length() > MAX_FILE_NAME_LEN) {
            name = name.substring(0, MAX_FILE_NAME_LEN);
        }
        if (!name.toLowerCase(Locale.US).endsWith(".apk")) {
            name = name + ".apk";
        }
        return name;
    }

    /**
     * 进度节流判定: 步长 = max(总量的 1%, 64KB), 首次与终点必报。
     * 3MB 的包按 8KB 一读会触发 ~380 次回调, 每次都 post 到主线程刷 TextView,
     * 车机上就是肉眼可见的卡顿; 按 64KB 一步只剩 ~48 次。
     *
     * @param lastReportedBytes 上次上报时的已下载字节; -1 表示还没报过
     * @param totalBytes        总字节; &lt;= 0 表示未知
     */
    static boolean shouldReportProgress(long lastReportedBytes, long downloadedBytes, long totalBytes) {
        if (lastReportedBytes < 0) {
            return true; // 首次必报: 用户点了「立即下载」得马上看到进度条动起来
        }
        if (totalBytes > 0 && downloadedBytes >= totalBytes) {
            return true; // 终点必报
        }
        return downloadedBytes - lastReportedBytes >= progressStep(totalBytes);
    }

    static long progressStep(long totalBytes) {
        if (totalBytes <= 0) {
            return PROGRESS_MIN_STEP_BYTES;
        }
        return Math.max(totalBytes / 100, PROGRESS_MIN_STEP_BYTES);
    }

    /** 字节数转人话; 负数表示未知 */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    // ------------------------------------------------------------ 内部

    private OkHttpClient ensureClient() {
        OkHttpClient c = client;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (client == null) {
                // 下载允许跟随 http→http / https→https 跳转 (CDN 换域名很常见),
                // 完整性由 sha256 兜底; https→http 的降级在 UpdateHttp 里被永久关掉。
                client = UpdateHttp.build(appContext, CONNECT_TIMEOUT_S, READ_TIMEOUT_S, true);
            }
            return client;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static long usableSpaceOf(File dir) {
        try {
            // File.getUsableSpace 是 API 9+, 车机 API 18 可用
            long usable = dir.getUsableSpace();
            return usable > 0 ? usable : -1;
        } catch (Throwable t) {
            Log.w(TAG, "usableSpace probe failed: " + t);
            return -1;
        }
    }

    private static boolean deleteQuietly(File f) {
        try {
            return f != null && f.exists() && f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    private void postProgress(final Listener l, final long downloaded, final long total) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                l.onProgress(downloaded, total);
            }
        });
    }

    private void postSuccess(final Listener l, final File file) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                l.onSuccess(file);
            }
        });
    }

    private void postError(final Listener l, final Exception e) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                l.onError(e);
            }
        });
    }

    private static void closeQuietly(Response response) {
        if (response == null) {
            return;
        }
        try {
            ResponseBody body = response.body();
            if (body != null) {
                body.close();
            }
        } catch (Throwable ignored) {}
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {}
    }
}
