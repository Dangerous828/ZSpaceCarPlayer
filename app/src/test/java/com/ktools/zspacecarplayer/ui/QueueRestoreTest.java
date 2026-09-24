package com.ktools.zspacecarplayer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.ktools.zspacecarplayer.model.SongItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 队列重建的契约: 持久化的 id 只决定**成员**, 顺序永远跟随池。
 * 这条契约是「播放跟着左侧列表走」的前提 —— 池就是左侧列表读出来的那份。
 */
public class QueueRestoreTest {

    private static SongItem song(String id, String name) {
        return new SongItem(id, name, "艺人", "专辑", "流派", 1000L, "url-" + id);
    }

    private static List<String> idsOf(List<SongItem> list) {
        List<String> out = new ArrayList<>();
        for (SongItem s : list) out.add(s.getId());
        return out;
    }

    /** 池按唯一顺序排好 (a<b<c)，持久化 id 却是乱序的 */
    private static List<SongItem> pool() {
        return new ArrayList<>(Arrays.asList(song("a", "安和桥"), song("b", "布谷"), song("c", "长城")));
    }

    @Test
    public void orderFollowsPoolNotPersistedIdSequence() {
        List<SongItem> restored = QueueRestore.rebuildFromIds("c\na\nb", pool());
        assertEquals("恢复出来的顺序必须等于池顺序, 不是 id 记录里的先后",
                Arrays.asList("a", "b", "c"), idsOf(restored));
    }

    @Test
    public void songsGoneFromLibraryAreDropped() {
        List<SongItem> restored = QueueRestore.rebuildFromIds("a\nzzz\nc", pool());
        assertEquals(Arrays.asList("a", "c"), idsOf(restored));
    }

    @Test
    public void blankOrMissingInputYieldsEmptyQueue() {
        assertTrue(QueueRestore.rebuildFromIds(null, pool()).isEmpty());
        assertTrue(QueueRestore.rebuildFromIds("   ", pool()).isEmpty());
        assertTrue(QueueRestore.rebuildFromIds("a\n\nb", null).isEmpty());
        assertTrue(QueueRestore.rebuildFromIds("a", new ArrayList<SongItem>()).isEmpty());
    }

    @Test
    public void idListToleratesWhitespaceAndBlanks() {
        List<SongItem> restored = QueueRestore.rebuildFromIds(" b \n\n\nc\n", pool());
        assertEquals(Arrays.asList("b", "c"), idsOf(restored));
    }

    @Test
    public void remapKeepsMembershipAndTakesNewLibraryOrder() {
        // 新库里 c 改名换到最前, 队列成员仍是 {a, c}; 顺序必须跟新库
        List<SongItem> newLibrary = new ArrayList<>(Arrays.asList(
                song("c", "长城"), song("x", "新来的"), song("a", "安和桥")));
        List<SongItem> remapped = QueueRestore.remapToLibrary(
                new ArrayList<>(Arrays.asList(song("a", "安和桥"), song("c", "长城"))), newLibrary);
        assertEquals(Arrays.asList("c", "a"), idsOf(remapped));
    }

    @Test
    public void wholeLibraryQueueIsReplacedByNewLibrary() {
        List<SongItem> wholeQueue = pool();
        List<SongItem> newLibrary = new ArrayList<>(Arrays.asList(song("d", "新专辑")));
        assertEquals(Arrays.asList("d"), idsOf(QueueRestore.remapToLibrary(wholeQueue, newLibrary)));
    }

    @Test
    public void remapNeverEndsWithAnEmptyQueue() {
        // 队列里三首全从库里删了: 兜底整份新库, 不能留空队列 (空队列 = 播无可播)
        List<SongItem> stale = new ArrayList<>(Arrays.asList(song("gone1", "x"), song("gone2", "y")));
        assertEquals(idsOf(pool()), idsOf(QueueRestore.remapToLibrary(stale, pool())));
    }

    @Test
    public void emptyQueueOrEmptyLibraryFallBackSafely() {
        List<SongItem> lib = pool();
        assertEquals(lib, QueueRestore.remapToLibrary(new ArrayList<SongItem>(), lib));
        assertEquals(lib, QueueRestore.remapToLibrary(null, lib));
        List<SongItem> queue = pool();
        assertEquals(queue, QueueRestore.remapToLibrary(queue, null));
        assertEquals(queue, QueueRestore.remapToLibrary(queue, new ArrayList<SongItem>()));
    }
}
