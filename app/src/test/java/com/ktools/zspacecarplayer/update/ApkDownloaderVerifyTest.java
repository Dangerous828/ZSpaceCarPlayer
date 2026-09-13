package com.ktools.zspacecarplayer.update;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 远程升级 (2026-09-12): 下载校验链的回归。
 *
 * 这条链只有一条铁律: **校验不通过就绝不把文件交出去**。车机上装一个被截断或被中间人
 * 换过的 APK, 轻则安装器报「解析包时出现问题」, 重则装上恶意包且签名不同导致后续
 * 再也覆盖不上去。所以这里既测纯函数 (摘要比对/大小比对/文件名清洗/进度节流),
 * 也用真实的 ResponseBody + 真实文件把整条落盘流程跑一遍, 确认失败时盘上不留残包。
 */
public class ApkDownloaderVerifyTest {

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String URL = "https://web.kentonnie.top/zspace/update/x-3.1.0.apk";

    private File dir;
    private File part;
    private File dest;

    @Before
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"), "apkdl-" + System.nanoTime());
        Assert.assertTrue(dir.mkdirs() || dir.isDirectory());
        part = new File(dir, "zspacecarplayer-3.1.0.apk.part");
        dest = new File(dir, "zspacecarplayer-3.1.0.apk");
    }

    @After
    public void tearDown() {
        deleteRecursively(dir);
    }

    // ------------------------------------------------------------ sha256 比对

    @Test
    public void sha256ComparisonIsCaseInsensitive() {
        String lower = ApkDownloader.sha256Hex("abc".getBytes());
        Assert.assertTrue(ApkDownloader.verifySha256(lower, lower));
        Assert.assertTrue("清单填大写、本地算出小写, 必须判等",
                ApkDownloader.verifySha256(lower.toUpperCase(Locale.US), lower));
        Assert.assertTrue(ApkDownloader.verifySha256(lower, lower.toUpperCase(Locale.US)));
        Assert.assertTrue(ApkDownloader.verifySha256(
                lower.toUpperCase(Locale.US), lower.toUpperCase(Locale.US)));
    }

    @Test
    public void sha256ComparisonToleratesSurroundingWhitespace() {
        // 手写清单里字段后面带个空格太常见了
        String sha = ApkDownloader.sha256Hex("abc".getBytes());
        Assert.assertTrue(ApkDownloader.verifySha256("  " + sha + "\n", sha));
    }

    @Test
    public void sha256MismatchIsRejected() {
        String actual = ApkDownloader.sha256Hex("abc".getBytes());
        String other = ApkDownloader.sha256Hex("abd".getBytes());
        Assert.assertFalse(ApkDownloader.verifySha256(other, actual));
        // 只差最后一位也必须拒: 这是最容易被"看起来差不多"糊弄过去的场景
        char last = actual.charAt(63);
        String oneBitOff = actual.substring(0, 63) + (last == '0' ? '1' : '0');
        Assert.assertFalse(ApkDownloader.verifySha256(oneBitOff, actual));
    }

    @Test
    public void sha256ComparisonRejectsNullOrEmptyOrWrongLengthExpectation() {
        String actual = ApkDownloader.sha256Hex("abc".getBytes());
        Assert.assertFalse("期望值缺失不能当成校验通过", ApkDownloader.verifySha256(null, actual));
        Assert.assertFalse(ApkDownloader.verifySha256(actual, null));
        Assert.assertFalse(ApkDownloader.verifySha256("", actual));
        Assert.assertFalse(ApkDownloader.verifySha256("abc123", actual));
        Assert.assertFalse("63 位差一位也不行",
                ApkDownloader.verifySha256(actual.substring(0, 63), actual));
        Assert.assertFalse(ApkDownloader.verifySha256(actual + "0", actual));
    }

    @Test
    public void sha256HexMatchesKnownVectors() {
        // 标准测试向量: 一旦有人把算法换成 SHA-1 或写错编码, 这两条立刻红
        Assert.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ApkDownloader.sha256Hex("abc".getBytes()));
        Assert.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ApkDownloader.sha256Hex(new byte[0]));
        Assert.assertEquals("", ApkDownloader.sha256Hex(null));
    }

    // ------------------------------------------------------------ 大小比对

    @Test
    public void sizeCheckRequiresExactMatchWhenDeclared() {
        Assert.assertTrue(ApkDownloader.isSizeOk(3071328L, 3071328L));
        Assert.assertFalse("少一字节说明流被截断", ApkDownloader.isSizeOk(3071328L, 3071327L));
        Assert.assertFalse("多一字节说明不是同一个包", ApkDownloader.isSizeOk(3071328L, 3071329L));
        Assert.assertFalse(ApkDownloader.isSizeOk(3071328L, 0L));
    }

    @Test
    public void sizeCheckIsSkippedWhenManifestDeclaresNothing() {
        // 清单没给大小 (<=0) 时只认 sha256, 不能因此判失败
        Assert.assertTrue(ApkDownloader.isSizeOk(-1L, 3071328L));
        Assert.assertTrue(ApkDownloader.isSizeOk(0L, 3071328L));
        Assert.assertTrue(ApkDownloader.isSizeOk(-1L, 0L));
    }

    // ------------------------------------------------------------ 文件名清洗

    @Test
    public void fileNameFromUrlStripsQueryAndFragment() {
        Assert.assertEquals("x-3.1.0.apk", ApkDownloader.fileNameFromUrl(URL));
        Assert.assertEquals("x.apk", ApkDownloader.fileNameFromUrl("https://h/a/x.apk?v=2&t=1"));
        Assert.assertEquals("x.apk", ApkDownloader.fileNameFromUrl("https://h/a/x.apk#top"));
        Assert.assertEquals("", ApkDownloader.fileNameFromUrl("https://h/a/"));
        Assert.assertEquals("", ApkDownloader.fileNameFromUrl(null));
        Assert.assertEquals("x.apk", ApkDownloader.fileNameFromUrl("x.apk"));
    }

    @Test
    public void safeFileNameBlocksPathTraversal() {
        // 清单是远端可写的: fileName 里塞 ../ 就能让文件落到 cacheDir 之外
        Assert.assertEquals("evil.apk", ApkDownloader.safeFileName("../../evil.apk", URL));
        Assert.assertEquals("evil.apk", ApkDownloader.safeFileName("/sdcard/evil.apk", URL));
        Assert.assertEquals("evil.apk", ApkDownloader.safeFileName("..\\..\\evil.apk", URL));
        Assert.assertEquals("evil.apk", ApkDownloader.safeFileName("./evil.apk", URL));
        for (String candidate : new String[]{"../../evil.apk", "/sdcard/evil.apk", "..\\evil.apk"}) {
            String name = ApkDownloader.safeFileName(candidate, URL);
            Assert.assertFalse("清洗后不该再有分隔符: " + name,
                    name.contains("/") || name.contains("\\"));
            Assert.assertFalse(name, name.contains(".."));
        }
    }

    @Test
    public void safeFileNameFallsBackToUrlThenToConstant() {
        Assert.assertEquals("x-3.1.0.apk", ApkDownloader.safeFileName(null, URL));
        Assert.assertEquals("x-3.1.0.apk", ApkDownloader.safeFileName("   ", URL));
        Assert.assertEquals("x-3.1.0.apk", ApkDownloader.safeFileName("", URL));
        Assert.assertEquals("url 也取不出名字时必须有兜底", "update.apk",
                ApkDownloader.safeFileName(null, "https://h/"));
        Assert.assertEquals("update.apk", ApkDownloader.safeFileName("...", null));
    }

    @Test
    public void safeFileNameForcesApkSuffixAndSanitizesCharacters() {
        Assert.assertEquals("包名没有后缀就补上, 否则安装器不认", "x.apk",
                ApkDownloader.safeFileName("x", URL));
        Assert.assertEquals("X.APK", ApkDownloader.safeFileName("X.APK", URL));
        Assert.assertEquals("a_b_c.apk", ApkDownloader.safeFileName("a b:c.apk", URL));
        Assert.assertEquals("中文与空格一律换成下划线, 老 ROM 的 vfat/ext4 上不冒险",
                "____.apk", ApkDownloader.safeFileName("更新 包.apk", URL));
    }

    @Test
    public void safeFileNameCapsLength() {
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            long1.append('a');
        }
        String name = ApkDownloader.safeFileName(long1 + ".apk", URL);
        Assert.assertTrue("名字长度 " + name.length() + " 应被限制住", name.length() <= 84);
        Assert.assertTrue(name.endsWith(".apk"));
    }

    // ------------------------------------------------------------ 人话体积

    @Test
    public void humanSizeCoversEveryMagnitude() {
        Assert.assertEquals("未知", ApkDownloader.humanSize(-1L));
        Assert.assertEquals("0 B", ApkDownloader.humanSize(0L));
        Assert.assertEquals("1023 B", ApkDownloader.humanSize(1023L));
        Assert.assertEquals("1.5 KB", ApkDownloader.humanSize(1536L));
        Assert.assertEquals("契约里那份 3071328 字节的包", "2.9 MB", ApkDownloader.humanSize(3071328L));
        Assert.assertEquals("1.00 GB", ApkDownloader.humanSize(1024L * 1024 * 1024));
    }

    // ------------------------------------------------------------ 进度节流

    @Test
    public void progressIsReportedImmediatelyThenThrottled() {
        long total = 3071328L;
        Assert.assertTrue("首次必报: 点了下载得马上看到进度条动起来",
                ApkDownloader.shouldReportProgress(-1L, 0L, total));
        Assert.assertFalse("8KB 一读就报会把主线程刷爆",
                ApkDownloader.shouldReportProgress(0L, 8192L, total));
        Assert.assertFalse(ApkDownloader.shouldReportProgress(0L, 65535L, total));
        Assert.assertTrue("攒够一个步长就该报", ApkDownloader.shouldReportProgress(0L, 65536L, total));
        Assert.assertTrue("终点必报, 否则 UI 卡在 99%",
                ApkDownloader.shouldReportProgress(3000000L, total, total));
    }

    @Test
    public void progressStepScalesWithFileSize() {
        Assert.assertEquals("3MB 的包: 1% 只有 30KB, 取 64KB 下限",
                65536L, ApkDownloader.progressStep(3071328L));
        Assert.assertEquals("100MB 的包: 1% = 1MB, 按 1% 走",
                1000000L, ApkDownloader.progressStep(100000000L));
        Assert.assertEquals("总量未知时用固定步长", 65536L, ApkDownloader.progressStep(-1L));
        Assert.assertEquals(65536L, ApkDownloader.progressStep(0L));
    }

    @Test
    public void progressThrottlingKeepsCallbackCountBounded() {
        // 3MB 包按 8KB 一读共 ~375 次循环, 上报次数必须在几十次量级
        long total = 3071328L;
        long last = -1;
        int reports = 0;
        for (long written = 8192; written <= total; written += 8192) {
            if (ApkDownloader.shouldReportProgress(last, written, total)) {
                reports++;
                last = written;
            }
        }
        Assert.assertTrue("上报 " + reports + " 次, 太多", reports <= 60);
        Assert.assertTrue("上报 " + reports + " 次, 太少看不出进度", reports >= 20);
    }

    // ------------------------------------------------------------ 整条落盘流程

    @Test
    public void verifiedPayloadIsWrittenAndRenamedFromPart() throws Exception {
        byte[] body = payload(300 * 1024);
        UpdateManifest m = manifest(body.length, ApkDownloader.sha256Hex(body));

        File out = newDownloader().transfer(response(200, APK_MIME, body), m, part, dest, null);

        Assert.assertEquals(dest, out);
        Assert.assertTrue("校验通过的文件必须落在最终名上", dest.isFile());
        Assert.assertFalse(".part 不该残留", part.exists());
        Assert.assertEquals(body.length, dest.length());
        Assert.assertArrayEquals("落盘内容必须与响应体逐字节一致", body, readAll(dest));
    }

    @Test
    public void streamingDigestMatchesOneShotDigestOnLargePayload() throws Exception {
        // 边下边算与一次性算必须一致, 否则「省一遍读盘」就成了「换了个算法」
        byte[] body = payload(1024 * 1024 + 12345);
        UpdateManifest m = manifest(-1L, ApkDownloader.sha256Hex(body));
        File out = newDownloader().transfer(response(200, APK_MIME, body), m, part, dest, null);
        Assert.assertEquals(body.length, out.length());
    }

    @Test
    public void shaMismatchIsRejectedAndNothingIsLeftOnDisk() {
        byte[] body = payload(64 * 1024);
        String wrong = repeat('a', 64);
        UpdateManifest m = manifest(body.length, wrong);
        try {
            newDownloader().transfer(response(200, APK_MIME, body), m, part, dest, null);
            Assert.fail("摘要不符必须抛 VerifyException");
        } catch (ApkDownloader.VerifyException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("SHA-256"));
        } catch (IOException e) {
            Assert.fail("应当是 VerifyException, 实际: " + e);
        }
        Assert.assertFalse("校验失败的包绝不能留在盘上", dest.exists());
        Assert.assertFalse(part.exists());
    }

    @Test
    public void truncatedStreamIsRejectedBySizeCheck() {
        byte[] body = payload(64 * 1024);
        // 清单声明的比实收多: 流被截断 (弱网/反代提前关闭)
        UpdateManifest m = manifest(body.length + 4096, ApkDownloader.sha256Hex(body));
        try {
            newDownloader().transfer(response(200, APK_MIME, body), m, part, dest, null);
            Assert.fail("字节数不符必须抛 VerifyException");
        } catch (ApkDownloader.VerifyException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("大小不符"));
        } catch (IOException e) {
            Assert.fail("应当是 VerifyException, 实际: " + e);
        }
        Assert.assertFalse(dest.exists());
        Assert.assertFalse(part.exists());
    }

    @Test
    public void oversizedStreamIsStoppedEarly() {
        byte[] body = payload(128 * 1024);
        // 清单声明的比实收少: 服务端给的显然不是那个包, 立刻止损别把 cache 写满
        UpdateManifest m = manifest(1024, ApkDownloader.sha256Hex(body));
        try {
            newDownloader().transfer(response(200, APK_MIME, body), m, part, dest, null);
            Assert.fail("超出声明大小必须中止");
        } catch (ApkDownloader.VerifyException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("超过"));
        } catch (IOException e) {
            Assert.fail("应当是 VerifyException, 实际: " + e);
        }
        Assert.assertFalse(dest.exists());
        Assert.assertFalse(part.exists());
    }

    @Test
    public void htmlCatchAllPageIsNotMistakenForAnApk() {
        // 2026-09-12 现网实测: 站点 SPA 兜底页对未知路径回 200 text/html
        byte[] html = "<!doctype html><html><body><div id=\"app\"></div></body></html>"
                .getBytes();
        UpdateManifest m = manifest(-1L, ApkDownloader.sha256Hex(html));
        try {
            newDownloader().transfer(response(200, "text/html; charset=utf-8", html),
                    m, part, dest, null);
            Assert.fail("网页不能当成安装包落盘");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("网页"));
        }
        Assert.assertFalse(dest.exists());
    }

    @Test
    public void httpErrorsAreSurfacedWithStatusCode() {
        byte[] body = payload(1024);
        UpdateManifest m = manifest(body.length, ApkDownloader.sha256Hex(body));
        assertHttpFailure(404, body, m);
        assertHttpFailure(500, body, m);
        assertHttpFailure(403, body, m);
    }

    @Test
    public void redirectIsNotSilentlyAccepted() {
        byte[] body = payload(1024);
        UpdateManifest m = manifest(body.length, ApkDownloader.sha256Hex(body));
        Response r = new Response.Builder()
                .request(new Request.Builder().url(URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(302).message("Found")
                .header("Location", "https://elsewhere.invalid/x.apk")
                .body(ResponseBody.create(MediaType.parse("text/plain"), new byte[0]))
                .build();
        try {
            newDownloader().transfer(r, m, part, dest, null);
            Assert.fail("3xx 必须报错而不是把空 body 当成包");
        } catch (UpdateChecker.HttpException expected) {
            Assert.assertEquals(302, expected.getCode());
        } catch (IOException e) {
            Assert.fail("应当是 HttpException, 实际: " + e);
        }
    }

    @Test
    public void staleFileFromPreviousAttemptIsOverwritten() throws Exception {
        write(dest, "上一版的残留".getBytes("UTF-8"));
        write(part, "半截文件".getBytes("UTF-8"));
        byte[] body = payload(40 * 1024);
        UpdateManifest m = manifest(body.length, ApkDownloader.sha256Hex(body));

        File out = newDownloader().transfer(response(200, APK_MIME, body), m, part, dest, null);

        Assert.assertArrayEquals(body, readAll(out));
        Assert.assertFalse(part.exists());
    }

    // ------------------------------------------------------------ 辅助

    /** Context 传 null: transfer 全程不碰 Context, 单测里也就用不上 Android 桩 */
    private static ApkDownloader newDownloader() {
        return new ApkDownloader(null);
    }

    private void assertHttpFailure(int code, byte[] body, UpdateManifest m) {
        try {
            newDownloader().transfer(response(code, APK_MIME, body), m, part, dest, null);
            Assert.fail("HTTP " + code + " 必须报错");
        } catch (UpdateChecker.HttpException expected) {
            Assert.assertEquals(code, expected.getCode());
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("" + code));
        } catch (IOException e) {
            Assert.fail("应当是 HttpException, 实际: " + e);
        }
        Assert.assertFalse("失败不该留下文件", dest.exists());
    }

    private static UpdateManifest manifest(long sizeBytes, String sha256) {
        String json = "{\"versionCode\":4,\"versionName\":\"3.1.0\",\"apkUrl\":\"" + URL + "\","
                + "\"fileName\":\"zspacecarplayer-3.1.0.apk\","
                + (sizeBytes >= 0 ? "\"sizeBytes\":" + sizeBytes + "," : "")
                + "\"sha256\":\"" + sha256 + "\",\"notes\":\"测试\",\"minVersionCode\":1,"
                + "\"mandatory\":false}";
        try {
            return UpdateManifest.fromJson(json);
        } catch (UpdateManifest.ParseException e) {
            throw new AssertionError("测试用的清单本身不合法: " + e.getMessage(), e);
        }
    }

    private static Response response(int code, String contentType, byte[] body) {
        return new Response.Builder()
                .request(new Request.Builder().url(URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Status")
                .body(ResponseBody.create(MediaType.parse(contentType), body))
                .build();
    }

    private static byte[] payload(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i * 31 + 7);
        }
        return b;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void write(File f, byte[] data) throws IOException {
        java.io.OutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            Assert.assertEquals("文件没读全", buf.length, off);
        } finally {
            in.close();
        }
        return buf;
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
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
