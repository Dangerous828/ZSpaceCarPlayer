package com.ktools.zspacecarplayer.service;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

/**
 * 用户音乐音量意图观察器 (2026-09-11 实车 #2/#3/#5)。
 *
 * 为什么需要它: 功放唤醒 ({@link GeelyAmpWakeStrategy}) 的手法是「短暂改动系统
 * STREAM_MUSIC 音量再改回」, 而车机静音键在定制 ROM 上只置静音标志、不清零音量,
 * App 侧靠 isMasterMute/isStreamMute 反射又未必读得到。API 18 就能用、且不依赖隐藏
 * 方法的用户意图信号只有一个: {@code Settings.System volume_music} 的变化通知 ——
 * 用户按音量键 (含把音量压到 0 的静音操作) 都会让 AudioService 重写这个值。
 *
 * 有了「上一次用户改动音量的时刻」, 唤醒策略就能做到:
 *   1. 用户正在/刚刚按音量键时完全不下发 probe (不与用户抢旋钮, #5);
 *   2. 把 App 自己的 probe 写入与用户改动区分开, 避免自发通知被误判成用户意图。
 *
 * 静音标志本身不在这个观察器的职责内 (无稳定的 Settings 键), 由
 * {@link GeelyAmpWakeStrategy.MuteState} 三态探测 + fail-safe 覆盖。
 */
final class MusicVolumeIntentTracker implements GeelyAmpWakeStrategy.UserVolumeIntent {

    private static final String TAG = "MusicVolumeIntent";
    /**
     * {@code Settings.System.VOLUME_MUSIC} 的字面值。不引用常量是为了与 SDK 版本解耦:
     * 该键自 API 1 起就由 AudioService 持久化媒体流音量, 车机 4.3 ROM 同样会写。
     */
    private static final String KEY_VOLUME_MUSIC = "volume_music";
    /** App 写完音量后 ROM 的 Settings 通知可能晚到这么久; 期间值与我们写入的一致即自发通知。 */
    private static final long SELF_WRITE_SETTLE_MS = 800L;

    private final Context context;
    private final AudioManager audioManager;
    private final GeelyAmpWakeStrategy.Clock clock;
    private final ContentObserver observer;
    private final Object lock = new Object();

    private boolean registered;
    private long lastUserChangeAtMs = -1L;
    private boolean appWriteInProgress;
    private long appWriteEndAtMs = -1L;
    private int appWrittenVolume = -1;

    MusicVolumeIntentTracker(Context context, AudioManager audioManager, Handler handler,
                             GeelyAmpWakeStrategy.Clock clock) {
        this.context = context.getApplicationContext();
        this.audioManager = audioManager;
        this.clock = clock;
        this.observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                onVolumeSettingChanged();
            }
        };
    }

    /** 注册失败返回 false, 调用方据此退回「只看实时音量值」的保守判定。 */
    boolean register() {
        if (registered) {
            return true;
        }
        try {
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(KEY_VOLUME_MUSIC), false, observer);
            registered = true;
            Log.i(TAG, "Observing Settings.System." + KEY_VOLUME_MUSIC
                    + " for user volume intent");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot observe music volume setting", e);
            return false;
        }
    }

    void unregister() {
        if (!registered) {
            return;
        }
        registered = false;
        try {
            context.getContentResolver().unregisterContentObserver(observer);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public long lastUserChangeAtMs() {
        synchronized (lock) {
            return lastUserChangeAtMs;
        }
    }

    @Override
    public void notifyAppVolumeWriteStart() {
        synchronized (lock) {
            appWriteInProgress = true;
        }
    }

    @Override
    public void notifyAppVolumeWriteEnd(int writtenVolume) {
        synchronized (lock) {
            appWriteInProgress = false;
            appWrittenVolume = writtenVolume;
            appWriteEndAtMs = clock.elapsedRealtime();
        }
    }

    private void onVolumeSettingChanged() {
        int volume = readVolume();
        long now = clock.elapsedRealtime();
        synchronized (lock) {
            // probe 期间, 或 probe 刚结束的 ROM 通知晚到窗口内且值就是我们写下的那个
            // → 自发通知, 不是用户意图。值不一样则说明用户同时在动音量, 必须记下来。
            boolean selfInduced = appWriteInProgress
                    || (appWriteEndAtMs >= 0
                        && now - appWriteEndAtMs <= SELF_WRITE_SETTLE_MS
                        && volume == appWrittenVolume);
            if (selfInduced) {
                return;
            }
            lastUserChangeAtMs = now;
        }
        Log.i(TAG, "User changed system music volume -> " + volume
                + ", amp wake yields to user intent");
    }

    private int readVolume() {
        try {
            return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
