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
    /** 同曲直接重试的退避起点 (翻倍递增)；与看门狗的 6s 起点是两条独立序列。 */
    private static final long STREAM_RETRY_BACKOFF_BASE_MS = 6000L;
    /** android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN 的值 (本类不依赖 Android, 故镜像于此)。 */
    private static final int MEDIA_ERROR_UNKNOWN_WHAT = 1;
    /** 慢网冷启动时 start/seek 撞车产生的传输层错误 extra, 重试即可恢复。 */
    private static final String TRANSIENT_TRANSPORT_EXTRA = "-19";
    /** 进度 tick 周期: AudioPlayerService.progressRunnable 的自投递间隔。 */
    public static final long PROGRESS_TICK_MS = 500L;
    /** 进度落库节流窗口: MainActivity.onProgressUpdate 每 5s 才写一次 song_progress。 */
    public static final long PROGRESS_SAVE_THROTTLE_MS = 5000L;
    /**
     * 距曲尾这么近就从头重播, 避免恢复瞬间又触发 onCompletion 跳下一首。
     *
     * 2026-09-12 #1「切歌之后自动跳到曲尾」的残留根因: 旧值 3000ms **小于**落库节流窗口
     * (5000ms) + tick (500ms)。一首歌自然播完时, 最后一次落库的位置必然落在
     * [时长-5.5s, 时长) 区间内, 其中 [时长-5.5s, 时长-3s) 这一段既能躲过保存侧清洗、
     * 又能躲过起播侧钳制 —— 于是每首播完的歌都在 song_progress 里留下一个「合法」的
     * 贴尾断点, 下次点它就被 seek 到曲尾, 几秒后又 COMPLETED 跳下一首。
     * guard 必须严格大于一个完整节流窗口 (见 {@link #isEndGuardWiderThanSaveWindow()}),
     * 这条缝隙才会真正闭合; 代价只是「最后 8 秒不可续播」, 对用户无感。
     */
    private static final int END_OF_TRACK_GUARD_MS = 8000;

    /**
     * 回归护栏: 曲尾 guard 必须宽于「一次落库节流 + 一个 tick」, 否则贴尾脏断点又能溜进库。
     * 单测直接断言本方法, 防止有人日后把 guard 调回 3000 而复现 #1。
     */
    public static boolean isEndGuardWiderThanSaveWindow() {
        return END_OF_TRACK_GUARD_MS > PROGRESS_SAVE_THROTTLE_MS + PROGRESS_TICK_MS;
    }

    /** 当前曲尾 guard (ms), 供日志与兜底窗口换算使用。 */
    public static int endOfTrackGuardMs() {
        return END_OF_TRACK_GUARD_MS;
    }

    /**
     * 「该断点是否已等于播到尾」的唯一判定 (2026-09-10 实车定位)。
     * 保存侧与起播侧此前各写一份 2000/3000 的阈值, 语义分叉迟早出事, 统一收口于此。
     * 注意本判定以传入的 durationMs 为基准: 元数据 (RunTimeTicks) 缺失或偏大时会失真,
     * 所以起播路径还要在 prepared 回调里用播放器真实时长再钳一次, 并且起播后仍有
     * {@link #isBadResumeLanding} / {@link #shouldReplayInsteadOfAdvance} 两道非元数据依赖的兜底。
     * 时长未知 (<=0) 时无法判定, 保守返回 false。
     */
    public static boolean isEffectivelyAtEnd(long durationMs, long positionMs) {
        return durationMs > 0 && positionMs >= durationMs - END_OF_TRACK_GUARD_MS;
    }

    /**
     * 进度落库前的清洗 (2026-09-12 #1)。
     *
     * realDurationMs 是播放器 tick 带回的真实时长, metaDurationMs 是 Jellyfin RunTimeTicks。
     * 只用元数据判定有两个漏洞: 元数据缺失 (0) 时任何贴尾脏值都原样入库; 元数据比转码流
     * 实际时长偏大时, 「已经越过真实曲尾」的位置照样被判成合法断点。故真实时长优先,
     * 两者都未知才放行 (此时起播侧会因 seekMs &lt; durationMs 不成立而丢弃断点, 不会跳到曲尾)。
     */
    public static int sanitizeProgressForSave(long metaDurationMs, long realDurationMs,
                                              int progressMs) {
        if (progressMs <= 0) return 0;
        long effectiveDurationMs = realDurationMs > 0 ? realDurationMs : metaDurationMs;
        if (isEffectivelyAtEnd(effectiveDurationMs, progressMs)) return 0;
        return progressMs;
    }

    /**
     * 带断点起播后, 出声不足该时长就 COMPLETED ⇒ 断点其实贴在真实曲尾。
     * 必须小于 {@link #END_OF_TRACK_GUARD_MS}, 否则合法断点 (刚过 guard 边界) 会被误判。
     */
    public static final long COMPLETION_TOO_FAST_MS = 6000L;

    /**
     * 起播后兜底之一: 「播完得太快」判定 (2026-09-12 #1)。
     *
     * 起播时真实时长可能未知 (流式/实时转码, 代理拿不到 Content-Length → MediaPlayer
     * getDuration()==0) 或与元数据不符, 此时前两道按时长钳制的防线都会失效。这条判定
     * 只看「本次是否带断点起播」+「实际出声了多久」, 不依赖任何时长: 断点起播后几秒内
     * 就 COMPLETED, 只可能是断点落在贴尾, 应当从头重播本曲, 而不是当成播完跳下一首。
     * playedMs 只累计真正出声的 tick, 暂停时长不算, 因此长暂停后恢复不会被误判。
     * alreadyReplayedOnce 保证一次性, 不会与重播形成死循环。
     */
    public static boolean shouldReplayInsteadOfAdvance(boolean startedFromResumePoint,
                                                       long playedMs,
                                                       boolean alreadyReplayedOnce) {
        return startedFromResumePoint
                && !alreadyReplayedOnce
                && playedMs >= 0
                && playedMs < COMPLETION_TOO_FAST_MS;
    }

    /**
     * 起播后兜底之二: 「首个 progress tick 就贴在真实曲尾」判定 (2026-09-12 #1)。
     *
     * prepared 回调可能报 0 时长, 而系统 MediaPlayer 对串流的时长往往在起播后若干 tick
     * 才收敛; 这里用 tick 上的真实时长再判一次, 命中即视为坏断点, 立刻从头重播本曲,
     * 不等它播完几秒再假 COMPLETED 乱跳。realDurationMs 未知时返回 false (交给兜底之一)。
     */
    public static boolean isBadResumeLanding(boolean startedFromResumePoint,
                                             boolean alreadyCorrected,
                                             long realDurationMs,
                                             long positionMs) {
        return startedFromResumePoint
                && !alreadyCorrected
                && isEffectivelyAtEnd(realDurationMs, positionMs);
    }
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
     * 第 attempt 次 (1 起计) 同曲直接重试前应等待的毫秒数：6s 起步翻倍。
     *
     * 立刻重放会在两三秒内把 {@link #MAX_STREAM_RETRY_ATTEMPTS} 的预算烧光——蜂窝链路抖一下通常
     * 要几秒才回来，于是三次全撞在同一个坑上，然后 GIVE_UP 跳下一首，用户听到「歌自己跳了」。
     * 起点与假播放看门狗 (DEAD_RETRY_TICKS_START) 同档，但**两条序列各自独立**：这里是
     * onError 重试链的节奏，那里是「UI 显示在播但播放器已死」的兜底节奏，改一个不该带动另一个。
     *
     * 第 3 次 (REAUTH_ATTEMPT) 返回 0：那条路径要先异步重新登录，本身已在等网络，再叠一层等待
     * 只会更差；预算之外的 attempt 同样返回 0，交由 GIVE_UP 处理。
     */
    public static long streamRetryBackoffMs(int attempt) {
        if (attempt >= REAUTH_ATTEMPT) {
            return 0L;
        }
        return STREAM_RETRY_BACKOFF_BASE_MS << (attempt - 1);
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
