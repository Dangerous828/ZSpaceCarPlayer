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
import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.CategoryItem;
import com.ktools.zspacecarplayer.model.SongItem;
import com.ktools.zspacecarplayer.util.TextRepair;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
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
    private static final String CLIENT_VERSION = "3.0.0";

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

    private static JellyfinApiClient instance;

    private Context appContext;
    private String serverUrl = DEFAULT_SERVER_URL;
    private String accessToken = "";
    private String userId = "";
    private OkHttpClient httpClient;
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
        initHttpClient();
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

    /**
     * 登录单飞 (single-flight): 同一时刻只允许一次 AuthenticateByName 请求。
     * Jellyfin 对同一 DeviceId 的重复登录会吊销上一个 Token, 若 Activity 与 Service
     * 并发登录, 正在分页拉取媒体库的请求会因 Token 被吊销而中途 401。
     * 并发调用方一律挂到第一次登录的结果上 (车载场景单用户, 不区分凭据差异)。
     */
    public void authenticate(final String url, final String username, final String password, final ApiCallback<Boolean> callback) {
        synchronized (this) {
            if (authInProgress) {
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

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                finishAuth(false, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    final int code = response.code();
                    response.close();
                    finishAuth(false, new Exception("HTTP Auth failed: " + code));
                    return;
                }

                try {
                    ResponseBody respBody = response.body();
                    String respStr = respBody != null ? respBody.string() : "";
                    JsonObject respJson = new JsonParser().parse(respStr).getAsJsonObject();
                    accessToken = respJson.get("AccessToken").getAsString();
                    userId = respJson.getAsJsonObject("User").get("Id").getAsString();
                    finishAuth(true, null);
                } catch (final Exception e) {
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

    public String getCoverUrl(String itemId) {
        return serverUrl + "/Items/" + itemId + "/Images/Primary?quality=90";
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
     */
    public void fetchMusicItems(final ApiCallback<List<SongItem>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Map<String, SongItem> uniqueSongs = new LinkedHashMap<String, SongItem>();
                try {
                    int start = 0;
                    int total = -1;
                    while (true) {
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

                        Response response = httpClient.newCall(request).execute();
                        int pageCount;
                        try {
                            if (!response.isSuccessful()) {
                                throw new IOException("Fetch items failed: " + response.code());
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

                        start += pageCount;
                        if (pageCount == 0) break;
                        if (total >= 0 && start >= total) break;
                        if (total < 0 && pageCount < PAGE_SIZE) break;
                    }

                    final List<SongItem> songList = new ArrayList<SongItem>(uniqueSongs.values());
                    Log.d(TAG, "fetchMusicItems done: songs=" + songList.size());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(songList);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "fetchMusicItems error", e);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e);
                        }
                    });
                }
            }
        }).start();
    }

    private SongItem parseSongItem(JsonObject itemObj) {
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

        boolean isFav = false;
        if (itemObj.has("UserData") && itemObj.getAsJsonObject("UserData").has("IsFavorite")) {
            isFav = itemObj.getAsJsonObject("UserData").get("IsFavorite").getAsBoolean();
        }

        return new SongItem(itemId, TextRepair.repair(name), TextRepair.repair(artist),
                TextRepair.repair(album), TextRepair.repair(genre), TextRepair.repair(folderName),
                durationMs, getStreamUrl(itemId), getCoverUrl(itemId), isFav);
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

        httpClient.newCall(request).enqueue(new Callback() {
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

        httpClient.newCall(builder.build()).enqueue(new Callback() {
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
