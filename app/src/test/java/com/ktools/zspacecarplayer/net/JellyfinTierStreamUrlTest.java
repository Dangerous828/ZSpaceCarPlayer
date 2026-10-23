package com.ktools.zspacecarplayer.net;

import static org.junit.Assert.assertEquals;

import com.ktools.zspacecarplayer.player.stream.StreamTier;

import org.junit.Test;

/** 验证“高品”选择正确的 Jellyfin item 与容器 URL。 */
public class JellyfinTierStreamUrlTest {

    @Test
    public void mappedAacUsesStaticM4aStream() {
        JellyfinApiClient api = JellyfinApiClient.getInstance();
        String previousUrl = api.getServerUrl();
        String previousUser = api.getUserId();
        String previousToken = api.getAccessToken();
        try {
            api.setServerUrl("https://jellyfin.example.test/");
            api.setAuthInfo("probe-user", "probe-token");

            assertEquals("https://jellyfin.example.test/Audio/aac-copy/stream.m4a"
                            + "?api_key=probe-token&static=true",
                    api.getStreamUrlForTier("source-flac", "", StreamTier.HIGH, "aac-copy"));
            assertEquals("https://jellyfin.example.test/Audio/source-mp3/stream.mp3"
                            + "?api_key=probe-token&static=true",
                    api.getStreamUrlForTier("source-mp3", "", StreamTier.HIGH, "source-mp3"));
            assertEquals("https://jellyfin.example.test/Audio/unmapped/stream.mp3"
                            + "?api_key=probe-token&static=false&maxStreamingBitrate=128000",
                    api.getStreamUrlForTier("unmapped", "", StreamTier.HIGH, ""));
        } finally {
            api.setServerUrl(previousUrl);
            api.setAuthInfo(previousUser, previousToken);
        }
    }
}
