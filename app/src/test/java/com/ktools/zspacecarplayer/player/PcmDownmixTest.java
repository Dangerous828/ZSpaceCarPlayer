package com.ktools.zspacecarplayer.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Random;

/**
 * 多幸运 (ch=6) 刺耳修复的下混数学验收。
 *
 * 期望值在测试里用 double 浮点权重独立算出，不与实现的 Q13 定点共用公式；
 * 容差 3 LSB 覆盖 0.707 → 5793/8192 的量化误差在满幅下的累积。
 */
public class PcmDownmixTest {

    private static final double SQ = Math.sqrt(0.5); // 0.7071067811865476
    private static final double TOL = 3.0;
    private static final int AMP = 20000;

    private static short[] frameOf(int[] ch) {
        short[] src = new short[6];
        for (int c = 0; c < 6 && c < ch.length; c++) src[c] = (short) ch[c];
        return src;
    }

    private static short[] downmixOne(int[] ch, int channels) {
        short[] dst = new short[2];
        PcmDownmix.toStereo(frameOf(ch), 0, 1, channels, dst, 0);
        return dst;
    }

    @Test
    public void effectiveChannelsKeepsMonoAndStereoUntouched() {
        assertEquals(1, PcmDownmix.effectiveChannels(1));
        assertEquals(2, PcmDownmix.effectiveChannels(2));
        for (int ch = 3; ch <= 8; ch++) {
            assertEquals("ch=" + ch, 2, PcmDownmix.effectiveChannels(ch));
        }
    }

    /** 单声道激励：听感上「谁响就归谁」，且不得串到对侧。 */
    @Test
    public void unitExcitationMapsEachChannelByItsOwnWeight() {
        short[] fl = downmixOne(new int[]{AMP, 0, 0, 0, 0, 0}, 6);
        assertEquals(AMP, fl[0], TOL);
        assertEquals(0, fl[1], TOL);

        short[] fr = downmixOne(new int[]{0, AMP, 0, 0, 0, 0}, 6);
        assertEquals(0, fr[0], TOL);
        assertEquals(AMP, fr[1], TOL);

        short[] fc = downmixOne(new int[]{0, 0, AMP, 0, 0, 0}, 6);
        assertEquals(AMP * SQ, fc[0], TOL);
        assertEquals(AMP * SQ, fc[1], TOL);

        short[] lfe = downmixOne(new int[]{0, 0, 0, AMP, 0, 0}, 6);
        assertEquals(AMP * 0.5, lfe[0], TOL);
        assertEquals(AMP * 0.5, lfe[1], TOL);

        short[] bl = downmixOne(new int[]{0, 0, 0, 0, AMP, 0}, 6);
        assertEquals(AMP * SQ, bl[0], TOL);
        assertEquals(AMP * SQ, bl[1], TOL);

        short[] br = downmixOne(new int[]{0, 0, 0, 0, 0, AMP}, 6);
        assertEquals(AMP * SQ, br[0], TOL);
        assertEquals(AMP * SQ, br[1], TOL);
    }

    /**
     * 人声常在 FC 单通道：错配成 stereo 时它是「只有一边响」，下混后必须两耳等量。
     * 这条直接盯住修复本身要消除的症状。
     */
    @Test
    public void centerOnlyContentStaysCentered() {
        short[] out = downmixOne(new int[]{0, 0, AMP, 0, 0, 0}, 6);
        assertEquals("下混后左右必须等量", out[0], out[1]);
        assertTrue("中心声道不得整体丢失", Math.abs(out[0]) > AMP / 2);
    }

    /** 全通道同相满幅：累加远超 int16，必须被软膝收住而不是回绕——回绕就是爆音。 */
    @Test
    public void allChannelsFullScaleSaturatesInsteadOfWrapping() {
        int[] max = new int[]{Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE,
                Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE};
        short[] out = downmixOne(max, 6);
        assertEquals(Short.MAX_VALUE, out[0]);
        assertEquals(Short.MAX_VALUE, out[1]);

        int[] min = new int[]{Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE,
                Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE};
        short[] neg = downmixOne(min, 6);
        // 软膝对称于 0，负半轴上界是 -32767 而非 -32768
        assertEquals((short) -Short.MAX_VALUE, neg[0]);
        assertEquals((short) -Short.MAX_VALUE, neg[1]);
    }

    /**
     * 饱和曲线必须单调且连续。
     *
     * 曾写过"超过满幅才起压"的版本，于是 v=32767 原样输出、v=32768 被拉回膝区起点，
     * 一个样本掉 1600 —— 比硬钳位更响的爆音。这条按 amp 扫过整个膝区抓这类错误。
     */
    @Test
    public void saturationCurveIsMonotonicAndContinuousAcrossTheKnee() {
        short[] src = new short[6];
        short[] dst = new short[2];
        int prev = Integer.MIN_VALUE;
        for (int amp = 0; amp <= Short.MAX_VALUE; amp++) {
            src[0] = (short) amp; // FL 只进 L
            src[2] = (short) amp; // FC 按 0.707 进两侧 → L 侧 1.707x，必然穿过膝区
            PcmDownmix.toStereo(src, 0, 1, 6, dst, 0);
            int out = dst[0];
            assertTrue("饱和曲线必须单调, amp=" + amp + " out=" + out, out >= prev);
            assertTrue("相邻样本跳变过大(膝区不连续), amp=" + amp + " delta=" + (out - prev),
                    prev == Integer.MIN_VALUE || out - prev <= 2);
            assertTrue("输出不得越过满幅, amp=" + amp, out <= Short.MAX_VALUE);
            prev = out;
        }
        assertEquals("最深超载应渐近满幅而不是折返", Short.MAX_VALUE, prev);
    }

    /** 7.1 全满幅时 Q13 累加仍须留在 int 范围内（这是权重定点化选 Q13 的原因）。 */
    @Test
    public void sevenPointOneFullScaleDoesNotOverflowAccumulator() {
        short[] src = new short[8];
        for (int c = 0; c < 8; c++) src[c] = Short.MAX_VALUE;
        short[] dst = new short[2];
        PcmDownmix.toStereo(src, 0, 1, 8, dst, 0);
        assertTrue("累加溢出会回绕成负值", dst[0] > 0);
        assertTrue("累加溢出会回绕成负值", dst[1] > 0);
    }

    /** 3/4/5 声道没有 LFE，索引 3 是环绕，不能按 0.5 衰减。 */
    @Test
    public void slotThreeIsSurroundBelowSixChannels() {
        short[] quad = downmixOne(new int[]{0, 0, 0, AMP, 0, 0}, 4);
        assertEquals(AMP * SQ, quad[0], TOL);
        assertEquals(AMP * SQ, quad[1], TOL);
    }

    /** MediaCodec 走字节搬运，数学必须与 short 入口逐样本一致。 */
    @Test
    public void byteEntryMatchesShortEntryOnRandomFrames() {
        final int frames = 500, channels = 6;
        Random rnd = new Random(20260920L);
        short[] srcS = new short[frames * channels];
        for (int i = 0; i < srcS.length; i++) srcS[i] = (short) rnd.nextInt(65536);

        byte[] srcB = new byte[frames * channels * 2];
        for (int i = 0; i < srcS.length; i++) {
            srcB[i * 2] = (byte) (srcS[i] & 0xFF);
            srcB[i * 2 + 1] = (byte) ((srcS[i] >> 8) & 0xFF);
        }

        short[] dstS = new short[frames * 2];
        PcmDownmix.toStereo(srcS, 0, frames, channels, dstS, 0);

        byte[] dstB = new byte[frames * 4];
        PcmDownmix.toStereoBytes(srcB, 0, frames, channels, dstB, 0);

        for (int i = 0; i < dstS.length; i++) {
            int fromBytes = (short) ((dstB[i * 2] & 0xFF) | (dstB[i * 2 + 1] << 8));
            assertEquals("frame sample " + i, dstS[i], fromBytes);
        }
    }

    /** 多声道分块必须整帧：非整帧长度会在帧中间切断，下混读到半个样本。 */
    @Test
    public void refusesMisalignedOrOversizedChunks() {
        byte[] dst = new byte[4 * 99]; // 少一帧
        try {
            PcmDownmix.toStereoBytes(new byte[12 * 100], 0, 100, 6, dst, 0);
            fail("dst 容量不足必须抛，不能踩坏调用方内存");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("buffer out of range"));
        }
    }

    /** <=2 声道落到这里说明调用方漏判，静默透传等于没修。 */
    @Test
    public void rejectsChannelCountsThatNeedNoDownmix() {
        short[] src = new short[2 * 64];
        short[] dst = new short[2 * 64];
        for (int ch = 1; ch <= 2; ch++) {
            try {
                PcmDownmix.toStereo(src, 0, 64, ch, dst, 0);
                fail("ch=" + ch + " 不应进入下混");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("3~8"));
            }
        }
        try {
            PcmDownmix.toStereo(src, 0, 64, 9, dst, 0);
            fail("超过 MAX_CHANNELS 的布局未知，必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("3~8"));
        }
    }

    /** scratch 复用：dstOffset 之前的内容不得被覆写。 */
    @Test
    public void honoursDestinationOffset() {
        short[] src = new short[6 * 3];
        for (int i = 0; i < src.length; i++) src[i] = (short) (i * 100);
        short[] dst = new short[2 + 3 * 2];
        dst[0] = 1234;
        dst[1] = 5678;
        PcmDownmix.toStereo(src, 0, 3, 6, dst, 2);
        assertEquals(1234, dst[0]);
        assertEquals(5678, dst[1]);

        short[] same = new short[2];
        PcmDownmix.toStereo(src, 0, 1, 6, same, 0);
        assertEquals(same[0], dst[2]);
        assertEquals(same[1], dst[3]);
    }

    /** 源偏移：解码缓冲前半段残留数据不得混进本帧。 */
    @Test
    public void honoursSourceOffset() {
        short[] src = new short[6 * 4];
        for (int i = 0; i < 6; i++) src[i] = Short.MAX_VALUE; // 脏前帧
        for (int i = 6; i < 12; i++) src[i] = AMP;
        short[] dst = new short[2];
        PcmDownmix.toStereo(src, 6, 1, 6, dst, 0);
        // 6 通道等幅 → 累加远超满幅，钳到 32767 一侧
        assertEquals(Short.MAX_VALUE, dst[0]);
        assertTrue(dst[1] > 0);
    }
}
