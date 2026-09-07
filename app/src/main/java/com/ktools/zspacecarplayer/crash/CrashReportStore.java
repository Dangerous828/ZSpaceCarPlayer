package com.ktools.zspacecarplayer.crash;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 崩溃报告的本地落盘与取用。
 *
 * 崩溃发生时进程正在死，任何异步/排队都来不及，所以写入是同步的并且 flush +
 * fsync；上传统一放到下次启动。目录里最多留 {@link #MAX_FILES} 份，超出按时间
 * 删最旧的，避免车机 /data 分区被塞满。
 *
 * 只用 java.io.File，不依赖 Android 类，便于 JVM 单测（传临时目录即可）。
 */
public final class CrashReportStore {

    private static final String TAG = "CrashReportStore";
    private static final String SUFFIX = ".json";
    /** 落盘上限。每份含 logcat 与线程快照，最坏约 60KB，20 份 ≈ 1.2MB。 */
    static final int MAX_FILES = 20;

    private final File dir;

    public CrashReportStore(File dir) {
        this.dir = dir;
    }

    public File getDir() {
        return dir;
    }

    /**
     * 同步写入一份报告并修剪超额文件。
     *
     * @return 落盘成功的文件；失败返回 null（写不进去也不能再把崩溃处理链带崩）
     */
    public File write(CrashReport report) {
        if (report == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.e(TAG, "cannot create crash dir: " + dir);
            return null;
        }
        File target = new File(dir, report.getKind() + "-" + report.getReportId() + SUFFIX);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(target);
            Writer w = new OutputStreamWriter(fos, "UTF-8");
            w.write(report.toJson());
            w.flush();
            // 进程马上就要没了，不落盘就等于没写
            fos.getFD().sync();
            trim();
            return target;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "write crash report failed", t);
            return null;
        } finally {
            closeQuietly(fos);
        }
    }

    /** 待上传报告，按写入时间从旧到新 */
    public List<File> pending() {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return new ArrayList<File>();
        }
        List<File> out = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(SUFFIX)) {
                out.add(f);
            }
        }
        Collections.sort(out, MTIME_ORDER);
        return out;
    }

    public String read(File file) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            Reader r = new InputStreamReader(fis, "UTF-8");
            StringBuilder sb = new StringBuilder((int) Math.min(file.length() + 16, 1 << 20));
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(fis);
        }
    }

    public void delete(File file) {
        if (file != null && file.exists() && !file.delete()) {
            android.util.Log.w(TAG, "delete failed: " + file);
        }
    }

    /** 超过上限就删最旧的 */
    private void trim() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_FILES) {
            return;
        }
        Arrays.sort(files, MTIME_ORDER);
        for (int i = 0; i < files.length - MAX_FILES; i++) {
            if (!files[i].delete()) {
                android.util.Log.w(TAG, "trim delete failed: " + files[i]);
            }
        }
    }

    /** 按最后修改时间升序。手写比较：Long.compare 是 API 19+，车机是 API 18。 */
    private static final Comparator<File> MTIME_ORDER = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            long x = a.lastModified();
            long y = b.lastModified();
            return x < y ? -1 : (x == y ? 0 : 1);
        }
    };

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {}
        }
    }
}
