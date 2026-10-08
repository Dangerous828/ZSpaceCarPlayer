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
                        PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY, true, false));
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY,
                PlaybackStateMachine.effectiveRetryAction(
                        PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY, false, false));
        // 重试链耗尽后不得被降级逻辑复活
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.GIVE_UP,
                PlaybackStateMachine.effectiveRetryAction(
                        PlaybackStateMachine.StreamRetryAction.GIVE_UP, true, false));

        // 端到端: 慢网 (1,-19) 在第 3 次尝试上仍走直接重试而非重新登录
        PlaybackStateMachine.StreamRetryAction third = PlaybackStateMachine.streamRetryAction(
                PlaybackStateMachine.isTransientTransportError(1, "-19"), 2, true);
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY, third);
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY,
                PlaybackStateMachine.effectiveRetryAction(third, true, false));
    }

    /**
     * 服务端 404/410 必须抢在一切重试之前终态。真车实测 (2026-10-06 曲库改名后): 按网络故障
     * 处理时一个失效 Id 要烧掉下载层 5 轮退避 + 同曲重试与 6s/12s 退避 ≈ 90s 才跳歌。
     */
    @Test
    public void missingResourceIsTerminalOnTheFirstAttempt() {
        // 第 1 次尝试本来是 PLAIN_RETRY，资源缺失要直接 GIVE_UP
        PlaybackStateMachine.StreamRetryAction first = PlaybackStateMachine.streamRetryAction(true, 0, true);
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY, first);
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.GIVE_UP,
                PlaybackStateMachine.effectiveRetryAction(first, false, true));

        // 鉴权重试那条路也不例外：换 Id 才是解，重新登录拿到同一个 Id 仍然 404
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.GIVE_UP,
                PlaybackStateMachine.effectiveRetryAction(
                        PlaybackStateMachine.StreamRetryAction.REAUTH_RETRY, true, true));

        // missingResource=false 时不得改变原有判定（防这条新分支把网络故障也判死）
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY,
                PlaybackStateMachine.effectiveRetryAction(first, false, false));
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
        Assert.assertTrue(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 0L, false, true));
        Assert.assertTrue(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 5500L, false, true));
        // 出声已超过兜底窗口 ⇒ 视为真播完, 正常自动下一首
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(
                true, PlaybackStateMachine.COMPLETION_TOO_FAST_MS, false, true));
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 60000L, false, true));
        // 不是断点起播 (用户点歌/自动切歌) ⇒ 不干预
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(false, 0L, false, true));
        // 一次性
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 0L, true, true));
        // 兜底窗口必须窄于曲尾 guard, 否则刚过 guard 的合法断点会被误判成坏断点
        Assert.assertTrue(PlaybackStateMachine.COMPLETION_TOO_FAST_MS
                < PlaybackStateMachine.endOfTrackGuardMs());
    }

    /**
     * 断路器（2026-10-08 08:51 真车崩溃现场）。出山 带断点 34728ms 起播，chunked 流畅档的下载
     * 被自己的重试打断 → 一个字节都没出声就 EOS。旧判据只看 tick 数，把这种"流死了"当成
     * "断点贴在曲尾"：清掉续播点 + 从零重播，而下一轮同样立刻 EOS —— 日志里这个环跑了两圈
     * 之后进程就没了。没出过声就不许走坏断点纠偏，交给「假播完」那道按 I/O 错误处理。
     */
    @Test
    public void neverHeardAudioIsNotABadResumePoint() {
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 0L, false, false));
        Assert.assertFalse(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 1000L, false, false));
        // 只有真的出过声，上面那条"贴尾"判定才成立
        Assert.assertTrue(PlaybackStateMachine.shouldReplayInsteadOfAdvance(true, 1000L, false, true));
    }

    /**
     * 「假播完」判定的输入就是 2026-10-07 真车那晚的真实数字：代理在总长未知时回复
     * 无 Content-Length 的 close-delimited 流，下载中途放弃 = 抽取器眼里的合法曲尾。
     */
    @Test
    public void prematureCompletionIsJudgedByPositionNotByFlags() {
        // 219,493ms 的歌在 65,802ms 结束，且本流判过死 → 截断，绝不能当成播完
        Assert.assertTrue(PlaybackStateMachine.isPrematureCompletion(219493L, 65802L, 0L, true));
        // 真播完（差 2 秒到曲尾）→ 不得误判
        Assert.assertFalse(PlaybackStateMachine.isPrematureCompletion(219493L, 217400L, 0L, true));
        // 曲尾余量之内一律放过：宁可不救，也不把一首正常放完的歌拖进重试
        Assert.assertFalse(PlaybackStateMachine.isPrematureCompletion(219493L,
                219493L - PlaybackStateMachine.TRUNCATION_GUARD_MS + 1L, 0L, true));
        // 元数据缺失（0）= 没有任何"应该播到哪儿"的依据 → 不判
        Assert.assertFalse(PlaybackStateMachine.isPrematureCompletion(0L, 1000L, 0L, true));
        // 播放器报回的真实时长优先于元数据：元数据偏大时不得误杀本来就短的歌流
        Assert.assertFalse(PlaybackStateMachine.isPrematureCompletion(240000L, 101000L, 100000L, true));
        Assert.assertTrue("真实时长更短时按真实时长判，仍然截得出来",
                PlaybackStateMachine.isPrematureCompletion(240000L, 40000L, 100000L, true));
        // 位置读不出来（-1）时不凭空判截断
        Assert.assertFalse(PlaybackStateMachine.isPrematureCompletion(219493L, -1L, 0L, true));
    }

    /**
     * 必须有"这条流出过事"的证据才许判截断。位置差本身不是证据：Jellyfin 的 FLAC 转码流
     * 元数据常比真实吐出的字节偏大，那种正常播完在只看位置的判据下会被误杀成截断，
     * 白白重试一次再报一句虚高的「无法播放」（审查发现的假阳性，2026-10-07）。
     */
    @Test
    public void positionGapAloneIsNotEnoughToCallTruncation() {
        Assert.assertFalse("没有判过死 → 一律当真播完",
                PlaybackStateMachine.isPrematureCompletion(219493L, 65802L, 0L, false));
        Assert.assertFalse("元数据偏大的正常结尾同样放过",
                PlaybackStateMachine.isPrematureCompletion(240000L, 100000L, 0L, false));
    }

    /** 连着跳歌时那行字必须把"是链路在扩大"说出来，而不是每首各报一遍互不相干的话 */
    @Test
    public void giveUpMessageScalesWithTheStreak() {
        // losslessTier=false：只看原文案，不受新提示干扰
        Assert.assertEquals("多次重试仍无法播放，已跳过: A",
                PlaybackStateMachine.describeGiveUp(1, "A", false, false));
        Assert.assertEquals("曲目已不在服务器, 已跳过: A",
                PlaybackStateMachine.describeGiveUp(1, "A", true, false));
        Assert.assertEquals("已连续 2 首无法播放(最近: B), 请检查网络或刷新曲库",
                PlaybackStateMachine.describeGiveUp(2, "B", false, false));
        Assert.assertEquals("已连续 7 首无法播放(最近: G), 请检查网络或刷新曲库",
                PlaybackStateMachine.describeGiveUp(7, "G", true, false));
    }

    /**
     * 无损档放弃时那一句"可切流畅"只是<b>提示</b>，不是动作 (2026-10-08 T1 重审 L2)：
     * 自动降档已被车主否决，所以这里给出口、由人去设置页点。服务端根本没这首歌时
     * 不许提这句——换了码率也照样没有那首歌。
     */
    @Test
    public void losslessGiveUpSuggestsSmoothTierButOnlyForPlaybackFailures() {
        Assert.assertTrue("无损档播不动 → 给出路",
                PlaybackStateMachine.describeGiveUp(1, "A", false, true)
                        .contains("可在设置把音质切成「流畅」"));
        Assert.assertTrue("连着几首也一样只给一句",
                PlaybackStateMachine.describeGiveUp(3, "C", false, true)
                        .contains("可在设置把音质切成「流畅」"));
        Assert.assertFalse("曲目已不在服务器：切档无用，不许误导",
                PlaybackStateMachine.describeGiveUp(1, "A", true, true)
                        .contains("流畅"));
        Assert.assertFalse("流畅档自己不提切流畅",
                PlaybackStateMachine.describeGiveUp(1, "A", false, false)
                        .contains("流畅"));
    }

    private PlaybackStateMachine readyToPlayWithFocus(PlaybackStateMachine.FocusState focus) {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        machine.beginGeneration(PlaybackStateMachine.EngineState.READY);
        machine.setFocusState(focus);
        return machine;
    }
}
