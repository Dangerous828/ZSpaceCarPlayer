package com.ktools.zspacecarplayer.player.stream;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

/**
 * v3 抗抖动流式数据源（Feeder 层）。
 *
 * 后台单线程从远端 HTTP 音频流按 Range 顺序拉数据，写入一块大容量“滑动窗口”环形缓冲
 * （默认 8MB，1.5Mbps 无损流约 42 秒余量）。读取方（本地 HTTP 代理，进而 MediaPlayer /
 * MediaExtractor）从窗口内取数。网络抖动 / 短时断流时播放器不再直接 underrun，
 * 从根源上消除车机上“播 2 秒停 1 秒”式的卡顿。
 *
 * 设计要点：
 *  - 窗口语义：窗口内容为 [bufStart, bufEnd) 的连续字节，下载推进 bufEnd；
 *  - 背压：窗口写满后下载线程阻塞等待，内存占用恒定 ≤ capacity；
 *  - 读位登记：读方（每个 HTTP 连接一个 token）登记自己的读取位置，窗口头部回收
 *    绝不越过最慢读者的未读位置；整个环形缓冲（默认 8MB）天然构成回读余量
 *    （extractor 偶发重读窗口内数据时无需回源）；
 *  - seek / 落洞：readAt 位置不在窗口内 → 重置下载到该位置（清窗、断开旧连接、续传）；
 *  - 断流自愈：IOException 后带退避重试（从 bufEnd 续传），超过上限进入 fatal，
 *    由上层（看门狗 / 新连接 Range 请求）触发 reset 自愈；
 *  - epoch 防串写：每次 reset 递增 epoch，旧连接的迟到写入被拒绝，避免双下载器写坏窗口。
 *
 * 线程模型：所有状态由 {@link #lock} 保护；Downloader 单线程；读方为代理服务的连接线程。
 */
public class BufferedHttpSource {

    private static final String TAG = "BufferedHttpSource";

    /** 默认窗口容量：8MB ≈ 1.5Mbps 下约 42s、320kbps 下约 3.4min 的抗抖动余量 */
    public static final int DEFAULT_CAPACITY_BYTES = 8 * 1024 * 1024;
    /** 单次网络读块大小 */
    private static final int CHUNK_SIZE = 64 * 1024;
    /** 连续网络失败的最大重试次数（指数退避） */
    private static final int MAX_RETRY = 5;
    /** 重试退避基数（ms），第 n 次失败退避 n * RETRY_BASE_MS */
    private static final long RETRY_BASE_MS = 800L;
    /** 网络无任何进展视为卡死的时限 */
    private static final long STALL_TIMEOUT_MS = 30_000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final String url;
    private final int capacity;
    private final byte[] ring;

    private final Object lock = new Object();
    /** 每个活动读方（连接）的当前读取位置，用于窗口头部回收 */
    private final HashMap<Object, Long> readPosMap = new HashMap<Object, Long>();

    // ---- 窗口状态（lock 保护） ----
    private long bufStart = 0L;          // 窗口内最早可读的绝对字节位置
    private long bufEnd = 0L;            // 已下载到的绝对位置（窗口尾）
    private boolean eof = false;         // 远端数据已读完
    private long contentLength = -1L;    // 远端资源总长（-1 未知）
    private String contentType = "application/octet-stream";
    private boolean remoteAcceptsRanges = false;
    private boolean metaReady = false;   // 首个响应头已解析（contentLength/contentType 可用）
    private String fatalError = null;    // 下载侧不可恢复错误（reset 可清除自愈）

    // ---- 下载线程控制（lock 保护，downloadAbort/volatile 供读循环快速检查） ----
    private Thread downloaderThread;
    private volatile boolean downloadAbort = false;
    private volatile boolean closed = false;
    private long downloadEpoch = 0L;     // 每次 reset +1，拒绝旧连接迟到写入
    private long lastProgressAtMs = 0L;  // 最后一次成功写窗时间（卡死检测）

    // ---- 代理服务侧引用计数 ----
    private int refCount = 0;
    private long lastUsedAtMs = 0L;
    /** 是否曾有读者登记过：区分「从未被读（需预取元数据）」和「读者已全部离开（应休眠）」 */
    private boolean everHadReader = false;

    /** 无读者休眠判定：曾有过读者且现在全部离开。构建初期的元数据预取不受此门控。 */
    private boolean idleNoReaders() {
        return everHadReader && readPosMap.isEmpty();
    }

    public BufferedHttpSource(String url) {
        this(url, DEFAULT_CAPACITY_BYTES);
    }

    public BufferedHttpSource(String url, int capacityBytes) {
        this.url = url;
        this.capacity = Math.max(CHUNK_SIZE * 2, capacityBytes);
        this.ring = new byte[this.capacity];
        ensureDownloader();
    }

    // ------------------------------------------------------------------ //
    //  读方 API（HttpProxyServer 连接线程调用）
    // ------------------------------------------------------------------ //

    /**
     * 阻塞读取指定绝对位置的数据。
     *
     * @return 实际读取字节数；数据流结束返回 -1
     * @throws IOException 源已关闭 / 下载侧 fatal / 等待被打断
     */
    public int readAt(long position, byte[] dest, int destOffset, int length) throws IOException {
        if (dest == null || length <= 0) {
            return 0;
        }
        synchronized (lock) {
            while (true) {
                if (fatalError != null) {
                    throw new IOException("buffered source error: " + fatalError);
                }
                if (closed) {
                    throw new IOException("buffered source closed");
                }
                if (position >= bufStart && position < bufEnd) {
                    int off = (int) (position % capacity);
                    int avail = (int) (bufEnd - position);
                    int n = Math.min(length, Math.min(avail, capacity - off));
                    System.arraycopy(ring, off, dest, destOffset, n);
                    return n;
                }
                if (eof && position >= bufEnd) {
                    return -1;
                }
                if (position < bufStart || position > bufEnd) {
                    // 落在窗口之外（新 seek / 回读越过已回收区）：重定位下载
                    requestResetLocked(position);
                    continue;
                }
                // position == bufEnd 且未 EOF：等待下载推进；做卡死检测
                long now = SystemClock.elapsedRealtime();
                if (lastProgressAtMs > 0 && now - lastProgressAtMs > STALL_TIMEOUT_MS) {
                    if (downloaderThread == null || !downloaderThread.isAlive()) {
                        fatalError = "downloader dead with no progress";
                    } else {
                        fatalError = "network stall over " + STALL_TIMEOUT_MS + "ms";
                    }
                    Log.e(TAG, "readAt stall: url=" + url + " pos=" + position);
                    lock.notifyAll();
                    continue;
                }
                try {
                    lock.wait(300);
                } catch (InterruptedException e) {
                    throw new IOException("readAt interrupted");
                }
            }
        }
    }

    /** 登记一个读方（连接）的起始位置。token 由调用方保证唯一。 */
    public void addReadPos(Object token, long pos) {
        synchronized (lock) {
            readPosMap.put(token, pos);
            everHadReader = true;
            lock.notifyAll();
        }
        ensureDownloader(); // 读者就位：拉起（可能已休眠的）下载线程
    }

    /** 读方推进位置（每写出一块数据后调用）。 */
    public void updateReadPos(Object token, long pos) {
        synchronized (lock) {
            Long old = readPosMap.get(token);
            if (old == null || pos > old) {
                readPosMap.put(token, pos);
            }
            lock.notifyAll();
        }
    }

    /** 读方（连接）结束，注销。 */
    public void removeReadPos(Object token) {
        synchronized (lock) {
            readPosMap.remove(token);
            lock.notifyAll();
        }
    }

    /**
     * 等待首个响应头解析完成（contentLength / contentType 就绪）。
     * 代理需要在回复 HTTP 头之前拿到总长，否则客户端无法 seek / 计算时长。
     *
     * @return true 表示元数据就绪
     */
    public boolean awaitMeta(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (lock) {
            while (!metaReady && fatalError == null && !closed
                    && SystemClock.elapsedRealtime() < deadline) {
                try {
                    lock.wait(250);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return metaReady;
        }
    }

    // ------------------------------------------------------------------ //
    //  属性
    // ------------------------------------------------------------------ //

    public String getUrl() {
        return url;
    }

    /** 当前窗口起始绝对位置（近邻共享判断用） */
    public long getWindowStart() {
        synchronized (lock) {
            return bufStart;
        }
    }

    /** 当前窗口末端绝对位置（已下载到的位置） */
    public long getWindowEnd() {
        synchronized (lock) {
            return bufEnd;
        }
    }

    public long getContentLength() {
        synchronized (lock) {
            return contentLength;
        }
    }

    public String getContentType() {
        synchronized (lock) {
            return contentType;
        }
    }

    /** 已缓冲字节数（调试 / UI 用） */
    public long getBufferedBytes() {
        synchronized (lock) {
            return bufEnd - bufStart;
        }
    }

    /** 缓冲进度百分比（0-100；总长未知返回 -1） */
    public int getBufferedPercent() {
        synchronized (lock) {
            if (contentLength <= 0) {
                return -1;
            }
            long pct = bufEnd * 100L / contentLength;
            return (int) Math.min(100L, Math.max(0L, pct));
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public int getRefCount() {
        synchronized (lock) {
            return refCount;
        }
    }

    public void addRef() {
        synchronized (lock) {
            refCount++;
            lastUsedAtMs = SystemClock.elapsedRealtime();
        }
    }

    public void release() {
        synchronized (lock) {
            refCount = Math.max(0, refCount - 1);
            lastUsedAtMs = SystemClock.elapsedRealtime();
        }
    }

    public void touch() {
        synchronized (lock) {
            lastUsedAtMs = SystemClock.elapsedRealtime();
        }
    }

    public long getLastUsedAtMs() {
        synchronized (lock) {
            return lastUsedAtMs;
        }
    }

    /** 关闭数据源：唤醒所有等待者，终止下载线程。不可恢复。 */
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            downloadAbort = true;
            lock.notifyAll();
        }
        Thread t = downloaderThread;
        if (t != null) {
            t.interrupt();
        }
        Log.i(TAG, "closed: " + url);
    }

    // ------------------------------------------------------------------ //
    //  下载侧
    // ------------------------------------------------------------------ //

    private void ensureDownloader() {
        synchronized (lock) {
            ensureDownloaderLocked();
        }
    }

    private void ensureDownloaderLocked() {
        if (closed) {
            return;
        }
        if (downloaderThread != null && downloaderThread.isAlive()) {
            return;
        }
        downloaderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                downloadLoop();
            }
        }, "bufsrc-dl");
        downloaderThread.setDaemon(true);
        downloaderThread.start();
    }

    /**
     * 读方请求重定位：清窗、断开旧下载连接、从 position 重新下载。
     * 同时清除 fatal 错误实现自愈（上层看门狗重连即可恢复播放）。
     */
    private void requestResetLocked(long position) {
        downloadEpoch++;
        downloadAbort = true;
        eof = false;
        bufStart = position;
        bufEnd = position;
        fatalError = null;
        lastProgressAtMs = SystemClock.elapsedRealtime();
        lock.notifyAll();
        ensureDownloaderLocked();
        Log.i(TAG, "reset download -> " + position + " url=" + url);
    }

    private void downloadLoop() {
        int retry = 0;
        while (!closed) {
            long epoch;
            synchronized (lock) {
                if (fatalError != null) {
                    return; // 等待上层 reset 自愈
                }
                if (eof) {
                    return;
                }
                epoch = downloadEpoch;
                downloadAbort = false;
            }
            try {
                downloadOnce(epoch);
                synchronized (lock) {
                    if (epoch == downloadEpoch) {
                        retry = 0; // 干净完成（eof 或被新 reset 接管）
                        if (idleNoReaders()) {
                            return; // 读者已全部离开：下载线程休眠，新读者 addReadPos 时再拉起
                        }
                    }
                }
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                synchronized (lock) {
                    if (epoch != downloadEpoch) {
                        continue; // 旧连接的失败直接忽略
                    }
                    retry++;
                    if (retry > MAX_RETRY) {
                        fatalError = "download failed after " + MAX_RETRY + " retries: " + e.getMessage();
                        Log.e(TAG, "fatal: " + fatalError + " url=" + url);
                        lock.notifyAll();
                        return;
                    }
                    Log.w(TAG, "download retry " + retry + "/" + MAX_RETRY + " from " + bufEnd + ": " + e);
                }
                sleepInterruptible(retry * RETRY_BASE_MS);
            }
        }
    }

    /** 建立一次连接，从当前 bufEnd 顺序下载到窗口，直到 EOF / abort / closed。 */
    private void downloadOnce(long epoch) throws IOException {
        long start;
        synchronized (lock) {
            start = bufEnd;
        }
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("User-Agent", "ZSpaceCarPlayer/3.0 buffered-source");
            boolean wantRange = start > 0;
            if (wantRange) {
                c.setRequestProperty("Range", "bytes=" + start + "-");
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            boolean partial = (code == HttpURLConnection.HTTP_PARTIAL);
            synchronized (lock) {
                if (partial) {
                    remoteAcceptsRanges = true;
                    long total = parseTotalFromContentRange(c.getHeaderField("Content-Range"));
                    if (total >= 0) {
                        contentLength = total;
                    }
                } else {
                    long cl = c.getContentLength();
                    if (cl >= 0) {
                        contentLength = (start > 0) ? start + cl : cl;
                    }
                }
                String ct = c.getContentType();
                if (ct != null && ct.length() > 0) {
                    contentType = ct;
                }
                metaReady = true;
                lock.notifyAll();
            }
            in = c.getInputStream();
            if (wantRange && !partial) {
                // 远端不支持 Range：只能全量拉取并跳过前 start 字节（Jellyfin 支持 Range，正常走不到）
                Log.w(TAG, "remote ignored Range, skipping " + start + " bytes");
                skipFully(in, start);
            }
            conn = c;
            pumpIntoRing(in, epoch);
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void pumpIntoRing(InputStream in, long epoch) throws IOException {
        byte[] chunk = new byte[CHUNK_SIZE];
        while (true) {
            if (closed) {
                return;
            }
            synchronized (lock) {
                if (downloadAbort || epoch != downloadEpoch) {
                    return;
                }
                if (idleNoReaders()) {
                    return; // 无读者：暂停下载，避免分家后的源在后台空耗带宽
                }
            }
            int n;
            try {
                n = in.read(chunk);
            } catch (IOException e) {
                throw e; // 交给 downloadLoop 带退避续传
            }
            if (n < 0) {
                synchronized (lock) {
                    if (epoch == downloadEpoch) {
                        if (contentLength > 0 && bufEnd < contentLength) {
                            // 远端提前 FIN（总长未下满）：按截断处理，交由 downloadLoop
                            // 带退避从 bufEnd 续传；若按 EOF 标记会让代理提前结束 body，
                            // MediaPlayer 静默停在半路不报错也不推进
                            long at = bufEnd;
                            long total = contentLength;
                            throw new IOException("remote closed early at " + at + "/" + total);
                        }
                        eof = true;
                        lock.notifyAll();
                    }
                }
                return;
            }
            if (n == 0) {
                continue;
            }
            if (writeToRing(chunk, n, epoch) < 0) {
                return; // closed / aborted / epoch 过期
            }
            synchronized (lock) {
                if (epoch == downloadEpoch) {
                    lastProgressAtMs = SystemClock.elapsedRealtime();
                }
            }
        }
    }

    /**
     * 把一块数据写入窗口尾部。窗口满时回收头部：
     * 无读者 → 自由滑动；有读者 → 绝不越过最慢读者的未读位置；
     * 读者已越过「滑到窗口尾所需位置」时正常滑动（只丢已读数据，不卡死）。
     *
     * @return 写入字节数；closed / aborted / epoch 过期返回 -1
     */
    private int writeToRing(byte[] chunk, int n, long epoch) {
        synchronized (lock) {
            while (true) {
                if (closed || downloadAbort || epoch != downloadEpoch) {
                    return -1;
                }
                if (idleNoReaders()) {
                    return -1; // 读者全部离开：放弃本次写入，线程随 downloadLoop 休眠
                }
                long free = capacity - (bufEnd - bufStart);
                if (free >= n) {
                    break;
                }
                long minPos = minReadPosLocked();
                long hardNeed = bufEnd - capacity + n; // 放下 n 字节至少要滑到的位置
                long target;
                if (minPos == Long.MAX_VALUE) {
                    target = hardNeed; // 无读者：自由滑动
                } else if (minPos < hardNeed) {
                    target = minPos;   // 读者落后：不越过任何未读数据，等它追
                } else {
                    target = hardNeed; // 读者已越过 hardNeed：只丢已读数据
                }
                if (target > bufStart) {
                    bufStart = Math.max(0L, target);
                }
                if (capacity - (bufEnd - bufStart) >= n) {
                    break;
                }
                try {
                    lock.wait(1000);
                } catch (InterruptedException e) {
                    return -1;
                }
            }
            int off = (int) (bufEnd % capacity);
            int first = Math.min(n, capacity - off);
            System.arraycopy(chunk, 0, ring, off, first);
            if (first < n) {
                System.arraycopy(chunk, first, ring, 0, n - first);
            }
            bufEnd += n;
            lock.notifyAll();
            return n;
        }
    }

    private long minReadPosLocked() {
        long min = Long.MAX_VALUE;
        for (Long v : readPosMap.values()) {
            if (v != null && v < min) {
                min = v;
            }
        }
        return min;
    }

    // ------------------------------------------------------------------ //
    //  工具
    // ------------------------------------------------------------------ //

    /** 解析 Content-Range: "bytes 0-12345/67890" → 67890；"bytes *\/67890" → 67890 */
    private static long parseTotalFromContentRange(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) {
            return -1L;
        }
        String total = contentRange.substring(slash + 1).trim();
        if ("*".equals(total)) {
            return -1L;
        }
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] skipBuf = new byte[CHUNK_SIZE];
        while (remaining > 0) {
            long r = in.skip(remaining);
            if (r > 0) {
                remaining -= r;
                continue;
            }
            int n = in.read(skipBuf, 0, (int) Math.min(skipBuf.length, remaining));
            if (n < 0) {
                throw new IOException("EOF while skipping " + remaining + " bytes");
            }
            remaining -= n;
        }
    }

    private static void sleepInterruptible(long ms) {
        long remaining = ms;
        while (remaining > 0) {
            long step = Math.min(500L, remaining);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                return;
            }
            remaining -= step;
        }
    }
}
