package com.ktools.zspacecarplayer.service;

import android.media.AudioManager;
import android.os.SystemClock;

/** Safe, rate-limited Geely amplifier channel wake strategy. */
public final class GeelyAmpWakeStrategy {

    static final long MIN_WAKE_INTERVAL_MS = 5000L;
    /** 同一播放 episode 的重试窗口: 首次唤醒后的窗口期内允许同 key 再次下发, 超窗终态拒绝。 */
    static final long RETRY_WINDOW_MS = 20000L;

    interface Clock {
        long elapsedRealtime();
    }

    interface AmpController {
        int getMaxMusicVolume();
        int getMusicVolume();
        void setMusicVolume(int volume);
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
        /** 被安全规则终态拦截: 系统音量为 0 (用户静音意图)、同 key 重复、或无法探测。 */
        SKIPPED,
        /** 被 5000ms 限频窗口拦截: 窗口过后允许以同一 key 重试。 */
        RATE_LIMITED
    }

    private final Clock clock;
    private final AmpController controller;
    private final WakeLimiter wakeLimiter;
    private long lastWakeKey = -1L;
    private long episodeFirstWakeAtMs;

    public GeelyAmpWakeStrategy(final AudioManager audioManager) {
        this(new Clock() {
            @Override
            public long elapsedRealtime() {
                return SystemClock.elapsedRealtime();
            }
        }, new AmpController() {
            @Override
            public int getMaxMusicVolume() {
                return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            }

            @Override
            public int getMusicVolume() {
                return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            }

            @Override
            public void setMusicVolume(int volume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            }
        }, GLOBAL_WAKE_LIMITER);
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

    /**
     * Re-applies a non-zero media volume without changing mute state or audio focus.
     * The wake key identifies one continuous playback episode (see
     * {@link PlaybackStateMachine#getAmplifierWakeKey()}), not a track generation: resuming
     * after a pause must be allowed to nudge again because the DSP may have gone to standby.
     * Within {@link #RETRY_WINDOW_MS} of an episode's first nudge, the same key may nudge
     * again (still rate-limited) — the amp DSP may only become ready after the first
     * volume re-apply. Beyond the window the same key is terminally skipped so a dead
     * amp channel can never cause endless volume jitter.
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

        // Absolute safety rule: physical/system mute is user intent and must never be undone.
        if (maxVolume <= 0 || currentVolume <= 0) {
            return WakeResult.SKIPPED;
        }

        long now = clock.elapsedRealtime();
        if (lastWakeKey == wakeKey) {
            if (now - episodeFirstWakeAtMs > RETRY_WINDOW_MS) {
                return WakeResult.SKIPPED;
            }
        } else {
            episodeFirstWakeAtMs = now;
        }

        int probeVolume = currentVolume < maxVolume ? currentVolume + 1 : currentVolume - 1;
        if (probeVolume <= 0 || probeVolume == currentVolume) {
            return WakeResult.SKIPPED;
        }

        if (!wakeLimiter.tryAcquire(now)) {
            return WakeResult.RATE_LIMITED;
        }

        controller.setMusicVolume(probeVolume);
        controller.setMusicVolume(currentVolume);
        lastWakeKey = wakeKey;
        return WakeResult.WOKEN;
    }
}
