package com.ktools.zspacecarplayer.player.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 涓流饥饿判定（{@link BufferedHttpSource#starveVerdict}）的边界测试。
 *
 * 车机现场的真实故障形态：上游以几 KB/s 持续「涓流」供数——只要有一个字节就算进展，
 * 30s 无进展检测被不断刷新、15s read timeout 也因为每次 read 都在时限内返回而不触发，
 * 2MB 环形缓冲十几秒被抽干，解码线程就在 bufEnd 上永久 wait：进度冻结、UI 仍显示播放中、
 * 无错误无回退无上报。判定改成「读者视角的累计挨饿时长」后，这套阈值必须钉死：
 * 太松回到永久静默，太紧会把弱网下能听的歌误杀成断流。
 *
 * 抽成纯函数是因为 JVM 单测里 SystemClock.elapsedRealtime() 恒为 0，带真实时钟的
 * 实例方法在单测中永远推进不了窗口。
 */
public class StreamStarvationTest {

    /** 与 BufferedHttpSource 内部常量保持一致（改动阈值时这里必须同步） */
    private static final long WINDOW_MS = 20_000L;
    private static final long LIMIT_MS = 18_000L;

    private static final int OK = BufferedHttpSource.STARVE_OK;
    private static final int RECONNECT = BufferedHttpSource.STARVE_RECONNECT;
    private static final int FATAL = BufferedHttpSource.STARVE_FATAL;

    /** 未武装（无读者在等数据）：永不判定，否则暂停/空闲会被误杀成断流 */
    @Test
    public void unarmedWindowNeverStalls() {
        assertEquals(OK, BufferedHttpSource.starveVerdict(-1L, 999_999L, 0L, 0, 1_000_000L));
        assertEquals(OK, BufferedHttpSource.starveVerdict(-1L, 0L, -1L, 5, 1_000_000L));
    }

    /** 窗口还没滚动：无论挨饿多久都不判定 */
    @Test
    public void windowNotYetRolledIsOk() {
        assertEquals(OK, BufferedHttpSource.starveVerdict(0L, 0L, 0L, 0, WINDOW_MS - 1));
        assertEquals(OK, BufferedHttpSource.starveVerdict(1_000L, 0L, 1_000L, 0, 1_000L + WINDOW_MS - 1));
    }

    /** 窗口刚满即可参与判定（闭区间下界） */
    @Test
    public void windowRollsExactlyAtBoundary() {
        assertEquals(RECONNECT, BufferedHttpSource.starveVerdict(0L, 0L, 0L, 0, WINDOW_MS));
    }

    /**
     * 涓流形态：全程挨饿（starvedSince 一直没被结算）。
     * 这是 8:40 现场的签名——首次判定只换连接，连续判定超过上限才升级 fatal。
     */
    @Test
    public void fullyStarvedWindowEscalates() {
        assertEquals(RECONNECT, BufferedHttpSource.starveVerdict(0L, 0L, 0L, 0, WINDOW_MS));
        assertEquals(RECONNECT, BufferedHttpSource.starveVerdict(0L, 0L, 0L, 1, WINDOW_MS));
        assertEquals(FATAL, BufferedHttpSource.starveVerdict(0L, 0L, 0L, 2, WINDOW_MS));
        assertEquals(FATAL, BufferedHttpSource.starveVerdict(0L, 0L, 0L, 9, WINDOW_MS));
    }

    /** 挨饿时长恰好压线即触发，差 1ms 不触发 */
    @Test
    public void limitIsInclusive() {
        assertEquals(RECONNECT, BufferedHttpSource.starveVerdict(0L, LIMIT_MS, -1L, 0, WINDOW_MS));
        assertEquals(OK, BufferedHttpSource.starveVerdict(0L, LIMIT_MS - 1, -1L, 0, WINDOW_MS));
    }

    /** 已结算挨饿 + 当前这段未结算挨饿必须合并计入（漏算任一段都会漏判涓流） */
    @Test
    public void settledAndOngoingStarvationAreSummed() {
        // 10s 已结算 + 从 12s 起连续挨饿到 20s = 18s，正好压线
        assertEquals(RECONNECT, BufferedHttpSource.starveVerdict(0L, 10_000L, 12_000L, 0, WINDOW_MS));
        // 同样 10s 已结算，但当前这段只挨了 7s → 17s，未达阈值
        assertEquals(OK, BufferedHttpSource.starveVerdict(0L, 10_000L, 13_000L, 0, WINDOW_MS));
    }

    /** 此刻有数据可读（starvedSince < 0）：只看已结算部分 */
    @Test
    public void fedReaderCountsOnlySettledTime() {
        assertEquals(OK, BufferedHttpSource.starveVerdict(0L, 2_000L, -1L, 0, WINDOW_MS));
        assertEquals(RECONNECT, BufferedHttpSource.starveVerdict(0L, WINDOW_MS, -1L, 0, WINDOW_MS));
    }

    /**
     * 健康 / 轻微抖动：一个窗口内喂得上（挨饿 < 18s）必须判 OK。
     * 这条是防误杀的红线——公网串流偶发几秒抖动是正常的，不能因此断掉能听的歌。
     */
    @Test
    public void mostlyFedWindowIsHealthy() {
        // 抖动 5s、其余时间在正常供数
        assertEquals(OK, BufferedHttpSource.starveVerdict(0L, 5_000L, -1L, 0, WINDOW_MS));
        // 已经因抖动被判过一次，但这个窗口恢复正常 → OK（调用方据此把计数清零）
        assertEquals(OK, BufferedHttpSource.starveVerdict(0L, 3_000L, -1L, 2, WINDOW_MS));
    }

    /** 窗口起点晚于当前时刻（时钟回拨等异常）不得判成断流 */
    @Test
    public void clockSkewBackwardsIsOk() {
        assertEquals(OK, BufferedHttpSource.starveVerdict(50_000L, 0L, 50_000L, 0, 20_000L));
    }

    /**
     * 只有能用 Range 续传的流，"换个连接从头挨着续下"才是自愈。
     * 2026-10-07 真车：流畅档（chunked 转码流）不支持 Range，重连等于让服务端从 0 重转、
     * 客户端丢弃已下字节——两次 stall 的 bufEnd 一模一样（零字节），随后 starve FATAL 把
     * 一首 219,493ms 的歌在 65,802ms 处打断。所以不可续传的流必须完全不走这条判定。
     */
    @Test
    public void reconnectOnlyHelpsResumableSources() {
        assertTrue(BufferedHttpSource.starvationReconnectUseful(true, -1L));
        assertTrue(BufferedHttpSource.starvationReconnectUseful(false, 38_934_625L));
        assertFalse("chunked 无总长：重连是自伤",
                BufferedHttpSource.starvationReconnectUseful(false, -1L));
        assertFalse(BufferedHttpSource.starvationReconnectUseful(false, 0L));
    }

    /**
     * 下载线程<b>因异常</b>重连时的同一把账，2026-10-08 08:51 真车现场版：
     * {@code download retry 1/5 from 1178858: SocketTimeoutException} →
     * {@code remote ignored Range, skipping 1178858 bytes}。chunked 流不认 Range，所以
     * "从 bufEnd 续传"实际是从 0 重下再丢掉 1.18MB；按当晚链路约 70KB/s，一次重连白烧 17 秒，
     * 五条退避跑完约 85 秒死寂。这期间播放器位置恒为 0，最后被"坏断点"兜底判成贴尾 →
     * 清零续播点 → 从零重播 → 再走同一条路，日志里这个环跑了两圈后进程就没了。
     */
    @Test
    public void midFlightRetryOnlyWhenReconnectIsCheapOrPossible() {
        // 可续传（回过 206 / 总长已知）：按字节续传有意义，照旧重试
        assertTrue(BufferedHttpSource.midFlightRetryUseful(true, true, -1L, 8_000_000L));
        assertTrue(BufferedHttpSource.midFlightRetryUseful(true, false, 38_934_625L, 8_000_000L));
        // 连响应头都没有（纯建连失败）：与可续传无关，必须重试
        assertTrue(BufferedHttpSource.midFlightRetryUseful(false, false, -1L, 4_000_000L));
        // chunked 且已经下了一窗：重下再丢弃 = 自伤，直接判死交给上层
        assertFalse(BufferedHttpSource.midFlightRetryUseful(true, false, -1L,
                BufferedHttpSource.NON_RESUMABLE_RETRY_MAX_BYTES));
        assertFalse(BufferedHttpSource.midFlightRetryUseful(true, false, -1L, 1_178_858L));
        // 起播初期从头重连代价可接受，仍按旧行为重试（别把一次抖动升级成整首失败）
        assertTrue(BufferedHttpSource.midFlightRetryUseful(true, false, -1L,
                BufferedHttpSource.NON_RESUMABLE_RETRY_MAX_BYTES - 1));
        assertTrue(BufferedHttpSource.midFlightRetryUseful(true, false, -1L, 0L));
    }

    /**
     * 读请求落点判定 (2026-10-08 A1)。旧判定把"只差一点的前向位置"和"真跳走了"混在一起，
     * 一律清窗重连——而清窗要重新付 0.8~1.4 秒首字节，还顺手作废已下载的尾部。
     */
    @Test
    public void smallForwardGapIsWaitedNotReconnected() {
        // 窗 [1,000,000 - 2,000,000)
        assertEquals(BufferedHttpSource.PLACE_IN_WINDOW,
                BufferedHttpSource.readPlacementAction(1_500_000L, 1_000_000L, 2_000_000L, false, true));
        assertEquals("窗尾正好是 bufEnd：等", BufferedHttpSource.PLACE_WAIT,
                BufferedHttpSource.readPlacementAction(2_000_000L, 1_000_000L, 2_000_000L, false, true));
        assertTrue("缺口在阈值内必须判成等待",
                BufferedHttpSource.FORWARD_GAP_WAIT_MAX_BYTES > 0);
        assertEquals(BufferedHttpSource.PLACE_WAIT, BufferedHttpSource.readPlacementAction(
                2_000_000L + BufferedHttpSource.FORWARD_GAP_WAIT_MAX_BYTES,
                1_000_000L, 2_000_000L, false, true));
        assertEquals("缺口过大才是真重定位", BufferedHttpSource.PLACE_RESET, BufferedHttpSource.readPlacementAction(
                2_000_001L + BufferedHttpSource.FORWARD_GAP_WAIT_MAX_BYTES,
                1_000_000L, 2_000_000L, false, true));
        assertEquals("回读到已回收区：只能重连", BufferedHttpSource.PLACE_RESET,
                BufferedHttpSource.readPlacementAction(999_999L, 1_000_000L, 2_000_000L, false, true));
        assertEquals("EOF 之后一律是 EOF，不再等一个永远不会来的字节", BufferedHttpSource.PLACE_EOF,
                BufferedHttpSource.readPlacementAction(2_000_000L, 1_000_000L, 2_000_000L, true, true));
        assertEquals(BufferedHttpSource.PLACE_EOF,
                BufferedHttpSource.readPlacementAction(9_000_000L, 1_000_000L, 2_000_000L, true, true));
    }

    /**
     * 摘窗宽限期 (2026-10-08 A1)：读者短暂离开（解码器跳读、代理换连接）时继续在同一条上游
     * 连接上收字节，别把 797KB/s 的长连接切成 133KB/s 的短连接。同时必须防住"切走的旧歌在后台
     * 继续吃新歌带宽"——release 之后立刻收手。
     */
    @Test
    public void idleFillKeepsConnectionButNeverOutlivesTheHolder() {
        long now = 100_000L;
        long grace = StreamTuning.DEFAULT_IDLE_FILL_GRACE_MS;
        assertTrue("宽限内、窗还有余量、仍被持有 = 继续填",
                BufferedHttpSource.shouldKeepFillingWhileIdle(now, now - 1_000L, true, true, grace));
        assertTrue("宽限边界内一毫秒都不算超",
                BufferedHttpSource.shouldKeepFillingWhileIdle(now, now - grace + 1L, true, true, grace));
        assertFalse("超宽限就收手，让出带宽与唯一的解码线程",
                BufferedHttpSource.shouldKeepFillingWhileIdle(now, now - grace, true, true, grace));
        assertFalse("窗满了继续读只会背压阻塞，收手等读者回来再拉起",
                BufferedHttpSource.shouldKeepFillingWhileIdle(now, now - 1_000L, false, true, grace));
        assertFalse("已 release（切歌/销毁）：旧源绝不允许在后台吃新歌的带宽",
                BufferedHttpSource.shouldKeepFillingWhileIdle(now, now - 1_000L, true, false, grace));
        assertFalse("从未摘窗（还有读者）不该走这条判定",
                BufferedHttpSource.shouldKeepFillingWhileIdle(now, -1L, true, true, grace));
        assertFalse("时钟回拨不认",
                BufferedHttpSource.shouldKeepFillingWhileIdle(now, now + 1_000L, true, true, grace));
    }
}
