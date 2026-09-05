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
    public void reauthIsSkippedDuringCooldownAndUnrecoverableErrorsGiveUp() {
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY,
                PlaybackStateMachine.streamRetryAction(true, 2, false));
        Assert.assertEquals(PlaybackStateMachine.StreamRetryAction.GIVE_UP,
                PlaybackStateMachine.streamRetryAction(false, 0, true));
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

    private PlaybackStateMachine readyToPlayWithFocus(PlaybackStateMachine.FocusState focus) {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        machine.beginGeneration(PlaybackStateMachine.EngineState.READY);
        machine.setFocusState(focus);
        return machine;
    }
}
