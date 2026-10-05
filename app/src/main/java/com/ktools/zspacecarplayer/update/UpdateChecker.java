package com.ktools.zspacecarplayer.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ktools.zspacecarplayer.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 远程升级 (2026-09-12): 拉版本清单并判定是否需要升级。
 *
 * 网络底座照抄 CrashUploader / JellyfinApiClient (OkHttp 3.12.13 + TLSSocketFactory +
 * IPv6FirstDns + 内置 GTS 根证书), 原因见 {@link UpdateHttp}。
 *
 * 线程契约: check() 可在任意线程调用 (实际由 OkHttp 派发线程执行网络), 回调一律切回主线程,
 * 调用方可以直接更新 UI。整个过程不阻塞主线程。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /**
     * 默认清单地址。BuildConfig.UPDATE_MANIFEST_URL 由项目根 local.properties 注入
     * (该文件已 gitignore), 填了用填的, 没填回退到这里 —— 与 CrashMonitor.devOr 同一套思路。
     */
    public static final String DEFAULT_MANIFEST_URL =
            "https://web.kentonnie.top/zspace/update/latest.json";

    /** 「启动时自动检查更新」偏好的存放位置与键 */
    public static final String PREF_NAME = "update_prefs";
    public static final String PREF_KEY_AUTO_CHECK = "auto_check_on_launch";
    /** 默认开启: OTA 是车机拿到修复的唯一通道, 静默检查只花几百字节流量 */
    public static final boolean DEFAULT_AUTO_CHECK = true;

    private static final int CONNECT_TIMEOUT_S = 8;
    private static final int READ_TIMEOUT_S = 12;
    /** 清单只有几百字节。给到 64KB 上限, 防止 SPA 兜底页/错误页把车机内存吃掉 */
    static final int MAX_MANIFEST_BYTES = 64 * 1024;

    /** HTTP 层失败 (非 2xx / 3xx 未跟随), 带状态码便于实车定位 */
    public static final class HttpException extends IOException {
        private final int code;

        HttpException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    /** 结果回调, 保证在主线程 */
    public interface ResultCallback {
        /** 清单已成功取回并解析 (不代表有新版, 需再调 {@link #isUpdateAvailable}) */
        void onManifest(UpdateManifest manifest);

        /** 任何失败: 网络/HTTP/解析。message 已是可直接展示给车主的中文 */
        void onError(Exception e);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile OkHttpClient client;
    /** 正在飞的请求, cancel() 时用得上 (退出页面别再回来弹对话框) */
    private volatile Call inFlight;
    /**
     * 底座装配与请求派发所在的后台线程。check() 的调用点是主线程 (手动按钮 + 启动自动检查)，
     * 而 ensureClient() 里的 X.509 解析与 TrustManagerFactory 初始化在 Android 4.3 上要好几秒
     * —— 留在调用者线程就违背类注释「整个过程不阻塞主线程」与 MainActivity 的硬要求 3。
     */
    private static final ExecutorService DISPATCH = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "update-check");
                    t.setDaemon(true);
                    return t;
                }
            });
    /** cancel() 可能落在装配完成之前 (inFlight 还没就位)，故后台派发前后各查一次它 */
    private volatile boolean cancelled;

    public UpdateChecker(Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    /**
     * 异步检查。调用后立刻返回, 结果走回调。
     *
     * @param manifestUrl 清单地址, 空则回退 {@link #DEFAULT_MANIFEST_URL}
     */
    public void check(String manifestUrl, final ResultCallback callback) {
        final String url = resolveManifestUrl(manifestUrl, DEFAULT_MANIFEST_URL);
        if (callback == null) {
            Log.w(TAG, "check() without callback, ignored");
            return;
        }
        if (appContext == null) {
            postError(callback, new IllegalStateException("上下文不可用, 无法检查更新"));
            return;
        }
        Log.i(TAG, "update check begin url=" + url
                + " currentVc=" + currentVersionCode(appContext));
        final Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "ZSpaceCarPlayer-updater")
                .header("Cache-Control", "no-cache")
                .get()
                .build();
        cancelled = false;
        // 连装配带派发一起进后台：装配那几秒是主线程阻塞的真正来源，只把 enqueue 挪走不够。
        DISPATCH.execute(new Runnable() {
            @Override
            public void run() {
                if (cancelled) {
                    Log.i(TAG, "update check cancelled before bootstrap, skipped");
                    return;
                }
                try {
                    Call call = ensureClient().newCall(request);
                    inFlight = call;
                    if (cancelled) {
                        // 装配期间用户已离开页面：此刻 inFlight 才刚就位，cancel() 那一下没打到
                        Log.i(TAG, "update check cancelled during bootstrap, call dropped");
                        call.cancel();
                        return;
                    }
                    call.enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException e) {
                            Log.w(TAG, "manifest fetch failed: " + e);
                            postError(callback, new Exception(friendlyError(e), e));
                        }

                        @Override
                        public void onResponse(Call call, Response response) {
                            try {
                                UpdateManifest manifest = parseResponse(response);
                                Log.i(TAG, "manifest fetched: " + manifest.describe());
                                postManifest(callback, manifest);
                            } catch (Throwable t) {
                                Log.w(TAG, "manifest rejected: " + t);
                                postError(callback, new Exception(friendlyError(t), t));
                            } finally {
                                closeQuietly(response);
                            }
                        }
                    });
                } catch (Throwable t) {
                    // 底座装配本身失败 (证书资源缺失 / TLS 初始化异常) 也必须走回调，
                    // 否则调用方挂在 45s 看门狗上、按钮卡在「检查中」
                    Log.w(TAG, "update client bootstrap failed: " + t);
                    postError(callback, new Exception(friendlyError(t), t));
                }
            }
        });
    }

    /** 取消在飞的请求 (离开设置页/退出 App 时调用), 回调不会再回来 */
    public void cancel() {
        cancelled = true;
        Call call = inFlight;
        if (call != null && !call.isCanceled()) {
            Log.i(TAG, "update check cancelled");
            call.cancel();
        }
        inFlight = null;
    }

    /**
     * 把 HTTP 响应变成清单。
     *
     * 不跟随重定向 (client 里关了): 与 CrashUploader 同一个理由 —— 站点/Caddy 一旦把
     * 未知路径 301 到首页, 跟随之后会拿到 200 text/html 的 SPA 兜底页, 看起来「清单取到了」
     * 实则内容是垃圾。宁可在这里直接判失败并把状态码写进日志。
     *
     * 也不校验 Content-Type: 现网 latest.json 被 Caddy 以 text/html 送出 (2026-09-12 实测),
     * 按 MIME 拒收会把正常清单一起挡掉。真正的闸门是「首字符必须是 { 且能解析成对象」。
     */
    private UpdateManifest parseResponse(Response response) throws IOException,
            UpdateManifest.ParseException {
        int code = response.code();
        if (code >= 300 && code < 400) {
            throw new HttpException(code, "清单地址被重定向 (HTTP " + code + " -> "
                    + response.header("Location") + "), 请确认服务端路径");
        }
        if (code < 200 || code >= 300) {
            throw new HttpException(code, "升级服务器返回 HTTP " + code);
        }
        ResponseBody body = response.body();
        if (body == null) {
            throw new HttpException(code, "升级服务器返回空响应体");
        }
        String text = readCapped(body, MAX_MANIFEST_BYTES);
        return UpdateManifest.fromJson(text);
    }

    /** 带上限地读文本: 兜底页可能有几十 KB, 不能让它在车机内存里无限膨胀 */
    private static String readCapped(ResponseBody body, int maxBytes) throws IOException {
        InputStream in = body.byteStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("清单体积超过 " + maxBytes + " 字节, 疑似返回了错误页");
            }
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ------------------------------------------------------------ 纯判定 (可单测)

    /**
     * 是否有新版: 严格大于才算。
     * 相等 (已是最新) 与更小 (灰度回滚/清单没跟上) 一律不提示, 否则车机会反复提示
     * 「有新版」却装出一个同样的包。
     */
    public static boolean isUpdateAvailable(UpdateManifest manifest, int currentVersionCode) {
        return manifest != null && manifest.getVersionCode() > currentVersionCode;
    }

    /**
     * 是否属于「必须更新」: 清单标了 mandatory, 或当前版本已低于清单要求的 minVersionCode
     * (旧版被服务端强制拉升的场景)。
     *
     * 注意: 即便为 true, 客户端也只是把措辞改成「需要更新」, 安装仍然由车主在系统安装界面
     * 手动确认 —— 车机没有静默安装权限, 也不该有。
     */
    public static boolean shouldForceUpdate(UpdateManifest manifest, int currentVersionCode) {
        if (manifest == null) {
            return false;
        }
        return manifest.isMandatory() || currentVersionCode < manifest.getMinVersionCode();
    }

    /** BuildConfig 值优先, 空则回退默认地址 (仿 CrashMonitor.devOr) */
    public static String resolveManifestUrl(String configured, String fallback) {
        if (configured != null && configured.trim().length() > 0) {
            return configured.trim();
        }
        return fallback;
    }

    /** 实际生效的清单地址 = local.properties 注入值 ?: 默认值 */
    public static String manifestUrlOrDefault() {
        return resolveManifestUrl(BuildConfig.UPDATE_MANIFEST_URL, DEFAULT_MANIFEST_URL);
    }

    /** 当前安装包的 versionCode; 取不到返回 0 (此时任何清单都会被判为「有新版」) */
    public static int currentVersionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            Log.w(TAG, "currentVersionCode unavailable: " + t);
            return 0;
        }
    }

    /** 当前安装包的 versionName; 取不到返回 "未知" */
    public static String currentVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (pi.versionName != null && pi.versionName.length() > 0) {
                return pi.versionName;
            }
        } catch (Throwable t) {
            Log.w(TAG, "currentVersionName unavailable: " + t);
        }
        return "未知";
    }

    /**
     * 把底层异常翻译成车主看得懂的中文。
     * 纯函数, 单测覆盖各分支; 实车 logcat 里另有一行原始异常, 两者互补。
     */
    public static String friendlyError(Throwable t) {
        if (t == null) {
            return "检查更新失败 (未知原因)";
        }
        if (t instanceof HttpException) {
            return t.getMessage();
        }
        if (t instanceof UpdateManifest.ParseException) {
            return "版本清单不可用: " + t.getMessage();
        }
        if (t instanceof SocketTimeoutException) {
            return "连接升级服务器超时, 请检查车机网络后重试";
        }
        if (t instanceof UnknownHostException) {
            return "无法解析升级服务器地址 (DNS 失败或车机离线)";
        }
        if (t instanceof ConnectException) {
            return "连不上升级服务器, 请稍后重试";
        }
        if (t instanceof SSLException) {
            return "升级服务器证书校验失败 (TLS), 请检查车机时间是否正确";
        }
        if (t instanceof IOException) {
            String msg = t.getMessage();
            return "网络异常: " + (msg == null ? t.getClass().getSimpleName() : msg);
        }
        String msg = t.getMessage();
        return "检查更新失败: " + (msg == null ? t.getClass().getSimpleName() : msg);
    }

    // ------------------------------------------------------------ 内部

    private OkHttpClient ensureClient() {
        OkHttpClient c = client;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (client == null) {
                client = UpdateHttp.build(appContext, CONNECT_TIMEOUT_S, READ_TIMEOUT_S, false);
            }
            return client;
        }
    }

    private void postManifest(final ResultCallback cb, final UpdateManifest manifest) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onManifest(manifest);
            }
        });
    }

    private void postError(final ResultCallback cb, final Exception e) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(e);
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
}
