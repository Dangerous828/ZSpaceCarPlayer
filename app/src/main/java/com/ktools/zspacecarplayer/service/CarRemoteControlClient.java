package com.ktools.zspacecarplayer.service;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.RemoteControlClient;
import android.util.Log;

/**
 * 车机 Remote Control 栈状态同步。API 18 无 MediaSession (API 21+), 只能用已废弃的
 * RemoteControlClient。2026-09-04 实车排查发现: dumpsys audio 的 Remote Control stack
 * 里本应用一直停在 PLAYSTATE_STOPPED (从未被更新), 车厂定制固件极可能据此判断"当前是否
 * 有音源在正经播放"从而决定是否把物理功放通道接入这路输出 —— Android 音频框架层
 * (焦点/音量/AudioFlinger/AudioPolicy 路由) 全部健康但物理喇叭无声, 与此矛盾吻合。
 * 必须在每次真实的播放状态变化时同步 setPlaybackState, 让车机侧看到本应用是活跃音源。
 */
final class CarRemoteControlClient {

    private static final String TAG = "CarRemoteControlClient";

    private final RemoteControlClient client;

    private CarRemoteControlClient(RemoteControlClient client) {
        this.client = client;
    }

    /** intentActionName 必须与 MediaButtonReceiver 监听的 ACTION_MEDIA_BUTTON 广播接收方一致。 */
    @SuppressLint("InlinedApi")
    static CarRemoteControlClient register(Context context, AudioManager audioManager,
                                            Class<?> mediaButtonReceiverClass) {
        if (context == null || audioManager == null || mediaButtonReceiverClass == null) return null;
        try {
            ComponentName receiver = new ComponentName(context.getPackageName(),
                    mediaButtonReceiverClass.getName());
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.setComponent(receiver);
            PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(context, 0, mediaButtonIntent, 0);
            RemoteControlClient rcc = new RemoteControlClient(mediaPendingIntent);
            rcc.setTransportControlFlags(
                    RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                            | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                            | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                            | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS
                            | RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                            | RemoteControlClient.FLAG_KEY_MEDIA_STOP);
            audioManager.registerRemoteControlClient(rcc);
            return new CarRemoteControlClient(rcc);
        } catch (RuntimeException e) {
            Log.w(TAG, "RemoteControlClient registration failed", e);
            return null;
        }
    }

    void setPlaying() {
        setState(RemoteControlClient.PLAYSTATE_PLAYING);
    }

    void setPaused() {
        setState(RemoteControlClient.PLAYSTATE_PAUSED);
    }

    void setStopped() {
        setState(RemoteControlClient.PLAYSTATE_STOPPED);
    }

    void setMetadata(String title, String artist, String album, long durationMs) {
        if (client == null) return;
        try {
            RemoteControlClient.MetadataEditor editor = client.editMetadata(true);
            editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, title);
            editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST, artist);
            editor.putString(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM, album);
            if (durationMs > 0) {
                editor.putLong(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, durationMs);
            }
            editor.apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "RemoteControlClient metadata update failed", e);
        }
    }

    private void setState(int playState) {
        if (client == null) return;
        try {
            client.setPlaybackState(playState);
        } catch (RuntimeException e) {
            Log.w(TAG, "RemoteControlClient state update failed", e);
        }
    }

    void unregister(AudioManager audioManager) {
        if (client == null || audioManager == null) return;
        try {
            audioManager.unregisterRemoteControlClient(client);
        } catch (RuntimeException e) {
            Log.w(TAG, "RemoteControlClient unregister failed", e);
        }
    }
}
