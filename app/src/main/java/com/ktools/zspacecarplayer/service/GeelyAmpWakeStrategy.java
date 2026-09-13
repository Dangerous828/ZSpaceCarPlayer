package com.ktools.zspacecarplayer.service;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/** Safe, rate-limited Geely amplifier channel wake strategy. */
public final class GeelyAmpWakeStrategy {

    private static final String TAG = "GeelyAmpWake";

    static final long MIN_WAKE_INTERVAL_MS = 5000L;
    /** 同一播放 episode 的重试窗口: 首次唤醒后的窗口期内允许同 key 再次下发, 超窗终态拒绝。 */
    static final long RETRY_WINDOW_MS = 20000L;
    /**
     * 用户刚动过音量后的静默期 (2026-09-11 实车 #5)。车机音量键长按会连发几十次
     * VOLUME_DOWN, 这期间任何一次 probe 都会把音量写回 App 记住的旧值 —— 用户观感就是
     * 「调低了但效果不明显 / 音量被 App 劫持」。静默期内唤醒一律让位。
     */
    static final long USER_INTENT_QUIET_MS = 1500L;

    interface Clock {
        long elapsedRealtime();
    }

    /**
     * 静音状态三态探测结果。UNKNOWN = 这台 ROM 上读不到静音标志。
     *
     * 旧实现把「读不到」当成「没静音」(fail-open), 是 #2/#3 的直接根因: 车机静音键只置
     * 静音标志、不清零音量, getStreamVolume 仍 >0, 于是 probe 照做, 而定制 ROM 把
     * setStreamVolume 解释成「用户在调音量」, 隐式清掉静音标志 → 用户的静音被 App 关掉。
     * 现在 UNKNOWN 与 MUTED 一样按红线处理 (fail-safe): 拿不准就绝不动系统音量。
     */
    enum MuteState {
        MUTED, UNMUTED, UNKNOWN
    }

    interface AmpController {
        int getMaxMusicVolume();
        int getMusicVolume();
        /** 系统是否处于静音状态 (master mute / ROM 静音键)。静音是用户意图，唤醒不得破坏。 */
        boolean isMusicMuted();
        /** 三态静音探测; 本机 ROM 读不到静音标志时返回 {@link MuteState#UNKNOWN}。 */
        MuteState readMuteState();
        void setMusicVolume(int volume);
    }

    /**
     * 用户音量意图来源 (实现见 {@link MusicVolumeIntentTracker})。可以为 null —— 此时
     * 唤醒只依赖「读到的音量值」判定, 少了「用户正在连按音量键」这一层保护。
     */
    interface UserVolumeIntent {
        /** 最近一次「非本 App 写入」的音量变化时刻 (elapsedRealtime); <0 表示从未观察到。 */
        long lastUserChangeAtMs();
        /** probe 开始前调用: 让观察器把随后到达的 Settings 通知认作 App 自发, 不算用户意图。 */
        void notifyAppVolumeWriteStart();
        /** probe 结束后调用, writtenVolume = App 最终留在系统里的音量值。 */
        void notifyAppVolumeWriteEnd(int writtenVolume);
    }

    static final class WakeLimiter {
        private boolean hasWoken;
        private long lastWakeAtMs;

        synchronized boolean tryAcquire(long nowMs) {
            if (hasWoken && nowMs - lastWakeAtMs < MIN_WAKE_INTERVAL_MS) {
                return false;
            }
            hasWoken = true;
            lastWakeAtMs = nowMs;
            return true;
        }
    }

    private static final WakeLimiter GLOBAL_WAKE_LIMITER = new WakeLimiter();

    /** 唤醒结果细分: 限频拒绝可以被调用方延迟重试, 其余拒绝是终态。 */
    public enum WakeResult {
        /** HAL 音量已重新下发, 功放通道已唤醒。 */
        WOKEN,
        /**
         * 被安全规则终态拦截: 系统音量为 0、静音标志已置位/读不到、用户刚改过音量、
         * 同 key 超过重试窗口、或无法构造净零 probe。调用方不得重试。
         */
        SKIPPED,
        /** 被 5000ms 限频窗口拦截: 窗口过后允许以同一 key 重试。 */
        RATE_LIMITED
    }

    private final Clock clock;
    private final AmpController controller;
    private final WakeLimiter wakeLimiter;
    private UserVolumeIntent userIntent;
    private long lastWakeKey = -1L;
    private long episodeFirstWakeAtMs;
    /**
     * 本 episode 已被用户音量意图封锁: 之后的 nudge 与跟进重试一律终态 SKIPPED。
     * 只在「用户在两个非零音量之间改动过音量」或「probe 期间用户静音」时置位;
     * 用户解除静音 / 把音量从 0 抬起来时自动解除 (那一刻恰恰需要重新唤醒功放)。
     */
    private boolean episodeBlocked;
    /** App 最近一次「看到并认可」的系统音乐音量; 与实时读值不一致即说明用户改过。 */
    private int lastKnownVolume = -1;
    /** 上一次 nudge 读到的静音状态; MUTED→UNMUTED 的跳变意味着用户刚解除静音。 */
    private MuteState lastObservedMute = MuteState.UNKNOWN;

    public GeelyAmpWakeStrategy(final AudioManager audioManager) {
        this(new ElapsedRealtimeClock(), new SystemAmpController(audioManager), GLOBAL_WAKE_LIMITER);
    }

    /**
     * 完整形态: 附带 {@code Settings.System volume_music} 观察器, 用于「用户刚动过音量就让位」。
     * 由 AudioPlayerService 在服务创建时装配; 服务销毁必须调 {@link #release()} 反注册,
     * 否则观察器会连带持有 Context 泄漏。
     */
    public GeelyAmpWakeStrategy(Context context, AudioManager audioManager, Handler handler) {
        this(new ElapsedRealtimeClock(), new SystemAmpController(audioManager), GLOBAL_WAKE_LIMITER);
        if (context == null || audioManager == null || handler == null) {
            return;
        }
        try {
            MusicVolumeIntentTracker tracker =
                    new MusicVolumeIntentTracker(context, audioManager, handler, clock);
            if (tracker.register()) {
                this.userIntent = tracker;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Volume intent observer unavailable, wake relies on read-only checks", e);
        }
    }

    GeelyAmpWakeStrategy(Clock clock, AmpController controller) {
        this(clock, controller, new WakeLimiter());
    }

    GeelyAmpWakeStrategy(Clock clock, AmpController controller, WakeLimiter wakeLimiter) {
        if (clock == null || controller == null || wakeLimiter == null) {
            throw new IllegalArgumentException("clock, controller and limiter are required");
        }
        this.clock = clock;
        this.controller = controller;
        this.wakeLimiter = wakeLimiter;
    }

    /** 仅供单测注入用户音量意图源。 */
    void setUserVolumeIntent(UserVolumeIntent userIntent) {
        this.userIntent = userIntent;
    }

    /** 反注册观察器 (服务销毁时调用)。 */
    public synchronized void release() {
        if (userIntent instanceof MusicVolumeIntentTracker) {
            ((MusicVolumeIntentTracker) userIntent).unregister();
        }
        userIntent = null;
    }

    /**
     * 当前 episode 是否已因用户音量/静音意图被封锁。调用方据此立刻放弃已排程的跟进重试,
     * 不必等到重试真正跑起来才发现要跳过。
     */
    public synchronized boolean isUserIntentBlocked() {
        return episodeBlocked;
    }

    /**
     * Re-applies a non-zero media volume without changing mute state or audio focus.
     * The wake key identifies one continuous playback episode (see
     * {@link PlaybackStateMachine#getAmplifierWakeKey()}), not a track generation: resuming
     * after a pause must be allowed to nudge again because the DSP may have gone to standby.
     * Within {@link #RETRY_WINDOW_MS} of an episode's first nudge, the same key may nudge
     * again (still rate-limited) — the amp DSP may only become ready after the first
     * volume re-apply. Beyond the window the same key is terminally skipped so a dead
     * amp channel can never cause endless volume jitter.
     *
     * 2026-09-11 起增加三道「用户意图优先」闸门 (#2/#3/#5): 静音标志三态探测 (读不到即跳过)、
     * 与 App 上次看到的音量对比 (用户改过就终态封锁本 episode)、用户按键静默期 (不抢旋钮)。
     * 但用户一解除静音 / 把音量从 0 抬起来, 封锁立即解除且重试窗口重新起算 —— 那一刻恰恰
     * 最需要唤醒功放 (车机物理静音解除后媒体通道会保持静默)。
     * 唤醒对用户的净效果必须恒为零: 只在「用户当前音量」上做 +1/-1 再回落。
     * Returns true only when a HAL volume nudge was issued.
     */
    public synchronized boolean wake(long wakeKey) {
        return wakeDetailed(wakeKey) == WakeResult.WOKEN;
    }

    /**
     * 同 {@link #wake(long)}, 但区分限频拒绝 (可重试) 与终态拒绝。
     */
    public synchronized WakeResult wakeDetailed(long wakeKey) {
        int maxVolume = controller.getMaxMusicVolume();
        int currentVolume = controller.getMusicVolume();
        long now = clock.elapsedRealtime();

        if (maxVolume <= 0) {
            return skip("maxVolume=" + maxVolume);
        }

        // 红线 1: 系统媒体音量为 0。多数车机 ROM 的静音键就是把媒体音量压到 0,
        // 抬回去等于替用户解除静音。基线记成 0, 用户抬起来后要能识别成「解除静音」。
        if (currentVolume <= 0) {
            lastKnownVolume = currentVolume;
            return skip("volume=0 (user silence intent)");
        }

        // 红线 2: 静音标志。只有明确读到「未静音」才允许去动系统音量; 读到静音、或这台 ROM
        // 根本读不到 (UNKNOWN) 都一律跳过 —— 拿不准就不动, 绝不 fail-open。
        MuteState muteState = controller.readMuteState();
        // 「刚从静音/音量 0 恢复」恰恰是功放唤醒存在的理由 (车机物理静音解除后, 功放媒体
        // 通道会保持静默), 这一跳必须放行并解除上一轮的用户意图封锁, 否则解除静音后永远没声。
        boolean cameOutOfSilence = lastKnownVolume == 0
                || (lastObservedMute == MuteState.MUTED && muteState == MuteState.UNMUTED);
        lastObservedMute = muteState;
        if (muteState != MuteState.UNMUTED) {
            // 静音期间音量值本身没变, 基线跟着刷新, 解除静音后不会因音量差被误判成抢旋钮
            lastKnownVolume = currentVolume;
            return skip("muteState=" + muteState);
        }

        if (lastWakeKey != wakeKey) {
            // 新 episode (起播/切歌/长暂停恢复): 解除上一轮的用户意图封锁, 并把音量基线
            // 同步到用户此刻的值 —— 唤醒永远只在「用户当前音量」上做净零 probe。
            lastWakeKey = wakeKey;
            episodeFirstWakeAtMs = now;
            episodeBlocked = false;
            lastKnownVolume = currentVolume;
        } else if (cameOutOfSilence) {
            // 解除静音是一个全新的「功放通道可能静默」时刻, 重试窗口从这里重新起算,
            // 否则长播放中解除静音会因为窗口已过而拿不到唤醒 (5s 限频仍然生效, 不会抖动)
            episodeBlocked = false;
            episodeFirstWakeAtMs = now;
            lastKnownVolume = currentVolume;
            Log.i(TAG, "User released mute/zero volume, amp wake allowed again at volume "
                    + currentVolume);
        } else if (episodeBlocked) {
            // 本 episode 已让位用户意图, 跟进重试不再下发 (否则就是把音量拉回旧值 = #5)
            return skip("episode blocked by user volume intent");
        } else if (currentVolume != lastKnownVolume) {
            // 用户在本 episode 内改过系统音量: 唤醒重试必须让位, 终态封锁本 episode,
            // 并且绝不把音量写回 App 记住的旧值。用户自己那次 setStreamVolume 已经把音量
            // 重下发给 HAL, 功放通道本就被这次操作唤醒, App 再插一手只会与用户抢旋钮。
            episodeBlocked = true;
            lastKnownVolume = currentVolume;
            return skip("user moved volume to " + currentVolume);
        }

        if (now - episodeFirstWakeAtMs > RETRY_WINDOW_MS) {
            return skip("retry window elapsed (" + (now - episodeFirstWakeAtMs) + "ms)");
        }

        // 用户正在按音量键 (长按连发): 此刻 probe 必然与用户抢同一个旋钮, 这一跳直接让位。
        // 不做终态封锁 —— 音量真的变了会由上面的基线对比拦下, 没变则下一跳照常唤醒。
        if (userIntent != null) {
            long lastUserChangeAtMs = userIntent.lastUserChangeAtMs();
            if (lastUserChangeAtMs >= 0 && now - lastUserChangeAtMs < USER_INTENT_QUIET_MS) {
                return skip("user adjusted volume " + (now - lastUserChangeAtMs) + "ms ago");
            }
        }

        int probeVolume = currentVolume < maxVolume ? currentVolume + 1 : currentVolume - 1;
        if (probeVolume <= 0 || probeVolume == currentVolume) {
            return skip("no net-zero probe volume (cur=" + currentVolume + " max=" + maxVolume + ")");
        }

        if (!wakeLimiter.tryAcquire(now)) {
            return WakeResult.RATE_LIMITED;
        }

        if (userIntent != null) {
            userIntent.notifyAppVolumeWriteStart();
        }
        try {
            controller.setMusicVolume(probeVolume);
            controller.setMusicVolume(currentVolume);
        } finally {
            if (userIntent != null) {
                userIntent.notifyAppVolumeWriteEnd(currentVolume);
            }
        }
        // ROM 的 setStreamVolume 可能异步生效, 两次 set 之间竞态会让音量停在 probe 值。
        // 这里只回收「我们自己留下的 probe 值」: 读到别的值说明用户此刻正在改音量,
        // 一律不再写 —— App 绝不以任何理由重写用户刚设定的 STREAM_MUSIC 音量 (#5)。
        int settled = controller.getMusicVolume();
        if (settled == probeVolume) {
            controller.setMusicVolume(currentVolume);
            settled = controller.getMusicVolume();
        }
        // 基线跟着实际落地值走: 用户若在 probe 期间把音量压到 0, 基线也要记 0, 否则下一次
        // 抬起来时识别不出「用户解除静音」, 就会拒绝唤醒 (那正是本策略存在的场景)。
        lastKnownVolume = Math.max(settled, 0);
        MuteState afterProbe = controller.readMuteState();
        lastObservedMute = afterProbe;
        // probe 期间用户可能按了静音 / 把音量压到 0: 本 episode 不再打扰, 让位给用户
        if (settled <= 0 || afterProbe != MuteState.UNMUTED) {
            episodeBlocked = true;
            Log.i(TAG, "User silenced during amp probe, no further nudge this episode");
        }
        return WakeResult.WOKEN;
    }

    private WakeResult skip(String reason) {
        Log.i(TAG, "Amp wake skipped: " + reason);
        return WakeResult.SKIPPED;
    }

    private static final class ElapsedRealtimeClock implements Clock {
        @Override
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }

    /**
     * AudioManager 实现: 静音相关方法全是隐藏/已移除 API, 一律走反射, 保证 minSdk 18
     * (Android 4.3) 车机可用 —— 4.3 上 isMasterMute/setStreamMute 反而是公开方法,
     * 新版 SDK 才移除, 所以编译期不能用直接调用。
     */
    private static final class SystemAmpController implements AmpController {

        private final AudioManager audioManager;
        /** 隐藏方法在给定 ROM 上要么一直可达要么一直不可达, 只解析一次并缓存。 */
        private boolean muteProbeResolved;
        private Method masterMuteRead;
        private Method streamMuteRead;
        private Object audioService;
        private Method serviceStreamMuteRead;
        private Method serviceMasterMuteRead;

        SystemAmpController(AudioManager audioManager) {
            this.audioManager = audioManager;
        }

        @Override
        public int getMaxMusicVolume() {
            return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        }

        @Override
        public int getMusicVolume() {
            return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }

        @Override
        public boolean isMusicMuted() {
            return readMuteState() == MuteState.MUTED;
        }

        @Override
        public MuteState readMuteState() {
            resolveMuteProbes();
            // 1) master mute (整车静音)。true 即静音; false 不足以证明媒体流没被单独静音
            //    (车机静音键通常落在流级别), 必须继续往流级别探, 探不到就是 UNKNOWN。
            Object master = invoke(masterMuteRead, audioManager);
            if (Boolean.TRUE.equals(master)) {
                return MuteState.MUTED;
            }
            // 2) AudioManager.isStreamMute(int): API 18 为隐藏方法, 部分 ROM 返回 mute 计数
            MuteState streamState = toMuteState(invoke(streamMuteRead, audioManager,
                    AudioManager.STREAM_MUSIC));
            if (streamState != MuteState.UNKNOWN) {
                return streamState;
            }
            // 3) 直连 AudioService: 静音标志的真身在这里 (dumpsys audio 的 "Mute count"
            //    就是它), AudioManager 上没有暴露对应方法时从 IAudioService 读。
            if (audioService != null) {
                MuteState serviceStream = toMuteState(invoke(serviceStreamMuteRead, audioService,
                        AudioManager.STREAM_MUSIC));
                if (serviceStream != MuteState.UNKNOWN) {
                    return serviceStream;
                }
                if (Boolean.TRUE.equals(invoke(serviceMasterMuteRead, audioService))) {
                    return MuteState.MUTED;
                }
            }
            return MuteState.UNKNOWN;
        }

        @Override
        public void setMusicVolume(int volume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        }

        private void resolveMuteProbes() {
            if (muteProbeResolved) {
                return;
            }
            muteProbeResolved = true;
            if (audioManager != null) {
                masterMuteRead = findMethod(audioManager.getClass(), "isMasterMute");
                streamMuteRead = findMethod(audioManager.getClass(), "isStreamMute", int.class);
            }
            try {
                Class<?> serviceManager = Class.forName("android.os.ServiceManager");
                Object binder = serviceManager.getMethod("getService", String.class)
                        .invoke(null, "audio");
                if (binder instanceof IBinder) {
                    Class<?> stub = Class.forName("android.media.IAudioService$Stub");
                    audioService = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
                }
                if (audioService != null) {
                    serviceStreamMuteRead = findMethod(audioService.getClass(), "isStreamMute", int.class);
                    serviceMasterMuteRead = findMethod(audioService.getClass(), "isMasterMute");
                }
            } catch (Throwable ignored) {
                audioService = null;
            }
            if (masterMuteRead == null && streamMuteRead == null
                    && serviceStreamMuteRead == null && serviceMasterMuteRead == null) {
                // 这台 ROM 上静音标志完全读不到 → 功放唤醒会被红线永久拦下 (宁可无声也不
                // 解除用户静音)。实车看到这行就说明 #2/#3 的修复生效在「fail-safe」分支上,
                // 若同时出现「长暂停恢复无声」需要回来单独评估这台 ROM 的静音实现。
                Log.w(TAG, "Mute flag unreadable on this ROM (no isMasterMute/isStreamMute/"
                        + "IAudioService) -> amp wake stays disabled to protect user mute");
            } else {
                Log.i(TAG, "Mute probes resolved: masterMute=" + (masterMuteRead != null)
                        + " streamMute=" + (streamMuteRead != null)
                        + " audioService=" + (audioService != null)
                        + " svcStreamMute=" + (serviceStreamMuteRead != null)
                        + " svcMasterMute=" + (serviceMasterMuteRead != null));
            }
        }

        private static Method findMethod(Class<?> type, String name, Class<?>... params) {
            if (type == null) {
                return null;
            }
            try {
                return type.getMethod(name, params);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object invoke(Method method, Object target, Object... args) {
            if (method == null || target == null) {
                return null;
            }
            try {
                return method.invoke(target, args);
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** 隐藏实现有的返回 boolean, 有的返回 mute 计数 (>0 即静音); 都取不到即 UNKNOWN。 */
        private static MuteState toMuteState(Object value) {
            if (value instanceof Boolean) {
                return ((Boolean) value) ? MuteState.MUTED : MuteState.UNMUTED;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue() > 0 ? MuteState.MUTED : MuteState.UNMUTED;
            }
            return MuteState.UNKNOWN;
        }
    }
}
