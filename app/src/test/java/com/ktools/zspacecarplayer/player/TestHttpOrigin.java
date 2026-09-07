package com.ktools.zspacecarplayer.player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用 HTTP 源服务器：按 Range 语义供数，可限速、可截断、可中途卡死、可计数。
 *
 * 从 StreamBufferLayerTest 里提出来给流式缓冲层与原生解码 reader 两组测试共用——
 * 两边要复现的都是同一类公网行为（抖动、断流、不支持 Range），复制一份服务器
 * 只会让两边的语义悄悄分叉。
 *
 * 全部线程守护，shutdown() 只关监听套接字；卡在 stallGate 上的连接线程随 JVM 退出。
 */
final class TestHttpOrigin {

    final byte[] data;
    volatile boolean supportRange = true;
    /** 每写出一块前睡眠的毫秒数，模拟公网抖动 */
    volatile long chunkDelayMs = 0;
    /** ≥0 时：首个请求只送这么多字节就干净关闭（模拟公网提前断流/截断） */
    volatile long firstRequestTruncateAt = -1;
    /**
     * ≥0 时：送满这么多字节后卡在闸门上不再供数（模拟链路挂死但不断开）。
     * 与 truncate 的区别是连接不关闭——这才是解码线程真正会被无限阻塞的场景，
     * 也是 abort()/cancelWaiters() 必须能解开的那个现场。
     */
    volatile long stallAfterBytes = -1;
    final AtomicInteger requestCount = new AtomicInteger();
    final AtomicInteger rangeCount = new AtomicInteger();

    private final Object stallGate = new Object();
    private volatile boolean shuttingDown = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    TestHttpOrigin(byte[] data) throws IOException {
        this.data = data;
        serverSocket = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "test-origin-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    String url() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/music.flac";
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    handle(socket);
                }
            }, "test-origin-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String line = readLine(in);
            long rangeStart = -1;
            while (true) {
                String h = readLine(in);
                if (h == null || h.length() == 0) {
                    break;
                }
                if (h.toLowerCase().startsWith("range:")) {
                    String v = h.substring(6).trim();
                    if (v.startsWith("bytes=")) {
                        String spec = v.substring(6);
                        int dash = spec.indexOf('-');
                        if (dash > 0) {
                            rangeStart = Long.parseLong(spec.substring(0, dash).trim());
                        }
                    }
                }
            }
            requestCount.incrementAndGet();
            if (rangeStart >= 0) {
                rangeCount.incrementAndGet();
            }
            long start = (rangeStart >= 0 && supportRange) ? rangeStart : 0;
            boolean truncate = requestCount.get() == 1 && firstRequestTruncateAt >= 0;
            long bodyLimit = data.length - start;
            if (truncate) {
                bodyLimit = Math.min(bodyLimit, Math.max(0, firstRequestTruncateAt - start));
            }
            if (rangeStart >= 0 && supportRange) {
                out.write(("HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes " + start + "-" + (data.length - 1) + "/" + data.length + "\r\n"
                        + "Content-Length: " + (data.length - start) + "\r\n"
                        + "Content-Type: audio/flac\r\n"
                        + "Accept-Ranges: bytes\r\n\r\n").getBytes("ISO-8859-1"));
            } else {
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Length: " + data.length + "\r\n"
                        + "Content-Type: audio/flac\r\n"
                        + "Accept-Ranges: bytes\r\n\r\n").getBytes("ISO-8859-1"));
            }
            out.flush();
            byte[] chunk = new byte[64 * 1024];
            long pos = start;
            long end = start + bodyLimit;
            long stallAt = (stallAfterBytes >= 0) ? start + stallAfterBytes : -1;
            while (pos < end) {
                int n = (int) Math.min(chunk.length, end - pos);
                if (chunkDelayMs > 0) {
                    Thread.sleep(chunkDelayMs);
                }
                if (stallAt >= 0 && pos >= stallAt) {
                    awaitShutdown();
                    return;
                }
                out.write(Arrays.copyOfRange(data, (int) pos, (int) pos + n), 0, n);
                pos += n;
                out.flush();
            }
            out.flush();
            socket.close();
        } catch (Exception e) {
            // 客户端提前断开属于正常路径
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void awaitShutdown() {
        synchronized (stallGate) {
            while (!shuttingDown) {
                try {
                    stallGate.wait(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(80);
        while (true) {
            int c = in.read();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
    }

    void shutdown() {
        shuttingDown = true;
        synchronized (stallGate) {
            stallGate.notifyAll();
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}
