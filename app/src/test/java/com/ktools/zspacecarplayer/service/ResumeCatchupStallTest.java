package com.ktools.zspacecarplayer.service;

import org.junit.Assert;
import org.junit.Test;

/**
 * 断点补位期不得被零位移看门狗判成断流 (2026-10-11 真车定位)。
 *
 * <p>现场：《光年之外》233,640ms，断点 26,853ms，播到该处时上游断流 &gt;30s 报 -10002。
 * 重试链路正确地带着 seek=26853 起播、prepared 也拿到 dur=233640ms，随后 12 秒出现
 * {@code stall recover pos=0ms} —— 续播点被冲成 0，整曲从头重播（观感即"歌又放了一遍"）。
 *
 * <p>根因不是 seek 没生效，而是 seek <b>正在生效的过程中</b>被看门狗打断了：无 seektable 的
 * FLAC 转码流要回到 26.8s，dr_flac 只能从头解码并丢弃字节补位（同一份上报里
 * {@code discarded=2,851,884B} 就是这笔丢弃量）。补位期间 {@code getCurrentPosition()} 合法地
 * 停在 0；按 88–112KB/s 的实测速率，2.85MB 要 25–32 秒，而零位移看门狗的预算只有
 * 20 tick × 500ms = 10 秒。于是"回到上次位置"必然跑不完就被掐掉，重头再来一次。
 *
 * <p>两条防线（本文件各自钉死）：
 * <ul>
 *   <li>补位期识别 —— 带断点起播、本首从未出过声、位置还没爬到断点，这段时间不是"声音冻住"，
 *       零位移预算放宽到补位上限；真断流自有下载层 {@code STARVE_*} 负责报 -10002，不靠这条兜。</li>
 *   <li>续播点不降级 —— 万一真的补位超预算要重启，恢复位置必须取<b>断点</b>而不是当下读到的 0，
 *       否则看门狗每一次出手都在销毁它想救的那个位置。</li>
 * </ul>
 */
public class ResumeCatchupStallTest {

    /** 上报里那首歌的真实数字，期望值全部来自它，不在测试里重新推导。 */
    private static final int RESUME_MS = 26_853;
    private static final long TRACK_DURATION_MS = 233_640L;

    // ---------------- 补位期识别 ----------------

    @Test
    public void positionAtZeroWhileCatchingUpIsNotAStall() {
        // 16:16:00~16:16:07 的那段：带 26853 起播、还没出过声、位置读到 0
        Assert.assertTrue(PlaybackStateMachine.isResumeCatchupPending(RESUME_MS, false, 0));
    }

    @Test
    public void catchupEndsOnceAudioHasAdvanced() {
        // 出过声 = 已经接上，此后位置不动才是真的冻住
        Assert.assertFalse(PlaybackStateMachine.isResumeCatchupPending(RESUME_MS, true, RESUME_MS + 400));
    }

    @Test
    public void catchupEndsWhenPositionReachesResumePoint() {
        Assert.assertFalse(PlaybackStateMachine.isResumeCatchupPending(RESUME_MS, false, RESUME_MS));
        Assert.assertFalse(PlaybackStateMachine.isResumeCatchupPending(RESUME_MS, false, RESUME_MS + 1));
    }

    @Test
    public void catchupStillRunsWhilePositionCrawlsBelowResumePoint() {
        // 补位途中位置可能先跳到 12s（dr_flac 二分落在中间），仍没到 26853
        Assert.assertTrue(PlaybackStateMachine.isResumeCatchupPending(RESUME_MS, false, 12_000));
    }

    @Test
    public void playFromTopIsNeverTreatedAsCatchup() {
        // 从头点播/自动下一首 (startMs=-1) 不受这条保护，原 10 秒预算必须原样保留
        Assert.assertFalse(PlaybackStateMachine.isResumeCatchupPending(-1, false, 0));
        Assert.assertFalse(PlaybackStateMachine.isResumeCatchupPending(0, false, 0));
    }

    // ---------------- 零位移预算 ----------------

    @Test
    public void normalStallBudgetStaysAtTenSeconds() {
        // 回归护栏：20 tick 是 vc23 既有行为，放宽只允许发生在补位期
        Assert.assertEquals(20, PlaybackStateMachine.stallTicksLimit(false));
        Assert.assertEquals(10_000L,
                PlaybackStateMachine.stallTicksLimit(false) * PlaybackStateMachine.PROGRESS_TICK_MS);
    }

    @Test
    public void catchupBudgetCoversMeasuredFlacRelocateCost() {
        int limit = PlaybackStateMachine.stallTicksLimit(true);
        Assert.assertTrue("补位预算必须盖住 2.85MB/88KBps ≈ 32s",
                limit * PlaybackStateMachine.PROGRESS_TICK_MS >= 32_000L);
        Assert.assertTrue("补位预算不得无限放宽，否则真断流没人管",
                limit * PlaybackStateMachine.PROGRESS_TICK_MS <= 60_000L);
    }

    @Test
    public void worstCaseCellularRateStillFitsCatchupBudget() {
        // 上报里的最差实测速率 103KB/s 是蜂窝 P25；作废量取 discarded=2,851,884B
        long relocateSeconds = 2_851_884L / 88_000L; // 最慢那条 FLAC 档
        long budgetSeconds = PlaybackStateMachine.stallTicksLimit(true)
                * PlaybackStateMachine.PROGRESS_TICK_MS / 1000L;
        Assert.assertTrue("补位上限必须容得下最慢 FLAC 档的重新定位",
                budgetSeconds >= relocateSeconds);
    }

    @Test
    public void catchupNeverKeepsTheWatchdogAwayForever() {
        // 纯函数判据本身必须是有界的：预算是有限 tick 数，不是"补位期永不看门狗"
        Assert.assertTrue(PlaybackStateMachine.stallTicksLimit(true) > 0);
        Assert.assertTrue(PlaybackStateMachine.stallTicksLimit(true)
                < Integer.MAX_VALUE / 2);
    }

    // ---------------- 续播点不降级 ----------------

    @Test
    public void recoveryKeepsResumePointWhenPositionStillReadsZero() {
        // 这一条单独成立：即便补位真的超预算要重启，也不能把 26853 冲成 0
        Assert.assertEquals(RESUME_MS,
                PlaybackStateMachine.stallRecoveryPositionMs(RESUME_MS, 0, TRACK_DURATION_MS));
    }

    @Test
    public void recoveryUsesObservedPositionAfterAudioAdvanced() {
        Assert.assertEquals(41_000, PlaybackStateMachine.stallRecoveryPositionMs(RESUME_MS, 41_000, TRACK_DURATION_MS));
    }

    @Test
    public void recoveryKeepsResumePointAheadOfObservedPosition() {
        // 已经出过声但位置回退（引擎重启后读 0），仍不该掉到 0
        Assert.assertEquals(RESUME_MS, PlaybackStateMachine.stallRecoveryPositionMs(RESUME_MS, 0, TRACK_DURATION_MS));
    }

    @Test
    public void recoveryWithoutResumePointUsesObservedPosition() {
        Assert.assertEquals(0, PlaybackStateMachine.stallRecoveryPositionMs(-1, 0, TRACK_DURATION_MS));
        Assert.assertEquals(77_000, PlaybackStateMachine.stallRecoveryPositionMs(-1, 77_000, TRACK_DURATION_MS));
    }

    @Test
    public void recoveryNeverLandsInEndOfTrackGuard() {
        // 断点贴着曲尾时不能拿它当恢复点：guard 之内一律回 0，否则会立刻假 COMPLETED 乱跳
        Assert.assertEquals(0, PlaybackStateMachine.stallRecoveryPositionMs(230_000, 0, TRACK_DURATION_MS));
    }

    // ---------------- 现场回放 ----------------

    @Test
    public void fieldIncidentWouldNotRestartSongFromZero() {
        // 16:15:55 prepared → 16:16:07 (24 tick) 位置恒 0：过去会触发重播，现在必须仍在等补位
        int observedZeroPosition = 0;
        boolean catchup = PlaybackStateMachine.isResumeCatchupPending(
                RESUME_MS, false, observedZeroPosition);
        int ticksObservedInTwelveSeconds = (int) (12_000L / PlaybackStateMachine.PROGRESS_TICK_MS);
        boolean oldBehaviourWouldRecover = ticksObservedInTwelveSeconds
                >= PlaybackStateMachine.stallTicksLimit(false);
        boolean newBehaviourRecovers = ticksObservedInTwelveSeconds
                >= PlaybackStateMachine.stallTicksLimit(catchup);
        Assert.assertTrue("旧判据正是现场那次重播的成因", oldBehaviourWouldRecover);
        Assert.assertFalse("新判据必须放过这次补位", newBehaviourRecovers);
        // 万一重启，续播点也得是 26853 而不是 0
        Assert.assertEquals(RESUME_MS, PlaybackStateMachine.stallRecoveryPositionMs(
                RESUME_MS, observedZeroPosition, TRACK_DURATION_MS));
    }
}
