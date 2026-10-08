package com.ktools.zspacecarplayer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 睡眠定时的边界。钉两件事：到点必须真的判"过期"，以及没开/时钟回拨时绝不能误触发
 * ——车上误触发一次就是"听着听着自己停了"，比没有这个功能更糟。
 */
public class SleepTimerTest {

    @Test
    public void expiryAndRemainingAreConsistent() {
        long now = 1_000_000L;
        long stop = SleepTimer.deadlineFor(now, 30);
        assertEquals(30 * 60_000L, stop - now);
        assertFalse("还没到点不许停", SleepTimer.isExpired(now + 1L, stop));
        assertTrue("差一毫秒都不算到点", SleepTimer.isExpired(stop, stop));
        assertTrue(SleepTimer.isExpired(stop + 1L, stop));
        assertEquals(29 * 60_000L, SleepTimer.remainingMs(now + 60_000L, stop));
        assertEquals("过点不得返回负数给 UI", 0L, SleepTimer.remainingMs(stop + 5_000L, stop));
    }

    @Test
    public void offStateNeverFires() {
        assertFalse("0 截止 = 没开定时", SleepTimer.isExpired(9_999_999L, SleepTimer.OFF));
        assertEquals(0L, SleepTimer.remainingMs(1L, SleepTimer.OFF));
        assertEquals(0L, SleepTimer.deadlineFor(1_000L, 0));
        assertEquals("负分钟数也当关", 0L, SleepTimer.deadlineFor(1_000L, -30));
    }

    @Test
    public void presetCycleEndsBackAtOff() {
        int m = SleepTimer.nextPreset(0);
        assertEquals(15, m);
        for (int i = 0; i < SleepTimer.PRESET_MINUTES.length - 1; i++) {
            m = SleepTimer.nextPreset(m);
            assertTrue("中间档必须还在预设里", m > 0);
        }
        assertEquals("走完一圈回到关", 0, SleepTimer.nextPreset(90));
        assertEquals("非预设值直接归零，不卡在中间", 0, SleepTimer.nextPreset(7));
    }

    @Test
    public void formattingNeverSaysZeroMinutes() {
        assertEquals("剩 30 分钟", SleepTimer.formatRemaining(30 * 60_000L));
        assertEquals("最后一分钟走秒", "剩 59 秒", SleepTimer.formatRemaining(59_999L));
        assertEquals("不足一秒也不说 0 秒", "剩 1 秒", SleepTimer.formatRemaining(1L));
        assertEquals("已结束", SleepTimer.formatRemaining(0L));
        assertEquals("已结束", SleepTimer.formatRemaining(-5L));
    }
}
