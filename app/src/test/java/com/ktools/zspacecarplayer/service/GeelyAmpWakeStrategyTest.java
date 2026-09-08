package com.ktools.zspacecarplayer.service;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class GeelyAmpWakeStrategyTest {

    private static final class FakeClock implements GeelyAmpWakeStrategy.Clock {
        long nowMs;

        @Override
        public long elapsedRealtime() {
            return nowMs;
        }
    }

    private static final class FakeAmp implements GeelyAmpWakeStrategy.AmpController {
        int maxVolume = 10;
        int currentVolume;
        boolean muted;
        final List<Integer> writes = new ArrayList<Integer>();

        @Override
        public int getMaxMusicVolume() {
            return maxVolume;
        }

        @Override
        public int getMusicVolume() {
            return currentVolume;
        }

        @Override
        public boolean isMusicMuted() {
            return muted;
        }

        @Override
        public void setMusicVolume(int volume) {
            writes.add(volume);
        }
    }

    @Test
    public void masterMuteIsNeverUndoneByWake() {
        FakeClock clock = new FakeClock();
        FakeAmp amp = new FakeAmp();
        // 实车复现形态: ROM 静音键保持音量值但置 mute 标志, setStreamVolume 会隐式解除
        amp.currentVolume = 5;
        amp.muted = true;
        GeelyAmpWakeStrategy strategy = new GeelyAmpWakeStrategy(clock, amp);

        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.SKIPPED, strategy.wakeDetailed(1L));
        Assert.assertTrue(amp.writes.isEmpty());
    }

    @Test
    public void zeroSystemVolumeIsNeverUnmutedOrRaised() {
        FakeClock clock = new FakeClock();
        FakeAmp amp = new FakeAmp();
        amp.currentVolume = 0;
        GeelyAmpWakeStrategy strategy = new GeelyAmpWakeStrategy(clock, amp);

        Assert.assertFalse(strategy.wake(1L));
        Assert.assertTrue(amp.writes.isEmpty());
    }

    @Test
    public void fiveSecondWindowAppliesAcrossDifferentKeys() {
        FakeClock clock = new FakeClock();
        FakeAmp amp = new FakeAmp();
        amp.currentVolume = 5;
        GeelyAmpWakeStrategy strategy = new GeelyAmpWakeStrategy(clock, amp);

        Assert.assertTrue(strategy.wake(10L));
        Assert.assertEquals(2, amp.writes.size());

        // 新 key 在 5s 限频窗口内: 限频拦截, 不写音量
        clock.nowMs = 4999L;
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.RATE_LIMITED, strategy.wakeDetailed(11L));
        Assert.assertEquals(2, amp.writes.size());

        clock.nowMs = 5000L;
        Assert.assertTrue(strategy.wake(11L));
    }

    @Test
    public void sameEpisodeRetryAllowedWithinWindowThenTerminal() {
        FakeClock clock = new FakeClock();
        FakeAmp amp = new FakeAmp();
        amp.currentVolume = 5;
        GeelyAmpWakeStrategy strategy = new GeelyAmpWakeStrategy(clock, amp);

        // 首次唤醒 (probe +1 / 回落 = 2 次写入)
        Assert.assertTrue(strategy.wake(10L));
        Assert.assertEquals(2, amp.writes.size());

        // 同 key 在 5s 限频窗口内: 限频拦截 (可重试), 不写音量
        clock.nowMs = 4999L;
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.RATE_LIMITED, strategy.wakeDetailed(10L));
        Assert.assertEquals(2, amp.writes.size());

        // 5s 后同 key: 处于 20s 重试窗口内 → 允许再次下发 (功放 DSP 晚就绪场景)
        clock.nowMs = 5000L;
        Assert.assertTrue(strategy.wake(10L));
        Assert.assertEquals(4, amp.writes.size());

        // 同 key 超过 20s 重试窗口: 终态拒绝, 不再扰动音量
        clock.nowMs = 30000L;
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.SKIPPED, strategy.wakeDetailed(10L));
        Assert.assertEquals(4, amp.writes.size());

        // 新 episode (新 key) 不受旧 episode 窗口影响
        Assert.assertTrue(strategy.wake(11L));
        Assert.assertEquals(6, amp.writes.size());
    }

    @Test
    public void retryWindowIsMeasuredFromEpisodeFirstNudgeNotLastNudge() {
        FakeClock clock = new FakeClock();
        FakeAmp amp = new FakeAmp();
        amp.currentVolume = 5;
        GeelyAmpWakeStrategy strategy = new GeelyAmpWakeStrategy(clock, amp);

        // 首次唤醒于 t=0; 之后每 5s 成功重试一次, 窗口始终以 t=0 (首次) 起算,
        // 最终必然终止 —— 防止"每次成功重试都把窗口续期"造成无限扰动。
        Assert.assertTrue(strategy.wake(7L));
        int writes = 2;
        for (long t = 5000L; t <= 20000L; t += 5000L) {
            clock.nowMs = t;
            if (t - 0L > GeelyAmpWakeStrategy.RETRY_WINDOW_MS) {
                Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.SKIPPED, strategy.wakeDetailed(7L));
            } else {
                Assert.assertTrue(strategy.wake(7L));
                writes += 2;
            }
        }
        clock.nowMs = 25000L;
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.SKIPPED, strategy.wakeDetailed(7L));
        Assert.assertEquals(writes, amp.writes.size());
        Assert.assertTrue(writes <= 10);
    }

    @Test
    public void fiveSecondLimitIsSharedAcrossStrategyInstances() {
        FakeClock clock = new FakeClock();
        FakeAmp firstAmp = new FakeAmp();
        FakeAmp secondAmp = new FakeAmp();
        firstAmp.currentVolume = 5;
        secondAmp.currentVolume = 5;
        GeelyAmpWakeStrategy.WakeLimiter limiter = new GeelyAmpWakeStrategy.WakeLimiter();
        GeelyAmpWakeStrategy first = new GeelyAmpWakeStrategy(clock, firstAmp, limiter);
        GeelyAmpWakeStrategy second = new GeelyAmpWakeStrategy(clock, secondAmp, limiter);

        Assert.assertTrue(first.wake(1L));
        clock.nowMs = 4999L;
        Assert.assertFalse(second.wake(1L));
        Assert.assertTrue(secondAmp.writes.isEmpty());

        clock.nowMs = 5000L;
        Assert.assertTrue(second.wake(1L));
    }

    @Test
    public void wakeDetailedDistinguishesTerminalSkipFromRetryableRateLimit() {
        FakeClock clock = new FakeClock();
        FakeAmp amp = new FakeAmp();
        amp.currentVolume = 0;
        GeelyAmpWakeStrategy strategy = new GeelyAmpWakeStrategy(clock, amp);

        // 音量为 0: 终态拦截, 调用方不应重试 (红线)
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.SKIPPED, strategy.wakeDetailed(1L));

        // 新 key 正常唤醒
        amp.currentVolume = 5;
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.WOKEN, strategy.wakeDetailed(2L));
        // 同 key 在限频窗口内: 限频拦截 (可重试), 不再是终态
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.RATE_LIMITED, strategy.wakeDetailed(2L));

        // 5s 窗口内的新 key: 限频拦截 (不写音量), 窗口过后允许以同一 key 重试
        clock.nowMs = 4999L;
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.RATE_LIMITED,
                strategy.wakeDetailed(3L));
        Assert.assertEquals(2, amp.writes.size());

        clock.nowMs = 5000L;
        Assert.assertEquals(GeelyAmpWakeStrategy.WakeResult.WOKEN, strategy.wakeDetailed(3L));
        Assert.assertEquals(4, amp.writes.size());
    }
}
