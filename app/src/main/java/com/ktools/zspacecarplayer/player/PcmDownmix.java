package com.ktools.zspacecarplayer.player;

/**
 * 多声道 (3~8) 交错 16-bit PCM 下混为立体声。
 *
 * 存在理由：车机曲库里混有 5.1 WAV（如「多幸运」ch=6）。C++ DSP 只实现了 1/2 声道分支，
 * AudioTrack 也只能建 mono/stereo，多声道母带必须先下混再进链路，否则 6 声道数据被当立体声
 * 两两错配消费（相位抵消 + LFE 进全频单元 + 同样内容拉成 3 倍时长），听感即刺耳。
 *
 * 权重（ITU-R BS.775 的 Lo/Ro 式）：L = FL + 0.707·FC + 0.707·环绕 + 0.5·LFE，R 对称。
 * 0.707 是等功率声像法则，保证中心/环绕并入双耳后总声压不衰减。
 * LFE 取 0.5 而非丢弃：车机没有独立低音炮，LFE 只能由全频单元发声，丢弃会让 5.1 母带低频
 * 偏薄；但 LFE 常是大动态纯低频，按 0.707 并入会把下级 BassBoost 推到频繁限幅。
 */
public final class PcmDownmix {

    /**
     * 定点 Q13 权重。取 Q13 而非更高：8 声道全满幅时累加值仍需留在 int 范围内，
     * 累加溢出不是 Java 异常而是静默错值。
     */
    private static final int Q = 13;
    private static final int W_FRONT = 1 << Q;                              // 1.0
    private static final int W_SURROUND = (int) Math.round(0.7071067811865476 * (1 << Q));
    private static final int W_LFE = 1 << (Q - 1);                          // 0.5
    private static final int ROUND = 1 << (Q - 1);

    /** 与 native-lib.cpp 的 MAX_CHANNELS 一致；超过即通道布局未知，拒绝猜测。 */
    private static final int MAX_CHANNELS = 8;

    /** 软膝起点 0.95 满幅，与 SoftLimiter.h 的 threshold 同值。 */
    private static final int KNEE = 31129;
    private static final int CEILING = Short.MAX_VALUE;

    private PcmDownmix() {}

    /** 下混后真正进 DSP 与 AudioTrack 的声道数。 */
    public static int effectiveChannels(int srcChannels) {
        return srcChannels > 2 ? 2 : srcChannels;
    }

    /** 交错多声道 short[] → 交错立体声 short[]；frames 是帧数，源声道数须 3~8。 */
    public static void toStereo(short[] src, int srcOffset, int frames, int channels,
                                short[] dst, int dstOffset) {
        check(frames, channels, src.length, srcOffset, frames * channels,
                dst.length, dstOffset, frames * 2);
        // 索引 3 只在 5.1 及以上是 LFE；3/4/5 声道布局里那是又一个环绕声道
        final int slot3 = (channels >= 6) ? W_LFE : W_SURROUND;

        int si = srcOffset;
        int di = dstOffset;
        for (int f = 0; f < frames; f++) {
            int sumL = 0;
            int sumR = 0;
            for (int c = 0; c < channels; c++) {
                int s = src[si + c];
                sumL += gainL(c, slot3) * s;
                sumR += gainR(c, slot3) * s;
            }
            dst[di] = roundAndClamp(sumL);
            dst[di + 1] = roundAndClamp(sumR);
            si += channels;
            di += 2;
        }
    }

    /**
     * 交错多声道小端 PCM byte[] → 交错立体声小端 byte[]。
     * MediaCodec 回退路径按字节搬运 PCM，走这个入口；数学与 {@link #toStereo} 完全一致。
     */
    public static void toStereoBytes(byte[] src, int srcOffset, int frames, int channels,
                                     byte[] dst, int dstOffset) {
        check(frames, channels, src.length, srcOffset, frames * channels * 2,
                dst.length, dstOffset, frames * 4);
        final int slot3 = (channels >= 6) ? W_LFE : W_SURROUND;
        final int srcStep = channels * 2;

        int si = srcOffset;
        int di = dstOffset;
        for (int f = 0; f < frames; f++) {
            int sumL = 0;
            int sumR = 0;
            for (int c = 0; c < channels; c++) {
                int pos = si + c * 2;
                int s = (short) ((src[pos] & 0xFF) | (src[pos + 1] << 8));
                sumL += gainL(c, slot3) * s;
                sumR += gainR(c, slot3) * s;
            }
            short l = roundAndClamp(sumL);
            short r = roundAndClamp(sumR);
            dst[di] = (byte) (l & 0xFF);
            dst[di + 1] = (byte) ((l >> 8) & 0xFF);
            dst[di + 2] = (byte) (r & 0xFF);
            dst[di + 3] = (byte) ((r >> 8) & 0xFF);
            si += srcStep;
            di += 4;
        }
    }

    /**
     * 通道 c 送入左声道的 Q13 增益。slot3 为索引 3 的权重（5.1 起是 LFE，否则环绕）。
     * 左右两侧的权重都在这里，避免 short / byte 两条路径各写一份而算出不同结果。
     */
    private static int gainL(int c, int slot3) {
        if (c == 0) return W_FRONT;
        if (c == 1) return 0;
        return (c == 3) ? slot3 : W_SURROUND;
    }

    private static int gainR(int c, int slot3) {
        if (c == 1) return W_FRONT;
        if (c == 0) return 0;
        return (c == 3) ? slot3 : W_SURROUND;
    }

    /**
     * 定点累加 → int16：先补半个量化单位再算术右移。
     *
     * 超过 0.95 满幅的部分走 tanh 软膝，而不是硬钳位——硬切削会留下陡边，且被砍掉的波峰
     * 信息在 int16 域已经丢失，链尾 SoftLimiter 再怎么压也救不回来。膝点与曲线都跟
     * SoftLimiter.h 对齐（threshold 0.95），等于把同一道限幅提前到下混这一步。
     * 膝从 KNEE 起压而非从满幅起压：否则 v 刚过 32767 时输出会被拉回膝区起点，
     * 在 32767→32768 处产生向下跳变。
     */
    private static short roundAndClamp(int accumulator) {
        int v = (accumulator + ROUND) >> Q;
        if (v > KNEE) return softKnee(v);
        if (v < -KNEE) return (short) -softKnee(-v);
        return (short) v;
    }

    /** 软膝单调递增且以 CEILING 为上界，绝不回绕成反相位样本（那才是真爆音）。 */
    private static short softKnee(int absValue) {
        float span = CEILING - KNEE;
        float compressed = KNEE + span * (float) Math.tanh((absValue - KNEE) / span);
        return (short) Math.min(compressed, CEILING);
    }

    /**
     * 渲染热路径的边界闸。容量不足会踩坏调用方的 scratch 数组，
     * channels &lt;= 2 落在这里则说明调用方漏判——静默透传等于没修 bug，所以一律抛。
     */
    private static void check(int frames, int channels,
                              int srcLen, int srcOffset, int srcNeed,
                              int dstLen, int dstOffset, int dstNeed) {
        if (channels < 3 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "PcmDownmix handles 3~8 channels, got " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be > 0, got " + frames);
        }
        if (srcOffset < 0 || dstOffset < 0
                || srcOffset + srcNeed > srcLen || dstOffset + dstNeed > dstLen) {
            throw new IllegalArgumentException("buffer out of range: src=" + srcLen
                    + " +" + srcOffset + " need " + srcNeed + " | dst=" + dstLen
                    + " +" + dstOffset + " need " + dstNeed + " (frames=" + frames
                    + ", ch=" + channels + ")");
        }
    }
}
