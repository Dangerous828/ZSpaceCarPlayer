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
    public void onlyTwoTiersAndNeitherIsAutomatic() {
        assertEquals("默认必须是无损：降级是人的权利，不是默认行为",
                StreamTier.LOSSLESS, StreamTier.DEFAULT);
        assertEquals("无损 → 流畅", StreamTier.SMOOTH, StreamTier.next(StreamTier.LOSSLESS));
        assertEquals("流畅 → 无损（两档循环，没有第三档『自动』）",
                StreamTier.LOSSLESS, StreamTier.next(StreamTier.SMOOTH));
        assertEquals("越界值一律钳回无损（旧版本残留/手改的偏好不能解锁不存在的路径）",
                StreamTier.LOSSLESS, StreamTier.clamp(7));
        assertEquals(StreamTier.LOSSLESS, StreamTier.clamp(-3));
        assertEquals(StreamTier.SMOOTH, StreamTier.clamp(StreamTier.SMOOTH));
    }

    /** 文案里的码率必须是实测值。写 128 就是假广告，也不许出现"自动"这种承诺。 */
    @Test
    public void labelsTellMeasuredBitrateNotTheRequestParam() {
        assertFalse("流畅档文案不得谎报 128", StreamTier.describe(StreamTier.SMOOTH).contains("128"));
        assertTrue("必须写实测的 256kbps", StreamTier.describe(StreamTier.SMOOTH).contains("256"));
        assertTrue(StreamTier.describe(StreamTier.LOSSLESS).contains("100~151"));
        assertFalse(StreamTier.label(StreamTier.LOSSLESS).contains("自动"));
        assertFalse(StreamTier.label(StreamTier.SMOOTH).contains("自动"));
        assertEquals("无损", StreamTier.label(StreamTier.LOSSLESS));
        assertEquals("流畅", StreamTier.label(StreamTier.SMOOTH));
    }
}
