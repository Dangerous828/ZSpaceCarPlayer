package com.ktools.zspacecarplayer.net;

import org.junit.Assert;
import org.junit.Test;

/**
 * 传输方式裁定的边界钉死测试（v3.1.4）。
 *
 * 判据只有一个：源码率是否高到车机蜂窝链路喂不上。车机上唯一可观测的信号是下载头
 * 领先量 lead，而它由字节率与链路吞吐之差决定，用真机回归撞阈值代价极高，故边界放这里。
 *
 * 参照实测量（2026-09-22 上报复盘）：
 * - 7 声道 44.1kHz PCM「答案」= 616KB/s = 4,928,000 bps，lead 被压死在 2s → 必须转码；
 * - 立体声 44.1kHz PCM = 176KB/s = 1,411,200 bps，lead 能到 47s → 必须保持直传不受扰动。
 */
public class StreamTransportChoiceTest {

    private static final String TEST_SERVER = "http://test.server/music";
    private static final String ITEM_ID = "bde3778eecb8429daf06059e4de26810";

    private JellyfinApiClient clientWithToken(String token) {
        JellyfinApiClient c = JellyfinApiClient.getInstance();
        c.setServerUrl(TEST_SERVER);
        c.setAuthInfo("user-car", token);
        return c;
    }

    // ---------- 阈值边界 ----------

    @Test
    public void testStereoPcmStaysDirectPlay() {
        // 176KB/s 的立体声无损今天 lead 能到 47s，不许被这次改动带进转码路径
        Assert.assertFalse(JellyfinApiClient.shouldUseServerDownmix(1_411_200L));
    }

    @Test
    public void testSevenChannelPcmMustBeDownmixed() {
        Assert.assertTrue(JellyfinApiClient.shouldUseServerDownmix(4_928_000L));
    }

    @Test
    public void testThresholdBoundaryIsExclusive() {
        Assert.assertFalse(JellyfinApiClient.shouldUseServerDownmix(2_000_000L));
        Assert.assertTrue(JellyfinApiClient.shouldUseServerDownmix(2_000_001L));
    }

    @Test
    public void testUnknownBitRateFallsBackToDirectPlay() {
        // 列表里缺 BitRate 时回 0：宁可保持原行为，也不要把所有歌都推去转码
        Assert.assertFalse(JellyfinApiClient.shouldUseServerDownmix(0L));
        Assert.assertFalse(JellyfinApiClient.shouldUseServerDownmix(-1L));
    }

    // ---------- 下混 URL 契约 ----------

    @Test
    public void testDownmixUrlRequestsLosslessStereo() {
        JellyfinApiClient c = clientWithToken("token-xyz");
        String url = c.getDownmixStreamUrl(ITEM_ID);
        Assert.assertEquals(TEST_SERVER + "/Audio/" + ITEM_ID
                + "/stream.flac?api_key=token-xyz&static=false&audioCodec=flac&audioChannels=2", url);
        // 有损编码会改变听感，这条断言防止以后有人图省事换成 mp3/aac
        Assert.assertFalse(url.contains("mp3"));
        Assert.assertFalse(url.contains("aac"));
    }

    @Test
    public void testDownmixUrlEmptyWithoutToken() {
        JellyfinApiClient c = clientWithToken("");
        Assert.assertEquals("", c.getDownmixStreamUrl(ITEM_ID));
        Assert.assertEquals("", c.getStreamUrlForSong(ITEM_ID, "anything"));
    }

    // ---------- 冷启动读缓存时复原决策 ----------

    @Test
    public void testCachedDownmixUrlRestoresDownmixPath() {
        JellyfinApiClient c = clientWithToken("renewed-token");
        String cached = TEST_SERVER + "/Audio/" + ITEM_ID
                + "/stream.flac?api_key=stale-token&static=false&audioCodec=flac&audioChannels=2";
        Assert.assertTrue(JellyfinApiClient.isServerDownmixUrl(cached));
        String url = c.getStreamUrlForSong(ITEM_ID, cached);
        Assert.assertTrue("必须重新签当前 token", url.contains("api_key=renewed-token"));
        Assert.assertTrue("必须留在下混路径", url.contains("audioChannels=2"));
    }

    @Test
    public void testCachedDirectPlayUrlStaysDirectPlayWithFreshToken() {
        JellyfinApiClient c = clientWithToken("renewed-token");
        String cached = TEST_SERVER + "/Audio/" + ITEM_ID + "/stream.mp3?api_key=stale&static=true";
        Assert.assertFalse(JellyfinApiClient.isServerDownmixUrl(cached));
        Assert.assertEquals(TEST_SERVER + "/Audio/" + ITEM_ID
                + "/stream.mp3?api_key=renewed-token&static=true",
                c.getStreamUrlForSong(ITEM_ID, cached));
    }

    @Test
    public void testNullCachedUrlDefaultsToDirectPlay() {
        JellyfinApiClient c = clientWithToken("token-1");
        Assert.assertEquals(TEST_SERVER + "/Audio/" + ITEM_ID + "/stream.mp3?api_key=token-1&static=true",
                c.getStreamUrlForSong(ITEM_ID, null));
    }
}
