package com.ktools.zspacecarplayer.net;

import org.junit.Assert;
import org.junit.Test;

/**
 * 传输方式裁定的边界钉死测试（v3.1.5）。
 *
 * 判据只有两件事：这源的字节率车机链路喂不喂得起，以及要不要顺手把声道压到两个。
 * 车机上唯一可观测的是下载头领先量 lead，而它由字节率与链路吞吐之差决定，用真机回归
 * 撞阈值代价极高，故边界放这里。
 *
 * 参照实测量（本机 Jellyfin 列表接口真实返回）：
 * - 2026-09-22「答案」Size=143,076,468 / RunTimeTicks=2,316,887,070 → 617,538 B/s，
 *   lead 被压死在 2s；
 * - 2026-09-23「出山」立体声 44.1k/16bit PCM = 176,400 B/s，17 秒内缓冲只涨 1%、
 *   lead 卡在 10s —— 证明立体声 PCM 直传同样没有余量，PCM 系容器不分声道一律转；
 * - 链路有效吞吐实测约 250~300KB/s。
 */
public class StreamTransportChoiceTest {

    private static final String TEST_SERVER = "http://test.server/music";
    private static final String ITEM_ID = "bde3778eecb8429daf06059e4de26810";

    private static final long STEREO_PCM_BPS = 176_400L;   // 44.1kHz/2ch/16bit
    private static final long SEVEN_CH_PCM_BPS = 617_538L; // 实测「答案」Size/RunTimeTicks
    private static final long FLAC_STEREO_BPS = 110_000L;

    private JellyfinApiClient clientWithToken(String token) {
        JellyfinApiClient c = JellyfinApiClient.getInstance();
        c.setServerUrl(TEST_SERVER);
        c.setAuthInfo("user-car", token);
        return c;
    }

    // ---------- 字节率来源：MediaSources 不带 BitRate，只能用 Size/时长 ----------

    @Test
    public void testBytesPerSecFromRealSample() {
        long bps = JellyfinApiClient.sourceBytesPerSec(143_076_468L, 2_316_887_070L);
        Assert.assertEquals(617_538L, bps);
    }

    @Test
    public void testBytesPerSecReturnsMinusOneWhenMetadataMissing() {
        Assert.assertEquals(-1L, JellyfinApiClient.sourceBytesPerSec(0L, 2_316_887_070L));
        Assert.assertEquals(-1L, JellyfinApiClient.sourceBytesPerSec(143_076_468L, 0L));
        Assert.assertEquals(-1L, JellyfinApiClient.sourceBytesPerSec(-5L, -5L));
    }

    // ---------- PCM 系容器：不分声道数一律转 ----------

    @Test
    public void testStereoWavMustBeTranscodedNotDirectPlayed() {
        // 这一条就是 v3.1.5 的来由：立体声 PCM 直传实测也没有余量
        Assert.assertTrue(JellyfinApiClient.shouldUseServerFlac(STEREO_PCM_BPS, "wav"));
    }

    @Test
    public void testUncompressedContainersAllTranscoded() {
        String[] pcm = {"wav", "WAVE", "PCM", "aiff", "aifc", "raw", "au", "aif", "sun"};
        for (String c : pcm) {
            Assert.assertTrue("container " + c + " 必须转 FLAC",
                    JellyfinApiClient.shouldUseServerFlac(100_000L, c));
        }
    }

    @Test
    public void testMultiValueContainerFieldRecognized() {
        // Jellyfin 对同名多扩展会回 "wav,flac" 形式
        Assert.assertTrue(JellyfinApiClient.shouldUseServerFlac(100_000L, "wav,flac"));
    }

    @Test
    public void testAlreadyCompressedLowBitRateStaysDirectPlay() {
        Assert.assertFalse(JellyfinApiClient.shouldUseServerFlac(FLAC_STEREO_BPS, "flac"));
        Assert.assertFalse(JellyfinApiClient.shouldUseServerFlac(40_000L, "mp3"));
        Assert.assertFalse(JellyfinApiClient.shouldUseServerFlac(FLAC_STEREO_BPS, "ape"));
    }

    @Test
    public void testHighByteRateNonPcmAlsoTranscoded() {
        Assert.assertTrue(JellyfinApiClient.shouldUseServerFlac(SEVEN_CH_PCM_BPS, "wav"));
        Assert.assertTrue(JellyfinApiClient.shouldUseServerFlac(300_001L, "flac"));
    }

    @Test
    public void testThresholdBoundaryIsExclusive() {
        Assert.assertFalse(JellyfinApiClient.shouldUseServerFlac(300_000L, "flac"));
        Assert.assertTrue(JellyfinApiClient.shouldUseServerFlac(300_001L, "flac"));
    }

    @Test
    public void testMissingContainerAndSizeFallBackToDirectPlay() {
        // 元数据缺失时宁可保持原行为，也不要把全库推去转码
        Assert.assertFalse(JellyfinApiClient.shouldUseServerFlac(-1L, null));
        Assert.assertFalse(JellyfinApiClient.shouldUseServerFlac(STEREO_PCM_BPS, null));
        Assert.assertFalse(JellyfinApiClient.shouldUseServerFlac(-1L, ""));
    }

    // ---------- 下混裁定与"是否转码"分离 ----------

    @Test
    public void testStereoPcmTranscodedButNotDownmixed() {
        Assert.assertTrue(JellyfinApiClient.shouldUseServerFlac(STEREO_PCM_BPS, "wav"));
        Assert.assertFalse("立体声不该被下混", JellyfinApiClient.shouldDownmixToStereo(STEREO_PCM_BPS));
    }

    @Test
    public void testMultiChannelPcmTranscodedAndDownmixed() {
        Assert.assertTrue(JellyfinApiClient.shouldUseServerFlac(SEVEN_CH_PCM_BPS, "wav"));
        Assert.assertTrue(JellyfinApiClient.shouldDownmixToStereo(SEVEN_CH_PCM_BPS));
    }

    // ---------- FLAC URL 契约 ----------

    @Test
    public void testFlacUrlForStereoOmitsChannelParam() {
        JellyfinApiClient c = clientWithToken("token-xyz");
        Assert.assertEquals(TEST_SERVER + "/Audio/" + ITEM_ID
                        + "/stream.flac?api_key=token-xyz&static=false&audioCodec=flac",
                c.getFlacStreamUrl(ITEM_ID, false));
    }

    @Test
    public void testFlacUrlForMultiChannelAddsDownmix() {
        JellyfinApiClient c = clientWithToken("token-xyz");
        Assert.assertEquals(TEST_SERVER + "/Audio/" + ITEM_ID
                        + "/stream.flac?api_key=token-xyz&static=false&audioCodec=flac&audioChannels=2",
                c.getFlacStreamUrl(ITEM_ID, true));
    }

    @Test
    public void testFlacUrlNeverUsesLossyCodec() {
        // 有损编码会改变听感；这条断言防止以后有人图省事换成 mp3/aac
        JellyfinApiClient c = clientWithToken("t");
        for (boolean dm : new boolean[]{true, false}) {
            String u = c.getFlacStreamUrl(ITEM_ID, dm);
            Assert.assertFalse(u.contains("mp3"));
            Assert.assertFalse(u.contains("aac"));
            Assert.assertTrue(u.contains("audioCodec=flac"));
        }
    }

    @Test
    public void testEmptyTokenYieldsEmptyUrls() {
        JellyfinApiClient c = clientWithToken("");
        Assert.assertEquals("", c.getFlacStreamUrl(ITEM_ID, true));
        Assert.assertEquals("", c.getStreamUrlForSong(ITEM_ID, "anything"));
    }

    // ---------- 冷启动读缓存时复原决策 ----------

    @Test
    public void testCachedStereoFlacUrlRestoresWithoutDownmix() {
        JellyfinApiClient c = clientWithToken("renewed");
        String cached = TEST_SERVER + "/Audio/" + ITEM_ID
                + "/stream.flac?api_key=stale&static=false&audioCodec=flac";
        Assert.assertTrue(JellyfinApiClient.isServerFlacUrl(cached));
        String url = c.getStreamUrlForSong(ITEM_ID, cached);
        Assert.assertTrue("必须重新签当前 token", url.contains("api_key=renewed"));
        Assert.assertFalse("立体声不该被强行下混", url.contains("audioChannels=2"));
    }

    @Test
    public void testCachedDownmixUrlKeepsDownmix() {
        JellyfinApiClient c = clientWithToken("renewed");
        String cached = TEST_SERVER + "/Audio/" + ITEM_ID
                + "/stream.flac?api_key=stale&static=false&audioCodec=flac&audioChannels=2";
        Assert.assertTrue(JellyfinApiClient.isServerFlacUrl(cached));
        String url = c.getStreamUrlForSong(ITEM_ID, cached);
        Assert.assertTrue(url.contains("audioChannels=2"));
        Assert.assertTrue(url.contains("api_key=renewed"));
    }

    @Test
    public void testCachedDirectPlayUrlStaysDirectWithFreshToken() {
        JellyfinApiClient c = clientWithToken("renewed");
        String cached = TEST_SERVER + "/Audio/" + ITEM_ID + "/stream.mp3?api_key=stale&static=true";
        Assert.assertFalse(JellyfinApiClient.isServerFlacUrl(cached));
        Assert.assertEquals(TEST_SERVER + "/Audio/" + ITEM_ID
                        + "/stream.mp3?api_key=renewed&static=true",
                c.getStreamUrlForSong(ITEM_ID, cached));
    }

    @Test
    public void testNullCachedUrlDefaultsToDirectPlay() {
        JellyfinApiClient c = clientWithToken("token-1");
        Assert.assertEquals(TEST_SERVER + "/Audio/" + ITEM_ID + "/stream.mp3?api_key=token-1&static=true",
                c.getStreamUrlForSong(ITEM_ID, null));
    }
}
