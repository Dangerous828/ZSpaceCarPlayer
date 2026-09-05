package com.ktools.zspacecarplayer.model;

import org.junit.Assert;
import org.junit.Test;

public class SongItemTest {

    @Test
    public void testSongItemEqualsAndHashCode() {
        SongItem song1 = new SongItem("id_101", "晴天", "周杰伦", "叶惠美", "流行", 269000, "url1", "cover1");
        SongItem song2 = new SongItem("id_101", "晴天 (更新版)", "周杰伦", "叶惠美", "流行", 269000, "url2", "cover2");
        SongItem song3 = new SongItem("id_102", "七里香", "周杰伦", "七里香", "流行", 299000, "url3", "cover3");

        // 验证 ID 相同即可判定 equals 为 true
        Assert.assertEquals(song1, song2);
        Assert.assertEquals(song1.hashCode(), song2.hashCode());

        // 验证 ID 不同则不相等
        Assert.assertNotEquals(song1, song3);
    }
}
