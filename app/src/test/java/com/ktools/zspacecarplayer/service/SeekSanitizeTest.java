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
        // 贴近末尾视为播完, 从头播。guard 自 2026-09-12 (#1) 起为 8s:
        // 旧值 3s 小于 UI 的 5s 落库节流窗口, 自然播完的歌必然在库里留下一个
        // [时长-5.5s, 时长-3s) 的「合法」贴尾断点, 下次点它就直接落到曲尾。
        Assert.assertEquals(-1,
                AudioPlayerService.sanitizeSeekMs(song(269000), 266500));
        Assert.assertEquals(-1,
                AudioPlayerService.sanitizeSeekMs(song(269000), 265000));
        Assert.assertEquals(260000,
                AudioPlayerService.sanitizeSeekMs(song(269000), 260000));
    }

    @Test
    public void resumeLeftBySaveThrottleWindowIsDropped() {
        // #1 的实际脏值形态: 最后一次 5s 节流落库停在曲尾前 3~5.5 秒
        Assert.assertEquals(-1, AudioPlayerService.sanitizeSeekMs(song(226000), 222500));
        Assert.assertEquals(-1, AudioPlayerService.sanitizeSeekMs(song(226000), 220600));
    }

    @Test
    public void endGuardMustBeWiderThanProgressSaveWindow() {
        // 回归护栏: guard 一旦小于「落库节流 + 一个 tick」, 贴尾脏断点又会溜进库 (#1)
        Assert.assertTrue(PlaybackStateMachine.isEndGuardWiderThanSaveWindow());
        Assert.assertTrue(PlaybackStateMachine.endOfTrackGuardMs()
                > PlaybackStateMachine.PROGRESS_SAVE_THROTTLE_MS);
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
