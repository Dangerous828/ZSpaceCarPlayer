package com.ktools.zspacecarplayer.player;

public interface IAudioPlayer {
    interface OnEventListener {
        void onPrepared(int durationMs);
        void onCompletion();
        void onError(int what, String extra);
        void onSeekComplete();
    }

    void setDataSource(String pathOrUrl) throws Exception;
    void prepareAsync();
    void start();
    void pause();
    void stop();
    void seekTo(int msec);
    void release();
    void reset();

    boolean isPlaying();
    int getCurrentPosition();
    int getDuration();
    void setVolume(float leftVolume, float rightVolume);
    void setOnEventListener(OnEventListener listener);
}
