package com.ktools.zspacecarplayer.player.stream;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 *   <li><b>只读已知连续段，且账本必须落盘</b>：环形缓冲被重定位过，落盘的位置序列是<b>一段一段</b>的，
 *       中间有洞。所以用区间表记账，读取时先确认 {@code [position, position+n)} 真的连续，绝不跨过洞
 *       （跨过去就是把没下过的字节当数据交给解码器，表现为"歌里冒杂音"，比卡顿难查十倍）。
 *       <b>区间表存在 sidecar（{@code stream_<key>.runs}）里，绝不允许从 {@code .dat} 的文件长度
 *       反推</b>：跳写后文件长度是"最后写到的位置"，中间那一片是零填充的洞，按长度反推就等于
 *       把零字节当音频交出去（2026-10-08 自查时正是踩在这个坑上）。sidecar 缺失/解析失败/与
 *       {@code .dat} 长度或总长不一致，一律整份作废重建——宁可重下一遍。</li>
 *   <li><b>转码流不缓存</b>：chunked 流没有总长，长度进不了键、洞也判不出来，直接跳过。</li>
 * </ul>
 *
 * <p>容量：{@link #evictForSpace} 按最后修改时间从旧开始删，删到放得下为止；{@code .dat} 与它的
 * sidecar 算<b>同一条条目</b>一起淘汰。目录在 {@code getCacheDir()/stream-cache} 下，所以本模块的
 * 1GB 自管上限是套在 {@code CacheSizeManager} 全局 10GB 应急裁剪<b>之内</b>的更紧一层（全局那道是
 * 递归扫 {@code getCacheDir()} 的，会把这些文件也算进总量并在超限时按 mtime 删）——
 * 两层各管一件事，不是一句"沿用同一口径"能糊过去的。
 */
public final class StreamDiskCache {

    private static final String TAG = "StreamDiskCache";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 缓存文件前缀，{@code evictForSpace} 与清理都认它。 */
    static final String FILE_PREFIX = "stream_";
    static final String FILE_SUFFIX = ".dat";
    /** 区间表 sidecar：{@code stream_<key>.runs}，与同名 {@code .dat} 成对。 */
    static final String RUNS_SUFFIX = ".runs";
    /** sidecar 首行魔数＋总长，格式变了老文件必须自动作废而不是猜。 */
    private static final String RUNS_HEADER_PREFIX = "v1 ";
    /** 提前落账的步长：太小会把下载线程变成频繁小文件写，太大就保不住并发实例的进度。 */
    private static final long LEDGER_FLUSH_STRIDE_BYTES = 2L * 1024 * 1024;

    private final Object ioLock = new Object();
    private final TreeMap<Long, Long> runs = new TreeMap<Long, Long>();
    private final File file;
    private final File runsFile;
    private final long contentLength;
    private RandomAccessFile raf;
    private long servedBytes;
    private boolean broken;
    /**
     * 上次落账时的水位（最大 run 末尾 / 段数），用来决定要不要提前写 sidecar。
     *
     * <p>只在 {@code close()} 写账本是不够的：预取那一首的实例还开着（没 close）时，主播放会为
     * 同一个 itemId 再开一个实例，看不到账本就把正在写的那份 {@code .dat} 当孤儿删掉——省下的
     * 一次重连反而变成两次重连。所以<b>每出现新段、或连续段每推进
     * {@link #LEDGER_FLUSH_STRIDE_BYTES} 就先落一次账</b>，顺带也让进程被杀/断电后已下的部分仍可用。
     */
    private long ledgerFlushedEnd = -1L;
    private int ledgerFlushedRunCount = 0;

    private StreamDiskCache(File file, File runsFile, long contentLength) {
        this.file = file;
        this.runsFile = runsFile;
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
     * 区间表 → sidecar 文本（纯函数，单测覆盖）。
     *
     * <p>首行带总长：服务端把文件原地换过（wav→flac）时长度会变，读回来对不上就整份作废。
     */
    static String encodeRuns(TreeMap<Long, Long> runs, long contentLength) {
        StringBuilder sb = new StringBuilder();
        sb.append(RUNS_HEADER_PREFIX).append(contentLength).append('\n');
        for (Map.Entry<Long, Long> e : runs.entrySet()) {
            sb.append(e.getKey().longValue()).append('-').append(e.getValue().longValue())
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * sidecar 文本 → 区间表；只要有任何一处对不上就返回 {@code null} 表示"整份作废"。
     *
     * <p>这里不存在"尽力而为地恢复一部分"：区间表是"哪些字节真的能当音频读"的唯一依据，
     * 猜错一次的代价是杂音，而丢缓存的代价只是一次重连。所以解析失败、区间越界、区间重叠、
     * 顺序乱了，全都按作废处理（纯函数，单测覆盖）。
     */
    static TreeMap<Long, Long> decodeRuns(String text, long contentLength) {
        if (text == null || contentLength <= 0L) {
            return null;
        }
        TreeMap<Long, Long> parsed = new TreeMap<Long, Long>();
        long previousEnd = -1L;
        boolean headerRead = false;
        try {
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.length() == 0) {
                    continue;
                }
                if (!headerRead) {
                    if (!line.startsWith(RUNS_HEADER_PREFIX)) {
                        return null;
                    }
                    long declared = Long.parseLong(
                            line.substring(RUNS_HEADER_PREFIX.length()).trim());
                    if (declared != contentLength) {
                        return null;
                    }
                    headerRead = true;
                    continue;
                }
                int dash = line.indexOf('-');
                if (dash <= 0) {
                    return null;
                }
                long start = Long.parseLong(line.substring(0, dash).trim());
                long end = Long.parseLong(line.substring(dash + 1).trim());
                if (start < 0L || end <= start || end > contentLength) {
                    return null;
                }
                if (start < previousEnd) {
                    return null; // 正常写出的表必然升序且互不重叠
                }
                parsed.put(Long.valueOf(start), Long.valueOf(end));
                previousEnd = end;
            }
        } catch (Exception e) {
            return null;
        }
        return headerRead ? parsed : null;
    }

    /** 读 sidecar；文件缺失/读失败都归一成 {@code null}（= 没有可信账本）。 */
    private static String readSidecar(File runsFile) {
        if (runsFile == null || !runsFile.isFile()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(runsFile), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "runs sidecar unreadable: " + e);
            return null;
        }
        return sb.toString();
    }

    /**
     * 把当前区间表写回 sidecar（覆盖写；空表就删掉，不留垃圾）。
     *
     * @param durable true = 收尾那次，额外 fsync；提前落账走 false，不许在热路径上等闪存
     */
    private static void writeSidecar(File runsFile, TreeMap<Long, Long> runs, long contentLength,
                                    boolean durable) {
        if (runsFile == null) {
            return;
        }
        if (runs == null || runs.isEmpty()) {
            if (runsFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                runsFile.delete();
            }
            return;
        }
        String text = encodeRuns(runs, contentLength);
        try {
            java.io.OutputStream out = new FileOutputStream(runsFile, false);
            try {
                out.write(text.getBytes("UTF-8"));
                out.flush();
                // 只有收尾才 fsync：这条是缓存索引，不是用户数据。提前落账那次若也 fsync，
                // 闪存一次几百毫秒的挂起会直接拖慢下载线程；掉电丢一次账本的代价只是重建缓存，
                // 而"多一次停顿"的代价是车上听得见的。
                if (durable && out instanceof java.io.FileOutputStream) {
                    ((java.io.FileOutputStream) out).getFD().sync();
                }
            } finally {
                out.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "runs sidecar write failed, cache will be rebuilt next time: " + e);
            // 账本写不出去，旧账本配新 .dat 就是"猜连续性"，必须一起抹掉
            if (runsFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                runsFile.delete();
            }
        }
    }

    /**
     * 账本自洽性校验：区间必须都落在实际文件长度内。
     *
     * <p> {@code .dat} 比账本最长还长是允许的（跳写后的尾部填充，读不到）；反过来账本声称有、
     * 盘上却没有，就是文件被截断过——整份作废。
     */
    private static boolean runsFitOnDisk(TreeMap<Long, Long> runs, long fileLength) {
        for (Map.Entry<Long, Long> e : runs.entrySet()) {
            if (e.getValue().longValue() > fileLength) {
                return false;
            }
        }
        return true;
    }

    /**
     * 给目录腾出 {@code needBytes}：按最后修改时间从旧开始删，删到装得下为止。
     *
     * <p>只删自己前缀的文件，绝不碰目录里别的东西（同一 cacheDir 下还有 EQ 预设下载等）。
     * 一条条目 = {@code .dat + .runs}，<b>成对删除</b>：只删数据留下账本的话，下次打开会因为
     * "账本找不到对应的盘"而白白重建。返回删掉的字节数（含 sidecar）。
     * 最旧的一定先死，因为重播的是"最近听过的那几首"。
     */
    static long evictForSpace(File dir, long needBytes, long capBytes) {
        if (dir == null || !dir.isDirectory()) {
            return 0L;
        }
        List<Entry> entries = scanEntries(dir);
        long total = 0L;
        for (int i = 0; i < entries.size(); i++) {
            total += entries.get(i).bytes;
        }
        if (total + needBytes <= capBytes) {
            // 现有总量<b>加上要新放的那个文件</b>仍在上限内，才什么都不动。
            // 只看 total<=cap 是错的：200/250 看着有余量，再来 100 就装不下了（2026-10-08 单测抓到）
            return 0L;
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.valueOf(a.lastModified).compareTo(Long.valueOf(b.lastModified));
            }
        });
        long freed = 0L;
        long want = total - capBytes + needBytes;
        for (int i = 0; i < entries.size() && freed < want; i++) {
            freed += entries.get(i).delete();
        }
        return freed;
    }

    /** 目录里属于本模块的条目（{@code .dat} 与它的 sidecar 归成一条；孤立的 sidecar 也算一条）。 */
    private static final class Entry {
        private final String key;
        private File dat;
        private File runs;
        private long bytes;
        private long lastModified;

        Entry(String key) {
            this.key = key;
        }

        long delete() {
            long freed = 0L;
            if (dat != null && dat.isFile()) {
                freed += dat.length();
                if (!dat.delete()) {
                    freed -= dat.length();
                }
            }
            if (runs != null && runs.isFile()) {
                freed += runs.length();
                if (!runs.delete()) {
                    freed -= runs.length();
                }
            }
            return freed;
        }
    }

    private static List<Entry> scanEntries(File dir) {
        Map<String, Entry> byKey = new HashMap<String, Entry>();
        File[] kids = dir.listFiles();
        if (kids == null) {
            return new ArrayList<Entry>();
        }
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            if (!f.isFile()) {
                continue;
            }
            String name = f.getName();
            boolean isDat = name.endsWith(FILE_SUFFIX);
            boolean isRuns = !isDat && name.endsWith(RUNS_SUFFIX);
            if (!name.startsWith(FILE_PREFIX) || !(isDat || isRuns)) {
                continue;
            }
            String suffix = isDat ? FILE_SUFFIX : RUNS_SUFFIX;
            String key = name.substring(FILE_PREFIX.length(), name.length() - suffix.length());
            if (key.length() == 0) {
                continue;
            }
            Entry entry = byKey.get(key);
            if (entry == null) {
                entry = new Entry(key);
                byKey.put(key, entry);
            }
            if (isDat) {
                entry.dat = f;
            } else {
                entry.runs = f;
            }
            entry.bytes += f.length();
            long modified = f.lastModified();
            if (modified > entry.lastModified) {
                entry.lastModified = modified;
            }
        }
        return new ArrayList<Entry>(byKey.values());
    }

    /** 缓存目录当前占用（字节）。与 {@link #evictForSpace} 同口径：{@code .dat} 与 sidecar 都算。 */
    public static long dirBytes(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0L;
        }
        List<Entry> entries = scanEntries(dir);
        long total = 0L;
        for (int i = 0; i < entries.size(); i++) {
            total += entries.get(i).bytes;
        }
        return total;
    }

    /**
     * 清空缓存目录，返回删掉的字节数。设置页「清空缓存」用——车主有权知道自己设备里
     * 存了什么、并一键抹掉，这条不是可选功能。
     */
    public static long clearDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0L;
        }
        List<Entry> entries = scanEntries(dir);
        long freed = 0L;
        for (int i = 0; i < entries.size(); i++) {
            freed += entries.get(i).delete();
        }
        return freed;
    }

    /**
     * 打开（或接着用）一条流的缓存文件。任何失败都只意味着"这次没缓存"，不影响播放。
     *
     * <p>重开时<b>区间表只从 sidecar 恢复</b>：账本缺失、解析不了、或跟 {@code .dat} 的实际长度／
     * 资源总长对不上，就把这对文件删掉重来。绝不用 {@code raf.length()} 反推"前面都是连续数据"
     * ——跳写之后文件长度只代表"最后写到哪儿"，中间是零填充的洞（2026-10-08 自查抓到的缺陷）。
     *
     * @param dir 缓存目录（{@code getCacheDir()/stream-cache}）
     * @param maxFileBytes 单个文件上限，超过就不缓存
     * @param capBytes 整个缓存目录的 LRU 上限
     */
    static StreamDiskCache open(File dir, String url, long contentLength, long maxFileBytes,
                                long capBytes) {
        if (dir == null || !shouldCache(contentLength, maxFileBytes)) {
            if (contentLength > maxFileBytes) {
                Log.i(TAG, "skip disk cache: file " + contentLength + "B over per-file cap "
                        + maxFileBytes + "B " + url);
            }
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
        File runsFile = new File(dir, FILE_PREFIX + key + RUNS_SUFFIX);

        TreeMap<Long, Long> restored = restoreOrDiscard(f, runsFile, contentLength);
        if (!f.exists()) {
            evictForSpace(dir, contentLength, capBytes);
        }
        StreamDiskCache cache = new StreamDiskCache(f, runsFile, contentLength);
        try {
            cache.raf = new RandomAccessFile(f, "rw");
            if (restored != null) {
                cache.runs.putAll(restored);
            }
            if (cache.raf.length() > contentLength) {
                // 兜底：走到这里 restored 必然为 null（校验里就拦下了），文件只是刚被创建失败残留
                cache.raf.setLength(0L);
                cache.runs.clear();
            }
        } catch (Exception e) {
            cache.closeQuietly();
            Log.w(TAG, "open failed, continuing without disk cache: " + e);
            return null;
        }
        return cache;
    }

    /**
     * 把盘上那份缓存核对成可信的区间表，不可信就当场作废（删掉成对文件）并返回 {@code null}。
     */
    private static TreeMap<Long, Long> restoreOrDiscard(File dat, File runsFile,
                                                        long contentLength) {
        boolean hasDat = dat.isFile();
        boolean hasRuns = runsFile.isFile();
        if (!hasDat && !hasRuns) {
            return null; // 全新条目
        }
        if (!hasRuns || !hasDat) {
            // 只有半边：数据没账本不可信，账本没数据是垃圾，都清掉重开
            Log.i(TAG, "cache pair incomplete, rebuilding: dat=" + hasDat + " runs=" + hasRuns);
            if (hasDat) {
                //noinspection ResultOfMethodCallIgnored
                dat.delete();
            }
            if (hasRuns) {
                //noinspection ResultOfMethodCallIgnored
                runsFile.delete();
            }
            return null;
        }
        TreeMap<Long, Long> parsed = decodeRuns(readSidecar(runsFile), contentLength);
        if (parsed == null || !runsFitOnDisk(parsed, dat.length())) {
            Log.i(TAG, "cache ledger inconsistent with data, rebuilding: " + dat.getName()
                    + " datLen=" + dat.length() + " runs=" + (parsed == null ? "unparsable"
                    : parsed.size()));
            //noinspection ResultOfMethodCallIgnored
            dat.delete();
            //noinspection ResultOfMethodCallIgnored
            runsFile.delete();
            return null;
        }
        return parsed;
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
                maybeFlushLedger();
            } catch (Exception e) {
                broken = true;
                Log.w(TAG, "disk write failed, cache disabled for this stream: " + e);
            }
        }
    }

    /** 段数变了（说明出现了新的不连续段）或连续段推进够多，就提前把账本落盘。 */
    private void maybeFlushLedger() {
        if (broken) {
            return;
        }
        long maxEnd = runs.isEmpty() ? 0L : runs.lastEntry().getValue().longValue();
        if (runs.size() == ledgerFlushedRunCount && maxEnd - ledgerFlushedEnd
                < LEDGER_FLUSH_STRIDE_BYTES) {
            return;
        }
        writeSidecar(runsFile, runs, contentLength, false);
        ledgerFlushedEnd = maxEnd;
        ledgerFlushedRunCount = runs.size();
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
                if (broken) {
                    // 这一轮碰上过 IO 错误：盘上状态没人能保证，只删账本让下次整份重建
                    runs.clear();
                }
                try {
                    writeSidecar(runsFile, runs, contentLength, true);
                } catch (Exception e) {
                    Log.w(TAG, "close-time ledger write failed: " + e);
                }
                closeQuietly();
            }
        }
        // 命中过就把 mtime 顶到最新：LRU 淘汰才不会把常听的歌先删掉
        if (servedBytes > 0L && file.exists()) {
            long now = System.currentTimeMillis();
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(now);
            if (runsFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                runsFile.setLastModified(now);
            }
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
