package com.ktools.zspacecarplayer.crash;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 落盘滚动诊断日志 (2026-09-15 实车: "昨晚一直缓冲失败"却抓不到 log)。
 *
 * <p>为什么需要它: 车机整机会断电/重启 (实车 dropbox 显示某晚 18:47/20:37/21:00 三次
 * SYSTEM_BOOT), logcat 环形缓冲随之清空; {@link CrashBreadcrumbs} 又是纯内存, 重启即丢。
 * 于是"缓冲卡死"这类现场三重失证。本类把关键事件 (面包屑) 追加写进 filesDir 下的滚动文件,
 * 重启后仍在, 下次复现直接 {@code adb pull} 即可还原时间线。
 *
 * <p>设计要点:
 * <ul>
 *   <li>单线程 daemon executor 异步追加, 绝不阻塞调用线程 (可能在音频/下载热路径);</li>
 *   <li>超过 {@link #MAX_BYTES} 自动滚动 (旧内容移到 .1), 上限约 1MB, 不撑爆车机存储;</li>
 *   <li>任何 IO 异常都吞掉并只记一条 android Log, 诊断日志本身绝不能成为新的故障源;</li>
 *   <li>未 {@link #init(File)} 时 {@link #log} 是安全 no-op (便于 JVM 单测与早期调用)。</li>
 * </ul>
 */
public final class DiagLog {

    private static final String TAG = "DiagLog";
    private static final String FILE_NAME = "diag.log";
    private static final String ROTATED_NAME = "diag.log.1";
    /** 滚动阈值: 单文件上限, 连同 .1 备份总占用 ≤ ~1MB。 */
    private static final long MAX_BYTES = 512 * 1024L;
    private static final int MAX_MSG_LEN = 300;

    private static volatile File logFile;
    private static volatile ExecutorService writer;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private DiagLog() {}

    /** 初始化落盘目录 (通常传 {@code context.getFilesDir()})。重复调用幂等。 */
    public static synchronized void init(File dir) {
        if (logFile != null || dir == null) {
            return;
        }
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            logFile = new File(dir, FILE_NAME);
            writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "DiagLog-writer");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });
            final String startup = "---- session start pid=" + android.os.Process.myPid() + " ----";
            appendAsync(startup);
            Log.i(TAG, "diag log ready at " + logFile.getAbsolutePath());
        } catch (Throwable t) {
            logFile = null;
            Log.w(TAG, "init failed, disk diag disabled: " + t);
        }
    }

    /** 记一行诊断日志。tag 标出子系统 (play/stream/net/engine/mute...)。未初始化则 no-op。 */
    public static void log(String tag, String message) {
        if (logFile == null || writer == null || message == null) {
            return;
        }
        String msg = message.length() > MAX_MSG_LEN
                ? message.substring(0, MAX_MSG_LEN) + "…" : message;
        String line;
        synchronized (TS) {
            line = TS.format(new Date()) + " [" + (tag == null ? "-" : tag) + "] " + msg;
        }
        appendAsync(line);
    }

    private static void appendAsync(final String line) {
        ExecutorService w = writer;
        if (w == null) {
            return;
        }
        try {
            w.execute(new Runnable() {
                @Override
                public void run() {
                    writeNow(line);
                }
            });
        } catch (Throwable ignored) {
            // executor 已关闭等极端情况: 丢弃这一行, 不影响主流程
        }
    }

    private static void writeNow(String line) {
        File f = logFile;
        if (f == null) {
            return;
        }
        RandomAccessFile raf = null;
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                rotate(f);
            }
            raf = new RandomAccessFile(f, "rw");
            raf.seek(raf.length());
            raf.write((line + "\n").getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "append failed: " + t);
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void rotate(File f) {
        try {
            File rotated = new File(f.getParentFile(), ROTATED_NAME);
            if (rotated.exists()) {
                rotated.delete();
            }
            f.renameTo(rotated);
        } catch (Throwable t) {
            Log.w(TAG, "rotate failed: " + t);
        }
    }

    /** 诊断日志文件的绝对路径 (供实车 {@code adb pull} 取证据); 未初始化返回 null。 */
    public static String path() {
        File f = logFile;
        return f != null ? f.getAbsolutePath() : null;
    }

    /**
     * 读取滚动日志的最近内容, 供「立即上报」把现场一并上传。
     *
     * 按时间序拼接: 先旧文件 (diag.log.1) 尾部, 再当前文件 (diag.log) 尾部。
     * 单文件若超过 maxBytes 只取末尾一段。返回 null 表示尚未初始化或有 IO 问题;
     * 返回空串表示已初始化但两个文件都还没有内容。
     */
    public static String content() {
        File cur = logFile;
        if (cur == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(256 * 1024);
        String oldTail = tail(new File(cur.getParentFile(), ROTATED_NAME), 96 * 1024);
        if (oldTail != null) {
            sb.append(oldTail);
            if (sb.length() > 0 && oldTail.length() > 0) {
                sb.append("\n");
            }
        }
        String curTail = tail(cur, 96 * 1024);
        if (curTail != null) {
            sb.append(curTail);
        }
        return sb.toString();
    }

    /** 读文件末尾最多 maxBytes 字节 (不跨 UTF-8 边界切割, 截断处退回前一字符边界)。 */
    private static String tail(File f, int maxBytes) {
        if (f == null || !f.isFile() || maxBytes <= 0) {
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            if (len <= 0) {
                return "";
            }
            long start = Math.max(0, len - maxBytes);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            // 若从字节中间截断, 回退到最近的字符边界, 避免上报体里出现半个 UTF-8 字符
            String s = new String(buf, "UTF-8");
            if (start > 0 && s.length() > 0 && s.charAt(0) == '\uFFFD') {
                s = s.substring(1);
            }
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "tail read failed: " + t);
            return null;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
