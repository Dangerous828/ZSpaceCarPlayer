package com.ktools.zspacecarplayer.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.SongItem;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private static final String CLIENT_VERSION = "1.0.0";

    private static final int PAGE_SIZE = 500;

    private static JellyfinApiClient instance;

    private Context appContext;
    private String serverUrl = "http://your-jellyfin.example.com/music";
    private String accessToken = "";
    private String userId = "";
    private OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
     * 在 Application/Activity 最早期调用, 用于读取内置根证书 (res/raw/isrg_root_x1)。
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
     * Android 4.3 的系统证书库太老, 不信任 Let's Encrypt 的 ISRG Root X1,
     * 因此把该根证书内置到 res/raw, 与系统信任链合并使用。
     */
    private X509TrustManager buildPinnedTrustManager() {
        if (appContext == null) return null;
        InputStream is = null;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            is = appContext.getResources().openRawResource(R.raw.isrg_root_x1);
            Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(is);
            ks.setCertificateEntry("isrg_root_x1", cert);

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
        this.userId = userId;
        this.accessToken = token;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getUserId() {
        return userId;
    }

    public String getStreamUrl(String itemId) {
        return serverUrl + "/Audio/" + itemId + "/stream.mp3?api_key=" + accessToken + "&static=true";
    }

    public String getCoverUrl(String itemId) {
        return serverUrl + "/Items/" + itemId + "/Images/Primary?quality=90";
    }

    private String buildAuthHeader() {
        return "MediaBrowser Client=\"" + CLIENT_NAME + "\", Device=\"" + DEVICE_NAME +
                "\", DeviceId=\"" + DEVICE_ID + "\", Version=\"" + CLIENT_VERSION + "\"" +
                (accessToken != null && !accessToken.isEmpty() ? ", Token=\"" + accessToken + "\"" : "");
    }

    public void authenticate(String url, String username, String password, final ApiCallback<Boolean> callback) {
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
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError(e);
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    final int code = response.code();
                    response.close();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(new Exception("HTTP Auth failed: " + code));
                        }
                    });
                    return;
                }

                try {
                    ResponseBody respBody = response.body();
                    String respStr = respBody != null ? respBody.string() : "";
                    JsonObject respJson = new JsonParser().parse(respStr).getAsJsonObject();
                    accessToken = respJson.get("AccessToken").getAsString();
                    userId = respJson.getAsJsonObject("User").get("Id").getAsString();

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(true);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e);
                        }
                    });
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * 分页拉取全部音频条目 (按名称排序), 避免一次性解析超大 JSON 卡死老车机。
     */
    public void fetchMusicItems(final ApiCallback<List<SongItem>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SongItem> songList = new ArrayList<SongItem>();
                try {
                    int start = 0;
                    int total = -1;
                    while (true) {
                        String endpoint = serverUrl + "/Users/" + userId + "/Items"
                                + "?IncludeItemTypes=Audio&Recursive=true&Fields=MediaSources,ParentId"
                                + "&SortBy=SortName&SortOrder=Ascending"
                                + "&Start=" + start + "&Limit=" + PAGE_SIZE;

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
                                        songList.add(song);
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

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(songList);
                        }
                    });
                } catch (final Exception e) {
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
        long durationMs = 0;
        if (itemObj.has("RunTimeTicks")) {
            durationMs = itemObj.get("RunTimeTicks").getAsLong() / 10000;
        }

        return new SongItem(itemId, name, artist, album, durationMs, getStreamUrl(itemId), getCoverUrl(itemId));
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
}
