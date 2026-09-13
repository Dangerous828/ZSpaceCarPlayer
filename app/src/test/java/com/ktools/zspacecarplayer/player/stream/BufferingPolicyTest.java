package com.ktools.zspacecarplayer.player.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 缓冲 / 预取纯判定策略（{@link BufferingPolicy}）的边界测试（2026-09-12 缓冲/预取）。
 *
 * 抽成纯函数 + host JVM 单测的原因同 PlaybackStateMachine / StreamStarvationTest：
 * 这些阈值直接决定实车「听 2s 卡 1s」能否被治住——门槛太松开头照样被抽干，太紧弱网永久卡住；
 * 预取太激进会和当前曲抢带宽反而更卡。阈值必须钉死，改动时这里立刻报警。
 */
public class BufferingPolicyTest {

    private static final long MB = 1024L * 1024L;
    private static final long WINDOW_8MB = 8 * MB;

    // ---------------- prefill 门槛目标字节 ----------------

    /** 1.5Mbps 无损 FLAC：目标 = min(5s 的字节, 窗口 12%)，两者都远大于下限 */
    @Test
    public void prefillTargetForLosslessUsesByteRateOrWindowFraction() {
        // 时长 240s、总长 45MB => 码率 ≈ 1.5Mbps => 5s ≈ 937.5KB；窗口 12% ≈ 983KB
        long contentLength = 45 * MB;
        long durationMs = 240_000L;
        long target = BufferingPolicy.prefillTargetBytes(WINDOW_8MB, contentLength, durationMs);
        double bytesPerSec = (double) contentLength / (durationMs / 1000.0);
        long expected = (long) Math.min(bytesPerSec * BufferingPolicy.PREFILL_TARGET_SECONDS,
                (double) WINDOW_8MB * BufferingPolicy.PREFILL_WINDOW_FRACTION);
        assertEquals(expected, target);
        assertTrue("目标应达到数百 KB 级领先量", target > 500L * 1024L);
        assertTrue("目标不应超过窗口 12%", target <= (long) (WINDOW_8MB * BufferingPolicy.PREFILL_WINDOW_FRACTION));
    }

    /** 低码率 / 超短曲：按秒算出的目标过小时兜到下限，保证有基本领先量 */
    @Test
    public void prefillTargetFlooredForLowBitrate() {
        // 320kbps、时长 200s、总长 8MB => 5s ≈ 200KB < 下限 256KB
        long target = BufferingPolicy.prefillTargetBytes(WINDOW_8MB, 8 * MB, 200_000L);
        assertEquals(BufferingPolicy.PREFILL_FLOOR_BYTES, target);
    }

    /** 总长或时长未知：退化为纯字节门槛 */
    @Test
    public void prefillTargetFallsBackWhenTotalUnknown() {
        assertEquals(BufferingPolicy.PREFILL_MIN_BYTES_UNKNOWN_TOTAL,
                BufferingPolicy.prefillTargetBytes(WINDOW_8MB, -1L, 240_000L));
        assertEquals(BufferingPolicy.PREFILL_MIN_BYTES_UNKNOWN_TOTAL,
                BufferingPolicy.prefillTargetBytes(WINDOW_8MB, 45 * MB, 0L));
    }

    /** 预取小窗（2MB）作当前曲时，目标按小窗收敛，绝不要求填满整窗、也远小于 8MB 窗目标 */
    @Test
    public void prefillTargetScalesWithSmallPrefetchWindow() {
        long small = BufferingPolicy.prefillTargetBytes(2 * MB, 45 * MB, 240_000L);
        long big = BufferingPolicy.prefillTargetBytes(WINDOW_8MB, 45 * MB, 240_000L);
        assertTrue("小窗目标不超过小窗容量", small <= 2 * MB);
        assertTrue("小窗目标应不大于 8MB 窗目标", small <= big);
        assertTrue("小窗目标不小于下限", small >= BufferingPolicy.PREFILL_FLOOR_BYTES);
    }

    // ---------------- prefill 门槛判定 ----------------

    /** 总长已知：达到目标字节即放行起播 */
    @Test
    public void shouldPrefillStartWhenKnownTotalReachesTarget() {
        long target = 900_000L;
        assertFalse(BufferingPolicy.shouldPrefillStart(target - 1, 30, true, target));
        assertTrue(BufferingPolicy.shouldPrefillStart(target, 30, true, target));
        assertTrue(BufferingPolicy.shouldPrefillStart(target + 1000, 31, true, target));
    }

    /** 总长未知（percent=-1）：退化为纯字节门槛，达到 768KB 才放行 */
    @Test
    public void shouldPrefillStartUsesByteFloorWhenTotalUnknown() {
        long floor = BufferingPolicy.PREFILL_MIN_BYTES_UNKNOWN_TOTAL;
        assertFalse(BufferingPolicy.shouldPrefillStart(floor - 1, -1, false, 999_999_999L));
        assertTrue(BufferingPolicy.shouldPrefillStart(floor, -1, false, 999_999_999L));
        // percent 传 -1 即便 totalKnown 传真，也应走未知分支（防上层传参不一致）
        assertTrue(BufferingPolicy.shouldPrefillStart(floor, -1, true, 999_999_999L));
    }

    /** 源尚未建立（bufferedBytes=-1）：继续等，不放行 */
    @Test
    public void shouldPrefillStartWaitsWhenSourceNotReady() {
        assertFalse(BufferingPolicy.shouldPrefillStart(-1L, -1, false, 100L));
        assertFalse(BufferingPolicy.shouldPrefillStart(-1L, 50, true, 100L));
    }

    // ---------------- 下一首预取判定 ----------------

    /** 当前曲健康（百分比达标）才预取 */
    @Test
    public void prefetchWhenCurrentHealthyByPercent() {
        assertTrue(BufferingPolicy.shouldPrefetchNext(
                BufferingPolicy.PREFETCH_HEALTHY_PERCENT, 0, 120, false, false, false));
        assertFalse(BufferingPolicy.shouldPrefetchNext(
                BufferingPolicy.PREFETCH_HEALTHY_PERCENT - 1, 0, 120, false, false, false));
    }

    /** 百分比未知但领先秒数达标：同样视为健康 */
    @Test
    public void prefetchWhenHealthyByLeadSeconds() {
        assertTrue(BufferingPolicy.shouldPrefetchNext(
                -1, BufferingPolicy.PREFETCH_LEAD_SECONDS, 120, false, false, false));
        assertFalse(BufferingPolicy.shouldPrefetchNext(
                -1, BufferingPolicy.PREFETCH_LEAD_SECONDS - 1, 120, false, false, false));
    }

    /** 接近结尾（剩余 <= 15s）无条件预取，即便当前百分比不高 */
    @Test
    public void prefetchWhenNearEnd() {
        assertTrue(BufferingPolicy.shouldPrefetchNext(
                5, 1, BufferingPolicy.PREFETCH_REMAINING_SECONDS, false, false, false));
        assertFalse(BufferingPolicy.shouldPrefetchNext(
                5, 1, BufferingPolicy.PREFETCH_REMAINING_SECONDS + 1, false, false, false));
    }

    /** 让位当前曲：饥饿 / 正在 prefill 时绝不预取（当前曲永远优先） */
    @Test
    public void prefetchYieldsToCurrentSong() {
        assertFalse("饥饿时不预取", BufferingPolicy.shouldPrefetchNext(
                90, 60, 120, true, false, false));
        assertFalse("prefill 门槛期间不预取", BufferingPolicy.shouldPrefetchNext(
                90, 60, 120, false, true, false));
    }

    /** 去重：同一首只预取一次 */
    @Test
    public void prefetchDedupesSameSong() {
        assertFalse(BufferingPolicy.shouldPrefetchNext(
                90, 60, 120, false, false, true));
    }

    /** 剩余时长未知（-1）且百分比未知：不满足任何触发条件，不预取 */
    @Test
    public void prefetchNotTriggeredWhenEverythingUnknown() {
        assertFalse(BufferingPolicy.shouldPrefetchNext(-1, -1, -1, false, false, false));
    }

    // ---------------- 容量 / 上限 / 稳定判定 ----------------

    @Test
    public void prefetchCapacityAndMaxSources() {
        // prefetchCapacityBytes() 现在是「预取期 eager 下载目标」(2MB)，不是环形数组容量。
        assertEquals(2 * 1024 * 1024, BufferingPolicy.prefetchCapacityBytes());
        // 当前曲 8MB + 下一首预取 8MB(整窗分配, eager 只拉 2MB) = 最坏 16MB，
        // 与历史已验证安全的堆占用持平（不再上探到 3×8MB=24MB 触碰堆红线）。
        assertEquals(2, BufferingPolicy.maxSources());
        // eager 目标必须严格小于整窗容量：这才是「预取期省带宽、接管后升到完整 8MB 抗抖余量」的关键。
        assertTrue("预取 eager 目标必须小于默认整窗，才叫‘防御式’",
                BufferingPolicy.prefetchCapacityBytes() < BufferedHttpSource.DEFAULT_CAPACITY_BYTES);
    }

    @Test
    public void bufferingStableHidesIndicator() {
        // 已下载 >= 95% => 稳定
        assertTrue(BufferingPolicy.isBufferingStable(BufferingPolicy.BUFFERING_STABLE_PERCENT, 0, 300));
        // 领先 >= 30s => 稳定
        assertTrue(BufferingPolicy.isBufferingStable(50, BufferingPolicy.BUFFERING_STABLE_LEAD_SECONDS, 300));
        // 剩余不足一个稳定领先量 => 视为稳定（后面没有可担心的抽干）
        assertTrue(BufferingPolicy.isBufferingStable(50, 5, BufferingPolicy.BUFFERING_STABLE_LEAD_SECONDS));
        // 领先不足、剩余还多、百分比也不高 => 仍在缓冲
        assertFalse(BufferingPolicy.isBufferingStable(50, 5, 300));
        // 全未知 => 不稳定（继续显示「缓冲中…」）
        assertFalse(BufferingPolicy.isBufferingStable(-1, -1, -1));
    }
}
