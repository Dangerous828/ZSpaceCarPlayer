package com.ktools.zspacecarplayer.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CacheSizeManager {
    private static final String TAG = "CacheSizeManager";

    // 最大缓存容量限制 10GB (单位: Bytes)
    private static final long MAX_CACHE_SIZE_BYTES = 10L * 1024L * 1024L * 1024L; // 10GB
    // 清理目标控制在 8GB
    private static final long TARGET_CACHE_SIZE_BYTES = 8L * 1024L * 1024L * 1024L; // 8GB

    public static void checkAndTrimCacheAsync(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File cacheDir = context.getCacheDir();
                    File filesDir = context.getFilesDir();

                    long currentSize = getFolderSize(cacheDir) + getFolderSize(filesDir);
                    Log.d(TAG, "Current total cache size: " + (currentSize / (1024 * 1024)) + " MB");

                    if (currentSize > MAX_CACHE_SIZE_BYTES) {
                        Log.w(TAG, "Cache size exceeded 10GB, starting LRU cleanup...");
                        List<File> allFiles = new ArrayList<File>();
                        collectAllFiles(cacheDir, allFiles);
                        collectAllFiles(filesDir, allFiles);

                        // 按最后修改时间升序排列 (最旧的文件排在最前面)
                        Collections.sort(allFiles, new Comparator<File>() {
                            @Override
                            public int compare(File f1, File f2) {
                                if (f1.lastModified() < f2.lastModified()) return -1;
                                if (f1.lastModified() > f2.lastModified()) return 1;
                                return 0;
                            }
                        });

                        for (File file : allFiles) {
                            if (currentSize <= TARGET_CACHE_SIZE_BYTES) break;
                            // 跳过 sqlite 数据库文件本身
                            if (file.getName().endsWith(".db") || file.getName().endsWith(".db-journal")) {
                                continue;
                            }
                            long fileSize = file.length();
                            if (file.delete()) {
                                currentSize -= fileSize;
                            }
                        }
                        Log.i(TAG, "LRU cleanup finished. New total cache size: " + (currentSize / (1024 * 1024)) + " MB");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error checking or trimming cache", e);
                }
            }
        }).start();
    }

    private static long getFolderSize(File file) {
        long size = 0;
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();

        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                size += getFolderSize(child);
            }
        }
        return size;
    }

    private static void collectAllFiles(File dir, List<File> result) {
        if (dir == null || !dir.exists()) return;
        if (dir.isFile()) {
            result.add(dir);
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File child : files) {
                collectAllFiles(child, result);
            }
        }
    }
}
