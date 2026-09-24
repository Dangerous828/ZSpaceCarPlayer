package com.ktools.zspacecarplayer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ktools.zspacecarplayer.model.SongItem;

import org.junit.Before;
import org.junit.Test;

/**
 * 传输方式裁定的**接线**测试。
 *
 * StreamTransportChoiceTest 已经把 shouldUseServerFlac / getFlacStreamUrl 这些零件各自
 * 测绿了, 但真车上 67 条上报里没有一条 audioCodec=flac —— 因为 parseSongItem 把裁定算进
 * 局部变量后, 构造 SongItem 时仍传的是直传 URL。零件全对、接缝是断的。
 * 本文件只测接缝: 喂进一条服务端 JSON, 拿出来的 SongItem.streamUrl 必须就是裁定结果。
 */
public class StreamTransportWiringTest {

    private static final String BASE = "http://nas.invalid/music";
    private static final String TOKEN = "probe-token";

    private JellyfinApiClient api;

    @Before
    public void setUp() {
        api = JellyfinApiClient.getInstance();
        api.setServerUrl(BASE);
        api.setAuthInfo("user-1", TOKEN);
    }

    /** 立体声 PCM: 字节率 177KB/s 低于 300KB/s 阈值, 但未压缩容器一律要转 FLAC */
    private static JsonObject stereoWav() {
        return item("id-wav", "出山", "wav", 35_528_136L, 2_003_900_000L);
    }

    private static JsonObject item(String id, String name, String container, long size, long ticks) {
        JsonObject o = new JsonObject();
        o.addProperty("Id", id);
        o.addProperty("Name", name);
        JsonArray artists = new JsonArray();
        artists.add("某歌手");
        o.add("Artists", artists);
        o.addProperty("RunTimeTicks", ticks);
        JsonArray sources = new JsonArray();
        JsonObject src = new JsonObject();
        src.addProperty("Container", container);
        src.addProperty("Size", size);
        sources.add(src);
        o.add("MediaSources", sources);
        return o;
    }

    private SongItem parse(JsonObject o) {
        SongItem s = api.parseSongItem(o);
        assertNotNull("parseSongItem 对合法条目不得返回 null", s);
        return s;
    }

    @Test
    public void pcmContainerReachesSongItemAsServerFlac() {
        SongItem s = parse(stereoWav());
        assertTrue("立体声 WAV 必须落到服务端 FLAC, 实际: " + s.getStreamUrl(),
                s.getStreamUrl().contains("audioCodec=flac"));
        assertTrue(s.getStreamUrl().startsWith(BASE + "/Audio/id-wav/stream.flac"));
        assertFalse("双声道源不该被下混", s.getStreamUrl().contains("audioChannels=2"));
    }

    @Test
    public void highByteRateMultiChannelIsDownmixedIntoSongItem() {
        // 2026-09-22「答案」现场实测: 143,076,468 B / 231.688707s = 617,538 B/s (7ch PCM)
        SongItem s = parse(item("id-7ch", "答案", "wav", 143_076_468L, 2_316_887_070L));
        assertTrue(s.getStreamUrl().contains("audioCodec=flac"));
        assertTrue("超阈值源要下混到两声道, 实际: " + s.getStreamUrl(),
                s.getStreamUrl().contains("audioChannels=2"));
    }

    @Test
    public void lowRateCompressedSourceStaysDirectPlay() {
        // 320kbps mp3 = 40,000 B/s, 远低于阈值且非 PCM 容器
        SongItem s = parse(item("id-mp3", "轻音乐", "mp3", 3_200_000L, 800_000_000L));
        assertEquals(BASE + "/Audio/id-mp3/stream.mp3?api_key=" + TOKEN + "&static=true",
                s.getStreamUrl());
        assertFalse(s.getStreamUrl().contains("flac"));
    }

    /** 起播侧沿用入库时定下的传输方式, 不因为重新取 token 就退回直传 */
    @Test
    public void playbackResolverKeepsTheStoredTransportChoice() {
        SongItem s = parse(stereoWav());
        String played = api.getStreamUrlForSong(s.getId(), s.getStreamUrl());
        assertTrue("起播 URL 必须仍是 FLAC 通道, 实际: " + played,
                played.contains("audioCodec=flac"));
    }
}
