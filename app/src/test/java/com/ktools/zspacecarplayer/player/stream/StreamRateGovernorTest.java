package com.ktools.zspacecarplayer.player.stream;

import org.junit.Assert;
import org.junit.Test;

/**
 * 带宽缺口判定的边界。全是纯判定，不碰网络与真实时钟，
 * 所以在 host JVM 上就能把"什么时候算缺口、什么时候才许换档"钉死——真车上换错档比不换更糟
 * （2026-10-07 那次误判把整条队列的 4 首歌全腰斩了）。
 */
public class StreamRateGovernorTest {

    private static final long WINDOW = StreamRateGovernor.WINDOW_MS;

    /** 本库直播无损原件的所需速率区间（B/s），实测锚点见 thresholdsAreRelativeToTheTrack。 */
    private static final long LOSSLESS_REQUIRED = 132_000L;

    /**
     * 按时序喂若干个完整窗口，每个窗口的速率给定为 rate（B/s），所需速率给定为 required。
     * 累计口径：total 单调累加，所以窗口之间不会重复计数，也不会漏。
     */
    private static final class Feed {
        private long nowMs = 0L;
        private long total = 0L;
        private boolean primed = false;

        void windows(StreamRateGovernor g, int count, long rate, long required) {
            for (int i = 0; i < count; i++) {
                nowMs += WINDOW;
                total += rate * WINDOW / 1000L;
                g.onProgress(nowMs, total, required);
            }
        }

        /** 第一个窗口之前要有基准点；没喂过就补一次零进度。 */
        void prime(StreamRateGovernor g) {
            if (!primed) {
                g.onProgress(nowMs, total, -1L);
                primed = true;
            }
        }
    }

    private Feed feed = new Feed();

    private StreamRateGovernor governed() {
        StreamRateGovernor g = new StreamRateGovernor();
        feed = new Feed();
        feed.prime(g);
        return g;
    }

    @Test
    public void unknownRequiredRateNeverJudges() {
        StreamRateGovernor g = governed();
        // 总长或时长任一未知（chunked 转码流就是这种）时一律不可判。vc16 的固定下限在这里
        // 会把 0B/s 的不可判窗口算成缺口——2026-10-07 的误判就是这么来的
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS * 3, 0L, -1L);
        Assert.assertEquals("不可判的窗口一次都不许计入缺口",
                0, g.getDeficitConfirmations());
        Assert.assertFalse(g.isDegraded());
    }

    @Test
    public void directPlayMp3ThroughputIsNotADeficit() {
        StreamRateGovernor g = governed();
        // 一首直播原件的 MP3 只需 ~24KB/s，链路跑 30KB/s 完全健康；
        // vc16 拿固定 140KB/s 判它，当场误降级（2026-10-07 16:15:58 percent=100 那次）
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS * 3, 30_000L, 24_000L);
        Assert.assertEquals(0, g.getDeficitConfirmations());
        Assert.assertFalse(g.isDegraded());
    }

    @Test
    public void singleSlowWindowDoesNotConfirmDeficit() {
        StreamRateGovernor g = governed();
        feed.windows(g, 1, 60_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals("一次慢窗口不足以定性——可能只是一次重连",
                0, g.getDeficitConfirmations());
    }

    @Test
    public void sustainedSlowWindowsConfirmDeficit() {
        StreamRateGovernor g = governed();
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS - 1, 60_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals(0, g.getDeficitConfirmations());
        feed.windows(g, 1, 60_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals("连续 3 个窗口追不上所需速率必须定性为缺口：132KB/s 的无损只给 60KB/s 就是持续抽干",
                1, g.getDeficitConfirmations());
    }

    /**
     * 自动换档关着的理由**只剩一个，而且很硬**：测速口径还不可信。
     *
     * <p>10-07 关它是怕流畅档腰斩（那两条 vc17 已经修了）；10-08 我据此打开，当天就被车主一个
     * 数字推翻——同一条链路 10 秒下完 3.1MB 的包（>300KB/s），而我"测"出来 107KB/s，因为样本
     * 取自 {@code bufEnd}：窗口饱和时它等于播放消耗速率，于是健康链路也必然满足
     * {@code est < 所需×1.15}。所以关着是"不许用坏数字接管档位"，等 socket 口径的
     * {@code bitrate deficit} 在真车上重新出现并可信，再谈打开。
     */
    @Test
    public void autoDegradeStaysOffWhileTheSampleIsUntrustworthy() {
        Assert.assertFalse(StreamRateGovernor.AUTO_DEGRADE_ENABLED);
        StreamRateGovernor g = governed();
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS * 4, 20_000L, LOSSLESS_REQUIRED);
        Assert.assertTrue("缺口照样要累计成证据", g.getDeficitConfirmations() >= 3);
        Assert.assertFalse("但不许据此换档", g.isDegraded());
    }

    @Test
    public void forceDegradeStillWorksRegardlessOfTheSample() {
        // 手动档不依赖测速：这是将来给"优先流畅"开关留的路，也是验证回升逻辑唯一的机会
        StreamRateGovernor g = governed();
        g.forceDegraded();
        Assert.assertTrue(g.isDegraded());
        feed.windows(g, StreamRateGovernor.RESTORE_CONFIRMATIONS, 300_000L, LOSSLESS_REQUIRED);
        Assert.assertFalse("富余够 5 个窗口必须能回无损", g.isDegraded());
    }

    @Test
    public void oneGoodWindowResetsTheStreak() {
        StreamRateGovernor g = governed();
        feed.windows(g, 1, 60_000L, LOSSLESS_REQUIRED);
        feed.windows(g, 1, 200_000L, LOSSLESS_REQUIRED);   // 夹一个达标窗口
        feed.windows(g, 2, 60_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals("抖动必须被吸收：慢-快-慢-慢 不该定性",
                0, g.getDeficitConfirmations());
    }

    @Test
    public void restoreNeedsMoreGoodWindowsThanDegradeNeededSlowOnes() {
        StreamRateGovernor g = governed();
        g.forceDegraded();   // 手动档才验证回升，自动档当前不换档
        Assert.assertTrue(g.isDegraded());
        Assert.assertTrue("回升要比降级更保守，否则会在临界点反复跳档",
                StreamRateGovernor.RESTORE_CONFIRMATIONS > StreamRateGovernor.DEGRADE_CONFIRMATIONS);
        feed.windows(g, StreamRateGovernor.RESTORE_CONFIRMATIONS - 1, 300_000L, LOSSLESS_REQUIRED);
        Assert.assertTrue("回升阈值之前不得提前回到无损", g.isDegraded());
        feed.windows(g, 1, 300_000L, LOSSLESS_REQUIRED);
        Assert.assertFalse(g.isDegraded());
    }

    @Test
    public void hysteresisBandChangesNothing() {
        StreamRateGovernor g = governed();
        g.forceDegraded();
        // 所需 ×1.15 ~ ×1.45 之间是滞回带：够播但不算明确恢复，此时保持现状
        feed.windows(g, StreamRateGovernor.RESTORE_CONFIRMATIONS + 2,
                LOSSLESS_REQUIRED + LOSSLESS_REQUIRED * 20 / 100, LOSSLESS_REQUIRED);
        Assert.assertTrue("滞回带内不得回升", g.isDegraded());
    }

    @Test
    public void stalledDownloadCountsAsDeficit() {
        StreamRateGovernor g = governed();
        // 所需速率已知而下载零进展（真车出现过 buffered=0B 撑满 15s 的开流）：按"追不上"计
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS, 0L, LOSSLESS_REQUIRED);
        Assert.assertEquals(1, g.getDeficitConfirmations());
    }

    @Test
    public void partialWindowDoesNotJudge() {
        StreamRateGovernor g = new StreamRateGovernor();
        g.onProgress(0L, 0L, LOSSLESS_REQUIRED);
        g.onProgress(WINDOW / 2, 10_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals("不足 WINDOW_MS 不得提前判定", -1L, g.getLastWindowBytesPerSec());
        g.onProgress(WINDOW, 20_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals(20_000L * 1000L / WINDOW, g.getLastWindowBytesPerSec());
    }

    @Test
    public void relocationIsNotReadAsASampleOfZeroProgress() {
        StreamRateGovernor g = governed();
        feed.windows(g, 1, 200_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals(200_000L, g.getLastWindowBytesPerSec());
        // 累计口径倒退 = 本流被重定位了（换曲 / 后向 seek / 原生二分内部的自动重定位都会这样，
        // 因为 requestResetLocked 把 bufStart、bufEnd 一起挪走）。它跟"链路没给字节"是两件事。
        // vc16 在这里钳成 0B/s 并计入缺口，等于每换一首歌就白送一个假缺口窗口。
        feed.nowMs += WINDOW;
        feed.total = 0L;
        g.onProgress(feed.nowMs, feed.total, LOSSLESS_REQUIRED);
        Assert.assertEquals("倒退那一次不得产出任何速率样本",
                200_000L, g.getLastWindowBytesPerSec());
        Assert.assertEquals("也不得计入缺口", 0, g.getDeficitConfirmations());
        // 重定位之后照常测：连续 3 个零进展窗口**这才算**真缺口
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS, 0L, LOSSLESS_REQUIRED);
        Assert.assertEquals(1, g.getDeficitConfirmations());
    }

    @Test
    public void trackChangeDoesNotInheritPreviousByteCounter() {
        StreamRateGovernor g = governed();
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS, 200_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals("上一首健康", 0, g.getDeficitConfirmations());
        feed.nowMs += WINDOW;
        feed.total = 0L;                     // 新歌从 0 数起
        g.onProgress(feed.nowMs, feed.total, LOSSLESS_REQUIRED);
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS, 200_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals("换曲不得凭空攒出缺口计数", 0, g.getDeficitConfirmations());
    }

    @Test
    public void rebaseKeepsVerdictButDropsTheWindowBase() {
        StreamRateGovernor g = governed();
        feed.windows(g, 1, 60_000L, LOSSLESS_REQUIRED);
        Assert.assertEquals(LOSSLESS_REQUIRED, g.getLastRequiredBytesPerSec());
        g.rebaseWindow();
        g.forceDegraded();
        Assert.assertTrue("重开窗口基准不得洗掉档位结论（拖一次进度条不该让降级白做）",
                g.isDegraded());
    }

    @Test
    public void thresholdsAreRelativeToTheTrack() {
        // 本库直播无损实测 100~151 KB/s（23,021,268B/204s、30,349,615B/241s、38,934,625B/294,661ms）：
        // 判据既然跟着每首的所需速率走，就必须留正的上浮，否则"判为够播"照样会抽干
        Assert.assertTrue(StreamRateGovernor.DEFICIT_MARGIN_PERCENT > 0);
        Assert.assertTrue("滞回带：回升阈值必须高于认定缺口的阈值",
                StreamRateGovernor.SURPLUS_MARGIN_PERCENT > StreamRateGovernor.DEFICIT_MARGIN_PERCENT);
        // 流畅档要远低于实测最差链路（67 KB/s），否则换了也没用
        Assert.assertTrue(com.ktools.zspacecarplayer.net.JellyfinApiClient.DEGRADED_TARGET_BITRATE / 8L
                < 60_000L);
    }
}
