package com.ktools.zspacecarplayer.service;

/**
 * Pure playback policy and generation state. This class deliberately has no Android dependencies
 * so transition decisions can be regression-tested on the host JVM.
 */
public final class PlaybackStateMachine {

    public enum DesiredPlayback {
        PLAY,
        PAUSE,
        STOP
    }

    public enum EngineState {
        IDLE,
        PREPARING,
        READY,
        PLAYING,
        PAUSED,
        SEEKING,
        ERROR,
        RELEASED
    }

    public enum FocusState {
        NONE,
        GRANTED,
        DENIED,
        DUCKED,
        LOST_TRANSIENT,
        LOST_PERMANENT
    }

    public enum PlaybackOrigin {
        USER_UI,
        MEDIA_BUTTON,
        AUTO_RESUME,
        NETWORK_RECOVERY,
        AUTH_RECOVERY
    }

    public enum PauseReason {
        USER_REQUEST,
        FOCUS_LOSS
    }

    public enum StreamRetryAction {
        PLAIN_RETRY,
        REAUTH_RETRY,
        GIVE_UP
    }

    public enum WatchdogAction {
        /** 无事可做: 用户没有播放意图, 或焦点是被别人抢走的 (会收到 GAIN, 静等即可)。 */
        IDLE,
        /** 只重新申请焦点, 不重建串流、不提示用户。 */
        RETRY_FOCUS,
        /** 重建串流并从断点续播。 */
        REBUILD_STREAM
    }

    /** 同一曲目最多尝试 3 次: 前 2 次直接重试, 第 3 次先重新登录再重试。 */
    public static final int MAX_STREAM_RETRY_ATTEMPTS = 3;
    private static final int REAUTH_ATTEMPT = 3;
    /** android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN 的值 (本类不依赖 Android, 故镜像于此)。 */
    private static final int MEDIA_ERROR_UNKNOWN_WHAT = 1;
    /** 慢网冷启动时 start/seek 撞车产生的传输层错误 extra, 重试即可恢复。 */
    private static final String TRANSIENT_TRANSPORT_EXTRA = "-19";
    /** 距曲尾这么近就从头重播, 避免恢复瞬间又触发 onCompletion 跳下一首。 */
    private static final int END_OF_TRACK_GUARD_MS = 3000;
    /**
     * 语音播报等短暂失焦暂停超过该时长后, 恢复时重建渲染路径 (seek 刷新):
     * 吉利语音助手占用音频后 DSP 路由/功放通道可能假死, 直接 start() 会出现
     * 进度推进但物理无声, 且位置在走导致两个看门狗都不会触发。
     */
    public static final long TRANSIENT_RESUME_REFRESH_THRESHOLD_MS = 4000L;

    public static boolean shouldRefreshOnTransientResume(long pausedDurationMs) {
        return pausedDurationMs >= TRANSIENT_RESUME_REFRESH_THRESHOLD_MS;
    }

    private DesiredPlayback desiredPlayback = DesiredPlayback.STOP;
    private EngineState engineState = EngineState.IDLE;
    private FocusState focusState = FocusState.NONE;
    private PlaybackOrigin playbackOrigin = PlaybackOrigin.USER_UI;
    private long generationId;
    private long seekOperationId;
    private long amplifierWakeKey;

    public synchronized long beginGeneration(EngineState initialState) {
        return beginGeneration(initialState, PlaybackOrigin.USER_UI);
    }

    public synchronized long beginGeneration(EngineState initialState, PlaybackOrigin origin) {
        generationId++;
        seekOperationId++;
        amplifierWakeKey++;
        engineState = initialState;
        if (origin != null) {
            playbackOrigin = origin;
        }
        return generationId;
    }

    /**
     * 播放被真正打断 (用户暂停/失焦暂停完成) 时调用。功放唤醒去重必须以「本次连续出声」
     * 为单位, 而不是曲目 generation: 暂停到恢复期间 generation 不变, 但 DSP 可能已经
     * standby, 恢复时必须允许再唤醒一次。
     */
    public synchronized void notePlaybackInterrupted() {
        amplifierWakeKey++;
    }

    public synchronized long getAmplifierWakeKey() {
        return amplifierWakeKey;
    }

    public synchronized long getGenerationId() {
        return generationId;
    }

    public synchronized boolean isCurrentGeneration(long generation) {
        return generation == generationId;
    }

    public synchronized long beginSeekOperation() {
        seekOperationId++;
        engineState = EngineState.SEEKING;
        return seekOperationId;
    }

    public synchronized void invalidateSeekOperations() {
        seekOperationId++;
    }

    public synchronized boolean canFinishSeekTransition(long generation, long operationId) {
        return generation == generationId
                && operationId == seekOperationId
                && engineState == EngineState.SEEKING;
    }

    public synchronized boolean finishSeekWithoutPlayback(long generation, long operationId) {
        if (!canFinishSeekTransition(generation, operationId)) {
            return false;
        }
        engineState = EngineState.PAUSED;
        return true;
    }

    public synchronized long getSeekOperationId() {
        return seekOperationId;
    }

    public synchronized boolean isCurrentSeekOperation(long operationId) {
        return operationId == seekOperationId;
    }

    public synchronized void setDesiredPlayback(DesiredPlayback desired) {
        if (desired == null) {
            throw new IllegalArgumentException("desired playback must not be null");
        }
        desiredPlayback = desired;
    }

    public synchronized DesiredPlayback getDesiredPlayback() {
        return desiredPlayback;
    }

    public synchronized void setEngineState(long generation, EngineState state) {
        if (generation == generationId && state != null) {
            engineState = state;
        }
    }

    public synchronized void setEngineState(EngineState state) {
        if (state != null) {
            engineState = state;
        }
    }

    public synchronized EngineState getEngineState() {
        return engineState;
    }

    public synchronized void setFocusState(FocusState state) {
        if (state != null) {
            focusState = state;
        }
    }

    public synchronized FocusState getFocusState() {
        return focusState;
    }

    public synchronized void setPlaybackOrigin(PlaybackOrigin origin) {
        if (origin != null) {
            playbackOrigin = origin;
        }
    }

    public synchronized PlaybackOrigin getPlaybackOrigin() {
        return playbackOrigin;
    }

    public synchronized boolean isPreparing() {
        return engineState == EngineState.PREPARING;
    }

    public synchronized boolean isPrepared() {
        return isPreparedState(engineState);
    }

    public synchronized boolean expectsPlayback() {
        return desiredPlayback == DesiredPlayback.PLAY;
    }

    public synchronized boolean isFocusBlockingPlayback() {
        return focusState == FocusState.LOST_TRANSIENT
                || focusState == FocusState.LOST_PERMANENT;
    }

    public synchronized boolean canStart(long generation, PlaybackOrigin origin) {
        return generation == generationId
                && desiredPlayback == DesiredPlayback.PLAY
                && focusAllowsPlayback(focusState, origin)
                && engineState != EngineState.ERROR
                && engineState != EngineState.RELEASED;
    }

    public static boolean shouldFadeBeforeReset(EngineState state, float currentGain) {
        return currentGain > 0.0f && isPotentiallyAudibleState(state);
    }

    public static boolean shouldRestoreGainOnFocusGain(DesiredPlayback desired,
                                                        EngineState state,
                                                        boolean engineActuallyPlaying) {
        return desired == DesiredPlayback.PLAY
                && isPreparedState(state)
                && engineActuallyPlaying;
    }

    public static boolean shouldCompletePause(PauseReason reason,
                                               DesiredPlayback desired,
                                               FocusState focus) {
        if (reason == PauseReason.USER_REQUEST) {
            return desired == DesiredPlayback.PAUSE || desired == DesiredPlayback.STOP;
        }
        if (reason == PauseReason.FOCUS_LOSS) {
            return focus == FocusState.LOST_TRANSIENT || focus == FocusState.LOST_PERMANENT;
        }
        return false;
    }

    public static boolean shouldToggleToPlay(DesiredPlayback desired,
                                              EngineState state,
                                              boolean engineActuallyPlaying) {
        if (desired != DesiredPlayback.PLAY) {
            return true;
        }
        if (state == EngineState.PREPARING || state == EngineState.SEEKING) {
            return false;
        }
        return state != EngineState.PLAYING || !engineActuallyPlaying;
    }

    public static PlaybackOrigin playbackOriginForPlaylistStart(boolean automaticResume) {
        return automaticResume ? PlaybackOrigin.AUTO_RESUME : PlaybackOrigin.USER_UI;
    }

    public static boolean focusAllowsPlayback(FocusState focus, PlaybackOrigin origin) {
        if (focus == FocusState.LOST_TRANSIENT || focus == FocusState.LOST_PERMANENT) {
            return false;
        }
        if (focus == FocusState.DENIED) {
            return origin == PlaybackOrigin.USER_UI || origin == PlaybackOrigin.MEDIA_BUTTON;
        }
        return true;
    }

    /**
     * 看门狗该做什么。焦点被拒 (DENIED) 时重建串流注定无声, 只会反复 reset/prepare 并刷提示;
     * 但 API 18 上被拒的请求不会在稍后收到 AUDIOFOCUS_GAIN (那只发给曾经持有过焦点的监听器),
     * 所以也不能干等 —— 必须自己按退避节奏重新申请焦点。
     * 焦点是「丢失」而非「被拒」时相反: 我们曾持有过焦点, 释放后会收到 GAIN, 静等即可。
     */
    public static WatchdogAction watchdogAction(DesiredPlayback desired,
                                                FocusState focus,
                                                PlaybackOrigin recoveryOrigin) {
        if (desired != DesiredPlayback.PLAY) {
            return WatchdogAction.IDLE;
        }
        if (focus == FocusState.LOST_TRANSIENT || focus == FocusState.LOST_PERMANENT) {
            return WatchdogAction.IDLE;
        }
        if (!focusAllowsPlayback(focus, recoveryOrigin)) {
            return WatchdogAction.RETRY_FOCUS;
        }
        return WatchdogAction.REBUILD_STREAM;
    }

    /**
     * 自动续播位置。观测到的进度只有属于当前曲目时才可用, 否则会把上一首的断点带到新曲目上。
     */
    public static int resumePositionForTrack(String currentTrackId,
                                            String observedTrackId,
                                            int observedPositionMs,
                                            long trackDurationMs) {
        boolean sameTrack = currentTrackId != null && currentTrackId.equals(observedTrackId);
        int resumeMs = sameTrack ? Math.max(observedPositionMs, 0) : 0;
        if (trackDurationMs > 0 && resumeMs > trackDurationMs - END_OF_TRACK_GUARD_MS) {
            resumeMs = 0;
        }
        return resumeMs;
    }

    /**
     * 串流错误后的处置。attemptsAlreadyUsed 为本曲已用掉的尝试次数。
     */
    public static StreamRetryAction streamRetryAction(boolean recoverable,
                                                      int attemptsAlreadyUsed,
                                                      boolean reauthCooldownElapsed) {
        if (!recoverable) {
            return StreamRetryAction.GIVE_UP;
        }
        int attempt = attemptsAlreadyUsed + 1;
        if (attempt > MAX_STREAM_RETRY_ATTEMPTS) {
            return StreamRetryAction.GIVE_UP;
        }
        if (attempt < REAUTH_ATTEMPT) {
            return StreamRetryAction.PLAIN_RETRY;
        }
        return reauthCooldownElapsed ? StreamRetryAction.REAUTH_RETRY : StreamRetryAction.PLAIN_RETRY;
    }

    /**
     * 慢网冷启动时系统 MediaPlayer 抛 (what=1, extra="-19"): start/seek 撞车, 底层数据源瞬时不可用,
     * 属传输层问题, 重试即可恢复。旧行为把它当致命错误直接 GIVE_UP, 而看门狗每 ≤60s 又重启一次
     * 当前曲目, 于是每轮都复现一次错误 —— Toast 与日志无限循环刷屏。
     */
    public static boolean isTransientTransportError(int what, String extra) {
        return what == MEDIA_ERROR_UNKNOWN_WHAT && TRANSIENT_TRANSPORT_EXTRA.equals(extra);
    }

    /** 传输层错误与会话有效性无关, 重新登录只会白等一轮鉴权冷却, 降级成直接重试。 */
    public static StreamRetryAction effectiveRetryAction(StreamRetryAction action,
                                                         boolean transientTransportError) {
        if (transientTransportError && action == StreamRetryAction.REAUTH_RETRY) {
            return StreamRetryAction.PLAIN_RETRY;
        }
        return action;
    }

    /**
     * 同一轮起播未成功期间 (errorStreak 从 1 起计) 只提示用户一次。慢网下看门狗按指数退避
     * 反复重启当前曲目, 每次都弹 Toast 会盖满车机屏幕; 后续重复只落日志与面包屑。
     * errorStreak 在起播成功或用户切歌时归零, 因此下一轮仍有提示机会。
     */
    public static boolean shouldNotifyError(int errorStreak) {
        return errorStreak <= 1;
    }

    private static boolean isPreparedState(EngineState state) {
        return state == EngineState.READY
                || state == EngineState.PLAYING
                || state == EngineState.PAUSED
                || state == EngineState.SEEKING;
    }

    private static boolean isPotentiallyAudibleState(EngineState state) {
        return state == EngineState.PREPARING || isPreparedState(state);
    }
}
