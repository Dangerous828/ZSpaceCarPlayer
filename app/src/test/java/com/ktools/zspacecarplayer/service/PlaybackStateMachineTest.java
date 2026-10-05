package com.ktools.zspacecarplayer.service;

import org.junit.Assert;
import org.junit.Test;

public class PlaybackStateMachineTest {

    @Test
    public void staleGenerationCannotMutateCurrentEngineState() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        long oldGeneration = machine.beginGeneration(PlaybackStateMachine.EngineState.PREPARING);
        long currentGeneration = machine.beginGeneration(PlaybackStateMachine.EngineState.PREPARING);

        machine.setEngineState(oldGeneration, PlaybackStateMachine.EngineState.PLAYING);

        Assert.assertFalse(machine.isCurrentGeneration(oldGeneration));
        Assert.assertTrue(machine.isCurrentGeneration(currentGeneration));
        Assert.assertEquals(PlaybackStateMachine.EngineState.PREPARING, machine.getEngineState());
    }

    @Test
    public void preparedCallbackStartsOnlyForPlayIntentWithoutBlockingFocusLoss() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        long generation = machine.beginGeneration(PlaybackStateMachine.EngineState.PREPARING);
        machine.setEngineState(generation, PlaybackStateMachine.EngineState.READY);

        machine.setFocusState(PlaybackStateMachine.FocusState.GRANTED);
        Assert.assertTrue(machine.canStart(generation, PlaybackStateMachine.PlaybackOrigin.USER_UI));

        machine.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PAUSE);
        Assert.assertFalse(machine.canStart(generation, PlaybackStateMachine.PlaybackOrigin.USER_UI));

        machine.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        machine.setFocusState(PlaybackStateMachine.FocusState.LOST_TRANSIENT);
        Assert.assertFalse(machine.canStart(generation, PlaybackStateMachine.PlaybackOrigin.USER_UI));

        machine.setFocusState(PlaybackStateMachine.FocusState.LOST_PERMANENT);
        Assert.assertFalse(machine.canStart(generation, PlaybackStateMachine.PlaybackOrigin.USER_UI));
    }

    @Test
    public void deniedFocusDoesNotMasqueradeAsPermanentLoss() {
        PlaybackStateMachine machine = readyToPlayWithFocus(PlaybackStateMachine.FocusState.DENIED);

        Assert.assertTrue(machine.canStart(machine.getGenerationId(),
                PlaybackStateMachine.PlaybackOrigin.USER_UI));
    }

    @Test
    public void switchingPlaybackFadeDecisionDependsOnPhysicalGain() {
        Assert.assertTrue(PlaybackStateMachine.shouldFadeBeforeReset(
                PlaybackStateMachine.EngineState.PREPARING, 0.4f));
        Assert.assertFalse(PlaybackStateMachine.shouldFadeBeforeReset(
                PlaybackStateMachine.EngineState.PREPARING, 0.0f));
    }

    @Test
    public void focusGainPolicyPreservesUserPauseAndRestoresActivePlay() {
        Assert.assertFalse(PlaybackStateMachine.shouldRestoreGainOnFocusGain(
                PlaybackStateMachine.DesiredPlayback.PAUSE,
                PlaybackStateMachine.EngineState.PLAYING,
                true));
        Assert.assertTrue(PlaybackStateMachine.shouldRestoreGainOnFocusGain(
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.EngineState.PLAYING,
                true));
    }

    @Test
    public void userPauseCompletionIsCancelledWhenPlayIsRequested() {
        Assert.assertFalse(PlaybackStateMachine.shouldCompletePause(
                PlaybackStateMachine.PauseReason.USER_REQUEST,
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.FocusState.GRANTED));
        Assert.assertTrue(PlaybackStateMachine.shouldCompletePause(
                PlaybackStateMachine.PauseReason.USER_REQUEST,
                PlaybackStateMachine.DesiredPlayback.PAUSE,
                PlaybackStateMachine.FocusState.GRANTED));
    }

    @Test
    public void focusPauseCompletionIsCancelledWhenFocusHasRecovered() {
        Assert.assertFalse(PlaybackStateMachine.shouldCompletePause(
                PlaybackStateMachine.PauseReason.FOCUS_LOSS,
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.FocusState.GRANTED));
        Assert.assertTrue(PlaybackStateMachine.shouldCompletePause(
                PlaybackStateMachine.PauseReason.FOCUS_LOSS,
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.FocusState.LOST_TRANSIENT));
    }

    @Test
    public void seekOperationDoesNotCreatePlaybackGenerationAndRejectsStaleCallbacks() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        long generation = machine.beginGeneration(PlaybackStateMachine.EngineState.PLAYING);

        long staleSeek = machine.beginSeekOperation();
        long currentSeek = machine.beginSeekOperation();

        Assert.assertEquals(generation, machine.getGenerationId());
        Assert.assertFalse(machine.isCurrentSeekOperation(staleSeek));
        Assert.assertTrue(machine.isCurrentSeekOperation(currentSeek));
    }

    @Test
    public void deniedFocusAllowsOnlyExplicitOriginsAndLossStillBlocksUsers() {
        PlaybackStateMachine machine = readyToPlayWithFocus(PlaybackStateMachine.FocusState.DENIED);
        long generation = machine.getGenerationId();

        Assert.assertTrue(machine.canStart(generation, PlaybackStateMachine.PlaybackOrigin.USER_UI));
        Assert.assertTrue(machine.canStart(generation, PlaybackStateMachine.PlaybackOrigin.MEDIA_BUTTON));
        Assert.assertFalse(machine.canStart(generation,
                PlaybackStateMachine.PlaybackOrigin.NETWORK_RECOVERY));
        Assert.assertFalse(machine.canStart(generation,
                PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME));
        Assert.assertFalse(machine.canStart(generation,
                PlaybackStateMachine.PlaybackOrigin.AUTH_RECOVERY));

        machine.setFocusState(PlaybackStateMachine.FocusState.LOST_TRANSIENT);
        Assert.assertFalse(machine.canStart(generation, PlaybackStateMachine.PlaybackOrigin.USER_UI));
    }

    @Test
    public void fakePlayingStateMakesToggleRequestPlayback() {
        Assert.assertTrue(PlaybackStateMachine.shouldToggleToPlay(
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.EngineState.PAUSED,
                false));
        Assert.assertFalse(PlaybackStateMachine.shouldToggleToPlay(
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.EngineState.PLAYING,
                true));
    }

    @Test
    public void activePlayIntentDuringPreparationOrSeekMakesTogglePause() {
        Assert.assertFalse(PlaybackStateMachine.shouldToggleToPlay(
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.EngineState.PREPARING,
                false));
        Assert.assertFalse(PlaybackStateMachine.shouldToggleToPlay(
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.EngineState.SEEKING,
                true));
        Assert.assertTrue(PlaybackStateMachine.shouldToggleToPlay(
                PlaybackStateMachine.DesiredPlayback.PLAY,
                PlaybackStateMachine.EngineState.PAUSED,
                false));
    }

    @Test
    public void automaticPlaylistStartUsesAutoResumeOrigin() {
        PlaybackStateMachine.PlaybackOrigin automatic =
                PlaybackStateMachine.playbackOriginForPlaylistStart(true);

        Assert.assertEquals(PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME, automatic);
        Assert.assertFalse(automatic == PlaybackStateMachine.PlaybackOrigin.USER_UI);
        Assert.assertEquals(PlaybackStateMachine.PlaybackOrigin.USER_UI,
                PlaybackStateMachine.playbackOriginForPlaylistStart(false));
    }

    @Test
    public void invalidatingSeekRejectsLateCompletion() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        long generation = machine.beginGeneration(PlaybackStateMachine.EngineState.PLAYING);
        long seekOperation = machine.beginSeekOperation();

        machine.invalidateSeekOperations();

        Assert.assertFalse(machine.isCurrentSeekOperation(seekOperation));
        Assert.assertFalse(machine.canFinishSeekTransition(generation, seekOperation));
    }

    @Test
    public void seekCompletionCannotOverwriteErrorOrReleasedState() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        long generation = machine.beginGeneration(PlaybackStateMachine.EngineState.PLAYING);
        long seekOperation = machine.beginSeekOperation();

        machine.setEngineState(generation, PlaybackStateMachine.EngineState.ERROR);
        Assert.assertFalse(machine.finishSeekWithoutPlayback(generation, seekOperation));
        Assert.assertEquals(PlaybackStateMachine.EngineState.ERROR, machine.getEngineState());

        machine.setEngineState(generation, PlaybackStateMachine.EngineState.RELEASED);
        Assert.assertFalse(machine.finishSeekWithoutPlayback(generation, seekOperation));
        Assert.assertEquals(PlaybackStateMachine.EngineState.RELEASED, machine.getEngineState());
    }

    @Test
    public void resumingAfterPauseGetsFreshAmplifierWakeKeyWithinSameGeneration() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.beginGeneration(PlaybackStateMachine.EngineState.PREPARING);
        long generationAtStart = machine.getGenerationId();
        long keyAtStart = machine.getAmplifierWakeKey();

        machine.notePlaybackInterrupted();

        Assert.assertEquals(generationAtStart, machine.getGenerationId());
        Assert.assertFalse(keyAtStart == machine.getAmplifierWakeKey());
    }

    @Test
    public void amplifierWakeKeyIsStableWhilePlaybackStaysContinuous() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.beginGeneration(PlaybackStateMachine.EngineState.PREPARING);
        long firstKey = machine.getAmplifierWakeKey();

        machine.setEngineState(machine.getGenerationId(), PlaybackStateMachine.EngineState.PLAYING);
        machine.setFocusState(PlaybackStateMachine.FocusState.GRANTED);

        Assert.assertEquals(firstKey, machine.getAmplifierWakeKey());
    }

    @Test
    public void deniedFocusMakesWatchdogRetryFocusInsteadOfRebuildingStream() {
        Assert.assertEquals(PlaybackStateMachine.WatchdogAction.RETRY_FOCUS,
                PlaybackStateMachine.watchdogAction(
                        PlaybackStateMachine.DesiredPlayback.PLAY,
                        PlaybackStateMachine.FocusState.DENIED,
                        PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME));
        Assert.assertEquals(PlaybackStateMachine.WatchdogAction.RETRY_FOCUS,
                PlaybackStateMachine.watchdogAction(
                        PlaybackStateMachine.DesiredPlayback.PLAY,
                        PlaybackStateMachine.FocusState.DENIED,
                        PlaybackStateMachine.PlaybackOrigin.NETWORK_RECOVERY));
    }

    @Test
    public void grantedFocusLetsWatchdogRebuildStream() {
        Assert.assertEquals(PlaybackStateMachine.WatchdogAction.REBUILD_STREAM,
                PlaybackStateMachine.watchdogAction(
                        PlaybackStateMachine.DesiredPlayback.PLAY,
                        PlaybackStateMachine.FocusState.GRANTED,
                        PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME));
        Assert.assertEquals(PlaybackStateMachine.WatchdogAction.REBUILD_STREAM,
                PlaybackStateMachine.watchdogAction(
                        PlaybackStateMachine.DesiredPlayback.PLAY,
                        PlaybackStateMachine.FocusState.DENIED,
                        PlaybackStateMachine.PlaybackOrigin.USER_UI));
    }

    @Test
    public void watchdogIdlesOnPausedIntentAndOnFocusTakenByAnotherApp() {
        Assert.assertEquals(PlaybackStateMachine.WatchdogAction.IDLE,
                PlaybackStateMachine.watchdogAction(
                        PlaybackStateMachine.DesiredPlayback.PAUSE,
                        PlaybackStateMachine.FocusState.GRANTED,
                        PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME));
        Assert.assertEquals(PlaybackStateMachine.WatchdogAction.IDLE,
                PlaybackStateMachine.watchdogAction(
                        PlaybackStateMachine.DesiredPlayback.PLAY,
                        PlaybackStateMachine.FocusState.LOST_TRANSIENT,
                        PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME));
        Assert.assertEquals(PlaybackStateMachine.WatchdogAction.IDLE,
                PlaybackStateMachine.watchdogAction(
                        PlaybackStateMachine.DesiredPlayback.PLAY,
                        PlaybackStateMachine.FocusState.LOST_PERMANENT,
                        PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME));
    }

    @Test
    public void resumePositionIsDiscardedWhenItBelongsToAnotherTrack() {
        Assert.assertEquals(0, PlaybackStateMachine.resumePositionForTrack(
                "track-B", "track-A", 90000, 240000));
        Assert.assertEquals(90000, PlaybackStateMachine.resumePositionForTrack(
                "track-A", "track-A", 90000, 240000));
        Assert.assertEquals(0, PlaybackStateMachine.resumePositionForTrack(
                "track-A", null, 90000, 240000));
    }

    @Test
    public void resumePositionNearTrackEndRestartsFromBeginning() {
        Assert.assertEquals(0, PlaybackStateMachine.resumePositionForTrack(
                "track-A", "track-A", 239000, 240000));
        Assert.assertEquals(0, PlaybackStateMachine.resumePositionForTrack(
                "track-A", "track-A", -1, 240000));
    }

    @Test
    public void streamRetryLadderIsTwoPlainRetriesThenOneReauth() {
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY,
                PlaybackStateMachine.streamRetryAction(true, 0, true));
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY,
                PlaybackStateMachine.streamRetryAction(true, 1, true));
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY,
                PlaybackStateMachine.streamRetryAction(true, 2, true));
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.GIVE_UP,
                PlaybackStateMachine.streamRetryAction(true, 3, true));
    }

    @Test
    public void plainRetriesWaitSixThenTwelveSeconds() {
        // 退避起点与假播放看门狗同为 6s 起步翻倍，但两条序列各自独立：
        // 改看门狗的 tick 常量不得带动本序列，反之亦然。
        Assert.assertEquals(6000L, PlaybackStateMachine.streamRetryBackoffMs(1));
        Assert.assertEquals(12000L, PlaybackStateMachine.streamRetryBackoffMs(2));
        // 第 3 次走重新登录的异步等待、预算之外走 GIVE_UP：都不该再叠一层等待
        Assert.assertEquals(0L, PlaybackStateMachine.streamRetryBackoffMs(3));
        Assert.assertEquals(0L, PlaybackStateMachine.streamRetryBackoffMs(4));
    }

    @Test
    public void backoffLadderWorstCaseSilenceIsEighteenSeconds() {
        long total = 0L;
        for (int attempt = 1; attempt <= PlaybackStateMachine.MAX_STREAM_RETRY_ATTEMPTS; attempt++) {
            total += PlaybackStateMachine.streamRetryBackoffMs(attempt);
        }
        Assert.assertEquals(18000L, total);
    }

    @Test
    public void reauthIsSkippedDuringCooldownAndUnrecoverableErrorsGiveUp() {
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY,
                PlaybackStateMachine.streamRetryAction(true, 2, false));
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.GIVE_UP,
                PlaybackStateMachine.streamRetryAction(false, 0, true));
    }

    @Test
    public void transientTransportErrorIsOnlyWhatOneWithMinus19Extra() {
        Assert.assertTrue(PlaybackStateMachine.isTransientTransportError(1, "-19"));
        Assert.assertFalse(PlaybackStateMachine.isTransientTransportError(1, "-1004"));
        Assert.assertFalse(PlaybackStateMachine.isTransientTransportError(1, null));
        Assert.assertFalse(PlaybackStateMachine.isTransientTransportError(0, "-19"));
        // v3 自研引擎的负值码 (STREAM_STALL/DECODE_FAILED) 走另一条判定, 不得混入
        Assert.assertFalse(PlaybackStateMachine.isTransientTransportError(-10002, "-10002"));
    }

    @Test
    public void transportErrorsNeverSpendAnAttemptOnReauth() {
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY,
                PlaybackStateMachine.effectiveRetryAction(
                        PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY, true));
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY,
                PlaybackStateMachine.effectiveRetryAction(
                        PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY, false));
        // 重试链耗尽后不得被降级逻辑复活
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.GIVE_UP,
                PlaybackStateMachine.effectiveRetryAction(
                        PlaybackStateMachine.StreamRetryAction.GIVE_UP, true));

        // 端到端: 慢网 (1,-19) 在第 3 次尝试上仍走直接重试而非重新登录
        PlaybackStateMachine.StreamRetryAction third = PlaybackStateMachine.streamRetryAction(
                PlaybackStateMachine.isTransientTransportError(1, "-19"), 2, true);
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY, third);
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY,
                PlaybackStateMachine.effectiveRetryAction(third, true));
    }

    @Test
    public void repeatErrorsNotifyOnlyOncePerAttemptEpisode() {
        Assert.assertTrue(PlaybackStateMachine.shouldNotifyError(1));
        Assert.assertFalse(PlaybackStateMachine.shouldNotifyError(2));
        Assert.assertFalse(PlaybackStateMachine.shouldNotifyError(50));
    }

    @Test
    public void transientResumeRefreshOnlyAppliesAfterFourSecondPause() {
        Assert.assertFalse(
                PlaybackStateMachine.shouldRefreshOnTransientResume(
                        PlaybackStateMachine.TRANSIENT_RESUME_REFRESH_THRESHOLD_MS - 1));
        Assert.assertTrue(
                PlaybackStateMachine.shouldRefreshOnTransientResume(
                        PlaybackStateMachine.TRANSIENT_RESUME_REFRESH_THRESHOLD_MS));
        Assert.assertTrue(PlaybackStateMachine.shouldRefreshOnTransientResume(30_000L));
        Assert.assertFalse(PlaybackStateMachine.shouldRefreshOnTransientResume(0L));
    }

    // ---------------- 2026-09-12 #1「切歌之后自动跳到曲尾」 ----------------

    @Test
    public void progressSavePrefersRealDurationOverMetadata() {
        // 元数据缺失 (RunTimeTicks=0) 但播放器真实时长已知: 贴尾脏值必须清成 0,
        // 旧实现只看元数据 → 直接放行 → 下次点歌被 seek 到曲尾
        Assert.assertEquals(0, PlaybackStateMachine.sanitizeProgressForSave(0L, 226000L, 225600));
        // 元数据比转码流实际时长偏大: 「已越过真实曲尾」的位置同样必须清掉
        Assert.assertEquals(0,
                PlaybackStateMachine.sanitizeProgressForSave(300000L, 226000L, 225600));
        // 5s 落库节流在自然播完前留下的残留值 (曲尾前 3.5s) 也必须清掉
        Assert.assertEquals(0,
                PlaybackStateMachine.sanitizeProgressForSave(226000L, 226000L, 222500));
        // 正常断点原样保留
        Assert.assertEquals(100000,
                PlaybackStateMachine.sanitizeProgressForSave(226000L, 226000L, 100000));
        Assert.assertEquals(100000,
                PlaybackStateMachine.sanitizeProgressForSave(226000L, 0L, 100000));
        // 两个时长都未知: 无从判定, 保守放行 (起播侧 seekMs < durationMs 不成立会丢弃断点)
        Assert.assertEquals(100000, PlaybackStateMachine.sanitizeProgressForSave(0L, 0L, 100000));
        // 非法位置一律归零
        Assert.assertEquals(0, PlaybackStateMachine.sanitizeProgressForSave(226000L, 226000L, -5));
        Assert.assertEquals(0, PlaybackStateMachine.sanitizeProgressForSave(226000L, 226000L, 0));
    }

    @Test
    public void badResumeLandingIsOnlyReportedForResumeStartsWithKnownDuration() {
        // 带断点起播后 tick 已贴真实曲尾 ⇒ 坏断点
        Assert.assertTrue(PlaybackStateMachine.isBadResumeLanding(true, false, 226000L, 225000L));
        // 从头起播不算: 否则时长本来就短于 guard 的歌会被无限「纠偏」
        Assert.assertFalse(PlaybackStateMachine.isBadResumeLanding(false, false, 226000L, 225000L));
        // 一次性: 已纠偏过就不再触发, 不会与重播形成死循环
        Assert.assertFalse(PlaybackStateMachine.isBadResumeLanding(true, true, 226000L, 225000L));
        // 真实时长仍未知 ⇒ 交给「播完得太快」那道非时长依赖的兜底
        Assert.assertFalse(PlaybackStateMachine.isBadResumeLanding(true, false, 0L, 225000L));
        // 正常位置不误报
        Assert.assertFalse(PlaybackStateMachine.isBadResumeLanding(true, false, 226000L, 100000L));
    }

    @Test
    public void completionRightAfterResumeReplaysInsteadOfAdvancing() {
        // 断点起播后几乎立刻 COMPLETED: 断点贴在曲尾, 不能当成播完跳下一首
        Assert.assertTrue(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 0L, false));
        Assert.assertTrue(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 5500L, false));
        // 出声已超过兜底窗口 ⇒ 视为真播完, 正常自动下一首
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(
                true, PlaybackStateMachine.COMPLETION_TOO_FAST_MS, false));
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 60000L, false));
        // 不是断点起播 (用户点歌/自动切歌) ⇒ 不干预
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(false, 0L, false));
        // 一次性
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 0L, true));
        // 兜底窗口必须窄于曲尾 guard, 否则刚过 guard 的合法断点会被误判成坏断点
        Assert.assertTrue(PlaybackStateMachine.COMPLETION_TOO_FAST_MS
                < PlaybackStateMachine.endOfTrackGuardMs());
    }

    private PlaybackStateMachine readyToPlayWithFocus(PlaybackStateMachine.FocusState focus) {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        machine.beginGeneration(PlaybackStateMachine.EngineState.READY);
        machine.setFocusState(focus);
        return machine;
    }
}
