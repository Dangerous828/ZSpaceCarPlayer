package com.ktools.zspacecarplayer.player.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.TreeMap;

/** 见 {@link StreamDiskCache}：缓存只允许"命中就不重连"，绝不允许把没下过的字节当数据交出去。 */
public class StreamDiskCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String ID = "f95c5458ea81cb686ff66e0ac9de3b61";
    private static final String URL_A =
            "http://jarvis.kentonnie.top/music/Audio/" + ID + "/stream.mp3?api_key=AAA&static=true";
    private static final String URL_B =
            "http://jarvis.kentonnie.top/music/Audio/" + ID + "/stream.mp3?api_key=BBB&static=true";

    /** 键里绝不能有 api_key：token 每次登录都换，含它的话缓存永远命不中。 */
    @Test
    public void keyIgnoresRotatingApiButTracksContentLength() {
        assertEquals(StreamDiskCache.cacheKeyFor(URL_A, 18_869_283L),
                StreamDiskCache.cacheKeyFor(URL_B, 18_869_283L));
        assertFalse("总长变了就是另一份内容（我们把 wav 原地换成 flac 就是这个形状）",
                StreamDiskCache.cacheKeyFor(URL_A, 18_869_283L)
                        .equals(StreamDiskCache.cacheKeyFor(URL_A, 18_869_284L)));
        assertEquals("键直接用 itemId+总长，读得出来也好排查",
                ID + "_18869283", StreamDiskCache.cacheKeyFor(URL_A, 18_869_283L));
    }

    @Test
    public void cacheKeyFallsBackWhenNoAudioId() {
        String k = StreamDiskCache.cacheKeyFor("http://x/other/path?api_key=Z", 1000L);
        assertFalse(k.contains("api_key=Z"));
        assertFalse(k.contains("?"));
        assertNull(StreamDiskCache.cacheKeyFor(null, 1000L));
        assertNull("总长未知不给键", StreamDiskCache.cacheKeyFor(URL_A, 0L));
    }

    @Test
    public void onlyCacheThingsWeCanJudge() {
        assertFalse("chunked 转码流没有总长：不进键也判不出洞，一律不缓存",
                StreamDiskCache.shouldCache(-1L, 1024L));
        assertFalse(StreamDiskCache.shouldCache(0L, 1024L));
        assertTrue(StreamDiskCache.shouldCache(1024L, 1024L));
        assertFalse("超过单文件上限就不缓存（360MB 的巨型文件会把常听歌挤光）",
                StreamDiskCache.shouldCache(1025L, 1024L));
        assertTrue("本库正常音频（44MB）必须在 100MB 单文件上限内",
                StreamDiskCache.shouldCache(44L * 1024 * 1024,
                        BufferingPolicy.DISK_CACHE_MAX_FILE_BYTES));
        assertFalse("360MB 那类必须被拒", StreamDiskCache.shouldCache(360L * 1024 * 1024,
                BufferingPolicy.DISK_CACHE_MAX_FILE_BYTES));
    }

    /** 区间表：合并必须保守——宁可留洞，绝不能把两段之间的洞说成连续。 */
    @Test
    public void runsMergeOnlyWhenActuallyAdjacent() {
        TreeMap<Long, Long> r = new TreeMap<Long, Long>();
        StreamDiskCache.addRun(r, 0L, 1000L);
        StreamDiskCache.addRun(r, 1000L, 2000L);
        assertEquals("首尾相接必须合成一段", 1, r.size());
        assertEquals(Long.valueOf(2000L), r.get(0L));

        StreamDiskCache.addRun(r, 2500L, 3000L);
        assertEquals("中间有洞就是两段", 2, r.size());
        // 契约：不在任何连续段内就返回 position 本身（has() 靠它判"要的长度不够"）
        assertEquals("洞里的位置不算连续", 2100L, StreamDiskCache.contiguousEnd(r, 2100L));
        assertEquals("贴着段尾也不算", 2000L, StreamDiskCache.contiguousEnd(r, 2000L));
        assertEquals(3000L, StreamDiskCache.contiguousEnd(r, 2600L));

        StreamDiskCache.addRun(r, 1500L, 2600L);
        assertEquals("跨洞补上后三段应并成一段", 1, r.size());
        assertEquals(Long.valueOf(3000L), r.get(0L));

        StreamDiskCache.addRun(r, 500L, 600L);
        assertEquals("写进已有区间内部不得改变结论", 1, r.size());
        StreamDiskCache.addRun(r, 7000L, 7000L);
        assertEquals("空区间一律忽略", 1, r.size());
    }

    /** 落盘/读回的硬约束：只读确认连续的范围，跨过洞必须停下来而不是把洞当数据。 */
    @Test
    public void readNeverCrossesAHole() {
        StreamDiskCache cache = StreamDiskCache.open(tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        assertNotNull(cache);
        byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        cache.record(0L, payload, 0, 512);
        cache.record(4000L, payload, 0, 512);

        assertTrue(cache.has(0L, 512));
        assertTrue(cache.has(4000L, 512));
        assertFalse("[512,4000) 从没下过，不许声称有", cache.has(512L, 100));

        byte[] dest = new byte[4096];
        assertEquals("从 0 起最多只能给到洞前", 512, cache.readInto(0L, dest, 0, 4096));
        assertEquals("从 300 起只连续到 512", 212, cache.readInto(300L, dest, 0, 4096));
        assertEquals("完全落在洞里的请求直接拒", -1, cache.readInto(1000L, dest, 0, 100));
        assertEquals("越出资源总长的写入不可信，record 应忽略", 1024L, cache.getStoredBytes());
        cache.close();
    }

    /** 同一首歌重播：第二段必须从盘上读出来，而不是重新建一条上游连接。 */
    @Test
    public void reopeningTheSameUrlServesFromDisk() {
        StreamDiskCache first = StreamDiskCache.open(tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        byte[] blob = new byte[2048];
        blob[2047] = 77;
        first.record(0L, blob, 0, 2048);
        first.close();

        StreamDiskCache second = StreamDiskCache.open(
                tmp.getRoot(), URL_B, 10_000L, 1_000_000L, 10_000_000L); // 换 token 也算同一首
        assertNotNull(second);
        byte[] dest = new byte[2048];
        assertEquals("重播同一首：整段都该命中", 2048, second.readInto(0L, dest, 0, 2048));
        assertEquals("字节内容必须真是上次那份", 77, dest[2047]);
        second.close();
    }

    /**
     * 重开时绝不能拿"文件大小"当"前面都连续"：跳写后的 {@code .dat} 长度是"最后写到哪儿"，
     * 中段是零填充的洞。这条就是 2026-10-08 自查抓到的那个缺陷本身。
     */
    @Test
    public void reopenKeepsTheHoleHole() {
        StreamDiskCache first = StreamDiskCache.open(tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        byte[] blob = new byte[512];
        blob[0] = 11;
        first.record(0L, blob, 0, 512);
        first.record(4000L, blob, 0, 512); // 重定位后跳写：文件长度变成 4512，[512,4000) 从没写过
        assertEquals("文件长度是 4512，可真正下过的只有 1024——按长度反推就是把洞当数据",
                4512L, datFile().length());
        assertEquals(1024L, first.getStoredBytes());
        first.close();

        StreamDiskCache again = StreamDiskCache.open(
                tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        assertNotNull(again);
        assertTrue("账本里记着的两段该恢复", again.has(0L, 512));
        assertTrue(again.has(4000L, 512));
        assertFalse("洞跨过一次就再也不是洞了——这正是 bug", again.has(512L, 100));
        assertEquals("按区间算而不是按文件长度算", 1024L, again.getStoredBytes());
        byte[] dest = new byte[4096];
        assertEquals("从 0 读也只能给到洞前", 512, again.readInto(0L, dest, 0, 4096));
        assertEquals("跳写那段能读出来", 512, again.readInto(4000L, dest, 0, 512));
        assertEquals("内容仍是真的", 11, dest[0]);
        again.close();
    }

    /** 没有账本的数据不可信：孤儿 {@code .dat} 必须整份作废，不许凭空恢复出可读区间。 */
    @Test
    public void dataWithoutLedgerIsDiscarded() {
        StreamDiskCache first = StreamDiskCache.open(tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        first.record(0L, new byte[2048], 0, 2048);
        first.close();
        assertTrue(datFile().isFile());
        assertTrue(runsFile().isFile());
        assertTrue("sidecar 得先真删掉，否则测不到孤儿路径", runsFile().delete());

        StreamDiskCache again = StreamDiskCache.open(
                tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        assertNotNull(again);
        assertFalse("账本没了就当这段数据从没存在过", again.has(0L, 100));
        assertEquals(0L, again.getStoredBytes());
        assertEquals("孤儿 .dat 应被清掉重建（长度归零）", 0L, datFile().length());
        again.close();
    }

    /** 账本声称有、盘上却没有（文件被截断/写坏）：同样整份作废，而不是读到 EOF。 */
    @Test
    public void ledgerAheadOfDataIsDiscarded() throws Exception {
        StreamDiskCache first = StreamDiskCache.open(tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        first.record(0L, new byte[512], 0, 512);
        first.record(4000L, new byte[512], 0, 512);
        first.close();
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(datFile(), "rw");
        raf.setLength(100L); // 只留下 100 字节，账本却记到 4512
        raf.close();

        StreamDiskCache again = StreamDiskCache.open(
                tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        assertNotNull(again);
        assertFalse("账本对不上盘就不能信任何一段", again.has(0L, 50));
        assertEquals(0L, again.getStoredBytes());
        assertFalse("成对作废：sidecar 也该一起删", runsFile().isFile());
        again.close();
    }

    /**
     * 预取的实例还开着（没 close）时，主播放会为同一个 itemId 再开一个：
     * 后来者必须能看见已落的账本，而不是把正在写的那份 {@code .dat} 当孤儿删掉重来。
     */
    @Test
    public void liveWriterKeepsItsDataWhenASecondInstanceOpensTheSameKey() {
        StreamDiskCache prefetch = StreamDiskCache.open(
                tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        prefetch.record(0L, new byte[1024], 0, 1024);
        assertTrue("出现新段就该提前落账，不能等 close", runsFile().isFile());

        StreamDiskCache second = StreamDiskCache.open(
                tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        assertNotNull(second);
        assertTrue("在写的那段必须能被后来者直接读走，否则预取白做", second.has(0L, 1024));
        assertEquals("数据不能被删了重来", 1024L, datFile().length());
        second.close();
        prefetch.close();
    }

    /** 账本里的总长和资源对不上（服务端原地换过文件）＝ 另一份内容，作废。 */
    @Test
    public void ledgerForAnotherContentLengthIsDiscarded() throws Exception {
        StreamDiskCache first = StreamDiskCache.open(tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        first.record(0L, new byte[512], 0, 512);
        first.close();
        java.io.FileOutputStream out = new java.io.FileOutputStream(runsFile());
        try {
            out.write(("v1 9999\n0-512\n").getBytes("UTF-8"));
        } finally {
            out.close();
        }

        StreamDiskCache again = StreamDiskCache.open(
                tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        assertFalse(again.has(0L, 100));
        again.close();
    }

    /** 区间表文本格式：往返一致，且任何畸形（重叠／乱序／越界／非数字／魔数不对）都判作废。 */
    @Test
    public void runsLedgerTextRoundTripsAndRejectsGarbage() {
        TreeMap<Long, Long> r = new TreeMap<Long, Long>();
        StreamDiskCache.addRun(r, 0L, 1000L);
        StreamDiskCache.addRun(r, 4000L, 4512L);
        String text = StreamDiskCache.encodeRuns(r, 10_000L);
        TreeMap<Long, Long> back = StreamDiskCache.decodeRuns(text, 10_000L);
        assertNotNull(back);
        assertEquals(r, back);
        assertEquals("空表也得能写能读", new TreeMap<Long, Long>(),
                StreamDiskCache.decodeRuns(StreamDiskCache.encodeRuns(
                        new TreeMap<Long, Long>(), 10_000L), 10_000L));

        assertNull("总长不符", StreamDiskCache.decodeRuns(text, 10_001L));
        assertNull("没有头", StreamDiskCache.decodeRuns("0-1000\n", 10_000L));
        assertNull("头里不是数字", StreamDiskCache.decodeRuns("v1 abc\n", 10_000L));
        assertNull("区间越出资源总长", StreamDiskCache.decodeRuns("v1 10000\n0-10001\n", 10_000L));
        assertNull("start>=end 是畸形", StreamDiskCache.decodeRuns("v1 10000\n5-5\n", 10_000L));
        assertNull("重叠（正常写出前必先合并过）", StreamDiskCache.decodeRuns(
                "v1 10000\n0-1000\n500-2000\n", 10_000L));
        assertNull("乱序", StreamDiskCache.decodeRuns("v1 10000\n4000-4512\n0-1000\n", 10_000L));
        assertNull("不是数字", StreamDiskCache.decodeRuns("v1 10000\n0-abc\n", 10_000L));
        assertNull("null 文本", StreamDiskCache.decodeRuns(null, 10_000L));
    }

    /** LRU 淘汰/清空都按"条目"走：{@code .dat} 和它的 sidecar 一起死，不留半个账本。 */
    @Test
    public void evictionAndClearRemoveWholePairs() throws Exception {
        StreamDiskCache first = StreamDiskCache.open(tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        first.record(0L, new byte[4096], 0, 4096);
        first.close();
        assertTrue("sidecar 该和 .dat 成对存在", runsFile().isFile());
        assertTrue("账本本身得有字节，否则下面这条等式是空的", runsFile().length() > 0L);
        assertEquals("占用口径必须把账本也算进去（否则设置页报的数比实际小）",
                datFile().length() + runsFile().length(), StreamDiskCache.dirBytes(tmp.getRoot()));

        // cap 就 100B，还要再放 1B → 唯一的条目必须整对消失
        long ledgerBytes = runsFile().length();
        long freed = StreamDiskCache.evictForSpace(tmp.getRoot(), 1L, 100L);
        assertEquals(4096L + ledgerBytes, freed);
        assertFalse(datFile().exists());
        assertFalse("账本不能孤儿留在盘上", runsFile().exists());
        assertEquals("清干净了", 0L, StreamDiskCache.dirBytes(tmp.getRoot()));

        StreamDiskCache third = StreamDiskCache.open(
                tmp.getRoot(), URL_A, 10_000L, 1_000_000L, 10_000_000L);
        third.record(0L, new byte[2048], 0, 2048);
        third.close();
        long beforeClear = StreamDiskCache.dirBytes(tmp.getRoot());
        assertEquals(beforeClear, StreamDiskCache.clearDir(tmp.getRoot()));
        assertFalse(datFile().exists());
        assertFalse("「清空缓存」同样不能留孤儿账本", runsFile().exists());
    }

    private File datFile() {
        return new File(tmp.getRoot(), StreamDiskCache.FILE_PREFIX + ID + "_10000"
                + StreamDiskCache.FILE_SUFFIX);
    }

    private File runsFile() {
        return new File(tmp.getRoot(), StreamDiskCache.FILE_PREFIX + ID + "_10000"
                + StreamDiskCache.RUNS_SUFFIX);
    }

    /** LRU：装不下时先删最旧的，并且绝不碰目录里别人的文件。 */
    @Test
    public void evictionDropsOldestCacheFileOnly() throws Exception {
        File dir = tmp.newFolder("cache");
        File old1 = write(dir, StreamDiskCache.FILE_PREFIX + "a_100" + StreamDiskCache.FILE_SUFFIX, 100);
        Thread.sleep(20L);
        File newer = write(dir, StreamDiskCache.FILE_PREFIX + "b_100" + StreamDiskCache.FILE_SUFFIX, 100);
        Thread.sleep(20L);
        File stranger = write(dir, "eq_preset.bin", 100);

        // cap=250、现有两个 100B、还要再放 100B → 只需腾 50B，所以只该死最旧那一个
        long freed = StreamDiskCache.evictForSpace(dir, 100L, 250L);
        assertEquals(100L, freed);
        assertFalse("最旧的缓存先死", old1.exists());
        assertTrue("较新的还在（重播的是最近听的那几首）", newer.exists());
        assertTrue("非本模块的文件一律不动", stranger.exists());
    }

    private static File write(File dir, String name, int size) throws Exception {
        File f = new File(dir, name);
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        out.write(new byte[size]);
        out.close();
        return f;
    }
}
