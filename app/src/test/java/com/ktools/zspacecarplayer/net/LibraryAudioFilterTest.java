package com.ktools.zspacecarplayer.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ktools.zspacecarplayer.model.SongItem;

import org.junit.Test;

/** 见 {@link JellyfinApiClient#hasVideoStream}：判据是流类型，不是码率；缺失一律放行。 */
public class LibraryAudioFilterTest {

    private static JsonObject item(String mediaSourcesJson) {
        JsonObject o = new JsonObject();
        o.addProperty("Id", "f95c5458ea81cb686ff66e0ac9de3b61");
        o.addProperty("Name", "测试曲");
        o.addProperty("RunTimeTicks", 1818120000L);
        if (mediaSourcesJson != null) {
            o.add("MediaSources", new JsonParser().parse(mediaSourcesJson).getAsJsonArray());
        }
        return o;
    }

    @Test
    public void videoStreamIsTheOnlyThingThatGetsFiltered() {
        // 真车曲库实测形状：4K60 帧汤姆猫 = h264 视频 + aac 音频
        assertTrue(JellyfinApiClient.hasVideoStream(item(
                "[{\"Container\":\"mp4\",\"Size\":360000000,\"MediaStreams\":[{\"Type\":\"Video\",\"Codec\":\"h264\"},{\"Type\":\"Audio\",\"Codec\":\"aac\"}]}]")));
        // 带封面的正常音乐：EmbeddedImage 不是视频，绝不能误伤
        assertFalse(JellyfinApiClient.hasVideoStream(item(
                "[{\"Container\":\"flac\",\"Size\":30349615,\"MediaStreams\":[{\"Type\":\"Audio\",\"Codec\":\"flac\"},{\"Type\":\"EmbeddedImage\"}]}]")));
        // 只有音频流
        assertFalse(JellyfinApiClient.hasVideoStream(item(
                "[{\"Container\":\"mp3\",\"Size\":12000000,\"MediaStreams\":[{\"Type\":\"Audio\",\"Codec\":\"mp3\"}]}]")));
    }

    /** 保守放行：字段缺失/结构不认识/没有流信息，一律当"可能是歌"留着。 */
    @Test
    public void unknownStructureIsNeverFiltered() {
        assertFalse("整段 MediaSources 缺失", JellyfinApiClient.hasVideoStream(item(null)));
        assertFalse(JellyfinApiClient.hasVideoStream(item("[]")));
        assertFalse(JellyfinApiClient.hasVideoStream(item("[{}]")));
        assertFalse(JellyfinApiClient.hasVideoStream(item("[{\"MediaStreams\":[]}]")));
        assertFalse(JellyfinApiClient.hasVideoStream(item("[{\"MediaStreams\":[{}]}]")));
        assertFalse(JellyfinApiClient.hasVideoStream(item("[{\"MediaStreams\":[{\"Type\":\"Audio\"}]}]")));
        assertFalse(JellyfinApiClient.hasVideoStream(null));
    }

    /** 滤除必须真的发生在条目解析出口，且合法音频条目不得被误杀。 */
    @Test
    public void parseSongItemSkipsVideoEntriesAndKeepsAudioEntries() {
        JellyfinApiClient api = JellyfinApiClient.getInstance();
        api.resetSkippedVideoItems();

        JsonObject flac = item("[{\"Container\":\"flac\",\"Size\":30349615,"
                + "\"MediaStreams\":[{\"Type\":\"Audio\",\"Codec\":\"flac\"}]}]");
        assertNotNull("正常 flac 必须留下来", api.parseSongItem(flac));

        JsonObject mp4 = item("[{\"Container\":\"mp4\",\"Size\":360000000,"
                + "\"MediaStreams\":[{\"Type\":\"Video\",\"Codec\":\"h264\"},{\"Type\":\"Audio\",\"Codec\":\"aac\"}]}]");
        assertNull("带视频流的必须被拒", api.parseSongItem(mp4));
        assertTrue("被滤掉的条数要能读出来，UI 才有话可说", api.getSkippedVideoItems() >= 1);
    }

    @Test
    public void songItemStillConstructsForAudioOnly() {
        SongItem s = JellyfinApiClient.getInstance().parseSongItem(item(
                "[{\"Container\":\"wav\",\"Size\":38934625,"
                        + "\"MediaStreams\":[{\"Type\":\"Audio\",\"Codec\":\"pcm_s16le\",\"Channels\":2}]}]"));
        assertNotNull(s);
        assertNotNull(s.getId());
    }
}
