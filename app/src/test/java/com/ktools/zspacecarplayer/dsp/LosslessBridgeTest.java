package com.ktools.zspacecarplayer.dsp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ktools.zspacecarplayer.dsp.NativeLosslessDecoder.LosslessStreamReader;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * JNI 回调桥（bridgeRead / bridgeSeek / bridgeAbort）的返回值语义。
 *
 * 这三个方法是 native 解码器唯一的取数入口，也是「解码失败 → 回退系统
 * MediaCodec」这条链的起点：dr_* 只认整数返回值，Java 侧任何异常若穿透到
 * JNI 边界，都会变成 pending exception 直接把进程带崩，回退根本没机会发生。
 * 所以桥必须把失败一律折叠成 0 / false，让 native 干净收场。
 *
 * 用桩 reader 而不是真实网络源：这里要验的是折叠语义本身，与数据从哪来无关。
 */
public class LosslessBridgeTest {

    /** 记录调用轨迹的桩 reader */
    private static class StubReader extends LosslessStreamReader {
        final byte[] data;
        long cursor = 0;
        boolean seekResult = true;
        boolean readThrows = false;
        boolean seekThrows = false;
        int readCalls = 0;
        int seekCalls = 0;
        int abortCalls = 0;
        int closeCalls = 0;
        long lastSeekPos = Long.MIN_VALUE;

        StubReader(byte[] data) {
            this.data = data;
        }

        @Override
        public int read(byte[] dest, int offset, int length) throws IOException {
            readCalls++;
            if (readThrows) {
                throw new IOException("simulated network failure");
            }
            if (cursor >= data.length) {
                return -1;
            }
            int n = (int) Math.min(length, data.length - cursor);
            System.arraycopy(data, (int) cursor, dest, offset, n);
            cursor += n;
            return n;
        }

        @Override
        public boolean seek(long absolutePos) {
            seekCalls++;
            lastSeekPos = absolutePos;
            if (seekThrows) {
                throw new IllegalStateException("simulated reader blowup");
            }
            if (!seekResult) {
                return false;
            }
            cursor = absolutePos;
            return true;
        }

        @Override
        public void abort() {
            abortCalls++;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 7 + 3);
        }
        return b;
    }

    // ===================== bridgeRead ===================== //

    @Test
    public void bridgeReadReturnsReaderResultAndAdvancesNothing() {
        byte[] data = payload(1024);
        StubReader reader = new StubReader(data);

        byte[] buf = new byte[256];
        int n = NativeLosslessDecoder.bridgeRead(reader, 0, buf, 0, 256);

        assertEquals(256, n);
        assertArrayEquals(Arrays.copyOfRange(data, 0, 256), buf);
        assertEquals("桥只负责转发，定位应由它自己发起", 1, reader.seekCalls);
        assertEquals(1, reader.readCalls);
    }

    /**
     * destOffset 必须被如实传递：JNI 侧复用一个 64KB 全局缓冲，偏移写错就会把
     * 上一块的尾巴当成音频数据喂给解码器（听感是规律的爆音）。
     */
    @Test
    public void bridgeReadHonoursDestinationOffset() {
        byte[] data = payload(512);
        StubReader reader = new StubReader(data);

        byte[] buf = new byte[64];
        Arrays.fill(buf, (byte) 0xAA);
        int n = NativeLosslessDecoder.bridgeRead(reader, 0, buf, 16, 32);

        assertEquals(32, n);
        byte[] expect = new byte[64];
        Arrays.fill(expect, (byte) 0xAA);
        System.arraycopy(data, 0, expect, 16, 32);
        assertArrayEquals("只有 [offset, offset+length) 区间该被写入", expect, buf);
    }

    /** fromPos 是绝对字节位，必须原样交给 reader（origin→绝对的换算在 C++ 侧） */
    @Test
    public void bridgeReadPassesAbsolutePositionThrough() {
        StubReader reader = new StubReader(payload(4096));

        byte[] buf = new byte[64];
        NativeLosslessDecoder.bridgeRead(reader, 3000, buf, 0, 64);

        assertEquals(3000L, reader.lastSeekPos);
        assertArrayEquals(Arrays.copyOfRange(reader.data, 3000, 3064), buf);
    }

    /**
     * reader 抛 IOException（断网 / 源已关闭 / 被 abort）时必须折叠成 0。
     * 0 是 dr_* 认识的「流中断」，异常穿透 JNI 边界则是进程级崩溃。
     */
    @Test
    public void bridgeReadCollapsesIoExceptionToZero() {
        StubReader reader = new StubReader(payload(1024));
        reader.readThrows = true;

        assertEquals("异常必须折叠成 0，不能穿透 JNI 边界",
                0, NativeLosslessDecoder.bridgeRead(reader, 0, new byte[64], 0, 64));
    }

    /** seek 不可达时返回 0，且不得再去读（游标位置已不可信） */
    @Test
    public void bridgeReadReturnsZeroWhenSeekFails() {
        StubReader reader = new StubReader(payload(1024));
        reader.seekResult = false;

        assertEquals(0, NativeLosslessDecoder.bridgeRead(reader, 900, new byte[64], 0, 64));
        assertEquals("seek 失败后不该再发起读", 0, reader.readCalls);
    }

    /** seek 自身抛异常时同样折叠成 0 */
    @Test
    public void bridgeReadCollapsesSeekExceptionToZero() {
        StubReader reader = new StubReader(payload(1024));
        reader.seekThrows = true;

        assertEquals(0, NativeLosslessDecoder.bridgeRead(reader, 0, new byte[64], 0, 64));
        assertEquals(0, reader.readCalls);
    }

    /** EOF 的 -1 必须原样透传：折叠成 0 会让解码器把正常曲尾当成流中断 */
    @Test
    public void bridgeReadPropagatesEof() {
        StubReader reader = new StubReader(payload(16));

        assertEquals(-1, NativeLosslessDecoder.bridgeRead(reader, 16, new byte[64], 0, 64));
    }

    @Test
    public void bridgeReadToleratesNullReader() {
        assertEquals(0, NativeLosslessDecoder.bridgeRead(null, 0, new byte[16], 0, 16));
    }

    // ===================== bridgeSeek ===================== //

    @Test
    public void bridgeSeekForwardsAbsolutePosition() {
        StubReader reader = new StubReader(payload(4096));

        assertTrue(NativeLosslessDecoder.bridgeSeek(reader, 1234));
        assertEquals(1234L, reader.lastSeekPos);
    }

    /** 负位在桥这一层就拦掉，不必让 reader 再判一次 */
    @Test
    public void bridgeSeekRejectsNegativeWithoutTouchingReader() {
        StubReader reader = new StubReader(payload(1024));

        assertFalse(NativeLosslessDecoder.bridgeSeek(reader, -1));
        assertEquals("非法位置不该惊动 reader", 0, reader.seekCalls);
    }

    @Test
    public void bridgeSeekCollapsesExceptionToFalse() {
        StubReader reader = new StubReader(payload(1024));
        reader.seekThrows = true;

        assertFalse(NativeLosslessDecoder.bridgeSeek(reader, 100));
    }

    @Test
    public void bridgeSeekPropagatesUnreachableAsFalse() {
        StubReader reader = new StubReader(payload(1024));
        reader.seekResult = false;

        assertFalse(NativeLosslessDecoder.bridgeSeek(reader, 100));
    }

    @Test
    public void bridgeSeekToleratesNullReader() {
        assertFalse(NativeLosslessDecoder.bridgeSeek(null, 0));
    }

    // ===================== bridgeAbort ===================== //

    /**
     * nativeClose 内部靠 bridgeAbort 唤醒卡在等网络供数上的解码线程。
     * 它必须是幂等且绝不抛的——close 路径上再抛一次异常，就正好复现了
     * 「关不掉只能泄漏解码器」的那个故障。
     */
    @Test
    public void bridgeAbortReachesReaderAndIsIdempotent() {
        StubReader reader = new StubReader(payload(1024));

        NativeLosslessDecoder.bridgeAbort(reader);
        NativeLosslessDecoder.bridgeAbort(reader);

        assertEquals(2, reader.abortCalls);
    }

    @Test
    public void bridgeAbortToleratesNullReader() {
        NativeLosslessDecoder.bridgeAbort(null);
    }

    /** 基类的 abort 默认空实现：本地文件 reader 不会阻塞，不需要取消 */
    @Test
    public void defaultAbortIsNoOp() {
        final int[] reads = new int[1];
        LosslessStreamReader local = new LosslessStreamReader() {
            @Override
            public int read(byte[] dest, int offset, int length) {
                reads[0]++;
                return -1;
            }

            @Override
            public boolean seek(long absolutePos) {
                return true;
            }

            @Override
            public void close() {
            }
        };
        local.abort();
        assertEquals(-1, NativeLosslessDecoder.bridgeRead(local, 0, new byte[8], 0, 8));
        assertEquals(1, reads[0]);
    }
}
