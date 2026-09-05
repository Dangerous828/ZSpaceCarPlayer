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
}
