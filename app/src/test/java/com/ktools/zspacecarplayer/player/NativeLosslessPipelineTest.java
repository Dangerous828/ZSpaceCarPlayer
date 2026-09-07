package com.ktools.zspacecarplayer.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.ktools.zspacecarplayer.dsp.NativeLosslessDecoder;
import com.ktools.zspacecarplayer.player.stream.HttpProxyServer;

import java.io.IOException;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.Random;

/**
 * JVM 侧可验证的原生解码链路纯逻辑：容器嗅探、代理 URL 抽取、
 * 文件随机读流的绝对定位契约（origin→绝对位换算在 C++ bridgeSeek 内，
 * Java 侧只承诺 seek(absolutePos) 语义）。
 */
public class NativeLosslessPipelineTest {

    @Test
    public void testSniffFormat() {
        // 1. FLAC
        byte[] flacHeader = new byte[]{'f', 'L', 'a', 'C', 0, 0, 0, 34};
        assertEquals(NativeLosslessDecoder.FMT_FLAC, NativeLosslessDecoder.sniffFormat(flacHeader));

        // 2. WAV
        byte[] wavHeader = new byte[]{
                'R', 'I', 'F', 'F', 0x24, 0, 0, 0,
                'W', 'A', 'V', 'E', 'f', 'm', 't', ' '
        };
        assertEquals(NativeLosslessDecoder.FMT_WAV, NativeLosslessDecoder.sniffFormat(wavHeader));

        // 3. MP3 ID3v2
        byte[] mp3Id3 = new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0};
        assertEquals(NativeLosslessDecoder.FMT_MP3, NativeLosslessDecoder.sniffFormat(mp3Id3));

        // 4. MP3 Sync Word (0xFF 0xFB)
        byte[] mp3Sync = new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, (byte) 0x44};
        assertEquals(NativeLosslessDecoder.FMT_MP3, NativeLosslessDecoder.sniffFormat(mp3Sync));

        // 5. Unknown / Too short
        byte[] unknown = new byte[]{0x00, 0x01, 0x02, 0x03};
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN, NativeLosslessDecoder.sniffFormat(unknown));
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN, NativeLosslessDecoder.sniffFormat(new byte[]{1, 2}));
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN, NativeLosslessDecoder.sniffFormat(null));
    }

    /**
     * 嗅探的边界与「宁可认不出、不可认错」原则。
     *
     * 认不出的后果只是回退系统解码链（可能播放失败但不崩）；认错的后果是把
     * 非 FLAC/WAV 的字节喂给 dr_flac/dr_wav，那是 native 崩溃。所以所有含糊
     * 输入都必须落到 FMT_UNKNOWN。
     */
    @Test
    public void testSniffFormatBoundaries() {
        // 恰好 4 字节的 FLAC 魔数就该认出来（嗅探只读头部，不应要求更多）
        assertEquals(NativeLosslessDecoder.FMT_FLAC,
                NativeLosslessDecoder.sniffFormat(new byte[]{'f', 'L', 'a', 'C'}));

        // 大小写敏感：大写 FLAC 不是合法原生 FLAC 容器
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN,
                NativeLosslessDecoder.sniffFormat(new byte[]{'F', 'L', 'A', 'C', 0, 0, 0, 34}));

        // RIFF 但不是 WAVE（AVI / WEBP 同用 RIFF 外壳）
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN, NativeLosslessDecoder.sniffFormat(new byte[]{
                'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'A', 'V', 'I', ' '}));

        // RIFF 头不足 12 字节：读不到 WAVE 就必须放弃，不能越界
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN, NativeLosslessDecoder.sniffFormat(new byte[]{
                'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V'}));
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN,
                NativeLosslessDecoder.sniffFormat(new byte[]{'R', 'I', 'F', 'F'}));

        // 恰好 12 字节的合法 WAV 头
        assertEquals(NativeLosslessDecoder.FMT_WAV, NativeLosslessDecoder.sniffFormat(new byte[]{
                'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E'}));

        // Ogg 容器（OggFLAC / Opus / Vorbis）：dr_flac 解不了 Ogg 封装，必须回退
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN, NativeLosslessDecoder.sniffFormat(new byte[]{
                'O', 'g', 'g', 'S', 0, 2, 0, 0}));

        // 3 字节 ID3：长度门槛是 4，认不出（真 ID3v2 头至少 10 字节，不会只给 3 个）
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN,
                NativeLosslessDecoder.sniffFormat(new byte[]{'I', 'D', '3'}));

        // 空数组不得抛异常
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN,
                NativeLosslessDecoder.sniffFormat(new byte[0]));

        // 帧同步低位边界：0xE0 是同步掩码的最低合法值，0xDF 就已经不是
        assertEquals(NativeLosslessDecoder.FMT_MP3, NativeLosslessDecoder.sniffFormat(new byte[]{
                (byte) 0xFF, (byte) 0xE0, 0, 0}));
        assertEquals(NativeLosslessDecoder.FMT_UNKNOWN, NativeLosslessDecoder.sniffFormat(new byte[]{
                (byte) 0xFF, (byte) 0xDF, 0, 0}));

        // ADTS AAC（0xFF 0xF1）会被同步掩码误判成 MP3。这是刻意接受的：
        // 它因此落到 FMT_MP3，而 FMT_MP3 不走原生软解，最终仍由系统 audio/mp4a-latm
        // 硬解接手——误判方向是安全的（错成「交给系统」而不是错成「喂给 dr_*」）。
        assertEquals(NativeLosslessDecoder.FMT_MP3, NativeLosslessDecoder.sniffFormat(new byte[]{
                (byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80}));
        assertFalse("ADTS AAC 绝不可进原生软解",
                NativeLosslessDecoder.isNativeSupportedFormat(NativeLosslessDecoder.sniffFormat(
                        new byte[]{(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80})));
    }

    /**
     * 原生软解 / 系统 MediaCodec 的分界。
     *
     * 只有 FLAC 与 WAV 下沉 native——吉利 8600 的 MediaCodec 没注册这两种解码器，
     * 直传必崩；反过来 MP3/AAC 系统有硬解，软解只会白烧 i.MX6Quad 的 CPU。
     */
    @Test
    public void testNativeFallbackBoundary() {
        assertTrue(NativeLosslessDecoder.isNativeSupportedFormat(NativeLosslessDecoder.FMT_FLAC));
        assertTrue(NativeLosslessDecoder.isNativeSupportedFormat(NativeLosslessDecoder.FMT_WAV));
        assertFalse("MP3 交给系统 audio/mpeg 硬解",
                NativeLosslessDecoder.isNativeSupportedFormat(NativeLosslessDecoder.FMT_MP3));
        assertFalse("认不出的容器必须回退，不能试着喂给 dr_*",
                NativeLosslessDecoder.isNativeSupportedFormat(NativeLosslessDecoder.FMT_UNKNOWN));

        // 嗅探结果与回退判定串起来看：任何真实头部要么走 native 无损，要么回退系统链
        byte[][] lossless = {
                {'f', 'L', 'a', 'C', 0, 0, 0, 34},
                {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E'},
        };
        for (byte[] h : lossless) {
            assertTrue("无损头部必须走原生软解",
                    NativeLosslessDecoder.isNativeSupportedFormat(NativeLosslessDecoder.sniffFormat(h)));
        }
        byte[][] systemSide = {
                {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0},
                {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x44},
                {'O', 'g', 'g', 'S', 0, 2, 0, 0},
                {'f', 't', 'y', 'p', 'M', '4', 'A', ' '},
                {0, 1, 2, 3},
        };
        for (byte[] h : systemSide) {
            assertFalse("非 FLAC/WAV 一律回退系统解码链",
                    NativeLosslessDecoder.isNativeSupportedFormat(NativeLosslessDecoder.sniffFormat(h)));
        }
    }

    @Test
    public void testFileSourceReaderAbsoluteSeekContract() throws Exception {
        File f = File.createTempFile("lossless-bridge", ".bin");
        f.deleteOnExit();
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(data);
        fos.close();

        NativeLosslessDecoder.FileSourceReader reader =
                new NativeLosslessDecoder.FileSourceReader(f);
        try {
            // 顺序读即读即推进
            byte[] buf = new byte[128];
            assertEquals(128, reader.read(buf, 0, 128));
            byte[] probe = new byte[128];
            System.arraycopy(data, 128, probe, 0, 128);
            reader.read(probe, 0, 128); // 游标推进到 256

            // 绝对定位后从新位置读
            assertTrue(reader.seek(1024));
            byte[] at1024 = new byte[64];
            assertEquals(64, reader.read(at1024, 0, 64));
            byte[] expect = new byte[64];
            System.arraycopy(data, 1024, expect, 0, 64);
            assertTrue(java.util.Arrays.equals(expect, at1024));

            // 回溯 seek（原生解码器重扫容器头必需）
            assertTrue(reader.seek(0));
            byte[] head = new byte[4];
            assertEquals(4, reader.read(head, 0, 4));
            assertTrue(java.util.Arrays.equals(new byte[]{data[0], data[1], data[2], data[3]}, head));

            // EOF：读到文件尾返回 -1
            assertTrue(reader.seek(4090));
            byte[] tail = new byte[16];
            assertEquals(6, reader.read(tail, 0, 16));
            assertEquals(-1, reader.read(tail, 0, 16));

            // 越界负位拒绝；EOF 后再 seek 回窗口内可继续
            assertFalse(reader.seek(-1));
            assertTrue(reader.seek(2000));
            assertEquals(16, reader.read(tail, 0, 16));
        } finally {
            reader.close();
        }
        // close 幂等；关闭后 seek 返回 false、read 抛 IOException（契约：false = 不可达）
        reader.close();
        assertFalse(reader.seek(0));
        try {
            reader.read(new byte[4], 0, 4);
            fail("read after close must throw");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void testExtractRemoteUrl() throws Exception {
        String original = "http://192.168.31.152:8096/Audio/123/stream.mp3?api_key=xyz&static=true";
        String proxyUrl = "http://127.0.0.1:30141/stream?u=" + URLEncoder.encode(original, "UTF-8");

        assertEquals(original, HttpProxyServer.extractRemoteUrl(proxyUrl));
        assertEquals(original, HttpProxyServer.extractRemoteUrl(original));
        assertEquals("", HttpProxyServer.extractRemoteUrl(null));
    }
}
