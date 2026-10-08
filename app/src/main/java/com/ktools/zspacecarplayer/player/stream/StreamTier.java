package com.ktools.zspacecarplayer.player.stream;

/**
 * 音质档位——<b>由车主手选，绝不自动切换</b>。
 *
 * <p>存在理由（2026-10-08 T1 基准重审，{@code docs/audit_t1_baseline_20261008.md} L2）：市面
 * T1 全部把"用哪一档"作为用户可选项，并把所需带宽讲明白（Amazon：HD 1.5–2Mbps、Ultra HD
 * 5–10Mbps；YouTube Music：Wi-Fi / 蜂窝 / 下载三套独立档）。我们此前只有一条自动选择的取流
 * 形态，外加一个<b>已被车主否决</b>的自动降档（见 {@link StreamRateGovernor#AUTO_DEGRADE_ENABLED}）。
 * 这一层把选择权交回去：档位只改<b>下一首</b>的取流 URL，链路撑不起无损时由 app 明示，
 * 换不换由人说。
 *
 * <p><b>档位名按服务端实测写，不按请求参数写</b>：2026-10-08 拿同一首歌（181.812s）实测，
 * {@code maxStreamingBitrate=128000 / 320000}、{@code audioBitRate=128000 / 320000}、
 * {@code MaxStreamingBitrate}（大写）、加 {@code enableTranscoding=true}、加
 * {@code audioCodec+audioBitRate+audioSampleRate}——七种请求返回的字节数<b>完全相同</b>
 * （5,819,950B = 256.1kbps）。也就是说这台 Jellyfin 的 mp3 转码固定 256kbps，码率参数一概不认。
 * 所以"流畅档"的真实代价是 <b>31.3 KB/s</b>（仍然只有无损的约 1/3），而不是标签上的 128k；
 * 把 128 写进用户可见文案就是假广告。请求参数仍然带着（无害，且日后服务端配置改了会自动生效）。
 */
public final class StreamTier {

    /** 原件直传：入库时定的那条 URL（无损 FLAC/WAV/mp3 原件），音质最好、要带宽最多。 */
    public static final int LOSSLESS = 0;
    /** 流畅：服务端转码 mp3，实测固定 256kbps = 31.3KB/s。 */
    public static final int SMOOTH = 1;

    /** 默认档：不动音质。降级是车主的权利，不是我们的默认行为。 */
    public static final int DEFAULT = LOSSLESS;

    private StreamTier() {
    }

    /** 用户可见的档位名（设置页那颗 pill）。 */
    public static String label(int tier) {
        return tier == SMOOTH ? "流畅" : "无损";
    }

    /**
     * 档位说明（设置页副标题）。数字全部来自实测：本库无损原件实测需 100~151KB/s
     * （2026-10-07 Bad Romance 44,499,871B/294,661ms = 151KB/s），流畅档实测 31.3KB/s。
     */
    public static String describe(int tier) {
        return tier == SMOOTH
                ? "服务端转码 256kbps，约需 32KB/s"
                : "原始文件直传，本库实测需 100~151KB/s";
    }

    /** 合法性钳制：SharedPreferences 里可能被写进越界值（旧版本残留/手改）。 */
    public static int clamp(int tier) {
        return tier == SMOOTH ? SMOOTH : LOSSLESS;
    }

    /** 循环切换：无损 → 流畅 → 无损。设置页一行放不下一个列表，沿用引擎/夜间档的循环式交互。 */
    public static int next(int tier) {
        return clamp(tier) == SMOOTH ? LOSSLESS : SMOOTH;
    }
}
