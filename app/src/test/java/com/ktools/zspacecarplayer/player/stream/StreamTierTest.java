package com.ktools.zspacecarplayer.player.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 档位这一层的价值全在"没有自动"和"数字是真的"这两件事上，所以两条都钉死：
 * 前者防有人日后把"根据网络自动切档"塞回来（车主 2026-10-08 明确否决），
 * 后者防文案照抄请求参数上的 128——实测那台 Jellyfin 忽略码率参数，固定给 256kbps。
 */
public class StreamTierTest {

    @Test
    public void threeManualTiersKeepExistingPreferenceValues() {
        assertEquals("默认必须是无损", StreamTier.LOSSLESS, StreamTier.DEFAULT);
        assertEquals("无损 → 高品", StreamTier.HIGH, StreamTier.next(StreamTier.LOSSLESS));
        assertEquals("高品 → 流畅", StreamTier.SMOOTH, StreamTier.next(StreamTier.HIGH));
        assertEquals("流畅 → 无损", StreamTier.LOSSLESS, StreamTier.next(StreamTier.SMOOTH));
        assertEquals("旧版本偏好 1 仍代表流畅", StreamTier.SMOOTH, StreamTier.clamp(1));
        assertEquals(StreamTier.HIGH, StreamTier.clamp(StreamTier.HIGH));
        assertEquals("未知值回到无损", StreamTier.LOSSLESS, StreamTier.clamp(7));
        assertEquals(StreamTier.LOSSLESS, StreamTier.clamp(-3));
    }

    @Test
    public void labelsDescribeMeasuredBitratesAndNeverClaimAutomaticSwitching() {
        assertFalse(StreamTier.describe(StreamTier.SMOOTH).contains("128"));
        assertTrue("流畅必须写实测的 256kbps", StreamTier.describe(StreamTier.SMOOTH).contains("256"));
        assertTrue("高品档必须标出 AAC 目标码率", StreamTier.describe(StreamTier.HIGH).contains("512kbps"));
        assertTrue("高品档预估带宽按 512kbps 目标计算", StreamTier.describe(StreamTier.HIGH).contains("65KB/s"));
        assertTrue(StreamTier.describe(StreamTier.LOSSLESS).contains("100~151"));
        assertFalse(StreamTier.label(StreamTier.LOSSLESS).contains("自动"));
        assertFalse(StreamTier.label(StreamTier.HIGH).contains("自动"));
        assertFalse(StreamTier.label(StreamTier.SMOOTH).contains("自动"));
        assertEquals("无损", StreamTier.label(StreamTier.LOSSLESS));
        assertEquals("高品", StreamTier.label(StreamTier.HIGH));
        assertEquals("流畅", StreamTier.label(StreamTier.SMOOTH));
    }
}
