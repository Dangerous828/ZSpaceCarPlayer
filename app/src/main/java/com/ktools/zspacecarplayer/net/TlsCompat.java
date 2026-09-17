package com.ktools.zspacecarplayer.net;

import android.content.Context;
import android.util.Log;

import com.ktools.zspacecarplayer.R;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;

/**
 * 针对 Android 4.3 (API 18 / 吉利 8600 车机) 与现代 Cloudflare / GTS / Caddy 服务器的 TLS 兼容工具。
 *
 * 核心问题与成因:
 * 1. 协议: Android 4.3 的 SSLSocket 默认只开启 SSLv3 和 TLSv1.0; 现代 CDN (Cloudflare) 最低要求 TLS 1.2;
 * 2. 根证书: web.kentonnie.top / jarvis.kentonnie.top 的证书由 Google Trust Services (GTS WE1 → GTS Root R4) 签发,
 *    Android 4.3 系统根证书库不包含 GTS Root R4, 必须加载内置 raw/gts_root_r4;
 * 3. 密码套件 (最隐蔽坑): Cloudflare 使用 256 位 ECDSA 证书, 仅协商 ECDSA 密码套件。Android 4.3 的 Conscrypt
 *    仅支持 CBC 类 ECDSA (TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA 等)。而 OkHttp 3.12 的默认 ConnectionSpec.MODERN_TLS
 *    只保留了 ECDSA GCM/CHACHA, 并在握手前将 SSLSocket 上启用的 ECDSA CBC 强行剔除, 导致交集为 0,
 *    触发经典的 "sslv3 alert handshake failure (external/openssl/ssl/s23_clnt.c:741)"。
 *    通过 ConnectionSpec.allEnabledCipherSuites() / allEnabledTlsVersions() 解除 OkHttp 的二次过滤,
 *    完整保留 TLSSocketFactory 协商出的 ECDSA CBC 套件。
 */
public final class TlsCompat {

    private static final String TAG = "TlsCompat";

    private TlsCompat() {}

    /**
     * 生成放宽密码套件二次过滤的 ConnectionSpec, 配合 TLSSocketFactory 允许系统支持的 ECDSA CBC 套件。
     */
    public static ConnectionSpec buildCompatibleModernTlsSpec() {
        return new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .allEnabledCipherSuites()
                .allEnabledTlsVersions()
                .build();
    }

    /**
     * 为 OkHttpClient.Builder 装配完整的 Android 4.3 TLS 兼容底座:
     * - ConnectionSpec: MODERN_TLS (放宽过滤) + CLEARTEXT
     * - SSLSocketFactory: TLSSocketFactory 强启 TLS 1.1/1.2 + SNI + ECDSA CBC
     * - TrustManager: 系统证书库 + 内置 GTS Root R4
     */
    public static void configureTls(OkHttpClient.Builder builder, Context context) {
        if (builder == null) return;

        builder.connectionSpecs(Arrays.asList(buildCompatibleModernTlsSpec(), ConnectionSpec.CLEARTEXT));

        try {
            X509TrustManager systemTm = findSystemTrustManager();
            X509TrustManager bundledTm = findBundledGtsTrustManager(context);
            CompositeTrustManager composite = new CompositeTrustManager(systemTm, bundledTm);
            if (composite.hasTrustManagers()) {
                builder.sslSocketFactory(new TLSSocketFactory(new TrustManager[]{composite}), composite);
                builder.hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
            }
        } catch (Throwable t) {
            Log.w(TAG, "TLS setup failed, fallback to okhttp default: " + t);
        }
    }

    public static X509TrustManager findSystemTrustManager() {
        try {
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "system trust manager unavailable: " + t);
        }
        return null;
    }

    public static X509TrustManager findBundledGtsTrustManager(Context context) {
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
            Log.w(TAG, "bundled GTS root unavailable: " + t);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 系统信任链 + 内置根证书 的组合校验器 (任一通过即放行) */
    public static final class CompositeTrustManager implements X509TrustManager {
        private final List<X509TrustManager> managers = new ArrayList<X509TrustManager>();

        public CompositeTrustManager(X509TrustManager... tms) {
            if (tms != null) {
                for (X509TrustManager tm : tms) {
                    if (tm != null) {
                        managers.add(tm);
                    }
                }
            }
        }

        public boolean hasTrustManagers() {
            return !managers.isEmpty();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager m : managers) {
                try {
                    m.checkClientTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    last = e;
                }
            }
            if (last != null) throw last;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager m : managers) {
                try {
                    m.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    last = e;
                }
            }
            if (last != null) throw last;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> list = new ArrayList<X509Certificate>();
            for (X509TrustManager m : managers) {
                list.addAll(Arrays.asList(m.getAcceptedIssuers()));
            }
            return list.toArray(new X509Certificate[0]);
        }
    }
}
