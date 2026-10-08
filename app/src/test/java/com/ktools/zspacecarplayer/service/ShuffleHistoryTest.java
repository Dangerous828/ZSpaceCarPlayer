package com.ktools.zspacecarplayer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 随机模式的历史栈。钉的是车主报过的那个观感：「随机模式下上一首根本不能用」——
 * 旧实现按"上一首"也是重新随机一次。
 */
public class ShuffleHistoryTest {

    @Test
    public void backThenForwardReturnsToTheSamePlace() {
        ShuffleHistory h = new ShuffleHistory();
        assertFalse(h.canGoBack());
        // 依次播过 7 → 3 → 9
        h.pushBack(7);
        h.pushBack(3);
        assertEquals(3, h.goBack(9));     // 9 的上一首是 3
        assertEquals(1, h.forwardSize());
        assertEquals(7, h.goBack(3));     // 3 的上一首是 7
        assertEquals(2, h.forwardSize());
        assertEquals(3, h.goForward());   // 再按下一首：走回 3，而不是又随机
        assertEquals(9, h.goForward());   // 再按：回到 9
        assertFalse(h.canGoForward());
    }

    @Test
    public void advancingClearsTheForwardPath() {
        ShuffleHistory h = new ShuffleHistory();
        h.pushBack(1);
        h.goBack(2);
        assertTrue(h.canGoForward());
        h.pushBack(5); // 用户主动往前进了一首，"来路"必须作废，
        assertFalse("否则下一首会跳回旧位置，等于凭空插进去一首不该播的", h.canGoForward());
    }

    @Test
    public void boundedAndResetOnQueueChange() {
        ShuffleHistory h = new ShuffleHistory();
        for (int i = 0; i < ShuffleHistory.MAX + 40; i++) {
            h.pushBack(i);
        }
        assertEquals("超出深度就丢最旧的", ShuffleHistory.MAX, h.backSize());
        // 弹栈弹的是<b>最近</b>那首（留下的 60 个是 40..99），不是最旧的 59
        assertEquals("最近一首必须能回到", 99, h.goBack(999));
        assertEquals("还留着 59 个可回溯", 59, h.backSize());
        h.reset();
        assertFalse(h.canGoBack());
        assertEquals(0, h.forwardSize());
    }

    @Test
    public void ignoresNegativeIndexAndEmptyForward() {
        ShuffleHistory h = new ShuffleHistory();
        h.pushBack(-1);
        assertFalse("无效位置不得进栈，否则 goBack 会返回 -1 把队列索引写坏", h.canGoBack());
        // 空栈被误调用：必须返回 -1 且不留下任何被改坏的状态
        assertEquals(-1, h.goBack(12));
        assertEquals(-1, h.goForward());
        assertEquals("空栈调用不得污染 forward", 0, h.forwardSize());
        assertEquals("也不得污染 back", 0, h.backSize());
    }
}
