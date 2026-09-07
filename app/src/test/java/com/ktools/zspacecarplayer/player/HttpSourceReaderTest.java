package com.ktools.zspacecarplayer.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.ktools.zspacecarplayer.dsp.NativeLosslessDecoder.HttpSourceReader;
import com.ktools.zspacecarplayer.player.stream.BufferedHttpSource;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 原生无损解码 reader（{@link HttpSourceReader}）与环形缓冲的契约测试。
 *
 * 这个 reader 是 dr_flac/dr_wav 唯一的取数通道，它的语义直接决定车机上能不能
 * 完整播完一首无损：短读必须续读满（否则一次网络抖动就被解码器当成流结束）、
 * 回溯 seek 必须真的落回小位（dr_flac 找 SEEKTABLE / 读尾部元数据会回读）、
 * 阻塞读必须能被 abort 解开（nativeClose 拿不到锁就只能泄漏解码器）。
 *
 * 全部走真实 socket + 真实 BufferedHttpSource：这些行为的成因都在下载线程与
 * 窗口回收的交互里，mock 掉就等于什么都没测。
 */
public class HttpSourceReaderTest {

    /** 2MB：够跨过 128KB 窗口，用于回收 / 阻塞类用例 */
    private static final byte[] BIG = new byte[2 * 1024 * 1024];
    /** 512KB：够跨过窗口又不至于让用例跑太久 */
    private static final byte[] SMALL = new byte[512 * 1024];

    static {
        new Random(42).nextBytes(BIG);
        new Random(7).nextBytes(SMALL);
    }

    /** 窗口容量下限即 BufferedHttpSource 的 CHUNK_SIZE * 2 */
    private static final int MIN_CAPACITY = 128 * 1024;

    private final List<TestHttpOrigin> origins = new ArrayList<TestHttpOrigin>();
    private final List<BufferedHttpSource> sources = new ArrayList<BufferedHttpSource>();

    @After
    public void tearDown() {
        for (BufferedHttpSource s : sources) {
            s.close();
        }
        sources.clear();
        for (TestHttpOrigin o : origins) {
            o.shutdown();
        }
        origins.clear();
    }

    // ===================== 装配 ===================== //

    private BufferedHttpSource newSource(TestHttpOrigin origin, int capacity) {
        BufferedHttpSource s = new BufferedHttpSource(origin.url(), capacity);
        sources.add(s);
        return s;
    }

    /** 按 reader 的文档契约：构造前调用方须已 addRef */
    private HttpSourceReader newReader(BufferedHttpSource source, long startPos) {
        source.addRef();
        return new HttpSourceReader(source, startPos);
    }

    /** 读满 length 字节，中途 EOF 或短读即失败 */
    private static void readFully(HttpSourceReader reader, byte[] dest, int offset, int length)
            throws IOException {
        int got = 0;
        while (got < length) {
            int n = reader.read(dest, offset + got, length - got);
            if (n < 0) {
                fail("unexpected EOF after " + got + "/" + length + " bytes");
            }
            got += n;
        }
    }

    // ===================== 顺序读 ===================== //

    /**
     * 单次 read 请求远大于源端一次能供的量时，必须内部续读直到读满。
     *
     * dr_* 的 onRead 契约等同 fread：返回短读即被判成 EOF，整首歌就在抖动处
     * 静默截断。这条是那个「短读即 EOF」陷阱的正面回归。
     */
    @Test(timeout = 60_000)
    public void readSatisfiesFullLengthAcrossShortReads() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(BIG);
        origins.add(origin);
        origin.chunkDelayMs = 5; // 每 64KB 一块地慢送，逼出多次短读
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        HttpSourceReader reader = newReader(source, 0);

        int want = 256 * 1024;
        byte[] buf = new byte[want];
        int n = reader.read(buf, 0, want);

        assertEquals("read 必须读满请求长度，短读会被 dr_* 当成流结束", want, n);
        assertArrayEquals(Arrays.copyOfRange(BIG, 0, want), buf);
        reader.close();
    }

    /**
     * 读者一路顺序推进时，窗口头部必须跟着回收。
     *
     * 读者登记位若停在起点，writeToRing 会因为「不能越过最慢读者的未读位置」
     * 而永远滑不动 bufStart，下载线程在写满 capacity 后死等——表现是超过窗口
     * 大小的无损曲目（几乎每一首 FLAC 都 20MB+）播完头 2MB 就卡死。
     */
    @Test(timeout = 30_000)
    public void windowRecyclesAsReaderAdvances() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(SMALL);
        origins.add(origin);
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        HttpSourceReader reader = newReader(source, 0);

        byte[] buf = new byte[32 * 1024];
        long pos = 0;
        while (pos < SMALL.length) {
            readFully(reader, buf, 0, buf.length);
            pos += buf.length;
            assertArrayEquals("pos=" + (pos - buf.length),
                    Arrays.copyOfRange(SMALL, (int) (pos - buf.length), (int) pos), buf);
            assertTrue("窗口水位必须恒 ≤ 容量，实际 " + source.getBufferedBytes(),
                    source.getBufferedBytes() <= MIN_CAPACITY);
        }
        assertTrue("读完 512KB 后窗口头必须已滑过起点，实际 windowStart="
                        + source.getWindowStart(),
                source.getWindowStart() > 0);
        reader.close();
    }

    /** 尾部只剩不足请求量的字节时，返回实际余量而不是 -1，也不是死等 */
    @Test(timeout = 30_000)
    public void readAtTailReturnsOnlyRemainingBytes() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(SMALL);
        origins.add(origin);
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        int tail = 1000;
        HttpSourceReader reader = newReader(source, SMALL.length - tail);

        byte[] buf = new byte[tail];
        assertEquals(tail, reader.read(buf, 0, 32 * 1024));
        assertArrayEquals(Arrays.copyOfRange(SMALL, SMALL.length - tail, SMALL.length), buf);

        // 再读一次才真正到 EOF
        assertEquals("EOF 必须返回 -1", -1, reader.read(buf, 0, 16));
        reader.close();
    }

    // ===================== seek ===================== //

    /**
     * 回溯 seek 后必须读回原位置的原始字节。
     *
     * updateReadPos 只进不退，回溯全靠 seekReadPos 强制落位；dr_flac 解析
     * SEEKTABLE、读文件尾元数据都会回读，这条错了整首无损直接解不出来。
     */
    @Test(timeout = 30_000)
    public void backwardSeekRereadsCorrectBytes() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(SMALL);
        origins.add(origin);
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        HttpSourceReader reader = newReader(source, 0);

        byte[] head = new byte[16 * 1024];
        readFully(reader, head, 0, head.length);

        // 往前推过整个窗口，确保起点已被回收
        byte[] skip = new byte[32 * 1024];
        for (int i = 0; i < 8; i++) {
            readFully(reader, skip, 0, skip.length);
        }

        assertTrue(reader.seek(0));
        byte[] again = new byte[16 * 1024];
        readFully(reader, again, 0, again.length);
        assertArrayEquals("回溯 seek 后必须读回原始字节", head, again);

        // 回溯之后继续顺序推进也必须正确（登记位不能因回退而错乱）
        byte[] next = new byte[16 * 1024];
        readFully(reader, next, 0, next.length);
        assertArrayEquals(Arrays.copyOfRange(SMALL, 16 * 1024, 32 * 1024), next);
        reader.close();
    }

    /** 停在原位的 seek 是无害空操作，不得触发重定位下载 */
    @Test(timeout = 30_000)
    public void seekToCurrentPositionIsNoOp() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(SMALL);
        origins.add(origin);
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        HttpSourceReader reader = newReader(source, 4096);

        assertTrue(reader.seek(4096));
        int before = origin.requestCount.get();
        assertTrue(reader.seek(4096));
        assertEquals("同位 seek 不该惊动远端", before, origin.requestCount.get());

        byte[] buf = new byte[1024];
        readFully(reader, buf, 0, buf.length);
        assertArrayEquals(Arrays.copyOfRange(SMALL, 4096, 4096 + 1024), buf);
        reader.close();
    }

    /** 非法目标一律拒绝，不能把游标写坏 */
    @Test(timeout = 30_000)
    public void seekRejectsOutOfRangePositions() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(SMALL);
        origins.add(origin);
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        HttpSourceReader reader = newReader(source, 0);

        assertFalse(reader.seek(-1));
        assertFalse(reader.seek(Long.MAX_VALUE));

        // 被拒绝之后游标必须仍然可用
        byte[] buf = new byte[512];
        readFully(reader, buf, 0, buf.length);
        assertArrayEquals(Arrays.copyOfRange(SMALL, 0, 512), buf);
        reader.close();
    }

    // ===================== 阻塞与取消 ===================== //

    /**
     * 源端链路挂死（不断开、只是不再供数）时，abort 必须立刻解开阻塞读。
     *
     * 这是车机上最要命的一条：nativeClose 在解码线程外调用，解码线程若卡在
     * 等网络供数上，close 就拿不到 native 侧的 apiMutex，只能被迫泄漏整个
     * 解码器 + JNI GlobalRef。abort() 就是那个逃生口。
     */
    @Test(timeout = 60_000)
    public void abortUnblocksReadParkedOnStalledOrigin() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(BIG);
        origins.add(origin);
        origin.stallAfterBytes = 64 * 1024; // 送 64KB 后挂死，连接不关
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        final HttpSourceReader reader = newReader(source, 0);

        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        Thread decode = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    reader.read(new byte[256 * 1024], 0, 256 * 1024);
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }
        }, "fake-decode-thread");
        decode.setDaemon(true);
        decode.start();

        decode.join(2000);
        assertTrue("源端已停止供数，读满 256KB 不可能完成——它应当正卡在等待里",
                decode.isAlive());
        assertNull(thrown.get());

        reader.abort();
        decode.join(5000);
        assertFalse("abort 后解码线程必须退出，否则 nativeClose 只能泄漏解码器",
                decode.isAlive());
        assertNotNull("被取消的读必须以异常收场，静默短读会让解码器误判 EOF",
                thrown.get());
        reader.close();
    }

    /** abort 幂等，且置位后所有后续读写一律失败（不得再挂回去） */
    @Test(timeout = 30_000)
    public void abortIsIdempotentAndPoisonsSubsequentCalls() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(SMALL);
        origins.add(origin);
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        HttpSourceReader reader = newReader(source, 0);

        reader.abort();
        reader.abort();
        try {
            reader.read(new byte[16], 0, 16);
            fail("abort 之后的 read 必须立刻失败");
        } catch (IOException expected) {
            // 正确路径
        }
        assertFalse(reader.seek(0));
        reader.close();
    }

    // ===================== close ===================== //

    /** close 后读写全部拒绝 */
    @Test(timeout = 30_000)
    public void closedReaderRejectsReadAndSeek() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(SMALL);
        origins.add(origin);
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        HttpSourceReader reader = newReader(source, 0);

        reader.close();
        try {
            reader.read(new byte[16], 0, 16);
            fail("close 之后的 read 必须抛 IOException");
        } catch (IOException expected) {
            // 正确路径
        }
        assertFalse("close 之后的 seek 必须返回 false", reader.seek(0));
    }

    /** close 幂等，且只归还一次数据源引用（重复 release 会误伤代理侧的其他持有者） */
    @Test(timeout = 30_000)
    public void closeIsIdempotentAndReleasesSourceReferenceOnce() throws Exception {
        TestHttpOrigin origin = new TestHttpOrigin(SMALL);
        origins.add(origin);
        BufferedHttpSource source = newSource(origin, MIN_CAPACITY);
        HttpSourceReader reader = newReader(source, 0);

        assertEquals(1, source.getRefCount());
        reader.close();
        assertEquals(0, source.getRefCount());
        reader.close();
        assertEquals("close 必须幂等，二次调用不得再减引用", 0, source.getRefCount());
    }
}
