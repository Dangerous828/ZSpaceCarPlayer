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
import com.ktools.zspacecarplayer.player.stream.BufferingPolicy;
import com.ktools.zspacecarplayer.player.stream.StreamDiskCache;
import com.ktools.zspacecarplayer.player.stream.StreamTier;
import com.ktools.zspacecarplayer.player.stream.HttpProxyServer;
import com.ktools.zspacecarplayer.model.SongItem;
import com.ktools.zspacecarplayer.ui.MainActivity;

import java.util.ArrayList;
import java.util.Arrays;
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
    /**
     * 车主手选的音质档位 (2026-10-08 T1 重审 L2)。存的是 {@link
     * com.ktools.zspacecarplayer.player.stream.StreamTier} 的整数 id，默认无损。
     * <b>没有任何自动切换路径</b>——自动降档已被车主否决。
     */
    public static final String PREF_KEY_STREAM_TIER = "play_stream_tier";
    /** 取流改造的回退闸 (2026-10-08)：关任何一项就退回 vc20 的既有行为，不用重出包。 */
    public static final String PREF_KEY_STREAM_TUNING = "stream_tuning_enabled";
    public static final String PREF_KEY_DISK_CACHE = "disk_cache_enabled";
    private static final boolean DEFAULT_ENGINE_V3 = false; // 默认系统引擎, 车机一次只变一个变量

    /** 播放器引擎抽象: 系统 MediaPlayer (兼容模式) / v3 自研 DSP 管线 */
    private IAudioPlayer player;
    /** 当前 player 是否为 v3 引擎 (与偏好可能短暂不一致, 切歌时惰性对齐) */
    private boolean playerIsV3 = false;
    private List<SongItem> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private int currentPlayMode = MODE_SEQUENCE;
    /** 随机模式的"上一首/下一首"可回溯序列 (2026-10-08)。队列一变即清空。 */
    private final ShuffleHistory shuffleHistory = new ShuffleHistory();
    /** 睡眠定时的截止时刻（elapsedRealtime 口径，墙钟改动不影响）；0 = 关。 */
    private volatile long sleepStopAtElapsedMs = SleepTimer.OFF;
    /** 耳机/外放拔出时暂停的接收器 (2026-10-08 T1 基准补齐)。 */
    private android.content.BroadcastReceiver audioNoisyReceiver;

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
    /** 系统静音监听 (2026-09-15): 车机静音键不进 App, 靠监听系统静音跳变触发暂停/恢复。 */
    private SystemMuteMonitor muteMonitor;
    /** 本次暂停是否由"系统静音"触发; 用户解除静音时据此自动恢复播放。 */
    private boolean pausedByMute = false;
    private long lastCountedGeneration = -1L;

    /** 有效播放判定阈值: 累计实际出声达到该时长才算一次「播放最多」计数 (2026-09-10) */
    private static final long VALID_PLAY_THRESHOLD_MS = 60000L;
    /** 待计数的 generation; -1 表示当前没有待计曲目 (未起播/已计满/已切歌) */
    private long pendingCountGeneration = -1L;
    /** 当前待计 generation 的累计实际出声时长 (500ms tick 累加, 暂停不加) */
    private long playAccumulatedMs = 0L;
    /** 上次 tick 的挂钟时间, 用于计算 tick 间增量; 暂停期间持续刷新防止把暂停算进去 */
    private long lastCountTickElapsedMs = 0L;

    /** Token 未就绪时挂起的待播曲目, 鉴权成功回调后自动起播 */
    private SongItem pendingAuthSong;
    private int pendingAuthSeekMs = -1;
    private long pendingAuthGeneration = -1L;
    private boolean authWaitInProgress = false;
    /**
     * 同曲重试退避挂起中：恢复动作由退避链唯一拥有，看门狗在此期间让位
     * (与 authWaitInProgress 用同一种表达方式，见 watchdog 的 REBUILD_STREAM 分支)。
     */
    private boolean streamRetryBackoffPending = false;
    private Runnable streamRetryBackoffRunnable;
    /** MEDIA_ERROR_IO 后的原曲重试计数, 起播成功 (onPrepared) 后归零 */
    private int streamRetryCount = 0;
    /** 同一轮起播未成功期间的重复错误计数, 用于抑制 Toast 刷屏; 与 streamRetryCount 同时归零 */
    private int silentErrorStreak = 0;
    /** 流卡死看门狗: isPlaying 但位置连续 10 秒零位移则从断点重启当前曲目 */
    private int lastTickPositionMs = -1;
    /** lastTickPositionMs 属于哪首歌, 防止把上一首的断点带到新曲目上 */
    private String lastTickTrackId;
    /**
     * 播放位置最近一次真正前进的时刻 ({@code SystemClock.elapsedRealtime()})；0 = 本轮播放
     * 还从未推进过。缓冲指示的<b>第二扇门</b>用它：下载口径在 chunked 流上三个数全是 -1
     * （不可判），那时"声音有没有往前走"是唯一拿得到的证据（2026-10-07「缓冲中」恒亮复盘）。
     */
    private long lastAudioAdvanceAtMs = 0L;
    /** {@link #lastAudioAdvanceAtMs} 的比较基准；-1 = 未初始化（第一 tick 只建基准）。 */
    private int lastHeardPositionMs = -1;
    /** 当前发给 UI 的起播状态文字；只为幂等（同一句话不重复刷回调）。 */
    private String playStatusText = null;
    /**
     * 本轮连续放弃了多少首（每次 GIVE_UP +1，任一首发出了声就归零）。
     * 只为一件事：连着跳歌时让屏幕上那行字说出"这是链路在扩大，不是某一首歌的问题"。
     */
    private int giveUpSongStreak = 0;
    /**
     * 已经因为「假播完」被救过一次的那首歌 (2026-10-07)。一次性，防止
     * "起播→截断→重试→又截断"在同一条路上原地打转：{@code handlePrepared} 每次成功
     * 都会把 {@code streamRetryCount} 归零，所以重试预算本身拦不住这个循环。
     */
    private String truncationRetriedTrackId;
    /**
     * 本次 generation 的「断点起播」目标 (2026-09-12 #1 兜底纠偏的武装条件)。
     * >0 表示这一轮是带着 song_progress 断点起播的 —— 只有这种情况才可能在起播后
     * 直接落到曲尾; 用户从头点播/自动切下一首 (startMs=-1) 一律不武装, 避免误纠偏。
     */
    private int startedWithResumeMs = -1;
    /**
     * 断点起播后累计的「真正出声」tick 数; -1 = 兜底未武装/窗口已过。
     * 只在 isPlaying() 的 tick 里自增, 所以暂停时长不计入, 长暂停后恢复不会被误判成
     * 「播完得太快」。换算成 ms 用 {@link PlaybackStateMachine#PROGRESS_TICK_MS}。
     */
    private int resumeGuardTicks = -1;
    /**
     * 断点起播后位置是否真的动过 (2026-10-08)。{@code resumeGuardTicks} 只数 tick，而
     * chunked 流死亡时播放器仍报 isPlaying()、位置恒为 0 —— 光看 tick 会把「一个字节都没出声」
     * 当成「播了 3 秒尾音」。这条只在位置发生位移时置位。
     */
    private boolean resumeHeardAudio = false;
    /** 本次 generation 是否已经因坏断点纠偏过一次 (一次性, 防止重播↔纠偏死循环) */
    private boolean badResumeCorrected = false;
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

    // ---- 缓冲 / 预取状态 (2026-09-12 缓冲/预取) ----
    /** 当前曲的远端直连 URL (与真正播放 / 预取命中同一个代理源用); 每轮起播刷新 */
    private volatile String currentRemoteUrl = null;
    /** 是否处于起播 prefill 门槛期间: prepareTransition 置真, handlePrepared 置假;
     *  v3 播放器也会经 onBufferingUpdate 校正。门槛期间不预取、不由 progress 重复上报缓冲。 */
    private volatile boolean prefillInProgress = false;

    /**
     * 带宽治理器：累计「链路持续追不上本曲所需速率」的证据。
     * 有状态但不跨进程存活——链路状况本来就是当下的事。
     */
    private final com.ktools.zspacecarplayer.player.stream.StreamRateGovernor rateGovernor =
            new com.ktools.zspacecarplayer.player.stream.StreamRateGovernor();
    /** 治理器认定缺口的次数是否已留过痕：每次认定只落一行面包屑 (2026-10-07 流畅档误判复盘)。 */
    private int lastDeficitConfirmations = 0;
    /**
     * 降档前实测到的「本曲不抽干所需速率」(B/s)，2026-10-08。流畅档是 chunked 流，没有
     * Content-Length 就算不出分母，回升判定需要一个分母才做得成 —— 唯一可信的就是这个数。
     */
    private long lastLosslessRequiredBytesPerSec = -1L;
    /** 上一次落盘的上游连接数，只在变化时留一行痕 (2026-10-08 连接形状复盘)。 */
    private long lastUpstreamConnections = -1L;
    /** 上次 tick 上报的缓冲 percent / 状态 (2026-09-13 双进度条调查): 变化时打一行诊断日志 */
    private int lastReportedBufferPercent = Integer.MIN_VALUE;
    private boolean lastReportedBuffering = false;
    /** 缓冲卡死心跳落盘的上次时刻: percent 长期不动时也要每 30s 留一行证据 (2026-09-15)。 */
    private long lastBufferDiagAtMs = 0L;
    /** 已预取的下一首曲目 id: 同一首只预取一次; 让位/切歌时清空以便恢复健康后重试 */
    private volatile String prefetchedNextSongId = null;
    /**
     * 预取源自己的 URL (2026-10-08 仪表补漏)。{@code getUpstreamCost} 按 URL 前缀聚合，
     * 而预取源的 key 是<b>下一首</b>的 URL——所以只看当前曲就永远统计不到预取开了几条连接、
     * 拉了多少字节，"预取有没有跟当前曲抢带宽"这件事根本没有数据。
     */
    private volatile String prefetchedNextUrl = null;

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

        /**
         * 缓冲进度上报（2026-09-12 缓冲/预取）。
         *
         * @param percent   已缓冲百分比；-1 表示总长未知（UI 显示「缓冲中…」）
         * @param buffering true = 仍在缓冲（应显示指示）；false = 稳定播放（应隐藏指示）
         */
        void onBufferingUpdate(int percent, boolean buffering);

        /**
         * 起播状态文字（2026-10-07 车主问「缓冲中那三个字对不上逻辑」后新增）。
         *
         * <p>带断点起播时，屏幕上原本只有一句含义不明的「缓冲中…」——而这段时间实际发生的事
         * 是"正在回到上次位置"，弱网下无 seektable 的 FLAC 要做十几次重定位，可能十几秒。
         * 与其让车主对着一个不动的进度条猜，不如把那件事说出来。
         *
         * @param text 要显示的状态文字；{@code null} / 空串 = 撤销这条，交回普通缓冲指示
         */
        void onPlayStatus(String text);
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
        applyStreamTuningFromPrefs();
        registerAudioNoisyReceiver();
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
        setupSystemMuteMonitor();
    }

    /**
     * 装配系统静音监听 (2026-09-15 实车需求: 按静音键 = 暂停歌曲 + 系统静音)。
     * 这台 GEELY/Neusoft 车机的硬件静音键经 ROM 自定义 AIDL 通道被系统 App 接走并直接
     * setStreamMute, 不进本 App 的 dispatchKeyEvent, 所以只能监听系统静音状态跳变:
     * 未静音→静音 时暂停播放; 静音→未静音 且此前是因静音而暂停时自动恢复。
     * 系统静音本身由 ROM 完成, 无需 App 再写; 仅"用户按播放键但系统仍静音"时主动解静音。
     */
    private void setupSystemMuteMonitor() {
        if (muteMonitor != null) return;
        try {
            muteMonitor = new SystemMuteMonitor(this, audioManager, progressHandler,
                    new SystemMuteMonitor.Listener() {
                        @Override
                        public void onSystemMuted() {
                            handleSystemMuted();
                        }

                        @Override
                        public void onSystemUnmuted() {
                            handleSystemUnmuted();
                        }
                    });
            muteMonitor.register();
        } catch (Throwable t) {
            Log.w(TAG, "SystemMuteMonitor setup failed, mute-key pause disabled: " + t);
            muteMonitor = null;
        }
    }

    /** 系统被静音 (用户按了静音键): 若正在播/期望播放则暂停, 并记住是"因静音而暂停"。 */
    private void handleSystemMuted() {
        boolean active = isPlaying() || playbackState.expectsPlayback();
        Log.i(TAG, "mute-key: system muted, active=" + active
                + " isPlaying=" + isPlaying() + " -> pause");
        if (!active) return;
        pausedByMute = true;
        pause();
    }

    /** 系统解除静音: 仅当此前是"因静音而暂停"时自动恢复, 避免用户手动暂停后被误恢复。 */
    private void handleSystemUnmuted() {
        Log.i(TAG, "mute-key: system unmuted, pausedByMute=" + pausedByMute);
        if (!pausedByMute) return;
        pausedByMute = false;
        play(PlaybackStateMachine.PlaybackOrigin.MEDIA_BUTTON);
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
                togglePause();
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
            // 唤醒手法要短暂改动系统 STREAM_MUSIC 音量, 所以必须同时知道「用户是否正在/刚刚
            // 动过音量或静音」, 否则 App 会与用户抢音量并解除用户静音 (2026-09-11 实车
            // #2/#3/#5)。观察器挂在主线程 handler 上, 服务销毁时在 release 里反注册。
            ampWakeStrategy = new GeelyAmpWakeStrategy(this, audioManager, progressHandler);
        }
    }

    private void initProgressTracker() {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (player != null && playbackState.isPrepared() && isPlaying()) {
                    // 睡眠定时到点：立刻暂停并解除定时（留着会在下次播放时又误触发一次）
                    if (SleepTimer.isExpired(android.os.SystemClock.elapsedRealtime(),
                            sleepStopAtElapsedMs)) {
                        sleepStopAtElapsedMs = SleepTimer.OFF;
                        Log.i(TAG, "sleep timer expired, pausing");
                        CrashMonitor.breadcrumb("play", "sleep timer expired -> pause");
                        pause();
                        if (stateChangeListener != null) {
                            stateChangeListener.onPlayStatus("睡眠定时到点，已停止播放");
                        }
                        progressHandler.postDelayed(this, PlaybackStateMachine.PROGRESS_TICK_MS);
                        return;
                    }
                    int currentMs = player.getCurrentPosition();
                    int totalMs = player.getDuration();

                    // 出声进展（缓冲指示的第二扇门）。特意不复用下面 stallTicks 的判定：那条
                    // 还要求 totalMs > 0，而 chunked 流恰好 totalMs=0——正是最需要这条新证据的
                    // 地方。这里只看位置动没动。
                    if (lastHeardPositionMs < 0) {
                        // 第一个 tick 只建基准，不算"前进"：否则刚 prepared 那一帧会把
                        // 「正在回到上次位置…」当场擦掉，也会让出声进展一直判成"在推进"
                        lastHeardPositionMs = currentMs;
                    } else if (currentMs != lastHeardPositionMs) {
                        lastHeardPositionMs = currentMs;
                        lastAudioAdvanceAtMs = SystemClock.elapsedRealtime();
                        // 位置真的走过 = 这一轮至少出过声。坏断点纠偏只在这种情况下才允许触发
                        resumeHeardAudio = true;
                        // 出声往前走了 = 这一轮的"连续放弃"链断了，计数必须清零，
                        // 否则下次真出事会报出一个虚高的 N
                        giveUpSongStreak = 0;
                        // 真的出声往前走了，"正在回到上次位置"这句话就到站
                        if (playStatusText != null) {
                            notifyPlayStatus(null);
                        }
                    }

                    // 起播后兜底纠偏 (2026-09-12 #1): prepared 回调可能报 0 时长 (流式/
                    // 实时转码拿不到 Content-Length), 系统 MediaPlayer 的真实时长也常在起播
                    // 后几个 tick 才收敛 —— 那两道按时长钳制的防线此时全都拦不住, 断点会
                    // 直接落到曲尾并在几秒后假 COMPLETED 跳下一首。这里用 tick 上的真实时长
                    // 再判一次, 命中即认定断点是坏的, 立刻从头重播本曲 (一次性, 不会死循环)。
                    if (resumeGuardTicks >= 0) {
                        resumeGuardTicks++;
                        if (PlaybackStateMachine.isBadResumeLanding(startedWithResumeMs > 0,
                                badResumeCorrected, totalMs, currentMs)) {
                            correctBadResumePoint("tick landed at tail", currentMs, totalMs);
                            progressHandler.postDelayed(this, PlaybackStateMachine.PROGRESS_TICK_MS);
                            return;
                        }
                        if (resumeGuardTicks * PlaybackStateMachine.PROGRESS_TICK_MS
                                > PlaybackStateMachine.COMPLETION_TOO_FAST_MS) {
                            // 已经正常出声超过兜底窗口: 断点是好的, 解除武装, 此后播完就算真播完
                            resumeGuardTicks = -1;
                        }
                    }

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

                    // 有效播放计数: 500ms tick 累计实际出声时长, 满 60s 落库一次。
                    // lastCountedGeneration 已计过的 generation 走 else 持续刷新挂钟,
                    // 保证暂停恢复后不会把暂停期误算进累计。
                    long countNowMs = SystemClock.elapsedRealtime();
                    if (pendingCountGeneration == playbackState.getGenerationId()) {
                        playAccumulatedMs += countNowMs - lastCountTickElapsedMs;
                        lastCountTickElapsedMs = countNowMs;
                        if (playAccumulatedMs >= VALID_PLAY_THRESHOLD_MS) {
                            pendingCountGeneration = -1L;
                            lastCountedGeneration = playbackState.getGenerationId();
                            SongItem counting = getCurrentSong();
                            if (counting != null) {
                                SongDao.getInstance(AudioPlayerService.this)
                                        .incrementPlayCountAsync(counting.getId());
                                Log.i(TAG, "Valid play counted (>=60s): " + counting.getName());
                            }
                        }
                    } else {
                        lastCountTickElapsedMs = countNowMs;
                    }

                    if (stateChangeListener != null) {
                        stateChangeListener.onProgressUpdate(currentMs, totalMs);
                    }

                    // 缓冲 / 预取 (2026-09-12)：跟着 500ms tick 驱动缓冲 % 上报与下一首预取判定。
                    // 均为只读 + 自愈式调用，不影响上面的进度 / 断点 / 看门狗治理。
                    maybeReportBuffering(currentMs, totalMs);
                    maybePrefetchNext(currentMs, totalMs);
                } else if (watchdogAction() == PlaybackStateMachine.WatchdogAction.REBUILD_STREAM
                        && !playbackState.isPreparing()) {
                    // 假播放兜底: onError 重试链耗尽或长时间无网络后播放器已死,
                    // UI 仍停留在「正在播放」(无声/进度冻结/歌词不动)。
                    // 指数退避自动重启当前曲目, 网络恢复后续播; 鉴权挂起时交给 auth 流程
                    preparingTicks = 0;
                    deadTicks++;
                    if (deadTicks >= deadRetryTicks
                            && pendingAuthSong == null && !authWaitInProgress
                            && !streamRetryBackoffPending) {
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
                    // 离开出声态就重建成败基准：暂停/起播前的位置不该被下一个新曲继承，
                    // 否则新曲第一 tick 一定"位置没变"，被误判成声音冻住
                    lastHeardPositionMs = -1;
                    lastAudioAdvanceAtMs = 0L;
                    preparingTicks = 0;
                    deadTicks = 0;
                    // 非出声态 (暂停/未准备) 持续刷新计数挂钟: 恢复播放后第一个
                    // tick 的增量才是真实的出声时长, 不会把暂停整段时间算进去
                    lastCountTickElapsedMs = SystemClock.elapsedRealtime();
                }
                // tick 周期必须与状态机里的常量一致: 兜底窗口按 tick 数换算成 ms
                progressHandler.postDelayed(this, PlaybackStateMachine.PROGRESS_TICK_MS);
            }
        };
        progressHandler.post(progressRunnable);
    }

    // ---------------- 缓冲 % 上报 / 下一首预取 (2026-09-12 缓冲/预取) ----------------

    /**
     * 发起播状态文字（幂等）。车机是 API 18，这里不能用 {@code java.util.Objects.equals}
     * （那是 API 19+），所以手写空值比较。
     */
    private void notifyPlayStatus(String text) {
        boolean same = (text == null) ? playStatusText == null : text.equals(playStatusText);
        if (same) return;
        playStatusText = text;
        if (stateChangeListener != null) {
            stateChangeListener.onPlayStatus(text);
        }
    }

    /**
     * 出声进展：播放位置在 {@link BufferingPolicy#AUDIO_ADVANCE_FRESH_MS} 内是否前进过。
     *
     * <p>本轮从未推进过（刚 prepared、或位置通道读不出数）时<b>返回 true</b>——那不是"声音冻住"
     * 的证据，此时判定权交回下载口径（prefill 期 percent 低，照样会显示缓冲中）。这样写是为了
     * 不把"读不到位置"这种设备/引擎差异凭空变成一条常驻的「缓冲中…」。
     */
    private boolean audioAdvancing() {
        return BufferingPolicy.audioAdvancedRecently(
                SystemClock.elapsedRealtime(), lastAudioAdvanceAtMs);
    }

    /**
     * 播放期间持续驱动缓冲 % 上报：prefill 门槛由播放器上报，进入播放后由本方法接管，
     * 每 tick 上报一次 percent + 是否仍处缓冲（稳定后 buffering=false，但 percent 照报，
     * 供 UI 双进度条的缓冲层持续推进）。跟着 500ms tick 走，频率天然不密。
     */
    private void maybeReportBuffering(int currentMs, int totalMs) {
        if (stateChangeListener == null) return;
        if (prefillInProgress) return; // 门槛期间由播放器上报，避免双报
        String url = currentRemoteUrl;
        if (url == null || url.length() == 0) return;
        HttpProxyServer proxy = HttpProxyServer.getInstance();
        int percent = proxy.getBufferedPercent(url);
        // 链路速率采样：累计偏移单调（重连续传也继续加），所以直接喂给治理器即可。
        //
        // 但**只有「总长已知、还没下完、且本曲时长已知」时才采**（2026-10-07 流畅档误判复盘）：
        // ① percent=100 表示这首歌已经整首落地，此后必然 0 新字节——那被 vc16 当成 0KB/s 的
        //    "带宽不足"，实测当晚 percent 87→100、lead 稳在 65s 的健康直播曲 9 秒后就被换档；
        // ② percent=-1 是 chunked 转码流（无 Content-Length），它<b>算不出本曲所需速率</b>，所以
        //    不能按无损档那道门来判；但链路的 socket 收字节速率照样量得出，换档之后必须继续量，
        //    否则档位出不去（见下面 smoothTierSampleIsMeasurable 那条分支）。两种采不了的此刻都要
        //    重开窗口基准，否则下一段真实下载会被接到一个过期基准上算出荒谬速率。
        // 所需速率 = 资源总长 / 本曲时长，即"不抽干"的最小值；治理器在它之上留 15% 上浮。
        // 口径只认 socket 真收字节（proxy.getDownloadedBytes 是 bufEnd，窗口饱和时它等于播放
        // 消耗速率，测出来必然"贴着所需但不及"——10-08 那个 107KB/s 的假缺口就是这么来的）
        long deliveredBytes = proxy.getSocketBytes(url);
        long contentLength = proxy.getContentLength(url);
        // 上游连接账 (2026-10-08)：同一首歌一条长连接取 5MB 实测 246KB/s，拆成 20 条独立请求只剩
        // 74KB/s。所以"链路只有 60~90KB/s"到底是链路还是我自己的重连，得靠这两个数判，不靠我猜。
        long[] upstreamCost = proxy.getUpstreamCost(url);
        if (upstreamCost != null && upstreamCost[0] != lastUpstreamConnections) {
            lastUpstreamConnections = upstreamCost[0];
            CrashMonitor.putContext("ctx_upstreamConns", String.valueOf(upstreamCost[0]));
            CrashMonitor.putContext("ctx_upstreamDiscardedBytes", String.valueOf(upstreamCost[1]));
            CrashMonitor.putContext("ctx_upstreamTtfbMaxMs", String.valueOf(upstreamCost[2]));
            CrashMonitor.breadcrumb("stream", "upstream conns=" + upstreamCost[0]
                    + " discarded=" + upstreamCost[1] + "B ttfbMax=" + upstreamCost[2]
                    + "ms socket=" + deliveredBytes + "B disk=" + upstreamCost[3]
                    + "B pos=" + currentMs + "ms");
            CrashMonitor.putContext("ctx_diskServedBytes", String.valueOf(upstreamCost[3]));
            if (prefetchedNextUrl != null) {
                // 预取源是另一条 URL，不在上面这笔账里；单列出来才能判"抢不抢带宽"
                long[] pf = HttpProxyServer.getInstance().getUpstreamCost(prefetchedNextUrl);
                if (pf != null) {
                    CrashMonitor.putContext("ctx_prefetchConns", String.valueOf(pf[0]));
                    CrashMonitor.putContext("ctx_prefetchSocketBytes", String.valueOf(pf[1]));
                }
            }
        }
        long requiredBytesPerSec = BufferingPolicy.requiredBytesPerSec(contentLength, totalMs);
        if (requiredBytesPerSec > 0L) {
            // 只在无损档上采得到"本曲不抽干所需速率"，它同时是回无损时唯一可用的分母
            lastLosslessRequiredBytesPerSec = requiredBytesPerSec;
        }
        if (deliveredBytes >= 0L) {
            boolean measurable = BufferingPolicy.bandwidthSampleIsMeasurable(
                    percent, contentLength, totalMs, proxy.sourceRingHasRoom(url));
            if (!measurable && isOnSmoothTier() && BufferingPolicy.smoothTierSampleIsMeasurable(
                    lastLosslessRequiredBytesPerSec, proxy.sourceRingHasRoom(url))) {
                // 流畅档 chunked 没有总长 ⇒ 上面那道门永远过不去。若就此 rebase，档位会被永久
                // 钉在 128k（回家也不会回升）。链路速率本身与总长无关，所以拿降档前实测到的
                // 所需速率当分母，继续判"有没有富余到够播无损"。
                rateGovernor.onProgress(SystemClock.elapsedRealtime(), deliveredBytes,
                        lastLosslessRequiredBytesPerSec);
            } else if (measurable) {
                rateGovernor.onProgress(SystemClock.elapsedRealtime(), deliveredBytes,
                        requiredBytesPerSec);
            } else {
                rateGovernor.rebaseWindow();
            }
            int deficits = rateGovernor.getDeficitConfirmations();
            if (deficits != lastDeficitConfirmations) {
                lastDeficitConfirmations = deficits;
                CrashMonitor.breadcrumb("play", "bitrate deficit #" + deficits
                        + " required=" + rateGovernor.getLastRequiredBytesPerSec()
                        + "B/s est=" + rateGovernor.getLastWindowBytesPerSec() + "B/s"
                        + " tier=" + StreamTier.label(getStreamTier()));
            }
            // 档位只由车主手选（StreamTier），切换那一刻在设置页已留痕；这里不再有自动档状态可监视
        }
        long remainingSeconds = totalMs > 0 ? Math.max(0, (totalMs - currentMs) / 1000) : -1L;
        long leadSeconds = computeLeadSeconds(percent, currentMs, totalMs);
        // 稳定后仍持续上报 (2026-09-13 双进度条): UI 文字指示靠 visibility 幂等隐藏,
        // 不会反复闪烁; 而缓冲进度层需要每 tick 的 percent 跟随下载头持续推进,
        // 像「视频加载」一样长条推进。跟着 500ms tick 走, 频率依然不密。
        boolean buffering = !BufferingPolicy.isBufferingStable(percent, leadSeconds, remainingSeconds,
                audioAdvancing());
        // 变化时打一行 (2026-09-13 双进度条实车调查): 实测 UI 偶现「缓冲 0%」卡住,
        // 用这行日志钉死 tick 查询到的 percent / lead 值, 定位是源查询错还是阈值判定错
        if (percent != lastReportedBufferPercent || buffering != lastReportedBuffering) {
            Log.i(TAG, "buffering report: percent=" + percent + " buffering=" + buffering
                    + " lead=" + leadSeconds + "s remaining=" + remainingSeconds + "s");
            // 落盘: 缓冲状态跳变是关键时间线, 重启后仍可 adb pull 取证 (2026-09-15)
            CrashMonitor.breadcrumb("buffer", "report percent=" + percent
                    + " buffering=" + buffering + " lead=" + leadSeconds
                    + "s remaining=" + remainingSeconds + "s");
            lastReportedBufferPercent = percent;
            lastReportedBuffering = buffering;
            lastBufferDiagAtMs = SystemClock.elapsedRealtime();
        } else if (buffering) {
            // percent 卡住不动时 (最典型的"一直缓冲中"现场) 也要每 30s 留一行心跳,
            // 否则变化检测会让最需要证据的时段一行日志都没有 (2026-09-15 缓冲失败调查)
            long now = SystemClock.elapsedRealtime();
            if (now - lastBufferDiagAtMs >= 30000L) {
                lastBufferDiagAtMs = now;
                CrashMonitor.breadcrumb("buffer", "STUCK heartbeat percent=" + percent
                        + " lead=" + leadSeconds + "s remaining=" + remainingSeconds
                        + "s pos=" + currentMs + "ms");
            }
        }
        stateChangeListener.onBufferingUpdate(percent, buffering);
    }

    /**
     * 领先秒数 = (已下载百分比 - 已播百分比) × 总时长。用现成的 getBufferedPercent（整首已下载比例）
     * 减去播放进度比例即得当前曲「领先播放头多少秒」，总长 / 百分比未知时返回 -1。
     */
    private long computeLeadSeconds(int percent, int currentMs, int totalMs) {
        if (percent < 0 || totalMs <= 0) return -1L;
        int playedPct = (int) (currentMs * 100L / totalMs);
        int leadPct = Math.max(0, percent - playedPct);
        return (long) leadPct * (totalMs / 1000) / 100L;
    }

    /**
     * 带宽防御式预取下一首：仅顺序模式下、当前曲缓冲健康或接近结尾时触发；一旦当前曲饥饿 /
     * 正处于起播门槛，立即作废在跑的预取并让位（当前曲永远优先）。同一首只预取一次。
     * 预取走 {@link HttpProxyServer#prefetch}（小窗、不 addRef），将来真正播放时命中同一个源。
     */
    private void maybePrefetchNext(int currentMs, int totalMs) {
        if (playlist == null || playlist.size() < 2) return;
        if (currentPlayMode != MODE_SEQUENCE) return; // 随机 / 单曲重复预取意义不大（后者下一首=当前曲）
        if (currentIndex < 0 || currentIndex >= playlist.size()) return;
        SongItem current = getCurrentSong();
        if (current == null) return;

        String url = currentRemoteUrl;
        HttpProxyServer proxy = HttpProxyServer.getInstance();
        int percent = (url != null && url.length() > 0) ? proxy.getBufferedPercent(url) : -1;
        boolean starving = (url != null && url.length() > 0) && proxy.isSourceStarving(url);
        // chunked 流畅档没有 Content-Length ⇒ 播放器时长报 0 ⇒ remainingSeconds 会是 -1，
        // 「接近结尾」这道门就永远开不了——一旦降档，后面每一首都再无预取。这里用入库元数据
        // 兜底只为算出"还剩多少秒"，不得据此放行 seek（见 durationForDerivation 的说明）。
        long durationBasisMs = BufferingPolicy.durationForDerivation(totalMs, current.getDurationMs());
        long remainingSeconds = durationBasisMs > 0
                ? Math.max(0, (durationBasisMs - currentMs) / 1000) : -1L;
        long leadSeconds = computeLeadSeconds(percent, currentMs, (int) Math.min(durationBasisMs,
                Integer.MAX_VALUE));

        // 让位当前曲：饥饿 / 断流 / 正在 prefill 时，作废在跑的预取（恢复健康后可重试）
        if ((starving || prefillInProgress) && prefetchedNextSongId != null) {
            abortPrefetchById(prefetchedNextSongId);
            prefetchedNextUrl = null;
            Log.w(TAG, "prefetch yielded to current song (starving=" + starving
                    + " prefill=" + prefillInProgress + ")");
            long[] pfCost = HttpProxyServer.getInstance().getUpstreamCost(prefetchedNextUrl);
            CrashMonitor.breadcrumb("buffer", "prefetch yielded starving=" + starving
                    + " prefill=" + prefillInProgress + " song=" + current.getName()
                    + (pfCost == null ? "" : " pfConns=" + pfCost[0] + " pfSocket=" + pfCost[1]
                            + "B pfDisk=" + pfCost[3] + "B"));
            prefetchedNextSongId = null;
            return;
        }

        int nextIndex = (currentIndex + 1) % playlist.size();
        SongItem next = playlist.get(nextIndex);
        if (next == null || next.getId() == null) return;
        boolean already = next.getId().equals(prefetchedNextSongId);
        if (!BufferingPolicy.shouldPrefetchNext(leadSeconds, remainingSeconds,
                starving, prefillInProgress, already)) {
            return;
        }
        String nextUrl = resolvePlayUrl(JellyfinApiClient.getInstance(), next.getId(), next.getStreamUrl());
        if (nextUrl == null || nextUrl.length() == 0) return;
        proxy.prefetch(nextUrl);
        prefetchedNextSongId = next.getId();
        prefetchedNextUrl = nextUrl;
        Log.i(TAG, "prefetch next triggered: " + next.getName() + " percent=" + percent
                + " lead=" + leadSeconds + "s remaining=" + remainingSeconds + "s");
        // 面包屑是必需的：预取只有 Log.i 时，manual_diag 上报里完全看不出它有没有跑过，
        // 车主反馈"超前加载不行"时无从判据 (2026-10-08)。
        CrashMonitor.breadcrumb("buffer", "prefetch next=" + next.getName()
                + " percent=" + percent + " lead=" + leadSeconds + "s"
                + " remaining=" + remainingSeconds + "s"
                + " tier=" + StreamTier.label(getStreamTier()));
    }

    /** 切歌 / 换列表 / 手动跳歌：作废与新一首无关的旧预取源，避免留着占内存 / 带宽。 */
    private void invalidateStalePrefetch(SongItem newSong) {
        String pfId = prefetchedNextSongId;
        prefetchedNextSongId = null;
        prefetchedNextUrl = null;
        if (pfId == null) return;
        if (newSong != null && pfId.equals(newSong.getId())) {
            return; // 预取的正是这首：保留，obtainSource 会命中复用，无缝起播
        }
        abortPrefetchById(pfId);
    }

    private void abortPrefetchById(String songId) {
        if (songId == null) return;
        String pfUrl = streamUrlForSongId(songId);
        if (pfUrl != null && pfUrl.length() > 0) {
            HttpProxyServer.getInstance().abortPrefetch(pfUrl);
        }
    }

    /** 缓存目录句柄：设置页要显示占用与一键清空。 */
    public static java.io.File streamCacheDir(android.content.Context ctx) {
        return new java.io.File(ctx.getCacheDir(), "stream-cache");
    }

    /**
     * 把四条回退闸从偏好灌进 {@link com.ktools.zspacecarplayer.player.stream.StreamTuning}，
     * 并据此挂上/摘下磁盘缓存 (2026-10-08)。启动时调一次，设置页每次切换后再调一次。
     *
     * <p>默认全开（= 本批新行为）。关掉任何一项都退回 vc20 的既有行为，<b>只影响下一首</b>：
     * 正在播的流不能中途改规则。
     */
    public void applyStreamTuningFromPrefs() {
        android.content.SharedPreferences sp =
                getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean tuning = sp.getBoolean(PREF_KEY_STREAM_TUNING, true);
        boolean disk = sp.getBoolean(PREF_KEY_DISK_CACHE, true);
        com.ktools.zspacecarplayer.player.stream.StreamTuning.configure(
                tuning ? com.ktools.zspacecarplayer.player.stream.StreamTuning
                        .DEFAULT_IDLE_FILL_GRACE_MS : 0L,
                tuning, tuning, disk);
        HttpProxyServer.getInstance().configureDiskCache(
                disk ? streamCacheDir(this) : null,
                BufferingPolicy.DISK_CACHE_LIMIT_BYTES);
        CrashMonitor.breadcrumb("play", "stream tuning: guards=" + tuning
                + " diskCache=" + disk);
    }

    /** 回退闸当前状态（设置页显示用）。 */
    public boolean isStreamTuningEnabled() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getBoolean(PREF_KEY_STREAM_TUNING, true);
    }

    /** 磁盘缓存当前状态（设置页显示用）。 */
    public boolean isDiskCacheEnabled() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getBoolean(PREF_KEY_DISK_CACHE, true);
    }

    /** 设置页切换回退闸：立刻生效（下一首起），并落到偏好。 */
    public void setStreamTuningEnabled(boolean enabled) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_KEY_STREAM_TUNING, enabled).apply();
        applyStreamTuningFromPrefs();
    }

    /** 设置页切换磁盘缓存；关掉时顺手把已有缓存清掉，别在设备上留没人用的字节。 */
    public void setDiskCacheEnabled(boolean enabled) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_KEY_DISK_CACHE, enabled).apply();
        if (!enabled) {
            StreamDiskCache.clearDir(streamCacheDir(this));
        }
        applyStreamTuningFromPrefs();
    }

    /** 当前播放缓存占用（字节）。 */
    public long getStreamCacheBytes() {
        return StreamDiskCache.dirBytes(streamCacheDir(this));
    }

    /** 清空播放缓存，返回删掉的字节数。 */
    public long clearStreamCache() {
        return StreamDiskCache.clearDir(streamCacheDir(this));
    }

    /** 无损档？只有它才需要在放弃时提示"可以切流畅"。 */
    private boolean isLosslessTier() {
        return getStreamTier() == StreamTier.LOSSLESS;
    }

    /** 车主当前选的是流畅档？测速的不可采分支要靠它，不能靠已删掉的自动档状态。 */
    private boolean isOnSmoothTier() {
        return getStreamTier() == StreamTier.SMOOTH;
    }

    /** 当前生效的音质档位（车主手选，默认无损）。 */
    public int getStreamTier() {
        return com.ktools.zspacecarplayer.player.stream.StreamTier.clamp(
                getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .getInt(PREF_KEY_STREAM_TIER,
                                com.ktools.zspacecarplayer.player.stream.StreamTier.DEFAULT));
    }

    /** 设置页切换档位后调用：只影响**下一首**，正在播的这首不动（中途换 URL 必断音）。 */
    public void setStreamTier(int tier) {
        int clamped = com.ktools.zspacecarplayer.player.stream.StreamTier.clamp(tier);
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_KEY_STREAM_TIER, clamped).apply();
        CrashMonitor.breadcrumb("play", "tier set by user: "
                + com.ktools.zspacecarplayer.player.stream.StreamTier.label(clamped));
    }

    /**
     * 起播/预取实际用的 URL：无损档沿用该曲入库时定下的传输方式，流畅档才换成服务端转码
     * （实测 256kbps，见 {@link com.ktools.zspacecarplayer.player.stream.StreamTier}）。
     * 档位由车主在设置页手选，<b>没有任何自动切换路径</b>（自动降档已被否决）。
     *
     * <p>只影响**下一首**：中途换 URL 一定断音。代价说清楚——若档位恰好在"预取之后、消费之前"翻转，
     * 作废会打空、那次预取白做（由 {@code MAX_SOURCES} 淘汰兜住），不会播错内容。
     */
    private String resolvePlayUrl(JellyfinApiClient client, String itemId, String cachedStreamUrl) {
        int tier = getStreamTier();
        String url = client.getStreamUrlForTier(itemId, cachedStreamUrl, tier);
        if (tier == StreamTier.SMOOTH) {
            CrashMonitor.breadcrumb("play", "start on smooth tier (user picked) id=" + itemId
                    + " est=" + rateGovernor.getLastWindowBytesPerSec() + "B/s");
        }
        return url;
    }

    /**
     * 只有 songId 时复原该曲的传输方式。
     *
     * 预取源是按 URL 登记与命中的，作废时必须用与预取时完全一致的 URL（含下混与否），
     * 否则作废打空、旧的 8MB 预取缓冲白占着内存与带宽。
     */
    private String streamUrlForSongId(String songId) {
        JellyfinApiClient client = JellyfinApiClient.getInstance();
        if (playlist != null) {
            for (int i = 0; i < playlist.size(); i++) {
                SongItem s = playlist.get(i);
                if (s != null && songId.equals(s.getId())) {
                    return resolvePlayUrl(client, songId, s.getStreamUrl());
                }
            }
        }
        return client.getStreamUrl(songId, true);
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
        shuffleHistory.reset();
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
        shuffleHistory.reset();
        if (newSongs == null) return;
        SongItem current = getCurrentSong();
        this.playlist = new ArrayList<>(newSongs);
        if (current != null) {
            this.currentIndex = playlist.indexOf(current);
        } else {
            this.currentIndex = -1;
        }
        // 上报里必须反映刷新后的真实位置，否则 playlistIndex 会一直停在 setPlaylist 那一刻
        CrashMonitor.putContext("playlistSize", playlist.size());
        CrashMonitor.putContext("playlistIndex", currentIndex);
        CrashMonitor.breadcrumb("play", "updatePlaylist size=" + playlist.size()
                + " index=" + currentIndex);
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
        startPlaybackWithSeek(song, sanitizeSeekMs(song, startMs), origin);
    }

    /**
     * 起播 seek 目标钳制 (2026-09-09 实车定位):
     * song_progress 表存过超长断点 (如 234s 断点配 226s 歌曲), 越界 seek 会把
     * currentPresentationTimeUs 锚到末尾 (进度条瞬跳末尾), native 在文件尾快速
     * EOS 触发假 COMPLETED 乱切歌。断点贴近/越过元数据时长 (<=3s 余量) 时按
     * 从头播处理; 时长元数据缺失时保守放行 (Jellyfin 曲目基本都带 RunTimeTicks)。
     */
    static int sanitizeSeekMs(SongItem song, int startMs) {
        if (startMs < 0) return -1;
        long durMs = song != null ? song.getDurationMs() : 0L;
        if (PlaybackStateMachine.isEffectivelyAtEnd(durMs, startMs)) {
            Log.w(TAG, "Drop end-of-track resume point: id=" + (song != null ? song.getId() : null)
                    + " name=" + (song != null ? song.getName() : null)
                    + " saved=" + startMs + "ms metaDuration=" + durMs + "ms -> replay from start");
            return -1;
        }
        return startMs;
    }

    /**
     * 取消尚未到点的同曲重试退避。{@code startPlayback} 是所有起播路径的唯一入口，在它开头调一次
     * 就同时覆盖用户切歌 / 自动下一首 / 上一首 / 看门狗重建 / GIVE_UP，比逐个复位点挂更可靠——
     * 迟到的重放打到另一首歌上是不可接受的。服务释放时也必须调，否则延迟任务残留在 handler 上。
     */
    private void cancelStreamRetryBackoff() {
        if (streamRetryBackoffRunnable != null) {
            if (progressHandler != null) {
                progressHandler.removeCallbacks(streamRetryBackoffRunnable);
            }
            streamRetryBackoffRunnable = null;
        }
        streamRetryBackoffPending = false;
    }

    private void replayPendingSong(SongItem song, int startMs,
                                   PlaybackStateMachine.PlaybackOrigin origin) {
        startPlaybackWithSeek(song, sanitizeSeekMs(song, startMs), origin);
    }

    private void startPlaybackWithSeek(SongItem song, int startMs,
                                       PlaybackStateMachine.PlaybackOrigin origin) {
        pendingSeekMs = startMs;
        startPlayback(song, origin);
    }

    /** Starts a new generation and performs fade -> pause -> reset -> prepareAsync. */
    private void startPlayback(final SongItem song,
                               final PlaybackStateMachine.PlaybackOrigin origin) {
        // 任何一轮新起播都作废尚未到点的退避重放 (含退避链自己这次，标志已在 Runnable 内先行清掉)
        cancelStreamRetryBackoff();
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
        // 新一轮起播先把"上一次说过的那句"作废：{@code playStatusText} 是 notifyPlayStatus 的
        // 幂等键，不清就等于把下一首的同款提示也吞掉（UI 那边在 onSongChanged 里已经自己清了）
        playStatusText = null;
        // 用户主动点的歌给一次全新的"假播完救援"机会与一次全新的连续放弃计数；
        // 自动链路不换（同一首被自动重试时若每次都放行救援，就会变成 prepare→截断→prepare 的无声循环）
        if (origin == PlaybackStateMachine.PlaybackOrigin.USER_UI
                || origin == PlaybackStateMachine.PlaybackOrigin.MEDIA_BUTTON) {
            truncationRetriedTrackId = null;
            giveUpSongStreak = 0;
        }
        cancelSeekTimeout();
        activeSeekOperationId = -1L;
        queuedSeekOperationId = -1L;
        queuedSeekMs = -1;
        transientPausedAtMs = -1L;
        // 坏断点兜底纠偏跟着 generation 走 (2026-09-12 #1): 新一轮起播一律先解除武装,
        // 只有 handlePrepared 真的下发了断点 seek 才重新武装, 上一首的纠偏状态不得外溢
        startedWithResumeMs = -1;
        resumeGuardTicks = -1;
        resumeHeardAudio = false;
        badResumeCorrected = false;

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
                    // 传输方式由该曲入库时的码率裁定决定（多声道无损走服务端下混），此处只现取 token
                    String urlToPlay = resolvePlayUrl(client, song.getId(), song.getStreamUrl());
                    CrashMonitor.putContext("streamUrl", urlToPlay);
                    // 缓冲 / 预取 (2026-09-12)：记录当前曲远端 URL 供 percent 查询与预取命中；
                    // 作废与新一首无关的旧预取源；重新武装起播门槛态
                    currentRemoteUrl = urlToPlay;
                    invalidateStalePrefetch(song);
                    prefillInProgress = true;
                    // v3: 本地回环代理 + 环形缓冲，抵御公网串流抖动（消除“播 2s 停 1s”式 underrun）
                    // 流式 FLAC 容器不自报时长，先把入库已知时长交给播放器兜底，
                    // 否则 getDuration()=0 会让剩余时长/预取/缓冲稳定判定全线失效
                    player.setKnownDurationMs(song.getDurationMs());
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
                        // 屏幕上不给异常原文 (2026-10-08 L5 收口)。这里漏出去过
                        // "java.io.IOException: Failed to allocate component instance" 这种句子，
                        // 车主读到只是一串英文，既不知道发生了什么也不知道该做什么。
                        // 异常本身已经在上面的 Log 与 breadcrumb 里，取证不损失。
                        SongItem failed = getCurrentSong();
                        stateChangeListener.onError("无法播放"
                                + (failed != null ? "《" + failed.getName() + "》" : "")
                                + "，请重试或换一首");
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
                final SongItem finishedSong = getCurrentSong();
                final int realDurationMs = safePlayerDurationMs();
                // 兜底之一 (2026-09-12 #1): 带断点起播后没出声几秒就 COMPLETED ——
                // 断点其实贴在真实曲尾, 而 prepared 时真实时长未知/与元数据不符,
                // 让保存侧清洗与起播侧钳制这两道「按时长」的防线全部失效。
                // 这不是「播完了」, 绝不能自动跳下一首 (用户观感就是切歌后直接到曲尾、
                // 甚至立刻又跳一首), 必须清掉脏断点并从头重播本曲。一次性, 不会死循环。
                long playedMs = resumeGuardTicks >= 0
                        ? resumeGuardTicks * PlaybackStateMachine.PROGRESS_TICK_MS
                        : Long.MAX_VALUE;
                if (PlaybackStateMachine.shouldReplayInsteadOfAdvance(
                        startedWithResumeMs > 0, playedMs, badResumeCorrected, resumeHeardAudio)) {
                    correctBadResumePoint("completed too fast after resume",
                            safePlayerPositionMs(), realDurationMs);
                    return;
                }
                // 兜底之三 (2026-10-07): 「假播完」。本地代理在总长未知时回复的是没有
                // Content-Length、也没有 chunked 的 close-delimited 流，"连接关了"就是那个响应的
                // 合法结束——下载中途放弃时抽取器看到的是曲尾，日志里只是一句
                // 「MediaCodec reached end of audio stream」。真车当晚: 219,493ms 的歌在 65,802ms
                // "播完"、续播点被当听完清零、静默跳下一首，屏幕上没有任何解释。
                // 位置取最后一次 tick 的值（此刻再问播放器可能已经归零，而"播到哪儿了"是唯一的证据）。
                long endedAtMs = lastTickPositionMs >= 0
                        ? lastTickPositionMs : safePlayerPositionMs();
                long metaMs = finishedSong != null ? finishedSong.getDurationMs() : 0L;
                boolean abortDeclared = false;
                if (currentRemoteUrl != null && currentRemoteUrl.length() > 0) {
                    abortDeclared = HttpProxyServer.getInstance().isSourceFatalEver(currentRemoteUrl);
                }
                if (PlaybackStateMachine.isPrematureCompletion(
                        metaMs, endedAtMs, realDurationMs, abortDeclared)) {
                    final String trackId = finishedSong != null ? finishedSong.getId() : null;
                    final String truncatedName = finishedSong != null ? finishedSong.getName() : "当前曲目";
                    CrashMonitor.breadcrumb("play", "premature eos at " + endedAtMs + "ms of "
                            + metaMs + "ms song=" + truncatedName);
                    if (trackId != null && !trackId.equals(truncationRetriedTrackId)) {
                        // 第一次: 按 I/O 错误处理，带断点重试本曲（续播点绝不能清零）
                        truncationRetriedTrackId = trackId;
                        pendingSeekMs = (int) Math.min(endedAtMs, (long) Integer.MAX_VALUE);
                        handlePlayerError(MediaPlayer.MEDIA_ERROR_IO,
                                "stream truncated before its end", generation);
                        return;
                    }
                    // 救过一次还在同一处被截断: 不再原地循环，跳下一首，但要说清原因、
                    // 并且**不清零**续播点——这首歌根本没听完，下次网络好了该从原处接上
                    giveUpSongStreak++;
                    if (stateChangeListener != null) {
                        stateChangeListener.onError(PlaybackStateMachine.describeGiveUp(
                                giveUpSongStreak, truncatedName, false, isLosslessTier()));
                    }
                    if (currentPlayMode == MODE_SINGLE_REPEAT) {
                        return; // 单曲循环下不跳歌，等下一次用户操作或看门狗
                    }
                    playNext(PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
                    return;
                }
                // 真播完: 该曲断点必须清零 (2026-09-12 #1 残留根因)。落库是 5s 节流的,
                // 自然播完时库里必然残留一个 [时长-5.5s, 时长) 的贴尾位置; 不清零的话
                // 下次点这首歌就会被 seek 到曲尾。放在服务侧是为了后台播放 (UI 未绑定)
                // 时同样生效, 不依赖 MainActivity 的节流写入。
                if (finishedSong != null && finishedSong.getId() != null) {
                    Log.i(TAG, "Track completed, clear resume point: id=" + finishedSong.getId()
                            + " name=" + finishedSong.getName()
                            + " metaDuration=" + finishedSong.getDurationMs()
                            + "ms realDuration=" + realDurationMs + "ms decision=progress->0");
                    SongDao.getInstance(AudioPlayerService.this)
                            .saveSongProgress(finishedSong.getId(), 0);
                }
                if (currentPlayMode == MODE_SINGLE_REPEAT) {
                    startPlaybackWithSeek(finishedSong, -1,
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

            @Override
            public void onBufferingUpdate(int percent, boolean buffering) {
                handleBufferingUpdate(percent, buffering, generation);
            }
        });
    }

    /**
     * 播放器（v3 prefill 门槛）上报的缓冲进度转发给 UI，并校正 prefill 状态。
     * 回调已由播放器切回主线程，这里直接转发（2026-09-12 缓冲/预取）。
     */
    private void handleBufferingUpdate(int percent, boolean buffering, long generation) {
        if (!playbackState.isCurrentGeneration(generation)) return;
        prefillInProgress = buffering;
        if (stateChangeListener != null) {
            stateChangeListener.onBufferingUpdate(percent, buffering);
        }
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

    /**
     * 车机「静音键」绑定的播放/暂停切换入口 (2026-09-11 实车 #6)。
     * 前台由 MainActivity.dispatchKeyEvent 直接调, 后台由 MediaButtonReceiver 经
     * ACTION_TOGGLE_PAUSE 走 onStartCommand —— 两条路最终都落到同一个 playOrPause 判定,
     * 不另造状态机分支, 语义与方向盘 PLAY_PAUSE 完全一致。
     */
    public void togglePause() {
        Log.i(TAG, "togglePause from mute/media key");
        playOrPause(PlaybackStateMachine.PlaybackOrigin.MEDIA_BUTTON);
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
        // 用户/媒体键主动播放: 清除"因静音暂停"标记; 若系统仍处于静音则主动解静音,
        // 否则会出现"恢复播放却无声"(静音是 ROM 置的, 播放键不会自动解除)。
        pausedByMute = false;
        if (muteMonitor != null
                && muteMonitor.readMuteState() == GeelyAmpWakeStrategy.MuteState.MUTED) {
            Log.i(TAG, "play while system muted -> unmute STREAM_MUSIC");
            muteMonitor.setMusicMuted(false);
        }
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
                // 有效播放计数 (2026-09-10): 起播即计会把「点了就切」也 +1。
                // 改为标记待计 generation, 由 progress tick 累计实际出声时长,
                // 满 60s 才落库; 暂停不累计, 同 generation 暂停恢复续算。
                if (pendingCountGeneration != generation) {
                    pendingCountGeneration = generation;
                    playAccumulatedMs = 0L;
                }
                lastCountTickElapsedMs = SystemClock.elapsedRealtime();
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
        CrashMonitor.breadcrumb("buffer", "stall recover pos=" + posMs + "ms song=" + song.getName());
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
            } else if (result == GeelyAmpWakeStrategy.WakeResult.SKIPPED
                    && ampWakeStrategy.isUserIntentBlocked()) {
                // 用户静音/改过音量: 唤醒让位是设计要求, 但实车必须能从日志确认这条路走到了
                Log.i(TAG, "Amp wake yielded to user volume/mute intent, key=" + wakeKey);
            }
            // WOKEN 也要安排跟进重试: 冷启动/install 后功放 DSP 可能晚于首次 nudge 才就绪,
            // 只唤醒一次会让同一 episode 内永远无声 (2026-09-03 实车部署复现)。
            // 终态 SKIPPED (音量 0 红线 / 静音标志已置位或读不到 / 用户改过音量 / 重试窗口已过)
            // 才停止, 防无限重试由 strategy 窗口兜底。
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
     * 用户在此期间改过音量或按了静音则立刻放弃整条重试链 (#5: 唤醒重试必须让位于用户意图,
     * 否则用户调低音量后 App 又把音量写回旧值)。
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
                if (ampWakeStrategy.isUserIntentBlocked()) {
                    Log.i(TAG, "Amp wake retry dropped, user owns volume/mute now, key=" + wakeKey);
                    return;
                }
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
            // 先走来路：刚被"上一首"退回去过，就应当能"下一首"走回原处，而不是又随机一次
            int from = shuffleHistory.canGoForward() ? shuffleHistory.goForward()
                    : new Random().nextInt(playlist.size());
            if (currentIndex >= 0 && from != currentIndex) {
                shuffleHistory.pushBack(currentIndex);
            }
            currentIndex = from;
        } else {
            currentIndex = (currentIndex + 1) % playlist.size();
        }
        streamRetryCount = 0;
        silentErrorStreak = 0;
        playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        CrashMonitor.putContext("playlistIndex", currentIndex);
        startPlaybackWithSeek(playlist.get(currentIndex), -1, origin);
    }

    public void playPrevious() {
        playPrevious(PlaybackStateMachine.PlaybackOrigin.USER_UI);
    }

    private void playPrevious(PlaybackStateMachine.PlaybackOrigin origin) {
        if (playlist.isEmpty()) return;
        if (currentPlayMode == MODE_RANDOM) {
            if (shuffleHistory.canGoBack()) {
                // 随机模式下"上一首"必须是<b>回到刚才那首</b>。旧实现这里也是
                // new Random().nextInt(size)，按下去会跳到任意一首（多数是往后），
                // 用户观感就是"随机模式上一首坏了" (2026-10-08 T1 基准补齐)
                currentIndex = shuffleHistory.goBack(currentIndex);
            } else {
                currentIndex = new Random().nextInt(playlist.size());
            }
        } else {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
        }
        streamRetryCount = 0;
        silentErrorStreak = 0;
        playbackState.setDesiredPlayback(PlaybackStateMachine.DesiredPlayback.PLAY);
        CrashMonitor.putContext("playlistIndex", currentIndex);
        startPlaybackWithSeek(playlist.get(currentIndex), -1, origin);
    }

    /**
     * 拔出耳机/断开外放即暂停 (2026-10-08)。T1 全都有这条，缺它的后果很具体：
     * 车上拔了耳机音乐继续从功放出来，或者用户把耳机拔了以为停了、回来发现还在响。
     *
     * <p>用 {@link AudioManager#ACTION_AUDIO_BECOMING_NOISY}： wired 耳机拔出与多数
     * A2DP 断开会发它；注册在运行时（4.3 上清单里声明无效）。
     */
    private void registerAudioNoisyReceiver() {
        if (audioNoisyReceiver != null) {
            return;
        }
        audioNoisyReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (intent == null || !AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    return;
                }
                if (!isPlaying()) {
                    return; // 本来就没在播，别把"暂停"提示打在静止态上
                }
                Log.i(TAG, "audio output became noisy, pausing playback");
                CrashMonitor.breadcrumb("play", "audio noisy -> pause");
                pause();
                if (stateChangeListener != null) {
                    stateChangeListener.onPlayStatus("已断开扬声器，播放暂停");
                }
            }
        };
        try {
            registerReceiver(audioNoisyReceiver,
                    new android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception e) {
            Log.w(TAG, "register noisy receiver failed", e);
            audioNoisyReceiver = null;
        }
    }

    /** 设睡眠定时：minutes<=0 即取消。到点是<b>立即暂停</b>（理由见 {@link SleepTimer}）。 */
    public void setSleepTimerMinutes(int minutes) {
        long now = android.os.SystemClock.elapsedRealtime();
        sleepStopAtElapsedMs = SleepTimer.deadlineFor(now, minutes);
        CrashMonitor.breadcrumb("play", minutes > 0
                ? ("sleep timer set " + minutes + "min") : "sleep timer cancelled");
    }

    /** 定时剩余毫秒；0 表示未开或已结束。设置页轮询它显示倒计时。 */
    public long getSleepRemainingMs() {
        return SleepTimer.remainingMs(android.os.SystemClock.elapsedRealtime(),
                sleepStopAtElapsedMs);
    }

    /** 是否已设睡眠定时。 */
    public boolean isSleepTimerActive() {
        return sleepStopAtElapsedMs > SleepTimer.OFF;
    }

    public void seekTo(final int ms) {
        // 用户手动 seek 后解除坏断点兜底 (2026-09-12 #1): 落点由用户负责, 他刻意拖到
        // 曲尾时不能被判成「断点坏了」而从零重播 —— 兜底只针对自动断点续播
        startedWithResumeMs = -1;
        resumeGuardTicks = -1;
        // 拖进度条会让下载头的累计偏移跳变：窗口基准必须作废重建，否则会算出假的高速率（正向 seek）
        // 或负速率（往回拖）。只作废窗口，不推翻档位结论——档位是链路属性，seek 不改链路。
        rateGovernor.rebaseWindow();
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
            // 未准备时的 seek 会转成「带位置重新起播」, 必须与点歌路径走同一道清洗:
            // 这里的 ms 可能来自上一首遗留的 SeekBar 值 (切歌瞬间 max 还是旧曲时长),
            // 越过新曲时长就是「切歌直接到曲尾」(2026-09-12 #1)
            startPlaybackWithSeek(song, sanitizeSeekMs(song, ms),
                    PlaybackStateMachine.PlaybackOrigin.USER_UI);
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

    /**
     * 播放器当前的真实时长 (ms), 未知/异常返回 0 (2026-09-12 #1)。
     * 落库清洗必须优先用它而不是 Jellyfin 元数据: 元数据缺失或比转码流偏大时,
     * 贴尾脏值会被判成合法断点存进 song_progress。
     */
    public int getCurrentRealDurationMs() {
        return safePlayerDurationMs();
    }

    /** onCompletion 时引擎状态已切到 READY, 不能走 isPrepared() 门槛, 单独安全读取。 */
    private int safePlayerPositionMs() {
        if (player == null) return 0;
        try {
            return player.getCurrentPosition();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int safePlayerDurationMs() {
        if (player == null) return 0;
        try {
            return player.getDuration();
        } catch (Exception ignored) {
            return 0;
        }
    }


    /**
     * 坏断点的最后一道兜底 (2026-09-12 #1): 起播后发现自己落在贴尾, 就清库 + 从头重播本曲。
     *
     * 前面所有防线都要靠「时长」判定, 而流式/实时转码源在起播瞬间时长可能是 0 或与元数据
     * 不符 —— 这条兜底只认「实际发生了什么」(tick 已贴尾 / 断点起播后几秒就 COMPLETED),
     * 因此时长完全未知时也有效。必须同时把库里的脏断点清零, 否则下次点这首歌会再踩一次。
     * 一次性 (badResumeCorrected), 且重播时不带断点 ⇒ 新一轮不会再次武装, 不可能死循环。
     */
    private void correctBadResumePoint(String reason, int positionMs, int realDurationMs) {
        SongItem song = getCurrentSong();
        int badResumeMs = startedWithResumeMs;
        badResumeCorrected = true;
        startedWithResumeMs = -1;
        resumeGuardTicks = -1;
        if (song == null) return;
        Log.w(TAG, "Bad resume point corrected (" + reason + "): id=" + song.getId()
                + " name=" + song.getName()
                + " saved=" + badResumeMs + "ms position=" + positionMs
                + "ms metaDuration=" + song.getDurationMs()
                + "ms realDuration=" + realDurationMs + "ms decision=replay-from-start");
        CrashMonitor.breadcrumb("play", "bad resume corrected reason=" + reason
                + " saved=" + badResumeMs + " pos=" + positionMs
                + " realDur=" + realDurationMs + " song=" + song.getName());
        // 脏断点必须同步清掉: 它是这次「切歌就到曲尾」的源头, 留着下次点歌必然复现
        if (song.getId() != null) {
            SongDao.getInstance(this).saveSongProgress(song.getId(), 0);
        }
        lastTickTrackId = song.getId();
        lastTickPositionMs = 0;
        startPlaybackWithSeek(song, -1, PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
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
        // v3 自研 DSP 不依赖系统 Equalizer， preset 由 NativeDsp.Equalizer.h 硬编码。
        // 顺序必须与 C++ 层 setPreset switch case 严格一致。
        return Arrays.asList(
                "原声 (Flat)",
                "古典 (Classical)",
                "流行 (Pop)",
                "摇滚 (Rock)",
                "人声 (Vocal)",
                "爵士 (Jazz)",
                "舞曲 (Dance)",
                "金属 (Metal)",
                "蓝调 (Blues)",
                "电子 (Electronic)",
                "电音舞曲 (EDM)",
                "嘻哈 (Hip-Hop)",
                "男声 (Male Vocal)",
                "女声 (Female Vocal)",
                "播客对话 (Speech)",
                "车载优化 (Car)",
                "低音增强 (Bass Boost)"
        );
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
        // 起播 prefill 门槛已通过（或超时放行）：解除门槛态，此后缓冲上报交给 progress tick
        prefillInProgress = false;
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
        SongItem preparedSong = getCurrentSong();
        // prepared 回调带回的是播放器真实时长, 用它再钳一次 (2026-09-10 实车定位):
        // Jellyfin 的 RunTimeTicks 元数据与转码流的实际时长可能不符, 只按元数据判定的
        // 断点照样会落在真实曲尾 —— 观感即「切过去直接到歌曲尾部」并假 COMPLETED 切歌。
        if (seekMs > 0 && PlaybackStateMachine.isEffectivelyAtEnd(durationMs, seekMs)) {
            Log.w(TAG, "Clamp resume point by real duration: id="
                    + (preparedSong != null ? preparedSong.getId() : null)
                    + " name=" + (preparedSong != null ? preparedSong.getName() : null)
                    + " saved=" + seekMs + "ms metaDuration="
                    + (preparedSong != null ? preparedSong.getDurationMs() : 0L)
                    + "ms realDuration=" + durationMs + "ms -> replay from start");
            seekMs = -1;
        }
        // 恢复锚点跟着当前曲目走: 若起播后立刻再次断流 (还没跑到第一个 tick),
        // 仍应从本次的断点续播, 而不是回到 0 或继承上一首的进度
        lastTickTrackId = preparedSong != null ? preparedSong.getId() : null;
        lastTickPositionMs = Math.max(seekMs, 0);
        if (seekMs > 0 && durationMs <= 0) {
            // 真实时长未知 (2026-09-12 #1): Jellyfin 实时转码时代理拿不到 Content-Length,
            // 回的是关闭定界流, 系统 MediaPlayer 的 getDuration() 就是 0, v3 的
            // MediaFormat 也没有 KEY_DURATION。此时盲 seek 会被底层钳到最后一个采样点,
            // 观感与「切歌直接到曲尾 + 立刻假 COMPLETED 跳下一首」完全一致, 所以宁可
            // 丢弃断点从 0 起播, 并留日志 (实车可据此判断是元数据缺失还是脏断点)。
            Log.w(TAG, "Drop resume point, real duration unknown at prepare: id="
                    + (preparedSong != null ? preparedSong.getId() : null)
                    + " name=" + (preparedSong != null ? preparedSong.getName() : null)
                    + " saved=" + seekMs + "ms metaDuration="
                    + (preparedSong != null ? preparedSong.getDurationMs() : 0L)
                    + "ms realDuration=0ms decision=replay-from-start");
        } else if (seekMs >= durationMs && seekMs > 0) {
            // seek 目标恰好等于/越过真实时长: 某些播放器会立刻回调 COMPLETED, 一律按从头播
            Log.w(TAG, "Drop resume point at/over real duration: id="
                    + (preparedSong != null ? preparedSong.getId() : null)
                    + " saved=" + seekMs + "ms realDuration=" + durationMs
                    + "ms decision=replay-from-start");
        }
        if (seekMs > 0 && seekMs < durationMs) {
            // 武装起播后兜底纠偏: 只有真的下发了断点 seek 才可能落到曲尾; 从头播不武装,
            // 否则会把「歌本来就短」误判成坏断点
            startedWithResumeMs = seekMs;
            resumeGuardTicks = 0;
            resumeHeardAudio = false;
            badResumeCorrected = false;
            // 弱网下这一步可能占掉十几秒（无 seektable 的 FLAC 二分要十几次重定位），
            // 屏幕上必须有句话说清"在等什么"，否则只剩一个不动的进度条和一句「缓冲中…」
            notifyPlayStatus("正在回到上次位置…");
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
        // 断点没回到位就别再挂着「正在回到上次位置…」——错误自有 toast 说话
        notifyPlayStatus(null);
        // v3 引擎的负值错误码 (DECODE_FAILED / STREAM_STALL) 一律按可恢复的网络/解码类错误重试;
        // 慢网 (1,-19) 是传输层瞬时错误, 同样可恢复, 否则看门狗每轮重启都复现一次 GIVE_UP
        boolean transientTransport = !playerIsV3
                && PlaybackStateMachine.isTransientTransportError(what, extra);
        boolean recoverable = playerIsV3 || transientTransport
                || (what == MediaPlayer.MEDIA_ERROR_IO || what == MEDIA_ERROR_SYSTEM);
        boolean reauthCooldownElapsed =
                System.currentTimeMillis() - lastReAuthAt > REAUTH_COOLDOWN_MS;
        // 服务端判定"这首不存在"(404/410)：必须抢在任何重试之前终态，见 effectiveRetryAction 注释
        boolean missingResource = playerIsV3 && player != null && player.isResourceGone();
        PlaybackStateMachine.StreamRetryAction action = PlaybackStateMachine.effectiveRetryAction(
                PlaybackStateMachine.streamRetryAction(recoverable, streamRetryCount, reauthCooldownElapsed),
                transientTransport, missingResource);
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
                    // streamRetryCount 已在本分支自增过，此刻它恰等于本次是第几次尝试
                    final long backoffMs = PlaybackStateMachine.streamRetryBackoffMs(streamRetryCount);
                    reportPlaybackError("网络波动, 自动重试 " + failedSong.getName());
                    CrashMonitor.breadcrumb("play", "retry backoff wait=" + backoffMs
                            + "ms attempt=" + streamRetryCount + " song=" + failedSong.getName());
                    if (backoffMs <= 0L) {
                        // 鉴权冷却期内第 3 次也会降级成 PLAIN_RETRY：这条已在等冷却，再叠等待只会更差
                        replayPendingSong(failedSong, finalResumeMs,
                                PlaybackStateMachine.PlaybackOrigin.NETWORK_RECOVERY);
                        return;
                    }
                    cancelStreamRetryBackoff();
                    streamRetryBackoffPending = true;
                    streamRetryBackoffRunnable = new Runnable() {
                        @Override
                        public void run() {
                            // 必须先复位状态再校验 generation：反过来写会在错误路径上留下
                            // pending=true，让看门狗永久让位、整个恢复链停摆
                            streamRetryBackoffRunnable = null;
                            streamRetryBackoffPending = false;
                            if (!playbackState.isCurrentGeneration(generation)) return;
                            CrashMonitor.breadcrumb("play", "retry backoff fired wait=" + backoffMs
                                    + "ms song=" + failedSong.getName());
                            replayPendingSong(failedSong, finalResumeMs,
                                    PlaybackStateMachine.PlaybackOrigin.NETWORK_RECOVERY);
                        }
                    };
                    progressHandler.postDelayed(streamRetryBackoffRunnable, backoffMs);
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
        // 重试预算用尽绝不能静默：shouldNotifyError 只放行 streak<=1，而走到这里 streak 必然已 >1，
        // 现场表现成「点了没声、屏幕上也没有任何提示」，且本曲要等进程重启才会再试
        // (2026-09-22 上报复盘：断点续播撞 Failed to instantiate extractor 三首全中)。
        SongItem abandoned = getCurrentSong();
        String abandonedName = abandoned != null ? abandoned.getName() : "当前曲目";
        giveUpSongStreak++;
        if (stateChangeListener != null) {
            stateChangeListener.onError(PlaybackStateMachine.describeGiveUp(
                    giveUpSongStreak, abandonedName, missingResource, isLosslessTier()));
        }
        CrashMonitor.breadcrumb("play", missingResource
                ? "gone on server, skip " + abandonedName
                : "GIVE_UP after " + streamRetryCount + " retries, skip " + abandonedName);
        silentErrorStreak = 0;
        streamRetryCount = 0;
        if (abandoned != null) {
            playNext(PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
        }
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
        cancelStreamRetryBackoff();
        cancelSeekTimeout();
        if (gainEnvelope != null) {
            try { gainEnvelope.hardMute(); } catch (Exception ignored) {}
        }
        if (ampWakeStrategy != null) {
            // 反注册音量意图观察器: 它会连带持有 Context, 服务停了必须摘掉
            try { ampWakeStrategy.release(); } catch (Exception ignored) {}
            ampWakeStrategy = null;
        }
        if (muteMonitor != null) {
            // 反注册静音监听 (ContentObserver/广播/轮询都持有 Context), 服务停了必须摘掉
            try { muteMonitor.unregister(); } catch (Exception ignored) {}
            muteMonitor = null;
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
        if (audioNoisyReceiver != null) {
            try {
                unregisterReceiver(audioNoisyReceiver);
            } catch (Exception ignored) {
                // 没注册成功过，忽略
            }
            audioNoisyReceiver = null;
        }
        stopAndReleaseAllAudioResources();
        super.onDestroy();
    }
}
