package com.ktools.zspacecarplayer.crash;

import android.content.Context;
import android.util.Log;

import com.ktools.zspacecarplayer.net.IPv6FirstDns;
import com.ktools.zspacecarplayer.net.TlsCompat;

import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 把崩溃报告 POST 到云端收集端点。
 *
 * 复用 JellyfinApiClient 同一套网络底座（OkHttp 3.12 + TLSSocketFactory +
 * IPv6FirstDns），因为它已经在吉利 8600 / Android 4.3 上跑通公网串流：
 * API 18 的 HttpsURLConnection 默认不开 TLS 1.2，裸用会在握手阶段就失败。
 *
 * 只在后台线程调用；失败不抛给调用方之外的任何地方，报告留在本地等下次启动重试。
 */
public final class CrashUploader {

    private static final String TAG = "CrashUploader";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    /** 单份报告体积上限（含 logcat 与线程快照）。超出直接丢弃，别把车机 4G 流量吃光。 */
    static final int MAX_BODY_BYTES = 512 * 1024;
    /** ack 判定只需前若干字符，避免把反代的整张 HTML 错误页拉回车机 */
    private static final int MAX_ACK_CHARS = 256;
    /** 处于键位置的 ok 字段。ack 体极短，正则开销可忽略 */
    private static final java.util.regex.Pattern OK_FIELD =
            java.util.regex.Pattern.compile("\"ok\"\\s*:");

    private final Context context;
    private final String endpoint;
    private volatile OkHttpClient client;

    public CrashUploader(String endpoint) {
        this(null, endpoint);
    }

    public CrashUploader(Context context, String endpoint) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.endpoint = endpoint;
    }

    public String getEndpoint() {
        return endpoint;
    }

    /**
     * 上传一份报告。
     *
     * @return true 表示服务端已确认收下，调用方可以删除本地文件
     */
    public boolean upload(String jsonBody) {
        if (endpoint == null || endpoint.length() == 0 || jsonBody == null) {
            return false;
        }
        if (jsonBody.length() > MAX_BODY_BYTES) {
            Log.w(TAG, "report too large (" + jsonBody.length() + " chars), dropping");
            return true; // 返回 true 让调用方删文件：这份永远传不上去，留着只会占位
        }
        Response response = null;
        try {
            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "ZSpaceCarPlayer-crash-reporter")
                    .post(RequestBody.create(JSON, jsonBody))
                    .build();
            response = ensureClient().newCall(request).execute();
            int code = response.code();
            if (code >= 300 && code < 400) {
                Log.w(TAG, "crash endpoint redirected (HTTP " + code + " -> "
                        + response.header("Location") + "), keeping report local");
                return false;
            }
            if (code >= 200 && code < 300) {
                String ack = readAck(response);
                if (isIngestAck(response.header("Content-Type"), ack)) {
                    Log.i(TAG, "crash report accepted: " + code + " ack=" + ack);
                    return true;
                }
                Log.w(TAG, "crash endpoint returned HTTP " + code
                        + " but no ingest ack (content-type="
                        + response.header("Content-Type") + "), keeping report local");
                return false;
            }
            Log.w(TAG, "crash endpoint rejected report: HTTP " + code);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "crash upload failed: " + t);
            return false;
        } finally {
            if (response != null) {
                try {
                    response.body().close();
                } catch (Throwable ignored) {}
            }
        }
    }

    /** ack 只有几十字节，读多了纯属浪费车机流量；截断即可判定 */
    private static String readAck(Response response) {
        try {
            String body = response.body().string();
            if (body == null) {
                return "";
            }
            return body.length() > MAX_ACK_CHARS ? body.substring(0, MAX_ACK_CHARS) : body;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 收件的铁证：JSON 且带 ok 字段。
     *
     * 只看状态码会静默丢数据——端点若被反代 301 到 https，OkHttp 跟随跳转时会把
     * POST 降级成 GET，静态兜底页对 GET 回 200 text/html，于是"上传成功"、本地
     * 报告被删，一份崩溃都没收到，日志里却全是好消息。宁可判定失败留在本地。
     *
     * ok 必须出现在键的位置：{"status":"ok"} 是业务状态，不是收件确认，
     * 用裸 contains 会把它当成 ack。
     */
    static boolean isIngestAck(String contentType, String body) {
        if (contentType == null || body == null) {
            return false;
        }
        return contentType.contains("application/json") && OK_FIELD.matcher(body).find();
    }

    private OkHttpClient ensureClient() {
        OkHttpClient c = client;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (client == null) {
                client = buildClient();
            }
            return client;
        }
    }

    private OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                // 绝不跟随跳转：OkHttp 跟 301/302 时会丢掉 POST body 并降级成 GET，
                // 反代的静态兜底页对 GET 回 200，报告就被当成"已收下"删掉了
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(new IPv6FirstDns());
        TlsCompat.configureTls(builder, context);
        return builder.build();
    }
}
