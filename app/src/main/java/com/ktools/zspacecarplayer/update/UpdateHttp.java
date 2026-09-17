package com.ktools.zspacecarplayer.update;

import android.content.Context;

import com.ktools.zspacecarplayer.net.IPv6FirstDns;
import com.ktools.zspacecarplayer.net.TlsCompat;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 远程升级 (2026-09-12): 清单拉取与 APK 下载共用的 OkHttp 装配。
 *
 * 为什么单独抽一个类而不是各自 new OkHttpClient:
 *  1. API 18 (吉利 8600 车机) 的 HttpsURLConnection 默认不开 TLS 1.2, 裸建 client 会在
 *     握手阶段就失败 —— 必须套 TLSSocketFactory 与 TlsCompat;
 *  2. Android 4.3 的系统证书库不信任 Google Trust Services 根 (清单域名
 *     web.kentonnie.top 的证书正是 GTS WE1 → GTS Root R4 签发), 因此由 TlsCompat 统一把
 *     res/raw/gts_root_r4 也作为额外信任锚合并进来;
 *  3. 两个调用方只差超时与是否跟随跳转, 复制两份 TLS 代码迟早走样。
 *
 * 只「增加」信任锚, 绝不放宽主机名校验 (仍用系统默认 HostnameVerifier)。
 */
final class UpdateHttp {

    private UpdateHttp() {}

    /**
     * @param connectTimeoutS 连接超时秒
     * @param readTimeoutS    读超时秒 (下载大文件时是「两次读之间」的上限, 不是总时长)
     * @param followRedirects 是否跟随 3xx。清单必须 false (见 UpdateChecker 注释);
     *                        APK 下载给 true, 因为 CDN 换域名很常见, 而完整性由 sha256 兜底
     */
    static OkHttpClient build(Context context, int connectTimeoutS, int readTimeoutS,
                              boolean followRedirects) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutS, TimeUnit.SECONDS)
                .readTimeout(readTimeoutS, TimeUnit.SECONDS)
                .writeTimeout(readTimeoutS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(followRedirects)
                // https → http 的降级跳转永不跟随: 那等于把校验前的字节流搬到明文信道上
                .followSslRedirects(false)
                .dns(new IPv6FirstDns());
        TlsCompat.configureTls(builder, context);
        return builder.build();
    }
}
