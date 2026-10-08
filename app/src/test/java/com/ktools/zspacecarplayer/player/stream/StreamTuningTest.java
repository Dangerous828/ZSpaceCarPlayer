package com.ktools.zspacecarplayer.player.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * 回退闸的测试只钉一件事：<b>关掉必须精确退回 vc20 的行为</b>，开着才是本批的新行为。
 * 开关如果只是个界面文字，出问题时救不了人，所以每条闸都当成断言对象。
 */
public class StreamTuningTest {

    @After
    public void restoreDefaults() {
        StreamTuning.enableAll();
    }

    @Test
    public void defaultsAreTheNewBehaviour() {
        StreamTuning.enableAll();
        assertEquals(StreamTuning.DEFAULT_IDLE_FILL_GRACE_MS, StreamTuning.idleFillGraceMs());
        assertTrue(StreamTuning.forwardGapWaitEnabled());
        assertTrue(StreamTuning.seekEofGuardEnabled());
        assertTrue(StreamTuning.diskCacheEnabled());
    }

    /** 关闸 = 摘窗立刻收手（vc20 行为）：宽限 0 时判定必须恒为 false。 */
    @Test
    public void graceZeroRestoresImmediateYield() {
        StreamTuning.disableAll();
        assertEquals(0L, StreamTuning.idleFillGraceMs());
        long now = 500_000L;
        assertFalse("关闸后哪怕刚摘窗一毫秒也不许继续填",
                BufferedHttpSource.shouldKeepFillingWhileIdle(now, now - 1L, true, true,
                        StreamTuning.idleFillGraceMs()));
        // 开着时同一时刻必须放行（否则这条闸形同虚设）
        StreamTuning.configure(StreamTuning.DEFAULT_IDLE_FILL_GRACE_MS, true, true, true);
        assertTrue(BufferedHttpSource.shouldKeepFillingWhileIdle(now, now - 1L, true, true,
                StreamTuning.idleFillGraceMs()));
    }

    /** 关闸 = 前向缺口一律清窗重连（vc20 行为）。 */
    @Test
    public void forwardGapGateFallsBackToReconnect() {
        long bufStart = 1_000_000L;
        long bufEnd = 2_000_000L;
        long justAhead = bufEnd + 1_000L;
        assertEquals(BufferedHttpSource.PLACE_WAIT, BufferedHttpSource.readPlacementAction(
                justAhead, bufStart, bufEnd, false, true));
        assertEquals("关闸后同一位置必须判重连，与 vc20 一致", BufferedHttpSource.PLACE_RESET,
                BufferedHttpSource.readPlacementAction(justAhead, bufStart, bufEnd, false, false));
        // 窗内与 EOF 不受这条闸影响：它们本来就不该重连
        assertEquals(BufferedHttpSource.PLACE_IN_WINDOW, BufferedHttpSource.readPlacementAction(
                1_500_000L, bufStart, bufEnd, false, false));
        assertEquals(BufferedHttpSource.PLACE_EOF, BufferedHttpSource.readPlacementAction(
                3_000_000L, bufStart, bufEnd, true, false));
    }

    /** 越界负数一律当 0，配置里不许把宽限写成负值反而绕过判定。 */
    @Test
    public void negativeGraceIsClampedToOff() {
        StreamTuning.configure(-5_000L, true, true, true);
        assertEquals(0L, StreamTuning.idleFillGraceMs());
        assertFalse(BufferedHttpSource.shouldKeepFillingWhileIdle(10L, 1L, true, true,
                StreamTuning.idleFillGraceMs()));
    }
}
