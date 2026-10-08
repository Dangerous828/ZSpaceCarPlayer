package com.ktools.zspacecarplayer.player.stream;

import android.os.SystemClock;
import android.util.Log;

import com.ktools.zspacecarplayer.crash.CrashMonitor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * 默认窗口容量：8MB ≈ 1.5Mbps 下约 42s、320kbps 下约 200s 的抗抖动余量。
     * 2026-09-08 实车反馈「网络正常仍 2s 抖一下」——若供给端（Jellyfin/公网链路）
     * 存在应用层发送节奏顿挫, 2MB 窗口余量太薄会被打穿; 8MB 把分钟级顿挫也平滑掉。
     * 车机 RSS 实测 60~63MB, 多 6MB 数组在预算内（2026-09-03 从 8MB 缩到 2MB 是
     * 为了堆红线, 现实测堆余量充足, 恢复设计值）。
     */
    public static final int DEFAULT_CAPACITY_BYTES = 8 * 1024 * 1024;
    /** 单次网络读块大小 */
    private static final int CHUNK_SIZE = 64 * 1024;
    /** 连续网络失败的最大重试次数（指数退避） */
    private static final int MAX_RETRY = 5;
    /** 重试退避基数（ms），第 n 次失败退避 n * RETRY_BASE_MS */
    private static final long RETRY_BASE_MS = 800L;
    /** 网络无任何进展视为卡死的时限 */
    private static final long STALL_TIMEOUT_MS = 30_000L;
    /**
     * 涓流饥饿检测：滑动窗口长度与「窗口内读者等不到数据的累计时长」上限。
     *
     * 只靠 {@link #STALL_TIMEOUT_MS}（有无字节进展）判不出涓流——上游每秒挤几百字节也算
     * 进展，基准被不断刷新，read timeout 也因为每次 read 都在 15s 内返回而不触发；环形缓冲
     * 十几秒被抽干后，读者就在 bufEnd 上永久 wait，播放进度冻结而 UI 仍显示播放中。
     * 因此改成从读者视角判定：一个窗口内几乎全程挨饿即视为断流。
     */
    private static final long STARVE_WINDOW_MS = 20_000L;
    private static final long STARVE_LIMIT_MS = 18_000L;
    /** 连续饥饿判定次数上限：前几次只强制重连续传（窗口不丢），超过才升级 fatal 让上层报错 */
    private static final int MAX_STARVE_STALLS = 2;
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
    /**
     * 本流是否<b>曾经</b>判过死：{@code requestResetLocked()} 会把 {@link #fatalError} 清成 null
     * 实现自愈，但"判过死"这件事必须留下记录——上层要靠它区分真播完与假播完（2026-10-07）。
     * 与 {@code fatalError} 同锁保护，只随本流生灭，不随重定位/重连复位。
     */
    private boolean fatalLatched = false;
    /**
     * 从 socket 真实读到的累计字节数（跨重连累加，<b>不受环形窗口背压影响</b>）。
     *
     * <p>为什么必须单独有这一个计数器（2026-10-08 真车）：测速若拿 {@code bufEnd} 当"下载了多少"，
     * 窗口满的时候它只跟着读者走——8MB 环形窗一饱和，`est` 就等于播放消耗速率而不是链路速率，
     * 于是"健康链路"也必然测出 `est ≈ 所需速率 < 所需×1.15`，被判成持续缺口。当晚车主用同一条
     * 链路 10 秒内下完 3.1MB 的 OTA 包（>300KB/s），而我这边"测"出来的是 107KB/s——就是这个假口径。
     */
    private long socketBytes = 0L;
    /**
     * 本流<b>建立过多少次上游连接</b>与因此丢掉过多少字节 (2026-10-08)。
     *
     * <p>来头：同一首歌、同一时刻、同一条热点上做过对照——一条长连接取 5MB 是 246KB/s，
     * 同样 5MB 拆成 20 条独立请求只剩 <b>74KB/s</b>（单条首字节握手就要 1.15s，Jellyfin 每次
     * 还要重新定位文件）。也就是说"链路只有 60~90KB/s"这个说法很可能是我自己的取流形状造出来的，
     * 不是链路的事实。这两个计数器就是用来在车上把它判掉的：上报里 {@code conns} 一高，
     * 病因在连接复用；{@code conns} 一直是 1~2 而速率仍低，才轮到怀疑链路。
     */
    private long upstreamConnections = 0L;
    /** 因重定位/Range 被忽略而丢掉或白重下的字节数。 */
    private long discardedBytes = 0L;
    /** 最近一次上游请求的首字节耗时 (ms)；-1 = 还没有完成过请求。见 {@link #getTtfbStats()}。 */
    private long lastTtfbMs = -1L;
    private long ttfbMaxMs = 0L;
    private long ttfbSumMs = 0L;
    // ---- 磁盘缓存 (2026-10-08 T1 重审 A2) ----
    /** 由 {@link HttpProxyServer#configureDiskCache} 注入；为 null 就是完全不碰盘。 */
    private File diskCacheDir;
    private long diskCacheLimitBytes;
    private StreamDiskCache disk;

    /** ≥0 表示本轮被服务端判为"资源已不存在"，读侧要抛可识别的类型而不是普通 IO 异常。 */
    private volatile int goneHttpStatus = -1;

    // ---- 下载线程控制（lock 保护，downloadAbort/volatile 供读循环快速检查） ----
    private Thread downloaderThread;
    private volatile boolean downloadAbort = false;
    private volatile boolean closed = false;
    private long downloadEpoch = 0L;     // 每次 reset +1，拒绝旧连接迟到写入
    /** 最后一次「下载侧应取得进展」的时间基准（卡死检测）。-1 = 尚未武装。
     *  构造 / 拉起下载线程 / reset 时即武装，否则冷启动弱网下（首字节还没进窗口）
     *  检测器永不触发，readAt 会无限 wait 下去。 */
    private long lastProgressAtMs = -1L;

    // ---- 涓流饥饿检测（lock 保护） ----
    /** 当前滑动窗口起点；-1 = 未武装（无读者在等数据） */
    private long starveWindowStartMs = -1L;
    /** 本窗口内已结算（读者拿到过数据）的挨饿时长 */
    private long starvedAccumMs = 0L;
    /** 当前这段连续挨饿的起点；-1 = 此刻读者有数据可读 */
    private long starvedSinceMs = -1L;
    /** 连续判定为饥饿的次数；某个窗口喂得上就清零 */
    private int starveStallCount = 0;
    /** 不可续传流上忽略挨饿判定的留痕只写一次，免得每 20s 刷一行 (2026-10-07)。 */
    private boolean starveSuppressionLogged = false;

    // ---- 当前活动下载连接 (用于 abort/reset 时打断阻塞的底层 read) ----
    private HttpURLConnection activeConn = null;
    private InputStream activeIn = null;

    // ---- 代理服务侧引用计数 ----
    private int refCount = 0;
    private long lastUsedAtMs = 0L;
    /** 是否曾有读者登记过：区分「从未被读（需预取元数据）」和「读者已全部离开（应休眠）」 */
    private boolean everHadReader = false;
    /** 读者全部摘窗的时刻 (elapsedRealtime)；-1 = 还有读者挂着。见 {@link #IDLE_FILL_GRACE_MS}。 */
    private long idleSinceMs = -1L;

    // ---- 预取模式（2026-09-12 缓冲/预取，lock 保护）----
    /**
     * 预取源标志：为「下一首」预热的小窗源。true 时下载线程只把窗口填到
     * {@link #prefetchTargetBytes} 就休眠，绝不 free-slide 继续拉完整首去和当前曲抢带宽；
     * 一旦真读者（真正播放这首）登记进来即清零，升级为普通整窗源。
     */
    private boolean prefetchMode = false;
    /** 预取模式下的填充目标字节数（= 小窗容量）；非预取为 Long.MAX_VALUE（不限制） */
    private long prefetchTargetBytes = Long.MAX_VALUE;

    /** 无读者休眠判定：曾有过读者且现在全部离开。构建初期的元数据预取不受此门控。 */
    private boolean idleNoReaders() {
        return everHadReader && readPosMap.isEmpty();
    }

    /**
     * 读者全部摘窗之后，下载线程还允许继续填多久才收手 (2026-10-08，A1)。
     *
     * <p>8 秒的来头：一次上游请求的首字节实测 0.8~1.4 秒，而解码器在跳读/换连接时离开窗口的
     * 时长就是这个量级；给它一个宽限期，等于用"继续在同一条连接上收字节"换掉"再建一条连接再等
     * 一秒"。再长就是真的没人要这些数据了（切歌/放弃），该让出带宽和唯一的解码线程。
     */
    static final long IDLE_FILL_GRACE_MS = StreamTuning.DEFAULT_IDLE_FILL_GRACE_MS;

    /**
     * 纯判定：没有读者挂窗时，下载线程该继续把环形窗填着，还是收手让出连接。
     *
     * <p>旧行为是"读者一摘窗就 {@code return}"，下载线程随即退出并把上游连接
     * {@code disconnect()} 掉；读者一回来就要重连 + 重付首字节。实车的无 seektable FLAC 断点
     * 恢复会跳读约 9 次（{@code docs/v3_dev_plan_20260907.md:249}：9 次 {@code reset download}
     * 挤在约 9 秒内、位置只在 270KB 范围内来回），每一次都踩这条——把一条本来能跑到
     * 797KB/s 的长连接切成 9 条 133KB/s 的短连接。
     *
     * <p>三个必须同时成立的条件，缺一条就收手：
     * ① {@code held}——还有人持有这条流（{@code refCount>0}）。这是防滑坡的关键：切歌时
     *    {@code HttpSourceReader.close()} 会 release，旧源因此立刻停，不会在后台继续吃
     *    新歌的带宽；
     * ② {@code ringHasRoom}——窗没满。满了再读只会让 {@code writeToRing} 背压阻塞，
     *    既不省时间也没地方放，读者回来时由 {@code addReadPos} 重新拉起即可；
     * ③ 摘窗时长还在宽限内。
     */
    static boolean shouldKeepFillingWhileIdle(long nowMs, long idleSinceMs, boolean ringHasRoom,
                                              boolean held, long graceMs) {
        if (!held || idleSinceMs < 0L || !ringHasRoom || graceMs <= 0L) {
            return false;
        }
        long idleMs = nowMs - idleSinceMs;
        return idleMs >= 0L && idleMs < graceMs;
    }

    /** 预取已达标：预取模式、尚无读者接管、窗口已填到目标。此时下载线程应休眠让出带宽。 */
    private boolean prefetchSatisfiedLocked() {
        return prefetchMode && readPosMap.isEmpty() && (bufEnd - bufStart) >= prefetchTargetBytes;
    }

    public BufferedHttpSource(String url) {
        this(url, DEFAULT_CAPACITY_BYTES, 0L, false);
    }

    public BufferedHttpSource(String url, long initialPosition) {
        this(url, DEFAULT_CAPACITY_BYTES, initialPosition, false);
    }

    public BufferedHttpSource(String url, int capacityBytes) {
        this(url, capacityBytes, 0L, false);
    }

    public BufferedHttpSource(String url, int capacityBytes, long initialPosition) {
        this(url, capacityBytes, initialPosition, false);
    }

    /**
     * @param prefetch true = 建为「下一首」预取源：预取期只 eager 下载到
     *                 {@code prefetchTargetBytes}（= capacity）即休眠，不 free-slide 抢带宽，
     *                 且被登记为淘汰保护（见 HttpProxyServer 的 LRU 淘汰）。真读者接管后自动
     *                 升级为普通整窗源。
     */
    public BufferedHttpSource(String url, int capacityBytes, long initialPosition, boolean prefetch) {
        this(url, capacityBytes, initialPosition, prefetch,
                prefetch ? capacityBytes : Long.MAX_VALUE);
    }

    /**
     * @param prefetch                true = 建为「下一首」预取源（淘汰保护 + eager 下载限流）。
     * @param prefetchEagerTargetBytes 预取期 eager 下载的目标字节数：填到这么多就让下载线程休眠，
     *                                 绝不 free-slide 把整首下完去和当前曲抢带宽。
     *                                 <b>与 {@code capacityBytes} 解耦是关键</b>（2026-09-12 修正）：
     *                                 环形数组容量 {@code capacity} 是 final，无法在被读者接管后
     *                                 再扩大。若预取源用小 capacity（如 2MB），一旦它晋升为正在播的
     *                                 当前曲，整首就只能在 2MB 窗口上滚动——正是当初 2MB「被打穿」
     *                                 才升到 8MB 的那个卡顿。所以预取源要按<b>整窗 8MB 分配</b>，
     *                                 只把 <b>eager 下载目标</b>压到小值（如 2MB）来省带宽；读者接管、
     *                                 prefetchMode 清零后，下载线程会继续把窗口填到完整 8MB 余量。
     */
    public BufferedHttpSource(String url, int capacityBytes, long initialPosition, boolean prefetch,
                              long prefetchEagerTargetBytes) {
        this.url = url;
        this.capacity = Math.max(CHUNK_SIZE * 2, capacityBytes);
        this.ring = new byte[this.capacity];
        long initPos = Math.max(0L, initialPosition);
        synchronized (lock) {
            this.bufStart = initPos;
            this.bufEnd = initPos;
            this.lastProgressAtMs = SystemClock.elapsedRealtime();
            this.prefetchMode = prefetch;
            this.prefetchTargetBytes = prefetch
                    ? Math.min(Math.max(CHUNK_SIZE, prefetchEagerTargetBytes), this.capacity)
                    : Long.MAX_VALUE;
        }
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
        return readAt(position, dest, destOffset, length, null);
    }

    /**
     * 阻塞读取指定绝对位置的数据，支持外部取消。
     *
     * @param cancel 置位后本调用立即抛出 IOException，用于原生解码器 close 时
     *               解开卡在等网络供数上的解码线程
     * @return 实际读取字节数；数据流结束返回 -1
     * @throws IOException 源已关闭 / 下载侧 fatal / 已取消 / 等待被打断
     */
    public int readAt(long position, byte[] dest, int destOffset, int length,
                      AtomicBoolean cancel) throws IOException {
        if (dest == null || length <= 0) {
            return 0;
        }
        synchronized (lock) {
            while (true) {
                if (cancel != null && cancel.get()) {
                    throw new IOException("readAt cancelled");
                }
                if (goneHttpStatus >= 0) {
                    // 抛可识别的类型：读侧上层据此跳过系统 MediaCodec 那一轮无望的尝试
                    throw new ResourceGoneException(goneHttpStatus);
                }
                if (fatalError != null) {
                    throw new IOException("buffered source error: " + fatalError);
                }
                if (closed) {
                    throw new IOException("buffered source closed");
                }
                int placement = readPlacementAction(position, bufStart, bufEnd, eof,
                        StreamTuning.forwardGapWaitEnabled());
                if (placement == PLACE_IN_WINDOW) {
                    int off = (int) (position % capacity);
                    int avail = (int) (bufEnd - position);
                    int n = Math.min(length, Math.min(avail, capacity - off));
                    System.arraycopy(ring, off, dest, destOffset, n);
                    noteReaderFedLocked(SystemClock.elapsedRealtime());
                    return n;
                }
                if (placement == PLACE_EOF) {
                    return -1;
                }
                if (placement == PLACE_RESET) {
                    // 窗外优先查盘 (A2)：回拖、重播同一首、以及 FLAC 跳读回落到的老位置，
                    // 只要在盘上就地给出去，一条上游连接都不建——省掉的正是那 0.8~1.4 秒。
                    // 只服务"区间表确认连续"的范围，跨洞一律不读。
                    if (disk != null && disk.has(position, length)) {
                        int fromDisk = disk.readInto(position, dest, destOffset, length);
                        if (fromDisk > 0) {
                            return fromDisk;
                        }
                    }
                    // 落在窗口之外（真重定位 / 回读越过已回收区 / 前向缺口过大）：重定位下载
                    requestResetLocked(position);
                    continue;
                }
                // PLACE_WAIT：position == bufEnd，或前向只差一点——顺序下载马上就会送到，
                // 为它清窗重连等于白付一次首字节（0.8~1.4s）还作废已下尾部
                long now = SystemClock.elapsedRealtime();
                if (lastProgressAtMs >= 0 && now - lastProgressAtMs > STALL_TIMEOUT_MS) {
                    if (downloaderThread == null || !downloaderThread.isAlive()) {
                        fatalError = "downloader dead with no progress";
                    } else {
                        fatalError = "network stall over " + STALL_TIMEOUT_MS + "ms";
                        abortActiveConnectionLocked(); // 强行打断阻塞在 recvfrom 的连接，促发重试续传
                    }
                    Log.e(TAG, "readAt stall: url=" + url + " pos=" + position);
                    CrashMonitor.breadcrumb("stream", "readAt stall fatal='" + fatalError
                            + "' pos=" + position + " url=" + url);
                    lock.notifyAll();
                    continue;
                }
                noteReaderStarvingLocked(now);
                if (handleStarvationLocked(now, position)) {
                    continue; // 已强制重连或升级 fatal：重新判定（可能已有数据或直接抛出）
                }
                try {
                    lock.wait(300);
                } catch (InterruptedException e) {
                    throw new IOException("readAt interrupted");
                }
            }
        }
    }

    /**
     * 读请求落点判定 (2026-10-08，A1)。
     *
     * <p>旧判定只有三档：窗内就地读、{@code ==bufEnd} 等下载、其余（含<b>只差一点的
     * 前向位置</b>）一律清窗重连。差一点的那种情况，顺序下载本来下一秒就会把字节送到，
     * 清窗重连却要重新付一次首字节（实测 0.8~1.4 秒）并把已下载的尾部作废。
     * 现在把"前向缺口 <= {@link #FORWARD_GAP_WAIT_MAX_BYTES}"单独判成等待。
     *
     * <p>纯函数：JVM 单测里 {@code SystemClock} 恒为 0，位置判定不依赖时钟，正好可测。
     */
    static final int PLACE_IN_WINDOW = 0;
    static final int PLACE_WAIT = 1;
    static final int PLACE_RESET = 2;
    static final int PLACE_EOF = 3;
    /** 前向缺口在这么多个字节内时等下载追上，而不是重连（约 1 秒可补上）。 */
    static final long FORWARD_GAP_WAIT_MAX_BYTES = 256L * 1024L;

    static int readPlacementAction(long position, long bufStart, long bufEnd, boolean eof,
                                   boolean forwardGapWait) {
        if (position >= bufStart && position < bufEnd) {
            return PLACE_IN_WINDOW;
        }
        if (eof && position >= bufEnd) {
            return PLACE_EOF;
        }
        if (position == bufEnd) {
            return PLACE_WAIT;
        }
        if (forwardGapWait && position > bufEnd
                && position - bufEnd <= FORWARD_GAP_WAIT_MAX_BYTES) {
            return PLACE_WAIT;
        }
        return PLACE_RESET;
    }

    /** 读者拿到了数据：结算这段挨饿时长，并在窗口未武装时武装它 */
    private void noteReaderFedLocked(long now) {
        if (starvedSinceMs >= 0) {
            starvedAccumMs += now - starvedSinceMs;
            starvedSinceMs = -1L;
        }
        if (starveWindowStartMs < 0) {
            starveWindowStartMs = now;
        }
    }

    /** 读者停在 bufEnd 等下载推进：开始（或继续）计挨饿 */
    private void noteReaderStarvingLocked(long now) {
        if (starveWindowStartMs < 0) {
            starveWindowStartMs = now;
            starvedAccumMs = 0L;
            starvedSinceMs = now;
        } else if (starvedSinceMs < 0) {
            starvedSinceMs = now;
        }
    }

    /**
     * 滑动窗口结算：窗口内读者挨饿时长超过 {@link #STARVE_LIMIT_MS} 即判定涓流断流。
     *
     * 前 {@link #MAX_STARVE_STALLS} 次只强制换连接（epoch +1 让 downloadLoop 从 bufEnd
     * 带退避续传，已缓冲的窗口内容不丢），给弱网一次自愈机会；仍救不回来才置 fatalError，
     * 让读者的 readAt 抛出，由上层如实报错重试——绝不允许无限静默等待。
     *
     * <p><b>但这条只适用于能用 Range 续传的流。</b>不可续传时（chunked 转码流）重连不是自愈而是
     * 自伤：服务端从 0 重转、客户端把已下的整段丢掉再拉一遍，2026-10-07 真车的四条面包屑就是
     * {@code starve stall 1/2 → 2/2（两次 bufEnd 一模一样 = 零字节）→ starve FATAL}，随后
     * {@code MediaCodec reached end of audio stream} 把一首 219,493ms 的歌在 65,802ms 处腰斩。
     * 这种流的"连接真死了"由 {@code readAt} 的 {@link #STALL_TIMEOUT_MS} 无进展判定负责——它读的
     * 是下载侧的字节推进，那才是本流唯一可信的信号。
     *
     * @return true 表示已采取动作，调用方须重新走一遍 readAt 判定
     */
    private boolean handleStarvationLocked(long now, long position) {
        boolean windowRolled = starveWindowStartMs >= 0
                && now - starveWindowStartMs >= STARVE_WINDOW_MS;
        int verdict = starveVerdict(starveWindowStartMs, starvedAccumMs, starvedSinceMs,
                starveStallCount, now);
        if (verdict != STARVE_OK
                && !starvationReconnectUseful(remoteAcceptsRanges, contentLength)) {
            rollStarveWindowLocked(now);
            if (!starveSuppressionLogged) {
                starveSuppressionLogged = true;
                CrashMonitor.breadcrumb("stream", "starve ignored: source not resumable"
                        + " (chunked, no Range) pos=" + position + " url=" + url);
            }
            return false;
        }
        if (verdict == STARVE_OK) {
            if (windowRolled) {
                rollStarveWindowLocked(now);
                starveStallCount = 0; // 本窗口喂得上：健康
            }
            return false;
        }
        rollStarveWindowLocked(now);
        starveStallCount++;
        if (verdict == STARVE_FATAL) {
            fatalError = "stream starved: reader idle over " + STARVE_LIMIT_MS + "ms/"
                    + STARVE_WINDOW_MS + "ms for " + starveStallCount + " windows";
            Log.e(TAG, "starve fatal: " + fatalError + " pos=" + position + " url=" + url);
            CrashMonitor.breadcrumb("stream", "starve FATAL '" + fatalError
                    + "' pos=" + position + " url=" + url);
            abortActiveConnectionLocked();
            lock.notifyAll();
            return true;
        }
        Log.w(TAG, "starve stall " + starveStallCount + "/" + MAX_STARVE_STALLS
                + ": reader idle over " + STARVE_LIMIT_MS + "ms/" + STARVE_WINDOW_MS
                + "ms, reconnecting from " + bufEnd + " url=" + url);
        CrashMonitor.breadcrumb("stream", "starve stall " + starveStallCount + "/"
                + MAX_STARVE_STALLS + " reconnect from bufEnd=" + bufEnd + " url=" + url);
        downloadEpoch++;
        downloadAbort = true;
        abortActiveConnectionLocked();
        // 重新武装无进展基准：否则旧基准会让下一次循环立刻误判成 30s 断流 fatal
        lastProgressAtMs = now;
        lock.notifyAll();
        ensureDownloaderLocked();
        return true;
    }

    /** 窗口滚动：仍在挨饿的话，新窗口从此刻重新计 */
    private void rollStarveWindowLocked(long now) {
        starveWindowStartMs = now;
        starvedAccumMs = 0L;
        starvedSinceMs = starvedSinceMs >= 0 ? now : -1L;
    }

    /** 读者全部离开：解除饥饿检测武装，避免下次有人来读时把整段空闲算成挨饿 */
    private void disarmStarveLocked() {
        starveWindowStartMs = -1L;
        starvedAccumMs = 0L;
        starvedSinceMs = -1L;
        starveStallCount = 0;
        starveSuppressionLogged = false;
    }

    static final int STARVE_OK = 0;
    static final int STARVE_RECONNECT = 1;
    static final int STARVE_FATAL = 2;

    /**
     * 读者挨饿时，换连接这件事有没有意义：服务端明确回过 206（{@code remoteAcceptsRanges}），
     * 或资源总长已知（那就是能按字节寻址的原件直传），才有意义。
     *
     * <p>两者皆无 = chunked 转码流。实测（2026-10-07，直接拿设备上报里的 URL 打服务端）
     * {@code stream.mp3?...static=false&maxStreamingBitrate=} 与 {@code stream.flac?...static=false
     * &audioCodec=flac} 都是 {@code Transfer-Encoding: chunked} + {@code Accept-Ranges: none}，
     * 带 {@code Range: bytes=1000000-} 重发仍是 200 且首字节回到文件开头。
     */
    static boolean starvationReconnectUseful(boolean remoteAcceptsRanges, long contentLength) {
        return remoteAcceptsRanges || contentLength > 0L;
    }

    /**
     * 下载线程<b>因异常</b>而按字节偏移重连时，这次重连值不值 (2026-10-08)。
     *
     * <p>真车当晚的形态：{@code download retry 1/5 from 1178858: SocketTimeoutException} →
     * {@code remote ignored Range, skipping 1178858 bytes}。转码流是 chunked 且不认 Range，
     * 所以"从 bufEnd 续传"实际是<b>从 0 重下再丢掉 1.18MB</b>；按当晚 70KB/s 的链路，一次重连
     * 白烧 17 秒，五条退避全跑完约 85 秒死寂，然后才轮到上层的 starve 判死。播放侧这期间
     * 一直在抽干，位置恒为 0 —— 最后被判成"坏断点"从零重播，重播又走同一条路。
     *
     * <p>所以这条路上"重试"越守规矩越糟。可续传（回过 206 或总长已知）时照旧重试；只有
     * 确认过响应头、既不接受 Range 又报不出总长时，重下的代价才需要被计量：窗口里还没
     * 几个字节时（起播初期）从 0 重连代价可接受，仍按旧行为重试；已经下了 {@link
     * #NON_RESUMABLE_RETRY_MAX_BYTES} 以上就直接判死，把决定权交回上层（那里只重试一次，
     * 且不清续播点）。
     */
    static final int NON_RESUMABLE_RETRY_MAX_BYTES = 512 * 1024;

    static boolean midFlightRetryUseful(boolean metaReady, boolean remoteAcceptsRanges,
                                        long contentLength, long bufEnd) {
        if (remoteAcceptsRanges || contentLength > 0L) {
            return true;
        }
        if (!metaReady) {
            return true; // 连响应头都没拿到，可能是纯建连失败，与可续传无关
        }
        return bufEnd < NON_RESUMABLE_RETRY_MAX_BYTES;
    }

    /**
     * 纯判定：本窗口是否已滚动，以及滚动后读者挨饿到什么程度该做什么。
     *
     * 抽成静态纯函数是因为 JVM 单测里 {@code SystemClock.elapsedRealtime()} 恒为 0，
     * 带真实时钟的实例方法在单测中永远推进不了窗口（同 PlaybackStateMachine.streamRetryAction）。
     *
     * @param windowStartMs  当前窗口起点；&lt;0 = 未武装
     * @param accumMs        窗口内已结算的挨饿时长
     * @param starvedSinceMs 当前这段连续挨饿的起点；&lt;0 = 此刻有数据可读
     * @param stallCount     此前连续判定为饥饿的次数
     * @param nowMs          当前时刻
     */
    static int starveVerdict(long windowStartMs, long accumMs, long starvedSinceMs,
                             int stallCount, long nowMs) {
        if (windowStartMs < 0 || nowMs - windowStartMs < STARVE_WINDOW_MS) {
            return STARVE_OK;
        }
        long starved = accumMs + (starvedSinceMs >= 0 ? nowMs - starvedSinceMs : 0L);
        if (starved < STARVE_LIMIT_MS) {
            return STARVE_OK;
        }
        return (stallCount + 1 > MAX_STARVE_STALLS) ? STARVE_FATAL : STARVE_RECONNECT;
    }

    /** 登记一个读方（连接）的起始位置。token 由调用方保证唯一。 */
    public void addReadPos(Object token, long pos) {
        synchronized (lock) {
            readPosMap.put(token, pos);
            everHadReader = true;
            idleSinceMs = -1L;
            // 预取源被真读者接管（这首真的要播了）：解除预取限制，升级为普通整窗源，
            // 下载线程恢复「随读者推进持续填充」，不再受小目标封顶（2026-09-12 缓冲/预取）
            if (prefetchMode) {
                prefetchMode = false;
                prefetchTargetBytes = Long.MAX_VALUE;
                Log.i(TAG, "prefetch source taken over by reader, promote to full window: " + url);
            }
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

    /** 读者强制重定位（原生解码器回溯 seek）：updateReadPos 只进不退，回溯必须能落回小位。 */
    public void seekReadPos(Object token, long pos) {
        synchronized (lock) {
            if (readPosMap.containsKey(token)) {
                readPosMap.put(token, Math.max(0L, pos));
            }
            lock.notifyAll();
        }
    }

    /** 读方（连接）结束，注销。 */
    public void removeReadPos(Object token) {
        synchronized (lock) {
            readPosMap.remove(token);
            if (readPosMap.isEmpty()) {
                idleSinceMs = SystemClock.elapsedRealtime();
                disarmStarveLocked();
            }
            lock.notifyAll();
        }
    }

    /**
     * 唤醒所有阻塞在 {@link #readAt} 等待循环中的读者。
     * 原生解码器 close 路径靠它解开卡在等网络供数上的解码线程（配合读者自身的
     * cancel 标志，被唤醒后会立即抛出而不是继续 wait）。
     */
    public void cancelWaiters() {
        synchronized (lock) {
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

    /**
     * 本流累计已下载到的字节偏移（单调递增；重连续传也继续往上加）。
     * 与 {@link #getBufferedBytes()} 的区别是它不被窗口淘汰影响，因此可以直接用于速率采样。
     */
    public long getDownloadedBytes() {
        synchronized (lock) {
            return bufEnd;
        }
    }

    /** 链路真实交付的累计字节数（不受窗口背压影响），测速只能用它。 */
    public long getSocketBytes() {
        synchronized (lock) {
            return socketBytes;
        }
    }

    /** 本流建过多少次上游连接 (2026-10-08，用来判"链路慢"还是"我自己反复重连")。 */
    public long getUpstreamConnections() {
        synchronized (lock) {
            return upstreamConnections;
        }
    }

    /** 因重定位或 Range 被忽略而作废/白重下的字节数。 */
    /** 挂上磁盘缓存（可选）。必须在开始下载之前调用。 */
    void enableDiskCache(File dir, long limitBytes) {
        synchronized (lock) {
            this.diskCacheDir = dir;
            this.diskCacheLimitBytes = limitBytes;
        }
    }

    /** 本条流已从盘上喂出去多少字节（=0 就说明缓存一次没命中，这是判命中率唯一的入口）。 */
    public long getDiskServedBytes() {
        synchronized (lock) {
            return disk == null ? 0L : disk.getServedBytes();
        }
    }

    /** 本条流已落盘多少字节。 */
    public long getDiskStoredBytes() {
        synchronized (lock) {
            return disk == null ? 0L : disk.getStoredBytes();
        }
    }

    public long getDiscardedBytes() {
        synchronized (lock) {
            return discardedBytes;
        }
    }

    /**
     * 上游请求的首字节耗时统计：{@code long[]{最近, 最大, 平均}}；没完成过请求时全为 -1/0。
     * 连接数之所以要配着它一起看，是因为 2026-10-08 的对照实测表明<b>每条请求都要付 0.8~1.4s
     * 的固定开销</b>（与 keep-alive 无关），"这首歌建了几次连接"因此直接等于"白等了几秒"。
     */
    public long[] getTtfbStats() {
        synchronized (lock) {
            if (upstreamConnections <= 0L) {
                return new long[]{-1L, 0L, 0L};
            }
            return new long[]{lastTtfbMs, ttfbMaxMs, ttfbSumMs / upstreamConnections};
        }
    }

    /**
     * 环形窗口是否还有余量收新字节。饱和时下载被读者拽着走（背压），
     * 此时<b>任何速率样本都不代表链路能力</b>，测速必须让路。
     */
    public boolean ringHasRoom() {
        synchronized (lock) {
            return (bufEnd - bufStart) < capacity - (capacity / 10);
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

    /** 窗口容量（字节）。prefill 门槛按容量比例算目标时需要（2026-09-12 缓冲/预取）。 */
    public int getCapacityBytes() {
        return capacity;
    }

    /** 是否为仍在预热、尚无读者接管的预取源（淘汰保护 / 主动作废判定用）。 */
    public boolean isPrefetch() {
        synchronized (lock) {
            return prefetchMode;
        }
    }

    /**
     * 当前是否处于「饥饿 / 断流」：已进 fatal，或此刻有读者停在窗口尾等下载推进。
     * 预取让位判定用它——当前曲一旦饥饿就立即暂停 / 放弃下一首预取（2026-09-12 缓冲/预取）。
     */
    public boolean isStarving() {
        synchronized (lock) {
            return fatalError != null || starvedSinceMs >= 0;
        }
    }

    /**
     * 下载侧是否已进入不可恢复错误（断流 / 重试耗尽 / 涓流饥饿）。
     * 此时 readAt 只会抛 IOException，唯有重定位（reset）能清掉；注册表据此回收死源。
     */
    public boolean hasFatalError() {
        synchronized (lock) {
            return fatalError != null;
        }
    }

    /**
     * 本流此刻判死，<b>或曾经判过死</b>。服务层区分真播完与假播完要用这条，不能用
     * {@link #hasFatalError()}：重定位（{@code requestResetLocked}）会为了自愈把实时标志清掉，
     * 而真车当晚恰恰是"判死→重连续下 2MB→假 EOS"，只看实时标志就漏判。
     */
    public boolean hasLatchedFatal() {
        synchronized (lock) {
            return fatalLatched || fatalError != null;
        }
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
            abortActiveConnectionLocked();
            lock.notifyAll();
        }
        Thread t = downloaderThread;
        if (t != null) {
            t.interrupt();
        }
        StreamDiskCache cacheToClose;
        synchronized (lock) {
            cacheToClose = disk;
            disk = null;
        }
        if (cacheToClose != null) {
            cacheToClose.close(); // 同步落盘 + 命中过就把 mtime 顶新（LRU 才不会先删常听的歌）
        }
        Log.i(TAG, "closed: " + url);
    }

    private void abortActiveConnectionLocked() {
        if (activeIn != null) {
            try { activeIn.close(); } catch (Throwable ignored) {}
            activeIn = null;
        }
        if (activeConn != null) {
            final HttpURLConnection c = activeConn;
            activeConn = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { c.disconnect(); } catch (Throwable ignored) {}
                }
            }, "bufsrc-abort").start();
        }
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
        // 重新武装卡死基准：休眠期间 lastProgressAtMs 停在很久以前，不刷新会让
        // 新读者的第一次 readAt 被误判成「已断流 30s」而立刻进入 fatal
        lastProgressAtMs = SystemClock.elapsedRealtime();
        downloaderThread.start();
    }

    /**
     * 读方请求重定位：清窗、断开旧下载连接、从 position 重新下载。
     * 同时清除 fatal 错误实现自愈（上层看门狗重连即可恢复播放）。
     */
    private void requestResetLocked(long position) {
        // 重定位会把整个窗口清掉重下：新位置之后已经下好的字节因此作废。这个代价必须被记下来
        // ——2026-10-08 对照实测：一条长连接取 5MB 是 246KB/s，拆成 20 条只剩 74KB/s，
        // 所以"链路慢"很可能就是这类重连+重下的叠乘，而不是蜂窝不给量。
        if (bufEnd > position) {
            discardedBytes += bufEnd - position;
        }
        downloadEpoch++;
        downloadAbort = true;
        abortActiveConnectionLocked();
        eof = false;
        bufStart = position;
        bufEnd = position;
        // **先 latch 再清**：这一句就是 2026-10-07 那晚的取证关键。starve FATAL 之后流又重连
        // 续下了约 2MB（reader pos 2,213,217 → 4,437,358），等假 EOS 到达时 fatalError 早被这里
        // 抹平了——只看实时标志会漏判。闩锁只随本新生效，"这条流曾经判过死"从此不会被洗掉。
        if (fatalError != null) {
            fatalLatched = true;
        }
        fatalError = null;
        goneHttpStatus = -1;
        lastProgressAtMs = SystemClock.elapsedRealtime();
        disarmStarveLocked();
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
                        if (idleNoReaders() || prefetchSatisfiedLocked()) {
                            // 读者已全部离开，或预取小窗已填达标：下载线程休眠让出带宽，
                            // 新读者 addReadPos（接管）时再拉起（2026-09-12 缓冲/预取）
                            return;
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
                    if (e instanceof ResourceGoneException) {
                        // 一次都不重试：0.8+1.6+2.4+3.2+4.0s 的退避只把"这首没了"拖成约 90s 才跳歌
                        goneHttpStatus = ((ResourceGoneException) e).httpCode;
                        fatalError = "resource gone: HTTP " + goneHttpStatus;
                        Log.e(TAG, "fatal: " + fatalError + " url=" + url);
                        CrashMonitor.breadcrumb("stream", "resource gone terminal: " + fatalError
                                + " pos=" + bufEnd);
                        lock.notifyAll();
                        return;
                    }
                    retry++;
                    if (retry > MAX_RETRY) {
                        fatalError = "download failed after " + MAX_RETRY + " retries: " + e.getMessage();
                        Log.e(TAG, "fatal: " + fatalError + " url=" + url);
                        lock.notifyAll();
                        return;
                    }
                    if (!midFlightRetryUseful(metaReady, remoteAcceptsRanges, contentLength, bufEnd)) {
                        // chunked 不认 Range：按字节重连=从 0 重下再丢弃，代价已经落在 bufEnd 上
                        fatalError = "download failed on non-resumable stream at " + bufEnd
                                + "B (reconnect would re-fetch and discard it): " + e;
                        Log.e(TAG, "fatal: " + fatalError + " url=" + url);
                        CrashMonitor.breadcrumb("stream", "non-resumable abort pos=" + bufEnd
                                + " retry=" + retry + " err=" + e);
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
            // 每次<b>尝试</b>都计一次：旧口径是在 getInputStream() 之后才 +1，失败的与被打断的
            // 重连一律不计，于是"这首歌到底建了几条连接"恰好漏掉了最该看见的那部分 (2026-10-08)
            upstreamConnections++;
        }
        long attemptAtMs = SystemClock.elapsedRealtime();
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
            // 首字节耗时：这是"一条连接值不值"的那笔固定开销。2026-10-08 对照实测每条上游
            // 请求要 0.8~1.4s（与是否复用 keep-alive 无关），所以连接数本身就是吞吐上限
            long ttfbMs = SystemClock.elapsedRealtime() - attemptAtMs;
            synchronized (lock) {
                lastTtfbMs = ttfbMs;
                ttfbSumMs += ttfbMs;
                if (ttfbMs > ttfbMaxMs) {
                    ttfbMaxMs = ttfbMs;
                }
            }
            if (code < 200 || code >= 300) {
                // 404/410 的含义是"这个 Id 指向的资源已经没了"，重试同一个 Id 永远同样的结果，
                // 单独抛类型让重试环直接终态，而不是按网络抖动退避五轮。
                if (BufferingPolicy.isMissingResourceStatus(code)) {
                    throw new ResourceGoneException(code);
                }
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
            if (disk == null && diskCacheDir != null && StreamDiskCache.shouldCache(
                    contentLength, BufferingPolicy.DISK_CACHE_MAX_FILE_BYTES)) {
                // 只有总长已知才缓存：chunked 转码流长度进不了键、洞也判不出来
                disk = StreamDiskCache.open(diskCacheDir, url, contentLength,
                        BufferingPolicy.DISK_CACHE_MAX_FILE_BYTES, diskCacheLimitBytes);
                if (disk != null) {
                    Log.i(TAG, "disk cache attached: " + contentLength + "B " + url);
                }
            }
            in = c.getInputStream();
            if (wantRange && !partial) {
                // 远端不支持 Range：只能全量拉取并跳过前 start 字节（Jellyfin 支持 Range，正常走不到）
                Log.w(TAG, "remote ignored Range, skipping " + start + " bytes");
                synchronized (lock) {
                    discardedBytes += start;
                }
                skipFully(in, start);
            }
            conn = c;
            synchronized (lock) {
                if (downloadAbort || epoch != downloadEpoch || closed) {
                    return;
                }
                activeConn = c;
                activeIn = in;
            }
            pumpIntoRing(in, epoch);
        } finally {
            synchronized (lock) {
                if (activeConn == conn) {
                    activeConn = null;
                }
                if (activeIn == in) {
                    activeIn = null;
                }
            }
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
                if (idleNoReaders()
                        && !shouldKeepFillingWhileIdle(SystemClock.elapsedRealtime(),
                                idleSinceMs, (bufEnd - bufStart) < capacity - (capacity / 10),
                                refCount > 0, StreamTuning.idleFillGraceMs())) {
                    // 读者摘窗且过了宽限（或窗已满）：收手，避免分家后的源在后台空耗带宽。
                    // 宽限之内**继续在同一条连接上收字节填窗**——旧行为是一摘窗就退出并断连，
                    // 解码器跳读回来就得重连再付约 1 秒首字节，实车那 9 次跳读就是这么变成
                    // 9 条短连接的（见 IDLE_FILL_GRACE_MS）
                    return;
                }
                if (prefetchSatisfiedLocked()) {
                    // 预取小窗已填达标：停止拉流，绝不 free-slide 把整首下完去和当前曲抢带宽
                    return;
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
            // 先记账再写环：这个计数代表"链路真给了多少"，不能被环形窗口的背压改写。
            synchronized (lock) {
                socketBytes += n;
            }
            if (writeToRing(chunk, n, epoch) < 0) {
                return; // closed / aborted / epoch 过期
            }
            synchronized (lock) {
                if (epoch == downloadEpoch) {
                    lastProgressAtMs = SystemClock.elapsedRealtime();
                    logWatermarkLocked(epoch);
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
                if (idleNoReaders() && !shouldKeepFillingWhileIdle(
                        SystemClock.elapsedRealtime(), idleSinceMs, true, refCount > 0,
                        StreamTuning.idleFillGraceMs())) {
                    // 读者全部离开且宽限已过：放弃本次写入，线程随 downloadLoop 休眠。
                    // 必须和 pumpIntoRing 用<b>同一条判定</b>——否则那边刚放行继续填窗，
                    // 这里立刻把写入判死，摘窗宽限就成了空话 (2026-10-08 A1)
                    return -1;
                }
                long free = capacity - (bufEnd - bufStart);
                if (free >= n) {
                    break;
                }
                long minPos = minReadPosLocked();
                if (prefetchMode && minPos == Long.MAX_VALUE) {
                    // 预取模式且尚无读者接管：窗口填不下就停，绝不 free-slide 把整首下完
                    // （否则会持续和当前曲抢带宽，违背「预取只预热小窗」的设计）
                    return -1;
                }
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
            long wroteAt = bufEnd;
            int off = (int) (bufEnd % capacity);
            int first = Math.min(n, capacity - off);
            System.arraycopy(chunk, 0, ring, off, first);
            if (first < n) {
                System.arraycopy(chunk, first, ring, 0, n - first);
            }
            bufEnd += n;
            lock.notifyAll();
            if (disk != null) {
                // 边播边存 (A2)。位置取写入前的 bufEnd——这是这段字节在资源里的绝对起点，
                // 与环形窗怎么绕回无关。锁内落盘是为了和窗口推进保持同一份顺序；
                // 单次最多 64KB，闪存的毫秒级写入换一个"不会读脏数据"的保证，值。
                disk.record(wroteAt, chunk, 0, n);
            }
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

    // ---- 水位诊断日志（装车量化缓冲健康度的唯一入口） ----
    private long lastWatermarkLogAtMs = 0L;
    private long lastWatermarkBytes = 0L;

    /**
     * 每 5s 打一行窗口水位与供给速率。调用方已持 lock 且确认 epoch 当前。
     *
     * 判读：水位持续 <1MB → 供给不足（服务器发送节奏/带宽），抖动来自网络侧；
     * 水位贴满 capacity → 供给充足，若仍停顿则问题在消费侧（解码/AudioTrack）。
     * rate 单调偏低（<消费码率）同样指向供给不足。
     */
    private void logWatermarkLocked(long epoch) {
        long now = SystemClock.elapsedRealtime();
        if (lastWatermarkLogAtMs == 0L) {
            lastWatermarkLogAtMs = now;
            lastWatermarkBytes = bufEnd;
            return;
        }
        long elapsed = now - lastWatermarkLogAtMs;
        if (elapsed < 5000L) {
            return;
        }
        long buffered = bufEnd - bufStart;
        long rateKbps = (bufEnd - lastWatermarkBytes) * 8L / elapsed; // kbit/s
        Log.i(TAG, "watermark: buffered=" + (buffered / 1024) + "KB/" + (capacity / 1024)
                + "KB rate=" + rateKbps + "kbps eof=" + eof + " fatal=" + fatalError
                + " epoch=" + epoch);
        lastWatermarkLogAtMs = now;
        lastWatermarkBytes = bufEnd;
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

    /**
     * 服务端明确回答"资源不存在"（见 {@link BufferingPolicy#isMissingResourceStatus}）。
     * 它不是网络故障：换 Id 才有救，对同一个 Id 重连多少次结果都一样。
     */
    public static final class ResourceGoneException extends IOException {
        public final int httpCode;

        ResourceGoneException(int httpCode) {
            super("HTTP " + httpCode);
            this.httpCode = httpCode;
        }
    }
}
