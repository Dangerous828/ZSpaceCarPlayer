package com.ktools.zspacecarplayer.player.stream;

/**
 * 带宽缺口的<b>判定与证据累计</b>——它不认识"档位"这个概念 (2026-10-08 T1 重审收口)。
 *
 * <p>存在理由：本库无损曲目需要约 100~151 KB/s 的**持续**带宽（2026-10-07 实测 Bad Romance 的
 * FLAC 是 44,499,871B / 294,661ms = 151 KB/s；2026-10-08 另一首 18,869,283B / 181,812ms
 * = 101 KB/s），而链路可能只给到三分之一。这种**持续缺口**不是缓冲能补的——环形窗口再大也只是
 * 把饿死往后推几十秒。要把它和"一次抖动"区分开，就需要窗口化的速率判定。
 *
 * <p><b>它只数缺口，不决定换档。</b>历史上这里同时持有档位状态并允许自动换档，两次都出事：
 * vc16 用固定阈值自动降档，健康歌被降、连着 4 首腰斩；vc19 重开自动档，直接进
 * prepare→EOS→replay 环。车主 2026-10-08 明确否决自动降档（"我不接受降档这个做法"），
 * 档位改由 {@link StreamTier} 承载、在设置页手选，于是本类的档位字段全部删掉。
 * 留着那份没人能置位的 degraded 状态有害而不只是无用：测速分支拿它当闸门，结果是
 * <b>车主手动选了「流畅」之后，恰恰这一档的 deficit 证据全部断供</b>。
 *
 * <p><b>判据必须是「本曲所需速率」，不能是任何固定下限</b>。vc16 用固定 140KB/s，2026-10-07 真车
 * 两件事同时把它证伪：① 一首 MP3 原件天然只跑约 30KB/s，链路完全健康也会被判成带宽不足；
 * ② 一首下载完成的歌零增长就是 0B/s，同样被判成带宽不足。当晚序列是 percent 87→100
 * （lead 稳在 65s，链路毫无问题）→ 9 秒后 bitrate tier -&gt; smooth(128k) est=-1KB/s
 * （那个 -1 就是实测 0）。
 *
 * <p><b>样本口径只认 socket 真收字节</b>，且窗口饱和时不采——见
 * {@link BufferingPolicy#bandwidthSampleIsMeasurable} 与
 * {@link BufferingPolicy#smoothTierSampleIsMeasurable}。拿 bufEnd 当"下载了多少"时，
 * 8MB 窗一饱和它就只跟着读者走，est 恒等于播放消耗速率，健康链路也必然满足
 * est &lt; 所需×1.15——那个口径会<b>凭空造出缺口</b>。
 */
public final class StreamRateGovernor {

    /** 判定窗口：5 秒一个样本。再短会被一次重连带偏，再长则饿死已经发生了。 */
    public static final long WINDOW_MS = 5_000L;
    /** 连续这么多个窗口追不上所需速率才认定一次缺口；单窗口抖动（一次重连、一次红灯）不足以定性。 */
    public static final int DEGRADE_CONFIRMATIONS = 3;
    /** 认定缺口的上浮：实测 &lt; 所需 ×(1+15%) 即算追不上——播放本身就要吃掉 100% 的所需速率。 */
    public static final int DEFICIT_MARGIN_PERCENT = 15;

    private int belowStreak = 0;
    private long windowBaseMs = -1L;
    private long windowBaseBytes = -1L;
    /** 最近一个完整窗口的实测速率（B/s）；-1 表示还没凑出过窗口。仅用于日志与上报。 */
    private long lastWindowBytesPerSec = -1L;
    /** 最近一次判定所用的所需速率（B/s），只为把证据写进面包屑。 */
    private long lastRequiredBytesPerSec = -1L;
    /** 累计认定「持续追不上」的次数（连续窗口达标算一次）。 */
    private int deficitConfirmations = 0;

    /**
     * 喂一次下载进度。
     *
     * <p>totalBytes 是<b>累计</b>字节数（socket 真收口径），不是增量：调用方不需要自己算差值，
     * 而窗口速率 = (本次累计 - 窗口基准累计) / (本次时间 - 窗口基准时间)，跨窗口不重复计数。
     * 不足一个窗口的调用只推进累计值，不做判定。
     *
     * @param nowMs               单调时钟毫秒（SystemClock.elapsedRealtime()），必须不回拨
     * @param totalBytes          本流开始以来累计从 socket 收到的字节数
     * @param requiredBytesPerSec 本曲「不抽干」所需的速率 = 资源总字节 / 时长；&lt;= 0 表示口径
     *                            不可判（总长或时长任一未知），此时<b>不做任何判定</b>——否则一次
     *                            不可判的采样就会被算成 0B/s 的缺口，那正是 vc16 误判的成因
     */
    public void onProgress(long nowMs, long totalBytes, long requiredBytesPerSec) {
        if (windowBaseMs < 0) {
            windowBaseMs = nowMs;
            windowBaseBytes = totalBytes;
            return;
        }
        if (totalBytes < windowBaseBytes) {
            // 累计口径的前提被打破：本流被<b>重定位</b>了（换曲、后向 seek、或原生二分内部的
            // 自动重定位——requestResetLocked 会把 bufStart/bufEnd 一起挪到新位置）。
            // 这跟"链路没给字节"是两件事，绝不能钳成 0B/s 计一个缺口窗口——vc16 就是这么
            // 在每次换曲时白送一个假缺口的（上一首累计 44MB，新歌从 0 数起）。
            // 只重建基准、不动任何已有结论；调用方的 rebaseWindow() 是显式入口，不是依赖。
            windowBaseMs = nowMs;
            windowBaseBytes = totalBytes;
            return;
        }
        long elapsed = nowMs - windowBaseMs;
        if (elapsed < WINDOW_MS) {
            return;
        }
        long est = (totalBytes - windowBaseBytes) * 1000L / elapsed;
        lastWindowBytesPerSec = est;
        windowBaseMs = nowMs;
        windowBaseBytes = totalBytes;
        if (requiredBytesPerSec <= 0L) {
            return;
        }
        lastRequiredBytesPerSec = requiredBytesPerSec;
        long deficitBound = requiredBytesPerSec + requiredBytesPerSec * DEFICIT_MARGIN_PERCENT / 100L;
        if (est < deficitBound) {
            if (++belowStreak >= DEGRADE_CONFIRMATIONS) {
                deficitConfirmations++;
                belowStreak = 0;
            }
        } else {
            belowStreak = 0;
        }
    }

    /**
     * 起播/拖动进度条后调用：累计口径被重连或 seek 打断，窗口基准与半截计数作废。
     * 这里没有任何档位结论可以被洗掉——拖一次进度条不该影响已认定的证据。
     */
    public void rebaseWindow() {
        windowBaseMs = -1L;
        windowBaseBytes = -1L;
        belowStreak = 0;
    }

    public long getLastWindowBytesPerSec() {
        return lastWindowBytesPerSec;
    }

    public long getLastRequiredBytesPerSec() {
        return lastRequiredBytesPerSec;
    }

    /** 认定过多少次「持续追不上」：这是"链路真的不够"的唯一留痕，也是提示车主可以切档的依据。 */
    public int getDeficitConfirmations() {
        return deficitConfirmations;
    }
}
