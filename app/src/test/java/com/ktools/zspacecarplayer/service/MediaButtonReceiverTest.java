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

    /**
     * 车机左右键也要能切歌 (2026-10-21 车主反馈「点左键打开了 QQ 音乐」)。
     *
     * 车企常把方向键原样发出而不映射成 MEDIA_NEXT/PREVIOUS。本应用不认的话，
     * 一旦媒体键接收方被后台播放器刷走，这颗键就整个交给对方。
     */
    @Test
    public void dpadLeftRightMapToSkipActions() {
        Assert.assertEquals(MediaButtonReceiver.ACTION_NEXT,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT));
        Assert.assertEquals(MediaButtonReceiver.ACTION_PREVIOUS,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_DPAD_LEFT));
        // 上报里实车下一首发的是 87，两条路都要通
        Assert.assertEquals(MediaButtonReceiver.ACTION_NEXT,
                MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT));
        // 上下键与确认键不得被当成切歌，否则列表导航会被吃掉
        Assert.assertNull(MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_DPAD_UP));
        Assert.assertNull(MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_DPAD_DOWN));
        Assert.assertNull(MediaButtonReceiver.actionForKeyCode(KeyEvent.KEYCODE_DPAD_CENTER));
    }
}
