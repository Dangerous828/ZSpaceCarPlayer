package com.ktools.zspacecarplayer.player.stream;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v3 本地回环 HTTP 流代理。
 *
 * 把远端 http(s) 音频流包装成本地地址：{@code http://127.0.0.1:{port}/stream?u={urlEncoded}}。
 * MediaPlayer / MediaExtractor 请求该地址时，由 {@link BufferedHttpSource}（大环形缓冲）
 * 提供数据，从系统播放器数百毫秒级的内部缓冲升级为秒级大缓冲，抵御公网串流抖动。
 *
 * 行为约定（贴合 Android 4.3 NuHTTPDataSource / MediaExtractor HTTPDataSource）：
 *  - 支持 GET + {@code Range: bytes=N-}；响应 200/206 + Content-Length + Accept-Ranges，
 *    总长未知时退化为 200 + Connection: close（客户端无 seek、无时长）；
 *  - 每个 HTTP 连接只处理一个请求，响应完即关闭（客户端 seek 时自会带 Range 重连，
 *    命中缓冲窗口内则零网络往返秒开）；
 *  - 同一远端 URL 的连接按「近邻共享」复用 {@link BufferedHttpSource}（起始位置落在
 *    现有窗口内才共享，引用计数 + LRU）；窗口外的新读者（如 extractor 的文件尾探测连接）
 *    派生独立数据源，避免共享窗口被相距数 MB 的两个读者互相 reset 拉扯成乒乓；
 *  - 仅绑定 127.0.0.1，不对外暴露。
 *
 * 单例使用：{@link #getInstance()} → {@link #getProxyUrl(String)} → 播放结束后 {@link #clearSources()}。
 */
public final class HttpProxyServer {

    private static final String TAG = "HttpProxyServer";

    private static HttpProxyServer sInstance;

    /** 同时保留的远端数据源上限（LRU 淘汰空闲者）。2026-09-12 缓冲/预取：从 2 提到 3，
     *  容纳「当前曲 8MB + 下一首预取 2MB + 1 个 slack（切歌瞬间新旧源并存）」。
     *  淘汰逻辑保证正在播的当前源（有读者）与刚预取的下一首源都不被淘汰。 */
    private static final int MAX_SOURCES = BufferingPolicy.maxSources();
    /** 新建数据源后最多保留的空闲（无读者）源数，超出立即关闭释放窗口 */
    private static final int MAX_IDLE_SOURCES = 0;
    /** 等待远端响应头就绪的上限（决定能否回复 Content-Length） */
    private static final long META_WAIT_MS = 10_000L;
    /** 客户端 socket 空闲读超时（请求头阶段） */
    private static final int REQUEST_SO_TIMEOUT_MS = 30_000;
    /** 每次向客户端写出的数据块 */
    private static final int PUMP_CHUNK = 32 * 1024;
    /** 每累积这么多字节 flush 一次，让客户端尽早拿到数据 */
    private static final long FLUSH_INTERVAL_BYTES = 64 * 1024;

    private ServerSocket serverSocket;
    private int port = -1;
    private Thread acceptThread;

    private final Object sourceLock = new Object();
    /** key 仅作登记去重；查找按 value.getUrl() + 窗口近邻匹配（分家源的 key 带 "#f" 后缀） */
    private final LinkedHashMap<String, BufferedHttpSource> sources =
            new LinkedHashMap<String, BufferedHttpSource>();
    private int forkSeq = 0;

    private HttpProxyServer() {
    }

    public static synchronized HttpProxyServer getInstance() {
        if (sInstance == null) {
            sInstance = new HttpProxyServer();
        }
        return sInstance;
    }

    // ------------------------------------------------------------------ //
    //  对外 API
    // ------------------------------------------------------------------ //

    /**
     * 把远端 http(s) 地址映射为本地代理地址；非 http(s)（本地文件等）原样返回。
     * 代理启动失败时退回原始地址（行为与不接代理一致）。
     */
    public String getProxyUrl(String remoteUrl) {
        if (remoteUrl == null
                || (!remoteUrl.startsWith("http://") && !remoteUrl.startsWith("https://"))) {
            return remoteUrl;
        }
        ensureStarted();
        if (port < 0) {
            Log.w(TAG, "proxy server not started, fallback to direct url");
            return remoteUrl;
        }
        try {
            return "http://127.0.0.1:" + port + "/stream?u="
                    + URLEncoder.encode(remoteUrl, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return remoteUrl;
        }
    }

    /**
     * 为原生软解等需要直接读取 BufferedHttpSource 的组件获取数据源实例并增加引用计数。
     * 调用方在使用完毕后必须显式调用 source.release()。
     */
    public BufferedHttpSource acquireSource(String remoteUrl, long requestStart) {
        ensureStarted();
        BufferedHttpSource source = obtainSource(remoteUrl, requestStart);
        source.addRef();
        return source;
    }

    /**
     * 从本地回环代理 URL (http://127.0.0.1:port/stream?u=...) 中提取原始远端 URL。
     */
    public static String extractRemoteUrl(String pathOrUrl) {
        if (pathOrUrl == null) return "";
        if (pathOrUrl.contains("/stream?u=")) {
            int idx = pathOrUrl.indexOf("/stream?u=");
            String encoded = pathOrUrl.substring(idx + "/stream?u=".length());
            try {
                return java.net.URLDecoder.decode(encoded, "UTF-8");
            } catch (Exception ignored) {}
        }
        return pathOrUrl;
    }

    /**
     * 释放全部缓冲数据源（停止下载线程、清空缓冲）。
     * 播放器释放 / 服务销毁时调用；代理服务本身保持运行以便复用。
     */
    public void clearSources() {
        synchronized (sourceLock) {
            for (BufferedHttpSource source : sources.values()) {
                try {
                    source.close();
                } catch (Throwable t) {
                    Log.w(TAG, "close source failed", t);
                }
            }
            sources.clear();
        }
    }

    /** 供调试 / 状态上报：当前某远端 URL 的缓冲百分比（无活动源返回 -1） */
    public int getBufferedPercent(String remoteUrl) {
        synchronized (sourceLock) {
            for (BufferedHttpSource source : sources.values()) {
                if (!source.isClosed() && source.getUrl().equals(remoteUrl)) {
                    return source.getBufferedPercent();
                }
            }
            return -1;
        }
    }

    /** 已缓冲字节数（prefill 门槛判定用）；无活动源返回 -1。（2026-09-12 缓冲/预取） */
    public long getBufferedBytes(String remoteUrl) {
        synchronized (sourceLock) {
            for (BufferedHttpSource source : sources.values()) {
                if (!source.isClosed() && source.getUrl().equals(remoteUrl)) {
                    return source.getBufferedBytes();
                }
            }
            return -1L;
        }
    }

    /** 该远端资源累计下载到的偏移（速率采样用）；无活动源返回 -1。 */
    public long getDownloadedBytes(String remoteUrl) {
        synchronized (sourceLock) {
            for (BufferedHttpSource source : sources.values()) {
                if (!source.isClosed() && source.getUrl().equals(remoteUrl)) {
                    return source.getDownloadedBytes();
                }
            }
            return -1L;
        }
    }

    /** 远端资源总长（prefill 门槛估算码率用）；无活动源 / 未知返回 -1。 */
    public long getContentLength(String remoteUrl) {
        synchronized (sourceLock) {
            for (BufferedHttpSource source : sources.values()) {
                if (!source.isClosed() && source.getUrl().equals(remoteUrl)) {
                    return source.getContentLength();
                }
            }
            return -1L;
        }
    }

    /** 窗口容量（prefill 门槛按容量比例算目标用）；无活动源返回 -1。 */
    public int getWindowCapacity(String remoteUrl) {
        synchronized (sourceLock) {
            for (BufferedHttpSource source : sources.values()) {
                if (!source.isClosed() && source.getUrl().equals(remoteUrl)) {
                    return source.getCapacityBytes();
                }
            }
            return -1;
        }
    }

    /** 当前曲是否饥饿 / 断流（预取让位判定用）；无活动源返回 false。 */
    public boolean isSourceStarving(String remoteUrl) {
        synchronized (sourceLock) {
            for (BufferedHttpSource source : sources.values()) {
                if (!source.isClosed() && source.getUrl().equals(remoteUrl)) {
                    return source.isStarving();
                }
            }
            return false;
        }
    }

    /**
     * 预取「下一首」：为 remoteUrl 建一个预取源并预热下载线程，eager 下载到
     * {@link BufferingPolicy#prefetchCapacityBytes()}（约 2MB）即休眠，绝不 free-slide 抢当前曲带宽。
     *
     * 关键一：**环形数组按整窗 {@link BufferedHttpSource#DEFAULT_CAPACITY_BYTES}（8MB）分配**，
     * 只把 <b>eager 下载目标</b>压到 2MB（见 BufferedHttpSource 5 参构造的解耦说明）。因为容量是
     * final，若这里按 2MB 建环，将来这首晋升为当前曲就只能在 2MB 窗上滚动——重蹈「2MB 被打穿」的
     * 卡顿。整窗分配 + 小 eager 目标 = 预取期省带宽、接管后仍有完整 8MB 抗抖余量。
     *
     * 关键二：**不 addRef、不占读者位**（复用「无读者也预填」特性），因此不会打乱引用计数；
     * 将来真正播放这首时 {@link #obtainSource} 会按 URL + 窗口近邻命中同一个源，无缝起播。
     * 已有同 URL 源（当前曲 / 已预取过）则只 touch 复用，绝不重复建源。
     * （2026-09-12 缓冲/预取）
     */
    public void prefetch(String remoteUrl) {
        if (remoteUrl == null
                || (!remoteUrl.startsWith("http://") && !remoteUrl.startsWith("https://"))) {
            return;
        }
        ensureStarted();
        synchronized (sourceLock) {
            for (BufferedHttpSource source : sources.values()) {
                if (!source.isClosed() && source.getUrl().equals(remoteUrl)) {
                    source.touch(); // 已有源（当前曲或已预取）：命中复用即可
                    return;
                }
            }
            if (sources.size() >= MAX_SOURCES) {
                evictIdleForNewSource(false); // 先淘汰非预取空闲源，保护当前曲与已预取源
            }
            if (sources.size() >= MAX_SOURCES) {
                Log.w(TAG, "prefetch skipped, source table full: " + remoteUrl);
                return;
            }
            BufferedHttpSource created = new BufferedHttpSource(
                    remoteUrl, BufferedHttpSource.DEFAULT_CAPACITY_BYTES, 0L, true,
                    BufferingPolicy.prefetchCapacityBytes());
            sources.put(remoteUrl + "#pf" + (forkSeq++), created);
            Log.i(TAG, "prefetch source created (full 8MB ring, eager target "
                    + (BufferingPolicy.prefetchCapacityBytes() / 1024) + "KB): " + remoteUrl);
            purgeIdleSources(created);
        }
    }

    /**
     * 作废指定 URL 的预取源（让位当前曲 / 切歌后清理不再相关的预取）。
     * 只关闭仍在预热、无读者接管的预取源；已被真读者接管（升级为当前曲）的源不动。
     * （2026-09-12 缓冲/预取）
     */
    public void abortPrefetch(String remoteUrl) {
        if (remoteUrl == null) {
            return;
        }
        synchronized (sourceLock) {
            Iterator<Map.Entry<String, BufferedHttpSource>> it = sources.entrySet().iterator();
            while (it.hasNext()) {
                BufferedHttpSource source = it.next().getValue();
                if (source.isPrefetch() && source.getRefCount() <= 0
                        && source.getUrl().equals(remoteUrl)) {
                    source.close();
                    it.remove();
                    Log.i(TAG, "prefetch aborted (yield to current song / stale): " + remoteUrl);
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  服务生命周期
    // ------------------------------------------------------------------ //

    private void ensureStarted() {
        if (acceptThread != null && acceptThread.isAlive()) {
            return;
        }
        // accept 线程已死但旧 socket 还开着：先关掉，避免 fd 泄漏
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        try {
            serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            port = serverSocket.getLocalPort();
        } catch (IOException e) {
            port = -1;
            Log.e(TAG, "start server failed", e);
            return;
        }
        final ServerSocket ss = serverSocket;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(ss);
            }
        }, "httpproxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "proxy server started at 127.0.0.1:" + port);
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            final Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                if (!ss.isClosed()) {
                    Log.w(TAG, "accept failed: " + e);
                }
                return;
            }
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    serveConnection(socket);
                }
            }, "httpproxy-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    // ------------------------------------------------------------------ //
    //  单连接处理
    // ------------------------------------------------------------------ //

    private void serveConnection(Socket socket) {
        BufferedHttpSource source = null;
        Object token = new Object();
        try {
            socket.setSoTimeout(REQUEST_SO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream rawIn = socket.getInputStream();
            OutputStream rawOut = socket.getOutputStream();

            HttpRequest request = readRequest(rawIn);
            if (request == null) {
                return; // 客户端连上后没发请求就断了
            }
            String remoteUrl = extractQueryParam(request.rawQuery, "u");
            if (remoteUrl == null || remoteUrl.length() == 0) {
                writeSimpleResponse(rawOut, "400 Bad Request");
                return;
            }
            long start = parseRangeStart(request.rangeHeader);
            source = obtainSource(remoteUrl, start);
            source.addRef();
            serveRange(rawOut, source, start < 0 ? 0 : start, token);
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "client idle timeout");
        } catch (IOException e) {
            // 客户端 seek / 释放导致的断开属于正常路径
            Log.d(TAG, "client connection ended: " + e);
        } catch (Throwable t) {
            Log.e(TAG, "serve failed", t);
        } finally {
            if (source != null) {
                source.removeReadPos(token);
                source.release();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 回复响应头并泵送缓冲数据，直到 EOF 或客户端断开。 */
    private void serveRange(OutputStream rawOut, BufferedHttpSource source, long start,
                            Object token) throws IOException {
        // 等远端响应头就绪：拿到总长才能回复 Content-Length（否则客户端无法 seek/算时长）
        boolean metaReady = source.awaitMeta(META_WAIT_MS);
        long total = source.getContentLength();

        BufferedOutputStream out = new BufferedOutputStream(rawOut, PUMP_CHUNK);
        StringBuilder head = new StringBuilder(256);
        if (total >= 0 && start >= total) {
            writeSimpleResponse(out, "416 Requested Range Not Satisfiable");
            return;
        }
        if (metaReady && total >= 0 && start > 0) {
            head.append("HTTP/1.1 206 Partial Content\r\n");
            head.append("Content-Range: bytes ").append(start).append('-')
                    .append(total - 1).append('/').append(total).append("\r\n");
            head.append("Content-Length: ").append(total - start).append("\r\n");
        } else if (metaReady && total >= 0) {
            head.append("HTTP/1.1 200 OK\r\n");
            head.append("Content-Length: ").append(total).append("\r\n");
        } else {
            // 元数据未就绪 / 总长未知：退化为关闭定界流
            head.append("HTTP/1.1 200 OK\r\n");
        }
        head.append("Content-Type: ").append(source.getContentType()).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        head.append("Connection: close\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes("ISO-8859-1"));
        out.flush();

        byte[] buf = new byte[PUMP_CHUNK];
        long pos = start;
        long lastFlush = pos;
        source.addReadPos(token, pos);
        boolean firstChunk = true;
        while (true) {
            int n = source.readAt(pos, buf, 0, buf.length);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            pos += n;
            source.updateReadPos(token, pos);
            if (firstChunk || pos - lastFlush >= FLUSH_INTERVAL_BYTES) {
                out.flush();
                lastFlush = pos;
                firstChunk = false;
            }
        }
        out.flush();
    }

    private void writeSimpleResponse(OutputStream out, String statusLine) throws IOException {
        String resp = "HTTP/1.1 " + statusLine + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(resp.getBytes("ISO-8859-1"));
        out.flush();
    }

    // ------------------------------------------------------------------ //
    //  数据源注册表
    // ------------------------------------------------------------------ //

    /**
     * 为一个新连接选择/创建数据源。
     *
     * 近邻共享：请求起始位置落在某个同 URL 源的当前窗口「内」（含 bufEnd）才共享——
     * 共享读者的初始位置必然在窗口内且只向前移动，头部回收又绝不越过最慢读者的未读位，
     * 因此共享者之间永远不会互相把对方踢出窗口。
     *
     * 远端分家：位置在窗口外（典型如 NuPlayer/extractor 为算时长向文件尾发的
     * 第二条探测连接，与主连接相距数 MB）必须派生独立数据源。若强行共享同一窗口，
     * 两个读者会无限互相触发 reset 乒乓（实测 12 分钟刷 2129 条 reset、零字节进展、
     * 播放彻底锁死）。
     */
    private BufferedHttpSource obtainSource(String remoteUrl, long requestStart) {
        synchronized (sourceLock) {
            BufferedHttpSource best = null;
            Iterator<Map.Entry<String, BufferedHttpSource>> it = sources.entrySet().iterator();
            while (it.hasNext()) {
                BufferedHttpSource candidate = it.next().getValue();
                if (candidate.isClosed()) {
                    it.remove();
                    continue;
                }
                if (candidate.hasFatalError()) {
                    // 已不可恢复（断流 / 重试耗尽 / 涓流饥饿）：fatal 只能靠重定位清除，
                    // 而重试同一首的 requestStart 往往正落在旧窗口内、会被判成近邻共享，
                    // 新读者一进来就吃 IOException，自愈永远发生不了。直接回收逼出新源。
                    candidate.close();
                    it.remove();
                    continue;
                }
                if (!candidate.getUrl().equals(remoteUrl)) {
                    continue;
                }
                if (candidate.getWindowStart() <= requestStart
                        && requestStart <= candidate.getWindowEnd()
                        && (best == null || candidate.getLastUsedAtMs() > best.getLastUsedAtMs())) {
                    best = candidate;
                }
            }
            if (best != null) {
                best.touch();
                for (Map.Entry<String, BufferedHttpSource> e : sources.entrySet()) {
                    if (e.getValue() == best) { // LRU 置尾
                        String key = e.getKey();
                        sources.remove(key);
                        sources.put(key, best);
                        break;
                    }
                }
                return best;
            }
            // 无近邻可共享：为该读者独立建源
            if (sources.size() >= MAX_SOURCES) {
                // 两轮淘汰（2026-09-12 缓冲/预取）：第一轮只淘汰「无读者且非预取」的空闲源，
                // 保护正在播的当前源（有读者）与刚预取的下一首源；仍满才动预取源（可作废重来）。
                evictIdleForNewSource(false);
                if (sources.size() >= MAX_SOURCES) {
                    evictIdleForNewSource(true);
                }
            }
            BufferedHttpSource created = new BufferedHttpSource(remoteUrl, requestStart < 0 ? 0 : requestStart);
            boolean isFork = false;
            for (BufferedHttpSource s : sources.values()) {
                if (!s.isClosed() && s.getUrl().equals(remoteUrl)) {
                    isFork = true;
                    break;
                }
            }
            sources.put(remoteUrl + "#f" + (forkSeq++), created);
            Log.i(TAG, "new source: " + remoteUrl + (isFork ? " (fork)" : ""));
            purgeIdleSources(created);
            return created;
        }
    }

    /**
     * 建源前的 LRU 淘汰（按 LinkedHashMap 头 = 最久未用）。
     *
     * 正在播的当前源恒有读者（refCount&gt;0）——任何一轮都不淘汰它。
     *
     * @param includePrefetch false = 只淘汰非预取空闲源（保护刚预取的下一首）；
     *                        true = 表仍满时的最后手段，允许淘汰预取源（预取可作废重来）
     */
    private void evictIdleForNewSource(boolean includePrefetch) {
        Iterator<Map.Entry<String, BufferedHttpSource>> it = sources.entrySet().iterator();
        while (it.hasNext()) {
            BufferedHttpSource candidate = it.next().getValue();
            if (candidate.getRefCount() > 0) {
                continue; // 正在播的当前源：永不淘汰
            }
            if (!includePrefetch && candidate.isPrefetch()) {
                continue; // 第一轮：保护刚预取的下一首源
            }
            candidate.close();
            it.remove();
            if (sources.size() < MAX_SOURCES) {
                break;
            }
        }
    }

    /** 新建源之后清理空闲者：最多保留 MAX_IDLE_SOURCES 个最新的空闲源（供窗口内 seek 秒开复用） */
    private void purgeIdleSources(BufferedHttpSource keep) {
        // 找出最新的空闲源予以保留（预取源单列保护，不参与「最新空闲」竞选，也不被淘汰）
        BufferedHttpSource newestIdle = null;
        for (BufferedHttpSource candidate : sources.values()) {
            if (candidate == keep || candidate.isClosed() || candidate.getRefCount() > 0
                    || candidate.isPrefetch()) {
                continue;
            }
            if (newestIdle == null || candidate.getLastUsedAtMs() > newestIdle.getLastUsedAtMs()) {
                newestIdle = candidate;
            }
        }
        int idleKept = 0;
        Iterator<Map.Entry<String, BufferedHttpSource>> it = sources.entrySet().iterator();
        while (it.hasNext()) {
            BufferedHttpSource candidate = it.next().getValue();
            boolean isKeptNewest = candidate == newestIdle && idleKept == 0;
            // 预取源（无读者但正在预热下一首）不在此淘汰：它是「刚预取的下一首」，
            // 由 evictIdleForNewSource 的最后手段或 abortPrefetch 显式作废（2026-09-12 缓冲/预取）
            if (candidate != keep && !candidate.isClosed() && candidate.getRefCount() <= 0
                    && !candidate.isPrefetch()) {
                if (isKeptNewest) {
                    idleKept++;
                    continue;
                }
                candidate.close();
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  HTTP 解析
    // ------------------------------------------------------------------ //

    private static final class HttpRequest {
        String rawQuery = "";
        String rangeHeader = null;
    }

    /** 读取并解析请求行 + 头部；连接断开 / 超时返回 null */
    private static HttpRequest readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.length() == 0) {
            return null;
        }
        // 仅支持 GET；行形如 "GET /stream?u=xxx HTTP/1.1"
        if (!requestLine.startsWith("GET ")) {
            throw new IOException("unsupported request: " + requestLine);
        }
        String target = requestLine.split(" ")[1];
        int qm = target.indexOf('?');
        HttpRequest request = new HttpRequest();
        if (qm >= 0) {
            request.rawQuery = target.substring(qm + 1);
        }
        while (true) {
            String line = readLine(in);
            if (line == null) {
                return null;
            }
            if (line.length() == 0) {
                break; // 头部结束
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("Range".equalsIgnoreCase(name)) {
                request.rangeHeader = value;
            }
        }
        return request;
    }

    /** 读一行（\n 结尾，容忍 \r\n）；连接关闭返回 null；超长防御性截断 */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(80);
        while (true) {
            int c = in.read();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            if (sb.length() < 8192) {
                sb.append((char) c);
            }
        }
    }

    /**
     * 解析 {@code Range: bytes=N-...} 的起始位置。
     * 无 Range / 不支持的形态（如后缀范围 bytes=-N）返回 -1。
     */
    private static long parseRangeStart(String rangeHeader) {
        if (rangeHeader == null) {
            return -1L;
        }
        if (!rangeHeader.startsWith("bytes=")) {
            return -1L;
        }
        String spec = rangeHeader.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash <= 0) {
            return -1L; // 缺起始位置（含后缀范围），不支持
        }
        try {
            return Long.parseLong(spec.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** 从 rawQuery 里取指定参数（已 URL 解码）；不存在返回 null */
    private static String extractQueryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.length() == 0) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (name.equals(key)) {
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return URLDecoder.decode(value, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
