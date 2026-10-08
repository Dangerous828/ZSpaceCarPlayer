package com.ktools.zspacecarplayer.player.stream;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 流式磁盘缓存——<b>只作为"命中就不重连"的加速层，不是下载管理器</b>（2026-10-08 T1 重审 A2）。
 *
 * <p>为什么必须有：这条链路上<b>每一次上游请求都要付 0.8~1.4 秒首字节</b>（实测：同一文件一条
 * 长连接 797KB/s，拆成 20 条请求只剩 133KB/s，拆开每次的首字节开销才是主角）。而现在的架构是
 * "过路字节不留盘"——回拖、重播同一首、断流重来，全都得重新走网络、重新付那一秒。
 * 市面 T1 与 Media3 都是同一件事：{@code CacheDataSource} + LRU，弱网先吃本地已缓存字节。
 *
 * <p>三条硬约束（都是这里独有的坑）：
 * <ul>
 *   <li><b>键不许含 {@code api_key}</b>：token 每次登录都换，用整条 URL 当键的话缓存永远命不中。
 *       解析出 {@code /Audio/{itemId}/} 就用 {@code itemId + 总长}，解不出就退化成
 *       "去掉 query 的 URL 摘要 + 总长"。总长进键是廉价的内容指纹——服务端换文件（比如我们
 *       把 wav 转成 flac 原地替换）时字节数会变，旧条目自然作废，宁可多下。</li>
 *   <li><b>只读已知连续段</b>：环形缓冲被重定位过，落盘的位置序列是<b>一段一段</b>的，中间有洞。
 *       所以用区间表记账，读取时先确认 {@code [position, position+n)} 真的连续，绝不跨过洞
 *       （跨过去就是把没下过的字节当数据交给解码器，表现为"歌里冒杂音"，比卡顿难查十倍）。</li>
 *   <li><b>转码流不缓存</b>：chunked 流没有总长，长度进不了键、洞也判不出来，直接跳过。</li>
 * </ul>
 *
 * <p>容量：{@link #evictForSpace} 按最后修改时间从旧开始删，删到放得下为止；目录仍在
 * {@code getCacheDir()} 下，沿用 {@code CacheSizeManager} 的全局裁剪边界，不另立第二套上限口径。
 */
public final class StreamDiskCache {

    private static final String TAG = "StreamDiskCache";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 缓存文件前缀，`evictForSpace` 与清理都认它。 */
    static final String FILE_PREFIX = "stream_";
    static final String FILE_SUFFIX = ".dat";

    private final Object ioLock = new Object();
    private final TreeMap<Long, Long> runs = new TreeMap<Long, Long>();
    private final File file;
    private final long contentLength;
    private RandomAccessFile raf;
    private long servedBytes;
    private boolean broken;

    private StreamDiskCache(File file, long contentLength) {
        this.file = file;
        this.contentLength = contentLength;
    }

    /**
     * 该不该为这条流开磁盘缓存。
     *
     * <p>总长未知（chunked 转码流）不开：长度进不了键，洞也判不出来。超过上限也不开：
     * 车机存储有限，一首超大文件把 LRU 挤干净是净损失。
     */
    static boolean shouldCache(long contentLength, long maxBytesPerFile) {
        return contentLength > 0L && contentLength <= maxBytesPerFile;
    }

    /** 缓存键：{@code itemId + 总长}，解不出 itemId 时用"去掉 query 的 URL 摘要 + 总长"。 */
    static String cacheKeyFor(String url, long contentLength) {
        if (url == null || url.length() == 0 || contentLength <= 0L) {
            return null;
        }
        int audioAt = url.indexOf("/Audio/");
        if (audioAt >= 0) {
            int from = audioAt + "/Audio/".length();
            int to = from;
            while (to < url.length() && isIdChar(url.charAt(to))) {
                to++;
            }
            // Jellyfin 的 Id 是 32 位十六进制；短于 8 位说明没解析到真 Id，别拿它当键
            if (to - from >= 8) {
                return url.substring(from, to) + "_" + contentLength;
            }
        }
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        return sha1Hex(base) + "_" + contentLength;
    }

    private static boolean isIdChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 区间表合并写入 (纯函数，单测覆盖)。
     *
     * <p>{@code [start, end)} 落进去，并与相邻/包含的旧区间合并。区间表是"哪些字节真的在盘上"
     * 的唯一依据，所以宁可少说（洞保持洞）也不能多说。
     */
    static void addRun(TreeMap<Long, Long> runs, long start, long end) {
        if (end <= start) {
            return;
        }
        long mergedStart = start;
        long mergedEnd = end;
        List<Long> drop = new ArrayList<Long>();
        Map.Entry<Long, Long> floor = runs.floorEntry(start);
        if (floor != null && floor.getValue() >= start) {
            mergedStart = floor.getKey();
            mergedEnd = Math.max(mergedEnd, floor.getValue());
            drop.add(floor.getKey());
        }
        Map.Entry<Long, Long> cell = runs.ceilingEntry(start);
        while (cell != null && cell.getKey() <= mergedEnd) {
            mergedEnd = Math.max(mergedEnd, cell.getValue());
            drop.add(cell.getKey());
            cell = runs.higherEntry(cell.getKey());
        }
        for (int i = 0; i < drop.size(); i++) {
            runs.remove(drop.get(i));
        }
        runs.put(mergedStart, mergedEnd);
    }

    /** 从 {@code position} 起盘上连续可用到哪儿（不含）；不可用返回 {@code position} 本身。 */
    static long contiguousEnd(TreeMap<Long, Long> runs, long position) {
        Map.Entry<Long, Long> floor = runs.floorEntry(position);
        if (floor == null || floor.getValue() <= position) {
            return position;
        }
        return floor.getValue();
    }

    /**
     * 给目录腾出 {@code needBytes}：按最后修改时间从旧开始删，删到装得下为止。
     *
     * <p>只删自己前缀的文件，绝不碰目录里别的东西（同一 cacheDir 下还有 EQ 预设下载等）。
     * 返回删掉的字节数。最旧的一定先死，因为重播的是"最近听过的那几首"。
     */
    static long evictForSpace(File dir, long needBytes, long capBytes) {
        if (dir == null || !dir.isDirectory()) {
            return 0L;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0L;
        }
        List<File> caches = new ArrayList<File>();
        long total = 0L;
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            if (f.isFile() && f.getName().startsWith(FILE_PREFIX)
                    && f.getName().endsWith(FILE_SUFFIX)) {
                caches.add(f);
                total += f.length();
            }
        }
        if (total + needBytes <= capBytes) {
            // 现有总量<b>加上要新放的那个文件</b>仍在上限内，才什么都不动。
            // 只看 total<=cap 是错的：200/250 看着有余量，再来 100 就装不下了（2026-10-08 单测抓到）
            return 0L;
        }
        Collections.sort(caches, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.valueOf(a.lastModified()).compareTo(Long.valueOf(b.lastModified()));
            }
        });
        long freed = 0L;
        long want = total - capBytes + needBytes;
        for (int i = 0; i < caches.size() && freed < want; i++) {
            File victim = caches.get(i);
            long size = victim.length();
            if (victim.delete()) {
                freed += size;
            }
        }
        return freed;
    }

    /**
     * 打开（或接着用）一条流的缓存文件。任何失败都只意味着"这次没缓存"，不影响播放。
     *
     * @param dir 缓存目录（{@code getCacheDir()/stream-cache}）
     */
    static StreamDiskCache open(File dir, String url, long contentLength, long capBytes) {
        if (dir == null || !shouldCache(contentLength, capBytes)) {
            return null;
        }
        String key = cacheKeyFor(url, contentLength);
        if (key == null) {
            return null;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        File f = new File(dir, FILE_PREFIX + key + FILE_SUFFIX);
        if (!f.exists()) {
            evictForSpace(dir, contentLength, capBytes);
        }
        StreamDiskCache cache = new StreamDiskCache(f, contentLength);
        try {
            cache.raf = new RandomAccessFile(f, "rw");
            long existing = cache.raf.length();
            if (existing > contentLength) {
                // 同名但比资源长：键撞了或者服务端内容变过，作废重建，绝不拿旧字节当数
                cache.raf.setLength(0L);
            } else {
                // 已有部分是可信的：整段都当作连续区间（写入本来就是顺序的），
                // 重播同一首时这段直接免网络
                if (existing > 0L) {
                    addRun(cache.runs, 0L, existing);
                }
            }
        } catch (Exception e) {
            cache.closeQuietly();
            Log.w(TAG, "open failed, continuing without disk cache: " + e);
            return null;
        }
        return cache;
    }

    /** 盘上有 {@code [position, position+want)} 这段连续数据吗。 */
    public boolean has(long position, long want) {
        if (broken || want <= 0L) {
            return false;
        }
        synchronized (ioLock) {
            return position >= 0L && contiguousEnd(runs, position) >= position + want;
        }
    }

    /**
     * 从盘读一段。返回实际读到的字节数（可能是区间边界截短的），不可用返回 -1。
     */
    public int readInto(long position, byte[] dest, int offset, int length) {
        if (broken || position < 0L || dest == null || length <= 0) {
            return -1;
        }
        synchronized (ioLock) {
            if (raf == null) {
                return -1;
            }
            long end = contiguousEnd(runs, position);
            int n = (int) Math.min((long) length, Math.max(0L, end - position));
            if (n <= 0) {
                return -1;
            }
            try {
                raf.seek(position);
                int got = 0;
                while (got < n) {
                    int r = raf.read(dest, offset + got, n - got);
                    if (r < 0) {
                        break;
                    }
                    got += r;
                }
                if (got > 0) {
                    servedBytes += got;
                }
                return got > 0 ? got : -1;
            } catch (Exception e) {
                broken = true;
                Log.w(TAG, "disk read failed, cache disabled for this stream: " + e);
                return -1;
            }
        }
    }

    /**
     * 落一段。调用方必须给"确实已下载"的字节位置；顺序写之外（重定位后的新段）也支持，
     * 因为记账按区间而不是按文件大小。
     */
    public void record(long position, byte[] src, int offset, int length) {
        if (broken || position < 0L || src == null || length <= 0) {
            return;
        }
        if (position + length > contentLength) {
            return; // 越出资源总长的数据不可信，宁可不写
        }
        synchronized (ioLock) {
            if (raf == null) {
                return;
            }
            try {
                raf.seek(position);
                raf.write(src, offset, length);
                addRun(runs, position, position + length);
            } catch (Exception e) {
                broken = true;
                Log.w(TAG, "disk write failed, cache disabled for this stream: " + e);
            }
        }
    }

    /** 已从盘上喂出去的字节数（上报里 {@code disk=} 用它判命中率）。 */
    public long getServedBytes() {
        synchronized (ioLock) {
            return servedBytes;
        }
    }

    /** 已落到盘上的字节总数（按区间表算，不重复计）。 */
    public long getStoredBytes() {
        synchronized (ioLock) {
            return sumOf(runs);
        }
    }

    private static long sumOf(TreeMap<Long, Long> runs) {
        long total = 0L;
        for (Map.Entry<Long, Long> e : runs.entrySet()) {
            total += e.getValue() - e.getKey();
        }
        return total;
    }

    public void close() {
        synchronized (ioLock) {
            if (raf != null) {
                try {
                    raf.getFD().sync();
                } catch (Exception ignored) {
                    // 同步失败只是"下次重播可能少一段"，不影响正确性
                }
                closeQuietly();
            }
        }
        // 命中过就把 mtime 顶到最新：LRU 淘汰才不会把常听的歌先删掉
        if (servedBytes > 0L && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
        }
    }

    private void closeQuietly() {
        if (raf != null) {
            try {
                raf.close();
            } catch (Exception ignored) {
            }
            raf = null;
        }
    }

    private static String sha1Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                out[i * 2] = HEX[(d[i] >> 4) & 0xF];
                out[i * 2 + 1] = HEX[d[i] & 0xF];
            }
            return new String(out);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
