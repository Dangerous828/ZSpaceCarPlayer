package com.ktools.zspacecarplayer.update;

import android.content.Context;
import android.util.Log;

import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.net.IPv6FirstDns;
import com.ktools.zspacecarplayer.net.TLSSocketFactory;

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

import okhttp3.OkHttpClient;

/**
 * 远程升级 (2026-09-12): 清单拉取与 APK 下载共用的 OkHttp 装配。
 *
 * 为什么单独抽一个类而不是各自 new OkHttpClient:
 *  1. API 18 (吉利 8600 车机) 的 HttpsURLConnection 默认不开 TLS 1.2, 裸建 client 会在
 *     握手阶段就失败 —— 必须套 TLSSocketFactory, 这与 JellyfinApiClient / CrashUploader
 *     是同一套底座;
 *  2. Android 4.3 的系统证书库不信任 Google Trust Services 根 (清单域名
 *     web.kentonnie.top 的证书正是 GTS WE1 → GTS Root R4 签发), 因此把项目已内置的
 *     res/raw/gts_root_r4 也作为额外信任锚合并进来, 与 JellyfinApiClient 的做法一致;
 *  3. 两个调用方只差超时与是否跟随跳转, 复制两份 TLS 代码迟早走样。
 *
 * 只「增加」信任锚, 绝不放宽主机名校验 (仍用系统默认 HostnameVerifier)。
 */
final class UpdateHttp {

    private static final String TAG = "UpdateHttp";

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
        try {
            X509TrustManager systemTm = findSystemTrustManager();
            X509TrustManager bundledTm = findBundledGtsTrustManager(context);
            X509TrustManager composite = new CompositeTrustManager(systemTm, bundledTm);
            builder.sslSocketFactory(new TLSSocketFactory(new TrustManager[]{composite}), composite);
            builder.hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        } catch (Throwable t) {
            // 装配失败也返回可用 client (OkHttp 自带默认 TLS): 新设备上不该因为这段兼容代码
            // 就让「检查更新」彻底不可用; API18 上则会退化成握手失败并在回调里报错。
            Log.w(TAG, "TLS setup failed, fallback to okhttp default: " + t);
        }
        return builder.build();
    }

    private static X509TrustManager findSystemTrustManager() throws Exception {
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No system X509TrustManager available");
    }

    /** 内置 GTS Root R4 作为额外信任锚; 取不到就返回 null (退化为仅系统信任链) */
    private static X509TrustManager findBundledGtsTrustManager(Context context) {
        if (context == null) {
            return null;
        }
        InputStream is = null;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            is = context.getResources().openRawResource(R.raw.gts_root_r4);
            Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(is);
            ks.setCertificateEntry("gts_root_r4", cert);
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "bundled GTS root unavailable, system trust chain only: " + t);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 系统信任链 + 内置根证书的组合校验器 (任一通过即放行), 与 JellyfinApiClient 同构 */
    private static final class CompositeTrustManager implements X509TrustManager {
        private final List<X509TrustManager> managers = new ArrayList<X509TrustManager>();

        CompositeTrustManager(X509TrustManager... tms) {
            for (TrustManager tm : tms) {
                if (tm instanceof X509TrustManager) {
                    managers.add((X509TrustManager) tm);
                }
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
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
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
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
}
