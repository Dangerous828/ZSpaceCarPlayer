package com.ktools.zspacecarplayer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.EnvironmentalReverb;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.crash.CrashMonitor;
import com.ktools.zspacecarplayer.db.SongDao;
import com.ktools.zspacecarplayer.net.JellyfinApiClient;
import com.ktools.zspacecarplayer.player.DspAudioTrackPlayer;
import com.ktools.zspacecarplayer.player.AndroidMediaPlayerWrapper;
import com.ktools.zspacecarplayer.player.IAudioPlayer;
import com.ktools.zspacecarplayer.player.stream.HttpProxyServer;
import com.ktools.zspacecarplayer.model.SongItem;
import com.ktools.zspacecarplayer.ui.MainActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AudioPlayerService extends Service {

    private static final String TAG = "AudioPlayerService";
    public static final String ACTION_STOP_AND_RELEASE = "com.ktools.zspacecarplayer.ACTION_STOP_AND_RELEASE";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "car_player_channel";
    private static final long FADE_OUT_MS = 80L;
    private static final long FADE_IN_MS = 200L;
    private static final long SEEK_TIMEOUT_MS = 2000L;

    public static final int MODE_SEQUENCE = 0;
    public static final int MODE_SINGLE_REPEAT = 1;
    public static final int MODE_RANDOM = 2;

    /** 播放引擎偏好: v3 自研 DSP 引擎 (软解 + NativeDsp + 裸 AudioTrack) */
    public static final String PREF_NAME = "zspace_car_player";
    public static final String PREF_KEY_ENGINE_V3 = "play_engine_v3";
    private static final boolean DEFAULT_ENGINE_V3 = false; // 默认系统引擎, 车机一次只变一个变量

    /** 播放器引擎抽象: 系统 MediaPlayer (兼容模式) / v3 自研 DSP 管线 */
    private IAudioPlayer player;
    /** 当前 player 是否为 v3 引擎 (与偏好可能短暂不一致, 切歌时惰性对齐) */
    private boolean playerIsV3 = false;
    private List<SongItem> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private int currentPlayMode = MODE_SEQUENCE;

    private boolean everPrepared = false;
    private int pendingSeekMs = -1;
    private Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private Runnable seekTimeoutRunnable;
    private long activeSeekOperationId = -1L;
    private long queuedSeekOperationId = -1L;
    private int queuedSeekMs = -1;
    private final PlaybackStateMachine playbackState = new PlaybackStateMachine();
    private GainEnvelope gainEnvelope;
    private GeelyAmpWakeStrategy ampWakeStrategy;
    private long lastCountedGeneration = -1L;

    /** Token 未就绪时挂起的待播曲目, 鉴权成功回调后自动起播 */
    private SongItem pendingAuthSong;
    private int pendingAuthSeekMs = -1;
    private long pendingAuthGeneration = -1L;
    private boolean authWaitInProgress = false;
    /** MEDIA_ERROR_IO 后的原曲重试计数, 起播成功 (onPrepared) 后归零 */
    private int streamRetryCount = 0;
    /** 同一轮起播未成功期间的重复错误计数, 用于抑制 Toast 刷屏; 与 streamRetryCount 同时归零 */
    private int silentErrorStreak = 0;
    /** 流卡死看门狗: isPlaying 但位置连续 10 秒零位移则从断点重启当前曲目 */
    private int lastTickPositionMs = -1;
    /** lastTickPositionMs 属于哪首歌, 防止把上一首的断点带到新曲目上 */
    private String lastTickTrackId;
    private int stallTicks = 0;
    private boolean stallRecovering = false;
    /** 假播放看门狗: 用户意图在播但播放器已死 (重试链耗尽/起播挂死) 时指数退避自动续播 */
    private int preparingTicks = 0;
    private int deadTicks = 0;
    private int deadRetryTicks = DEAD_RETRY_TICKS_START;
    /** 自动恢复退避: 500ms/tick, 6s 起步翻倍, 60s 封顶 (地库/隧道断网后网络恢复即续播) */
    private static final int DEAD_RETRY_TICKS_START = 12;
    private static final int DEAD_RETRY_TICKS_MAX = 120;

    private AudioManager audioManager;
    /** 车机 Remote Control 栈状态同步, 见 CarRemoteControlClient 类注释 */
    private CarRemoteControlClient remoteControlClient;

    /** 失焦暂停 (语音播报/被抢焦点) 真正完成的时间点; >=0 表示存在待消费的失焦暂停记录 */
    private long transientPausedAtMs = -1L;
    /** 已排程的功放唤醒重试所属 episode key, -1 表示无排程 */
    private long scheduledAmpRetryKey = -1L;

    /**
     * 二分排查开关: false = 完全旁路本轮新增的全景/混响代码。
     * 2026-09-03 实车部署确认无声根因: 车机 Neusoft Virtualizer 驱动在 setEnabled(false)
     * (bypass 直通) 时未把输入 PCM 复制到输出, 直接吐静音, 而它是效果链末端直连主 mix
     * 输出 —— 即使全景/混响 UI 显示"关", 只要 new Virtualizer() 挂载过就会切断声音。
     * 关闭前旧版 (无此代码) 实车确认有声, 关闭后不再挂载该效果, 音频链路与旧版等价。
     */
    private static final boolean ENABLE_PANORAMA_REVERB = true;

    private Equalizer equalizer;
    private BassBoost bassBoost;
    private Virtualizer virtualizer;
    /** 空间混响是全局 aux 效果 (session 0), 生命周期跨曲目, 只在 onDestroy 释放 */
    private EnvironmentalReverb environmentalReverb;
    private int currentAudioSessionId = -1;
    private short currentPresetIndex = -1;
    private int currentBassPercent = 0;
    private int currentVirtualizerPercent = 0;
    /** 0=关 1=房间 2=音乐厅 3=影院 */
    private int currentReverbMode = 0;

    /** 音效设置持久化 key (v3 引擎每次 prepare 重建 DSP 引擎, 必须重放用户设置) */
    private static final String DSP_PREF_KEY_EQ = "dsp_eq_preset";
    private static final String DSP_PREF_KEY_BASS = "dsp_bass_percent";
    private static final String DSP_PREF_KEY_VIRTUALIZER = "dsp_virtualizer_percent";
    private static final String DSP_PREF_KEY_REVERB = "dsp_reverb_mode";

    /** 把当前音效设置下发到 v3 DSP 引擎 (prepare 后调用, 覆盖 init 造成的归零) */
    private void applyDspParamsToNative() {
        com.ktools.zspacecarplayer.dsp.NativeDsp.setEqualizerPreset(currentPresetIndex);
        com.ktools.zspacecarplayer.dsp.NativeDsp.setBassBoost(currentBassPercent);
        com.ktools.zspacecarplayer.dsp.NativeDsp.setVirtualizer(currentVirtualizerPercent);
        com.ktools.zspacecarplayer.dsp.NativeDsp.setReverb(currentReverbMode);
        Log.i(TAG, "DSP params applied: eq=" + currentPresetIndex
                + " bass=" + currentBassPercent + "% virt=" + currentVirtualizerPercent
                + "% reverb=" + currentReverbMode);
    }

    private void loadDspParamsFromPrefs() {
        android.content.SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int eq = sp.getInt(DSP_PREF_KEY_EQ, -1);
        currentPresetIndex = (short) eq;
        currentBassPercent = sp.getInt(DSP_PREF_KEY_BASS, 0);
        currentVirtualizerPercent = sp.getInt(DSP_PREF_KEY_VIRTUALIZER, 0);
        currentReverbMode = sp.getInt(DSP_PREF_KEY_REVERB, 0);
    }

    private void saveDspParamsToPrefs() {
        android.content.SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        sp.edit()
                .putInt(DSP_PREF_KEY_EQ, currentPresetIndex)
                .putInt(DSP_PREF_KEY_BASS, currentBassPercent)
                .putInt(DSP_PREF_KEY_VIRTUALIZER, currentVirtualizerPercent)
                .putInt(DSP_PREF_KEY_REVERB, currentReverbMode)
                .apply();
    }

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
        loadDspParamsFromPrefs();
        // 立刻把持久化的音效参数灌进 NativeDsp 的静态缓存。此时 native 引擎还没建
        // （setter 内部有空判，是安全 no-op），引擎 init 时会重放这份缓存；若只在
        // handlePrepared 里下发，崩溃重启后首次 prepare 一旦卡住，音效就永远停在关闭态
        applyDspParamsToNative();
        initDspSafetyComponents();
        initProgressTracker();
        registerMediaButton();
        remoteControlClient = CarRemoteControlClient.register(this, audioManager, MediaButtonReceiver.class);
        registerAuthStateListener();
        startForegroundServiceNotification("ZSpace Car Player 运行中", "准备播放");
    }

    /**
     * 鉴权完成 (成功/失败) 时检查是否有挂起的待播曲目:
     * 例如冷启动后 SQLite 秒显列表, 用户在 autoSilentLogin 完成前点击了歌曲。
     */
    private void registerAuthStateListener() {
        JellyfinApiClient.getInstance().setOnAuthStateListener(new JellyfinApiClient.OnAuthStateListener() {
            @Override
            public void onAuthStateChanged(boolean success) {
                if (pendingAuthSong == null) return;
                final long generation = pendingAuthGeneration;
                if (!playbackState.isCurrentGeneration(generation)) return;
                SongItem song = pendingAuthSong;
                int startMs = pendingAuthSeekMs;
                pendingAuthSong = null;
                pendingAuthSeekMs = -1;
                pendingAuthGeneration = -1L;
                authWaitInProgress = false;
                if (success) {
                    replayPendingSong(song, startMs,
                            PlaybackStateMachine.PlaybackOrigin.AUTH_RECOVERY);
                } else {
                    playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.STOP);
                    playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.ERROR);
                    pendingSeekMs = -1;
                    if (stateChangeListener != null) {
                        stateChangeListener.onError("鉴权未完成, 已取消播放 " + song.getName());
                    }
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_STOP_AND_RELEASE.equals(action)) {
                Log.i(TAG, "onStartCommand: ACTION_STOP_AND_RELEASE received, releasing all audio resources");
                stopAndReleaseAllAudioResources();
                return START_NOT_STICKY;
            } else if (MediaButtonReceiver.ACTION_TOGGLE_PAUSE.equals(action)) {
                playOrPause(PlaybackStateMachine.PlaybackOrigin.MEDIA_BUTTON);
            } else if (MediaButtonReceiver.ACTION_PLAY.equals(action)) {
                play(PlaybackStateMachine.PlaybackOrigin.MEDIA_BUTTON);
            } else if (MediaButtonReceiver.ACTION_PAUSE.equals(action)) {
                pause();
            } else if (MediaButtonReceiver.ACTION_NEXT.equals(action)) {
                playNext(PlaybackStateMachine.PlaybackOrigin.MEDIA_BUTTON);
            } else if (MediaButtonReceiver.ACTION_PREVIOUS.equals(action)) {
                playPrevious(PlaybackStateMachine.PlaybackOrigin.MEDIA_BUTTON);
            }
        }
        return START_STICKY;
    }

    private void registerMediaButton() {
        if (audioManager != null) {
            ComponentName mbComponent = new ComponentName(getPackageName(), MediaButtonReceiver.class.getName());
            audioManager.registerMediaButtonEventReceiver(mbComponent);
        }
    }

    /**
     * RCC 状态同步后立即把 media button receiver 重新压回栈顶。
     *
     * Android 4.3 无 MediaSession，方向盘物理键派发给 Remote Control 栈顶——而
     * 第三方音乐 app (QQ音乐) 在后台活跃时其 RCC 会周期性刷新压过本应用，导致
     * 用户按「下一首」反而拉起 QQ 音乐 (2026-09-08 实车复现)。注册即成为当前
     * receiver (LIFO 栈)，因此在每次本应用播放状态变化的同一时机重注册抢占。
     */
    private void reassertMediaButton() {
        registerMediaButton();
    }

    /**
     * 是否启用 v3 自研 DSP 引擎 (设置页可切换)。
     *
     * 偏好为真还要再过一道崩溃熔断：v3 连续崩过阈值次数后强制走系统引擎，
     * 直到用户在设置页显式重开（见 {@link com.ktools.zspacecarplayer.crash.NativeEngineGuard}）。
     */
    public boolean isV3EngineEnabled() {
        if (!getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getBoolean(PREF_KEY_ENGINE_V3, DEFAULT_ENGINE_V3)) {
            return false;
        }
        if (CrashMonitor.isEngineAutoDisabled()) {
            Log.w(TAG, "v3 engine preferred but circuit-broken by crash guard, using system MediaPlayer");
            return false;
        }
        return true;
    }

    /** 确保当前引擎与偏好一致; 不一致 (或未创建) 时重建。在每次起播前调用。 */
    private void ensureEngine(final long generation) {
        boolean wantV3 = isV3EngineEnabled();
        if (player != null && playerIsV3 == wantV3) {
            return;
        }
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
            playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.IDLE);
            Log.i(TAG, "Switched engine, old player released (v3=" + playerIsV3 + ")");
        }
        player = wantV3 ? new DspAudioTrackPlayer() : new AndroidMediaPlayerWrapper();
        playerIsV3 = wantV3;
        Log.i(TAG, "Player engine: " + (wantV3 ? "v3 native DSP (AudioTrack)" : "system MediaPlayer"));
        CrashMonitor.putContext("engine", wantV3 ? "v3" : "system");
        CrashMonitor.breadcrumb("engine", wantV3 ? "v3 native DSP" : "system MediaPlayer");
        // 崩溃归因：进程若在此后死掉，下次启动靠这条标记判断当时是不是在用原生引擎
        CrashMonitor.markV3EngineActive(wantV3);
    }

    private void initMediaPlayer() {
        ensureEngine(playbackState.getGenerationId());
    }

    private void initDspSafetyComponents() {
        gainEnvelope = new GainEnvelope(new GainEnvelope.VolumeTarget() {
            @Override
            public void setVolume(float gain) {
                if (player == null) return;
                try {
                    player.setVolume(gain, gain);
                } catch (IllegalStateException ignored) {
                }
            }
        }, progressHandler);
        if (audioManager != null) {
            ampWakeStrategy = new GeelyAmpWakeStrategy(audioManager);
        }
    }

    private void initProgressTracker() {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (player != null && playbackState.isPrepared() && isPlaying()) {
                    int currentMs = player.getCurrentPosition();
                    int totalMs = player.getDuration();

                    // 卡死检测: 进度零位移累计 10 秒 (公网断流时 NuPlayer 保持 playing
                    // 状态但无数据, AudioFlinger standby 无声), 从断点重启当前曲目
                    if (currentMs == lastTickPositionMs && totalMs > 0 && currentMs < totalMs) {
                        stallTicks++;
                        if (stallTicks >= 20 && !stallRecovering) {
                            recoverStalledStream(currentMs, playbackState.getGenerationId());
                        }
                    } else {
                        stallTicks = 0;
                        lastTickPositionMs = currentMs;
                        SongItem ticking = getCurrentSong();
                        lastTickTrackId = ticking != null ? ticking.getId() : null;
                    }

                    preparingTicks = 0;
                    deadTicks = 0;
                    deadRetryTicks = DEAD_RETRY_TICKS_START;

                    if (stateChangeListener != null) {
                        stateChangeListener.onProgressUpdate(currentMs, totalMs);
                    }
                } else if (watchdogAction() == PlaybackStateMachine.WatchdogAction.REBUILD_STREAM
                        && !playbackState.isPreparing()) {
                    // 假播放兜底: onError 重试链耗尽或长时间无网络后播放器已死,
                    // UI 仍停留在「正在播放」(无声/进度冻结/歌词不动)。
                    // 指数退避自动重启当前曲目, 网络恢复后续播; 鉴权挂起时交给 auth 流程
                    preparingTicks = 0;
                    deadTicks++;
                    if (deadTicks >= deadRetryTicks
                            && pendingAuthSong == null && !authWaitInProgress) {
                        deadTicks = 0;
                        deadRetryTicks = Math.min(deadRetryTicks * 2, DEAD_RETRY_TICKS_MAX);
                        autoResumeFromLastPosition(playbackState.getGenerationId());
                    }
                } else if (watchdogAction() == PlaybackStateMachine.WatchdogAction.REBUILD_STREAM) {
                    // prepareAsync 进行中: 超过 30 秒未回调视为挂死 (弱网 TCP 黑洞),
                    // 强制从断点重建连接
                    preparingTicks++;
                    deadTicks = 0;
                    if (preparingTicks >= 60) {
                        preparingTicks = 0;
                        autoResumeFromLastPosition(playbackState.getGenerationId());
                    }
                } else if (watchdogAction() == PlaybackStateMachine.WatchdogAction.RETRY_FOCUS) {
                    // 焦点被拒: 不重建串流也不提示用户, 只按退避节奏重新申请焦点。
                    // 断点锚点必须保留, 拿到焦点后要从原位置续播。
                    preparingTicks = 0;
                    deadTicks++;
                    if (deadTicks >= deadRetryTicks) {
                        deadTicks = 0;
                        deadRetryTicks = Math.min(deadRetryTicks * 2, DEAD_RETRY_TICKS_MAX);
                        retryAudioFocusForAutomaticPlayback();
                    }
                } else {
                    stallTicks = 0;
                    lastTickPositionMs = -1;
                    lastTickTrackId = null;
                    preparingTicks = 0;
                    deadTicks = 0;
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
        setPlaylist(songs, startIndex, -1,
                PlaybackStateMachine.playbackOriginForPlaylistStart(true));
    }

    public void setPlaylist(List<SongItem> songs, int startIndex, int startMs) {
        setPlaylist(songs, startIndex, startMs,
                PlaybackStateMachine.playbackOriginForPlaylistStart(true));
    }

    public void setPlaylist(List<SongItem> songs, int startIndex, int startMs,
                            PlaybackStateMachine.PlaybackOrigin origin) {
        this.playlist = new ArrayList<>(songs);
        this.currentIndex = startIndex;
        CrashMonitor.putContext("playlistSize", playlist.size());
        CrashMonitor.putContext("playlistIndex", startIndex);
        CrashMonitor.breadcrumb("play", "setPlaylist size=" + playlist.size()
                + " index=" + startIndex + " startMs=" + startMs);
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaybackStateMachine.PlaybackOrigin safeOrigin = origin == null
                    ? PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME : origin;
            playSong(playlist.get(currentIndex), startMs, safeOrigin);
        }
    }

    /**
     * 媒体库刷新后同步播放列表，保持正在播放的曲目与 index 不会被破坏
     */
    public void updatePlaylist(List<SongItem> newSongs) {
        if (newSongs == null) return;
        SongItem current = getCurrentSong();
        this.playlist = new ArrayList<>(newSongs);
        if (current != null) {
            this.currentIndex = playlist.indexOf(current);
        } else {
            this.currentIndex = -1;
        }
    }

    public List<SongItem> getPlaylist() {
        return playlist;
    }

    public void playSong(SongItem song, int startMs) {
        playSong(song, startMs, PlaybackStateMachine.PlaybackOrigin.USER_UI);
    }

    public void playSong(SongItem song) {
        playSong(song, -1, PlaybackStateMachine.PlaybackOrigin.USER_UI);
    }

    private void playSong(SongItem song, int startMs,
                          PlaybackStateMachine.PlaybackOrigin origin) {
        streamRetryCount = 0;
        silentErrorStreak = 0;
        playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        startPlaybackWithSeek(song, startMs, origin);
    }

    private void replayPendingSong(SongItem song, int startMs,
                                   PlaybackStateMachine.PlaybackOrigin origin) {
        startPlaybackWithSeek(song, startMs, origin);
    }

    private void startPlaybackWithSeek(SongItem song, int startMs,
                                       PlaybackStateMachine.PlaybackOrigin origin) {
        pendingSeekMs = startMs;
        startPlayback(song, origin);
    }

    /** Starts a new generation and performs fade -> pause -> reset -> prepareAsync. */
    private void startPlayback(final SongItem song,
                               final PlaybackStateMachine.PlaybackOrigin origin) {
        if (song == null) {
            pendingSeekMs = -1;
            return;
        }

        final boolean fadeExistingPlayer = PlaybackStateMachine.shouldFadeBeforeReset(
                playbackState.getEngineState(), gainEnvelope.getCurrentGain());
        final long generation = playbackState.beginGeneration(
                PlaybackStateMachine.EngineState.PREPARING, origin);
        CrashMonitor.putContext("songId", song.getId());
        CrashMonitor.putContext("songTitle", song.getName());
        CrashMonitor.putContext("generation", generation);
        CrashMonitor.putContext("origin", String.valueOf(origin));
        CrashMonitor.breadcrumb("play", "startPlayback " + song.getName()
                + " id=" + song.getId() + " seek=" + pendingSeekMs
                + " origin=" + origin + " fade=" + fadeExistingPlayer);
        cancelSeekTimeout();
        activeSeekOperationId = -1L;
        queuedSeekOperationId = -1L;
        queuedSeekMs = -1;
        transientPausedAtMs = -1L;

        JellyfinApiClient client = JellyfinApiClient.getInstance();
        if (!client.hasToken()) {
            client.restoreAuthFromPrefs(this);
            if (!client.hasToken()) {
                waitForAuthThenPlay(song, generation);
                return;
            }
        }

        pendingAuthSong = null;
        pendingAuthSeekMs = -1;
        pendingAuthGeneration = -1L;

        final Runnable prepareTransition = new Runnable() {
            @Override
            public void run() {
                if (!playbackState.isCurrentGeneration(generation)) return;
                try {
                    ensureEngine(generation); // 引擎偏好有变化时在此惰性重建
                    if (player == null) return;
                    if (isPlaying()) {
                        player.pause();
                    }
                    // reset 在主线程执行且内部要等渲染线程退出，是「卡死」的高危窗口。
                    // 主线程看门狗抓到的报告停在这一条上，就能断定是拆机阻塞而非 UI 自身问题。
                    CrashMonitor.breadcrumb("play", "reset begin");
                    player.reset();
                    CrashMonitor.breadcrumb("play", "reset done");
                    bindPlayerCallbacks(generation);
                    gainEnvelope.setImmediate(0.0f);
                    // 双引擎均支持原码率直传无损 (static=true)：v3 经原生 dr_* 软解出 PCM 送 NativeDsp，系统引擎走系统 MediaPlayer
                    String urlToPlay = client.getStreamUrl(song.getId());
                    CrashMonitor.putContext("streamUrl", urlToPlay);
                    // v3: 本地回环代理 + 环形缓冲，抵御公网串流抖动（消除“播 2s 停 1s”式 underrun）
                    player.setDataSource(HttpProxyServer.getInstance().getProxyUrl(urlToPlay));
                    playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.PREPARING);
                    CrashMonitor.breadcrumb("play", "prepareAsync");
                    player.prepareAsync();
                    startForegroundServiceNotification("正在播放", song.getName() + " - " + song.getArtist());
                    if (remoteControlClient != null) {
                        remoteControlClient.setMetadata(song.getName(), song.getArtist(),
                                song.getAlbum(), song.getDurationMs());
                    }
                    if (stateChangeListener != null) {
                        stateChangeListener.onSongChanged(song, currentIndex);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error playing song", e);
                    CrashMonitor.breadcrumb("play", "startPlayback failed: " + e);
                    playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.ERROR);
                    pendingSeekMs = -1;
                    if (stateChangeListener != null) {
                        stateChangeListener.onError("无法加载音频流: " + e.getMessage());
                    }
                }
            }
        };

        requestAudioFocus();
        if (fadeExistingPlayer) {
            gainEnvelope.fadeTo(0.0f, FADE_OUT_MS, prepareTransition);
        } else {
            gainEnvelope.setImmediate(0.0f);
            prepareTransition.run();
        }
    }

    private void bindPlayerCallbacks(final long generation) {
        if (player == null) return;
        player.setOnEventListener(new IAudioPlayer.OnEventListener() {
            @Override
            public void onPrepared(int durationMs) {
                handlePrepared(durationMs, generation);
            }

            @Override
            public void onCompletion() {
                if (!playbackState.isCurrentGeneration(generation)) return;
                playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.READY);
                if (currentPlayMode == MODE_SINGLE_REPEAT) {
                    startPlaybackWithSeek(getCurrentSong(), -1,
                            PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
                } else {
                    playNext(PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
                }
            }

            @Override
            public void onError(int what, String extra) {
                handlePlayerError(what, extra, generation);
            }

            @Override
            public void onSeekComplete() {
                handleSeekComplete(generation);
            }
        });
    }

    private void waitForAuthThenPlay(final SongItem song, final long generation) {
        pendingAuthSong = song;
        pendingAuthSeekMs = pendingSeekMs;
        pendingAuthGeneration = generation;
        pendingSeekMs = -1;
        playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.IDLE);
        startForegroundServiceNotification("正在鉴权", song.getName() + " - 认证后自动播放");
        if (stateChangeListener != null) {
            stateChangeListener.onError("正在鉴权, 认证完成后自动播放 " + song.getName());
        }
        if (!authWaitInProgress) {
            authWaitInProgress = true;
            JellyfinApiClient.getInstance().authenticateFromPrefs(this, new JellyfinApiClient.ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    if (!playbackState.isCurrentGeneration(generation)) return;
                    authWaitInProgress = false;
                }

                @Override
                public void onError(Exception e) {
                    if (!playbackState.isCurrentGeneration(generation)) return;
                    authWaitInProgress = false;
                }
            });
        }
    }

    public void playOrPause() {
        playOrPause(PlaybackStateMachine.PlaybackOrigin.USER_UI);
    }

    private void playOrPause(PlaybackStateMachine.PlaybackOrigin origin) {
        if (PlaybackStateMachine.shouldToggleToPlay(
                playbackState.getDesiredPlayback(), playbackState.getEngineState(), isPlaying())) {
            play(origin);
        } else {
            pause();
        }
    }

    public void play() {
        play(PlaybackStateMachine.PlaybackOrigin.USER_UI);
    }

    private void play(PlaybackStateMachine.PlaybackOrigin origin) {
        PlaybackStateMachine.DesiredPlayback previousDesired = playbackState.getDesiredPlayback();
        playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        playbackState.setPlaybackOrigin(origin);
        if (previousDesired == PlaybackStateMachine.DesiredPlayback.PLAY
                && (playbackState.isPreparing() || isPlaying())) {
            return;
        }
        if (player != null && playbackState.isPrepared()) {
            requestAudioFocus();
            resumeAfterInterruption(playbackState.getGenerationId(), origin);
            return;
        }
        if (playlist != null && !playlist.isEmpty()) {
            if (currentIndex < 0 || currentIndex >= playlist.size()) currentIndex = 0;
            streamRetryCount = 0;
            silentErrorStreak = 0;
            startPlaybackWithSeek(playlist.get(currentIndex), -1, origin);
        }
    }

    public void pause() {
        playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PAUSE);
        fadeOutAndPause(playbackState.getGenerationId(), true,
                PlaybackStateMachine.PauseReason.USER_REQUEST);
    }

    public void resume() {
        play();
    }

    private void fadeOutAndPause(final long generation, final boolean notify,
                                 final PlaybackStateMachine.PauseReason reason) {
        if (player == null || !playbackState.isPrepared()) {
            if (notify && PlaybackStateMachine.shouldCompletePause(reason,
                    playbackState.getDesiredPlayback(), playbackState.getFocusState())
                    && stateChangeListener != null) {
                if (remoteControlClient != null) remoteControlClient.setPaused();
                reassertMediaButton();
                stateChangeListener.onPlayStateChanged(false);
            }
            return;
        }
        gainEnvelope.fadeTo(0.0f, FADE_OUT_MS, new Runnable() {
            @Override
            public void run() {
                if (!playbackState.isCurrentGeneration(generation) || player == null) return;
                if (!PlaybackStateMachine.shouldCompletePause(reason,
                        playbackState.getDesiredPlayback(), playbackState.getFocusState())) return;
                try {
                    if (player.isPlaying()) player.pause();
                    playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.PAUSED);
                    // 本次连续出声结束: 恢复时允许功放再唤醒一次 (长暂停后 DSP 可能已 standby)
                    playbackState.notePlaybackInterrupted();
                    if (reason == PlaybackStateMachine.PauseReason.FOCUS_LOSS) {
                        // 记录失焦暂停时刻: 语音播报等场景恢复时按暂停时长决定是否重建渲染路径
                        transientPausedAtMs = SystemClock.elapsedRealtime();
                    }
                    if (remoteControlClient != null) remoteControlClient.setPaused();
                    reassertMediaButton();
                    if (notify && stateChangeListener != null) stateChangeListener.onPlayStateChanged(false);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Pause transition failed", e);
                }
            }
        });
    }

    private boolean startPreparedPlayback(long generation,
                                          PlaybackStateMachine.PlaybackOrigin origin) {
        if (player == null || !playbackState.canStart(generation, origin)) return false;
        try {
            if (!player.isPlaying()) player.start();
            playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.PLAYING);
            nudgeAmpChannel();
            if (remoteControlClient != null) remoteControlClient.setPlaying();
            reassertMediaButton();
            float targetGain = playbackState.getFocusState() == PlaybackStateMachine.FocusState.DUCKED ? 0.2f : 1.0f;
            gainEnvelope.fadeTo(targetGain, FADE_IN_MS, null);
            if (stateChangeListener != null) stateChangeListener.onPlayStateChanged(true);
            if (lastCountedGeneration != generation) {
                lastCountedGeneration = generation;
                SongItem playing = getCurrentSong();
                if (playing != null) SongDao.getInstance(this).incrementPlayCountAsync(playing.getId());
            }
            return true;
        } catch (IllegalStateException e) {
            playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.ERROR);
            Log.w(TAG, "Start transition failed", e);
            return false;
        }
    }

    /** 断流恢复: seek 会迫使 NuPlayer 重建 HTTP 连接, 失败则走 onError 重试链 */
    private void recoverStalledStream(int posMs, long observedGeneration) {
        if (!playbackState.isCurrentGeneration(observedGeneration)) return;
        final SongItem song = getCurrentSong();
        if (song == null) return;
        stallRecovering = true;
        stallTicks = 0;
        lastTickTrackId = song.getId();
        lastTickPositionMs = Math.max(posMs, 0);
        pendingSeekMs = posMs;
        Log.w(TAG, "Stream stalled at " + posMs + "ms, rebuilding connection for " + song.getName());
        if (stateChangeListener != null) {
            stateChangeListener.onError("网络波动, 正在恢复播放 " + song.getName());
        }
        startPlayback(song, PlaybackStateMachine.PlaybackOrigin.NETWORK_RECOVERY);
    }

    /**
     * 看门狗当前该做什么。焦点被拒时只重新申请焦点, 不重建串流也不提示用户;
     * 焦点被别人抢走时静等 AUDIOFOCUS_GAIN。
     */
    private PlaybackStateMachine.WatchdogAction watchdogAction() {
        return PlaybackStateMachine.watchdogAction(
                playbackState.getDesiredPlayback(),
                playbackState.getFocusState(),
                PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
    }

    /**
     * 焦点被拒后的静默重试。拿到焦点且播放器仍可用就直接起播; 播放器已死则交给下一 tick
     * 走正常的断点重建路径。全程不提示用户。
     */
    private void retryAudioFocusForAutomaticPlayback() {
        requestAudioFocus();
        if (playbackState.getFocusState() == PlaybackStateMachine.FocusState.DENIED) return;
        if (player != null && playbackState.isPrepared()) {
            startPreparedPlayback(playbackState.getGenerationId(),
                    PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
        }
    }

    /** 假播放看门狗兜底: 重试链耗尽后播放器死亡, 从最后已知位置自动续播 (指数退避调用) */
    private void autoResumeFromLastPosition(long observedGeneration) {
        if (!playbackState.isCurrentGeneration(observedGeneration)) return;
        SongItem song = getCurrentSong();
        if (song == null) return;
        int resumeMs = PlaybackStateMachine.resumePositionForTrack(
                song.getId(), lastTickTrackId, lastTickPositionMs, song.getDurationMs());
        stallRecovering = false;
        stallTicks = 0;
        pendingSeekMs = resumeMs;
        Log.w(TAG, "Watchdog auto-resume " + song.getName() + " from " + resumeMs + "ms");
        if (stateChangeListener != null) {
            stateChangeListener.onError("网络不稳定, 正在自动恢复播放...");
        }
        startPlayback(song, PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
    }

    /**
     * 被打断后的统一恢复入口 (焦点 GAIN / 用户按播放)。
     * 语音播报等失焦暂停超过阈值时, 直接 start() 可能遇到 DSP 路由假死
     * (进度推进但物理无声, 两个看门狗都不触发), 改为 seek 到当前位置强迫
     * 播放器重建渲染路径; 短暂停保持直接 start()。
     */
    private void resumeAfterInterruption(long generation,
                                         PlaybackStateMachine.PlaybackOrigin origin) {
        long pausedAtMs = transientPausedAtMs;
        transientPausedAtMs = -1L;
        if (pausedAtMs >= 0
                && PlaybackStateMachine.shouldRefreshOnTransientResume(
                        SystemClock.elapsedRealtime() - pausedAtMs)
                && player != null
                && playbackState.isPrepared()) {
            int resumeMs = getCurrentPositionMs();
            long seekOperation = playbackState.beginSeekOperation();
            Log.i(TAG, "Refreshing render path after transient focus loss, resume at "
                    + resumeMs + "ms");
            performSeek(resumeMs, generation, seekOperation);
            return;
        }
        startPreparedPlayback(generation, origin);
    }

    /** Re-applies a non-zero media volume without changing mute state or audio focus. */
    private void nudgeAmpChannel() {
        if (ampWakeStrategy == null) return;
        try {
            long wakeKey = playbackState.getAmplifierWakeKey();
            GeelyAmpWakeStrategy.WakeResult result = ampWakeStrategy.wakeDetailed(wakeKey);
            if (result == GeelyAmpWakeStrategy.WakeResult.WOKEN) {
                Log.d(TAG, "Amp wake nudged, key=" + wakeKey);
            }
            // WOKEN 也要安排跟进重试: 冷启动/install 后功放 DSP 可能晚于首次 nudge 才就绪,
            // 只唤醒一次会让同一 episode 内永远无声 (2026-09-03 实车部署复现)。
            // 终态 SKIPPED (音量 0 红线 / 重试窗口已过) 才停止, 防无限重试由 strategy 窗口兜底。
            if (result != GeelyAmpWakeStrategy.WakeResult.SKIPPED) {
                scheduleAmpWakeRetry(wakeKey, 0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Amplifier wake nudge failed", e);
        }
    }

    /** 启动/恢复后功放唤醒的跟进重试跳数上限 (首次 nudge + 2 跳, 间隔 ≥5.3s)。 */
    private static final int AMP_WAKE_MAX_RETRIES = 2;

    /**
     * 功放唤醒后的延迟重试。重试前复验 episode key 仍当前且仍在播放 ——
     * 切歌/暂停后的旧重试不得误唤醒。音量 0 红线由 wake() 内部复验。
     * 若重试仍返回非终态结果则续排下一跳, 直至 {@link #AMP_WAKE_MAX_RETRIES};
     * strategy 的 20s 重试窗口保证最终收敛。
     */
    private void scheduleAmpWakeRetry(final long wakeKey, final int retryNo) {
        if (retryNo >= AMP_WAKE_MAX_RETRIES) return;
        if (scheduledAmpRetryKey == wakeKey) return;
        scheduledAmpRetryKey = wakeKey;
        progressHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                scheduledAmpRetryKey = -1L;
                if (ampWakeStrategy == null) return;
                if (playbackState.getAmplifierWakeKey() != wakeKey) return;
                if (!playbackState.expectsPlayback() || !isPlaying()) return;
                try {
                    GeelyAmpWakeStrategy.WakeResult result = ampWakeStrategy.wakeDetailed(wakeKey);
                    if (result == GeelyAmpWakeStrategy.WakeResult.WOKEN) {
                        Log.d(TAG, "Amp wake retry nudged, key=" + wakeKey + " attempt=" + (retryNo + 1));
                    }
                    if (result != GeelyAmpWakeStrategy.WakeResult.SKIPPED) {
                        scheduleAmpWakeRetry(wakeKey, retryNo + 1);
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "Amplifier wake retry failed", e);
                }
            }
        }, GeelyAmpWakeStrategy.MIN_WAKE_INTERVAL_MS + 300);
    }

    public void playNext() {
        playNext(PlaybackStateMachine.PlaybackOrigin.USER_UI);
    }

    private void playNext(PlaybackStateMachine.PlaybackOrigin origin) {
        if (playlist.isEmpty()) return;
        if (currentPlayMode == MODE_RANDOM) {
            currentIndex = new Random().nextInt(playlist.size());
        } else {
            currentIndex = (currentIndex + 1) % playlist.size();
        }
        streamRetryCount = 0;
        silentErrorStreak = 0;
        playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        startPlaybackWithSeek(playlist.get(currentIndex), -1, origin);
    }

    public void playPrevious() {
        playPrevious(PlaybackStateMachine.PlaybackOrigin.USER_UI);
    }

    private void playPrevious(PlaybackStateMachine.PlaybackOrigin origin) {
        if (playlist.isEmpty()) return;
        if (currentPlayMode == MODE_RANDOM) {
            currentIndex = new Random().nextInt(playlist.size());
        } else {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
        }
        streamRetryCount = 0;
        silentErrorStreak = 0;
        playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        startPlaybackWithSeek(playlist.get(currentIndex), -1, origin);
    }

    public void seekTo(final int ms) {
        if (player != null && playbackState.isPrepared()) {
            final long generation = playbackState.getGenerationId();
            final long seekOperation = playbackState.beginSeekOperation();
            playbackState.setPlaybackOrigin(PlaybackStateMachine.PlaybackOrigin.USER_UI);
            gainEnvelope.fadeTo(0.0f, FADE_OUT_MS, new Runnable() {
                @Override
                public void run() {
                    if (!playbackState.isCurrentGeneration(generation)
                            || !playbackState.isCurrentSeekOperation(seekOperation)
                            || player == null) return;
                    try {
                        if (player.isPlaying()) player.pause();
                        performSeek(ms, generation, seekOperation);
                    } catch (IllegalStateException e) {
                        playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.ERROR);
                        Log.w(TAG, "Seek transition failed", e);
                    }
                }
            });
            return;
        }
        SongItem song = getCurrentSong();
        if (song != null && pendingAuthSong == null) {
            playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
            startPlaybackWithSeek(song, ms, PlaybackStateMachine.PlaybackOrigin.USER_UI);
        }
    }

    private void performSeek(int ms, final long generation, final long seekOperation) {
        if (!playbackState.isCurrentGeneration(generation)
                || !playbackState.isCurrentSeekOperation(seekOperation)) return;
        if (activeSeekOperationId >= 0L) {
            queuedSeekOperationId = seekOperation;
            queuedSeekMs = ms;
            return;
        }
        activeSeekOperationId = seekOperation;
        playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.SEEKING);
        player.seekTo(ms);
        seekTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (activeSeekOperationId != seekOperation) return;
                activeSeekOperationId = -1L;
                seekTimeoutRunnable = null;
                if (playbackState.isCurrentGeneration(generation)
                        && playbackState.isCurrentSeekOperation(seekOperation)) {
                    Log.w(TAG, "Seek callback timed out for operation " + seekOperation);
                    finishSeekTransition(generation, seekOperation);
                } else {
                    performQueuedSeek(generation);
                }
            }
        };
        progressHandler.postDelayed(seekTimeoutRunnable, SEEK_TIMEOUT_MS);
    }

    private void handleSeekComplete(long generation) {
        long completedOperation = activeSeekOperationId;
        if (completedOperation < 0L) return;
        activeSeekOperationId = -1L;
        cancelSeekTimeout();
        if (playbackState.isCurrentGeneration(generation)
                && playbackState.isCurrentSeekOperation(completedOperation)) {
            finishSeekTransition(generation, completedOperation);
        } else {
            performQueuedSeek(generation);
        }
    }

    private void performQueuedSeek(long generation) {
        if (queuedSeekOperationId < 0L || player == null) return;
        long operation = queuedSeekOperationId;
        int targetMs = queuedSeekMs;
        queuedSeekOperationId = -1L;
        queuedSeekMs = -1;
        performSeek(targetMs, generation, operation);
    }

    private void finishSeekTransition(long generation, long seekOperation) {
        if (!playbackState.canFinishSeekTransition(generation, seekOperation)) return;
        if (playbackState.canStart(generation, playbackState.getPlaybackOrigin())) {
            startPreparedPlayback(generation, playbackState.getPlaybackOrigin());
        } else {
            playbackState.finishSeekWithoutPlayback(generation, seekOperation);
        }
    }

    private void invalidateSeekOperations() {
        cancelSeekTimeout();
        activeSeekOperationId = -1L;
        queuedSeekOperationId = -1L;
        queuedSeekMs = -1;
        playbackState.invalidateSeekOperations();
    }

    private void cancelSeekTimeout() {
        if (seekTimeoutRunnable != null) {
            progressHandler.removeCallbacks(seekTimeoutRunnable);
            seekTimeoutRunnable = null;
        }
    }

    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
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

    /** 播放器内的真实进度, 避免依赖可能滞后的 UI SeekBar 状态 */
    public int getCurrentPositionMs() {
        if (player != null && playbackState.isPrepared()) {
            try {
                return player.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    /** 是否至少完成过一次 prepare (EQ 引擎随首次 prepare 创建) */
    public boolean isEverPrepared() {
        return everPrepared;
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

    // ---------------- AudioFx 解决每次 reset 后 SessionId 变化问题 ----------------

    private void updateAudioFxSession(int sessionId) {
        if (sessionId == 0 || sessionId == currentAudioSessionId) return;

        releaseAudioFx();
        currentAudioSessionId = sessionId;

        try {
            equalizer = new Equalizer(0, currentAudioSessionId);
            equalizer.setEnabled(true);
            if (currentPresetIndex >= 0 && currentPresetIndex < equalizer.getNumberOfPresets()) {
                equalizer.usePreset(currentPresetIndex);
            }
        } catch (Exception e) {
            Log.w(TAG, "Equalizer init failed for session " + currentAudioSessionId, e);
            equalizer = null;
        }

        try {
            bassBoost = new BassBoost(0, currentAudioSessionId);
            bassBoost.setEnabled(true);
            if (currentBassPercent > 0 && bassBoost.getStrengthSupported()) {
                bassBoost.setStrength((short) (currentBassPercent * 10));
            }
        } catch (Exception e) {
            Log.w(TAG, "BassBoost init failed for session " + currentAudioSessionId, e);
            bassBoost = null;
        }

        if (!ENABLE_PANORAMA_REVERB) return;
        if (currentVirtualizerPercent <= 0) {
            // Neusoft Virtualizer 驱动缺陷: 当 setEnabled(false) bypass 时未把输入 PCM 拷贝到输出, 直接吐静音!
            // 故 percent == 0 时坚决不 new/挂载 Virtualizer, 保持效果链末端纯净直通
            if (virtualizer != null) {
                try { virtualizer.release(); } catch (Exception ignored) {}
                virtualizer = null;
            }
            return;
        }
        try {
            virtualizer = new Virtualizer(0, currentAudioSessionId);
            // 车机 HAL 若报 strengthSupported=false, setStrength 会被跳过, 效果引擎只能用
            // 厂商内置默认强度运行 (通常很轻), 拖动滑块会看起来"没反应"。诊断日志确认此设备
            // 的真实返回值, 而不是继续猜测强度公式或映射逻辑。
            boolean strengthSupported = virtualizer.getStrengthSupported();
            Log.i(TAG, "Virtualizer strengthSupported=" + strengthSupported
                    + " on session " + currentAudioSessionId);
            if (strengthSupported) {
                virtualizer.setStrength((short) (currentVirtualizerPercent * 10));
            }
            virtualizer.setEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Virtualizer init failed for session " + currentAudioSessionId, e);
            virtualizer = null;
        }
    }

    private void releaseAudioFx() {
        if (equalizer != null) {
            try { equalizer.release(); } catch (Exception ignored) {}
            equalizer = null;
        }
        if (bassBoost != null) {
            try { bassBoost.release(); } catch (Exception ignored) {}
            bassBoost = null;
        }
        if (virtualizer != null) {
            try { virtualizer.release(); } catch (Exception ignored) {}
            virtualizer = null;
        }
    }

    // ---------------- 全景 (Virtualizer) / 空间混响 (EnvironmentalReverb aux) ----------------

    /** 混响参数表 (保守防爆音): roomLevel/roomHFLevel mB, decayTime ms, decayHFRatio/扩散/密度 ‰, reverbLevel mB。
     *  reverbLevel 2026-09-05 车机实测普遍感知不到, 原表房间档 -2000mB(-20dB) 过弱, 三档统一上调
     *  且保持房间<音乐厅<影院的强度递增关系, decay/room 等空间感参数不变以维持已验证的防炸音裕度。 */
    private static final int[][] REVERB_PARAMS = {
            // 关闭占位, 不会下发
            {0, 0, 0, 0, 0, 0, 0},
            // 房间: 短衰减弱湿声, 轻微空间感
            {-6000, -2000, 300, 500, 600, 600, -800},
            // 音乐厅: 长衰减, 声场拉开
            {-4000, -1000, 1400, 400, 900, 900, -300},
            // 影院: 中长衰减 + 高扩散
            {-3000, 0, 900, 700, 1000, 1000, 0},
    };

    /** sendLevel: aux 湿声占比。0.4 在车机实测中偏弱难以感知 (2026-09-05), 提高到 0.65
     *  仍远低于 1.0 满湿声, 保留防喷麦/防炸音的裕度。 */
    private static final float REVERB_SEND_LEVEL = 0.65f;

    /** 懒加载全局混响 (session 0)。车机 HAL 不支持时返回 false, 功能静默降级 */
    private boolean ensureReverb() {
        if (currentReverbMode <= 0) return false;
        if (environmentalReverb == null) {
            try {
                environmentalReverb = new EnvironmentalReverb(0, 0);
            } catch (Exception e) {
                Log.w(TAG, "EnvironmentalReverb init failed", e);
                return false;
            }
        }
        try {
            int[] p = REVERB_PARAMS[currentReverbMode];
            environmentalReverb.setRoomLevel((short) p[0]);
            environmentalReverb.setRoomHFLevel((short) p[1]);
            environmentalReverb.setDecayTime(p[2]);
            environmentalReverb.setDecayHFRatio((short) p[3]);
            environmentalReverb.setDiffusion((short) p[4]);
            environmentalReverb.setDensity((short) p[5]);
            environmentalReverb.setReverbLevel((short) p[6]);
            environmentalReverb.setEnabled(true);
            // 诊断: 确认参数真的落地 (而非静默被 HAL 忽略), 而不是继续猜测感知弱的原因
            Log.i(TAG, "Reverb applied mode=" + currentReverbMode
                    + " roomLevel=" + environmentalReverb.getRoomLevel()
                    + " reverbLevel=" + environmentalReverb.getReverbLevel()
                    + " enabled=" + environmentalReverb.getEnabled());
        } catch (Exception e) {
            Log.w(TAG, "Reverb params failed (mode " + currentReverbMode + ")", e);
            return false;
        }
        return true;
    }

    /** 每次曲目 prepare 后调用: reset 会重建 AudioTrack, aux 挂接关系会丢, 必须重挂。
     *  v3 引擎下为 no-op (软件混响在 NativeDsp 内部)。 */
    private void applyAuxEffect() {
        if (playerIsV3 || player == null) return;
        try {
            if (currentReverbMode > 0 && ensureReverb()) {
                player.attachAuxEffect(environmentalReverb.getId());
                player.setAuxEffectSendLevel(REVERB_SEND_LEVEL);
            } else {
                player.attachAuxEffect(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "attachAuxEffect failed", e);
        }
    }

    public short getCurrentPresetIndex() {
        return currentPresetIndex;
    }

    public int getCurrentBassPercent() {
        return currentBassPercent;
    }

    public List<String> getEqPresets() {
        List<String> list = new ArrayList<>();
        if (equalizer != null) {
            try {
                short numPresets = equalizer.getNumberOfPresets();
                for (short i = 0; i < numPresets; i++) {
                    list.add(equalizer.getPresetName(i));
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting EQ presets", e);
            }
        }
        return list;
    }

    public void setEqPreset(short presetIndex) {
        this.currentPresetIndex = presetIndex;
        saveDspParamsToPrefs();
        com.ktools.zspacecarplayer.dsp.NativeDsp.setEqualizerPreset(presetIndex);
        if (equalizer != null) {
            try {
                if (presetIndex >= 0 && presetIndex < equalizer.getNumberOfPresets()) {
                    equalizer.usePreset(presetIndex);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error setting EQ preset", e);
            }
        }
    }

    public void setBassBoostPercent(int percent) {
        this.currentBassPercent = percent;
        saveDspParamsToPrefs();
        com.ktools.zspacecarplayer.dsp.NativeDsp.setBassBoost(percent);
        if (bassBoost != null) {
            try {
                if (bassBoost.getStrengthSupported()) {
                    short strength = (short) (percent * 10);
                    bassBoost.setStrength(strength);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error setting BassBoost", e);
            }
        }
    }

    public int getCurrentVirtualizerPercent() {
        return currentVirtualizerPercent;
    }

    public void setVirtualizerPercent(int percent) {
        this.currentVirtualizerPercent = Math.max(0, Math.min(100, percent));
        saveDspParamsToPrefs();
        com.ktools.zspacecarplayer.dsp.NativeDsp.setVirtualizer(this.currentVirtualizerPercent);
        if (!ENABLE_PANORAMA_REVERB) return;
        if (this.currentVirtualizerPercent <= 0) {
            if (virtualizer != null) {
                try { virtualizer.release(); } catch (Exception ignored) {}
                virtualizer = null;
            }
            return;
        }
        if (currentAudioSessionId < 0) return;
        try {
            if (virtualizer == null) {
                virtualizer = new Virtualizer(0, currentAudioSessionId);
            }
            if (virtualizer.getStrengthSupported()) {
                virtualizer.setStrength((short) (this.currentVirtualizerPercent * 10));
            }
            virtualizer.setEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Error setting Virtualizer", e);
            virtualizer = null;
        }
    }

    public int getCurrentReverbMode() {
        return currentReverbMode;
    }

    public void setReverbMode(int mode) {
        this.currentReverbMode = Math.max(0, Math.min(REVERB_PARAMS.length - 1, mode));
        saveDspParamsToPrefs();
        com.ktools.zspacecarplayer.dsp.NativeDsp.setReverb(this.currentReverbMode);
        if (!ENABLE_PANORAMA_REVERB) return;
        if (this.currentReverbMode > 0) {
            ensureReverb();
        } else if (environmentalReverb != null) {
            try { environmentalReverb.setEnabled(false); } catch (Exception ignored) {}
        }
        applyAuxEffect();
    }

    // ---------------- 音频焦点避让 ----------------

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            final long generation = playbackState.getGenerationId();
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    playbackState.setFocusState(PlaybackStateMachine.FocusState.GRANTED);
                    if (playbackState.expectsPlayback() && playbackState.isPrepared() && !isPlaying()) {
                        resumeAfterInterruption(generation, playbackState.getPlaybackOrigin());
                    } else if (PlaybackStateMachine.shouldRestoreGainOnFocusGain(
                            playbackState.getDesiredPlayback(), playbackState.getEngineState(), isPlaying())) {
                        gainEnvelope.fadeTo(1.0f, FADE_IN_MS, null);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    playbackState.setFocusState(PlaybackStateMachine.FocusState.LOST_PERMANENT);
                    fadeOutAndPause(generation, true, PlaybackStateMachine.PauseReason.FOCUS_LOSS);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    playbackState.setFocusState(PlaybackStateMachine.FocusState.LOST_TRANSIENT);
                    fadeOutAndPause(generation, true, PlaybackStateMachine.PauseReason.FOCUS_LOSS);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    playbackState.setFocusState(PlaybackStateMachine.FocusState.DUCKED);
                    if (playbackState.isPrepared()) gainEnvelope.fadeTo(0.2f, FADE_OUT_MS, null);
                    break;
                default:
                    break;
            }
        }
    };

    private void requestAudioFocus() {
        if (audioManager == null) {
            playbackState.setFocusState(PlaybackStateMachine.FocusState.NONE);
            return;
        }
        int result = audioManager.requestAudioFocus(focusChangeListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        playbackState.setFocusState(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                ? PlaybackStateMachine.FocusState.GRANTED : PlaybackStateMachine.FocusState.DENIED);
    }

    // ---------------- Generation-bound MediaPlayer callbacks ----------------

    private void handlePrepared(int durationMs, long generation) {
        if (!playbackState.isCurrentGeneration(generation)) return;
        CrashMonitor.breadcrumb("play", "prepared dur=" + durationMs + "ms v3=" + playerIsV3);
        playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.READY);
        everPrepared = true;
        streamRetryCount = 0;
        silentErrorStreak = 0;
        stallRecovering = false;
        stallTicks = 0;
        if (playerIsV3) {
            // v3 引擎: 音效全部由 NativeDsp 在 PCM 链路内完成。
            // 系统音效绝不能挂 (尤其 session 0 的 Virtualizer 有 bypass 静音缺陷)
            releaseAudioFx();
            currentAudioSessionId = -1;
            applyDspParamsToNative(); // 引擎刚被 init 重建, 必须重放用户音效设置
        } else {
            updateAudioFxSession(player.getAudioSessionId());
        }
        if (!playerIsV3 && ENABLE_PANORAMA_REVERB) applyAuxEffect();
        int seekMs = pendingSeekMs;
        pendingSeekMs = -1;
        // 恢复锚点跟着当前曲目走: 若起播后立刻再次断流 (还没跑到第一个 tick),
        // 仍应从本次的断点续播, 而不是回到 0 或继承上一首的进度
        SongItem preparedSong = getCurrentSong();
        lastTickTrackId = preparedSong != null ? preparedSong.getId() : null;
        lastTickPositionMs = Math.max(seekMs, 0);
        if (seekMs > 0 && seekMs < durationMs) {
            long seekOperation = playbackState.beginSeekOperation();
            performSeek(seekMs, generation, seekOperation);
            return;
        }
        if (playbackState.canStart(generation, playbackState.getPlaybackOrigin())) {
            startPreparedPlayback(generation, playbackState.getPlaybackOrigin());
        }
    }

    private static final int MEDIA_ERROR_SYSTEM = -2147483648;
    private static final long REAUTH_COOLDOWN_MS = 120 * 1000;
    private long lastReAuthAt = 0;

    private void handlePlayerError(int what, String extra, final long generation) {
        if (!playbackState.isCurrentGeneration(generation)) return;
        silentErrorStreak++;
        Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra
                + ", streak=" + silentErrorStreak);
        CrashMonitor.putContext("lastErrorWhat", what);
        CrashMonitor.putContext("lastErrorExtra", String.valueOf(extra));
        CrashMonitor.breadcrumb("play", "error what=" + what + " extra=" + extra
                + " retry=" + streamRetryCount + " streak=" + silentErrorStreak + " v3=" + playerIsV3);
        playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.ERROR);
        stallRecovering = false;
        invalidateSeekOperations();
        // v3 引擎的负值错误码 (DECODE_FAILED / STREAM_STALL) 一律按可恢复的网络/解码类错误重试;
        // 慢网 (1,-19) 是传输层瞬时错误, 同样可恢复, 否则看门狗每轮重启都复现一次 GIVE_UP
        boolean transientTransport = !playerIsV3
                && PlaybackStateMachine.isTransientTransportError(what, extra);
        boolean recoverable = playerIsV3 || transientTransport
                || (what == MediaPlayer.MEDIA_ERROR_IO || what == MEDIA_ERROR_SYSTEM);
        boolean reauthCooldownElapsed =
                System.currentTimeMillis() - lastReAuthAt > REAUTH_COOLDOWN_MS;
        PlaybackStateMachine.StreamRetryAction action = PlaybackStateMachine.effectiveRetryAction(
                PlaybackStateMachine.streamRetryAction(recoverable, streamRetryCount, reauthCooldownElapsed),
                transientTransport);
        if (action != PlaybackStateMachine.StreamRetryAction.GIVE_UP) {
            streamRetryCount++;
            final SongItem failedSong = getCurrentSong();
            if (failedSong != null && !authWaitInProgress) {
                int resumeMs = pendingSeekMs;
                if (resumeMs <= 0) {
                    try { resumeMs = player.getCurrentPosition(); } catch (Exception ignored) { resumeMs = 0; }
                }
                final int finalResumeMs = Math.max(resumeMs, 0);
                pendingSeekMs = -1;
                if (action == PlaybackStateMachine.StreamRetryAction.PLAIN_RETRY) {
                    reportPlaybackError("网络波动, 自动重试 " + failedSong.getName());
                    replayPendingSong(failedSong, finalResumeMs,
                            PlaybackStateMachine.PlaybackOrigin.NETWORK_RECOVERY);
                    return;
                }
                lastReAuthAt = System.currentTimeMillis();
                authWaitInProgress = true;
                reportPlaybackError("会话可能失效, 重新登录后自动重试...");
                JellyfinApiClient.getInstance().authenticateFromPrefs(this, new JellyfinApiClient.ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        if (!playbackState.isCurrentGeneration(generation)) return;
                        authWaitInProgress = false;
                        replayPendingSong(failedSong, finalResumeMs,
                                PlaybackStateMachine.PlaybackOrigin.AUTH_RECOVERY);
                    }

                    @Override
                    public void onError(Exception e) {
                        if (!playbackState.isCurrentGeneration(generation)) return;
                        authWaitInProgress = false;
                        pendingSeekMs = -1;
                        playbackState.setEngineState(generation, PlaybackStateMachine.EngineState.ERROR);
                        if (stateChangeListener != null) stateChangeListener.onError("重新登录失败, 无法播放 " + failedSong.getName());
                    }
                });
                return;
            }
        }
        pendingSeekMs = -1;
        reportPlaybackError("播放出错(Code " + what + ")");
    }

    /** 重复错误只提示一次, 其余落日志与面包屑 (慢网下看门狗会反复重启当前曲目)。 */
    private void reportPlaybackError(String message) {
        if (PlaybackStateMachine.shouldNotifyError(silentErrorStreak)) {
            if (stateChangeListener != null) stateChangeListener.onError(message);
            return;
        }
        Log.w(TAG, "Repeat playback error suppressed (streak=" + silentErrorStreak + "): " + message);
        CrashMonitor.breadcrumb("play", "error suppressed streak=" + silentErrorStreak);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void stopAndReleaseAllAudioResources() {
        Log.i(TAG, "stopAndReleaseAllAudioResources: releasing DSP, audio effects, MediaPlayer, and audio focus");
        try {
            JellyfinApiClient.getInstance().setOnAuthStateListener(null);
        } catch (Exception ignored) {}
        if (progressHandler != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }
        cancelSeekTimeout();
        if (gainEnvelope != null) {
            try { gainEnvelope.hardMute(); } catch (Exception ignored) {}
        }
        playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.STOP);
        playbackState.beginGeneration(PlaybackStateMachine.EngineState.RELEASED);
        if (audioManager != null) {
            try { audioManager.abandonAudioFocus(focusChangeListener); } catch (Exception ignored) {}
            try {
                ComponentName mbComponent = new ComponentName(getPackageName(), MediaButtonReceiver.class.getName());
                audioManager.unregisterMediaButtonEventReceiver(mbComponent);
            } catch (Exception ignored) {}
        }
        if (remoteControlClient != null) {
            try {
                remoteControlClient.setStopped();
                remoteControlClient.unregister(audioManager);
            } catch (Exception ignored) {}
            remoteControlClient = null;
        }
        releaseAudioFx();
        if (environmentalReverb != null) {
            try { environmentalReverb.release(); } catch (Exception ignored) {}
            environmentalReverb = null;
        }
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        // v3: 释放流式缓冲数据源（本地代理的环形缓冲下载线程）
        try { HttpProxyServer.getInstance().clearSources(); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopAndReleaseAllAudioResources();
        super.onDestroy();
    }
}
