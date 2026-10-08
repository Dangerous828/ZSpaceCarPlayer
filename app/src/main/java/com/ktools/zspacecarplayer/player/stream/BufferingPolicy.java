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
    /** 触发预取的健康阈值：领先秒数 ≥ 该值才允许抢带宽（见 {@link #shouldPrefetchNext}）。 */
    public static final long PREFETCH_LEAD_SECONDS = 15L;
    /** 当前曲接近结尾阈值：剩余 ≤ 该秒数时无条件预取（马上要切歌）。 */
    public static final long PREFETCH_REMAINING_SECONDS = 15L;

    /**
     * 磁盘缓存上限 (2026-10-08 T1 重审 A2)：既是<b>单文件</b>上限（超过就不缓存，比如曲库里
     * 混进来的那些 4K mp4），也是整个缓存目录的 LRU 上限。1GB 的取舍：本库无损单曲实测
     * 20~44MB，够存二十几首常听的；再大就侵占车机存储，而 {@code CacheSizeManager} 的
     * 全局裁剪是 10GB/8GB，不能跟它抢口径。
     */
    public static final long DISK_CACHE_LIMIT_BYTES = 1024L * 1024L * 1024L;
    /**
     * 单个文件的缓存上限 100MB (2026-10-08)。必须与目录上限分开：只给一个 1GB 的话，
     * 曲库里那类 360MB 的巨型文件会把常听的歌全挤出去，LRU 反而变成"缓存了个没用的"。
     * 本库正常音频实测 20~44MB，100MB 已经留了三倍余量。
     */
    public static final long DISK_CACHE_MAX_FILE_BYTES = 100L * 1024L * 1024L;

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
     * （当前曲永远优先）；同一首只预取一次。仅在「当前曲还有领先秒数」或「接近结尾」时触发。
     *
     * @param currentLeadSeconds  当前曲领先秒数；&lt;0 表示无法估算（chunked 流就是这种）
     * @param remainingSeconds    当前曲剩余秒数；&lt;0 表示未知
     * @param currentStarving     当前曲是否正饥饿 / 断流
     * @param currentPrefilling   当前曲是否正处于 prefill 门槛等待期间
     * @param alreadyPrefetched   这首下一曲是否已预取过（去重）
     */
    public static boolean shouldPrefetchNext(long currentLeadSeconds,
                                             long remainingSeconds, boolean currentStarving,
                                             boolean currentPrefilling, boolean alreadyPrefetched) {
        if (alreadyPrefetched) {
            return false; // 同一首只预取一次
        }
        if (currentStarving || currentPrefilling) {
            return false; // 让位当前曲：饥饿 / 断流 / 正在起播门槛，绝不抢带宽
        }
        // 「健康」只认**领先秒数**，不认「已下载百分比」(2026-10-08 真车复盘)。
        // percent 说的是"这首歌的文件下了多少"，不是"还能播几秒"：链路有缺口时下载头会紧贴
        // 播放头（当天现场 lead 全程 0s，而 percent 一路涨到 57），旧的 percent>=40 那一支
        // 正是在这种状态下把 2MB 预取窗口放出去，从当前曲嘴里抢带宽——预取越勤快，当前曲
        // 越早抽干。反过来在健康链路上，缓冲领先本来就会让 leadSeconds 达标，不需要 percent 分支。
        boolean healthy = currentLeadSeconds >= 0 && currentLeadSeconds >= PREFETCH_LEAD_SECONDS;
        // 「接近结尾」这道门依赖剩余秒数：播放器在 chunked 流上报 0 时长（无 Content-Length），
        // 调用方须先用入库元数据兜底，见 {@link #durationForDerivation}。
        boolean nearEnd = remainingSeconds >= 0 && remainingSeconds <= PREFETCH_REMAINING_SECONDS;
        return healthy || nearEnd;
    }

    /**
     * 播放器时长不可用时（chunked 流没有 Content-Length，{@code getDuration()==0}）用入库元数据
     * 兜底，让「剩多少秒」这条门重新算得出来 (2026-10-08)。
     *
     * <p><b>只用于预取与显示这类"算个大概也不致命"的推导</b>。不许拿它去放行 seek：元数据说
     * 这首歌 200 秒，不代表这条 chunked 流能定位到 200 秒里的任意字节——vc19 就是因为把兜底时长
     * 接到了起播路径上，对不可续传流下发了 seek，当晚现场以进程消失收场（已整体撤回）。
     */
    public static long durationForDerivation(long playerDurationMs, long metaDurationMs) {
        return playerDurationMs > 0L ? playerDurationMs : Math.max(0L, metaDurationMs);
    }

    // ------------------------------------------------------------------ //
    //  3) 缓冲 % 上 UI：稳定播放即隐藏指示
    // ------------------------------------------------------------------ //

    /** 已下载百分比 ≥ 该值即视为「稳定播放」，隐藏缓冲指示。 */
    public static final int BUFFERING_STABLE_PERCENT = 95;
    /** 领先秒数 ≥ 该值即视为「稳定播放」。 */
    public static final long BUFFERING_STABLE_LEAD_SECONDS = 30L;
    /** 位置在这个时长内推进过一次就算「还在出声」；比它长就认为声音冻住了。 */
    public static final long AUDIO_ADVANCE_FRESH_MS = 3_000L;

    /**
     * 出声进展的纯判定（抽出来是因为本仓纪律：凡裁定都必须能在 host JVM 上单测，
     * {@code SystemClock.elapsedRealtime()} 在单测里恒为 0，带真实时钟的实例方法推不动）。
     *
     * @param nowMs            当前单调时钟
     * @param lastAdvanceAtMs  位置最近一次前进的时刻；{@code <=0} = 本轮从未推进过
     * @return {@code true} = 不能断定"声音冻住了"。从未推进时<b>故意返回 true</b>：那可能只是
     *         刚 prepared，也可能是这台设备的位置通道读不出数，两种都不该凭空显示「缓冲中…」——
     *         此时判定权交回下载口径（prefill 期 percent 低，照样会显示）
     */
    public static boolean audioAdvancedRecently(long nowMs, long lastAdvanceAtMs) {
        if (lastAdvanceAtMs <= 0L) {
            return true;
        }
        return nowMs - lastAdvanceAtMs <= AUDIO_ADVANCE_FRESH_MS;
    }

    /**
     * 测速窗口是否<b>可采</b>（2026-10-07 / 10-08 两连续晚的误判都栽在这道门上，四条缺一不可）。
     *
     * <p>① {@code percent < 100}：整首已经落地，此后必然零新字节，读成 0B/s 就是"带宽不足"
     *    ——10-07 那次 `percent 87→100`（链路健康）9 秒后就被换了档。
     * <p>② 窗口有余量（{@code ringRoom}）：8MB 环形窗一饱和，写入被读者拽着走，
     *    <b>任何速率样本都变成了"播放消耗速率"</b>而不是链路能力，于是健康链路也永远测出
     *    `est ≈ 所需 < 所需×1.15`，必然误判。10-08 就是这么"测"出 107KB/s 的——车主同一条链路
     *    10 秒下完 3.1MB 的包（>300KB/s）。
     * <p>③④ {@code contentLength > 0} 且 {@code durationMs > 0}：没有总长就没有"这首需要多少
     *    B/s"，chunked 转码流两条都拿不到（它的产率还是转码器的，不是链路的）。
     */
    public static boolean bandwidthSampleIsMeasurable(int percent, long contentLength,
                                                      long durationMs, boolean ringRoom) {
        return percent >= 0 && percent < 100 && contentLength > 0L && durationMs > 0L && ringRoom;
    }

    /**
     * 已经在流畅档上时，这个样本能不能用来判「链路是否已经好到可以回无损」(2026-10-08)。
     *
     * <p>流畅档是 chunked：没有 Content-Length，所以 {@link #bandwidthSampleIsMeasurable} 恒为
     * false，{@code requiredBytesPerSec} 也恒为 -1。若沿用那道门，降档之后就<b>再也采不到任何
     * 样本</b>，回升判定永远不会发生，档位被永久钉在 128k——回家连 WiFi 也还是流畅档。vc16 那晚
     * 「之后 4 首全部在流畅档被腰斩」除了误判进去，还有这一条让它出不来。
     *
     * <p>但链路本身照样量得出：socket 真收字节与总长无关。缺的只是"本曲所需速率"这个分母，
     * 于是用<b>降档前那一次实测到的所需速率</b>当基准（{@code rememberedRequiredBytesPerSec}）。
     * 播放时长在这里允许是 0（chunked 流 {@code dur=0ms} 是常态），因为分母已经不由它决定了。
     *
     * <p>所需速率本逐首不同，拿上一首的数是近似；但回升要求 {@code 所需×1.45} 的富余，近似只会
     * 偏保守（更难回升），不会把不够的链路放回无损。
     */
    public static boolean smoothTierSampleIsMeasurable(long rememberedRequiredBytesPerSec,
                                                       boolean ringRoom) {
        return rememberedRequiredBytesPerSec > 0L && ringRoom;
    }

    /** 本曲「不抽干」所需速率 = 资源总长 / 时长；不可测时返回 -1（治理器据此不动任何结论）。 */
    public static long requiredBytesPerSec(long contentLength, long durationMs) {
        if (contentLength <= 0L || durationMs <= 0L) {
            return -1L;
        }
        return contentLength * 1000L / durationMs;
    }

    /**
     * 是否已进入稳定播放（可隐藏缓冲指示）。
     *
     * <p><b>两扇门，任一门说卡就是卡</b>（2026-10-07 车主问「界面上那个缓冲中三个字对不上逻辑
     * 了吧」——确实对不上，而且是两个相反方向都错）：
     * <ul>
     *   <li><b>下载口径</b>：percent / lead / remaining 三个数里任何一个达到稳定线就算下载侧稳。
     *       它的好处是<b>前瞻</b>——{@code lead} 贴 0 而声音还没停时就能预告抽干，这条能力必须保留。</li>
     *   <li><b>出声进展</b>：位置还在往前走。旧判据完全不看这一条，于是 {@code percent>=95} 或
     *       {@code lead>=30} 会在声音真冻住时把指示藏起来（错法一）。</li>
     * </ul>
     *
     * <p>旧判据的反向错法更要命：三个数<b>全都不可判</b>（{@code -1/-1/-1}，chunked 转码流就是
     * 这样——{@code dur=0ms} 让 {@code remaining} 永远是 -1）时三条守卫全部落空，返回 false，
     * UI 于是<b>恒亮且没有熄灭条件</b>；当晚上报里 {@code STUCK heartbeat percent=-1 lead=-1s
     * remaining=-1s} 的那 4 首，声音连续播了两分钟，屏幕上一直挂着「缓冲中…」。所以不可判
     * <b>不是证据</b>，不能当成"还在缓冲"，只能交给唯一剩下的证据——出声进展。
     *
     * @param percent          已下载百分比；&lt;0 表示未知
     * @param leadSeconds      领先秒数；&lt;0 表示无法估算
     * @param remainingSeconds 剩余秒数；&lt;0 表示未知（剩余不多时也算稳定，不必再提示）
     * @param audioAdvancing   播放位置最近是否在推进；{@code false} = 声音冻住了
     */
    public static boolean isBufferingStable(int percent, long leadSeconds, long remainingSeconds,
                                           boolean audioAdvancing) {
        if (!audioAdvancing) {
            return false;
        }
        // 下载侧只有拿到 percent 或 lead 才有投票权。只剩 remaining（时长已知但流没有长度，
        // chunked 转码流就是这样）时它对"是否在缓冲"其实一无所知——让它投票就等于恒亮，
        // 而恒亮正是 2026-10-07 车主投诉的样子。（10-08 补：那天我把时长兜底只接在原生分支，
        // 补上之后 remaining 变已知，这条判据若不同步改就会以另一种方式复发。）
        boolean downloadKnowsSomething = percent >= 0 || leadSeconds >= 0;
        if (!downloadKnowsSomething) {
            return true;
        }
        return (percent >= BUFFERING_STABLE_PERCENT)
                || (leadSeconds >= BUFFERING_STABLE_LEAD_SECONDS)
                // 剩余时长已不足一个稳定领先量：后面没有可担心的抽干，视为稳定
                || (remainingSeconds >= 0 && remainingSeconds <= BUFFERING_STABLE_LEAD_SECONDS);
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
