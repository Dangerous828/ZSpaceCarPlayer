package com.ktools.zspacecarplayer.ui;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import com.ktools.zspacecarplayer.service.PlaybackStateMachine;
import com.ktools.zspacecarplayer.util.CacheSizeManager;

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
    private View seekFill;
    private EditText etSearch;
    private SeekBar seekBarProgress;
    private Button btnPlayPause, btnPrev, btnNext, btnPlayMode, btnCurrentFav;
    private Button btnNavAllSongs, btnNavPlaylist, btnNavRefresh, btnNavSettings, btnBackToPlaylist;
    private Button btnSearchToggle, btnCloseSearch;

    private boolean isUserSeeking = false;
    private boolean autoReloginAttempted = false;
    private boolean isAutoPlayInitialized = false;

    private List<SongItem> allSongsList = new ArrayList<>();
    private List<SongItem> currentDisplayedSongs = new ArrayList<>();
    private long lastBackPressTime = 0;
    private long lastProgressSaveTime = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

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
                    int exactMs = SongDao.getInstance(MainActivity.this).getSongProgress(song.getId());
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
                autoReloginAttempted = false;
                refreshMediaLibrary();
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
        syncPlayingHighlight(false);
        rvSongList.scrollToPosition(0);
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
            presets = Arrays.asList("普通 (Normal)", "古典 (Classical)", "流行 (Pop)", "摇滚 (Rock)", "人声 (Vocal)", "爵士 (Jazz)", "舞曲 (Dance)");
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
            int exactMs = SongDao.getInstance(this).getSongProgress(lastSongId);
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
                        int exactMs = SongDao.getInstance(this).getSongProgress(lastSongId);
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
        String username = sp.getString(JellyfinApiClient.KEY_USERNAME, JellyfinApiClient.DEFAULT_USERNAME);
        String password = sp.getString(JellyfinApiClient.KEY_PASSWORD, JellyfinApiClient.DEFAULT_PASSWORD);
        String userId = sp.getString(JellyfinApiClient.KEY_USER_ID, "");
        String token = sp.getString(JellyfinApiClient.KEY_ACCESS_TOKEN, "");

        JellyfinApiClient.getInstance().setServerUrl(serverUrl);
        JellyfinApiClient.getInstance().setAuthInfo(userId, token);

        tvServerStatus.setText("连接中...");

        if (token.isEmpty()) {
            autoSilentLogin(serverUrl, username, password);
        } else {
            refreshMediaLibrary();
        }
    }

    private void autoSilentLogin(final String url, final String username, final String password) {
        tvServerStatus.setText("连接中...");
        JellyfinApiClient.getInstance().authenticate(url, username, password, new JellyfinApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                SharedPreferences sp = getSharedPreferences(JellyfinApiClient.PREF_NAME, MODE_PRIVATE);
                sp.edit()
                        .putString(JellyfinApiClient.KEY_SERVER_URL, url)
                        .putString(JellyfinApiClient.KEY_USERNAME, username)
                        .putString(JellyfinApiClient.KEY_PASSWORD, password)
                        .putString(JellyfinApiClient.KEY_USER_ID, JellyfinApiClient.getInstance().getUserId())
                        .putString(JellyfinApiClient.KEY_ACCESS_TOKEN, JellyfinApiClient.getInstance().getAccessToken())
                        .apply();

                tvServerStatus.setText("已连接");
                refreshMediaLibrary();
            }

            @Override
            public void onError(Exception e) {
                tvServerStatus.setText("连接失败");
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

                JellyfinApiClient.getInstance().authenticate(url, username, password, new JellyfinApiClient.ApiCallback<Boolean>() {
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

                        tvServerStatus.setText("已连接");
                        dialog.dismiss();
                        refreshMediaLibrary();
                    }

                    @Override
                    public void onError(Exception e) {
                        Toast.makeText(MainActivity.this, "登录失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        dialog.show();
    }

    private void refreshMediaLibrary() {
        JellyfinApiClient.getInstance().fetchMusicItems(new JellyfinApiClient.ApiCallback<List<SongItem>>() {
            @Override
            public void onSuccess(List<SongItem> songs) {
                autoReloginAttempted = false;
                tvServerStatus.setText("已连接");
                if (songs == null || songs.isEmpty()) return;

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
            }

            @Override
            public void onError(Exception e) {
                if (!autoReloginAttempted) {
                    autoReloginAttempted = true;
                    SharedPreferences sp = getSharedPreferences(JellyfinApiClient.PREF_NAME, MODE_PRIVATE);
                    tvServerStatus.setText("重登中...");
                    autoSilentLogin(
                            sp.getString(JellyfinApiClient.KEY_SERVER_URL, JellyfinApiClient.DEFAULT_SERVER_URL),
                            sp.getString(JellyfinApiClient.KEY_USERNAME, JellyfinApiClient.DEFAULT_USERNAME),
                            sp.getString(JellyfinApiClient.KEY_PASSWORD, JellyfinApiClient.DEFAULT_PASSWORD));
                } else {
                    tvServerStatus.setText("同步失败");
                }
            }
        });
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
        if (now - lastProgressSaveTime > 5000) {
            lastProgressSaveTime = now;
            if (isBound && playerService != null && playerService.isPlaying()) {
                SongItem current = playerService.getCurrentSong();
                if (current != null) {
                    SongDao.getInstance(this).saveSongProgress(current.getId(),
                            sanitizeProgressForSave(current, currentMs));
                }
            }
        }
    }

    /**
     * 进度落库前治理 (2026-09-09 实车定位): seek 越界/异常回调可能把 position 推过
     * 歌曲实际时长, 脏断点入库后点歌即「进度条瞬跳末尾 + 假 COMPLETED 乱切歌」。
     * 贴近末尾 (>= 时长-2s) 视为播完存 0, 其余夹到 [0, 时长) 内。
     */
    private static int sanitizeProgressForSave(SongItem song, int progressMs) {
        if (progressMs <= 0) return 0;
        long durMs = song != null ? song.getDurationMs() : 0L;
        if (durMs <= 0) return progressMs;
        if (progressMs >= durMs - 2000L) return 0;
        return progressMs;
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
                SongDao.getInstance(this).saveSongProgress(current.getId(), playerService.getCurrentPositionMs());
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
}
