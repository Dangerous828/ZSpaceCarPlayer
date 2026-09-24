package com.ktools.zspacecarplayer.service;

import com.ktools.zspacecarplayer.model.SongItem;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PlaylistSyncTest {

    @Test
    public void testUpdatePlaylistIndexMatching() {
        List<SongItem> oldList = new ArrayList<>();
        SongItem s1 = new SongItem("1", "Song 1", "Artist", "Album", "Pop", 1000, "url1");
        SongItem s2 = new SongItem("2", "Song 2", "Artist", "Album", "Pop", 2000, "url2");
        SongItem s3 = new SongItem("3", "Song 3", "Artist", "Album", "Pop", 3000, "url3");
        oldList.add(s1);
        oldList.add(s2);
        oldList.add(s3);

        SongItem currentPlaying = s2; // index 1

        // 场景 A: 服务器更新后，顺序改变或增加歌曲，但当前播放歌曲存在
        List<SongItem> newListA = new ArrayList<>();
        SongItem s0 = new SongItem("0", "Song 0", "Artist", "Album", "Pop", 500, "url0");
        newListA.add(s0);
        newListA.add(s1);
        newListA.add(s2); // 此时 index 应更新为 2
        newListA.add(s3);

        int newIndexA = newListA.indexOf(currentPlaying);
        Assert.assertEquals(2, newIndexA);

        // 场景 B: 当前歌曲在服务器被删除了
        List<SongItem> newListB = new ArrayList<>();
        newListB.add(s1);
        newListB.add(s3);

        int newIndexB = (newListB.contains(currentPlaying)) ? newListB.indexOf(currentPlaying) : -1;
        Assert.assertEquals(-1, newIndexB);
    }
}
