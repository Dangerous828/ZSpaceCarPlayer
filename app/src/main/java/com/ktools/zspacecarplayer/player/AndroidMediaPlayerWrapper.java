package com.ktools.zspacecarplayer.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

public class AndroidMediaPlayerWrapper implements IAudioPlayer {
    private static final String TAG = "AndroidMediaPlayer";

    private MediaPlayer mediaPlayer;
    private OnEventListener eventListener;
    private float leftVol = 1.0f;
    private float rightVol = 1.0f;

    public AndroidMediaPlayerWrapper() {
        initMediaPlayer();
    }

    private void initMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (eventListener != null) {
                    eventListener.onPrepared(mp.getDuration());
                }
            }
        });

        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (eventListener != null) {
                    eventListener.onCompletion();
                }
            }
        });

        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (eventListener != null) {
                    eventListener.onError(what, "MediaPlayer error extra=" + extra);
                }
                return true;
            }
        });

        mediaPlayer.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
            @Override
            public void onSeekComplete(MediaPlayer mp) {
                if (eventListener != null) {
                    eventListener.onSeekComplete();
                }
            }
        });
    }

    public int getAudioSessionId() {
        return mediaPlayer != null ? mediaPlayer.getAudioSessionId() : 0;
    }

    public void attachAuxEffect(int effectId) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.attachAuxEffect(effectId);
            } catch (Exception e) {
                Log.w(TAG, "attachAuxEffect failed", e);
            }
        }
    }

    public void setAuxEffectSendLevel(float level) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setAuxEffectSendLevel(level);
            } catch (Exception e) {
                Log.w(TAG, "setAuxEffectSendLevel failed", e);
            }
        }
    }

    @Override
    public void setDataSource(String pathOrUrl) throws Exception {
        if (mediaPlayer == null) {
            initMediaPlayer();
        }
        mediaPlayer.reset();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setDataSource(pathOrUrl);
    }

    @Override
    public void prepareAsync() {
        if (mediaPlayer != null) {
            mediaPlayer.prepareAsync();
        }
    }

    @Override
    public void start() {
        if (mediaPlayer != null) {
            mediaPlayer.start();
        }
    }

    @Override
    public void pause() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
        }
    }

    @Override
    public void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
        }
    }

    @Override
    public void seekTo(int msec) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(msec);
        }
    }

    @Override
    public void release() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    @Override
    public void reset() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    @Override
    public int getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
    }

    @Override
    public int getDuration() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        this.leftVol = leftVolume;
        this.rightVol = rightVolume;
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(leftVolume, rightVolume);
        }
    }

    @Override
    public void setOnEventListener(OnEventListener listener) {
        this.eventListener = listener;
    }
}
