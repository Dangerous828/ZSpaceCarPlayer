package com.ktools.zspacecarplayer.crash;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;

/**
 * 落盘是崩溃上报链上唯一不能失败的一环：进程正在死，写不进磁盘就等于这次崩溃永远丢了。
 * 这里的断言全部围绕「写得进去、读得回来、不会把 /data 撑满」。
 */
public class CrashReportStoreTest {

    private File dir;
    private CrashReportStore store;

    @Before
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"),
                "crashstore-" + System.nanoTime());
        store = new CrashReportStore(dir);
    }

    @After
    public void tearDown() {
        deleteRecursively(dir);
    }

    @Test
    public void writeCreatesMissingDirectory() {
        Assert.assertFalse(dir.exists());
        File f = store.write(report("rid-1", CrashReport.KIND_JAVA_CRASH));
        Assert.assertNotNull(f);
        Assert.assertTrue(dir.isDirectory());
        Assert.assertTrue(f.isFile());
    }

    @Test
    public void fileNameEncodesKindAndReportId() {
        File f = store.write(report("abc123", CrashReport.KIND_NATIVE_CRASH));
        Assert.assertNotNull(f);
        Assert.assertEquals("native_crash-abc123.json", f.getName());
    }

    @Test
    public void writeThenReadRoundTripsJson() {
        CrashReport r = report("rid-2", CrashReport.KIND_MAIN_THREAD_BLOCKED);
        r.put("engine", "v3").put("category", "粤语金曲");
        r.setStackTrace("main blocked at Thread.join");

        File f = store.write(r);
        Assert.assertNotNull(f);
        String back = store.read(f);
        Assert.assertEquals(r.toJson(), back);
        Assert.assertTrue(back.contains("\"category\":\"粤语金曲\""));
    }

    @Test
    public void writeSurvivesLargeEvidenceBlocks() {
        StringBuilder logcat = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            logcat.append("E/AndroidRuntime: line ").append(i).append('\n');
        }
        CrashReport r = report("big", CrashReport.KIND_NATIVE_CRASH);
        r.setLogcat(logcat.toString());
        r.setThreadDump(CrashReport.dumpAllThreads());

        File f = store.write(r);
        Assert.assertNotNull(f);
        Assert.assertEquals(r.toJson(), store.read(f));
    }

    @Test
    public void writeNullReportReturnsNull() {
        Assert.assertNull(store.write(null));
    }

    @Test
    public void pendingIsEmptyWhenNothingWritten() {
        Assert.assertTrue(store.pending().isEmpty());
    }

    @Test
    public void pendingIsEmptyWhenDirectoryDoesNotExist() {
        Assert.assertFalse(dir.exists());
        Assert.assertTrue(store.pending().isEmpty());
    }

    @Test
    public void pendingReturnsOldestFirst() throws Exception {
        // 连续写入的时间戳可能落在同一毫秒，显式指定 mtime 才能确定顺序
        File a = store.write(report("a", CrashReport.KIND_JAVA_CRASH));
        File b = store.write(report("b", CrashReport.KIND_NATIVE_CRASH));
        File c = store.write(report("c", CrashReport.KIND_ABNORMAL_EXIT));
        Assert.assertNotNull(a);
        Assert.assertNotNull(b);
        Assert.assertNotNull(c);
        long base = System.currentTimeMillis() - 60_000L;
        Assert.assertTrue(c.setLastModified(base));
        Assert.assertTrue(a.setLastModified(base + 1000L));
        Assert.assertTrue(b.setLastModified(base + 2000L));

        List<File> pending = store.pending();
        Assert.assertEquals(3, pending.size());
        Assert.assertEquals("c", idOf(pending.get(0)));
        Assert.assertEquals("a", idOf(pending.get(1)));
        Assert.assertEquals("b", idOf(pending.get(2)));
    }

    @Test
    public void pendingIgnoresNonJsonFilesAndSubdirectories() throws Exception {
        store.write(report("keep", CrashReport.KIND_JAVA_CRASH));
        writeFile(new File(dir, "notes.txt"), "not a report");
        writeFile(new File(dir, "crash_session_active"), "1|123");
        new File(dir, "subdir.json").mkdirs();

        List<File> pending = store.pending();
        Assert.assertEquals(1, pending.size());
        Assert.assertEquals("keep", idOf(pending.get(0)));
    }

    @Test
    public void trimKeepsAtMostMaxFilesAndDropsOldest() throws Exception {
        // 先写到刚好满额：此时 trim 不会动手，mtime 可以随意重排
        for (int i = 0; i < CrashReportStore.MAX_FILES; i++) {
            File f = store.write(report("r" + i, CrashReport.KIND_JAVA_CRASH));
            Assert.assertNotNull(f);
        }
        long base = System.currentTimeMillis() - 3_600_000L;
        for (int i = 0; i < CrashReportStore.MAX_FILES; i++) {
            File f = new File(dir, "java_crash-r" + i + ".json");
            // i 越大越旧，于是 r(MAX_FILES-1) 是全场最旧的一份
            Assert.assertTrue(f.setLastModified(base + (CrashReportStore.MAX_FILES - i) * 1000L));
        }
        Assert.assertEquals(CrashReportStore.MAX_FILES, store.pending().size());

        // 这一份让总数越线，触发唯一一次修剪
        File newest = store.write(report("newest", CrashReport.KIND_JAVA_CRASH));
        Assert.assertNotNull(newest);

        List<File> pending = store.pending();
        Assert.assertEquals(CrashReportStore.MAX_FILES, pending.size());
        Assert.assertTrue("刚写入的报告不该被修剪掉", newest.isFile());
        Assert.assertFalse("最旧的一份应被删掉",
                new File(dir, "java_crash-r" + (CrashReportStore.MAX_FILES - 1) + ".json").exists());
        Assert.assertTrue("第二旧的应保留",
                new File(dir, "java_crash-r" + (CrashReportStore.MAX_FILES - 2) + ".json").exists());
        Assert.assertEquals("newest", idOf(pending.get(pending.size() - 1)));
    }

    @Test
    public void deleteRemovesFile() {
        File f = store.write(report("gone", CrashReport.KIND_JAVA_CRASH));
        Assert.assertNotNull(f);
        store.delete(f);
        Assert.assertFalse(f.exists());
        Assert.assertTrue(store.pending().isEmpty());
    }

    @Test
    public void deleteToleratesNullAndMissingFile() {
        store.delete(null);
        store.delete(new File(dir, "never-existed.json"));
    }

    @Test
    public void readMissingFileReturnsNull() {
        Assert.assertNull(store.read(new File(dir, "nope.json")));
    }

    @Test
    public void getDirExposesConfiguredLocation() {
        Assert.assertEquals(dir, store.getDir());
    }

    private static CrashReport report(String id, String kind) {
        return new CrashReport(id, kind, System.currentTimeMillis());
    }

    private static String idOf(File f) {
        String name = f.getName();
        int dash = name.indexOf('-');
        return name.substring(dash + 1, name.length() - ".json".length());
    }

    private static void writeFile(File f, String content) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(content);
        } finally {
            w.close();
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }
}
