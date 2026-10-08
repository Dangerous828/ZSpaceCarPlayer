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
        // 声音在往前走时，才轮到下载口径决定「能不能说稳定」
        assertTrue(BufferingPolicy.isBufferingStable(
                BufferingPolicy.BUFFERING_STABLE_PERCENT, 0, 300, true));
        assertTrue(BufferingPolicy.isBufferingStable(
                50, BufferingPolicy.BUFFERING_STABLE_LEAD_SECONDS, 300, true));
        assertTrue(BufferingPolicy.isBufferingStable(
                50, 5, BufferingPolicy.BUFFERING_STABLE_LEAD_SECONDS, true));
        assertFalse(BufferingPolicy.isBufferingStable(50, 5, 300, true));
        // 全未知 + 声音还在推进 => 稳定。这一条在 vc16 是 assertFalse，当晚被真车证伪：
        // chunked 流上三个数恒为 -1，false 就是「缓冲中…」亮起来再没有熄灭条件，
        // 而那 4 首的声音实际连续播了两分钟。不可判不是证据，不能当成还在缓冲。
        assertTrue("不可判时由出声进展裁决，不许恒亮",
                BufferingPolicy.isBufferingStable(-1, -1, -1, true));
    }

    /**
     * 第二扇门：声音冻住了，下载口径再"好看"也不许把指示藏起来。
     * 这是「缓冲中」对不上逻辑的反向形态——今晚之前它连看都不看位置。
     */
    @Test
    public void stalledAudioOverridesDownloadArithmetic() {
        assertFalse("percent 已满但声音不前进，不得判稳定",
                BufferingPolicy.isBufferingStable(100, 65, 65, false));
        assertFalse("lead 够长但声音冻住，同样不得判稳定",
                BufferingPolicy.isBufferingStable(50, 600, 300, false));
        assertFalse("连不可判也不能替冻住的声音说话",
                BufferingPolicy.isBufferingStable(-1, -1, -1, false));
    }

    /**
     * 测速可采门——今晚误判的根因闸门。特别是「已经下完」这一条：0 新字节是下载完成，
     * 不是链路给不动。
     */
    @Test
    public void bandwidthSamplingRequiresAKnownStillRunningDownload() {
        assertTrue(BufferingPolicy.bandwidthSampleIsMeasurable(50, 38_934_625L, 294_661L, true));
        assertFalse("整首已落地 → 不可采（今晚就是这里被读成 0B/s 后换了档）",
                BufferingPolicy.bandwidthSampleIsMeasurable(100, 38_934_625L, 294_661L, true));
        assertFalse("chunked 无总长 → 转码产率不是链路能力",
                BufferingPolicy.bandwidthSampleIsMeasurable(-1, -1L, 0L, true));
        assertFalse("时长未知 → 没有『所需速率』可算",
                BufferingPolicy.bandwidthSampleIsMeasurable(50, 38_934_625L, 0L, true));
        // 第四条：8MB 环形窗一饱和，bufEnd 只跟着读者走，此时任何样本都是"播放消耗速率"。
        // 这是 2026-10-08 那个 107KB/s 假缺口的真正来源（车主同一条链路 10 秒下完 3.1MB 的包）。
        assertFalse("窗口饱和 = 背压，样本代表不了链路",
                BufferingPolicy.bandwidthSampleIsMeasurable(50, 38_934_625L, 294_661L, false));
        assertEquals("所需速率 = 总长/时长（Bad Romance 实测锚点）",
                132_133L, BufferingPolicy.requiredBytesPerSec(38_934_625L, 294_661L));
        assertEquals(-1L, BufferingPolicy.requiredBytesPerSec(-1L, 294_661L));
        assertEquals(-1L, BufferingPolicy.requiredBytesPerSec(38_934_625L, 0L));
    }

    /** 出声进展：从未推进不算"冻住"，但推进过之后再停下就算。 */
    @Test
    public void audioAdvanceFreshnessRule() {
        long fresh = BufferingPolicy.AUDIO_ADVANCE_FRESH_MS;
        assertTrue("本轮从未推进（刚 prepared / 位置通道读不出）不得凭空判卡",
                BufferingPolicy.audioAdvancedRecently(100_000L, 0L));
        assertTrue(BufferingPolicy.audioAdvancedRecently(100_000L, 100_000L - fresh));
        assertFalse("推进过之后再静止超过新鲜度窗口 = 声音冻住了",
                BufferingPolicy.audioAdvancedRecently(100_000L, 100_000L - fresh - 1L));
    }

    @Test
    public void openWatchdogKillsOnlyStalledOpens() {
        long stall = BufferingPolicy.OPEN_STALL_MS;
        long cap = BufferingPolicy.OPEN_HARD_CAP_MS;
        // 有进展：过了 15s 也不掐——旧判据正是在这里把「慢但活着」的开流做成静音
        assertFalse(BufferingPolicy.openShouldAbort(true, 0, stall, true));
        assertFalse(BufferingPolicy.openShouldAbort(true, 0, cap - 1, true));
        // 无进展：满 stall 才掐，差一毫秒都不算
        assertFalse(BufferingPolicy.openShouldAbort(false, stall - 1, stall - 1, true));
        assertFalse(BufferingPolicy.openShouldAbort(false, stall - 1, stall, true));
        assertTrue(BufferingPolicy.openShouldAbort(false, stall, stall, true));
        // 一直有进展也不能赌死：硬上限一到必须收手，解码线程只有一条
        assertTrue(BufferingPolicy.openShouldAbort(true, 0, cap, true));
        assertTrue(BufferingPolicy.openShouldAbort(false, 0, cap, true));
        assertTrue("stall 必须严格小于硬上限，否则“有进展就顺延”是空话", stall < cap);
    }

    /**
     * 「零字节」与「下得慢」必须分档。真车实测 (2026-10-06)：一首只需 16KB/s 的 128k MP3 也卡住，
     * 上报是 `native open stalled no-progress=15004ms ... buffered=0B`——旧判据给最需要快速失败的
     * 形态最长耐心，还把唯一一条解码线程占死 15 秒。
     */
    @Test
    public void zeroByteOpenDiesSoonerThanSlowOpen() {
        long first = BufferingPolicy.OPEN_FIRST_BYTE_MS;
        long stall = BufferingPolicy.OPEN_STALL_MS;
        assertTrue("首字节耐心必须显著短于 stall 耐心，否则这次拆分是空改动", first * 3 < stall);
        assertFalse(BufferingPolicy.openShouldAbort(false, first - 1, first - 1, false));
        assertTrue(BufferingPolicy.openShouldAbort(false, first, first, false));
        // 同一时刻"已经收到过字节"就不能按零字节判死：那是 2026-10-05 修回来的慢流保护
        assertFalse("4s 时有首字节必须放过（旧行为会在这里误掐）",
                BufferingPolicy.openShouldAbort(false, first, first, true));
        // 零字节分支同样受硬上限保护，不因"更快失败"就变成无限等
        assertTrue(BufferingPolicy.openShouldAbort(false, 0, BufferingPolicy.OPEN_HARD_CAP_MS, false));
    }

    @Test
    public void knownDurationRestoresNearEndJudgements() {
        // 流式 FLAC 容器里 percent/lead 恒为 -1：时长一旦接回播放器，
        // 「接近结尾算稳定」与「接近结尾无条件预取」两条判据才可能命中。
        assertTrue(BufferingPolicy.isBufferingStable(-1, -1, 20, true));
        // remaining 单独已知**不算下载侧的证据**：chunked 转码流补上时长兜底之后就是这个形状
        // （percent=-1, lead=-1, remaining 几百秒）。拿它当证据就会让「缓冲中」再次恒亮，
        // 那正是 2026-10-07 被投诉的样子——10-08 把时长接回这条分支时必须同步改这里。
        assertTrue("下载侧只有 remaining 时不投票，交给出声进展",
                BufferingPolicy.isBufferingStable(-1, -1, 300, true));
        assertFalse("但出声一冻住，remaining 再大也得显示",
                BufferingPolicy.isBufferingStable(-1, -1, 300, false));
        assertTrue("三个数全不可判而声音在推进时按稳定处理（见 bufferingStableHidesIndicator 的复盘）",
                BufferingPolicy.isBufferingStable(-1, -1, -1, true));
        assertTrue(BufferingPolicy.shouldPrefetchNext(-1, -1, 10, false, false, false));
        assertFalse(BufferingPolicy.shouldPrefetchNext(-1, -1, -1, false, false, false));
    }

    /**
     * 404/410 是"换 Id 才有救"，必须和 4xx 里的 408/429（现在不行、等一下也许行）分开。
     * 判错的代价是真车实测过的：按网络故障处理要烧掉下载层 5 轮退避 + 同曲重试 ≈ 90s 才跳歌。
     */
    @Test
    public void onlyGoneStatusesAreTerminal() {
        assertTrue(BufferingPolicy.isMissingResourceStatus(404));
        assertTrue(BufferingPolicy.isMissingResourceStatus(410));
        assertFalse("超时仍属可重试", BufferingPolicy.isMissingResourceStatus(408));
        assertFalse("限流仍属可重试", BufferingPolicy.isMissingResourceStatus(429));
        assertFalse(BufferingPolicy.isMissingResourceStatus(403));
        assertFalse(BufferingPolicy.isMissingResourceStatus(500));
        assertFalse(BufferingPolicy.isMissingResourceStatus(503));
        assertFalse(BufferingPolicy.isMissingResourceStatus(200));
        assertFalse(BufferingPolicy.isMissingResourceStatus(206));
    }
}
