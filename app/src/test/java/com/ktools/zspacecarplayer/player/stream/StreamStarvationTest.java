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
}
