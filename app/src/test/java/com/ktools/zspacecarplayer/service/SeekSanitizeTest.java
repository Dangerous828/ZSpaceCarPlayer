package com.ktools.zspacecarplayer.service;

import com.ktools.zspacecarplayer.model.SongItem;

import org.junit.Assert;
import org.junit.Test;

/**
 * 2026-09-09 实车定位: song_progress 存过超长断点 (234s 断点配 226s 歌曲),
 * 越界 seek 会让进度条瞬跳末尾并在文件尾假 COMPLETED 乱切歌。
 * 验证起播 seek 目标的钳制规则。
 */
public class SeekSanitizeTest {

    private static SongItem song(long durationMs) {
        return new SongItem("id_x", "晴天", "周杰伦", "叶惠美", "流行", durationMs, "url", "cover");
    }

    @Test
    public void normalResumeWithinDurationPassesThrough() {
        Assert.assertEquals(100000,
                AudioPlayerService.sanitizeSeekMs(song(269000), 100000));
    }

    @Test
    public void negativeResumeStaysNegative() {
        Assert.assertEquals(-1, AudioPlayerService.sanitizeSeekMs(song(269000), -1));
    }

    @Test
    public void resumeBeyondDurationResetsToStart() {
        // 234s 断点配 226s 歌 (实车 All Rise 案例数字)
        Assert.assertEquals(-1,
                AudioPlayerService.sanitizeSeekMs(song(226000), 234288));
    }

    @Test
    public void resumeNearTailResetsToStart() {
        // 贴近末尾 (<=3s 余量) 视为播完, 从头播
        Assert.assertEquals(-1,
                AudioPlayerService.sanitizeSeekMs(song(269000), 266500));
        Assert.assertEquals(265000,
                AudioPlayerService.sanitizeSeekMs(song(269000), 265000));
    }

    @Test
    public void missingDurationMetadataPassesThrough() {
        // 时长元数据缺失时保守放行 (Jellyfin 曲目基本都带 RunTimeTicks)
        Assert.assertEquals(5000,
                AudioPlayerService.sanitizeSeekMs(song(0), 5000));
    }

    @Test
    public void nullSongPassesThrough() {
        Assert.assertEquals(5000, AudioPlayerService.sanitizeSeekMs(null, 5000));
    }
}
