package com.ktools.zspacecarplayer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 系统静音监听 (2026-09-15 实车需求: 按静音键 = 暂停歌曲 + 系统静音)。
 *
 * <p>为什么不能靠拦按键: GEELY / Neusoft Optimus 车机 (API18) 的硬件静音键不走标准
 * Android 输入派发。实车取证:
 * <ul>
 *   <li>{@code getevent /dev/input/event0} 抓到静音键发原始 {@code KEY_MUTE}(scancode 113);</li>
 *   <li>同一时刻前台 MainActivity 的 {@code dispatchKeyEvent} 一行日志都没有 —— 键根本没进窗口;</li>
 *   <li>logcat 显示该键被系统 App {@code com.neusoft.optimus.wheeljack.popview} 经自定义
 *       AIDL HardKey 通道接走 ({@code @HardKeyAidl@ _keyCode=164}), 再由它调
 *       {@code AudioManager.setStreamMute(STREAM_MUSIC, true)} 真静音, 并通过
 *       {@code content://com.neusoft.optimus.mmi/VolumeChangeNotify} 通知音量/静音变化。</li>
 * </ul>
 *
 * <p>结论: 系统静音由 ROM 自己完成 (需求的一半天然满足), 本 App 无法拦键, 只能"监听系统
 * 静音状态的变化"来触发暂停。三路信号取并集, 任一生效即可:
 * <ol>
 *   <li>ContentObserver 观察 Neusoft 的 {@code VolumeChangeNotify} URI (ROM 原生信号, 即时);</li>
 *   <li>标准广播 {@code STREAM_MUTE_CHANGED / MASTER_MUTE_CHANGED / VOLUME_CHANGED_ACTION}
 *       (兜底, 部分 ROM 会发);</li>
 *   <li>1s 轮询 {@link #readMuteState()} (反射 isMasterMute/isStreamMute/IAudioService),
 *       保证即使前两路都不触发也能在 1s 内感知。</li>
 * </ol>
 *
 * <p>只在 UNMUTED→MUTED 跳变时回调 {@link Listener#onSystemMuted()} (Service 据此暂停),
 * MUTED→UNMUTED 跳变回调 {@link Listener#onSystemUnmuted()} (Service 据此决定是否恢复)。
 * 读不到静音标志 (UNKNOWN) 时不做任何跳变判定, 绝不 fail-open 误暂停 —— 与
 * {@link GeelyAmpWakeStrategy} 的 fail-safe 原则一致。
 */
final class SystemMuteMonitor {

    private static final String TAG = "SystemMuteMonitor";

    /** ROM 音量/静音变化通知 URI (实车 logcat 抓到的真实地址, 无需可 query 即可 observe)。 */
    private static final String NEUSOFT_VOLUME_NOTIFY_URI =
            "content://com.neusoft.optimus.mmi/VolumeChangeNotify";
    /** {@code Settings.System.VOLUME_MUSIC} 字面值 (本机 ROM 未使用, 作为通用兜底一并观察)。 */
    private static final String KEY_VOLUME_MUSIC = "volume_music";
    /** 轮询兜底间隔: ContentObserver/广播都没触发时, 最迟 1s 内感知静音跳变。 */
    private static final long POLL_INTERVAL_MS = 1000L;
    // 标准广播 action (AudioManager 里是隐藏常量, 用字面值以解耦 SDK 版本)
    private static final String ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED";
    private static final String ACTION_MASTER_MUTE_CHANGED = "android.media.MASTER_MUTE_CHANGED";
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";

    interface Listener {
        /** 系统刚从"未静音"跳到"静音" (用户按了静音键)。 */
        void onSystemMuted();
        /** 系统刚从"静音"跳到"未静音" (用户解除静音)。 */
        void onSystemUnmuted();
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Handler handler;
    private final Listener listener;

    private final Object lock = new Object();
    private boolean registered;
    private GeelyAmpWakeStrategy.MuteState lastState = GeelyAmpWakeStrategy.MuteState.UNKNOWN;
    /** 我们主动写静音 (setMusicMuted) 后, 抑制随之而来的一次自发跳变回调。 */
    private boolean suppressNextTransition;

    private ContentObserver observer;
    private BroadcastReceiver receiver;
    private Runnable pollRunnable;

    // --- 反射缓存 (隐藏方法在给定 ROM 上要么一直可达要么一直不可达, 只解析一次) ---
    private boolean probesResolved;
    private Method masterMuteRead;
    private Method streamMuteRead;
    private Method streamMuteWrite;
    private Object audioService;
    private Method serviceStreamMuteRead;
    private Method serviceMasterMuteRead;

    SystemMuteMonitor(Context context, AudioManager audioManager, Handler handler, Listener listener) {
        this.context = context.getApplicationContext();
        this.audioManager = audioManager;
        this.handler = handler;
        this.listener = listener;
    }

    /** 注册三路信号并开始轮询。重复调用幂等。 */
    void register() {
        synchronized (lock) {
            if (registered) return;
            registered = true;
            // 注册即读一次基线, 避免把"注册前就已静音"误判成一次新的跳变
            lastState = readMuteState();
            Log.i(TAG, "register: baseline muteState=" + lastState);
        }
        try {
            observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    checkNow("contentObserver");
                }
            };
            context.getContentResolver().registerContentObserver(
                    Uri.parse(NEUSOFT_VOLUME_NOTIFY_URI), false, observer);
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(KEY_VOLUME_MUSIC), false, observer);
            Log.i(TAG, "observing " + NEUSOFT_VOLUME_NOTIFY_URI + " + Settings." + KEY_VOLUME_MUSIC);
        } catch (Throwable t) {
            Log.w(TAG, "registerContentObserver failed, rely on poll+broadcast: " + t);
        }
        try {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    checkNow("broadcast:" + (intent != null ? intent.getAction() : "?"));
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_STREAM_MUTE_CHANGED);
            filter.addAction(ACTION_MASTER_MUTE_CHANGED);
            filter.addAction(ACTION_VOLUME_CHANGED);
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver failed, rely on poll+observer: " + t);
        }
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                checkNow("poll");
                if (handler != null && pollRunnable != null) {
                    handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                }
            }
        };
        if (handler != null) {
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }
    }

    /** 反注册全部信号并停止轮询 (Service 销毁时调用, 防止泄漏 Context)。 */
    void unregister() {
        synchronized (lock) {
            registered = false;
        }
        if (handler != null && pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
        pollRunnable = null;
        if (observer != null) {
            try {
                context.getContentResolver().unregisterContentObserver(observer);
            } catch (Throwable ignored) {
            }
            observer = null;
        }
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            receiver = null;
        }
    }

    /** 读一次静音状态, 与上次比较, 命中跳变则回调 listener。 */
    private void checkNow(String source) {
        GeelyAmpWakeStrategy.MuteState now = readMuteState();
        GeelyAmpWakeStrategy.MuteState prev;
        boolean fireMuted = false;
        boolean fireUnmuted = false;
        synchronized (lock) {
            prev = lastState;
            if (now == prev || now == GeelyAmpWakeStrategy.MuteState.UNKNOWN) {
                // 无变化 / 读不到: 不判定跳变 (UNKNOWN 不更新基线, 防止把可读态冲掉)
                if (now != GeelyAmpWakeStrategy.MuteState.UNKNOWN) lastState = now;
                return;
            }
            lastState = now;
            if (suppressNextTransition) {
                suppressNextTransition = false;
                Log.i(TAG, "transition " + prev + "->" + now + " suppressed (self-induced, " + source + ")");
                return;
            }
            if (prev == GeelyAmpWakeStrategy.MuteState.UNMUTED
                    && now == GeelyAmpWakeStrategy.MuteState.MUTED) {
                fireMuted = true;
            } else if (prev == GeelyAmpWakeStrategy.MuteState.MUTED
                    && now == GeelyAmpWakeStrategy.MuteState.UNMUTED) {
                fireUnmuted = true;
            }
        }
        if (fireMuted) {
            Log.i(TAG, "system MUTED (" + source + ") -> notify listener");
            if (listener != null) listener.onSystemMuted();
        } else if (fireUnmuted) {
            Log.i(TAG, "system UNMUTED (" + source + ") -> notify listener");
            if (listener != null) listener.onSystemUnmuted();
        }
    }

    /**
     * 主动设置系统媒体流静音 (反射 {@code AudioManager.setStreamMute}, API18 隐藏/新版移除)。
     * 供 Service 在"用户按播放键但系统仍处于静音"时解除静音, 避免恢复播放却无声。
     * 由本方法引发的 UNMUTED 跳变会被 {@link #suppressNextTransition} 抑制, 不会二次回调。
     *
     * @return 是否成功下发 (反射不可达/异常返回 false, 调用方不应依赖其必然生效)
     */
    boolean setMusicMuted(boolean muted) {
        resolveProbes();
        if (streamMuteWrite == null || audioManager == null) {
            Log.w(TAG, "setStreamMute unavailable on this ROM, cannot write mute=" + muted);
            return false;
        }
        synchronized (lock) {
            suppressNextTransition = true;
        }
        try {
            streamMuteWrite.invoke(audioManager, AudioManager.STREAM_MUSIC, muted);
            Log.i(TAG, "setStreamMute(STREAM_MUSIC," + muted + ") issued");
            // 写后立即刷新基线, 减少抑制窗口与真实用户操作冲突的概率
            synchronized (lock) {
                lastState = muted ? GeelyAmpWakeStrategy.MuteState.MUTED
                        : GeelyAmpWakeStrategy.MuteState.UNMUTED;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setStreamMute invoke failed: " + t);
            synchronized (lock) {
                suppressNextTransition = false;
            }
            return false;
        }
    }

    /** 三态静音探测; 与 {@link GeelyAmpWakeStrategy} 同一套反射, 读不到返回 UNKNOWN。 */
    GeelyAmpWakeStrategy.MuteState readMuteState() {
        resolveProbes();
        if (audioManager == null) return GeelyAmpWakeStrategy.MuteState.UNKNOWN;
        Object master = invoke(masterMuteRead, audioManager);
        if (Boolean.TRUE.equals(master)) {
            return GeelyAmpWakeStrategy.MuteState.MUTED;
        }
        GeelyAmpWakeStrategy.MuteState streamState =
                toMuteState(invoke(streamMuteRead, audioManager, AudioManager.STREAM_MUSIC));
        if (streamState != GeelyAmpWakeStrategy.MuteState.UNKNOWN) {
            return streamState;
        }
        if (audioService != null) {
            GeelyAmpWakeStrategy.MuteState serviceStream = toMuteState(
                    invoke(serviceStreamMuteRead, audioService, AudioManager.STREAM_MUSIC));
            if (serviceStream != GeelyAmpWakeStrategy.MuteState.UNKNOWN) {
                return serviceStream;
            }
            if (Boolean.TRUE.equals(invoke(serviceMasterMuteRead, audioService))) {
                return GeelyAmpWakeStrategy.MuteState.MUTED;
            }
        }
        return GeelyAmpWakeStrategy.MuteState.UNKNOWN;
    }

    private void resolveProbes() {
        synchronized (lock) {
            if (probesResolved) return;
            probesResolved = true;
        }
        if (audioManager != null) {
            masterMuteRead = findMethod(audioManager.getClass(), "isMasterMute");
            streamMuteRead = findMethod(audioManager.getClass(), "isStreamMute", int.class);
            streamMuteWrite = findMethod(audioManager.getClass(), "setStreamMute", int.class, boolean.class);
        }
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "audio");
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
        Log.i(TAG, "mute probes resolved: masterMute=" + (masterMuteRead != null)
                + " streamMuteRead=" + (streamMuteRead != null)
                + " streamMuteWrite=" + (streamMuteWrite != null)
                + " audioService=" + (audioService != null)
                + " svcStreamMute=" + (serviceStreamMuteRead != null)
                + " svcMasterMute=" + (serviceMasterMuteRead != null));
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        if (type == null) return null;
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        if (method == null || target == null) return null;
        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static GeelyAmpWakeStrategy.MuteState toMuteState(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value) ? GeelyAmpWakeStrategy.MuteState.MUTED
                    : GeelyAmpWakeStrategy.MuteState.UNMUTED;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() > 0 ? GeelyAmpWakeStrategy.MuteState.MUTED
                    : GeelyAmpWakeStrategy.MuteState.UNMUTED;
        }
        return GeelyAmpWakeStrategy.MuteState.UNKNOWN;
    }
}
