package com.ktools.zspacecarplayer.crash;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传成功判定的回归测试。
 *
 * 真实事故：端点配成 http://your-jellyfin.example.com/crash，Caddy 301 到 https，
 * OkHttp 跟随跳转时丢掉 POST body 并降级成 GET，站点的静态兜底页对 GET 回
 * 200 text/html —— 于是日志打出 "crash report uploaded: 200"、本地报告被删，
 * 云端一份崩溃都没收到。上传器"成功"得越干脆，数据丢得越安静。
 *
 * 所以这里用真 socket 起一个可编排的端点，把每种应答形态都摆出来验一遍：
 * 只有服务端明确回了 JSON ack 才允许删本地报告。
 */
public class CrashUploaderAckTest {

    private final List<MockEndpoint> endpoints = new ArrayList<MockEndpoint>();

    @After
    public void tearDown() {
        for (MockEndpoint e : endpoints) {
            e.shutdown();
        }
        endpoints.clear();
    }

    private MockEndpoint start(int status, String contentType, String body) throws IOException {
        MockEndpoint e = new MockEndpoint(status, contentType, body, null);
        e.start();
        endpoints.add(e);
        return e;
    }

    // ------------------------------------------------------------ 成功判定

    @Test
    public void jsonAckIsTheOnlySuccess() throws Exception {
        MockEndpoint e = start(200, "application/json", "{\"ok\":true,\"id\":\"r-1\"}");
        Assert.assertTrue(new CrashUploader(e.url()).upload("{\"reportId\":\"r-1\"}"));
        Assert.assertEquals("POST", e.lastMethod);
        Assert.assertTrue(e.lastBody.contains("r-1"));
    }

    @Test
    public void jsonAckWithCharsetAndExtraFieldsStillCounts() throws Exception {
        MockEndpoint e = start(200, "application/json; charset=utf-8",
                "{\"ok\":true,\"stored\":\"/data/crash/r-1.json\"}");
        Assert.assertTrue(new CrashUploader(e.url()).upload("{}"));
    }

    @Test
    public void redirectIsNotSuccessAndMustNotBeFollowed() throws Exception {
        // 这条就是事故本身：301 一旦被跟随，第二个请求会变成 GET 并拿到 200
        MockEndpoint e = new MockEndpoint(301, "text/html", "", "https://example.invalid/crash");
        e.start();
        endpoints.add(e);

        Assert.assertFalse(new CrashUploader(e.url()).upload("{\"reportId\":\"r-1\"}"));
        Assert.assertEquals("只该发一次请求，跟随跳转就会变两次", 1, e.requestCount);
        Assert.assertEquals("POST", e.lastMethod);
    }

    @Test
    public void htmlCatchAllReturning200IsNotSuccess() throws Exception {
        MockEndpoint e = start(200, "text/html",
                "<!doctype html><html lang=\"zh-CN\"><head><title>Jarvis</title>");
        Assert.assertFalse("静态兜底页的 200 不能当成收件成功",
                new CrashUploader(e.url()).upload("{}"));
    }

    @Test
    public void jsonWithoutOkFieldIsNotSuccess() throws Exception {
        // 宁可判失败留在本地（20 份封顶），也不能把"没确认收下"当成"已收下"删掉
        MockEndpoint e = start(200, "application/json", "{\"status\":\"queued\"}");
        Assert.assertFalse(new CrashUploader(e.url()).upload("{}"));
    }

    @Test
    public void okTokenInHtmlIsNotSuccess() throws Exception {
        // 只认 content-type + body 两个条件同时成立，避免 HTML 里碰巧有 "ok" 字样
        MockEndpoint e = start(200, "text/html", "<html><body>ok</body></html>");
        Assert.assertFalse(new CrashUploader(e.url()).upload("{}"));
    }

    @Test
    public void serverErrorsAreNotSuccess() throws Exception {
        Assert.assertFalse(new CrashUploader(start(500, "text/plain", "boom").url()).upload("{}"));
        Assert.assertFalse(new CrashUploader(start(405, "text/html", "Not Allowed").url()).upload("{}"));
        Assert.assertFalse(new CrashUploader(start(502, "text/html", "Bad Gateway").url()).upload("{}"));
    }

    @Test
    public void unreachableEndpointFailsWithoutThrowing() {
        // 车机在隧道里没网是常态：失败必须安静返回，绝不能把上传线程带崩
        String deadUrl = "http://127.0.0.1:1/crash"; // 端口 1，没人听
        Assert.assertFalse(new CrashUploader(deadUrl).upload("{}"));
    }

    @Test
    public void emptyEndpointOrNullBodyFailsFast() {
        Assert.assertFalse(new CrashUploader("").upload("{}"));
        Assert.assertFalse(new CrashUploader(null).upload("{}"));
        Assert.assertFalse(new CrashUploader("http://127.0.0.1:1/crash").upload(null));
    }

    // ------------------------------------------------------------ 体积闸门

    @Test
    public void oversizedReportIsDroppedWithoutTouchingTheNetwork() throws Exception {
        MockEndpoint e = start(200, "application/json", "{\"ok\":true}");
        StringBuilder huge = new StringBuilder(CrashUploader.MAX_BODY_BYTES + 8);
        for (int i = 0; i <= CrashUploader.MAX_BODY_BYTES; i++) {
            huge.append('x');
        }
        Assert.assertEquals(CrashUploader.MAX_BODY_BYTES + 1, huge.length());
        // 返回 true 是刻意的：这份永远传不上去，留着只会把 20 份的配额占死
        Assert.assertTrue(new CrashUploader(e.url()).upload(huge.toString()));
        Assert.assertEquals("超限的报告不该发出去吃 4G 流量", 0, e.requestCount);
    }

    @Test
    public void reportExactlyAtTheLimitIsStillSent() throws Exception {
        // 闸门是严格大于：卡在上限的报告是真实现场（logcat 64KB + 线程快照），不能丢
        MockEndpoint e = start(200, "application/json", "{\"ok\":true}");
        StringBuilder atLimit = new StringBuilder(CrashUploader.MAX_BODY_BYTES);
        for (int i = 0; i < CrashUploader.MAX_BODY_BYTES; i++) {
            atLimit.append('x');
        }
        Assert.assertTrue(new CrashUploader(e.url()).upload(atLimit.toString()));
        Assert.assertEquals(1, e.requestCount);
        Assert.assertEquals(CrashUploader.MAX_BODY_BYTES, e.lastBody.length());
    }

    // ------------------------------------------------------------ 请求本身

    @Test
    public void bodyIsPostedAsJsonAndArrivesIntact() throws Exception {
        MockEndpoint e = start(200, "application/json", "{\"ok\":true}");
        String payload = "{\"reportId\":\"a-1\",\"kind\":\"java_crash\",\"breadcrumbs\":[]}";
        Assert.assertTrue(new CrashUploader(e.url()).upload(payload));
        Assert.assertEquals("POST", e.lastMethod);
        Assert.assertEquals("/crash", e.lastPath);
        Assert.assertTrue("Content-Type 必须是 json: " + e.lastContentType,
                e.lastContentType != null && e.lastContentType.contains("application/json"));
        Assert.assertEquals("报告正文必须原样送达", payload, e.lastBody);
    }

    // ------------------------------------------------------------ ack 判定单元

    @Test
    public void isIngestAckRequiresBothJsonAndOkField() {
        Assert.assertTrue(CrashUploader.isIngestAck("application/json", "{\"ok\":true}"));
        Assert.assertTrue(CrashUploader.isIngestAck("application/json; charset=utf-8", "{\"ok\":1}"));
        Assert.assertFalse(CrashUploader.isIngestAck("text/html", "{\"ok\":true}"));
        Assert.assertFalse(CrashUploader.isIngestAck("application/json", "{\"status\":\"ok\"}"));
        Assert.assertFalse(CrashUploader.isIngestAck("application/json", ""));
        Assert.assertFalse(CrashUploader.isIngestAck(null, "{\"ok\":true}"));
        Assert.assertFalse(CrashUploader.isIngestAck("application/json", null));
    }

    // ------------------------------------------------------------ mock 端点

    /**
     * 一次一答的裸 socket HTTP 端点。刻意不用任何测试框架：依赖锁死，
     * 而且要把 301 的 Location、Content-Type、body 三样都精确摆出来才能复现事故。
     */
    private static final class MockEndpoint {
        private final int status;
        private final String contentType;
        private final String body;
        private final String location;

        private ServerSocket server;
        private Thread acceptor;
        private volatile boolean shuttingDown;

        volatile int requestCount;
        volatile String lastMethod = "";
        volatile String lastPath = "";
        volatile String lastBody = "";
        volatile String lastContentType;

        MockEndpoint(int status, String contentType, String body, String location) {
            this.status = status;
            this.contentType = contentType;
            this.body = body == null ? "" : body;
            this.location = location;
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/crash";
        }

        void start() throws IOException {
            server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!shuttingDown) {
                        Socket socket = null;
                        try {
                            socket = server.accept();
                            handle(socket);
                        } catch (IOException ignored) {
                            return; // shutdown 关掉了 server
                        } finally {
                            closeQuietly(socket);
                        }
                    }
                }
            }, "MockCrashEndpoint");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void handle(Socket socket) throws IOException {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            String requestLine = readLine(in);
            String[] parts = requestLine.split(" ");
            lastMethod = parts.length > 0 ? parts[0] : "";
            lastPath = parts.length > 1 ? parts[1] : "";

            int contentLength = 0;
            lastContentType = null;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if ("Content-Length".equalsIgnoreCase(name)) {
                        contentLength = Integer.parseInt(value);
                    } else if ("Content-Type".equalsIgnoreCase(name)) {
                        lastContentType = value;
                    }
                }
            }
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(buf, off, contentLength - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
                lastBody = new String(buf, 0, off, "UTF-8");
            } else {
                lastBody = "";
            }
            requestCount++;

            byte[] payload = body.getBytes("UTF-8");
            StringBuilder head = new StringBuilder(128);
            head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            if (contentType != null) {
                head.append("Content-Type: ").append(contentType).append("\r\n");
            }
            head.append("Content-Length: ").append(payload.length).append("\r\n");
            if (location != null) {
                head.append("Location: ").append(location).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes("UTF-8"));
            out.write(payload);
            out.flush();
        }

        void shutdown() {
            shuttingDown = true;
            closeQuietly(server);
            if (acceptor != null) {
                acceptor.interrupt();
            }
        }

        private static String reason(int code) {
            switch (code) {
                case 200: return "OK";
                case 301: return "Moved Permanently";
                case 405: return "Method Not Allowed";
                case 500: return "Internal Server Error";
                case 502: return "Bad Gateway";
                default: return "Status";
            }
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    bos.write(c);
                }
            }
            if (c == -1 && bos.size() == 0) {
                return null;
            }
            return new String(bos.toByteArray(), "UTF-8");
        }

        private static void closeQuietly(java.io.Closeable c) {
            if (c != null) {
                try {
                    c.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
