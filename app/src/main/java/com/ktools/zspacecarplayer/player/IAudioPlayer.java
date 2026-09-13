package com.ktools.zspacecarplayer.player;

public interface IAudioPlayer {
    interface OnEventListener {
        void onPrepared(int durationMs);
        void onCompletion();
        void onError(int what, String extra);
        void onSeekComplete();

        /**
         * 缓冲进度上报（2026-09-12 缓冲/预取）。
         *
         * @param percent   已缓冲百分比；-1 表示总长未知（UI 显示「缓冲中…」）
         * @param buffering true = 仍在缓冲（起播门槛 / 播放早期领先量不足）；false = 已就绪 / 稳定
         */
        void onBufferingUpdate(int percent, boolean buffering);
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

    /** 系统 audiofx 挂接用的会话 id；v3 自研 DSP 引擎不经过系统音效链，返回 0 */
    int getAudioSessionId();

    /** 全局 aux 混响挂接（系统引擎用）；v3 DSP 引擎自带软件混响，此处 no-op */
    void attachAuxEffect(int effectId);

    void setAuxEffectSendLevel(float level);
}
