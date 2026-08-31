package com.ktools.zspacecarplayer.ui;

import android.app.Dialog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.LyricLine;
import com.ktools.zspacecarplayer.model.SongItem;
import com.ktools.zspacecarplayer.net.JellyfinApiClient;
import com.ktools.zspacecarplayer.service.AudioPlayerService;

import java.util.List;

public class MainActivity extends AppCompatActivity implements AudioPlayerService.OnPlayerStateChangeListener {

    private static final String PREF_NAME = "zspace_car_player_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_ACCESS_TOKEN = "access_token";

    private AudioPlayerService playerService;
    private boolean isBound = false;

    private RecyclerView rvSongList;
    private RecyclerView rvLyrics;
    private SongAdapter songAdapter;
    private LyricAdapter lyricAdapter;

    private ImageView ivBigCover;
    private TextView tvCurrentTitle, tvCurrentArtist, tvCurrentTime, tvTotalTime, tvSongCount, tvServerStatus;
    private SeekBar seekBarProgress;
    private Button btnPlayPause, btnPrev, btnNext, btnPlayMode, btnNavAllSongs, btnNavImmersion, btnNavRefresh, btnNavSettings;

    private boolean isUserSeeking = false;
    private boolean isImmersive = true;
    private boolean autoReloginAttempted = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlayerService.LocalBinder binder = (AudioPlayerService.LocalBinder) service;
            playerService = binder.getService();
            playerService.setOnPlayerStateChangeListener(MainActivity.this);
            isBound = true;

            if (playerService.getCurrentSong() != null) {
                onSongChanged(playerService.getCurrentSong(), playerService.getCurrentIndex());
                onPlayStateChanged(playerService.isPlaying());
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

        setContentView(R.layout.activity_main);

        JellyfinApiClient.getInstance().init(getApplicationContext());

        initViews();
        setupImmersiveMode();
        setupAdapters();
        setupListeners();
        bindPlayerService();

        loadSavedServerConfig();
    }

    private void initViews() {
        rvSongList = findViewById(R.id.rvSongList);
        rvLyrics = findViewById(R.id.rvLyrics);
        ivBigCover = findViewById(R.id.ivBigCover);
        tvCurrentTitle = findViewById(R.id.tvCurrentTitle);
        tvCurrentArtist = findViewById(R.id.tvCurrentArtist);
        tvCurrentTime = findViewById(R.id.tvCurrentTime);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        tvSongCount = findViewById(R.id.tvSongCount);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        seekBarProgress = findViewById(R.id.seekBarProgress);

        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnPlayMode = findViewById(R.id.btnPlayMode);
        btnNavAllSongs = findViewById(R.id.btnNavAllSongs);
        btnNavImmersion = findViewById(R.id.btnNavImmersion);
        btnNavRefresh = findViewById(R.id.btnNavRefresh);
        btnNavSettings = findViewById(R.id.btnNavSettings);
    }

    private void setupImmersiveMode() {
        if (isImmersive) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private void setupAdapters() {
        songAdapter = new SongAdapter();
        rvSongList.setLayoutManager(new LinearLayoutManager(this));
        rvSongList.setAdapter(songAdapter);

        lyricAdapter = new LyricAdapter();
        rvLyrics.setHasFixedSize(true);
        rvLyrics.setLayoutManager(new LinearLayoutManager(this));
        rvLyrics.setAdapter(lyricAdapter);

        songAdapter.setOnSongClickListener(new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(SongItem song, int position) {
                if (isBound && playerService != null) {
                    playerService.setPlaylist(playerService.getPlaylist(), position);
                }
            }
        });
    }

    private void setupListeners() {
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isBound && playerService != null) {
                    playerService.playOrPause();
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
                switch (next) {
                    case AudioPlayerService.MODE_SEQUENCE:
                        btnPlayMode.setText("顺序");
                        Toast.makeText(MainActivity.this, "顺序播放", Toast.LENGTH_SHORT).show();
                        break;
                    case AudioPlayerService.MODE_SINGLE_REPEAT:
                        btnPlayMode.setText("单曲");
                        Toast.makeText(MainActivity.this, "单曲循环", Toast.LENGTH_SHORT).show();
                        break;
                    case AudioPlayerService.MODE_RANDOM:
                        btnPlayMode.setText("随机");
                        Toast.makeText(MainActivity.this, "随机播放", Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        });

        btnNavImmersion.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isImmersive = !isImmersive;
                setupImmersiveMode();
                Toast.makeText(MainActivity.this, isImmersive ? "已开启沉浸模式" : "退出沉浸模式", Toast.LENGTH_SHORT).show();
            }
        });

        btnNavAllSongs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rvSongList.smoothScrollToPosition(0);
            }
        });

        btnNavRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoReloginAttempted = false;
                refreshMediaLibrary();
            }
        });

        btnNavSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        seekBarProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long sec = (progress / 1000) % 60;
                    long min = (progress / 1000) / 60;
                    tvCurrentTime.setText(String.format("%02d:%02d", min, sec));
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

    private void bindPlayerService() {
        Intent intent = new Intent(this, AudioPlayerService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private static final String DEFAULT_SERVER_URL = "http://your-jellyfin.example.com/music";
    private static final String DEFAULT_USERNAME = "car";
    private static final String DEFAULT_PASSWORD = "your_password";

    private void loadSavedServerConfig() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String serverUrl = sp.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
        String username = sp.getString(KEY_USERNAME, DEFAULT_USERNAME);
        String password = sp.getString(KEY_PASSWORD, DEFAULT_PASSWORD);
        String userId = sp.getString(KEY_USER_ID, "");
        String token = sp.getString(KEY_ACCESS_TOKEN, "");

        JellyfinApiClient.getInstance().setServerUrl(serverUrl);
        JellyfinApiClient.getInstance().setAuthInfo(userId, token);

        tvServerStatus.setText("Server: " + serverUrl);

        if (token.isEmpty()) {
            autoSilentLogin(serverUrl, username, password);
        } else {
            refreshMediaLibrary();
        }
    }

    private void autoSilentLogin(final String url, final String username, final String password) {
        tvServerStatus.setText("正在连接服务器...");
        JellyfinApiClient.getInstance().authenticate(url, username, password, new JellyfinApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                sp.edit()
                        .putString(KEY_SERVER_URL, url)
                        .putString(KEY_USERNAME, username)
                        .putString(KEY_PASSWORD, password)
                        .putString(KEY_USER_ID, JellyfinApiClient.getInstance().getUserId())
                        .putString(KEY_ACCESS_TOKEN, JellyfinApiClient.getInstance().getAccessToken())
                        .apply();

                tvServerStatus.setText("Server: " + url + " (已连接)");
                Toast.makeText(MainActivity.this, "车机自动连接成功！", Toast.LENGTH_SHORT).show();
                refreshMediaLibrary();
            }

            @Override
            public void onError(Exception e) {
                tvServerStatus.setText("连接失败: " + e.getMessage());
                Toast.makeText(MainActivity.this, "自动连接失败: " + e.getMessage() + "，点击设置可修改配置", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showSettingsDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_settings);

        final SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        final EditText etUrl = dialog.findViewById(R.id.etServerUrl);
        final EditText etUser = dialog.findViewById(R.id.etUsername);
        final EditText etPass = dialog.findViewById(R.id.etPassword);

        etUrl.setText(sp.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        etUser.setText(sp.getString(KEY_USERNAME, DEFAULT_USERNAME));
        etPass.setText(sp.getString(KEY_PASSWORD, DEFAULT_PASSWORD));

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
                        editor.putString(KEY_SERVER_URL, url);
                        editor.putString(KEY_USERNAME, username);
                        editor.putString(KEY_PASSWORD, password);
                        editor.putString(KEY_USER_ID, JellyfinApiClient.getInstance().getUserId());
                        editor.putString(KEY_ACCESS_TOKEN, JellyfinApiClient.getInstance().getAccessToken());
                        editor.apply();

                        tvServerStatus.setText("Server: " + url);
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
        Toast.makeText(this, "正在从极空间 / Jellyfin 加载媒体库...", Toast.LENGTH_SHORT).show();
        JellyfinApiClient.getInstance().fetchMusicItems(new JellyfinApiClient.ApiCallback<List<SongItem>>() {
            @Override
            public void onSuccess(List<SongItem> songs) {
                autoReloginAttempted = false;
                tvSongCount.setText(songs.size() + " 首");
                songAdapter.setSongs(songs);
                if (isBound && playerService != null) {
                    playerService.setPlaylist(songs, -1);
                }
                Toast.makeText(MainActivity.this, "媒体库加载完成: " + songs.size() + " 首曲目", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Exception e) {
                if (!autoReloginAttempted) {
                    // 缓存 token 可能已失效, 自动静默重登一次再刷新
                    autoReloginAttempted = true;
                    SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                    tvServerStatus.setText("Token 失效, 重新登录中...");
                    autoSilentLogin(
                            sp.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL),
                            sp.getString(KEY_USERNAME, DEFAULT_USERNAME),
                            sp.getString(KEY_PASSWORD, DEFAULT_PASSWORD));
                    return;
                }
                tvServerStatus.setText("加载失败: " + e.getMessage());
                Toast.makeText(MainActivity.this, "加载曲目失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onSongChanged(SongItem song, int index) {
        if (song == null) return;
        tvCurrentTitle.setText(song.getName());
        tvCurrentArtist.setText(song.getArtist() + " · " + song.getAlbum());

        long sec = (song.getDurationMs() / 1000) % 60;
        long min = (song.getDurationMs() / 1000) / 60;
        tvTotalTime.setText(String.format("%02d:%02d", min, sec));
        seekBarProgress.setMax((int) song.getDurationMs());

        songAdapter.setSelectedIndex(index);
        rvSongList.smoothScrollToPosition(index);

        Glide.with(this)
                .load(song.getCoverUrl())
                .placeholder(R.drawable.bg_cover_placeholder)
                .error(R.drawable.bg_cover_placeholder)
                .into(ivBigCover);

        // 加载歌词
        JellyfinApiClient.getInstance().fetchLyrics(song.getId(), new JellyfinApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String lrcContent) {
                List<LyricLine> lyricLines = LyricLine.parseLrc(lrcContent);
                lyricAdapter.setLyrics(lyricLines);
            }

            @Override
            public void onError(Exception e) {
                lyricAdapter.setLyrics(null);
            }
        });
    }

    @Override
    public void onPlayStateChanged(boolean isPlaying) {
        btnPlayPause.setText(isPlaying ? "||" : "▶");
    }

    @Override
    public void onProgressUpdate(int currentMs, int totalMs) {
        if (!isUserSeeking) {
            seekBarProgress.setProgress(currentMs);
            long sec = (currentMs / 1000) % 60;
            long min = (currentMs / 1000) / 60;
            tvCurrentTime.setText(String.format("%02d:%02d", min, sec));
        }

        int highlightIdx = lyricAdapter.updateHighlight(currentMs);
        if (highlightIdx >= 0) {
            rvLyrics.smoothScrollToPosition(highlightIdx);
        }
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isImmersive) {
            setupImmersiveMode();
        }
    }

    @Override
    protected void onDestroy() {
        if (isBound && playerService != null) {
            playerService.setOnPlayerStateChangeListener(null);
        }
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        super.onDestroy();
    }
}
