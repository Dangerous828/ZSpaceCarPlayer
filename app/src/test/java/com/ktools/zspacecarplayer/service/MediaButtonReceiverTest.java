package com.ktools.zspacecarplayer.service;

import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Test;

public class MediaButtonReceiverTest {

    @Test
    public void playAndPauseKeysMapToIdempotentActions() {
        Assert.assertEquals(MediaButtonReceiver.ACTION_PLAY,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY));
        Assert.assertEquals(MediaButtonReceiver.ACTION_PAUSE,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    @Test
    public void toggleKeysRemainToggleActions() {
        Assert.assertEquals(MediaButtonReceiver.ACTION_TOGGLE_PAUSE,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        Assert.assertEquals(MediaButtonReceiver.ACTION_TOGGLE_PAUSE,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_HEADSETHOOK));
    }

    @Test
    public void unsupportedKeyDoesNotStartPlaybackServiceAction() {
        Assert.assertNull(MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void muteKeysTogglePlayback() {
        // 车机静音键 = 暂停/播放 (#6)
        Assert.assertEquals(MediaButtonReceiver.ACTION_TOGGLE_PAUSE,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_VOLUME_MUTE));
        Assert.assertEquals(MediaButtonReceiver.ACTION_TOGGLE_PAUSE,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_MUTE));
        Assert.assertTrue(MediaButtonReceiver.isMuteKey(KeyEvent.KEYCODE_VOLUME_MUTE));
        // 前台窗口只拦静音键: PLAY_PAUSE 归媒体键通路, 音量键必须放行给系统
        Assert.assertFalse(MediaButtonReceiver.isMuteKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        Assert.assertFalse(MediaButtonReceiver.isMuteKey(KeyEvent.KEYCODE_VOLUME_UP));
        Assert.assertFalse(MediaButtonReceiver.isMuteKey(KeyEvent.KEYCODE_VOLUME_DOWN));
    }
}
