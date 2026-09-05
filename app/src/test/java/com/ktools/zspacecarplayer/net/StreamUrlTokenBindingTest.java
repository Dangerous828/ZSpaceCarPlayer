package com.ktools.zspacecarplayer.net;

import org.junit.Assert;
import org.junit.Test;

/**
 * 验证播放起播链路依赖的 Token 绑定契约:
 * AudioPlayerService.startPlayback 只信任 JellyfinApiClient 动态拼接的串流地址,
 * 空Token 返回空串 (触发"等待鉴权后自动起播"), 换新 Token 后 URL 必须重建。
 */
public class StreamUrlTokenBindingTest {

    private static final String TEST_SERVER = "http://test.server/music";
    private static final String ITEM_ID = "ce0f2dd6cd326617841305dd96274b7f";

    @Test
    public void testStreamUrlBuiltWithFreshToken() {
        JellyfinApiClient client = JellyfinApiClient.getInstance();
        client.setServerUrl(TEST_SERVER);
        client.setAuthInfo("user-1", "token-abc");

        Assert.assertTrue(client.hasToken());
        Assert.assertEquals(
                TEST_SERVER + "/Audio/" + ITEM_ID + "/stream.mp3?api_key=token-abc&static=true",
                client.getStreamUrl(ITEM_ID));
    }

    @Test
    public void testEmptyTokenReturnsEmptyUrlForDeferredPlay() {
        JellyfinApiClient client = JellyfinApiClient.getInstance();
        client.setServerUrl(TEST_SERVER);
        client.setAuthInfo("", "");

        Assert.assertFalse(client.hasToken());
        // 空串是 Service 挂起播放、等待鉴权的触发条件, 绝不能回落到旧的持久化 URL
        Assert.assertEquals("", client.getStreamUrl(ITEM_ID));
    }

    @Test
    public void testUrlRegeneratedAfterTokenRenewal() {
        JellyfinApiClient client = JellyfinApiClient.getInstance();
        client.setServerUrl(TEST_SERVER);
        client.setAuthInfo("user-1", "old-token");
        String staleUrl = client.getStreamUrl(ITEM_ID);

        client.setAuthInfo("user-1", "new-token");
        String renewedUrl = client.getStreamUrl(ITEM_ID);

        Assert.assertNotEquals(staleUrl, renewedUrl);
        Assert.assertFalse("旧 Token 的 URL 不允许复用", renewedUrl.contains("old-token"));
        Assert.assertTrue(renewedUrl.contains("api_key=new-token"));
    }

    @Test
    public void testNullItemIdReturnsEmptyUrl() {
        JellyfinApiClient client = JellyfinApiClient.getInstance();
        client.setAuthInfo("user-1", "token-abc");
        Assert.assertEquals("", client.getStreamUrl(null));
        Assert.assertEquals("", client.getStreamUrl(""));
    }
}
