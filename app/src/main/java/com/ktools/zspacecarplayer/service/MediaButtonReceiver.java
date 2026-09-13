package com.ktools.zspacecarplayer.service;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

public class MediaButtonReceiver extends BroadcastReceiver {

    private static final String TAG = "MediaButtonReceiver";

    public static final String ACTION_TOGGLE_PAUSE = "com.ktools.zspacecarplayer.ACTION_TOGGLE_PAUSE";
    public static final String ACTION_PLAY = "com.ktools.zspacecarplayer.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.ktools.zspacecarplayer.ACTION_PAUSE";
    public static final String ACTION_NEXT = "com.ktools.zspacecarplayer.ACTION_NEXT";
    public static final String ACTION_PREVIOUS = "com.ktools.zspacecarplayer.ACTION_PREVIOUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }

        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() > 0) {
            return;
        }

        String action = actionForKeyCode(event.getKeyCode());
        if (action != null) {
            // 实车抓真实 keyCode: 车厂静音键可能是自定义键值, 这行能确认走没走媒体键通路
            Log.i(TAG, "Media button keyCode=" + event.getKeyCode()
                    + " scanCode=" + event.getScanCode() + " -> " + action);
            Intent serviceIntent = new Intent(context, AudioPlayerService.class);
            serviceIntent.setAction(action);
            context.startService(serviceIntent);
        }
    }

    /**
     * 车机「静音键」是否应当被本 App 当作播放/暂停键 (2026-09-11 实车 #6)。
     *
     * KEYCODE_VOLUME_MUTE(164) 是 API 19 才加入的公开常量, 这里只是编译期内联的整数,
     * minSdk 18 的车机一样能匹配 (前提是 ROM 真发这个键值); KEYCODE_MUTE(91) 一并覆盖,
     * 部分车厂把整车静音键映射到它。真实 keyCode 需要实车日志确认, 见
     * MainActivity.dispatchKeyEvent 的按键日志。
     */
    @SuppressLint("InlinedApi")
    public static boolean isMuteKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_MUTE;
    }

    /**
     * 媒体键通路上的「暂停切换」判定: 静音键与 MEDIA_PLAY_PAUSE 在本 App 语义一致。
     * 注意前台窗口拦截只用 {@link #isMuteKey(int)} —— MEDIA_PLAY_PAUSE 已由
     * RemoteControlClient/媒体键通路处理, 窗口里再拦一次有与方向盘键双重触发的风险。
     */
    public static boolean isMutePauseKey(int keyCode) {
        return isMuteKey(keyCode) || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    }

    @SuppressLint("InlinedApi")
    static String actionForKeyCode(int keyCode) {
        // 静音键 = 播放/暂停切换 (#6), 语义与 MEDIA_PLAY_PAUSE 完全一致, 优先判定
        if (isMutePauseKey(keyCode)) {
            return ACTION_TOGGLE_PAUSE;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return ACTION_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return ACTION_PAUSE;
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return ACTION_TOGGLE_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return ACTION_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return ACTION_PREVIOUS;
            default:
                return null;
        }
    }
}
