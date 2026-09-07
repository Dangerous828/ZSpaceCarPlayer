package com.ktools.zspacecarplayer.player;

import com.ktools.zspacecarplayer.player.stream.BufferedHttpSource;
import com.ktools.zspacecarplayer.player.stream.HttpProxyServer;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * v3 流式缓冲层（BufferedHttpSource + HttpProxyServer）JVM 单元测试。
 *
 * 用一个可控的原始 socket 测试源服务器（支持 Range / 限速 / 请求计数）验证：
 * 顺序读取、环形缓冲窗口回收、seek 重定位续传、代理 206/Content-Range、
 * 窗口内 seek 零回源、网络抖动下的持续读出。
 */
public class StreamBufferLayerTest {

    private static final byte[] CONTENT = new byte[2 * 1024 * 1024];

    static {
        new Random(42).nextBytes(CONTENT);
    }

    private TestHttpOrigin origin;

    private TestHttpOrigin newOrigin() throws IOException {
        origin = new TestHttpOrigin(CONTENT);
        return origin;
    }

    @After
    public void tearDown() {
        HttpProxyServer.getInstance().clearSources();
        if (origin != null) {
            origin.shutdown();
            origin = null;
        }
    }

    // ===================== 工具 ===================== //

    /** 从 HttpURLConnection 读出整个 body */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ===================== BufferedHttpSource 直接测试 ===================== //

    /** 顺序读穿整段流；窗口必须恒 ≤ 容量（环形回收生效且不死锁） */
    @Test(timeout = 60_000)
    public void sequentialReadWrapsRingWithBoundedWindow() throws Exception {
        TestHttpOrigin src = newOrigin();
        int capacity = 256 * 1024; // 故意 < 旧实现 1MB 的 PROTECT 保留量，回归回收死锁
        BufferedHttpSource source = new BufferedHttpSource(src.url(), capacity);
        Object token = new Object();
        byte[] buf = new byte[32 * 1024];
        long pos = 0;
        Object readToken = new Object();
        source.addReadPos(readToken, 0);
        while (pos < CONTENT.length) {
            int n = source.readAt(pos, buf, 0, buf.length);
            assertTrue(n > 0);
            assertArrayEquals(Arrays.copyOfRange(CONTENT, (int) pos, (int) pos + n),
                    Arrays.copyOfRange(buf, 0, n));
            pos += n;
            source.updateReadPos(readToken, pos);
            assertTrue("window exceeded capacity",
                    source.getBufferedBytes() <= capacity);
        }
        assertEquals(-1, source.readAt(pos, buf, 0, buf.length));
        source.removeReadPos(readToken);
        source.close();
    }

    /** 向后 seek 落在窗口外：触发 reset + 从新位置重新下载，数据必须正确 */
    @Test(timeout = 60_000)
    public void backwardSeekOutsideWindowRepositions() throws Exception {
        TestHttpOrigin src = newOrigin();
        int capacity = 256 * 1024;
        BufferedHttpSource source = new BufferedHttpSource(src.url(), capacity);
        byte[] buf = new byte[32 * 1024];
        Object token = new Object();
        long pos = 0;
        source.addReadPos(token, 0);
        while (pos < 1024 * 1024) { // 读到 1MB，窗口已滑过开头
            int n = source.readAt(pos, buf, 0, buf.length);
            assertTrue(n > 0);
            pos += n;
            source.updateReadPos(token, pos);
        }
        long backTo = 500_000L;
        // 窗口只有 256KB，已读到 1MB：backTo 必然已滑出窗口头部 → 走 reset + 重下载路径，
        // 数据仍必须逐字节正确
        int n = source.readAt(backTo, buf, 0, buf.length);
        assertTrue(n > 0);
        assertArrayEquals(Arrays.copyOfRange(CONTENT, (int) backTo, (int) backTo + n),
                Arrays.copyOfRange(buf, 0, n));
        source.close();
    }

    // ===================== HttpProxyServer 端到端测试 ===================== //

    /** 全量拉流：200 + Content-Length + 字节一致 + 远端仅 1 次请求 */
    @Test(timeout = 60_000)
    public void proxyFullStreamByteIdentical() throws Exception {
        TestHttpOrigin src = newOrigin();
        String proxyUrl = HttpProxyServer.getInstance().getProxyUrl(src.url());
        assertTrue(proxyUrl.startsWith("http://127.0.0.1:"));
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(proxyUrl).openConnection();
        assertEquals(200, conn.getResponseCode());
        assertEquals(String.valueOf(CONTENT.length), conn.getHeaderField("Content-Length"));
        assertEquals("audio/flac", conn.getHeaderField("Content-Type"));
        byte[] body = readAll(conn.getInputStream());
        assertArrayEquals(CONTENT, body);
        conn.disconnect();
        assertEquals(1, src.requestCount.get());
    }

    /** Range 请求：206 + Content-Range + 切片字节正确 */
    @Test(timeout = 60_000)
    public void proxyRangeRequestReturns206Slice() throws Exception {
        TestHttpOrigin src = newOrigin();
        String proxyUrl = HttpProxyServer.getInstance().getProxyUrl(src.url());
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(proxyUrl).openConnection();
        conn.setRequestProperty("Range", "bytes=1000-");
        assertEquals(206, conn.getResponseCode());
        assertEquals("bytes 1000-" + (CONTENT.length - 1) + "/" + CONTENT.length,
                conn.getHeaderField("Content-Range"));
        assertEquals(String.valueOf(CONTENT.length - 1000), conn.getHeaderField("Content-Length"));
        byte[] body = readAll(conn.getInputStream());
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 1000, CONTENT.length), body);
        conn.disconnect();
    }

    /** seek 落在缓冲窗口内：第二个连接共享数据源，从断点续传而不重新下载已缓冲区间 */
    @Test(timeout = 60_000)
    public void seekWithinWindowServedWithoutRemoteRequest() throws Exception {
        TestHttpOrigin src = newOrigin();
        String remoteUrl = src.url();
        String proxyUrl = HttpProxyServer.getInstance().getProxyUrl(remoteUrl);

        // 第一个连接：读 512KB 后断开（模拟播放器首连）。读完即保证窗口已有 ≥512KB
        java.net.HttpURLConnection conn1 =
                (java.net.HttpURLConnection) new java.net.URL(proxyUrl).openConnection();
        assertEquals(200, conn1.getResponseCode());
        InputStream in1 = conn1.getInputStream();
        byte[] first = new byte[512 * 1024];
        int off = 0;
        while (off < first.length) {
            int n = in1.read(first, off, first.length - off);
            assertTrue(n > 0);
            off += n;
        }
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 0, first.length), first);
        in1.close();
        conn1.disconnect();

        // 第二个连接：窗口内 seek，共享数据源；下载线程从原断点续传（最多一次 Range 重连），
        // 但绝不会从 seek 点重新下载已缓冲的 [0, 512KB)
        java.net.HttpURLConnection conn2 =
                (java.net.HttpURLConnection) new java.net.URL(proxyUrl).openConnection();
        conn2.setRequestProperty("Range", "bytes=" + (512 * 1024) + "-");
        assertEquals(206, conn2.getResponseCode());
        byte[] body = readAll(conn2.getInputStream());
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 512 * 1024, CONTENT.length), body);
        conn2.disconnect();
        assertTrue("resume must not re-download buffered region, count="
                        + src.requestCount.get(),
                src.requestCount.get() <= 2);
    }

    /** 网络抖动（源端按块延迟）：代理持续供数不饿死 */
    @Test(timeout = 60_000)
    public void jitteredSourceStillStreams() throws Exception {
        TestHttpOrigin src = newOrigin();
        src.chunkDelayMs = 20; // 每块 64KB 延迟 20ms，模拟公网抖动
        String proxyUrl = HttpProxyServer.getInstance().getProxyUrl(src.url());
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(proxyUrl).openConnection();
        assertEquals(200, conn.getResponseCode());
        byte[] body = readAll(conn.getInputStream());
        assertArrayEquals(CONTENT, body);
        conn.disconnect();
    }

    /** 源端不支持 Range（恒 200 全量）：读过容量后回读触发 reset，下载侧走 skip 续传路径仍正确 */
    @Test(timeout = 90_000)
    public void remoteWithoutRangeSupportStillServes() throws Exception {
        TestHttpOrigin src = newOrigin();
        src.supportRange = false;
        // 容量 256KB：先顺序读 512KB 让窗口滑过开头，再回读 1000 位置触发 reset
        BufferedHttpSource source = new BufferedHttpSource(src.url(), 256 * 1024);
        byte[] buf = new byte[32 * 1024];
        Object token = new Object();
        source.addReadPos(token, 0);
        long pos = 0;
        while (pos < 512 * 1024) {
            int n = source.readAt(pos, buf, 0, buf.length);
            assertTrue(n > 0);
            pos += n;
            source.updateReadPos(token, pos);
        }
        // 触发回读 reset（远端无 Range → skip 路径）
        long backTo = 1000L;
        int n = source.readAt(backTo, buf, 0, buf.length);
        assertTrue(n > 0);
        assertArrayEquals(Arrays.copyOfRange(CONTENT, (int) backTo, (int) backTo + n),
                Arrays.copyOfRange(buf, 0, n));
        source.close();
    }

    /** getProxyUrl 非法/本地输入原样返回 */
    @Test
    public void proxyUrlPassthroughForNonHttp() {
        org.junit.Assert.assertNull(HttpProxyServer.getInstance().getProxyUrl(null));
        assertEquals("/sdcard/a.flac", HttpProxyServer.getInstance().getProxyUrl("/sdcard/a.flac"));
    }

    // ===================== 截断自愈测试 ===================== //

    /**
     * 回归：主连接还在下载时，第二条连接从窗口外远端位置（如 extractor 文件尾探测）进入，
     * 必须分家独立供数、快速完成，且主连接随后继续正常读——旧实现共享同一窗口会导致
     * 两读者互相 reset 乒乓、双双饿死（真实车机/模拟器播放锁死根因）。
     */
    @Test(timeout = 90_000)
    public void farRangeConnectionForksInsteadOfPingPong() throws Exception {
        TestHttpOrigin src = newOrigin();
        src.chunkDelayMs = 25; // 放慢源端，保证主连接仍在读时第二连接到来
        String proxyUrl = HttpProxyServer.getInstance().getProxyUrl(src.url());

        // 主连接：Range 0-，只读一小块后挂住不关（模拟 NuPlayer 主连接）
        java.net.HttpURLConnection main =
                (java.net.HttpURLConnection) new java.net.URL(proxyUrl).openConnection();
        main.setRequestProperty("Range", "bytes=0-");
        assertEquals(200, main.getResponseCode()); // start=0 按全量 200 回复
        InputStream mainIn = main.getInputStream();
        byte[] head = new byte[128 * 1024];
        int off = 0;
        while (off < head.length) {
            int n = mainIn.read(head, off, head.length - off);
            assertTrue(n > 0);
            off += n;
        }
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 0, head.length), head);

        // 第二连接：从 1.5MB 处进入（远超主连接当前窗口末端）
        long farStart = 1536 * 1024;
        java.net.HttpURLConnection far =
                (java.net.HttpURLConnection) new java.net.URL(proxyUrl).openConnection();
        far.setRequestProperty("Range", "bytes=" + farStart + "-");
        assertEquals(206, far.getResponseCode());
        byte[] farBody = readAll(far.getInputStream()); // 必须能独立完成，不被主连接拉扯
        far.disconnect();
        assertArrayEquals(Arrays.copyOfRange(CONTENT, (int) farStart, CONTENT.length), farBody);

        // 主连接必须仍然健在且继续供数正确
        byte[] next = new byte[64 * 1024];
        off = 0;
        while (off < next.length) {
            int n = mainIn.read(next, off, next.length - off);
            assertTrue(n > 0);
            off += n;
        }
        assertArrayEquals(Arrays.copyOfRange(CONTENT, head.length, head.length + next.length), next);
        mainIn.close();
        main.disconnect();
    }

    /** 远端干净 FIN 但总长未下满：必须按截断续传而非误标 EOF，最终数据完整 */
    @Test(timeout = 60_000)
    public void truncatedRemoteResumesInsteadOfPrematureEof() throws Exception {
        TestHttpOrigin src = newOrigin();
        src.firstRequestTruncateAt = 256 * 1024;
        BufferedHttpSource source = new BufferedHttpSource(src.url());
        byte[] buf = new byte[64 * 1024];
        Object token = new Object();
        source.addReadPos(token, 0);
        long pos = 0;
        while (pos < CONTENT.length) {
            int n = source.readAt(pos, buf, 0, buf.length);
            assertTrue("readAt returned " + n + " at pos " + pos, n > 0);
            assertArrayEquals(Arrays.copyOfRange(CONTENT, (int) pos, (int) pos + n),
                    Arrays.copyOfRange(buf, 0, n));
            pos += n;
            source.updateReadPos(token, pos);
        }
        assertEquals(-1, source.readAt(pos, buf, 0, buf.length)); // 真正的 EOF 只能出现在下满之后
        assertTrue("resume must have re-connected to remote", src.requestCount.get() >= 2);
        source.close();
    }

    /** 截断场景走代理：客户端仍拿到字节一致的全量 body */
    @Test(timeout = 60_000)
    public void truncatedRemoteServesFullBodyThroughProxy() throws Exception {
        TestHttpOrigin src = newOrigin();
        src.firstRequestTruncateAt = 300 * 1024;
        String proxyUrl = HttpProxyServer.getInstance().getProxyUrl(src.url());
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(proxyUrl).openConnection();
        assertEquals(200, conn.getResponseCode());
        byte[] body = readAll(conn.getInputStream());
        assertArrayEquals(CONTENT, body);
        conn.disconnect();
        assertTrue(src.requestCount.get() >= 2);
    }
}
