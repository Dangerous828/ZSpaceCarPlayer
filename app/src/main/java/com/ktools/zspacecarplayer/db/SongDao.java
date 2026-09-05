package com.ktools.zspacecarplayer.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ktools.zspacecarplayer.model.SongItem;
import com.ktools.zspacecarplayer.util.PinyinUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class SongDao {
    private static final String TAG = "SongDao";
    private static SongDao instance;
    private final DbHelper dbHelper;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger searchSeq = new AtomicInteger(0);

    public interface DbCallback<T> {
        void onResult(T result);
    }

    private SongDao(Context context) {
        dbHelper = new DbHelper(context.getApplicationContext());
    }

    public static synchronized SongDao getInstance(Context context) {
        if (instance == null) {
            instance = new SongDao(context);
        }
        return instance;
    }

    public void saveSongs(final List<SongItem> songs) {
        if (songs == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                // 一次性查出已有 play_count Map, 避免循环内执行 887 次查询导致耗时严重
                Map<String, Integer> playCountMap = new HashMap<String, Integer>();
                Cursor pc = null;
                try {
                    pc = db.query("songs", new String[]{"id", "play_count"}, null, null, null, null, null);
                    if (pc != null && pc.moveToFirst()) {
                        do {
                            String id = pc.getString(0);
                            int count = pc.getInt(1);
                            playCountMap.put(id, count);
                        } while (pc.moveToNext());
                    }
                } catch (Exception ignored) {
                } finally {
                    if (pc != null) pc.close();
                }

                db.beginTransaction();
                try {
                    for (SongItem song : songs) {
                        ContentValues cv = new ContentValues();
                        cv.put("id", song.getId());
                        cv.put("name", song.getName());
                        cv.put("artist", song.getArtist());
                        cv.put("album", song.getAlbum());
                        cv.put("genre", song.getGenre());
                        cv.put("folder_name", song.getFolderName());
                        cv.put("duration_ms", song.getDurationMs());
                        cv.put("stream_url", song.getStreamUrl());
                        cv.put("cover_url", song.getCoverUrl());
                        cv.put("is_favorite", song.isFavorite() ? 1 : 0);

                        Integer existingPlayCount = playCountMap.get(song.getId());
                        cv.put("play_count", existingPlayCount != null ? existingPlayCount : 0);

                        String pinyin = PinyinUtils.getSearchKeywords(song.getName() + " " + song.getArtist() + " " + song.getAlbum() + " " + song.getGenre());
                        cv.put("pinyin", pinyin);

                        db.insertWithOnConflict("songs", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    db.setTransactionSuccessful();
                } catch (Exception e) {
                    Log.e(TAG, "Error saving songs to SQLite", e);
                } finally {
                    db.endTransaction();
                }
            }
        }).start();
    }

    public void getAllSongsAsync(final DbCallback<List<SongItem>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SongItem> list = getAllSongs();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onResult(list);
                    }
                });
            }
        }).start();
    }

    public List<SongItem> getAllSongs() {
        List<SongItem> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query("songs", null, null, null, null, null, "name ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    SongItem song = parseCursorToSong(cursor);
                    list.add(song);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading all songs from SQLite", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public void getFavoriteSongsAsync(final DbCallback<List<SongItem>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SongItem> list = getFavoriteSongs();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onResult(list);
                    }
                });
            }
        }).start();
    }

    public List<SongItem> getFavoriteSongs() {
        List<SongItem> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query("songs", null, "is_favorite = 1", null, null, null, "name ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    SongItem song = parseCursorToSong(cursor);
                    list.add(song);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading favorite songs from SQLite", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public void getMostPlayedAsync(final DbCallback<List<SongItem>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SongItem> list = getMostPlayed();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onResult(list);
                    }
                });
            }
        }).start();
    }

    public List<SongItem> getMostPlayed() {
        List<SongItem> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query("songs", null, "play_count > 0", null, null, null,
                    "play_count DESC, name ASC", "100");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    SongItem song = parseCursorToSong(cursor);
                    list.add(song);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading most played songs from SQLite", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public void incrementPlayCountAsync(final String songId) {
        if (songId == null || songId.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                try {
                    db.execSQL("UPDATE songs SET play_count = play_count + 1 WHERE id = ?", new Object[]{songId});
                } catch (Exception e) {
                    Log.e(TAG, "Error incrementing play count", e);
                }
            }
        }).start();
    }

    public void updateFavoriteState(final String songId, final boolean isFav) {        new Thread(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                try {
                    ContentValues cv = new ContentValues();
                    cv.put("is_favorite", isFav ? 1 : 0);
                    db.update("songs", cv, "id = ?", new String[]{songId});
                } catch (Exception e) {
                    Log.e(TAG, "Error updating favorite state", e);
                }
            }
        }).start();
    }

    public void searchSongsAsync(final String keyword, final DbCallback<List<SongItem>> callback) {
        final int seq = searchSeq.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SongItem> list = searchSongs(keyword);
                if (seq != searchSeq.get()) return;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != searchSeq.get()) return;
                        if (callback != null) callback.onResult(list);
                    }
                });
            }
        }).start();
    }

    public List<SongItem> searchSongs(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getAllSongs();
        }
        List<SongItem> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        try {
            String kw = "%" + keyword.trim().toLowerCase() + "%";
            String where = "name LIKE ? OR artist LIKE ? OR album LIKE ? OR genre LIKE ? OR pinyin LIKE ?";
            String[] args = new String[]{kw, kw, kw, kw, kw};

            cursor = db.query("songs", null, where, args, null, null, "name ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    SongItem song = parseCursorToSong(cursor);
                    list.add(song);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error searching songs from SQLite", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    private SongItem parseCursorToSong(Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndexOrThrow("id"));
        String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
        String artist = cursor.getString(cursor.getColumnIndexOrThrow("artist"));
        String album = cursor.getString(cursor.getColumnIndexOrThrow("album"));
        String genre = cursor.getString(cursor.getColumnIndexOrThrow("genre"));

        String folderName = "未分类";
        int folderIdx = cursor.getColumnIndex("folder_name");
        if (folderIdx != -1 && !cursor.isNull(folderIdx)) {
            folderName = cursor.getString(folderIdx);
        }

        long durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms"));
        String streamUrl = cursor.getString(cursor.getColumnIndexOrThrow("stream_url"));
        String coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url"));
        boolean isFav = cursor.getInt(cursor.getColumnIndexOrThrow("is_favorite")) == 1;

        SongItem song = new SongItem(id, name, artist, album, genre, folderName, durationMs, streamUrl, coverUrl, isFav);
        song.setPlayCount(cursor.getInt(cursor.getColumnIndexOrThrow("play_count")));
        return song;
    }

    public void saveLyric(final String songId, final String lrcContent) {
        if (songId == null || songId.isEmpty() || lrcContent == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                try {
                    ContentValues cv = new ContentValues();
                    cv.put("song_id", songId);
                    cv.put("lrc_content", lrcContent);
                    cv.put("update_time", System.currentTimeMillis());
                    db.insertWithOnConflict("lyrics", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Exception e) {
                    Log.e(TAG, "Error saving lyric to SQLite", e);
                }
            }
        }).start();
    }

    public String getLyric(String songId) {
        if (songId == null || songId.isEmpty()) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query("lyrics", new String[]{"lrc_content"}, "song_id = ?", new String[]{songId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading lyric from SQLite", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public void saveSongProgress(final String songId, final int progressMs) {
        if (songId == null || songId.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                try {
                    ContentValues cv = new ContentValues();
                    cv.put("song_id", songId);
                    cv.put("progress_ms", progressMs);
                    cv.put("update_time", System.currentTimeMillis());
                    db.insertWithOnConflict("song_progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Exception e) {
                    Log.e(TAG, "Error saving song progress", e);
                }
            }
        }).start();
    }

    public int getSongProgress(String songId) {
        if (songId == null || songId.isEmpty()) return 0;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query("song_progress", new String[]{"progress_ms"}, "song_id = ?", new String[]{songId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting song progress", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    public void saveState(final String key, final String value) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                try {
                    ContentValues cv = new ContentValues();
                    cv.put("key_name", key);
                    cv.put("value_text", value != null ? value : "");
                    db.insertWithOnConflict("player_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Exception e) {
                    Log.e(TAG, "Error saving state to SQLite", e);
                }
            }
        }).start();
    }

    public String getState(String key, String defaultVal) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query("player_state", new String[]{"value_text"}, "key_name = ?", new String[]{key}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting state from SQLite", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return defaultVal;
    }
}
