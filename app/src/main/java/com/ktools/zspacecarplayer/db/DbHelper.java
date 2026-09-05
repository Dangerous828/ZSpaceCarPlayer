package com.ktools.zspacecarplayer.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "zspace_car_player.db";
    private static final int DB_VERSION = 4;

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 歌曲列表表
        db.execSQL("CREATE TABLE IF NOT EXISTS songs ("
                + "id TEXT PRIMARY KEY, "
                + "name TEXT, "
                + "artist TEXT, "
                + "album TEXT, "
                + "genre TEXT, "
                + "folder_name TEXT, "
                + "duration_ms INTEGER, "
                + "stream_url TEXT, "
                + "cover_url TEXT, "
                + "is_favorite INTEGER DEFAULT 0, "
                + "play_count INTEGER DEFAULT 0, "
                + "pinyin TEXT)");

        // 歌词表
        db.execSQL("CREATE TABLE IF NOT EXISTS lyrics ("
                + "song_id TEXT PRIMARY KEY, "
                + "lrc_content TEXT, "
                + "update_time INTEGER)");

        // 播放状态键值表
        db.execSQL("CREATE TABLE IF NOT EXISTS player_state ("
                + "key_name TEXT PRIMARY KEY, "
                + "value_text TEXT)");

        // 每首歌曲播放进度表 (支持精确断点续播)
        db.execSQL("CREATE TABLE IF NOT EXISTS song_progress ("
                + "song_id TEXT PRIMARY KEY, "
                + "progress_ms INTEGER, "
                + "update_time INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE songs ADD COLUMN is_favorite INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
            db.execSQL("CREATE TABLE IF NOT EXISTS song_progress ("
                    + "song_id TEXT PRIMARY KEY, "
                    + "progress_ms INTEGER, "
                    + "update_time INTEGER)");
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE songs ADD COLUMN play_count INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE songs ADD COLUMN folder_name TEXT");
            } catch (Exception ignored) {}
            // v4 同期引入的拼音搜索列, 旧库升级必须补齐, 否则 saveSongs 整批回滚
            try {
                db.execSQL("ALTER TABLE songs ADD COLUMN pinyin TEXT");
            } catch (Exception ignored) {}
        }
    }
}
