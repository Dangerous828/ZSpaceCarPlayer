package com.ktools.zspacecarplayer.crash;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * 面包屑是车机崩溃唯一的现场还原手段（native SIGSEGV 与主线程卡死都拿不到 Java 栈），
 * 环形回绕后顺序一旦错乱，报告里的因果链就是反的，比没有更糟。
 */
public class CrashBreadcrumbsTest {

    @Before
    public void setUp() {
        CrashBreadcrumbs.clear();
    }

    @Test
    public void snapshotReturnsEntriesInChronologicalOrder() {
        CrashBreadcrumbs.record("ui", "category 粤语金曲");
        CrashBreadcrumbs.record("ui", "song clicked");
        CrashBreadcrumbs.record("play", "startPlayback");

        List<String> snap = CrashBreadcrumbs.snapshot();
        Assert.assertEquals(3, snap.size());
        Assert.assertTrue(snap.get(0).contains("category 粤语金曲"));
        Assert.assertTrue(snap.get(1).contains("song clicked"));
        Assert.assertTrue(snap.get(2).contains("startPlayback"));
    }

    @Test
    public void entryCarriesTagAndTimestamp() {
        long before = System.currentTimeMillis();
        CrashBreadcrumbs.record("v3", "doPrepare begin");
        long after = System.currentTimeMillis();

        String entry = CrashBreadcrumbs.snapshot().get(0);
        // 格式: "<wallMs> [tag] message"
        Assert.assertTrue(entry.contains("[v3] doPrepare begin"));
        long ts = Long.parseLong(entry.substring(0, entry.indexOf(' ')));
        Assert.assertTrue(ts >= before && ts <= after);
    }

    @Test
    public void wrapAroundKeepsNewestWindowAndOrder() {
        int total = CrashBreadcrumbs.MAX_ENTRIES + 17;
        for (int i = 0; i < total; i++) {
            CrashBreadcrumbs.record("t", "e" + i);
        }

        List<String> snap = CrashBreadcrumbs.snapshot();
        Assert.assertEquals(CrashBreadcrumbs.MAX_ENTRIES, snap.size());
        // 最旧的 17 条应已被覆盖，第一条是 e17，最后一条是最新写入的
        Assert.assertTrue(snap.get(0).endsWith("e17"));
        Assert.assertTrue(snap.get(snap.size() - 1).endsWith("e" + (total - 1)));
        for (int i = 1; i < snap.size(); i++) {
            Assert.assertTrue("顺序错乱 @" + i,
                    seqOf(snap.get(i - 1)) < seqOf(snap.get(i)));
        }
    }

    @Test
    public void wrapAroundTwiceStillYieldsExactlyMaxEntries() {
        for (int i = 0; i < CrashBreadcrumbs.MAX_ENTRIES * 2 + 3; i++) {
            CrashBreadcrumbs.record("t", "x" + i);
        }
        List<String> snap = CrashBreadcrumbs.snapshot();
        Assert.assertEquals(CrashBreadcrumbs.MAX_ENTRIES, snap.size());
        Assert.assertTrue(snap.get(snap.size() - 1).endsWith("x" + (CrashBreadcrumbs.MAX_ENTRIES * 2 + 2)));
    }

    @Test
    public void exactlyMaxEntriesDoesNotWrap() {
        for (int i = 0; i < CrashBreadcrumbs.MAX_ENTRIES; i++) {
            CrashBreadcrumbs.record("t", "e" + i);
        }
        List<String> snap = CrashBreadcrumbs.snapshot();
        Assert.assertEquals(CrashBreadcrumbs.MAX_ENTRIES, snap.size());
        Assert.assertTrue(snap.get(0).endsWith("e0"));
    }

    @Test
    public void longMessageIsTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CrashBreadcrumbs.MAX_MSG_LEN + 500; i++) {
            sb.append('a');
        }
        CrashBreadcrumbs.record("big", sb.toString());

        String entry = CrashBreadcrumbs.snapshot().get(0);
        int msgStart = entry.indexOf("] ") + 2;
        String msg = entry.substring(msgStart);
        Assert.assertEquals(CrashBreadcrumbs.MAX_MSG_LEN + 1, msg.length()); // +1 是省略号
        Assert.assertTrue(msg.endsWith("…"));
    }

    @Test
    public void messageAtExactLimitIsKeptVerbatim() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CrashBreadcrumbs.MAX_MSG_LEN; i++) {
            sb.append('b');
        }
        CrashBreadcrumbs.record("exact", sb.toString());
        String entry = CrashBreadcrumbs.snapshot().get(0);
        Assert.assertFalse(entry.endsWith("…"));
    }

    @Test
    public void nullTagOrMessageIsIgnored() {
        CrashBreadcrumbs.record(null, "no tag");
        CrashBreadcrumbs.record("no msg", null);
        Assert.assertTrue(CrashBreadcrumbs.snapshot().isEmpty());
    }

    @Test
    public void clearEmptiesBuffer() {
        CrashBreadcrumbs.record("a", "1");
        CrashBreadcrumbs.clear();
        Assert.assertTrue(CrashBreadcrumbs.snapshot().isEmpty());
        // 清空后写入指针复位：新记录必须排在最前，而不是接着旧位置
        CrashBreadcrumbs.record("a", "after clear");
        Assert.assertEquals(1, CrashBreadcrumbs.snapshot().size());
    }

    /** 从 "e17" / "x130" 这类消息里取出序号 */
    private static int seqOf(String entry) {
        String msg = entry.substring(entry.indexOf("] ") + 2);
        return Integer.parseInt(msg.substring(1));
    }
}
