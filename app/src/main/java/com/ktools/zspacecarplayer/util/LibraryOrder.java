package com.ktools.zspacecarplayer.util;

import com.ktools.zspacecarplayer.model.SongItem;

import java.util.Comparator;

/**
 * 全应用唯一的列表定序规则。
 *
 * 同一条规则有两种拼法: 交给 SQLite 的 ORDER BY 串, 和内存里子集 (分类过滤 / 播放最多)
 * 用的 Java 比较器。两者必须放在一起 —— 分开写就会漂移, 而一旦漂移, 「左侧列表」和
 * 「播放队列」就是两套顺序, 表现成实车反馈的「播放不跟列表走」。
 *
 * 为什么不用服务端 (Jellyfin) 返回的顺序: 实测它会把 SortName 未赋值的条目无条件顶到
 * 最前 (全库 79/799 首, 单个歌单最多 34/97 首), 且这一段内部还是引擎自定次序,
 * 不是任何用户能预期或解释的规则。
 *
 * 为什么必须有 id 兜底: 库里有 34 组重名共 73 首 (光「富士山下」就 5 首)。只比 name
 * 时这些行彼此颠倒的先后由排序算法决定, 两次查询之间可以不一样。
 *
 * SQLite 的 TEXT 序与 Java String.compareTo 同为 UTF-16 码点序, 所以两种拼法同序。
 */
public final class LibraryOrder {

    /** 交给我 SQLite 的 songs 表: 全库 / 收藏 / 分类的读侧顺序 */
    public static final String SQL_ORDER_BY = "name ASC, id ASC";

    /** 「播放最多」的读侧顺序 */
    public static final String SQL_ORDER_BY_MOST_PLAYED =
            "play_count DESC, " + SQL_ORDER_BY;

    public static final Comparator<SongItem> BY_NAME_THEN_ID = new Comparator<SongItem>() {
        @Override
        public int compare(SongItem a, SongItem b) {
            int d = nullSafe(a.getName()).compareTo(nullSafe(b.getName()));
            if (d != 0) return d;
            return nullSafe(a.getId()).compareTo(nullSafe(b.getId()));
        }
    };

    public static final Comparator<SongItem> BY_PLAY_COUNT_THEN_NAME = new Comparator<SongItem>() {
        @Override
        public int compare(SongItem a, SongItem b) {
            int d = Integer.compare(b.getPlayCount(), a.getPlayCount());
            return d != 0 ? d : BY_NAME_THEN_ID.compare(a, b);
        }
    };

    private LibraryOrder() {}

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
