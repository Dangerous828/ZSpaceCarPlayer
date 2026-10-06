package com.ktools.zspacecarplayer.player.stream;

import org.junit.Assert;
import org.junit.Test;

/**
 * 带宽自适应降码率的边界。全是纯判定，不碰网络与真实时钟，
 * 所以在 host JVM 上就能把"什么时候换档"钉死——真车上换档时机错了比不换更糟。
 */
public class StreamRateGovernorTest {

    private static final long WINDOW = StreamRateGovernor.WINDOW_MS;

    /**
     * 按时序喂若干个完整窗口，每个窗口的速率给定为 rate（B/s）。
     * 累计口径：total 单调累加，所以窗口之间不会重复计数，也不会漏。
     */
    private static final class Feed {
        private long nowMs = 0L;
        private long total = 0L;
        private boolean primed = false;

        void windows(StreamRateGovernor g, int count, long rate) {
            for (int i = 0; i < count; i++) {
                nowMs += WINDOW;
                total += rate * WINDOW / 1000L;
                g.onProgress(nowMs, total);
            }
        }

        /** 第一个窗口之前要有基准点；没喂过就补一次零进度。 */
        void prime(StreamRateGovernor g) {
            if (!primed) {
                g.onProgress(nowMs, total);
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
    public void singleSlowWindowDoesNotDegrade() {
        StreamRateGovernor g = governed();
        feed.windows(g, 1, 60_000L);
        Assert.assertFalse("一次慢窗口不足以换档——可能只是一次重连", g.isDegraded());
    }

    @Test
    public void sustainedSlowWindowsDegrade() {
        StreamRateGovernor g = governed();
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS - 1, 60_000L);
        Assert.assertFalse(g.isDegraded());
        feed.windows(g, 1, 60_000L);
        Assert.assertTrue("连续 3 个窗口低于 140KB/s 必须降级：110KB/s 的无损追不上就是持续抽干",
                g.isDegraded());
    }

    @Test
    public void oneGoodWindowResetsTheStreak() {
        StreamRateGovernor g = governed();
        feed.windows(g, 1, 60_000L);
        feed.windows(g, 1, 200_000L);   // 夹一个达标窗口
        feed.windows(g, 2, 60_000L);
        Assert.assertFalse("抖动必须被吸收：慢-快-慢-慢 不该换档", g.isDegraded());
    }

    @Test
    public void restoreNeedsMoreGoodWindowsThanDegradeNeededSlowOnes() {
        StreamRateGovernor g = governed();
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS, 60_000L);
        Assert.assertTrue(g.isDegraded());
        Assert.assertTrue("回升要比降级更保守，否则会在临界点反复跳档",
                StreamRateGovernor.RESTORE_CONFIRMATIONS > StreamRateGovernor.DEGRADE_CONFIRMATIONS);
        feed.windows(g, StreamRateGovernor.RESTORE_CONFIRMATIONS - 1, 300_000L);
        Assert.assertTrue("回升阈值之前不得提前回到无损", g.isDegraded());
        feed.windows(g, 1, 300_000L);
        Assert.assertFalse(g.isDegraded());
    }

    @Test
    public void hysteresisBandChangesNothing() {
        StreamRateGovernor g = governed();
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS + 2, 60_000L);
        Assert.assertTrue(g.isDegraded());
        // 140KB/s ~ 175KB/s 之间是滞回带：够播但不算明确恢复，此时保持现状
        feed.windows(g, StreamRateGovernor.RESTORE_CONFIRMATIONS + 2, 160_000L);
        Assert.assertTrue("滞回带内不得回升", g.isDegraded());
    }

    @Test
    public void stalledDownloadCountsAsSlow() {
        StreamRateGovernor g = governed();
        // 真车出现过 buffered=0B 撑满 15s 的开流：零进展必须按"慢"计，否则永远不会降级
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS, 0L);
        Assert.assertTrue(g.isDegraded());
    }

    @Test
    public void partialWindowDoesNotJudge() {
        StreamRateGovernor g = new StreamRateGovernor();
        g.onProgress(0L, 0L);
        g.onProgress(WINDOW / 2, 10_000L);
        Assert.assertEquals("不足 WINDOW_MS 不得提前判定", -1L, g.getLastWindowBytesPerSec());
        g.onProgress(WINDOW, 20_000L);
        Assert.assertEquals(20_000L * 1000L / WINDOW, g.getLastWindowBytesPerSec());
    }

    @Test
    public void counterResetOnNewConnectionIsNotReadAsNegativeRate() {
        StreamRateGovernor g = governed();
        feed.windows(g, 1, 200_000L);
        // 上层重连后 bufEnd 归零重新数：累计值倒退不能算出负速率，按无进展处理
        feed.nowMs += WINDOW;
        feed.total = 0L;
        g.onProgress(feed.nowMs, feed.total);
        Assert.assertEquals(0L, g.getLastWindowBytesPerSec());
    }

    @Test
    public void resetDropsStaleHistory() {
        StreamRateGovernor g = governed();
        feed.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS, 60_000L);
        Assert.assertTrue(g.isDegraded());
        g.reset();
        Assert.assertFalse("reset 后不得沿用旧链路的结论", g.isDegraded());
        Assert.assertEquals(-1L, g.getLastWindowBytesPerSec());
        Feed f2 = new Feed();
        f2.prime(g);
        f2.windows(g, StreamRateGovernor.DEGRADE_CONFIRMATIONS - 1, 60_000L);
        Assert.assertFalse("重新按完整窗口计数", g.isDegraded());
        f2.windows(g, 1, 60_000L);
        Assert.assertTrue(g.isDegraded());
    }

    @Test
    public void thresholdsLeaveLosslessNoHeadroom() {
        // 本库无损实测约 110~126 KB/s（23,021,268B/204s 与 30,349,615B/241s）：
        // 下限必须显著高于它，否则"判为够播"却依然会抽干
        Assert.assertTrue(StreamRateGovernor.DEGRADE_BELOW_BYTES_PER_SEC > 126_000L);
        // 回升阈值必须高于降级下限，否则没有滞回
        Assert.assertTrue(StreamRateGovernor.RESTORE_ABOVE_BYTES_PER_SEC
                > StreamRateGovernor.DEGRADE_BELOW_BYTES_PER_SEC);
        // 流畅档要远低于实测最差链路（67 KB/s），否则换了也没用
        Assert.assertTrue(com.ktools.zspacecarplayer.net.JellyfinApiClient.DEGRADED_TARGET_BITRATE / 8L < 60_000L);
    }
}
