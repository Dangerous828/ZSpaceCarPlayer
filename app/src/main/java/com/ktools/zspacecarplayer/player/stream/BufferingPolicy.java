package com.ktools.zspacecarplayer.player.stream;

/**
 * 缓冲 / 预取的纯判定策略（2026-09-12「缓冲/预取」）。
 *
 * 本类刻意不依赖任何 Android API，全部为静态纯函数 + 常量，风格对齐
 * {@code PlaybackStateMachine} / {@code GeelyAmpWakeStrategy}：把可回归的阈值判定从
 * 带真实时钟 / 线程的实例方法里抽出来，好在 host JVM 单测里钉死边界。
 *
 * 三件事的判据都收口在这里，避免阈值散落在播放器 / 代理 / 服务三处语义分叉：
 *  1) 起播预缓冲门槛（prefill gate）：出声前先建立领先量，消除开头几秒被抽干的卡顿；
 *  2) 下一首预取（next-song prefetch）：带宽防御式，仅在当前曲健康 / 接近结尾时预热小窗；
 *  3) 缓冲百分比上 UI：进入稳定播放即隐藏指示。
 */
public final class BufferingPolicy {

    private BufferingPolicy() {
    }

    // ------------------------------------------------------------------ //
    //  1) 起播 / 恢复预缓冲门槛 (prefill gate)
    // ------------------------------------------------------------------ //

    /** 门槛目标覆盖秒数：起播前至少缓冲这么多秒的音频，兼顾「秒开体感」与「抗抽干」。 */
    public static final int PREFILL_TARGET_SECONDS = 5;
    /** 门槛最大等待：弱网下即使没到门槛也起播，绝不永久卡住（超时仍会留日志）。 */
    public static final long PREFILL_MAX_WAIT_MS = 7000L;
    /** 门槛轮询间隔（运行在播放器解码后台线程，绝不阻塞主线程 / UI）。 */
    public static final long PREFILL_POLL_MS = 150L;
    /** 门槛期间缓冲 % 上报的最小间隔，避免刷屏抖动。 */
    public static final long PREFILL_REPORT_INTERVAL_MS = 400L;
    /** 目标字节占窗口容量的比例上限：不让门槛目标大到把整个窗口填满才起播。 */
    public static final double PREFILL_WINDOW_FRACTION = 0.12;
    /** 总长未知（getBufferedPercent=-1）时退化的纯字节门槛。 */
    public static final long PREFILL_MIN_BYTES_UNKNOWN_TOTAL = 768L * 1024L;
    /** 目标字节下限：超短曲 / 低码率下按秒算出的目标过小时兜底，保证有基本领先量。 */
    public static final long PREFILL_FLOOR_BYTES = 256L * 1024L;

    /**
     * 计算 prefill 门槛的目标字节数（字节 / 秒双判据）。
     *
     * 目标 ≈ min(可覆盖 {@link #PREFILL_TARGET_SECONDS} 的字节, 窗口的
     * {@link #PREFILL_WINDOW_FRACTION})；总长 / 时长未知时退化为纯字节门槛
     * {@link #PREFILL_MIN_BYTES_UNKNOWN_TOTAL}；再兜一个 {@link #PREFILL_FLOOR_BYTES} 下限。
     *
     * @param windowCapacityBytes 当前曲环形缓冲窗口容量（正常曲 8MB，预取小窗 2MB）
     * @param contentLength       远端资源总长；&lt;=0 表示未知
     * @param durationMs          曲目时长（ms）；&lt;=0 表示未知
     */
    public static long prefillTargetBytes(long windowCapacityBytes, long contentLength, long durationMs) {
        if (contentLength <= 0 || durationMs <= 0 || windowCapacityBytes <= 0) {
            // 码率无法估算：退化为纯字节门槛，够起播即可
            return PREFILL_MIN_BYTES_UNKNOWN_TOTAL;
        }
        double bytesPerSec = (double) contentLength / ((double) durationMs / 1000.0);
        long bytesForTarget = (long) (bytesPerSec * PREFILL_TARGET_SECONDS);
        long windowFraction = (long) ((double) windowCapacityBytes * PREFILL_WINDOW_FRACTION);
        long target = Math.min(bytesForTarget, windowFraction);
        return Math.max(target, PREFILL_FLOOR_BYTES);
    }

    /**
     * 是否已缓冲到足以起播（门槛判定）。
     *
     * @param bufferedBytes 当前已缓冲字节数（bufEnd-bufStart）
     * @param bufferedPercent 缓冲百分比；&lt;0 表示总长未知
     * @param totalKnown    总长是否已知
     * @param targetBytes   由 {@link #prefillTargetBytes} 算出的目标
     */
    public static boolean shouldPrefillStart(long bufferedBytes, int bufferedPercent,
                                             boolean totalKnown, long targetBytes) {
        if (bufferedBytes < 0) {
            return false; // 源尚未建立，继续等
        }
        if (!totalKnown || bufferedPercent < 0) {
            // 总长未知：纯字节门槛
            return bufferedBytes >= PREFILL_MIN_BYTES_UNKNOWN_TOTAL;
        }
        return bufferedBytes >= targetBytes;
    }

    /**
     * open 看门狗该不该掐掉读者。
     *
     * 旧判据是「墙上时间超过 15s 就 abort」，于是把**慢但一直在下载**的开流也一起掐死：
     * 8600 的 MediaCodec 没注册 FLAC/WAV，abort 之后回退 MediaExtractor 必然抛
     * `Failed to instantiate extractor`，重试耗尽就跳歌——把一条还能撑住的链路做成了静音。
     * 现按「环形缓冲有没有增长」判：有进展就一直等到硬上限；连续无进展满 stall 才收手。
     *
     * @param progressAdvanced 本轮相对上轮是否拿到了更多字节
     * @param msSinceProgress  距上一次出现增长过了多久
     * @param msSinceOpenStart open 一共进行了多久
     */
    public static boolean openShouldAbort(boolean progressAdvanced,
                                          long msSinceProgress,
                                          long msSinceOpenStart,
                                          boolean gotFirstByte) {
        if (msSinceOpenStart >= OPEN_HARD_CAP_MS) {
            return true;
        }
        if (!gotFirstByte) {
            // 连一个字节都没下来：这不是"下得慢"，是根本没通。再等 15s 只是让车主多听 15s 静音，
            // 而且这段时间占着唯一的解码线程，点下一首都排不上队。
            return msSinceOpenStart >= OPEN_FIRST_BYTE_MS;
        }
        return !progressAdvanced && msSinceProgress >= OPEN_STALL_MS;
    }

    /** open 看门狗轮询间隔：够密以免拖长判死，够稀以免白读环形缓冲游标。 */
    public static final long OPEN_POLL_MS = 500L;
    /** 已有首字节、但连续这么久环形缓冲零增长才掐——这才是「慢但活着 vs 真卡住」的分界。 */
    public static final long OPEN_STALL_MS = 15_000L;
    /**
     * 一个字节都没下来的耐心：远短于 {@link #OPEN_STALL_MS}。
     * 2026-10-06 真车实测到一种旧判据治不了的形态——一首只需 16 KB/s 的 128kbps MP3 也卡住，
     * 上报里是 {@code native open stalled no-progress=15004ms ... buffered=0B}，即 15 秒零字节。
     * 旧判据把"零字节"和"下得慢"用同一个 15s 处理，等于给最需要快速失败的形态最长的耐心。
     */
    public static final long OPEN_FIRST_BYTE_MS = 4_000L;
    /** 即便一直有进展也最多等这么久：解码线程只有一条，不能赌死。 */
    public static final long OPEN_HARD_CAP_MS = 45_000L;

    // ------------------------------------------------------------------ //
    //  2) 下一首预取 (next-song prefetch)
    // ------------------------------------------------------------------ //

    /**
     * 预取源的 <b>eager 下载目标</b>（不是环形数组容量）：预取期只把下一首预下载这么多就让
     * 下载线程休眠，绝不 free-slide 把整首下完去和当前曲抢带宽。环形数组本身仍按整窗 8MB 分配
     * （见 {@code BufferedHttpSource} 5 参构造 / {@code HttpProxyServer.prefetch} 的说明），
     * 这样预取源晋升为当前曲后仍有完整 8MB 抗抖余量，而非被 2MB 小窗「打穿」。
     */
    public static final int PREFETCH_CAPACITY_BYTES = 2 * 1024 * 1024;
    /**
     * 数据源上限：当前曲 8MB + 下一首预取 8MB（eager 只拉 2MB）= 最坏 16MB，与本项目历史
     * 已验证安全的堆占用持平（曾触碰堆红线，故不再上探到 3×8MB=24MB）。
     * 淘汰逻辑保证「正在播的当前源（有读者）」与「刚预取的下一首源」都不被淘汰；
     * 若当前曲自身 fork 出两个源占满表，预取会被跳过——这是尽力而为的优化，跳过不是回归。
     */
    public static final int MAX_SOURCES = 2;
    /** 触发预取的当前曲健康阈值：已下载百分比 ≥ 该值即视为健康。 */
    public static final int PREFETCH_HEALTHY_PERCENT = 40;
    /** 触发预取的当前曲健康阈值：领先秒数 ≥ 该值即视为健康（百分比未知时的替代判据）。 */
    public static final long PREFETCH_LEAD_SECONDS = 15L;
    /** 当前曲接近结尾阈值：剩余 ≤ 该秒数时无条件预取（马上要切歌）。 */
    public static final long PREFETCH_REMAINING_SECONDS = 15L;

    /** 预取源的小窗口容量（字节）。 */
    public static int prefetchCapacityBytes() {
        return PREFETCH_CAPACITY_BYTES;
    }

    /** 数据源上限（当前曲 + 预取 + slack）。 */
    public static int maxSources() {
        return MAX_SOURCES;
    }

    /**
     * 是否应触发下一首预取（带宽防御式）。
     *
     * 让位规则优先：当前曲饥饿 / 断流，或正处于起播 prefill 门槛期间，一律不预取
     * （当前曲永远优先）；同一首只预取一次。仅在「当前曲缓冲健康」或「接近结尾」时触发。
     *
     * @param currentPercent      当前曲已下载百分比；&lt;0 表示总长未知
     * @param currentLeadSeconds  当前曲领先秒数；&lt;0 表示无法估算
     * @param remainingSeconds    当前曲剩余秒数；&lt;0 表示未知
     * @param currentStarving     当前曲是否正饥饿 / 断流
     * @param currentPrefilling   当前曲是否正处于 prefill 门槛等待期间
     * @param alreadyPrefetched   这首下一曲是否已预取过（去重）
     */
    public static boolean shouldPrefetchNext(int currentPercent, long currentLeadSeconds,
                                             long remainingSeconds, boolean currentStarving,
                                             boolean currentPrefilling, boolean alreadyPrefetched) {
        if (alreadyPrefetched) {
            return false; // 同一首只预取一次
        }
        if (currentStarving || currentPrefilling) {
            return false; // 让位当前曲：饥饿 / 断流 / 正在起播门槛，绝不抢带宽
        }
        boolean healthy = (currentPercent >= 0 && currentPercent >= PREFETCH_HEALTHY_PERCENT)
                || (currentLeadSeconds >= 0 && currentLeadSeconds >= PREFETCH_LEAD_SECONDS);
        boolean nearEnd = remainingSeconds >= 0 && remainingSeconds <= PREFETCH_REMAINING_SECONDS;
        return healthy || nearEnd;
    }

    // ------------------------------------------------------------------ //
    //  3) 缓冲 % 上 UI：稳定播放即隐藏指示
    // ------------------------------------------------------------------ //

    /** 已下载百分比 ≥ 该值即视为「稳定播放」，隐藏缓冲指示。 */
    public static final int BUFFERING_STABLE_PERCENT = 95;
    /** 领先秒数 ≥ 该值即视为「稳定播放」。 */
    public static final long BUFFERING_STABLE_LEAD_SECONDS = 30L;

    /**
     * 是否已进入稳定播放（可隐藏缓冲指示）。
     *
     * @param percent          已下载百分比；&lt;0 表示未知
     * @param leadSeconds      领先秒数；&lt;0 表示无法估算
     * @param remainingSeconds 剩余秒数；&lt;0 表示未知（剩余不多时也算稳定，不必再提示）
     */
    public static boolean isBufferingStable(int percent, long leadSeconds, long remainingSeconds) {
        if (percent >= 0 && percent >= BUFFERING_STABLE_PERCENT) {
            return true;
        }
        if (leadSeconds >= 0 && leadSeconds >= BUFFERING_STABLE_LEAD_SECONDS) {
            return true;
        }
        // 剩余时长已不足一个稳定领先量：后面没有可担心的抽干，视为稳定
        return remainingSeconds >= 0 && remainingSeconds <= BUFFERING_STABLE_LEAD_SECONDS;
    }

    // ------------------------------------------------------------------ //
    //  4) 服务端响应状态：哪些是"再连也没用"
    // ------------------------------------------------------------------ //

    /**
     * 该 HTTP 状态是否表示"这个 Id 指向的资源不在了"。
     *
     * 曲库文件被改名或删除后 Jellyfin 就是这样回答的。它和网络故障的区别是决定性的：
     * 换 Id 才有救，对同一个 Id 重连多少次结果都一样。2026-10-06 曲库 wav→flac 改名后
     * 车机实测到 `download retry 2/5..4/5 from 0: java.io.IOException: HTTP 404`——
     * 按网络抖动退避五轮 (~12s) 再叠同曲重试与退避，一个失效 Id 要烧掉约 90s 才跳歌。
     *
     * 408/429 虽然也是 4xx，但它们是"现在不行、等一下也许行"，必须继续走重试。
     */
    public static boolean isMissingResourceStatus(int httpCode) {
        return httpCode == 404 || httpCode == 410;
    }
}
