package com.ktools.zspacecarplayer.player.stream;

/**
 * 取流改造的<b>回退闸</b>（2026-10-08，T1 重审 A1/A2 那一批）。
 *
 * <p>为什么必须有：那一批改的是所有播放都要走的地基（下载线程何时收手、什么时候清窗重连、
 * seek 越界怎么报、字节要不要落盘）。风险比前 20 个版本都高，而真车只有一台、看不到现场日志。
 * 没有闸的话，一旦某条改动在 8600 上引起新问题，唯一的出路是"改代码重出包再等上电升级"。
 * 有了闸，设置页关掉即可退回 vc20 的既有行为，<b>不需要重新出包</b>。
 *
 * <p>四条闸各自独立，因为它们可能各自出问题，也不该互相牵连：
 * <ul>
 *   <li>{@code idleFillGraceMs}：摘窗后继续填窗多久。0 = 一摘窗就断连（vc20 行为）。</li>
 *   <li>{@code forwardGapWaitEnabled}：前向小缺口判等待而不是清窗重连。false = 一律重连。</li>
 *   <li>{@code seekEofGuardEnabled}：seek 越过已知总长返回 false。false = 恒放行（vc20 行为，
 *       代价是 dr_flac 拿不到"到流尾了"的判据）。</li>
 *   <li>{@code diskCacheEnabled}：边播边存。false = 完全不碰盘。</li>
 * </ul>
 *
 * <p>这里<b>不读 SharedPreferences</b>：偏好读取由服务层做一次（{@code AudioPlayerService}
 * 启动与设置页切换时调 {@link #configure}），这些判定因此是纯函数、能在 JVM 单测里钉死。
 * 开关翻转<b>只影响下一首</b>——正在播的流不动，中途改行为等于制造第二个 bug。
 */
public final class StreamTuning {

    /** 摘窗宽限默认值，与 {@code BufferedHttpSource.IDLE_FILL_GRACE_MS} 同源。 */
    public static final long DEFAULT_IDLE_FILL_GRACE_MS = 8_000L;

    private static volatile long idleFillGraceMs = DEFAULT_IDLE_FILL_GRACE_MS;
    private static volatile boolean forwardGapWaitEnabled = true;
    private static volatile boolean seekEofGuardEnabled = true;
    private static volatile boolean diskCacheEnabled = true;

    private StreamTuning() {
    }

    /** 服务启动 / 设置页切换时灌进来。任何一项为 0/false 都表示退回 vc20 的既有行为。 */
    public static void configure(long graceMs, boolean forwardGapWait, boolean seekEofGuard,
                                 boolean diskCache) {
        idleFillGraceMs = graceMs < 0L ? 0L : graceMs;
        forwardGapWaitEnabled = forwardGapWait;
        seekEofGuardEnabled = seekEofGuard;
        diskCacheEnabled = diskCache;
    }

    public static long idleFillGraceMs() {
        return idleFillGraceMs;
    }

    public static boolean forwardGapWaitEnabled() {
        return forwardGapWaitEnabled;
    }

    public static boolean seekEofGuardEnabled() {
        return seekEofGuardEnabled;
    }

    public static boolean diskCacheEnabled() {
        return diskCacheEnabled;
    }

    /** 全开——测试与"恢复默认"用。 */
    public static void enableAll() {
        configure(DEFAULT_IDLE_FILL_GRACE_MS, true, true, true);
    }

    /** 全关——即彻底退回 vc20 的取流行为（排查问题时用）。 */
    public static void disableAll() {
        configure(0L, false, false, false);
    }
}
