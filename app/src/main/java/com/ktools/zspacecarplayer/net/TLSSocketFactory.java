package com.ktools.zspacecarplayer.net;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * 针对 Android 4.3 (API 18) 的 TLS 1.2 / 1.1 强制开启及 SNI (Server Name Indication) 补丁
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

            // 2. 针对 Android 4.3 反射注入 SNI (Server Name Indication)，防止多域名 Caddy 握手拒绝
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
}
