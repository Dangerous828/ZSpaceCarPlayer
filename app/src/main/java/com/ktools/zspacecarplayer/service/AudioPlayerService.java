package com.ktools.zspacecarplayer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.SongItem;
import com.ktools.zspacecarplayer.ui.MainActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AudioPlayerService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

    private static final String TAG = "AudioPlayerService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "car_player_channel";

    public static final int MODE_SEQUENCE = 0;
    public static final int MODE_SINGLE_REPEAT = 1;
    public static final int MODE_RANDOM = 2;

    private MediaPlayer mediaPlayer;
    private List<SongItem> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private int currentPlayMode = MODE_SEQUENCE;

    private boolean isPrepared = false;
    private Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;

    private AudioManager audioManager;
    private boolean pausedByTransientFocusLoss = false;

    private OnPlayerStateChangeListener stateChangeListener;
    private final IBinder binder = new LocalBinder();

    public interface OnPlayerStateChangeListener {
        void onSongChanged(SongItem song, int index);
        void onPlayStateChanged(boolean isPlaying);
        void onProgressUpdate(int currentMs, int totalMs);
        void onError(String message);
    }

    public class LocalBinder extends Binder {
        public AudioPlayerService getService() {
            return AudioPlayerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initMediaPlayer();
        initProgressTracker();
        startForegroundServiceNotification("ZSpace Car Player 运行中", "准备播放");
    }

    private void initMediaPlayer() {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.setOnErrorListener(this);
        }
    }

    private void initProgressTracker() {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPrepared && isPlaying()) {
                    int currentMs = mediaPlayer.getCurrentPosition();
                    int totalMs = mediaPlayer.getDuration();
                    if (stateChangeListener != null) {
                        stateChangeListener.onProgressUpdate(currentMs, totalMs);
                    }
                }
                progressHandler.postDelayed(this, 500);
            }
        };
        progressHandler.post(progressRunnable);
    }

    private void startForegroundServiceNotification(String title, String content) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "ZSpace Car Player", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    public void setPlaylist(List<SongItem> songs, int startIndex) {
        this.playlist = new ArrayList<>(songs);
        this.currentIndex = startIndex;
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            playSong(playlist.get(currentIndex));
        }
    }

    public List<SongItem> getPlaylist() {
        return playlist;
    }

    public void playSong(SongItem song) {
        if (song == null || song.getStreamUrl() == null) return;
        try {
            isPrepared = false;
            requestAudioFocus();
            mediaPlayer.reset();
            mediaPlayer.setDataSource(song.getStreamUrl());
            mediaPlayer.prepareAsync();

            startForegroundServiceNotification("正在播放", song.getName() + " - " + song.getArtist());
            if (stateChangeListener != null) {
                stateChangeListener.onSongChanged(song, currentIndex);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing song", e);
            if (stateChangeListener != null) {
                stateChangeListener.onError("无法加载音频流: " + e.getMessage());
            }
        }
    }

    public void playOrPause() {
        if (mediaPlayer == null || !isPrepared) return;
        try {
            if (mediaPlayer.isPlaying()) {
                pause();
            } else {
                resume();
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "playOrPause in illegal state", e);
        }
    }

    public void pause() {
        if (mediaPlayer != null && isPlaying()) {
            mediaPlayer.pause();
            if (stateChangeListener != null) {
                stateChangeListener.onPlayStateChanged(false);
            }
        }
    }

    public void resume() {
        if (mediaPlayer != null && isPrepared && !isPlaying()) {
            requestAudioFocus();
            mediaPlayer.start();
            if (stateChangeListener != null) {
                stateChangeListener.onPlayStateChanged(true);
            }
        }
    }

    public void playNext() {
        if (playlist.isEmpty()) return;
        if (currentPlayMode == MODE_RANDOM) {
            currentIndex = new Random().nextInt(playlist.size());
        } else {
            currentIndex = (currentIndex + 1) % playlist.size();
        }
        playSong(playlist.get(currentIndex));
    }

    public void playPrevious() {
        if (playlist.isEmpty()) return;
        if (currentPlayMode == MODE_RANDOM) {
            currentIndex = new Random().nextInt(playlist.size());
        } else {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
        }
        playSong(playlist.get(currentIndex));
    }

    public void seekTo(int ms) {
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer.seekTo(ms);
        }
    }

    public boolean isPlaying() {
        try {
            return mediaPlayer != null && mediaPlayer.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public SongItem getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getPlayMode() {
        return currentPlayMode;
    }

    public void setPlayMode(int mode) {
        this.currentPlayMode = mode;
    }

    public void setOnPlayerStateChangeListener(OnPlayerStateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    // ---------------- 音频焦点避让 (高德导航播报自动降音) ----------------

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (mediaPlayer != null && isPrepared) {
                        mediaPlayer.setVolume(1.0f, 1.0f);
                    }
                    if (pausedByTransientFocusLoss) {
                        pausedByTransientFocusLoss = false;
                        resume();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    pausedByTransientFocusLoss = false;
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    pausedByTransientFocusLoss = isPlaying();
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // 导航播报: 降至 20% 音量, 播报结束 AUDIOFOCUS_GAIN 自动恢复
                    if (mediaPlayer != null && isPrepared) {
                        mediaPlayer.setVolume(0.2f, 0.2f);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(focusChangeListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    // ---------------- MediaPlayer 回调 ----------------

    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        mp.start();
        if (stateChangeListener != null) {
            stateChangeListener.onPlayStateChanged(true);
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (currentPlayMode == MODE_SINGLE_REPEAT) {
            playSong(getCurrentSong());
        } else {
            playNext();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
        isPrepared = false;
        if (stateChangeListener != null) {
            stateChangeListener.onError("播放出错(Code " + what + ")");
        }
        return true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        progressHandler.removeCallbacks(progressRunnable);
        if (audioManager != null) {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }
}
