package com.ktools.zspacecarplayer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.ktools.zspacecarplayer.model.SongItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 唯一定序规则的守卫测试。这里断言的不是实现细节, 而是「会不会又长出第二套顺序」。
 */
public class LibraryOrderTest {

    private static SongItem song(String id, String name, int playCount) {
        SongItem s = new SongItem(id, name, "艺人", "专辑", "流派", 1000L, "url-" + id);
        s.setPlayCount(playCount);
        return s;
    }

    @Test
    public void nameTiesAreBrokenByIdSoOrderIsTotal() {
        SongItem a = song("id-b", "富士山下", 0);
        SongItem b = song("id-a", "富士山下", 0);
        List<SongItem> list = new ArrayList<>(Arrays.asList(a, b));
        Collections.sort(list, LibraryOrder.BY_NAME_THEN_ID);
        assertEquals("同名必须靠 id 定先后, 否则两次查询结果可以不一样",
                Arrays.asList("id-a", "id-b"),
                Arrays.asList(list.get(0).getId(), list.get(1).getId()));
        assertNotEquals(0, LibraryOrder.BY_NAME_THEN_ID.compare(a, b));
    }

    @Test
    public void playCountOrderFallsThroughToNameThenId() {
        List<SongItem> list = new ArrayList<>(Arrays.asList(
                song("x1", "安和桥", 3),
                song("x2", "安和桥", 9),
                song("x3", "长城", 9),
                song("x4", "布谷", 1)));
        Collections.sort(list, LibraryOrder.BY_PLAY_COUNT_THEN_NAME);
        // 同为 9 次时按名排: 安(U+5B89) < 长(U+957F)
        assertEquals(Arrays.asList("x2", "x3", "x1", "x4"), ids(list));
    }

    /**
     * SQLite 的 TEXT 序与 Java compareTo 同为码点序。钉住这条, 才可以说
     * 「SQL 的 ORDER BY」和「内存 Comparator」是同一套顺序。
     */
    @Test
    public void codepointBasisMatchesWhatSqliteBinaryCollationGives() {
        assertTrue("数字排在拉丁字母前", "17岁".compareTo("Andy") < 0);
        assertTrue("大写排在小写前", "Andy".compareTo("andy") < 0);
        assertTrue("拉丁字母排在汉字前", "andy".compareTo("出山") < 0);
    }

    @Test
    public void sqlOrderStringsCarryTheIdTiebreak() {
        assertTrue(LibraryOrder.SQL_ORDER_BY, LibraryOrder.SQL_ORDER_BY.contains("id ASC"));
        assertTrue(LibraryOrder.SQL_ORDER_BY_MOST_PLAYED.endsWith(LibraryOrder.SQL_ORDER_BY));
    }

    /** 脏行 (元数据缺失) 不得让排序抛异常: SongItem 把 null 歌名归一成「未知曲目」, id 归一成空串 */
    @Test
    public void nullNameOrIdDoNotThrow() {
        SongItem bare = new SongItem();
        bare.setId(null);
        bare.setName(null);
        assertEquals(0, LibraryOrder.BY_NAME_THEN_ID.compare(bare, bare));
        List<SongItem> list = new ArrayList<>(Arrays.asList(bare, song("z", "安和桥", 0)));
        Collections.sort(list, LibraryOrder.BY_NAME_THEN_ID);
        assertEquals("未(U+672A) 排在 安(U+5B89) 之后", Arrays.asList("z", ""), ids(list));
    }

    private static List<String> ids(List<SongItem> list) {
        List<String> out = new ArrayList<>();
        for (SongItem s : list) out.add(s.getId());
        return out;
    }
}
