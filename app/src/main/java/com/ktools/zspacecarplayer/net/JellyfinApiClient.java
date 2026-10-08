package com.ktools.zspacecarplayer.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ktools.zspacecarplayer.BuildConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.CategoryItem;
import com.ktools.zspacecarplayer.model.SongItem;
import com.ktools.zspacecarplayer.util.TextRepair;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class JellyfinApiClient {

    private static final String TAG = "JellyfinApiClient";
    private static final String CLIENT_NAME = "ZSpaceCarPlayer";
    private static final String DEVICE_NAME = "Geely-iMX6-Car";
    private static final String DEVICE_ID = "CAR-IMX6-001";
    private static final String CLIENT_VERSION = "3.2.9";

    /** 鉴权持久化统一走这里, Service 后台静默登录与 Activity 必须读写同一份凭据 */
    public static final String PREF_NAME = "zspace_car_player_prefs";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_USER_ID = "user_id";
    public static final String KEY_ACCESS_TOKEN = "access_token";

    /**
     * 本地开发凭据回退 (2026-09-10): BuildConfig 由项目根 local.properties 注入
     * (该文件已 gitignore, 不进版本库)。填了就用真实值, 没填就用占位符 ——
     * 保证公开仓库里不含任何真实凭据, 本地开发又不必每次手填。
     */
    private static String devOr(String buildConfigValue, String placeholder) {
        return (buildConfigValue == null || buildConfigValue.isEmpty()) ? placeholder : buildConfigValue;
    }

    public static final String DEFAULT_SERVER_URL =
            devOr(BuildConfig.JELLYFIN_SERVER_URL, "http://your-jellyfin.example.com/music");
    public static final String DEFAULT_USERNAME =
            devOr(BuildConfig.JELLYFIN_USERNAME, "your_username");
    public static final String DEFAULT_PASSWORD =
            devOr(BuildConfig.JELLYFIN_PASSWORD, "your_password");

    private static final int PAGE_SIZE = 500;

    // ==================== 2026-09-12 #4: 连接失败可诊断 ====================
    /**
     * 错误类别常量。旧实现无论超时、地址写错、密码错还是服务器 500, 一律只吐
     * "连接失败" 四个字, 用户在车机上下一步该做什么完全无从判断。这里把底层异常
     * 归成有限几类, UI 据此给出短状态文案 + Toast 指引。
     */
    public static final int ERR_UNKNOWN = 0;
    /** 连接/读取超时: 车机断网或服务器地址不通时最常见 */
    public static final int ERR_TIMEOUT = 1;
    /** 401/403: Token 失效或账号密码错误 */
    public static final int ERR_AUTH = 2;
    /** 5xx: Jellyfin 服务端自身故障 */
    public static final int ERR_SERVER = 3;
    /** 其它非 2xx (404 等): 多半是服务器地址路径填错 */
    public static final int ERR_HTTP = 4;
    /** 域名解析失败: 服务器地址写错或车机 DNS 不通 */
    public static final int ERR_DNS = 5;
    /** 网络不可达/连接被拒: 没有路由或 Jellyfin 没在监听 */
    public static final int ERR_UNREACHABLE = 6;
    /** TLS 证书校验失败: Android 4.3 老证书库场景需重点排查 */
    public static final int ERR_TLS = 7;
    /** 其它 IO 异常 (连接中途被重置等) */
    public static final int ERR_NETWORK = 8;
    /** 响应不是预期结构: 服务端版本不匹配或分页参数被忽略 */
    public static final int ERR_PARSE = 9;

    /** 带类别与 HTTP 状态码的网络异常, 让上层能区分「超时 / 鉴权失效 / 服务器错误」 */
    public static class ApiException extends IOException {
        private final int kind;
        private final int httpCode;

        public ApiException(int kind, int httpCode, String message) {
            super(message);
            this.kind = kind;
            this.httpCode = httpCode;
        }

        public int getKind() {
            return kind;
        }

        /** 无 HTTP 响应 (超时/DNS 等) 时为 -1 */
        public int getHttpCode() {
            return httpCode;
        }
    }

    /**
     * 把任意异常归类成 ERR_* 常量。纯函数, 不依赖 Android 框架, 可直接 JVM 单测。
     * 注意判定顺序: 下面这些具体异常都是 IOException 的子类, 必须排在兜底之前。
     */
    public static int classifyError(Throwable t) {
        if (t == null) return ERR_UNKNOWN;
        if (t instanceof ApiException) return ((ApiException) t).getKind();
        // SocketTimeoutException 继承自 InterruptedIOException, 两条都归超时
        if (t instanceof SocketTimeoutException) return ERR_TIMEOUT;
        if (t instanceof InterruptedIOException) return ERR_TIMEOUT;
        if (t instanceof SSLException) return ERR_TLS;
        if (t instanceof CertificateException) return ERR_TLS;
        if (t instanceof UnknownHostException) return ERR_DNS;
        if (t instanceof NoRouteToHostException) return ERR_UNREACHABLE;
        if (t instanceof ConnectException) return ERR_UNREACHABLE;
        if (t instanceof JsonSyntaxException) return ERR_PARSE;
        if (t instanceof IOException) return ERR_NETWORK;
        return ERR_UNKNOWN;
    }

    /** 按 HTTP 状态码归类: 401/403 是鉴权问题, 5xx 是服务端问题, 其余按通用 HTTP 错误 */
    public static ApiException httpError(int code, String what) {
        int kind;
        if (code == 401 || code == 403) {
            kind = ERR_AUTH;
        } else if (code >= 500) {
            kind = ERR_SERVER;
        } else {
            kind = ERR_HTTP;
        }
        return new ApiException(kind, code, what + " HTTP " + code);
    }

    /**
     * 交互刷新 (用户点「刷新」按钮) 专用短超时。默认 client 的 15s 连接 / 20s 读取
     * 是为后台拉大库保健壮性用的, 但离线时每页都要各等一次, 用户会干等十几秒还
     * 看不到任何变化, 误判成「点了没反应」。交互链路改为快速失败。
     */
    private static final int INTERACTIVE_CONNECT_TIMEOUT_S = 5;
    private static final int INTERACTIVE_READ_TIMEOUT_S = 8;
    /** 交互刷新整轮分页的总时限: 到点立即失败回调, 不再让后续页各自去等一次超时 */
    private static final long INTERACTIVE_TOTAL_BUDGET_MS = 12000L;
    /** 分页兜底上限: 服务端忽略 StartIndex 时防止 while 死循环刷爆车机 */
    private static final int MAX_PAGES = 64;

    private static JellyfinApiClient instance;

    private Context appContext;
    private String serverUrl = DEFAULT_SERVER_URL;
    private String accessToken = "";
    private String userId = "";
    private volatile OkHttpClient httpClient;
    /** 交互刷新专用短超时 client, 与 httpClient 共享连接池 (2026-09-12 #4) */
    private volatile OkHttpClient interactiveClient;
    /** 底座只构建一次：预热线程与调用方兜底共用这把锁，不会装配两份 trustmanager */
    private final Object clientLock = new Object();
    /**
     * TLS 底座预热线程。装配 = 系统 trustmanager 枚举 + 内置 GTS 根 PEM 解析 +
     * OkHttp 建 BasicTrustRootIndex (遍历全部内置根证书 DN)，在 8600 上要好几秒。
     * 2026-10-05 实测 5 次 main_thread_blocked 里 4 次栈顶就是它，原先发生在
     * MainActivity.onCreate 里那次同步 init —— 故从主线程挪到这里。
     */
    private static final ExecutorService CLIENT_WARMUP = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tls-warmup");
                    t.setDaemon(true);
                    return t;
                }
            });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 鉴权状态回调: 拿到新 Token 或登录失败时在主线程通知 (Service 用于挂起的自动起播) */
    public interface OnAuthStateListener {
        void onAuthStateChanged(boolean success);
    }

    private OnAuthStateListener authStateListener;

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    private JellyfinApiClient() {
        initHttpClient();
    }

    public static synchronized JellyfinApiClient getInstance() {
        if (instance == null) {
            instance = new JellyfinApiClient();
        }
        return instance;
    }

    /**
     * 在 Application/Activity 最早期调用, 用于读取内置根证书 (res/raw/gts_root_r4)。
     * 未调用时退化为仅系统信任链。
     */
    public synchronized void init(Context context) {
        if (context == null) return;
        this.appContext = context.getApplicationContext();
        // 主线程只负责把装配排上队。真正要用 client 的地方一律走 ensureClient()：
        // 未就绪时在调用方线程同步构建一次 —— 语义与旧实现等价 (旧的必然在调用方线程构建)，
        // 只是从「每次冷启动必卡主线程几秒」降级成「预热没赶上时才兜底」。
        CLIENT_WARMUP.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureClient();
                } catch (Throwable t) {
                    Log.w(TAG, "tls warmup failed", t);
                }
            }
        });
    }

    /**
     * 取可用的 HTTP client。已就绪直接返回；否则在当前线程同步构建一次。
     * 构建耗时与所在线程一并打日志——「预热是否赶上了调用方」只能靠这条判断，
     * 光看有没有卡顿日志分不出来。
     */
    private OkHttpClient ensureClient() {
        OkHttpClient c = httpClient;
        if (c != null) {
            return c;
        }
        synchronized (clientLock) {
            if (httpClient == null) {
                long startedAt = System.currentTimeMillis();
                initHttpClient();
                Log.i(TAG, "tls client built on " + Thread.currentThread().getName()
                        + " in " + (System.currentTimeMillis() - startedAt) + "ms");
            }
            return httpClient;
        }
    }

    /** 交互请求走短超时 client；它随底座一起构建，缺失时退回主 client 而不是再造一份。 */
    private OkHttpClient ensureClient(boolean interactive) {
        OkHttpClient base = ensureClient();
        if (!interactive) {
            return base;
        }
        OkHttpClient i = interactiveClient;
        return i != null ? i : base;
    }

    private void initHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .dns(new IPv6FirstDns());

        try {
            X509TrustManager systemTm = findSystemTrustManager();
            X509TrustManager pinnedTm = buildPinnedTrustManager();
            X509TrustManager composite = new CompositeTrustManager(systemTm, pinnedTm);
            TLSSocketFactory tlsFactory = new TLSSocketFactory(new TrustManager[]{composite});
            builder.sslSocketFactory(tlsFactory, composite);
            // 使用系统默认主机名校验, 不再信任任意主机
            builder.hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        } catch (Exception e) {
            Log.e(TAG, "TLS init failed", e);
        }

        httpClient = builder.build();
        // 交互刷新专用 client (2026-09-12 #4): newBuilder() 复用同一个连接池、派发器
        // 与上面配好的 TLS/证书链, 只覆盖超时, 因此既不影响后台正常拉取, 也不多养线程。
        interactiveClient = httpClient.newBuilder()
                .connectTimeout(INTERACTIVE_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(INTERACTIVE_READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(INTERACTIVE_READ_TIMEOUT_S, TimeUnit.SECONDS)
                .build();
    }

    private static X509TrustManager findSystemTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No system X509TrustManager available");
    }

    /**
     * Android 4.3 的系统证书库太老, 不信任 Google Trust Services 的 GTS Root R4,
     * 因此把该根证书内置到 res/raw, 与系统信任链合并使用。
     */
    private X509TrustManager buildPinnedTrustManager() {
        if (appContext == null) return null;
        InputStream is = null;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            is = appContext.getResources().openRawResource(R.raw.gts_root_r4);
            Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(is);
            ks.setCertificateEntry("gts_root_r4", cert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Pinned root cert unavailable: " + e.getMessage());
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** 系统信任链 + 内置根证书 的组合校验器 */
    private static class CompositeTrustManager implements X509TrustManager {
        private final List<X509TrustManager> managers = new ArrayList<X509TrustManager>();

        CompositeTrustManager(X509TrustManager... tms) {
            for (X509TrustManager tm : tms) {
                if (tm != null) managers.add(tm);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager tm : managers) {
                try {
                    tm.checkClientTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    last = e;
                }
            }
            throw last != null ? last : new CertificateException("No trust managers available");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager tm : managers) {
                try {
                    tm.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    last = e;
                }
            }
            throw last != null ? last : new CertificateException("No trust managers available");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<X509Certificate>();
            for (X509TrustManager tm : managers) {
                X509Certificate[] issuers = tm.getAcceptedIssuers();
                if (issuers != null) {
                    all.addAll(Arrays.asList(issuers));
                }
            }
            return all.toArray(new X509Certificate[all.size()]);
        }
    }

    public void setServerUrl(String url) {
        if (url != null) {
            this.serverUrl = url.replaceAll("/+$", "");
        }
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setAuthInfo(String userId, String token) {
        boolean tokenChanged = token != null && !token.trim().isEmpty() && !token.equals(this.accessToken);
        this.userId = userId != null ? userId : "";
        this.accessToken = token != null ? token : "";
        if (tokenChanged) {
            notifyAuthStateChanged(true);
        }
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getUserId() {
        return userId;
    }

    public boolean hasToken() {
        return accessToken != null && !accessToken.trim().isEmpty();
    }

    /**
     * 丢弃已被服务端判死的 Token (2026-09-12 #4)。
     * 拉库收到 401/403 说明内存里这份 Token 已失效, 不清的话用户每点一次刷新都要
     * 先拿死 Token 撞一轮超时/401 再走重登, 白等一次; 清了以后 hasToken() 立刻为
     * false, 下一次刷新直接进登录流程, 与既有的「空 Token 挂起等待鉴权」契约一致。
     *
     * 这里刻意不广播 onAuthStateChanged(false): AudioPlayerService 收到 false 会直接
     * 判死挂起中的自动起播, 而紧随其后的重新登录本身就会广播真实结果, 无需多此一举。
     */
    public synchronized void clearAuth() {
        if (!hasToken()) return;
        accessToken = "";
        Log.w(TAG, "clearAuth: 失效 Token 已清除, 等待重新登录");
    }

    public void setOnAuthStateListener(OnAuthStateListener listener) {
        this.authStateListener = listener;
    }

    private void notifyAuthStateChanged(final boolean success) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (authStateListener != null) {
                    authStateListener.onAuthStateChanged(success);
                }
            }
        });
    }

    /**
     * 服务端起播前调用: 若客户端尚无 Token, 则从 SharedPreferences 恢复上次保存的会话。
     * @return 恢复后是否已持有 Token
     */
    public boolean restoreAuthFromPrefs(Context context) {
        if (hasToken()) return true;
        if (context == null) return false;
        SharedPreferences sp = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String token = sp.getString(KEY_ACCESS_TOKEN, "");
        if (token == null || token.trim().isEmpty()) return false;
        setAuthInfo(sp.getString(KEY_USER_ID, ""), token);
        return true;
    }

    /** 使用 SharedPreferences 中保存的服务器地址与账号密码静默登录 (Service 后台重鉴权用) */
    public void authenticateFromPrefs(Context context, final ApiCallback<Boolean> callback) {
        Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String url = sp.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
        String username = sp.getString(KEY_USERNAME, DEFAULT_USERNAME);
        String password = sp.getString(KEY_PASSWORD, DEFAULT_PASSWORD);
        authenticate(url, username, password, callback);
    }

    private boolean authInProgress = false;
    private final List<ApiCallback<Boolean>> pendingAuthCallbacks = new ArrayList<ApiCallback<Boolean>>();

    /** 兼容旧签名: Service 后台重鉴权走健壮超时 (2026-09-12 #4 之前的唯一入口) */
    public void authenticate(final String url, final String username, final String password, final ApiCallback<Boolean> callback) {
        authenticate(url, username, password, false, callback);
    }

    /**
     * 登录单飞 (single-flight): 同一时刻只允许一次 AuthenticateByName 请求。
     * Jellyfin 对同一 DeviceId 的重复登录会吊销上一个 Token, 若 Activity 与 Service
     * 并发登录, 正在分页拉取媒体库的请求会因 Token 被吊销而中途 401。
     * 并发调用方一律挂到第一次登录的结果上 (车载场景单用户, 不区分凭据差异)。
     *
     * @param interactive true = 用户点「刷新」触发的重登, 走短超时 client 快速失败;
     *                    false = Service 后台重鉴权, 保持原有 15s/20s 的健壮性。
     */
    public void authenticate(final String url, final String username, final String password,
                             final boolean interactive, final ApiCallback<Boolean> callback) {
        synchronized (this) {
            if (authInProgress) {
                Log.i(TAG, "authenticate: 已有登录在飞, 挂到同一结果上 (interactive=" + interactive + ")");
                if (callback != null) pendingAuthCallbacks.add(callback);
                return;
            }
            authInProgress = true;
            if (callback != null) pendingAuthCallbacks.add(callback);
        }

        setServerUrl(url);

        JsonObject json = new JsonObject();
        json.addProperty("Username", username);
        json.addProperty("Pw", password);

        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json.toString());

        Request request = new Request.Builder()
                .url(serverUrl + "/Users/AuthenticateByName")
                .addHeader("X-Emby-Authorization", buildAuthHeader())
                .post(body)
                .build();

        OkHttpClient client = ensureClient(interactive);
        Log.i(TAG, "authenticate: start user=" + username + " interactive=" + interactive);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                Log.w(TAG, "authenticate: 网络失败 kind=" + classifyError(e) + " " + e);
                finishAuth(false, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    final int code = response.code();
                    response.close();
                    // 用带状态码的 ApiException, 上层才能把 401(账号密码错) 与 5xx(服务端故障)
                    // 分成两条不同的用户提示, 而不是统一一句"连接失败"
                    Log.w(TAG, "authenticate: 服务端拒绝 HTTP " + code);
                    finishAuth(false, httpError(code, "Auth failed:"));
                    return;
                }

                try {
                    ResponseBody respBody = response.body();
                    String respStr = respBody != null ? respBody.string() : "";
                    JsonObject respJson = new JsonParser().parse(respStr).getAsJsonObject();
                    accessToken = respJson.get("AccessToken").getAsString();
                    userId = respJson.getAsJsonObject("User").get("Id").getAsString();
                    Log.i(TAG, "authenticate: 登录成功 userId=" + userId);
                    finishAuth(true, null);
                } catch (final Exception e) {
                    Log.w(TAG, "authenticate: 响应解析失败 " + e);
                    finishAuth(false, e);
                } finally {
                    response.close();
                }
            }
        });
    }

    /** 登录终结: 释放单飞锁, 把同一结果分发给所有挂起的调用方, 并广播鉴权状态 */
    private void finishAuth(final boolean success, final Exception error) {
        final List<ApiCallback<Boolean>> toDeliver = new ArrayList<ApiCallback<Boolean>>();
        synchronized (this) {
            authInProgress = false;
            toDeliver.addAll(pendingAuthCallbacks);
            pendingAuthCallbacks.clear();
        }
        notifyAuthStateChanged(success);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (ApiCallback<Boolean> cb : toDeliver) {
                    if (success) {
                        cb.onSuccess(true);
                    } else {
                        cb.onError(error);
                    }
                }
            }
        });
    }

    public String getStreamUrl(String itemId) {
        return getStreamUrl(itemId, true);
    }

    public String getStreamUrl(String itemId, boolean directPlay) {
        if (itemId == null || itemId.isEmpty()) return "";
        if (accessToken == null || accessToken.isEmpty()) return "";
        String base = serverUrl + "/Audio/" + itemId + "/stream.mp3?api_key=" + accessToken;
        return directPlay ? (base + "&static=true") : base;
    }

    /**
     * 服务端转码 FLAC 的字节率阈值 (B/s)，只对非 PCM 容器生效。
     *
     * 车机蜂窝链路实测有效吞吐约 250~300KB/s。未压缩 PCM 的字节率与声道数成正比：
     * 44.1kHz/7ch 要 616KB/s (2026-09-22「答案」现场：下载头只能领先播放头 2s，全程
     * underrun)；连立体声也要 176KB/s，几乎贴着链路能力跑，没有余量（2026-09-23「出山」
     * 现场：17 秒内缓冲百分比只涨 1%，lead 卡在 10s，用户因此点了上报）。所以 PCM 系
     * 容器无论声道数一律转，超阈值的其它源也转。
     */
    private static final long SERVER_TRANSCODE_BYTES_PER_SEC = 300_000L;

    /**
     * 未压缩 PCM 系容器：无损但字节率极高，FLAC 再压缩仍是无损。
     * 压缩比实测约七成（44.1kHz/2ch/16bit 流行曲 42,468,094B → 30,349,615B，
     * 2026-10-06 在 NAS 容器内 flac -8 试转并裸 PCM 逐字节比对确认为无损），
     * 不是早先注释写的"四成"。
     */
    private static final String[] UNCOMPRESSED_CONTAINERS = {
            "wav", "wave", "pcm", "aif", "aiff", "aifc", "raw", "sun", "au"
    };

    /** 缓存 stream_url 里辨识「该曲走服务端 FLAC」的标记，冷启动读缓存时据此复原决策。 */
    private static final String TRANSCODE_URL_MARKER = "audioCodec=flac";

    private static final String DOWNMIX_QUERY = "&audioChannels=2";

    /**
     * 由 MediaSources 的 Size 与 RunTimeTicks 求源码字节率 (B/s)，取不到回 -1。
     *
     * 不能用 MediaSources[0].BitRate：本机 Jellyfin 在列表接口返回的 MediaSources 里
     * 根本不带这个字段（实测 801 首全缺，只有嵌套 MediaStreams 里有），而 Size 与
     * RunTimeTicks 一定在，两者相除即精确字节率。此前按 BitRate 判定的版本因此从未
     * 触发过转码。
     */
    public static long sourceBytesPerSec(long sizeBytes, long runTimeTicks) {
        if (sizeBytes <= 0 || runTimeTicks <= 0) return -1L;
        double seconds = runTimeTicks / 10_000_000.0d;
        if (seconds <= 0) return -1L;
        return Math.round(sizeBytes / seconds);
    }

    /**
     * 该源是否该由服务端出 FLAC 再传。
     *
     * 判据收口成纯函数：车机上唯一可观测的是「下载领先量」，而它由字节率与链路吞吐之差
     * 决定，用真机回归撞阈值代价极高，故边界由 host 单测钉死。
     */
    public static boolean shouldUseServerFlac(long bytesPerSec, String container) {
        if (container != null) {
            String c = container.toLowerCase(java.util.Locale.US);
            for (String pcm : UNCOMPRESSED_CONTAINERS) {
                if (c.equals(pcm) || c.startsWith(pcm + ",")) {
                    return true;
                }
            }
        }
        return bytesPerSec > SERVER_TRANSCODE_BYTES_PER_SEC;
    }

    /** 字节率高到只可能是多声道母带，顺带让服务端下混到两声道。 */
    public static boolean shouldDownmixToStereo(long bytesPerSec) {
        return bytesPerSec > SERVER_TRANSCODE_BYTES_PER_SEC;
    }

    /** 缓存 stream_url 是否走服务端 FLAC（冷启动读缓存时据此复原决策）。 */
    public static boolean isServerFlacUrl(String streamUrl) {
        return streamUrl != null && streamUrl.contains(TRANSCODE_URL_MARKER);
    }

    /**
     * 服务端出 FLAC 再传。
     *
     * 无损：flac 只做无损压缩，不做有损量化，音质零变化；下混到两声道的口径与 v3 客户端
     * 的 6ch/7ch->2ch 一致，听感不变而传输字节降到约 101KB/s。车机 v3 的 sniff 认 fLaC
     * 头并由 dr_flac 硬核软解，不会掉回系统 MediaExtractor（8600 缺 FLAC 解码，正是它抛
     * "Failed to instantiate extractor" 的那条路）。
     */
    public String getFlacStreamUrl(String itemId, boolean downmixToStereo) {
        if (itemId == null || itemId.isEmpty()) return "";
        if (accessToken == null || accessToken.isEmpty()) return "";
        return serverUrl + "/Audio/" + itemId + "/stream.flac?api_key=" + accessToken
                + "&static=false&audioCodec=flac"
                + (downmixToStereo ? DOWNMIX_QUERY : "");
    }

    /**
     * 链路撑不住无损时的**流畅档**：让服务端按 128kbps 出 MP3（8600 的 MediaCodec 原生支持），
     * 约 16 KB/s，远低于实测最差链路（2026-10-06 真车 67 KB/s）。
     *
     * <p>这不是"换个编码再无损"：它是**有损**降级，只在 {@code StreamRateGovernor} 判定链路持续
     * 追不上无损码率时才用。DSP 不受影响——降级后走系统 MediaCodec 分支，而
     * {@code DspAudioTrackPlayer} 在该分支里同样调 {@code NativeDsp.processBytes}，EQ/Bass/声场/混响保留。
     */
    /** 流畅档目标码率（bps）：约 16 KB/s。刻意低于实测最差链路一个数量级。 */
    public static final long DEGRADED_TARGET_BITRATE = 128_000L;

    public String getDegradedStreamUrl(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "";
        if (accessToken == null || accessToken.isEmpty()) return "";
        return serverUrl + "/Audio/" + itemId + "/stream.mp3?api_key=" + accessToken
                + "&static=false&maxStreamingBitrate=" + DEGRADED_TARGET_BITRATE;
    }

    /**
     * 起播/预取用的 URL：沿用该曲入库时定下的传输方式，同时现取当前 access token。
     *
     * 不能无条件走直传：token 轮换会让缓存 URL 作废，但传输方式必须由缓存决定，
     * 否则重启后高字节率源又回到喂不上的直传路径。
     */
    public String getStreamUrlForSong(String itemId, String cachedStreamUrl) {
        if (isServerFlacUrl(cachedStreamUrl)) {
            return getFlacStreamUrl(itemId, cachedStreamUrl.contains(DOWNMIX_QUERY));
        }
        return getStreamUrl(itemId, true);
    }

    private String buildAuthHeader() {
        return "MediaBrowser Client=\"" + CLIENT_NAME + "\", Device=\"" + DEVICE_NAME +
                "\", DeviceId=\"" + DEVICE_ID + "\", Version=\"" + CLIENT_VERSION + "\"" +
                (accessToken != null && !accessToken.isEmpty() ? ", Token=\"" + accessToken + "\"" : "");
    }

    /**
     * 分页拉取全部音频条目 (按名称排序), 避免一次性解析超大 JSON 卡死老车机。
     * 分页参数必须用 StartIndex (Jellyfin 会静默忽略未知的 Start 参数, 导致第二页
     * 永远返回第一页数据, 887 首被计成 1000); URL 附带 cb 时间戳击穿 CDN 缓存,
     * 并按条目 Id 全局去重兜底。
     *
     * 兼容旧签名: 启动/后台拉取走健壮超时。
     */
    public void fetchMusicItems(final ApiCallback<List<SongItem>> callback) {
        fetchMusicItems(callback, false);
    }

    /**
     * @param interactive true = 用户点「刷新」按钮触发的交互请求: 换短超时 client,
     *                    并对整轮分页施加 {@link #INTERACTIVE_TOTAL_BUDGET_MS} 总时限,
     *                    让失败在数秒内可见; false = 启动/后台拉取, 保持 15s/20s 的健壮性。
     */
    public void fetchMusicItems(final ApiCallback<List<SongItem>> callback, final boolean interactive) {
        // 交互刷新的整轮时限: 到点即放弃, 不让后面的页各自再去等一次连接超时
        final long deadlineMs = interactive
                ? System.currentTimeMillis() + INTERACTIVE_TOTAL_BUDGET_MS
                : Long.MAX_VALUE;
        final OkHttpClient client = ensureClient(interactive);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Map<String, SongItem> uniqueSongs = new LinkedHashMap<String, SongItem>();
                int page = 0;
                try {
                    int start = 0;
                    int total = -1;
                    while (true) {
                        if (System.currentTimeMillis() > deadlineMs) {
                            throw new ApiException(ERR_TIMEOUT, -1,
                                    "fetchMusicItems 超出交互总时限 " + INTERACTIVE_TOTAL_BUDGET_MS
                                            + "ms, 已拉 page=" + page);
                        }
                        // 兜底: 服务端若忽略 StartIndex 会永远返回同一页, pageCount 恒为 PAGE_SIZE,
                        // 没有上限就是死循环刷爆车机流量
                        if (page >= MAX_PAGES) {
                            throw new ApiException(ERR_PARSE, -1,
                                    "fetchMusicItems 分页超过上限 " + MAX_PAGES + " 页, 疑似 StartIndex 被忽略");
                        }

                        String endpoint = serverUrl + "/Users/" + userId + "/Items"
                                + "?IncludeItemTypes=Audio&Recursive=true&Fields=MediaSources,ParentId,Path,Genres,GenreItems"
                                + "&SortBy=SortName&SortOrder=Ascending"
                                + "&StartIndex=" + start + "&Limit=" + PAGE_SIZE
                                + "&cb=" + System.currentTimeMillis();

                        Request request = new Request.Builder()
                                .url(endpoint)
                                .addHeader("X-Emby-Authorization", buildAuthHeader())
                                .get()
                                .build();

                        Log.i(TAG, "fetchMusicItems page=" + page + " startIndex=" + start
                                + " interactive=" + interactive);
                        Response response = client.newCall(request).execute();
                        int pageCount;
                        try {
                            if (!response.isSuccessful()) {
                                // 任一页失败必须立刻抛出、中断整个 while 循环并回调 onError:
                                // 否则会一页页各自再等一次 15s 连接超时, 用户点刷新后要干等
                                // 十几秒 ×N 才看到失败, 观感就是「点了没反应」。
                                // 用带状态码的 ApiException, 上层才能把 401 (Token 失效, 该清
                                // Token 重登) 与 5xx (服务端故障, 只需重试) 分开处理。
                                throw httpError(response.code(), "Fetch items failed:");
                            }
                            ResponseBody respBody = response.body();
                            String respStr = respBody != null ? respBody.string() : "";
                            JsonObject rootJson = new JsonParser().parse(respStr).getAsJsonObject();
                            if (total < 0 && rootJson.has("TotalRecordCount")) {
                                total = rootJson.get("TotalRecordCount").getAsInt();
                            }
                            JsonArray items = rootJson.getAsJsonArray("Items");
                            pageCount = items != null ? items.size() : 0;
                            if (items != null) {
                                for (JsonElement el : items) {
                                    JsonObject itemObj = el.getAsJsonObject();
                                    SongItem song = parseSongItem(itemObj);
                                    if (song != null) {
                                        uniqueSongs.put(song.getId(), song);
                                    }
                                }
                            }
                        } finally {
                            response.close();
                        }

                        page++;
                        start += pageCount;
                        if (pageCount == 0) break;
                        if (total >= 0 && start >= total) break;
                        if (total < 0 && pageCount < PAGE_SIZE) break;
                    }

                    final List<SongItem> songList = new ArrayList<SongItem>(uniqueSongs.values());
                    Log.i(TAG, "fetchMusicItems done: songs=" + songList.size()
                            + " pages=" + page + " interactive=" + interactive);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(songList);
                        }
                    });
                } catch (final Exception e) {
                    // 实车抓日志的关键锚点: kind 直接对应 UI 上给出的那一句失败原因
                    Log.w(TAG, "fetchMusicItems error kind=" + classifyError(e)
                            + " page=" + page + " interactive=" + interactive + " msg=" + e, e);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e);
                        }
                    });
                }
            }
        }, "jf-fetch-items").start();
    }

    /** 包内可见: 传输方式裁定是否正确落到 SongItem 上, 由单测端到端钉住 (见 StreamTransportWiringTest) */
    SongItem parseSongItem(JsonObject itemObj) {
        if (itemObj == null || !itemObj.has("Id")) return null;
        String itemId = itemObj.get("Id").getAsString();
        String name = itemObj.has("Name") ? itemObj.get("Name").getAsString() : "未知曲目";

        String artist = "未知歌手";
        if (itemObj.has("Artists") && itemObj.getAsJsonArray("Artists").size() > 0) {
            artist = itemObj.getAsJsonArray("Artists").get(0).getAsString();
        } else if (itemObj.has("AlbumArtist")) {
            artist = itemObj.get("AlbumArtist").getAsString();
        }

        String album = itemObj.has("Album") ? itemObj.get("Album").getAsString() : "未知专辑";
        
        String genre = "未分类";
        if (itemObj.has("Genres") && itemObj.getAsJsonArray("Genres").size() > 0) {
            genre = itemObj.getAsJsonArray("Genres").get(0).getAsString();
        } else if (itemObj.has("GenreItems") && itemObj.getAsJsonArray("GenreItems").size() > 0) {
            genre = itemObj.getAsJsonArray("GenreItems").get(0).getAsJsonObject().get("Name").getAsString();
        }

        String folderName = "未分类文件夹";
        if (itemObj.has("Path")) {
            String pathStr = itemObj.get("Path").getAsString();
            if (pathStr != null && !pathStr.trim().isEmpty()) {
                String normalized = pathStr.replace('\\', '/');
                int lastSlash = normalized.lastIndexOf('/');
                if (lastSlash > 0) {
                    String parentPath = normalized.substring(0, lastSlash);
                    int prevSlash = parentPath.lastIndexOf('/');
                    if (prevSlash >= 0) {
                        folderName = parentPath.substring(prevSlash + 1);
                    } else {
                        folderName = parentPath;
                    }
                }
            }
        }

        long durationMs = 0;
        if (itemObj.has("RunTimeTicks")) {
            durationMs = itemObj.get("RunTimeTicks").getAsLong() / 10000;
        }

        // 源码率取自 MediaSources (请求已带 Fields=MediaSources)。只用于裁定传输方式，
        // 不入 SongItem 字段：裁定结果写进 stream_url 本身，冷启动读缓存时靠标记复原，免建 DB 迁移。
        // 字节率取自 Size/时长：列表接口的 MediaSources 不带 BitRate (实测恒缺)，
        // 而 Container 与 Size 一定在。只用于裁定传输方式，不入 SongItem 字段：
        // 裁定结果写进 stream_url 本身，冷启动读缓存时按标记复原，免建 DB 迁移。
        long sourceSizeBytes = 0;
        String sourceContainer = null;
        if (itemObj.has("MediaSources") && itemObj.get("MediaSources").isJsonArray()) {
            JsonArray sources = itemObj.getAsJsonArray("MediaSources");
            if (sources.size() > 0 && sources.get(0).isJsonObject()) {
                JsonObject src = sources.get(0).getAsJsonObject();
                if (src.has("Size") && !src.get("Size").isJsonNull()) {
                    sourceSizeBytes = src.get("Size").getAsLong();
                }
                if (src.has("Container") && !src.get("Container").isJsonNull()) {
                    sourceContainer = src.get("Container").getAsString();
                }
            }
        }
        long sourceBytesPerSec = sourceBytesPerSec(sourceSizeBytes, durationMs * 10_000L);

        boolean isFav = false;
        if (itemObj.has("UserData") && itemObj.getAsJsonObject("UserData").has("IsFavorite")) {
            isFav = itemObj.getAsJsonObject("UserData").get("IsFavorite").getAsBoolean();
        }

        // 裁定结果必须直接进构造参数。这里曾经先算进一个局部变量、下一行却仍传
        // getStreamUrl(itemId)——于是 PCM 走服务端 FLAC、多声道下混两条规则从 v3.1.2
        // 到 v3.1.5 所有已发布版本全部空转 (2026-09-24 核对 67 条真车上报, 无一条
        // audioCodec=flac)。写成单个表达式, 结构上就不存在"算了但忘了用"。
        return new SongItem(itemId, TextRepair.repair(name), TextRepair.repair(artist),
                TextRepair.repair(album), TextRepair.repair(genre), TextRepair.repair(folderName),
                durationMs,
                shouldUseServerFlac(sourceBytesPerSec, sourceContainer)
                        ? getFlacStreamUrl(itemId, shouldDownmixToStereo(sourceBytesPerSec))
                        : getStreamUrl(itemId, true),
                isFav);
    }

    /**
     * 拉取歌词: 优先 Jellyfin 10.9+ 官方歌词接口 GET /Lyrics/{itemId},
     * 失败再回退旧接口 GET /Audio/{itemId}/Lyrics; 两种响应结构都兼容解析。
     */
    public void fetchLyrics(String itemId, final ApiCallback<String> callback) {
        requestLyricsEndpoint(serverUrl + "/Lyrics/" + itemId, itemId, callback, true);
    }

    private void requestLyricsEndpoint(String endpoint, final String itemId, final ApiCallback<String> callback, final boolean canFallback) {
        Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("X-Emby-Authorization", buildAuthHeader())
                .get()
                .build();

        ensureClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (canFallback) {
                    requestLyricsEndpoint(serverUrl + "/Audio/" + itemId + "/Lyrics", itemId, callback, false);
                } else {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(""); // 歌词不存在返回空串
                        }
                    });
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    if (canFallback) {
                        requestLyricsEndpoint(serverUrl + "/Audio/" + itemId + "/Lyrics", itemId, callback, false);
                    } else {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess("");
                            }
                        });
                    }
                    return;
                }

                try {
                    ResponseBody respBody = response.body();
                    String respStr = respBody != null ? respBody.string() : "";
                    final String lrcText = parseLyricsJson(respStr);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(lrcText);
                        }
                    });
                } catch (final Exception e) {
                    if (canFallback) {
                        requestLyricsEndpoint(serverUrl + "/Audio/" + itemId + "/Lyrics", itemId, callback, false);
                    } else {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess("");
                            }
                        });
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    /** 兼容两种歌词响应: 顶层 Lyrics 数组, 或 {Lyrics:{Metadata, Lyrics:[...]}} 嵌套结构 */
    private String parseLyricsJson(String respStr) throws Exception {
        JsonObject json = new JsonParser().parse(respStr).getAsJsonObject();
        JsonElement lyricsEl = json.get("Lyrics");
        JsonArray lines = null;
        if (lyricsEl != null) {
            if (lyricsEl.isJsonArray()) {
                lines = lyricsEl.getAsJsonArray();
            } else if (lyricsEl.isJsonObject()) {
                JsonObject inner = lyricsEl.getAsJsonObject();
                if (inner.has("Lyrics") && inner.get("Lyrics").isJsonArray()) {
                    lines = inner.getAsJsonArray("Lyrics");
                }
            }
        }
        if (lines == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonElement elem : lines) {
            JsonObject lineObj = elem.getAsJsonObject();
            long startTicks = lineObj.has("Start") ? lineObj.get("Start").getAsLong() : 0;
            String text = lineObj.has("Text") ? lineObj.get("Text").getAsString() : "";
            long totalMs = startTicks / 10000;
            long min = totalMs / 60000;
            long sec = (totalMs % 60000) / 1000;
            long ms = (totalMs % 1000) / 10;
            sb.append(String.format("[%02d:%02d.%02d]%s\n", min, sec, ms, text));
        }
        return sb.toString();
    }

    /**
     * 标记/取消标记 Jellyfin 红心收藏曲目
     */
    public void toggleFavorite(String itemId, final boolean isFav, final ApiCallback<Boolean> callback) {
        String endpoint = serverUrl + "/Users/" + userId + "/FavoriteItems/" + itemId;
        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .addHeader("X-Emby-Authorization", buildAuthHeader());

        if (isFav) {
            builder.post(RequestBody.create(MediaType.parse("application/json"), ""));
        } else {
            builder.delete();
        }

        ensureClient().newCall(builder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onError(e);
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onSuccess(true);
                    }
                });
            }
        });
    }
}
