package com.ktools.zspacecarplayer.net;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * 针对 Android 4.3 (API 18) 的 TLS 1.2 / 1.1 强制开启、SNI (Server Name Indication) 补丁，
 * 并解决 Android 4.3 与 Cloudflare 等现代服务器的 cipher 兼容问题。
 *
 * Android 4.3 默认只启用 CBC 类 cipher，而 Cloudflare 仅提供 ECDSA 的 GCM/CHACHA20 cipher，
 * 两者无交集会导致 TLS 握手失败（典型的 "SSL handshake aborted ... Failure in SSL library,
 * usually a protocol error"）。这里显式启用系统支持 ECDSA GCM cipher 以完成握手。
 */
public class TLSSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TLSSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        delegate = context.getSocketFactory();
    }

    public TLSSocketFactory(TrustManager[] trustManagers) throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, null);
        delegate = context.getSocketFactory();
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(s, host, port, autoClose), host);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(host, port), host);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(host, port, localHost, localPort), host);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(host, port), host.getHostName());
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(address, port, localAddress, localPort), address.getHostName());
    }

    private Socket enableTLSOnSocket(Socket socket, String host) {
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;

            // 1. 开启 TLSv1.1 和 TLSv1.2
            try {
                sslSocket.setEnabledProtocols(new String[]{"TLSv1.1", "TLSv1.2"});
            } catch (Exception e) {
                sslSocket.setEnabledProtocols(sslSocket.getSupportedProtocols());
            }

            // 2. 显式启用 ECDSA GCM cipher（Android 4.3 默认未启用 GCM；Cloudflare 只提供 ECDSA GCM/CHACHA20）
            enableModernCiphers(sslSocket);

            // 3. 针对 Android 4.3 反射注入 SNI (Server Name Indication)，防止多域名 Caddy 握手拒绝
            if (host != null && !host.isEmpty()) {
                try {
                    Method setHostnameMethod = sslSocket.getClass().getMethod("setHostname", String.class);
                    setHostnameMethod.invoke(sslSocket, host);
                } catch (Exception ignored) {
                }
            }
        }
        return socket;
    }

    /**
     * 追加启用服务器(Cloudflare)所要求、且该系统 SSL 引擎实际支持(cipher 实现存在)的 ECDSA GCM 套件。
     * Android 4.3 的 Conscrypt/OpenSSL 1.0.1 支持这些 cipher 实现，只是默认 enabled 列表不包含它们。
     */
    private void enableModernCiphers(SSLSocket sslSocket) {
        try {
            Set<String> supportedSet = new HashSet<String>(Arrays.asList(sslSocket.getSupportedCipherSuites()));
            String[] wanted = new String[]{
                    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA"
            };
            List<String> addList = new ArrayList<String>();
            for (String c : wanted) {
                if (supportedSet.contains(c)) {
                    addList.add(c);
                }
            }
            if (addList.isEmpty()) {
                return;
            }

            Set<String> enabledSet = new LinkedHashSet<String>(Arrays.asList(sslSocket.getEnabledCipherSuites()));
            enabledSet.addAll(addList);
            sslSocket.setEnabledCipherSuites(enabledSet.toArray(new String[0]));
        } catch (Exception e) {
            // 任一 cipher 不受支持则回退到系统默认，避免崩溃
        }
    }
}
