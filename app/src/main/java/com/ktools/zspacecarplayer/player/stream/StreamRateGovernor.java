package com.ktools.zspacecarplayer.player.stream;

/**
 * 带宽缺口的判定核心（只判定；是否据此换档由 {@link #AUTO_DEGRADE_ENABLED} 决定）。
 *
 * <p>存在理由：本库无损曲目需要约 100~151 KB/s 的**持续**带宽（2026-10-07 实测 Bad Romance 的
 * FLAC 是 44,499,871B / 294,661ms = 151 KB/s），而车机蜂窝链路会长时间只给到这个数的一半
 * （2026-10-06 真车：23,021,268B / 204s 的 FLAC 需要 110 KB/s，实测下载 67 KB/s，{@code lead}
 * 从 4s 一路掉到 0s，屏幕上就是「能出声但一直在抽干」）。这种**持续缺口**不是缓冲能补的——
 * 环形窗口再大也只是把饿死往后推几十秒。
 *
 * <p><b>判据必须是「本曲所需速率」，不能是任何固定下限</b>。vc16 用固定 140KB/s，2026-10-07 真车
 * 两件事同时把它证伪：① 直播一首 MP3 原件天然只跑约 30KB/s，链路完全健康也会被判成带宽不足；
 * ② 一首下载完成的歌零增长就是 0B/s，同样被判成带宽不足。当晚实测序列是 percent 87→100
 * （lead 稳在 65s，链路毫无问题）→ 9 秒后 {@code bitrate tier -> smooth(128k) est=-1KB/s}
 * （那个 -1 就是实测 0）→ 之后 4 首全部在流畅档被腰斩。
 *
 * <p><b>流畅档的固有代价（2026-10-07 拿设备自己的 key 打服务端实测）</b>：它走
 * {@code stream.mp3?...static=false&maxStreamingBitrate=}，是 <b>chunked、无 Content-Length、
 * Accept-Ranges: none、Range 请求被忽略</b>的。后果连着三条：{@code dur=0ms} 让进度条与剩余时长
 * 全瞎、seek 失效（续播点丢）、以及任何一次按字节重连都退化成「服务端从 0 重转 + 客户端丢弃
 * 已下字节」。第三条已在 {@link BufferedHttpSource#midFlightRetryUseful} 处直接禁止；前两条
 * 是这一档的固有属性。<b>自动换档已被车主否决 (2026-10-08)，见 {@link #AUTO_DEGRADE_ENABLED}</b>，
 * 这里只留证据与手动入口 {@link #forceDegraded()}，以及万一有人手动用了这一档时的
 * 止损阀 {@link #abortDegrade()}。
 *
 * <p>换档（若启用）只作用于<b>下一首</b>：中途改 URL 一定会断音，所以这里只出结论，
 * 切换由服务层在起播边界执行。
 */
public final class StreamRateGovernor {

    /** 判定窗口：5 秒一个样本。再短会被一次重连带偏，再长则饿死已经发生了。 */
    public static final long WINDOW_MS = 5_000L;
    /** 连续这么多个窗口追不上所需速率才认定缺口；单窗口抖动（一次重连、一次红灯）不足以定性。 */
    public static final int DEGRADE_CONFIRMATIONS = 3;
    /** 回到无损要连续这么多窗口有富余——比认定缺口更保守，避免来回跳档。 */
    public static final int RESTORE_CONFIRMATIONS = 5;
    /** 认定缺口的上浮：实测 &lt; 所需 ×(1+15%) 即算追不上——播放本身就要吃掉 100% 的所需速率。 */
    public static final int DEFICIT_MARGIN_PERCENT = 15;
    /** 认定富余的上浮：实测 &gt; 所需 ×(1+45%) 才算链路真的恢复了。 */
    public static final int SURPLUS_MARGIN_PERCENT = 45;
    /**
     * 是否允许按判定结果自动换到流畅档。
     *
     * <p>这条开关历史上被推翻了两次，每次都因为"少了一环证据"，所以把判据写全：
     * ① 10-07 关：流畅档当时会腰斩，因为它 chunked、无总长、Range 被忽略；
     * ② 10-08 开：我以为 ③④ 已修就打开了它，当天被车主的一个数字推翻——车机 10 秒下完 3.1MB
     *    的 OTA 包（&gt;300KB/s），而我"测"出来的是 107KB/s。原因是样本取自 {@code bufEnd}：
     *    8MB 窗饱和时它只跟着读者走，于是 {@code est} 恒等于播放消耗速率，健康链路也必然满足
     *    {@code est < 所需×1.15}，也就是说这个坏口径会<b>凭空造出缺口</b>；
     * ③ 10-08 再关（vc20）：换成"只认 socket 真收字节 + 窗口饱和时不采"，但在真车上拿到可信
     *    数字前不让它接管档位。
     *
     * <p><b>④ 10-08 车主明确否决自动降档，本值必须为 false</b>：判定结果只累计成
     * {@code bitrate deficit #N} 证据，换档只保留手动入口（{@link #forceDegraded()}）。
     * 否决的理由不是舍不得音质，而是<b>"链路不够"这个前提从来没被证明过</b>。同一台 Mac、
     * 同一条手机热点、上报里那首歌的同一份文件做对照：一条长连接取 5MB = <b>246KB/s</b>，
     * 同样 5MB 拆成 20 条独立请求（单条首字节握手就要 1.15s）= <b>74KB/s</b>；
     * 而我在车上"测"出来的恰好就是 60~94KB/s 这个带。也就是说我拿来论证"必须降档"的那个数字，
     * 量的很可能是<b>我自己的取流形状</b>——下载线程一没有读者挂窗就退出、每次重定位都清窗重连，
     * 正是那 20 条请求的形状。在这两笔账（{@code upstream conns=} / {@code discarded=}）
     * 于真车上判掉之前，不许拿"链路不够"去换音质。
     */
    public static final boolean AUTO_DEGRADE_ENABLED = false;

    private boolean degraded = false;
    private int belowStreak = 0;
    private int aboveStreak = 0;
    private long windowBaseMs = -1L;
    private long windowBaseBytes = -1L;
    /** 最近一个完整窗口的实测速率（B/s）；-1 表示还没凑出过窗口。仅用于日志与上报。 */
    private long lastWindowBytesPerSec = -1L;
    /** 最近一次判定所用的所需速率（B/s），只为把证据写进面包屑。 */
    private long lastRequiredBytesPerSec = -1L;
    /** 累计认定「持续追不上」的次数（连续窗口达标算一次）。自动档关闭时这就是在攒的证据。 */
    private int deficitConfirmations = 0;

    /**
     * 喂一次下载进度。
     *
     * <p>{@code totalBytes} 是<b>累计</b>字节数（就是 {@code bufEnd} 本身），不是增量：调用方不需要
     * 自己算差值，而窗口速率 = (本次累计 - 窗口基准累计) / (本次时间 - 窗口基准时间)，跨窗口不重复计数。
     * 不足一个窗口的调用只推进累计值，不做判定。
     *
     * @param nowMs               单调时钟毫秒（{@code SystemClock.elapsedRealtime()}），必须不回拨
     * @param totalBytes          本连接开始以来累计下载的字节数
     * @param requiredBytesPerSec 本曲「不抽干」所需的速率 = 资源总字节 / 时长；{@code <= 0} 表示
     *                            口径不可判（总长或时长任一未知），此时**不做任何判定**——否则一次
     *                            不可判的采样就会被算成 0B/s 的缺口，那正是 vc16 误判的成因
     */
    public void onProgress(long nowMs, long totalBytes, long requiredBytesPerSec) {
        if (windowBaseMs < 0) {
            windowBaseMs = nowMs;
            windowBaseBytes = totalBytes;
            return;
        }
        if (totalBytes < windowBaseBytes) {
            // 累计口径的前提被打破：本流被**重定位**了（换曲、后向 seek、或原生二分内部的
            // 自动重定位——requestResetLocked 会把 bufStart/bufEnd 一起挪到新位置）。
            // 这跟"链路没给字节"是两件事，绝不能钳成 0B/s 计一个缺口窗口——vc16 就是这么
            // 在每次换曲时白送一个假缺口的（上一首累计 44MB，新歌从 0 数起）。
            // 只重建基准、不动任何已有结论；调用方各自的 rebaseWindow() 是锦上添花，不是依赖。
            windowBaseMs = nowMs;
            windowBaseBytes = totalBytes;
            return;
        }
        long elapsed = nowMs - windowBaseMs;
        if (elapsed < WINDOW_MS) {
            return;
        }
        long est = (totalBytes - windowBaseBytes) * 1000L / elapsed;
        lastWindowBytesPerSec = est;
        windowBaseMs = nowMs;
        windowBaseBytes = totalBytes;
        if (requiredBytesPerSec <= 0L) {
            return;
        }
        lastRequiredBytesPerSec = requiredBytesPerSec;
        evaluate(est, requiredBytesPerSec);
    }

    private void evaluate(long est, long required) {
        long deficitBound = required + required * DEFICIT_MARGIN_PERCENT / 100L;
        long surplusBound = required + required * SURPLUS_MARGIN_PERCENT / 100L;
        if (!degraded) {
            aboveStreak = 0;
            if (est < deficitBound) {
                if (++belowStreak >= DEGRADE_CONFIRMATIONS) {
                    deficitConfirmations++;
                    belowStreak = 0;
                    if (AUTO_DEGRADE_ENABLED) {
                        degraded = true;
                    }
                }
            } else {
                belowStreak = 0;
            }
            return;
        }
        belowStreak = 0;
        if (est > surplusBound) {
            if (++aboveStreak >= RESTORE_CONFIRMATIONS) {
                degraded = false;
                aboveStreak = 0;
            }
        } else {
            aboveStreak = 0;
        }
    }

    /** 当前是否应该用流畅档起播下一首；自动档关闭时只可能因 {@link #forceDegraded()} 为真。 */
    public boolean isDegraded() {
        return degraded;
    }

    /** 起播前强制降级（用户在设置里手动选「优先流畅」时用）；不影响回升判定。 */
    public void forceDegraded() {
        degraded = true;
        belowStreak = 0;
        aboveStreak = 0;
    }

    /**
     * 放弃流畅档，退回无损 (2026-10-08)。
     *
     * <p>这是自动降档的止损阀：档位是靠"链路追不上无损"推出来的，但如果连降档后的歌都救不回来
     * （服务层已经带断点重试过一次仍截断），继续留着这一档只会把后面每一首一起拖下去——10-07
     * 那晚就是档位翻转后连着 4 首全部腰斩。宁可退回"能出声但会抽干"，也不要"整批都放不完"。
     * 之后若链路确实变差，判定窗口会再把档位推上去，代价只是一首歌。
     *
     * @return 本次调用是否真的改变过档位
     */
    public boolean abortDegrade() {
        if (!degraded) {
            return false;
        }
        degraded = false;
        belowStreak = 0;
        aboveStreak = 0;
        return true;
    }

    /** 起播/拖动进度条后调用：累计口径被重连或 seek 打断，窗口基准作废重建，但**不推翻已有结论**
     *  —— 档位是链路属性，seek 不会让链路变好或变坏；若这里洗掉状态，用户每拖一次条又要重新
     *  等 15 秒才判回来。{@link #onProgress} 内部也会自己发现口径倒退并重建基准，这里是显式入口。 */
    public void rebaseWindow() {
        windowBaseMs = -1L;
        windowBaseBytes = -1L;
    }

    public long getLastWindowBytesPerSec() {
        return lastWindowBytesPerSec;
    }

    public long getLastRequiredBytesPerSec() {
        return lastRequiredBytesPerSec;
    }

    /** 认定过多少次「持续追不上」。自动档关闭时，这是当晚链路是否真有缺口的唯一留痕。 */
    public int getDeficitConfirmations() {
        return deficitConfirmations;
    }
}
