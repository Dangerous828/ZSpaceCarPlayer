package com.ktools.zspacecarplayer.player.stream;

/**
 * 带宽自适应降码率的判定核心。
 *
 * <p>存在理由：无损曲目在本库里需要约 100~126 KB/s 的**持续**带宽，而车机蜂窝链路会长时间只给到
 * 这个数的一半（2026-10-06 真车实测：23,021,268B / 204s 的 FLAC 需要 110 KB/s，实测下载 67 KB/s，
 * {@code lead} 从 4s 一路掉到 0s，屏幕上就是「能出声但一直在抽干」）。这种**持续缺口**不是缓冲能
 * 补的——环形窗口再大也只是把饿死往后推几十秒。唯一能真正止住的是换更低码率的流。
 *
 * <p>判据用<b>固定速率下限</b>而不是「按单曲所需码率」：后者要拿每首的 sizeBytes，而 sizeBytes 不在
 * 车机的曲库缓存里，加一列就要动 SQLite 迁移；而本库无损曲的码率本就均匀（100~126 KB/s），
 * 一个带余量的固定下限更简单，也少一个"算出来但其实不准"的输入。
 *
 * <p>降级只作用于<b>下一首</b>：中途改 URL 一定会断音，所以这里只出结论，切换由服务层在起播边界执行。
 */
public final class StreamRateGovernor {

    /** 判定窗口：5 秒一个样本。再短会被一次重连带偏，再长则饿死已经发生了。 */
    public static final long WINDOW_MS = 5_000L;
    /** 连续这么多个窗口低于下限才降级；单窗口抖动（一次重连、一次红灯）不足以换档。 */
    public static final int DEGRADE_CONFIRMATIONS = 3;
    /** 回到无损要连续这么多窗口高于回升阈值——比降级更保守，避免来回跳档。 */
    public static final int RESTORE_CONFIRMATIONS = 5;
    /** 降级下限：本库无损约 110~126 KB/s，留 ~15% 余量。低于它就必然追不上播放消耗。 */
    public static final long DEGRADE_BELOW_BYTES_PER_SEC = 140_000L;
    /** 回升阈值刻意高于降级下限，两者之间是滞回带，不在带里做任何切换。 */
    public static final long RESTORE_ABOVE_BYTES_PER_SEC = 175_000L;

    private boolean degraded = false;
    private int belowStreak = 0;
    private int aboveStreak = 0;
    private long windowBaseMs = -1L;
    private long windowBaseBytes = -1L;
    /** 最近一个完整窗口的实测速率（B/s）；-1 表示还没凑出过窗口。仅用于日志与上报。 */
    private long lastWindowBytesPerSec = -1L;

    /**
     * 喂一次下载进度。由 {@code BufferedHttpSource} 的进度推进处调用。
     *
     * <p>参数是<b>累计</b>字节数（就是 {@code bufEnd} 本身），不是增量：调用方不需要自己算差值，
     * 而窗口速率 = (本次累计 - 窗口基准累计) / (本次时间 - 窗口基准时间)，跨窗口不会重复计数。
     * 不足一个窗口的调用只更新基准之前的累计值，不做判定。
     *
     * @param nowMs      单调时钟毫秒（{@code SystemClock.elapsedRealtime()}），必须不回拨
     * @param totalBytes 本连接开始以来累计下载的字节数
     */
    public void onProgress(long nowMs, long totalBytes) {
        if (windowBaseMs < 0) {
            windowBaseMs = nowMs;
            windowBaseBytes = totalBytes;
            return;
        }
        long elapsed = nowMs - windowBaseMs;
        if (elapsed < WINDOW_MS) {
            return;
        }
        long est = (totalBytes - windowBaseBytes) * 1000L / elapsed;
        if (est < 0L) {
            est = 0L;   // 上层换了连接、累计值被清零：按无进展处理，不得算出负速率
        }
        lastWindowBytesPerSec = est;
        windowBaseMs = nowMs;
        windowBaseBytes = totalBytes;
        evaluate(est);
    }

    private void evaluate(long est) {
        if (!degraded) {
            aboveStreak = 0;
            if (est < DEGRADE_BELOW_BYTES_PER_SEC) {
                if (++belowStreak >= DEGRADE_CONFIRMATIONS) {
                    degraded = true;
                    belowStreak = 0;
                }
            } else {
                belowStreak = 0;
            }
            return;
        }
        belowStreak = 0;
        if (est > RESTORE_ABOVE_BYTES_PER_SEC) {
            if (++aboveStreak >= RESTORE_CONFIRMATIONS) {
                degraded = false;
                aboveStreak = 0;
            }
        } else {
            aboveStreak = 0;
        }
    }

    /** 当前是否应该用流畅档起播下一首。 */
    public boolean isDegraded() {
        return degraded;
    }

    /** 起播前强制降级（用户在设置里手动选「优先流畅」时用）；不影响回升判定。 */
    public void forceDegraded() {
        degraded = true;
        belowStreak = 0;
        aboveStreak = 0;
    }

    /** 起播/拖动进度条后调用：累计口径被重连或 seek 打断，窗口基准作废重建，但**不推翻已有档位结论**
     *  —— 档位是链路属性，seek 不会让链路变好或变坏；若这里做 reset()，每次拖条都会把降级状态洗掉。 */
    public void rebaseWindow() {
        windowBaseMs = -1L;
        windowBaseBytes = -1L;
    }

    /** 切歌/手动刷新等"链路条件可能已变"的时刻调用：丢掉历史窗口，避免用旧速率做新决策。 */
    public void reset() {
        degraded = false;
        belowStreak = 0;
        aboveStreak = 0;
        windowBaseMs = -1L;
        windowBaseBytes = -1L;
        lastWindowBytesPerSec = -1L;
    }

    public long getLastWindowBytesPerSec() {
        return lastWindowBytesPerSec;
    }
}
