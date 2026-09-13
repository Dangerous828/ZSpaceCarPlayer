package com.ktools.zspacecarplayer.ui;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.db.SongDao;
import com.ktools.zspacecarplayer.crash.CrashMonitor;
import com.ktools.zspacecarplayer.crash.NativeEngineGuard;
import com.ktools.zspacecarplayer.model.CategoryItem;
import com.ktools.zspacecarplayer.model.LyricLine;
import com.ktools.zspacecarplayer.model.SongItem;
import com.ktools.zspacecarplayer.net.JellyfinApiClient;
import com.ktools.zspacecarplayer.service.AudioPlayerService;
import com.ktools.zspacecarplayer.service.MediaButtonReceiver;
import com.ktools.zspacecarplayer.service.PlaybackStateMachine;
import com.ktools.zspacecarplayer.util.CacheSizeManager;
import com.ktools.zspacecarplayer.update.ApkDownloader;
import com.ktools.zspacecarplayer.update.UpdateChecker;
import com.ktools.zspacecarplayer.update.UpdateInstaller;
import com.ktools.zspacecarplayer.update.UpdateManifest;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements AudioPlayerService.OnPlayerStateChangeListener {

    // 鉴权 SharedPreferences 文件名/key 与默认凭据统一引用 JellyfinApiClient 常量,
    // 保证 Service 后台静默登录与 Activity 读写的是同一份会话凭据
    private static final String TAG = "MainActivity";

    private static final String KEY_LAST_SONG_ID = "last_song_id";
    private static final String KEY_LAST_CAT = "last_cat";

    private AudioPlayerService playerService;
    private boolean isBound = false;

    private RecyclerView rvCategoriesGrid;
    private RecyclerView rvSongList;
    private RecyclerView rvLyrics;
    /** 用户正在手动拖动歌词列表 (暂停自动居中) */
    private boolean lyricUserDragging = false;
    /** 双击返回确认的完全退出: onDestroy 时结束进程, 不留后台残留 */
    private boolean exitCompletely = false;
    private View layoutSettingsPage;

    private CategoryAdapter categoryAdapter;
    private SongAdapter songAdapter;
    private LyricAdapter lyricAdapter;

    private TextView tvCurrentTitle, tvCurrentArtist, tvCurrentTime, tvTotalTime, tvSongCount, tvServerStatus, tvListTitle;
    private TextView tvBadgeFolder;
    private TextView tvLyricsEmpty;
    /** 缓冲指示 (2026-09-12 缓冲/预取): 缓冲时可见「缓冲 43%」/「缓冲中…」, 稳定播放后隐藏 */
    private TextView tvBuffering;
    private View seekFill;
    private EditText etSearch;
    private SeekBar seekBarProgress;
    private Button btnPlayPause, btnPrev, btnNext, btnPlayMode, btnCurrentFav;
    private Button btnNavAllSongs, btnNavPlaylist, btnNavRefresh, btnNavSettings, btnBackToPlaylist;
    private Button btnSearchToggle, btnCloseSearch;

    private boolean isUserSeeking = false;
    private boolean autoReloginAttempted = false;
    /**
     * 连接/刷新链是否正在进行 (2026-09-12 #4)。
     * 旧实现点一次刷新就 new 一个线程发一整轮分页请求, 且点击后不改任何文案、不禁用
     * 按钮; 离线时要干等 15s 连接超时才有回调, 用户看到的就是「点了没反应」, 于是连点,
     * 于是几套回调互相覆盖状态文案。现在链进行中一律合并, 且点击瞬间就有可见反馈。
     */
    private boolean isRefreshing = false;
    /** 当前链是否由用户点「刷新」触发: 决定用短超时 client 与看门狗时长 */
    private boolean refreshInteractive = false;
    /**
     * 连接链代号, 每次开链自增。所有网络回调都带着开链时的代号回来, 失配即丢弃 ——
     * 防止上一轮的迟到回调把新一轮的状态文案与按钮可用性搅乱。
     */
    private int refreshEpoch = 0;
    /**
     * 本条链内是否已经因「手上没 Token」直接进过登录环节。
     * 单独立这个标记而不去占用 autoReloginAttempted, 是为了两全: 既挡住「登录回调说
     * 成功却仍没拿到 Token」时的自我递归死循环, 又保留 autoReloginAttempted 给
     * 「登录成功但拉库时网络抖了一下」的那次宝贵重试 (车上网络不稳, 这次重试很值)。
     */
    private boolean noTokenLoginDone = false;
    private boolean isAutoPlayInitialized = false;

    private List<SongItem> allSongsList = new ArrayList<>();
    private List<SongItem> currentDisplayedSongs = new ArrayList<>();
    private long lastBackPressTime = 0;
    private long lastProgressSaveTime = 0;
    /** 上一次落库的曲目 id 与进度值: 用于识别「同一首歌进度倒退」的过期 tick (2026-09-12 #1) */
    private String lastProgressSaveTrackId;
    private int lastProgressSaveMs = -1;
    /**
     * 允许的正常倒退容差。切歌竞态里可能读到上一首贴尾的 position 却挂到新一首的 id 上,
     * 也可能在 seek 未完成时读到旧位置; 超过该容差的倒退一律视为脏值不落库。
     * 用户手动 seek 造成的倒退由 onStopTrackingTouch 清空追踪器放行。
     */
    private static final int PROGRESS_BACKWARD_TOLERANCE_MS = 2000;
    /** 静音键当暂停用的去抖窗口: ROM 可能对同一次按键重复投递, 双触发会变成「暂停又播放」。 */
    private static final long MUTE_KEY_TOGGLE_DEBOUNCE_MS = 500L;
    private long lastMuteKeyToggleAtMs = 0L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    /**
     * 看门狗时长 (2026-09-12 #4)。交互链最坏 = 12s 整轮时限 + 短超时重登 + 再拉一轮,
     * 45s 足够; 启动链走健壮超时, 大库分页可能 15s×N, 给到 120s 免得误报。
     */
    private static final long REFRESH_WATCHDOG_INTERACTIVE_MS = 45000L;
    private static final long REFRESH_WATCHDOG_ROBUST_MS = 120000L;

    /**
     * 刷新看门狗: 万一某条回调彻底没回来 (ROM 冻结进程、OkHttp 派发异常等),
     * 绝不能让刷新按钮永久卡在禁用态 —— 那才是真正不可恢复的「点了没反应」。
     * 到点强制解锁并明确告知超时; 若真实回调随后才到, 只是补一次状态文案与列表刷新。
     */
    private final Runnable refreshWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!isRefreshing) return;
            Log.w(TAG, "refresh watchdog fired, force unlock (interactive=" + refreshInteractive + ")");
            CrashMonitor.breadcrumb("net", "refresh watchdog fired interactive=" + refreshInteractive);
            isRefreshing = false;
            btnNavRefresh.setEnabled(true);
            btnNavRefresh.setText("刷新");
            tvServerStatus.setText("连接超时");
            Toast.makeText(MainActivity.this, "连接超时, 请检查网络后重试", Toast.LENGTH_LONG).show();
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlayerService.LocalBinder binder = (AudioPlayerService.LocalBinder) service;
            playerService = binder.getService();
            playerService.setOnPlayerStateChangeListener(MainActivity.this);
            isBound = true;

            int savedMode = getSavedPlayMode();
            playerService.setPlayMode(savedMode);
            btnPlayMode.setText(getPlayModeText(savedMode));

            if (playerService.getCurrentSong() != null) {
                onSongChanged(playerService.getCurrentSong(), playerService.getCurrentIndex());
                onPlayStateChanged(playerService.isPlaying());
            } else if (!currentDisplayedSongs.isEmpty()) {
                handleAutoPlayOrResume(currentDisplayedSongs);
                // 服务晚于 DB 就绪的时序: 恢复挂载后同样把列表定位到正在播的歌
                syncPlayingHighlight(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            playerService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureSystemUiVisible();
        setContentView(R.layout.activity_main);

        // 音量键锁定到媒体流 (#5)。AOSP 在没有活跃播放时把音量键指向 suggested stream
        // (可能是 RING/VOICE_CALL), 用户在前台按音量键调的其实不是音乐流, 观感就是
        // 「调低了但效果不明显」。API 1 方法, 只影响按键目标流, App 不接管音量值本身。
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        JellyfinApiClient.getInstance().init(getApplicationContext());

        initViews();
        setupAdapters();
        setupListeners();
        showPlaylistGridView();

        // 1. 检查应用缓存容量上限
        CacheSizeManager.checkAndTrimCacheAsync(this);

        // 2. 异步读出 SQLite 数据库
        loadLocalDbFirst();

        // 3. 绑定服务并初始化网络
        bindPlayerService();
        loadSavedServerConfig();

        // 4. 车机音频路由调试入口 (实车 HAL 绑卡错乱修复工具, adb 广播触发)
        registerDebugRouteReceiver();

        // 5. 远程升级 (2026-09-12): 偏好开启时, 冷启动后延迟静默检查一次
        scheduleAutoUpdateCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureSystemUiVisible();
    }

    private void ensureSystemUiVisible() {
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            View decorView = getWindow().getDecorView();
            if (decorView != null) {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        } catch (Exception ignored) {}
    }

    private void initViews() {
        rvCategoriesGrid = findViewById(R.id.rvCategoriesGrid);
        rvSongList = findViewById(R.id.rvSongList);
        rvLyrics = findViewById(R.id.rvLyrics);
        tvCurrentTitle = findViewById(R.id.tvCurrentTitle);
        tvCurrentArtist = findViewById(R.id.tvCurrentArtist);
        tvCurrentTime = findViewById(R.id.tvCurrentTime);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        tvSongCount = findViewById(R.id.tvSongCount);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        tvListTitle = findViewById(R.id.tvListTitle);
        tvBuffering = findViewById(R.id.tvBuffering);
        seekFill = findViewById(R.id.seekFill);
        tvLyricsEmpty = findViewById(R.id.tvLyricsEmpty);
        tvBadgeFolder = findViewById(R.id.tvBadgeFolder);
        layoutSettingsPage = findViewById(R.id.layoutSettingsPage);
        etSearch = findViewById(R.id.etSearch);
        seekBarProgress = findViewById(R.id.seekBarProgress);

        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnPlayMode = findViewById(R.id.btnPlayMode);
        btnCurrentFav = findViewById(R.id.btnCurrentFav);

        btnNavAllSongs = findViewById(R.id.btnNavAllSongs);
        btnNavPlaylist = findViewById(R.id.btnNavPlaylist);
        btnNavRefresh = findViewById(R.id.btnNavRefresh);
        btnNavSettings = findViewById(R.id.btnNavSettings);
        btnBackToPlaylist = findViewById(R.id.btnBackToPlaylist);

        btnSearchToggle = findViewById(R.id.btnSearchToggle);
        btnCloseSearch = findViewById(R.id.btnCloseSearch);
    }

    private void setupAdapters() {
        categoryAdapter = new CategoryAdapter();
        rvCategoriesGrid.setLayoutManager(new GridLayoutManager(this, 2));
        rvCategoriesGrid.setAdapter(categoryAdapter);

        songAdapter = new SongAdapter();
        rvSongList.setLayoutManager(new LinearLayoutManager(this));
        rvSongList.setAdapter(songAdapter);

        lyricAdapter = new LyricAdapter();
        rvLyrics.setHasFixedSize(true);
        rvLyrics.setLayoutManager(new LinearLayoutManager(this));
        rvLyrics.setAdapter(lyricAdapter);
        // 用户手动拖动歌词时暂停自动居中, 松手恢复 (避免抢滚动)
        rvLyrics.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                lyricUserDragging = (newState == RecyclerView.SCROLL_STATE_DRAGGING);
            }
        });

        categoryAdapter.setOnCategoryClickListener(new CategoryAdapter.OnCategoryClickListener() {
            @Override
            public void onCategoryClick(CategoryItem category, int position) {
                onCategorySelected(category);
            }
        });

        songAdapter.setOnSongClickListener(new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(SongItem song, int position) {
                hideSoftKeyboard();
                if (isBound && playerService != null) {
                    // 取出的断点必须先按「这首歌自己的时长」清洗再交给播放服务 (#1):
                    // 服务侧 sanitizeSeekMs 与 prepared 回调还会各钳一次, 多这一道是为了
                    // 让点歌路径的 decision 在 UI 日志里就能看到
                    int exactMs = resumePointForPlayback(song,
                            SongDao.getInstance(MainActivity.this).getSongProgress(song.getId()),
                            "song clicked");
                    Log.i(TAG, "song clicked pos=" + position + " " + song.getName()
                            + " id=" + song.getId() + " savedProgress=" + exactMs
                            + "ms metaDuration=" + song.getDurationMs() + "ms");
                    CrashMonitor.breadcrumb("ui", "song clicked pos=" + position
                            + " " + song.getName() + " resumeMs=" + exactMs);
                    playerService.setPlaylist(currentDisplayedSongs, position, exactMs,
                            PlaybackStateMachine.PlaybackOrigin.USER_UI);
                } else {
                    // 服务未绑定时点击只会弹 Toast，用户观感同样是「点了没反应」
                    CrashMonitor.breadcrumb("ui", "song clicked but service not bound: "
                            + song.getName());
                    Toast.makeText(MainActivity.this, "播放服务初始化中, 请稍候再试", Toast.LENGTH_SHORT).show();
                }
            }
        });

        songAdapter.setOnFavClickListener(new SongAdapter.OnFavClickListener() {
            @Override
            public void onFavClick(final SongItem song, final int position) {
                final boolean newFavState = !song.isFavorite();
                song.setFavorite(newFavState);
                songAdapter.notifyItemChanged(position);

                SongDao.getInstance(MainActivity.this).updateFavoriteState(song.getId(), newFavState);

                JellyfinApiClient.getInstance().toggleFavorite(song.getId(), newFavState, new JellyfinApiClient.ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        Toast.makeText(MainActivity.this, newFavState ? "已添加红心收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(Exception e) {
                        Toast.makeText(MainActivity.this, "收藏状态已离线保存", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void hideSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void setupListeners() {
        // 点击“搜索”触发按钮，展开搜索输入框并弹出软键盘
        btnSearchToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnSearchToggle.setVisibility(View.GONE);
                etSearch.setVisibility(View.VISIBLE);
                btnCloseSearch.setVisibility(View.VISIBLE);
                etSearch.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });

        // 点击“清空/收起”按钮，清空搜索、收起软键盘并重置按钮
        btnCloseSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etSearch.setText("");
                hideSoftKeyboard();
                etSearch.setVisibility(View.GONE);
                btnCloseSearch.setVisibility(View.GONE);
                btnSearchToggle.setVisibility(View.VISIBLE);
            }
        });

        // 搜索框 300ms 防抖
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(final CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) {
                    mainHandler.removeCallbacks(searchRunnable);
                }
                searchRunnable = new Runnable() {
                    @Override
                    public void run() {
                        filterOrSearchSongsAsync(s.toString());
                    }
                };
                mainHandler.postDelayed(searchRunnable, 300);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSoftKeyboard();
                if (isBound && playerService != null) {
                    if (playerService.getCurrentSong() == null && !currentDisplayedSongs.isEmpty()) {
                        playerService.setPlaylist(currentDisplayedSongs, 0, -1,
                                PlaybackStateMachine.PlaybackOrigin.USER_UI);
                    } else {
                        playerService.playOrPause();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "播放服务初始化中, 请稍候再试", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isBound && playerService != null) {
                    playerService.playPrevious();
                }
            }
        });

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isBound && playerService != null) {
                    playerService.playNext();
                }
            }
        });

        btnPlayMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isBound || playerService == null) return;
                int current = playerService.getPlayMode();
                int next = (current + 1) % 3;
                playerService.setPlayMode(next);
                btnPlayMode.setText(getPlayModeText(next));

                String tip = "顺序播放";
                if (next == AudioPlayerService.MODE_SINGLE_REPEAT) tip = "单曲循环";
                else if (next == AudioPlayerService.MODE_RANDOM) tip = "随机播放";
                Toast.makeText(MainActivity.this, tip, Toast.LENGTH_SHORT).show();

                saveCurrentState();
            }
        });

        btnCurrentFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playerService == null) return;
                final SongItem current = playerService.getCurrentSong();
                if (current == null) return;
                final boolean newFav = !current.isFavorite();
                current.setFavorite(newFav);
                updateCurrentFavBtn(newFav);
                SongDao.getInstance(MainActivity.this).updateFavoriteState(current.getId(), newFav);
                songAdapter.notifyDataSetChanged();
                Toast.makeText(MainActivity.this, newFav ? "已添加红心收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
                JellyfinApiClient.getInstance().toggleFavorite(current.getId(), newFav, null);
            }
        });

        btnBackToPlaylist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSoftKeyboard();
                showPlaylistGridView();
            }
        });

        btnNavAllSongs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSoftKeyboard();
                showAllSongsView();
            }
        });

        btnNavPlaylist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSoftKeyboard();
                showPlaylistGridView();
            }
        });

        btnNavRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSoftKeyboard();
                // 2026-09-12 #4: 点了必有反应。进行中再点击一律合并, 不再叠线程。
                // 按钮此时已禁用, 这条分支只是兜底 (个别 ROM 上禁用态仍会派发点击)。
                if (isRefreshing) {
                    Log.i(TAG, "refresh clicked while chain in flight, merged");
                    Toast.makeText(MainActivity.this, "正在连接中, 请稍候…", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 车机整体断网时不必再去撞连接超时, 立刻给出可诊断结论
                if (!isNetworkAvailable()) {
                    Log.w(TAG, "refresh clicked but no active network");
                    CrashMonitor.breadcrumb("net", "refresh blocked: no active network");
                    tvServerStatus.setText("无网络");
                    Toast.makeText(MainActivity.this,
                            "车机当前无网络连接, 请检查网络设置后重试", Toast.LENGTH_LONG).show();
                    return;
                }
                autoReloginAttempted = false;
                if (!beginRefreshChain("user tap", true)) return;
                fetchLibrary(true, refreshEpoch);
            }
        });

        btnNavSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CrashMonitor.breadcrumb("ui", "settings clicked");
                hideSoftKeyboard();
                showSettingsPageView();
            }
        });

        seekBarProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long sec = (progress / 1000) % 60;
                    long min = (progress / 1000) / 60;
                    tvCurrentTime.setText(String.format("%02d:%02d", min, sec));
                    updateSeekFill();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (isBound && playerService != null) {
                    playerService.seekTo(seekBar.getProgress());
                    // 用户手动往回拖是合法的进度倒退, 清空追踪器, 否则随后的正常落库
                    // 会被「同曲进度倒退 = 脏 tick」这道防线误拦 (2026-09-12 #1)
                    lastProgressSaveTrackId = null;
                    lastProgressSaveMs = -1;
                }
                isUserSeeking = false;
            }
        });
    }

    private void updateCurrentFavBtn(boolean isFav) {
        if (btnCurrentFav == null) return;
        btnCurrentFav.setText("♥");
        btnCurrentFav.setTextColor(Color.parseColor(isFav ? "#30DDC2" : "#556275"));
    }

    private void showAllSongsView() {
        rvCategoriesGrid.setVisibility(View.GONE);
        rvSongList.setVisibility(View.VISIBLE);
        if (layoutSettingsPage != null) layoutSettingsPage.setVisibility(View.GONE);
        btnBackToPlaylist.setVisibility(View.GONE);
        btnSearchToggle.setVisibility(View.VISIBLE);
        tvListTitle.setText("全部歌曲");
        currentDisplayedSongs = new ArrayList<>(allSongsList);
        tvSongCount.setText(currentDisplayedSongs.size() + " 首");
        songAdapter.setShowPlayCount(false);
        songAdapter.setSongs(currentDisplayedSongs);
        // 与 showSongListView 一致: 命中正在播就定位, 未命中回顶部
        syncPlayingHighlight(true);
        if (songAdapter.getSelectedIndex() < 0) {
            rvSongList.scrollToPosition(0);
        }
        btnNavAllSongs.setSelected(true);
        btnNavAllSongs.setTextColor(Color.parseColor("#30DDC2"));
        btnNavPlaylist.setSelected(false);
        btnNavPlaylist.setTextColor(Color.parseColor("#A8B5C6"));
        btnNavSettings.setSelected(false);
        btnNavSettings.setTextColor(Color.parseColor("#A8B5C6"));
    }

    private void showPlaylistGridView() {
        rvCategoriesGrid.setVisibility(View.VISIBLE);
        rvSongList.setVisibility(View.GONE);
        if (layoutSettingsPage != null) layoutSettingsPage.setVisibility(View.GONE);
        btnBackToPlaylist.setVisibility(View.GONE);
        btnSearchToggle.setVisibility(View.VISIBLE);
        tvListTitle.setText("我的歌单");
        tvSongCount.setText(categoryAdapter.getItemCount() + " 个分类");
        btnNavPlaylist.setSelected(true);
        btnNavPlaylist.setTextColor(Color.parseColor("#30DDC2"));
        btnNavAllSongs.setSelected(false);
        btnNavAllSongs.setTextColor(Color.parseColor("#A8B5C6"));
        btnNavSettings.setSelected(false);
        btnNavSettings.setTextColor(Color.parseColor("#A8B5C6"));
    }

    private void showSongListView(String title, List<SongItem> songs) {
        rvCategoriesGrid.setVisibility(View.GONE);
        rvSongList.setVisibility(View.VISIBLE);
        if (layoutSettingsPage != null) layoutSettingsPage.setVisibility(View.GONE);
        btnBackToPlaylist.setVisibility(View.VISIBLE);
        btnSearchToggle.setVisibility(View.VISIBLE);
        tvListTitle.setText(title);
        currentDisplayedSongs = (songs != null) ? songs : new ArrayList<SongItem>();
        tvSongCount.setText(currentDisplayedSongs.size() + " 首");
        songAdapter.setSongs(currentDisplayedSongs);
        // (2026-09-09 实车反馈) 列表切换后必须立刻重算高亮: setSongs 不清 selectedIndex,
        // 旧列表的 index 会原样落到新列表同一位置, 造成「B 列表错位高亮, 等歌播完才纠正」。
        // 所有列表入口 (分类/全部歌曲/红心/最多播放/搜索) 都走本方法, 收口在这里最稳。
        // (2026-09-10 实车反馈) 打开列表要直接定位到正在播的歌 (冷启动恢复/切列表后
        // 都停在顶部, 用户得手动翻找); 未命中 (歌不在本列表) 时保持回顶部原行为。
        syncPlayingHighlight(true);
        if (songAdapter.getSelectedIndex() < 0) {
            rvSongList.scrollToPosition(0);
        }
        btnNavPlaylist.setSelected(true);
        btnNavPlaylist.setTextColor(Color.parseColor("#30DDC2"));
        btnNavAllSongs.setSelected(false);
        btnNavAllSongs.setTextColor(Color.parseColor("#A8B5C6"));
        btnNavSettings.setSelected(false);
        btnNavSettings.setTextColor(Color.parseColor("#A8B5C6"));
    }

    /**
     * 把歌曲列表高亮同步到服务端正在播的歌。
     * SongItem.equals 按 Id 比较, DB 异步加载的红心/最多播放列表是新对象实例也能命中;
     * 正在播的歌不在当前列表时显式清 -1, 不残留旧 index。
     *
     * @param scrollToList 高亮命中后是否把列表滚动到该行 (切列表时不滚, 切歌时滚)
     */
    private void syncPlayingHighlight(boolean scrollToList) {
        int index = -1;
        if (isBound && playerService != null) {
            SongItem current = playerService.getCurrentSong();
            if (current != null) {
                index = currentDisplayedSongs.indexOf(current);
            }
        }
        songAdapter.setSelectedIndex(index);
        if (index >= 0 && scrollToList) {
            rvSongList.smoothScrollToPosition(index);
        }
    }

    private void onCategorySelected(CategoryItem category) {
        if (category == null) return;
        String id = category.getId();
        String name = category.getName();
        CrashMonitor.putContext("category", name);
        CrashMonitor.breadcrumb("ui", "category " + name + " id=" + id
                + " count=" + category.getSongCount());
        if ("fav".equals(id) || name.contains("红心")) {
            loadFavoriteSongs();
        } else if ("most_played".equals(id) || name.contains("播放最多")) {
            loadMostPlayedSongs();
        } else if ("all".equals(id) || name.contains("全部歌曲")) {
            showSongListView("全部歌曲", allSongsList);
        } else {
            List<SongItem> filtered = filterSongsByCategory(allSongsList, name);
            showSongListView(name, filtered);
        }
    }

    private void showSettingsPageView() {
        rvCategoriesGrid.setVisibility(View.GONE);
        rvSongList.setVisibility(View.GONE);
        layoutSettingsPage.setVisibility(View.VISIBLE);
        btnBackToPlaylist.setVisibility(View.GONE);
        btnSearchToggle.setVisibility(View.GONE);
        btnCloseSearch.setVisibility(View.GONE);
        etSearch.setVisibility(View.GONE);
        tvListTitle.setText("系统与播放设置");
        tvSongCount.setText("");
        btnNavSettings.setSelected(true);
        btnNavSettings.setTextColor(Color.parseColor("#30DDC2"));
        btnNavAllSongs.setSelected(false);
        btnNavAllSongs.setTextColor(Color.parseColor("#A8B5C6"));
        btnNavPlaylist.setSelected(false);
        btnNavPlaylist.setTextColor(Color.parseColor("#A8B5C6"));

        setupSettingsPageListeners();
    }

    private void setupSettingsPageListeners() {
        if (layoutSettingsPage == null) return;

        Button btnSub = layoutSettingsPage.findViewById(R.id.btnSettingLrcSub);
        Button btnAdd = layoutSettingsPage.findViewById(R.id.btnSettingLrcAdd);
        final Button btnFont = layoutSettingsPage.findViewById(R.id.btnSettingLrcFont);
        View btnEq = layoutSettingsPage.findViewById(R.id.btnSettingOpenEq);
        View btnServer = layoutSettingsPage.findViewById(R.id.btnSettingOpenServer);

        if (btnSub != null) {
            btnSub.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int offset = lyricAdapter.getOffsetMs() - 500;
                    lyricAdapter.setOffsetMs(offset);
                    Toast.makeText(MainActivity.this, "歌词时间轴: " + (offset / 1000f) + "s", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnAdd != null) {
            btnAdd.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int offset = lyricAdapter.getOffsetMs() + 500;
                    lyricAdapter.setOffsetMs(offset);
                    Toast.makeText(MainActivity.this, "歌词时间轴: " + (offset / 1000f) + "s", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnFont != null) {
            btnFont.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    float base = getResources().getDimension(R.dimen.lyric_base);
                    float mid = getResources().getDimension(R.dimen.lyric_mid);
                    float large = getResources().getDimension(R.dimen.lyric_large);
                    float cur = lyricAdapter.getBaseTextSizePx();
                    if (cur < 0) cur = base;

                    float next; String label;
                    if (cur >= large - 1f) { next = base; label = "中"; }
                    else if (cur >= mid - 1f) { next = large; label = "超大"; }
                    else { next = mid; label = "大"; }

                    lyricAdapter.setBaseTextSizeSp(next);
                    btnFont.setText("字号:" + label);
                    Toast.makeText(MainActivity.this, "歌词字号: " + label, Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnEq != null) {
            btnEq.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEqDialog();
                }
            });
        }

        if (btnServer != null) {
            btnServer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSettingsDialog();
                }
            });
        }

        setupEngineToggle();
        // 远程升级 (2026-09-12): 「关于与升级」分块 (检查更新 + 启动时自动检查开关)
        setupUpdateSection();
    }

    /** v3 自研 DSP 引擎开关: 写偏好即可, 引擎在下一首歌起播时惰性重建 (不打断当前播放) */
    private void setupEngineToggle() {
        if (layoutSettingsPage == null) return;
        final View row = layoutSettingsPage.findViewById(R.id.btnSettingEngineToggle);
        final Button valueBtn = layoutSettingsPage.findViewById(R.id.btnSettingEngineValue);
        if (row == null || valueBtn == null) return;

        final SharedPreferences sp = getSharedPreferences(AudioPlayerService.PREF_NAME, MODE_PRIVATE);
        updateEngineLabel(sp, valueBtn);

        final View.OnClickListener toggleListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 熔断期间偏好仍是 true 而实际跑系统引擎, 必须按有效状态翻转, 否则要点两次
                boolean autoDisabled = CrashMonitor.isEngineAutoDisabled();
                boolean effective = sp.getBoolean(AudioPlayerService.PREF_KEY_ENGINE_V3, false)
                        && !autoDisabled;
                boolean next = !effective;
                if (next && autoDisabled) {
                    // 显式重开即给一轮新预算: 清空熔断与连续崩溃计数
                    CrashMonitor.resetEngineGuard();
                }
                sp.edit().putBoolean(AudioPlayerService.PREF_KEY_ENGINE_V3, next).apply();
                updateEngineLabel(sp, valueBtn);
                String message;
                if (next) {
                    message = autoDisabled
                            ? "已重开 v3 引擎 (此前连续 " + NativeEngineGuard.CRASH_LIMIT
                              + " 次崩溃被自动回退), 下一首歌起生效"
                            : "已切换 v3 自研 DSP 引擎, 下一首歌起生效";
                } else {
                    message = "已切换回系统 MediaPlayer, 下一首歌起生效";
                }
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
        // 药丸是 Button，默认 clickable=true：不单独挂监听就会把落在它上面的触点吞掉，
        // 而它恰恰是显示「系统 (崩溃保护)」的那个最显眼的可点目标。
        row.setOnClickListener(toggleListener);
        valueBtn.setOnClickListener(toggleListener);
    }

    /** 引擎标签: 被崩溃熔断强制回退时明确标出, 否则用户会以为偏好没生效 */
    private void updateEngineLabel(SharedPreferences sp, Button valueBtn) {
        boolean wanted = sp.getBoolean(AudioPlayerService.PREF_KEY_ENGINE_V3, false);
        if (wanted && CrashMonitor.isEngineAutoDisabled()) {
            valueBtn.setText("系统 (崩溃保护)");
        } else {
            valueBtn.setText(wanted ? "v3 DSP" : "系统");
        }
    }

    private String getPlayModeText(int mode) {
        switch (mode) {
            case AudioPlayerService.MODE_SINGLE_REPEAT:
                return "单曲";
            case AudioPlayerService.MODE_RANDOM:
                return "随机";
            case AudioPlayerService.MODE_SEQUENCE:
            default:
                return "顺序";
        }
    }

    private void showEqDialog() {
        if (!isBound || playerService == null) {
            Toast.makeText(this, "播放服务初始化中...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!playerService.isEverPrepared()) {
            Toast.makeText(this, "开始播放后将自动启用 EQ 音效", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> presets = playerService.getEqPresets();
        if (presets == null || presets.isEmpty()) {
            // 如果底层未返回预设，提供标准车载内置音效预设
            presets = Arrays.asList(
                    "原声 (Flat)", "古典 (Classical)", "流行 (Pop)", "摇滚 (Rock)", "人声 (Vocal)",
                    "爵士 (Jazz)", "舞曲 (Dance)", "金属 (Metal)", "蓝调 (Blues)", "电子 (Electronic)",
                    "电音舞曲 (EDM)", "嘻哈 (Hip-Hop)", "男声 (Male Vocal)", "女声 (Female Vocal)",
                    "播客对话 (Speech)", "车载优化 (Car)", "低音增强 (Bass Boost)"
            );
        }

        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_eq);

        Spinner spEq = dialog.findViewById(R.id.spEqPresets);
        final TextView tvBassVal = dialog.findViewById(R.id.tvBassValue);
        SeekBar sbBass = dialog.findViewById(R.id.sbBassBoost);
        final TextView tvPanoVal = dialog.findViewById(R.id.tvPanoValue);
        SeekBar sbPano = dialog.findViewById(R.id.sbPanorama);
        final TextView tvSpaceVal = dialog.findViewById(R.id.tvSpaceValue);

        if (presets == null || presets.isEmpty()) {
            spEq.setEnabled(false);
        } else {
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, presets);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spEq.setAdapter(adapter);

            short currentPreset = playerService.getCurrentPresetIndex();
            if (currentPreset >= 0 && currentPreset < adapter.getCount()) {
                spEq.setSelection(currentPreset);
            }

            spEq.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                private boolean isFirstSelection = true;

                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (isFirstSelection) {
                        isFirstSelection = false;
                        return; // 拦截初始化时误触发 usePreset(0)
                    }
                    playerService.setEqPreset((short) position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        int currentBass = playerService.getCurrentBassPercent();
        sbBass.setProgress(currentBass);
        tvBassVal.setText(currentBass + "%");

        int currentPano = playerService.getCurrentVirtualizerPercent();
        sbPano.setProgress(currentPano);
        tvPanoVal.setText(currentPano + "%");

        int currentSpace = playerService.getCurrentReverbMode();
        // 点选式档位: SeekBar 在车机上难以精确停档 (2026-09-08 实车反馈),
        // 改为 4 个按钮, 点击即刻生效, 选中态高亮
        final String[] spaceModes = {"关", "房间", "音乐厅", "影院"};
        final Button[] spaceTabs = {
                dialog.findViewById(R.id.btnSpace0),
                dialog.findViewById(R.id.btnSpace1),
                dialog.findViewById(R.id.btnSpace2),
                dialog.findViewById(R.id.btnSpace3)
        };
        View.OnClickListener spaceTabClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int mode = Integer.parseInt((String) v.getTag());
                for (int i = 0; i < spaceTabs.length; i++) {
                    spaceTabs[i].setSelected(i == mode);
                }
                tvSpaceVal.setText(spaceModes[mode]);
                playerService.setReverbMode(mode);
            }
        };
        for (int i = 0; i < spaceTabs.length; i++) {
            spaceTabs[i].setTag(String.valueOf(i));
            spaceTabs[i].setOnClickListener(spaceTabClick);
        }
        int cur = Math.max(0, Math.min(spaceModes.length - 1, currentSpace));
        spaceTabs[cur].setSelected(true);
        tvSpaceVal.setText(spaceModes[cur]);

        spEq.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean isFirstSelection = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isFirstSelection) {
                    isFirstSelection = false;
                    return; // 拦截初始化时误触发 usePreset(0)
                }
                playerService.setEqPreset((short) position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sbBass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvBassVal.setText(progress + "%");
                playerService.setBassBoostPercent(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbPano.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvPanoVal.setText(progress + "%");
                playerService.setVirtualizerPercent(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        dialog.findViewById(R.id.btnCloseEq).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        // 车机可视区仅 ~640px 高, 内容(3 组滑条+说明+关闭按钮)固定超出。
        // 之前依赖 show() 后测量 Activity decor view 可视区再 setLayout() 的做法在车机 ROM 上
        // 不可靠 (测量到的高度早于/晚于系统对话框实际布局 pass, 窗口仍按 wrap_content 撑到
        // 内容自然高度再被系统裁掉、看不到也摸不到底部内容, 2026-09-05 实车复现)。
        // 改为在 show() 之前直接把窗口高度设为 MATCH_PARENT: 窗口本身占满可视区,
        // dialog_eq.xml 的 ScrollView 在这个固定高度内处理内容滚动, 不依赖任何运行时测量。
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    getResources().getDimensionPixelSize(R.dimen.dialog_w),
                    android.view.WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
    }

    // ---------------- 异步 SQLite 数据加载 ----------------

    private void loadLocalDbFirst() {
        SongDao.getInstance(this).getAllSongsAsync(new SongDao.DbCallback<List<SongItem>>() {
            @Override
            public void onResult(List<SongItem> cachedSongs) {
                if (cachedSongs != null && !cachedSongs.isEmpty()) {
                    allSongsList = new ArrayList<>(cachedSongs);
                    List<CategoryItem> categories = buildCategories(allSongsList);
                    categoryAdapter.setCategories(categories);

                    String savedCategory = getLastCategoryName();
                    categoryAdapter.setSelectedCategoryName(savedCategory);
                    currentDisplayedSongs = filterSongsByCategory(allSongsList, savedCategory);

                    tvSongCount.setText(currentDisplayedSongs.size() + " 首");
                    tvListTitle.setText("歌曲列表");
                    songAdapter.setShowPlayCount(false);
                    songAdapter.setSongs(currentDisplayedSongs);

                    // 缓存就绪即触发自动续播: 公网抖动导致服务器同步失败时,
                    // 也能用缓存歌单 + 保存的断点恢复播放, 而不是永远等待同步
                    handleAutoPlayOrResume(currentDisplayedSongs);
                    // 冷启动定位 (2026-09-10 实车反馈): 自动续播挂载后列表要滚到
                    // 正在播的歌, 而不是停在顶部让用户自己找 (setPlaylist 同步设置
                    // currentIndex, 此处 getCurrentSong() 已可用)
                    syncPlayingHighlight(true);
                }
            }
        });
    }

    private void loadFavoriteSongs() {
        etSearch.setText("");
        cancelPendingSearch();
        SongDao.getInstance(MainActivity.this).getFavoriteSongsAsync(new SongDao.DbCallback<List<SongItem>>() {
            @Override
            public void onResult(List<SongItem> favs) {
                showSongListView("❤️ 红心收藏", favs);
            }
        });
    }

    private void loadMostPlayedSongs() {
        etSearch.setText("");
        cancelPendingSearch();
        SongDao.getInstance(MainActivity.this).getMostPlayedAsync(new SongDao.DbCallback<List<SongItem>>() {
            @Override
            public void onResult(List<SongItem> tops) {
                showSongListView("🔥 播放最多", tops);
            }
        });
    }

    /** 取消待执行的防抖搜索回调, 防止清空搜索框后 switchCategory 覆盖刚切换的歌单 */
    private void cancelPendingSearch() {
        if (searchRunnable != null) {
            mainHandler.removeCallbacks(searchRunnable);
            searchRunnable = null;
        }
    }

    private void filterOrSearchSongsAsync(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            SongDao.getInstance(this).searchSongsAsync(keyword, new SongDao.DbCallback<List<SongItem>>() {
                @Override
                public void onResult(List<SongItem> searched) {
                    showSongListView("搜索结果", searched);
                }
            });
        } else {
            showPlaylistGridView();
        }
    }

    private List<CategoryItem> buildCategories(List<SongItem> songs) {
        List<CategoryItem> list = new ArrayList<>();
        if (songs == null || songs.isEmpty()) {
            list.add(new CategoryItem("all", "全部歌曲", 0));
            list.add(new CategoryItem("fav", "红心收藏", 0));
            list.add(new CategoryItem("most_played", "播放最多", 0));
            return list;
        }

        int favCount = 0;
        for (SongItem s : songs) {
            if (s.isFavorite()) favCount++;
        }

        // 1. 核心置顶歌单 (全部歌曲 + 红心收藏 + 播放最多)
        list.add(new CategoryItem("all", "全部歌曲", songs.size()));
        list.add(new CategoryItem("fav", "红心收藏", favCount));
        list.add(new CategoryItem("most_played", "播放最多", Math.min(50, songs.size())));

        // 2. 文件夹聚类
        Map<String, Integer> folderCountMap = new LinkedHashMap<>();
        // 3. 流派 Tag 聚类
        Map<String, Integer> categoryCountMap = new LinkedHashMap<>();

        for (SongItem song : songs) {
            String folder = song.getFolderName();
            if (folder != null && !folder.trim().isEmpty() && !"未分类文件夹".equals(folder)) {
                String folderTag = "📁 " + folder;
                Integer fCount = folderCountMap.get(folderTag);
                folderCountMap.put(folderTag, fCount == null ? 1 : fCount + 1);
            }

            String g = song.getGenre();
            if (g == null || g.trim().isEmpty()) {
                g = "未分类";
            }
            Integer count = categoryCountMap.get(g);
            categoryCountMap.put(g, count == null ? 1 : count + 1);
        }

        // 再放文件夹分类卡片
        for (Map.Entry<String, Integer> entry : folderCountMap.entrySet()) {
            list.add(new CategoryItem(entry.getKey(), entry.getKey(), entry.getValue()));
        }

        // 再放音乐流派卡片
        for (Map.Entry<String, Integer> entry : categoryCountMap.entrySet()) {
            list.add(new CategoryItem(entry.getKey(), entry.getKey(), entry.getValue()));
        }

        return list;
    }

    private List<SongItem> filterSongsByCategory(List<SongItem> all, String categoryName) {
        if (categoryName == null || "全部歌曲".equals(categoryName) || "all".equals(categoryName)) {
            return new ArrayList<>(all);
        }
        List<SongItem> result = new ArrayList<>();
        for (SongItem song : all) {
            if (categoryName.startsWith("📁 ")) {
                String targetFolder = categoryName.substring(3);
                if (targetFolder.equals(song.getFolderName())) {
                    result.add(song);
                }
            } else if (categoryName.equals(song.getGenre())) {
                result.add(song);
            }
        }
        return result;
    }

    // ---------------- 仅当存在 lastSongId 记录时才开启自动续播 ----------------

    private void handleAutoPlayOrResume(List<SongItem> currentSongs) {
        if (isAutoPlayInitialized) return;
        if (!isBound || playerService == null) return;

        if (playerService.isPlaying()) {
            isAutoPlayInitialized = true;
            SongItem playing = playerService.getCurrentSong();
            if (playing != null) {
                onSongChanged(playing, playerService.getCurrentIndex());
            }
            return;
        }

        if (currentSongs == null || currentSongs.isEmpty()) return;

        String lastSongId = getLastSongId();
        int savedPlayMode = getSavedPlayMode();

        playerService.setPlayMode(savedPlayMode);
        btnPlayMode.setText(getPlayModeText(savedPlayMode));

        int targetIndex = -1;
        if (lastSongId != null && !lastSongId.trim().isEmpty()) {
            for (int i = 0; i < currentSongs.size(); i++) {
                if (lastSongId.equals(currentSongs.get(i).getId())) {
                    targetIndex = i;
                    break;
                }
            }
        }

        if (targetIndex >= 0) {
            // 匹配到上次的歌曲记录: 自动开播并跳回精确断点!
            isAutoPlayInitialized = true;
            SongItem target = currentSongs.get(targetIndex);
            int exactMs = resumePointForPlayback(target,
                    SongDao.getInstance(this).getSongProgress(lastSongId), "auto-resume");
            playerService.setPlaylist(currentSongs, targetIndex, exactMs,
                    PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
        } else {
            // 当前显示分类里找不到上次歌曲 (分类被切换过/歌曲归属变化):
            // 回退到全曲库匹配, 绝不能因为分类过滤而丢掉自动续播
            List<SongItem> fallback = allSongsList;
            if (fallback != null && !fallback.isEmpty()) {
                for (int i = 0; i < fallback.size(); i++) {
                    if (lastSongId.equals(fallback.get(i).getId())) {
                        isAutoPlayInitialized = true;
                        int exactMs = resumePointForPlayback(fallback.get(i),
                                SongDao.getInstance(this).getSongProgress(lastSongId),
                                "auto-resume-fallback");
                        playerService.setPlaylist(fallback, i, exactMs,
                                PlaybackStateMachine.PlaybackOrigin.AUTO_RESUME);
                        Log.i(TAG, "auto-resume: last song not in category '"
                                + getLastCategoryName() + "', fell back to full library index " + i);
                        return;
                    }
                }
            }
            // 无上次播放记录: 只挂载播放列表，不自动播放，等待用户点击
            isAutoPlayInitialized = true;
            playerService.setPlaylist(currentSongs, -1);
            songAdapter.setSelectedIndex(0);
            Log.i(TAG, "auto-resume: no last song record (lastSongId='"
                    + lastSongId + "'), mount playlist only");
        }
    }

    // ---------------- 网络鉴权与媒体库同步 ----------------

    private void bindPlayerService() {
        Intent intent = new Intent(this, AudioPlayerService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void loadSavedServerConfig() {
        SharedPreferences sp = getSharedPreferences(JellyfinApiClient.PREF_NAME, MODE_PRIVATE);
        String serverUrl = sp.getString(JellyfinApiClient.KEY_SERVER_URL, JellyfinApiClient.DEFAULT_SERVER_URL);
        String userId = sp.getString(JellyfinApiClient.KEY_USER_ID, "");
        String token = sp.getString(JellyfinApiClient.KEY_ACCESS_TOKEN, "");

        JellyfinApiClient.getInstance().setServerUrl(serverUrl);
        JellyfinApiClient.getInstance().setAuthInfo(userId, token);

        // 冷启动就没网时不要开链 (2026-09-12 #4): 否则刷新按钮会被 "同步" 禁用态
        // 卡住几十秒 (拉库超时 + 重登超时各一轮), 而本地库其实已经能看能播。
        // 这里只置一句短状态, 按钮保持可点, 等用户网通后自己点刷新。
        if (!isNetworkAvailable()) {
            Log.w(TAG, "startup: no active network, skip connect chain (local library only)");
            CrashMonitor.breadcrumb("net", "startup skipped: no active network");
            tvServerStatus.setText("无网络");
            return;
        }

        // 启动即开链 (2026-09-12 #4): "连接中..." 由 beginRefreshChain 统一置上。
        // 开链后用户若在启动拉库期间手点刷新, 会被合并而不是再叠一套线程与回调。
        if (!beginRefreshChain("startup", false)) return;
        int epoch = refreshEpoch;

        // 无 Token 时 fetchLibrary 会自己转进静默登录环节 (并挂上防递归标记),
        // 这里不必再分岔, 一条路径一套守卫
        Log.i(TAG, "startup: hasToken=" + JellyfinApiClient.getInstance().hasToken());
        fetchLibrary(false, epoch);
    }

    /**
     * 静默重登。必须在 beginRefreshChain 之后调用 —— 它是同一条链的中间环节,
     * 链锁由调用方持有, 成功后接着拉库, 失败才终结整条链。
     *
     * @param interactive true 时走短超时 client, 让用户点刷新触发的重登也能快速失败
     * @param epoch       所属链代号, 回调失配即丢弃
     */
    private void autoSilentLogin(final String url, final String username, final String password,
                                 final boolean interactive, final int epoch) {
        tvServerStatus.setText("登录中...");
        Log.i(TAG, "autoSilentLogin: start interactive=" + interactive + " epoch=" + epoch);
        JellyfinApiClient.getInstance().authenticate(url, username, password, interactive,
                new JellyfinApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (epoch != refreshEpoch) {
                    Log.w(TAG, "autoSilentLogin onSuccess stale, dropped (epoch=" + epoch
                            + " current=" + refreshEpoch + ")");
                    return;
                }
                SharedPreferences sp = getSharedPreferences(JellyfinApiClient.PREF_NAME, MODE_PRIVATE);
                sp.edit()
                        .putString(JellyfinApiClient.KEY_SERVER_URL, url)
                        .putString(JellyfinApiClient.KEY_USERNAME, username)
                        .putString(JellyfinApiClient.KEY_PASSWORD, password)
                        .putString(JellyfinApiClient.KEY_USER_ID, JellyfinApiClient.getInstance().getUserId())
                        .putString(JellyfinApiClient.KEY_ACCESS_TOKEN, JellyfinApiClient.getInstance().getAccessToken())
                        .apply();

                // 拿到新 Token, 同一条链继续去拉库 (不重新开链, 否则会自己把自己挡住)
                tvServerStatus.setText("同步中...");
                fetchLibrary(interactive, epoch);
            }

            @Override
            public void onError(Exception e) {
                if (epoch != refreshEpoch) {
                    Log.w(TAG, "autoSilentLogin onError stale, dropped (epoch=" + epoch
                            + " current=" + refreshEpoch + ")");
                    return;
                }
                // 旧实现这里只有一句 "连接失败": 用户既不知道是密码错还是断网,
                // 也不知道该去设置页改什么 (2026-09-12 #4)
                Log.w(TAG, "autoSilentLogin failed kind=" + JellyfinApiClient.classifyError(e)
                        + " msg=" + e);
                showConnectFailure(e, interactive);
                endRefreshChain(epoch, "login failed");
            }
        });
    }

    private void showSettingsDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_settings);

        final SharedPreferences sp = getSharedPreferences(JellyfinApiClient.PREF_NAME, MODE_PRIVATE);
        final EditText etUrl = dialog.findViewById(R.id.etServerUrl);
        final EditText etUser = dialog.findViewById(R.id.etUsername);
        final EditText etPass = dialog.findViewById(R.id.etPassword);

        etUrl.setText(sp.getString(JellyfinApiClient.KEY_SERVER_URL, JellyfinApiClient.DEFAULT_SERVER_URL));
        etUser.setText(sp.getString(JellyfinApiClient.KEY_USERNAME, JellyfinApiClient.DEFAULT_USERNAME));
        etPass.setText(sp.getString(JellyfinApiClient.KEY_PASSWORD, JellyfinApiClient.DEFAULT_PASSWORD));

        dialog.findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        dialog.findViewById(R.id.btnSaveAuth).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String url = etUrl.getText().toString().trim();
                final String username = etUser.getText().toString().trim();
                final String password = etPass.getText().toString().trim();

                if (url.isEmpty() || username.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入完整服务器信息", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 用户正盯着弹窗等结果, 走短超时 client, 别让他干等 15s (2026-09-12 #4)
                JellyfinApiClient.getInstance().authenticate(url, username, password, true,
                        new JellyfinApiClient.ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        Toast.makeText(MainActivity.this, "Jellyfin 认证成功！", Toast.LENGTH_SHORT).show();
                        SharedPreferences.Editor editor = sp.edit();
                        editor.putString(JellyfinApiClient.KEY_SERVER_URL, url);
                        editor.putString(JellyfinApiClient.KEY_USERNAME, username);
                        editor.putString(JellyfinApiClient.KEY_PASSWORD, password);
                        editor.putString(JellyfinApiClient.KEY_USER_ID, JellyfinApiClient.getInstance().getUserId());
                        editor.putString(JellyfinApiClient.KEY_ACCESS_TOKEN, JellyfinApiClient.getInstance().getAccessToken());
                        editor.apply();

                        dialog.dismiss();
                        autoReloginAttempted = false;
                        // 新凭据已落库: 打断可能在飞的旧链重开, 否则旧 Token 那轮结果
                        // 还会把状态文案改回去, 新凭据要等下次刷新才生效
                        restartLibraryRefresh("settings saved");
                    }

                    @Override
                    public void onError(Exception e) {
                        // 原来只吐 e.getMessage() (英文 HTTP 码), 车主看不懂
                        Log.w(TAG, "settings auth failed kind=" + JellyfinApiClient.classifyError(e)
                                + " msg=" + e);
                        showConnectFailure(e, true);
                    }
                });
            }
        });

        dialog.show();
    }

    /**
     * 媒体库刷新入口 (2026-09-12 #4 重构): 自行开一条健壮链, 已有链在飞时合并。
     */
    private void refreshMediaLibrary() {
        if (!beginRefreshChain("refreshMediaLibrary", false)) return;
        fetchLibrary(false, refreshEpoch);
    }

    /**
     * 开启一次「登录 → 拉库」链。
     *
     * @return false 表示已有链在飞, 本次请求被合并 (调用方必须就此打住, 不得再发请求)
     */
    private boolean beginRefreshChain(String reason, boolean interactive) {
        if (isRefreshing) {
            Log.i(TAG, "refresh chain busy, merged: " + reason);
            return false;
        }
        isRefreshing = true;
        refreshInteractive = interactive;
        refreshEpoch++;
        noTokenLoginDone = false;
        // 点击瞬间的三件可见反馈: 状态文案、按钮文字、按钮禁用。
        // bg_nav_tab 没有 state_enabled=false 的图样, 光 setEnabled(false) 车主看不出
        // 任何区别, 所以必须同时改按钮文字, 否则观感仍然是「点了没反应」。
        // 进行中用 "同步" 而非 "同步中": 导航组是 wrap_content 且靠右对齐, 按钮变宽
        // 会把「全部歌曲/歌单」往左顶, 每次刷新都抖一下。等宽两字则完全不动版。
        tvServerStatus.setText("连接中...");
        btnNavRefresh.setText("同步");
        btnNavRefresh.setEnabled(false);
        Log.i(TAG, "refresh chain begin: " + reason + " interactive=" + interactive
                + " epoch=" + refreshEpoch);
        CrashMonitor.breadcrumb("net", "refresh begin " + reason + " interactive=" + interactive);
        armRefreshWatchdog();
        return true;
    }

    /** 链终结 (成功或最终失败): 丢弃过期回调, 解锁并恢复按钮可点 */
    private void endRefreshChain(int epoch, String reason) {
        if (epoch != refreshEpoch) {
            Log.w(TAG, "stale refresh settle ignored: epoch=" + epoch
                    + " current=" + refreshEpoch + " (" + reason + ")");
            return;
        }
        isRefreshing = false;
        mainHandler.removeCallbacks(refreshWatchdog);
        btnNavRefresh.setEnabled(true);
        btnNavRefresh.setText("刷新");
        Log.i(TAG, "refresh chain end: " + reason);
    }

    private void armRefreshWatchdog() {
        mainHandler.removeCallbacks(refreshWatchdog);
        mainHandler.postDelayed(refreshWatchdog,
                refreshInteractive ? REFRESH_WATCHDOG_INTERACTIVE_MS : REFRESH_WATCHDOG_ROBUST_MS);
    }

    /**
     * 强制拆掉在飞的链后重开。设置页存了新凭据就得走这里: 旧 Token 那轮结果已无意义,
     * 且它的迟到回调会把状态文案改回去。拆链会让 refreshEpoch 前的回调全部失配作废。
     */
    private void restartLibraryRefresh(String reason) {
        if (isRefreshing) {
            Log.i(TAG, "refresh chain dropped for restart: " + reason);
            isRefreshing = false;
            mainHandler.removeCallbacks(refreshWatchdog);
        }
        refreshMediaLibrary();
    }

    /**
     * 拉库环节。调用方必须已持有链锁 (beginRefreshChain 返回 true), 本方法负责终结。
     */
    private void fetchLibrary(final boolean interactive, final int epoch) {
        // 手上没 Token 就别再去撞一次必然的 401 (2026-09-12 #4): 那等于白等一轮
        // 超时/往返。直接在同一条链里进登录环节, 拿到 Token 再回来拉库。
        if (!JellyfinApiClient.getInstance().hasToken()) {
            if (noTokenLoginDone) {
                // 登录回调说成功却还是没 Token: 再递归下去就是死循环, 就此终结并给出结论
                Log.w(TAG, "fetchLibrary: 登录后仍无 Token, 终止本链 epoch=" + epoch);
                showConnectFailure(new JellyfinApiClient.ApiException(
                        JellyfinApiClient.ERR_AUTH, -1, "login succeeded but token empty"), interactive);
                endRefreshChain(epoch, "empty token after login");
                return;
            }
            noTokenLoginDone = true;
            SharedPreferences spNoToken = getSharedPreferences(JellyfinApiClient.PREF_NAME, MODE_PRIVATE);
            Log.i(TAG, "fetchLibrary: no token in hand, go straight to silent login");
            tvServerStatus.setText("登录中...");
            autoSilentLogin(
                    spNoToken.getString(JellyfinApiClient.KEY_SERVER_URL, JellyfinApiClient.DEFAULT_SERVER_URL),
                    spNoToken.getString(JellyfinApiClient.KEY_USERNAME, JellyfinApiClient.DEFAULT_USERNAME),
                    spNoToken.getString(JellyfinApiClient.KEY_PASSWORD, JellyfinApiClient.DEFAULT_PASSWORD),
                    interactive, epoch);
            return;
        }

        JellyfinApiClient.getInstance().fetchMusicItems(new JellyfinApiClient.ApiCallback<List<SongItem>>() {
            @Override
            public void onSuccess(List<SongItem> songs) {
                if (epoch != refreshEpoch) {
                    Log.w(TAG, "fetchLibrary onSuccess stale, dropped (epoch=" + epoch
                            + " current=" + refreshEpoch + ")");
                    return;
                }
                autoReloginAttempted = false;
                tvServerStatus.setText("已连接");
                endRefreshChain(epoch, "ok songs=" + (songs == null ? 0 : songs.size()));
                if (songs == null || songs.isEmpty()) {
                    Log.i(TAG, "fetchLibrary: 服务器返回空库, 保留现有列表");
                    return;
                }

                allSongsList = new ArrayList<>(songs);
                SongDao.getInstance(MainActivity.this).saveSongs(allSongsList);

                // 同步 Service 中的播放列表对象并校准 index
                if (isBound && playerService != null) {
                    playerService.updatePlaylist(allSongsList);
                }

                List<CategoryItem> categories = buildCategories(allSongsList);
                categoryAdapter.setCategories(categories);

                CategoryItem selectedCat = categoryAdapter.getSelectedCategory();
                String catName = selectedCat != null ? selectedCat.getName() : "全部歌曲";
                currentDisplayedSongs = filterSongsByCategory(allSongsList, catName);

                tvSongCount.setText(currentDisplayedSongs.size() + " 首");
                tvListTitle.setText("歌曲列表");
                songAdapter.setSongs(currentDisplayedSongs);

                if (!isAutoPlayInitialized) {
                    handleAutoPlayOrResume(currentDisplayedSongs);
                }
                // 服务器刷新重挂列表 (SongItem 全部为新对象实例), 无论是否走到
                // 自动续播分支都要重算高亮并定位到正在播的歌
                syncPlayingHighlight(true);
            }

            @Override
            public void onError(Exception e) {
                if (epoch != refreshEpoch) {
                    Log.w(TAG, "fetchLibrary onError stale, dropped (epoch=" + epoch
                            + " current=" + refreshEpoch + ")");
                    return;
                }
                int kind = JellyfinApiClient.classifyError(e);
                Log.w(TAG, "fetchLibrary failed kind=" + kind + " epoch=" + epoch + " msg=" + e);

                // 401/403 = 手里这份 Token 已被服务端判死。必须同时清掉内存与持久化的
                // Token: 不清的话用户每点一次刷新都要先拿死 Token 撞一轮 401 再重登,
                // 白等一次往返; 而持久化那份不清, 下次冷启动又是同一个死循环 (2026-09-12 #4)
                if (kind == JellyfinApiClient.ERR_AUTH) {
                    JellyfinApiClient.getInstance().clearAuth();
                    getSharedPreferences(JellyfinApiClient.PREF_NAME, MODE_PRIVATE)
                            .edit().remove(JellyfinApiClient.KEY_ACCESS_TOKEN).apply();
                    Log.w(TAG, "stale token dropped from memory and prefs");
                }

                if (!autoReloginAttempted) {
                    autoReloginAttempted = true;
                    SharedPreferences sp = getSharedPreferences(JellyfinApiClient.PREF_NAME, MODE_PRIVATE);
                    tvServerStatus.setText("重登中...");
                    // 同一条链内重登, 链锁继续持有, 不重新开链
                    autoSilentLogin(
                            sp.getString(JellyfinApiClient.KEY_SERVER_URL, JellyfinApiClient.DEFAULT_SERVER_URL),
                            sp.getString(JellyfinApiClient.KEY_USERNAME, JellyfinApiClient.DEFAULT_USERNAME),
                            sp.getString(JellyfinApiClient.KEY_PASSWORD, JellyfinApiClient.DEFAULT_PASSWORD),
                            interactive, epoch);
                } else {
                    // 重登也没救回来: 给出可诊断结论并终结, 让按钮恢复可点
                    showConnectFailure(e, interactive);
                    endRefreshChain(epoch, "fetch failed after relogin");
                }
            }
        }, interactive);
    }

    /**
     * 把底层异常翻译成「为什么失败 + 下一步做什么」(2026-09-12 #4)。
     * tvServerStatus 只有 160dp 宽、单行且 ellipsize=end, 所以那里只放极短结论,
     * 具体指引一律走 Toast。
     *
     * @param toast 用户主动触发的链才弹 Toast; 冷启动失败不打扰车主
     */
    private void showConnectFailure(Exception e, boolean toast) {
        int kind = JellyfinApiClient.classifyError(e);
        String status;
        String hint;
        switch (kind) {
            case JellyfinApiClient.ERR_TIMEOUT:
                status = "连接超时";
                hint = "连接超时, 请检查车机网络或服务器地址";
                break;
            case JellyfinApiClient.ERR_DNS:
                status = "地址解析失败";
                hint = "服务器地址无法解析, 请到设置里核对地址";
                break;
            case JellyfinApiClient.ERR_UNREACHABLE:
                status = "网络不可达";
                hint = "连不上服务器, 请检查车机网络或确认 Jellyfin 已启动";
                break;
            case JellyfinApiClient.ERR_TLS:
                status = "证书校验失败";
                hint = "HTTPS 证书校验失败, 请检查服务器证书链";
                break;
            case JellyfinApiClient.ERR_AUTH:
                status = "登录失效";
                hint = "账号或密码不正确, 请到设置里重新填写";
                break;
            case JellyfinApiClient.ERR_SERVER:
                status = "服务器错误";
                hint = "Jellyfin 服务器内部错误, 请稍后重试";
                break;
            case JellyfinApiClient.ERR_HTTP:
                status = "接口异常";
                hint = "服务器返回异常, 请到设置里核对地址是否为 Jellyfin 根地址";
                break;
            case JellyfinApiClient.ERR_PARSE:
                status = "数据异常";
                hint = "服务器返回的数据无法解析, 请确认 Jellyfin 版本兼容";
                break;
            default:
                status = "同步失败";
                hint = "同步失败: " + (e != null && e.getMessage() != null ? e.getMessage() : "未知错误");
                break;
        }
        if (e instanceof JellyfinApiClient.ApiException) {
            int code = ((JellyfinApiClient.ApiException) e).getHttpCode();
            if (code > 0) hint = hint + " (HTTP " + code + ")";
        }

        tvServerStatus.setText(status);
        Log.w(TAG, "connect failure surfaced: kind=" + kind + " status=" + status
                + " raw=" + e);
        CrashMonitor.breadcrumb("net", "connect failed kind=" + kind + " " + e);
        if (toast) {
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 车机整体断网时短路, 不必再去撞 OkHttp 的连接超时 (2026-09-12 #4)。
     * 只用 API 1 就有的 getActiveNetworkInfo(), 不碰 API 21+/23+ 的
     * NetworkCapabilities 那套, 以守住 minSdk 18。判定不出来时一律放行,
     * 绝不能因为探测失败把正常车主挡在门外。
     */
    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            Log.w(TAG, "isNetworkAvailable probe failed, assume online: " + e);
            return true;
        }
    }

    // ---------------- 播放回调与歌词先显后同 ----------------

    @Override
    public void onSongChanged(final SongItem song, int index) {
        if (song == null) return;

        tvCurrentTitle.setText(song.getName());
        tvCurrentArtist.setText(song.getArtist() + " · " + song.getAlbum());
        updateCurrentFavBtn(song.isFavorite());

        String folder = song.getFolderName();
        if (folder != null && !folder.trim().isEmpty() && !"未分类文件夹".equals(folder)) {
            tvBadgeFolder.setText(folder);
            tvBadgeFolder.setVisibility(View.VISIBLE);
        } else {
            tvBadgeFolder.setVisibility(View.GONE);
        }

        long sec = (song.getDurationMs() / 1000) % 60;
        long min = (song.getDurationMs() / 1000) / 60;
        tvTotalTime.setText(String.format("%02d:%02d", min, sec));
        // 切歌即归零: max 先用元数据时长 (服务首个进度回调会用真实时长纠正);
        // 进度、时间、填充条必须同帧复位, 否则残留上一首的比例尺与位置,
        // 播放中断时更会永久冻结成分叉状态
        seekBarProgress.setMax((int) song.getDurationMs());
        seekBarProgress.setProgress(0);
        tvCurrentTime.setText("00:00");
        updateSeekFill();
        // 切歌即给出缓冲反馈 (2026-09-12 缓冲/预取): 起播门槛 / 加载期间先显「缓冲中…」,
        // 后续 onBufferingUpdate 会刷新百分比并在稳定后隐藏
        if (tvBuffering != null) {
            tvBuffering.setText("缓冲中…");
            tvBuffering.setVisibility(View.VISIBLE);
        }
        // 换曲后上一首的进度基准作废: 不清掉的话, 新一首第一个 tick 会被「同曲倒退」
        // 防线误判 (id 相同但基准来自上一轮 generation 的情况尤其危险) (2026-09-12 #1)
        lastProgressSaveTrackId = null;
        lastProgressSaveMs = -1;

        // 歌曲不在当前列表时也要清掉旧高亮 (2026-09-09 实车反馈):
        // 否则切列表后旧 index 位置的歌被错误点亮, 与实际播放脱节
        syncPlayingHighlight(true);

        saveCurrentState();

        String cachedLrc = SongDao.getInstance(this).getLyric(song.getId());
        if (cachedLrc != null && !cachedLrc.isEmpty()) {
            lyricAdapter.setLyrics(LyricLine.parseLrc(cachedLrc));
            tvLyricsEmpty.setVisibility(View.GONE);
        } else {
            lyricAdapter.setLyrics(null);
            tvLyricsEmpty.setVisibility(View.VISIBLE);
        }

        JellyfinApiClient.getInstance().fetchLyrics(song.getId(), new JellyfinApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String lrcContent) {
                if (lrcContent != null && !lrcContent.isEmpty()) {
                    SongDao.getInstance(MainActivity.this).saveLyric(song.getId(), lrcContent);
                    lyricAdapter.setLyrics(LyricLine.parseLrc(lrcContent));
                    tvLyricsEmpty.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(Exception e) {
                // 请求失败保留 SQLite 中已读取的歌词
            }
        });
    }

    @Override
    public void onPlayStateChanged(boolean isPlaying) {
        btnPlayPause.setText(isPlaying ? "||" : "▶");
        saveCurrentState();
    }

    /**
     * 缓冲 % 上 UI (2026-09-12 缓冲/预取): 缓冲时显示「缓冲 43%」, 总长未知显示「缓冲中…」,
     * 进入稳定播放 (buffering=false) 即隐藏。回调已在主线程, 直接操作控件。
     */
    @Override
    public void onBufferingUpdate(int percent, boolean buffering) {
        if (tvBuffering == null) return;
        if (!buffering) {
            if (tvBuffering.getVisibility() != View.GONE) {
                tvBuffering.setVisibility(View.GONE);
            }
            return;
        }
        tvBuffering.setText(percent < 0 ? "缓冲中…" : "缓冲 " + percent + "%");
        if (tvBuffering.getVisibility() != View.VISIBLE) {
            tvBuffering.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onProgressUpdate(int currentMs, int totalMs) {
        if (!isUserSeeking) {
            // max 与播放器真实时长保持一致, 保证滑块与自绘填充条共用同一比例尺
            if (totalMs > 0 && seekBarProgress.getMax() != totalMs) {
                seekBarProgress.setMax(totalMs);
                long tSec = (totalMs / 1000) % 60;
                long tMin = (totalMs / 1000) / 60;
                tvTotalTime.setText(String.format("%02d:%02d", tMin, tSec));
            }
            seekBarProgress.setProgress(currentMs);
            long sec = (currentMs / 1000) % 60;
            long min = (currentMs / 1000) / 60;
            tvCurrentTime.setText(String.format("%02d:%02d", min, sec));
            updateSeekFill();
        }

        int highlightIdx = lyricAdapter.updateHighlight(currentMs);
        if (highlightIdx >= 0 && !lyricUserDragging) {
            centerLyricHighlight(highlightIdx);
        }

        // 5秒节流异步写入进度
        long now = System.currentTimeMillis();
        if (now - lastProgressSaveTime > PlaybackStateMachine.PROGRESS_SAVE_THROTTLE_MS) {
            lastProgressSaveTime = now;
            if (isBound && playerService != null && playerService.isPlaying()) {
                SongItem current = playerService.getCurrentSong();
                if (current != null) {
                    saveProgressForSong(current, totalMs, currentMs, "tick");
                }
            }
        }
    }

    /**
     * 进度落库前的治理 (2026-09-09 起, 2026-09-12 #1 加强): seek 越界/异常回调/切歌竞态
     * 都可能把接近曲尾甚至等于时长的脏值写进 song_progress, 下次点这首歌就直接被 seek 到
     * 曲尾 (观感: 切歌后跳到末尾, 几秒后又 COMPLETED 跳下一首)。
     *
     * 两道防线:
     *  1) 清洗按「播放器真实时长优先, Jellyfin 元数据兜底」判定贴尾 —— 元数据缺失或比
     *     转码流偏大时, 只用元数据判定会放过脏值;
     *  2) 同一首歌的进度明显倒退则不写 —— 切歌竞态里可能读到上一首贴尾的 position 却挂在
     *     新一首的 id 上, 或把已存的大断点覆盖成小值。用户手动 seek 造成的倒退是合法的,
     *     由 onStopTrackingTouch 清空追踪器放行。
     *
     * @param realDurationMs 播放器上报的真实时长, <=0 表示未知
     */
    private void saveProgressForSong(SongItem song, int realDurationMs, int progressMs,
                                     String reason) {
        if (song == null || song.getId() == null) return;
        int cleanMs = sanitizeProgressForSave(song, realDurationMs, progressMs);
        boolean backward = lastProgressSaveTrackId != null
                && lastProgressSaveTrackId.equals(song.getId())
                && lastProgressSaveMs > 0 && cleanMs > 0
                && cleanMs < lastProgressSaveMs - PROGRESS_BACKWARD_TOLERANCE_MS;
        if (backward) {
            Log.w(TAG, "Skip backward progress save (" + reason + "): id=" + song.getId()
                    + " name=" + song.getName() + " saved=" + cleanMs
                    + "ms lastSaved=" + lastProgressSaveMs
                    + "ms metaDuration=" + song.getDurationMs()
                    + "ms realDuration=" + realDurationMs + "ms decision=skip-stale-tick");
            return;
        }
        if (cleanMs != progressMs) {
            Log.i(TAG, "Sanitized progress before save (" + reason + "): id=" + song.getId()
                    + " name=" + song.getName() + " saved=" + progressMs + "->" + cleanMs
                    + "ms metaDuration=" + song.getDurationMs()
                    + "ms realDuration=" + realDurationMs + "ms decision=progress->" + cleanMs);
        }
        SongDao.getInstance(this).saveSongProgress(song.getId(), cleanMs);
        lastProgressSaveTrackId = song.getId();
        lastProgressSaveMs = cleanMs;
    }

    /**
     * 贴尾/越界断点清洗, 判定逻辑收口在 {@link PlaybackStateMachine#sanitizeProgressForSave}
     * 以便与起播侧共用同一阈值并做单测 (2026-09-12 #1)。
     */
    private static int sanitizeProgressForSave(SongItem song, int realDurationMs, int progressMs) {
        long metaMs = song != null ? song.getDurationMs() : 0L;
        return PlaybackStateMachine.sanitizeProgressForSave(metaMs, realDurationMs, progressMs);
    }

    /**
     * 从 song_progress 取出的断点在交给播放服务前先清洗一次 (2026-09-12 #1)。
     *
     * 点歌 / 冷启动自动续播三条路径共用。此刻还没有播放器实例, 真实时长未知, 只能按
     * 这首歌自己的元数据时长判定; 服务侧 sanitizeSeekMs 会再按元数据钳一次,
     * prepared 回调再按播放器真实时长钳一次, 起播后还有 tick 兜底 —— 这一道的价值是
     * 让「贴尾断点被丢弃」的 decision 在 UI 日志里就能看到, 便于实车对时间线。
     */
    private int resumePointForPlayback(SongItem song, int savedMs, String reason) {
        int cleanMs = sanitizeProgressForSave(song, 0, savedMs);
        if (cleanMs != savedMs) {
            Log.w(TAG, "Drop end-of-track resume point before playback (" + reason + "): id="
                    + (song != null ? song.getId() : null)
                    + " name=" + (song != null ? song.getName() : null)
                    + " saved=" + savedMs + "->" + cleanMs
                    + "ms metaDuration=" + (song != null ? song.getDurationMs() : 0L)
                    + "ms realDuration=unknown decision=replay-from-start");
        }
        return cleanMs;
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 歌词高亮行视口居中: 高亮行上下都保留歌词。
     *  目标行在屏内 → 计算到视口中心的增量平滑滚动 (逐行跟进);
     *  不在屏内 (换歌/手动 seek 的远距离跳转) → 先跳转, 布局后一次性居中。 */
    private void centerLyricHighlight(final int position) {
        final RecyclerView.LayoutManager lm = rvLyrics.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;
        final LinearLayoutManager llm = (LinearLayoutManager) lm;
        final int rvHeight = rvLyrics.getHeight();
        if (rvHeight <= 0) return;
        View target = llm.findViewByPosition(position);
        if (target != null) {
            int targetCenter = (llm.getDecoratedTop(target) + llm.getDecoratedBottom(target)) / 2;
            int dy = targetCenter - rvHeight / 2;
            if (dy != 0) {
                rvLyrics.smoothScrollBy(0, dy);
            }
        } else {
            rvLyrics.scrollToPosition(position);
            rvLyrics.post(new Runnable() {
                @Override
                public void run() {
                    View v = llm.findViewByPosition(position);
                    if (v != null) {
                        llm.scrollToPositionWithOffset(position, rvHeight / 2 - v.getHeight() / 2);
                    }
                }
            });
        }
    }

    /** 细进度填充条: 直接读 SeekBar 自身 progress/max, 与原生滑块共用同一比例尺, 永不分叉。
     *  宽度终点对齐滑块中心 (滑块行程是 宽−滑块宽, 中心再补半个滑块宽), 否则填充会超出滑块 */
    private void updateSeekFill() {
        if (seekFill == null || seekBarProgress == null) return;
        int w = seekBarProgress.getWidth();
        int max = seekBarProgress.getMax();
        if (w <= 0) return;
        float frac = max > 0 ? seekBarProgress.getProgress() / (float) max : 0f;
        if (frac < 0f) frac = 0f;
        if (frac > 1f) frac = 1f;
        int width;
        android.graphics.drawable.Drawable thumb = seekBarProgress.getThumb();
        if (thumb != null) {
            int thumbW = thumb.getIntrinsicWidth();
            width = (int) (frac * (w - thumbW) + thumbW / 2f + 0.5f);
        } else {
            width = (int) (w * frac);
        }
        ViewGroup.LayoutParams lp = seekFill.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            seekFill.setLayoutParams(lp);
        }
    }

    private void saveCurrentState() {
        if (isBound && playerService != null) {
            SongItem current = playerService.getCurrentSong();
            if (current != null) {
                saveState(KEY_LAST_SONG_ID, current.getId());
                // 与 5s 节流落库走同一套治理 (2026-09-10), 并按播放器真实时长判定贴尾 (#1)
                int positionMs = playerService.getCurrentPositionMs();
                if (positionMs > 0 || playerService.isPlaying()) {
                    saveProgressForSong(current, playerService.getCurrentRealDurationMs(),
                            positionMs, "state");
                } else {
                    // 起播中/尚未出声: position 恒为 0, 落库会把正要用的断点抹成 0
                    // (点歌 → onSongChanged → saveCurrentState 正好踩这条)。真播完的清零
                    // 已由服务侧 onCompletion 负责, 这里跳过不会留下脏断点。
                    Log.i(TAG, "Skip progress save before playback starts: id=" + current.getId()
                            + " name=" + current.getName() + " decision=keep-existing");
                }
            }
            CategoryItem currentCat = categoryAdapter.getSelectedCategory();
            if (currentCat != null) {
                saveState(KEY_LAST_CAT, currentCat.getName());
            }
            saveState("play_mode", String.valueOf(playerService.getPlayMode()));
        }
    }

    private void saveState(String key, String value) {
        SongDao.getInstance(this).saveState(key, value);
    }

    private String getLastSongId() {
        return SongDao.getInstance(this).getState(KEY_LAST_SONG_ID, "");
    }

    private String getLastCategoryName() {
        return SongDao.getInstance(this).getState(KEY_LAST_CAT, "全部歌曲");
    }

    private int getSavedPlayMode() {
        try {
            return Integer.parseInt(SongDao.getInstance(this).getState("play_mode", "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- 物理返回键双击退出与沉浸模式 ----------------

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime < 2000) {
            exitPlayerCompletely();
        } else {
            lastBackPressTime = now;
            Toast.makeText(this, "再按一次退出 ZSpaceCarPlayer", Toast.LENGTH_SHORT).show();
        }
    }

    /** 完全退出: 停止播放并释放全部音频资源 (播放器/音效/缓冲源/焦点/媒体键),
     *  移除前台通知并停止服务; onDestroy 里再结束进程, 不留任何后台残留。
     *  车机场景「退出=停」: 与 Home 键后台听歌是两条路径, 互不影响。 */
    private void exitPlayerCompletely() {
        exitCompletely = true;
        if (isBound && playerService != null) {
            try {
                playerService.stopAndReleaseAllAudioResources();
            } catch (Exception ignored) {}
        } else {
            Intent stopIntent = new Intent(this, AudioPlayerService.class);
            stopIntent.setAction(AudioPlayerService.ACTION_STOP_AND_RELEASE);
            try { startService(stopIntent); } catch (Exception ignored) {}
        }
        finish();
    }

    /**
     * 车机「静音键」= 暂停/播放 (2026-09-11 实车 #6)。
     *
     * 为什么用 dispatchKeyEvent 而不是 onKeyDown: 音量/静音类按键在 AOSP 上由
     * PhoneWindowManager 在入队前就截走交给 AudioService, 根本走不到 Activity.onKeyDown;
     * dispatchKeyEvent 是窗口拿到 KeyEvent 的第一站, 只有这里有机会拦住 KEYCODE_VOLUME_MUTE
     * 并阻止系统继续处理 (否则车机会在暂停的同时把媒体流也静音)。
     * 只拦静音键, VOLUME_UP/DOWN 一律原样放行 —— App 绝不接管音量调节 (#5)。
     * 刻意不含 KEYCODE_MEDIA_PLAY_PAUSE: 那条路由 RemoteControlClient/MediaButtonReceiver
     * 处理 (方向盘键现网可用), 窗口里再拦一次有双重触发变成「暂停又播放」的风险。
     * App 在后台/无焦点时这里收不到事件, 只能靠 MediaButtonReceiver 的媒体键通路,
     * 静音键是否会产生 MEDIA_BUTTON 广播取决于 ROM (局限见交付说明)。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) {
            return super.dispatchKeyEvent(event);
        }
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            // 实车抓真实 keyCode: 车厂可能用自定义键值, 这行能把所有物理按键打出来
            // (adb logcat -s MainActivity | grep "key down")
            Log.i(TAG, "Activity key down: keyCode=" + keyCode + " scanCode=" + event.getScanCode());
        }
        if (MediaButtonReceiver.isMuteKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Log.i(TAG, "Mute key intercepted as pause toggle: keyCode=" + keyCode);
                handleMuteKeyPauseToggle();
            }
            // DOWN 与 UP 都要吞掉, 否则系统会收到不成对的 UP 而补做静音动作
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 静音键 → 暂停/播放切换。服务未绑定时退回 startService, 与媒体键走同一条 action。 */
    private void handleMuteKeyPauseToggle() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastMuteKeyToggleAtMs < MUTE_KEY_TOGGLE_DEBOUNCE_MS) {
            Log.i(TAG, "Mute key toggle debounced (" + (now - lastMuteKeyToggleAtMs) + "ms)");
            return;
        }
        lastMuteKeyToggleAtMs = now;
        if (isBound && playerService != null) {
            playerService.togglePause();
            return;
        }
        Intent intent = new Intent(this, AudioPlayerService.class);
        intent.setAction(MediaButtonReceiver.ACTION_TOGGLE_PAUSE);
        try {
            startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Mute key toggle failed, service not startable", e);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentState();
    }

    @Override
    protected void onDestroy() {
        saveCurrentState();
        // 摘掉刷新看门狗: 否则它会在 Activity 销毁后触发, 往死掉的 Context 上弹 Toast
        mainHandler.removeCallbacks(refreshWatchdog);
        // 远程升级收尾 (2026-09-12): 停在飞的清单请求与 APK 下载、摘看门狗、关对话框。
        // 不收的话下载会在后台继续吃车机流量, 回调还会往已销毁的 Activity 上弹 Toast。
        cancelUpdateWork();
        if (debugRouteReceiver != null) {
            try { unregisterReceiver(debugRouteReceiver); } catch (Exception ignored) {}
            debugRouteReceiver = null;
        }
        if (isBound && playerService != null) {
            playerService.setOnPlayerStateChangeListener(null);
        }
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        super.onDestroy();
        if (exitCompletely) {
            // 音频资源已在 exitPlayerCompletely 释放; 这里结束进程, 确保代理 accept 线程、
            // 原生库与一切后台线程零残留 (进度状态已在 onPause 落库)
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    // ------------------------------------------------------------------ //
    //  车机音频路由调试入口 (HAL 绑卡错乱修复工具)
    //
    //  2026-09-08 实车根因: NeuSoft HAL 按 persist.neusoft.iPod.mode=CarPlay
    //  把 primary 输出绑到 card2 (Carplay 回声参考卡, 不接喇叭), card1 (tef6638
    //  功放) closed → 整车 Android 音频全局哑。persist 属性无 root 不可改,
    //  HAL 的 audio.primary.C3ALFUS.so 只认运行时 setParameters 命令:
    //  "set route primary|CARPLAYAUDIO|BTAUDIO|HFT|NAVI TTS|RING|TTS|VR|EMPTY"。
    //  shell 调不到 AudioFlinger.setParameters, 唯一通路是本 app
    //  (持有 MODIFY_AUDIO_SETTINGS)。用法 (adb):
    //    am broadcast -a com.ktools.zspacecarplayer.DEBUG_AUDIO_ROUTE \
    //        --es route primary
    // ------------------------------------------------------------------ //
    private android.content.BroadcastReceiver debugRouteReceiver;

    private void registerDebugRouteReceiver() {
        debugRouteReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String route = intent.getStringExtra("route");
                if (route == null || route.trim().isEmpty()) route = "primary";
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;
                String cmd = "set route " + route.trim();
                try {
                    am.setParameters(cmd);
                    Log.i(TAG, "debug route sent: " + cmd);
                    Toast.makeText(MainActivity.this, "已发送: " + cmd, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.w(TAG, "debug route failed: " + cmd, t);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.ktools.zspacecarplayer.DEBUG_AUDIO_ROUTE");
        try {
            registerReceiver(debugRouteReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "debug route receiver register failed", e);
        }
    }

    // ------------------------------------------------------------------ //
    //  远程升级 OTA (2026-09-12)
    //
    //  入口在设置页「关于与升级」分块: 手动「检查更新」按钮 + 「启动时自动检查」开关。
    //  动线: 拉版本清单 → 比对 versionCode → 带进度下载 APK → SHA-256/大小双校验 →
    //        拉起系统安装界面, 由车主亲手点「安装」。车机没有静默安装权限, 也不该有。
    //
    //  三条硬要求 (与本项目刷新链的验收标准一致):
    //   1. 点了必有反应: 点击瞬间就改按钮文案 + 禁用 + Toast, 并挂 45s 看门狗,
    //      万一回调彻底没回来 (ROM 冻结进程/派发异常) 也不会把按钮永久卡死;
    //   2. 校验失败绝不安装: 交给 UpdateInstaller 的文件必然已通过 sha256,
    //      校验失败时 ApkDownloader 已把文件删掉, 这里只负责把原因讲给车主;
    //   3. 绝不阻塞主线程: 网络全在 OkHttp 派发线程, 回调经 mainHandler 切回来。
    // ------------------------------------------------------------------ //

    private UpdateChecker updateChecker;
    private ApkDownloader apkDownloader;
    /** 更新对话框: 同一个实例承载「发现新版 / 下载中 / 下载失败可重试」三态 */
    private Dialog updateDialog;
    private Button btnSettingCheckUpdate;
    private Button btnSettingAutoUpdateValue;
    /** 检查是否在飞: 与 isRefreshing 同理, 进行中再点一律合并, 不叠请求 */
    private boolean updateCheckInFlight = false;
    /** 本进程是否已排过自动检查: 防止配置变更重建 Activity 时重复排队 */
    private boolean autoUpdateCheckScheduled = false;

    /**
     * 自动检查的延迟。冷启动这条线上依次是: 鉴权 → 拉媒体库(大库可能几十秒) →
     * 崩溃报告上传(CrashMonitor 的 12s)。更新检查排在最后, 免得三方一起抢车机
     * 那条本就窄的上行带宽 —— 清单只有几百字节, 晚 20 秒毫无体感差别。
     */
    private static final long AUTO_UPDATE_CHECK_DELAY_MS = 20_000L;
    /** 检查看门狗: 清单请求最坏 = 8s 连接 + 12s 读, 45s 富余 (与刷新链口径一致) */
    private static final long UPDATE_CHECK_WATCHDOG_MS = 45_000L;

    private final Runnable updateCheckWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!updateCheckInFlight) return;
            Log.w(TAG, "update check watchdog fired, force unlock");
            CrashMonitor.breadcrumb("update", "check watchdog fired");
            settleUpdateCheck();
            Toast.makeText(MainActivity.this, "检查更新超时, 请确认车机网络后重试",
                    Toast.LENGTH_LONG).show();
        }
    };

    private final Runnable autoUpdateCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            Log.i(TAG, "auto update check fired");
            checkForUpdate(true);
        }
    };

    private UpdateChecker updateChecker() {
        if (updateChecker == null) {
            updateChecker = new UpdateChecker(getApplicationContext());
        }
        return updateChecker;
    }

    private ApkDownloader apkDownloader() {
        if (apkDownloader == null) {
            apkDownloader = new ApkDownloader(getApplicationContext());
        }
        return apkDownloader;
    }

    /** 设置页「关于与升级」两行的挂载 (由 setupSettingsPageListeners 调用) */
    private void setupUpdateSection() {
        if (layoutSettingsPage == null) return;

        View rowCheck = layoutSettingsPage.findViewById(R.id.rowSettingCheckUpdate);
        btnSettingCheckUpdate = layoutSettingsPage.findViewById(R.id.btnSettingCheckUpdate);
        View rowAuto = layoutSettingsPage.findViewById(R.id.rowSettingAutoUpdate);
        btnSettingAutoUpdateValue = layoutSettingsPage.findViewById(R.id.btnSettingAutoUpdateValue);
        TextView tvVersion = layoutSettingsPage.findViewById(R.id.tvSettingAppVersion);

        if (tvVersion != null) {
            tvVersion.setText("当前版本 " + UpdateChecker.currentVersionName(this)
                    + " (内部号 " + UpdateChecker.currentVersionCode(this) + ")");
        }
        updateAutoCheckLabel();

        // 与引擎开关同理: 整行和右侧药丸都挂同一个监听。药丸是 Button, 默认 clickable=true,
        // 只挂行不挂药丸的话, 落在药丸上的触点会被它自己吞掉 —— 那正是最显眼的可点目标。
        final View.OnClickListener checkListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CrashMonitor.breadcrumb("update", "check update clicked");
                checkForUpdate(false);
            }
        };
        if (rowCheck != null) rowCheck.setOnClickListener(checkListener);
        if (btnSettingCheckUpdate != null) btnSettingCheckUpdate.setOnClickListener(checkListener);

        final View.OnClickListener autoListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAutoUpdateCheck();
            }
        };
        if (rowAuto != null) rowAuto.setOnClickListener(autoListener);
        if (btnSettingAutoUpdateValue != null) btnSettingAutoUpdateValue.setOnClickListener(autoListener);

        // 顺手清掉上次被打断的半截下载 (后台线程执行, 不碰主线程)
        apkDownloader().purgePartialDownloads();
    }

    /** 「启动时自动检查更新」开关: 翻转偏好 → 落盘 → 改药丸文案 → Toast */
    private void toggleAutoUpdateCheck() {
        SharedPreferences sp = getSharedPreferences(UpdateChecker.PREF_NAME, MODE_PRIVATE);
        boolean next = !sp.getBoolean(UpdateChecker.PREF_KEY_AUTO_CHECK,
                UpdateChecker.DEFAULT_AUTO_CHECK);
        sp.edit().putBoolean(UpdateChecker.PREF_KEY_AUTO_CHECK, next).apply();
        updateAutoCheckLabel();
        Log.i(TAG, "auto update check pref -> " + next);
        Toast.makeText(this, next
                ? "已开启: 每次启动约 20 秒后静默检查, 有新版本才提示"
                : "已关闭启动时自动检查更新", Toast.LENGTH_LONG).show();
    }

    private void updateAutoCheckLabel() {
        if (btnSettingAutoUpdateValue == null) return;
        boolean on = getSharedPreferences(UpdateChecker.PREF_NAME, MODE_PRIVATE)
                .getBoolean(UpdateChecker.PREF_KEY_AUTO_CHECK, UpdateChecker.DEFAULT_AUTO_CHECK);
        btnSettingAutoUpdateValue.setText(on ? "开" : "关");
    }

    /** 冷启动后排一次静默检查 (由 onCreate 调用) */
    private void scheduleAutoUpdateCheck() {
        boolean enabled = getSharedPreferences(UpdateChecker.PREF_NAME, MODE_PRIVATE)
                .getBoolean(UpdateChecker.PREF_KEY_AUTO_CHECK, UpdateChecker.DEFAULT_AUTO_CHECK);
        if (!enabled) {
            Log.i(TAG, "auto update check disabled by preference");
            return;
        }
        if (autoUpdateCheckScheduled) return;
        autoUpdateCheckScheduled = true;
        mainHandler.postDelayed(autoUpdateCheckRunnable, AUTO_UPDATE_CHECK_DELAY_MS);
        Log.i(TAG, "auto update check scheduled in " + AUTO_UPDATE_CHECK_DELAY_MS + "ms");
    }

    /**
     * 检查更新。
     *
     * @param silent true = 启动时自动检查: 失败与「已是最新」都不出声, 只有真有新版才弹框,
     *               不打扰正在开车的人; false = 车主手点, 任何结果都必须给可见反馈。
     */
    private void checkForUpdate(final boolean silent) {
        if (updateCheckInFlight) {
            Log.i(TAG, "update check clicked while in flight, merged");
            if (!silent) {
                Toast.makeText(this, "正在检查更新, 请稍候…", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!isNetworkAvailable()) {
            // 整体断网时不必再去撞 8s 连接超时, 立刻给出可诊断结论
            Log.w(TAG, "update check blocked: no active network");
            CrashMonitor.breadcrumb("update", "check blocked: no active network");
            if (!silent) {
                Toast.makeText(this, "车机当前无网络连接, 无法检查更新", Toast.LENGTH_LONG).show();
            }
            return;
        }

        updateCheckInFlight = true;
        setCheckUpdateBusy(true);
        if (!silent) {
            // 点击瞬间就先给一句反馈: 清单请求最坏要 20 秒, 静默等待就是「点了没反应」
            Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        }

        final int currentVc = UpdateChecker.currentVersionCode(this);
        final String currentVn = UpdateChecker.currentVersionName(this);
        Log.i(TAG, "check update begin silent=" + silent + " currentVc=" + currentVc
                + " url=" + UpdateChecker.manifestUrlOrDefault());
        CrashMonitor.breadcrumb("update", "check begin silent=" + silent + " vc=" + currentVc);
        mainHandler.removeCallbacks(updateCheckWatchdog);
        mainHandler.postDelayed(updateCheckWatchdog, UPDATE_CHECK_WATCHDOG_MS);

        updateChecker().check(UpdateChecker.manifestUrlOrDefault(),
                new UpdateChecker.ResultCallback() {
            @Override
            public void onManifest(UpdateManifest manifest) {
                settleUpdateCheck();
                if (isFinishing()) return;
                boolean newer = UpdateChecker.isUpdateAvailable(manifest, currentVc);
                boolean forced = UpdateChecker.shouldForceUpdate(manifest, currentVc);
                Log.i(TAG, "manifest vc=" + manifest.getVersionCode()
                        + " vn=" + manifest.getVersionName() + " current=" + currentVc
                        + " newer=" + newer + " forced=" + forced);
                CrashMonitor.breadcrumb("update", "manifest vc=" + manifest.getVersionCode()
                        + " newer=" + newer + " forced=" + forced);
                if (newer || forced) {
                    showUpdateDialog(manifest, currentVn, currentVc, forced);
                } else if (!silent) {
                    Toast.makeText(MainActivity.this, "已是最新版本 (" + currentVn + ")",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(Exception e) {
                settleUpdateCheck();
                String msg = (e == null || e.getMessage() == null) ? "检查更新失败" : e.getMessage();
                Log.w(TAG, "check update failed silent=" + silent + ": " + msg);
                CrashMonitor.breadcrumb("update", "check failed: " + msg);
                if (!silent && !isFinishing()) {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** 检查终结 (成功/失败/看门狗): 解锁按钮并撤看门狗 */
    private void settleUpdateCheck() {
        updateCheckInFlight = false;
        mainHandler.removeCallbacks(updateCheckWatchdog);
        setCheckUpdateBusy(false);
    }

    private void setCheckUpdateBusy(boolean busy) {
        Button btn = resolveCheckUpdateButton();
        if (btn == null) return;
        // bg_soft_pill 没有 state_enabled=false 的图样, 光 setEnabled(false) 车主看不出任何
        // 区别, 必须同时改文案 —— 与刷新按钮同一个教训。
        btn.setText(busy ? "检查中" : "检查更新");
        btn.setEnabled(!busy);
    }

    /** 自动检查可能先于车主打开设置页触发, 此时按钮还没被 setupUpdateSection 赋值 */
    private Button resolveCheckUpdateButton() {
        if (btnSettingCheckUpdate == null && layoutSettingsPage != null) {
            btnSettingCheckUpdate = layoutSettingsPage.findViewById(R.id.btnSettingCheckUpdate);
        }
        return btnSettingCheckUpdate;
    }

    /**
     * 弹出更新对话框。三态共用一个布局 (dialog_update): 车机上从「有新版」到「装完」是
     * 一条不该断的动线, 中途换弹窗会让车主以为要从头再来一遍。
     */
    private void showUpdateDialog(final UpdateManifest manifest, String currentVn,
                                  int currentVc, boolean forced) {
        if (isFinishing()) return;
        // 自动检查与手动点击可能前后脚都命中: 只保留一个对话框
        dismissUpdateDialog();

        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_update);

        TextView tvTitle = dialog.findViewById(R.id.tvUpdateTitle);
        TextView tvVersion = dialog.findViewById(R.id.tvUpdateVersion);
        TextView tvSize = dialog.findViewById(R.id.tvUpdateSize);
        TextView tvWarn = dialog.findViewById(R.id.tvUpdateWarn);
        TextView tvNotes = dialog.findViewById(R.id.tvUpdateNotes);
        final ProgressBar progressBar = dialog.findViewById(R.id.progressUpdate);
        final TextView tvProgress = dialog.findViewById(R.id.tvUpdateProgress);
        final Button btnAction = dialog.findViewById(R.id.btnUpdateAction);
        final Button btnLater = dialog.findViewById(R.id.btnUpdateLater);

        tvTitle.setText(forced ? "需要更新" : "发现新版本");
        String newVn = manifest.getVersionName();
        tvVersion.setText(currentVn + " (" + currentVc + ")  →  "
                + (newVn == null || newVn.length() == 0 ? "新版本" : newVn)
                + " (" + manifest.getVersionCode() + ")");
        tvSize.setText("安装包 " + ApkDownloader.humanSize(manifest.getSizeBytes())
                + " · 下载后自动校验 SHA-256");
        if (forced) {
            // 措辞更强, 但依然由车主手动确认安装: 车机上没有静默安装这条路
            tvWarn.setVisibility(View.VISIBLE);
            tvWarn.setText(manifest.isMandatory()
                    ? "服务端标记为必须更新, 建议立即安装"
                    : "当前版本已低于服务端要求的最低版本, 需要更新后才能正常使用");
        }
        String notes = manifest.getNotes();
        if (notes == null || notes.trim().length() == 0) {
            tvNotes.setVisibility(View.GONE);
        } else {
            tvNotes.setText("更新说明:\n" + notes.trim());
        }

        progressBar.setVisibility(View.GONE);
        progressBar.setProgress(0);
        tvProgress.setVisibility(View.GONE);
        btnAction.setText("立即下载");
        btnAction.setEnabled(true);
        btnLater.setText("稍后");
        btnLater.setEnabled(true);

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpdateDownload(manifest, dialog, progressBar, tvProgress, btnAction, btnLater);
            }
        });
        // 关窗即表示「现在不装」: 顺手停掉在飞的下载, 别让它在后台白吃车机流量
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (updateDialog == d) updateDialog = null;
                if (apkDownloader != null && apkDownloader.isRunning()) {
                    Log.i(TAG, "update dialog dismissed while downloading, cancelling");
                    apkDownloader.cancel();
                }
            }
        });

        updateDialog = dialog;
        dialog.show();
        Log.i(TAG, "update dialog shown: " + manifest.describe());
    }

    /** 开始/重试下载。进度与结果回调都由 ApkDownloader 切到主线程后才到这里 */
    private void startUpdateDownload(final UpdateManifest manifest, final Dialog dialog,
                                     final ProgressBar progressBar, final TextView tvProgress,
                                     final Button btnAction, final Button btnLater) {
        if (apkDownloader().isRunning()) {
            Toast.makeText(this, "安装包正在下载中, 请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        // 点击瞬间的可见反馈: 按钮改文案并禁用, 进度条立刻出现并停在 0%
        btnAction.setText("下载中");
        btnAction.setEnabled(false);
        btnLater.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvProgress.setVisibility(View.VISIBLE);
        tvProgress.setTextColor(Color.parseColor("#30DDC2"));
        tvProgress.setText("正在下载 0%");
        Log.i(TAG, "apk download requested url=" + manifest.getApkUrl()
                + " size=" + manifest.getSizeBytes());
        CrashMonitor.breadcrumb("update", "download begin " + manifest.getFileName());

        apkDownloader().download(manifest, new ApkDownloader.Listener() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (!isUiAlive(dialog)) return;
                int percent = totalBytes > 0
                        ? (int) Math.min(100L, downloadedBytes * 100L / totalBytes) : 0;
                progressBar.setProgress(percent);
                tvProgress.setText(totalBytes > 0
                        ? "正在下载 " + percent + "%  (" + ApkDownloader.humanSize(downloadedBytes)
                          + " / " + ApkDownloader.humanSize(totalBytes) + ")"
                        : "正在下载 " + ApkDownloader.humanSize(downloadedBytes));
            }

            @Override
            public void onSuccess(File apkFile) {
                Log.i(TAG, "apk ready, launching system installer: " + apkFile);
                CrashMonitor.breadcrumb("update", "apk verified " + apkFile.length() + "B");
                if (!isUiAlive(dialog)) {
                    // 车主已关窗: 包留在 cache 里, 下次点「立即下载」会重新校验后再拉起
                    Log.w(TAG, "apk ready but dialog gone, installer not launched");
                    return;
                }
                progressBar.setProgress(100);
                tvProgress.setText("下载完成, SHA-256 校验通过, 正在打开安装界面…");
                int result = UpdateInstaller.install(MainActivity.this, apkFile);
                Log.i(TAG, "installer result=" + result);
                CrashMonitor.breadcrumb("update", "installer result=" + result);
                // 先收掉自己的对话框再提示: 系统安装界面盖上来时不该还压着我们的弹窗
                dialog.dismiss();
                Toast.makeText(MainActivity.this, UpdateInstaller.describeResult(result),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(Exception e) {
                if (e instanceof ApkDownloader.CancelledException) {
                    Log.i(TAG, "apk download cancelled, ui left untouched");
                    return;
                }
                String msg = (e == null || e.getMessage() == null) ? "下载失败" : e.getMessage();
                Log.w(TAG, "apk download failed: " + msg);
                CrashMonitor.breadcrumb("update", "download failed: " + msg);
                if (!isUiAlive(dialog)) {
                    if (!isFinishing()) {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                // 失败留在同一个对话框里: 原因写清楚, 按钮变「重试」, 车主可以直接再来一次。
                // 校验失败的包 ApkDownloader 已经删掉了, 这里绝不会再碰安装器。
                progressBar.setVisibility(View.GONE);
                tvProgress.setTextColor(Color.parseColor("#F3B34C"));
                tvProgress.setText(msg);
                btnAction.setText("重试");
                btnAction.setEnabled(true);
                btnLater.setEnabled(true);
                btnLater.setText("取消");
            }
        });
    }

    /** 对话框还能不能安全地改: Activity 将死或窗已关时一律不动 UI */
    private boolean isUiAlive(Dialog dialog) {
        return !isFinishing() && dialog != null && dialog.isShowing();
    }

    private void dismissUpdateDialog() {
        Dialog d = updateDialog;
        updateDialog = null;
        if (d != null && d.isShowing()) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {}
        }
    }

    /** 退出收尾 (由 onDestroy 调用) */
    private void cancelUpdateWork() {
        mainHandler.removeCallbacks(updateCheckWatchdog);
        mainHandler.removeCallbacks(autoUpdateCheckRunnable);
        if (updateChecker != null) {
            updateChecker.cancel();
        }
        if (apkDownloader != null) {
            apkDownloader.cancel();
        }
        dismissUpdateDialog();
    }
}
