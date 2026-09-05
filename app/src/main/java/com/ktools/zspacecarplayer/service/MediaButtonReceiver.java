package com.ktools.zspacecarplayer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public class MediaButtonReceiver extends BroadcastReceiver {

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
            Intent serviceIntent = new Intent(context, AudioPlayerService.class);
            serviceIntent.setAction(action);
            context.startService(serviceIntent);
        }
    }

    static String actionForKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return ACTION_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return ACTION_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
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
