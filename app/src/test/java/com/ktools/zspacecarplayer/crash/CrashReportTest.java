package com.ktools.zspacecarplayer.crash;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * 报告是自己拼的 JSON（依赖锁死，且 org.json 在 JVM 单测里是 android.jar 空壳），
 * 转义漏一个字符整份报告就废掉——云端拿到的是一段无法解析的文本，等于崩溃没上报。
 */
public class CrashReportTest {

    // ---------------------------------------------------------------- 转义

    @Test
    public void escapeHandlesQuoteBackslashAndNewline() {
        Assert.assertEquals("\\\"", CrashReport.escape("\""));
        Assert.assertEquals("\\\\", CrashReport.escape("\\"));
        Assert.assertEquals("a\\nb", CrashReport.escape("a\nb"));
        Assert.assertEquals("a\\rb", CrashReport.escape("a\rb"));
    }

    @Test
    public void escapeHandlesTabBackspaceFormfeed() {
        Assert.assertEquals("a\\tb", CrashReport.escape("a\tb"));
        Assert.assertEquals("a\\bb", CrashReport.escape("a\bb"));
        Assert.assertEquals("a\\fb", CrashReport.escape("a\fb"));
    }

    @Test
    public void escapeConvertsOtherControlCharsToUnicode() {
        Assert.assertEquals("\\u0001", CrashReport.escape("\u0001"));
        Assert.assertEquals("\\u001f", CrashReport.escape("\u001f"));
        Assert.assertEquals("\\u0000x", CrashReport.escape("\u0000x"));
    }

    @Test
    public void escapeLeavesNormalTextAndCjkUntouched() {
        Assert.assertEquals("粤语金曲 - 海阔天空", CrashReport.escape("粤语金曲 - 海阔天空"));
        Assert.assertEquals("a/b{c}d[e]", CrashReport.escape("a/b{c}d[e]"));
    }

    @Test
    public void escapeTreatsNullAsEmpty() {
        Assert.assertEquals("", CrashReport.escape(null));
    }

    @Test
    public void escapeIsIdempotentSafeForAlreadyEscapedInput() {
        // 栈里常含字面量反斜杠（Windows 路径 / 正则），必须被再转义而不是原样吐出
        Assert.assertEquals("C:\\\\temp", CrashReport.escape("C:\\temp"));
    }

    // ---------------------------------------------------------------- 脱敏

    private static final String SECRET = "9f3c1a7b2e5d48f0a6c1b9d8e7f01234";

    @Test
    public void redactsApiKeyInPlainQueryString() {
        String url = "http://192.168.31.152:8096/Audio/123/stream.flac?api_key=" + SECRET
                + "&static=true";
        String out = CrashReport.redact(url);
        Assert.assertFalse("api_key 明文漏出: " + out, out.contains(SECRET));
        Assert.assertTrue(out.contains("api_key=[REDACTED]"));
        // 其它查询参数是排查线索，不能一起被抹掉
        Assert.assertTrue(out.contains("static=true"));
        Assert.assertTrue(out.contains("/Audio/123/stream.flac"));
    }

    @Test
    public void redactsApiKeyInsideUrlEncodedProxyPath() {
        // DspAudioTrackPlayer 的面包屑 "doPrepare begin path=…" 打的就是这个形态：
        // 本地代理把远端地址整体 URL 编码塞进 ?u=，于是 api_key 变成 api_key%3D…
        String proxy = "http://127.0.0.1:30141/stream?u=http%3A%2F%2F192.168.31.152%3A8096"
                + "%2FAudio%2F123%2Fstream.flac%3Fapi_key%3D" + SECRET + "%26static%3Dtrue";
        String out = CrashReport.redact(proxy);
        Assert.assertFalse("编码形态的 api_key 漏出: " + out, out.contains(SECRET));
        Assert.assertTrue(out.contains("[REDACTED]"));
    }

    @Test
    public void redactsTokenInLogcatHeaderDump() {
        String logcat = "D/OkHttp: X-Emby-Token: " + SECRET + "\n"
                + "D/OkHttp: X-MediaBrowser-Token: " + SECRET + "\n"
                + "D/OkHttp: Content-Type: application/json";
        String out = CrashReport.redact(logcat);
        Assert.assertFalse("请求头里的 token 漏出: " + out, out.contains(SECRET));
        Assert.assertTrue(out.contains("Content-Type: application/json"));
    }

    @Test
    public void redactsPasswordButKeepsUsername() {
        String payload = "{\"Username\":\"car\",\"Pw\":\"your_password\"}";
        String out = CrashReport.redact(payload);
        Assert.assertFalse("车机账号密码漏出: " + out, out.contains("your_password"));
        // 用户名不是秘密，且是判断"哪台车机/哪个账号"的关键线索
        Assert.assertTrue(out.contains("\"Username\":\"car\""));
        Assert.assertTrue(out.contains("\"Pw\":\"[REDACTED]\""));
    }

    @Test
    public void redactionIsCaseInsensitive() {
        Assert.assertFalse(CrashReport.redact("API_KEY=" + SECRET).contains(SECRET));
        Assert.assertFalse(CrashReport.redact("ApiKey=" + SECRET).contains(SECRET));
        Assert.assertFalse(CrashReport.redact("ACCESS_TOKEN=" + SECRET).contains(SECRET));
        Assert.assertFalse(CrashReport.redact("{\"pw\":\"your_password\"}").contains("your_password"));
    }

    @Test
    public void redactionLeavesDiagnosticTextAlone() {
        String[] benign = {
                "粤语金曲 - 海阔天空",
                "09-07 08:40:12.345  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main",
                "  at com.ktools.zspacecarplayer.player.DspAudioTrackPlayer.doPrepare(DspAudioTrackPlayer.java:135)",
                "Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 2526",
                "network stall over 30000ms",
                "key=1 token=2", // 不含被保护的参数名，不该被误伤
        };
        for (String s : benign) {
            Assert.assertEquals("被误伤: " + s, s, CrashReport.redact(s));
        }
    }

    @Test
    public void redactToleratesNullAndEmpty() {
        Assert.assertEquals("", CrashReport.redact(null));
        Assert.assertEquals("", CrashReport.redact(""));
    }

    @Test
    public void escapeRedactsBecauseEveryReportStringPassesThroughIt() {
        // escape() 是 toJson() 唯一的字符串出口，脱敏挂在这里就不可能被绕过
        Assert.assertFalse(CrashReport.escape("api_key=" + SECRET).contains(SECRET));
    }

    @Test
    public void jsonCarriesNoSecretFromAnyEvidenceBlock() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.put("ctx_streamUrl", "http://h/Audio/1?api_key=" + SECRET);
        r.addBreadcrumbs(Arrays.asList(
                "v3 doPrepare begin path=http://127.0.0.1:30141/stream?u=x%3Fapi_key%3D" + SECRET));
        r.setStackTrace("java.lang.RuntimeException: auth {\"Pw\":\"your_password\"} failed");
        r.setLogcat("D/OkHttp: X-Emby-Token: " + SECRET);
        String json = r.toJson();
        Assert.assertFalse("报告里仍有 api_key: " + json, json.contains(SECRET));
        Assert.assertFalse("报告里仍有密码: " + json, json.contains("your_password"));
        // 脱敏不能顺手毁掉结构：仍然是一个完整对象
        Assert.assertTrue(json.startsWith("{\"reportId\":\"r\""));
        Assert.assertTrue(json.endsWith("}"));
    }

    @Test
    public void redactionCostStaysLowOnFullSizeLogcat() {
        // 崩溃时写盘是同步的，进程正在死；脱敏跑在 64KB logcat 上不能拖到几十毫秒
        StringBuilder big = new StringBuilder(64 * 1024 + 1024);
        while (big.length() < 64 * 1024) {
            big.append("09-07 08:40:12.345  1234  1234 I Player: buffer 40% pos=12345\n");
        }
        big.append("D/OkHttp: X-Emby-Token: ").append(SECRET).append('\n');
        String text = big.toString();

        CrashReport.redact(text); // 预热，避开正则编译与 JIT 冷启动
        long begin = System.nanoTime();
        String out = CrashReport.redact(text);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;

        Assert.assertFalse(out.contains(SECRET));
        Assert.assertTrue("64KB logcat 脱敏耗时 " + elapsedMs + "ms，太慢", elapsedMs < 500);
    }

    // ---------------------------------------------------------------- 结构

    @Test
    public void jsonStartsWithReportIdAndKind() {
        CrashReport r = new CrashReport("rid-1", CrashReport.KIND_NATIVE_CRASH, 1700000000000L);
        String json = r.toJson();
        Assert.assertTrue(json.startsWith("{\"reportId\":\"rid-1\",\"kind\":\"native_crash\""));
        Assert.assertTrue(json.contains("\"timestamp\":1700000000000"));
        Assert.assertTrue(json.endsWith("}"));
    }

    @Test
    public void timestampIsANumberNotAString() {
        String json = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 42L).toJson();
        Assert.assertTrue(json.contains("\"timestamp\":42,"));
        Assert.assertFalse(json.contains("\"timestamp\":\"42\""));
    }

    @Test
    public void fieldsAreEmittedInInsertionOrder() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.put("model", "Geely-8600").put("engine", "v3").put("category", "粤语金曲");
        String json = r.toJson();
        int model = json.indexOf("\"model\":");
        int engine = json.indexOf("\"engine\":");
        int category = json.indexOf("\"category\":");
        Assert.assertTrue(model > 0 && model < engine && engine < category);
    }

    @Test
    public void numericAndBooleanFieldsAreStringified() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.put("sdkInt", 18L).put("isV3", true);
        String json = r.toJson();
        Assert.assertTrue(json.contains("\"sdkInt\":\"18\""));
        Assert.assertTrue(json.contains("\"isV3\":\"true\""));
    }

    @Test
    public void nullKeyOrValueIsIgnored() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.put(null, "v").put("k", (String) null).put("ok", "yes");
        String json = r.toJson();
        Assert.assertTrue(json.contains("\"ok\":\"yes\""));
        Assert.assertFalse(json.contains("null"));
    }

    @Test
    public void sameKeyOverwritesInsteadOfDuplicating() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.put("song", "A").put("song", "B");
        String json = r.toJson();
        Assert.assertEquals(1, countOccurrences(json, "\"song\":"));
        Assert.assertTrue(json.contains("\"song\":\"B\""));
    }

    @Test
    public void breadcrumbsAreEmittedAsJsonArray() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_MAIN_THREAD_BLOCKED, 1L);
        r.addBreadcrumbs(Arrays.asList("first", "se\"cond", "third"));
        String json = r.toJson();
        Assert.assertTrue(json.contains("\"breadcrumbs\":[\"first\",\"se\\\"cond\",\"third\"]"));
    }

    @Test
    public void emptyBreadcrumbsEmitEmptyArray() {
        String json = new CrashReport("r", CrashReport.KIND_ABNORMAL_EXIT, 1L).toJson();
        Assert.assertTrue(json.contains("\"breadcrumbs\":[]"));
    }

    @Test
    public void addBreadcrumbsToleratesNull() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.addBreadcrumbs(null);
        Assert.assertTrue(r.toJson().contains("\"breadcrumbs\":[]"));
    }

    @Test
    public void fieldValuesAreEscapedInsideJson() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.put("songTitle", "海阔天空\n(live)");
        r.setStackTrace("java.lang.RuntimeException: boom \"quoted\"\n\tat Foo.bar(Foo.java:1)");
        String json = r.toJson();
        Assert.assertTrue(json.contains("\"songTitle\":\"海阔天空\\n(live)\""));
        Assert.assertTrue(json.contains("\\\"quoted\\\""));
        Assert.assertTrue(json.contains("\\n\\tat Foo.bar(Foo.java:1)"));
        // 整份报告必须恰好是一个对象：没有裸换行破坏结构
        Assert.assertFalse(json.contains("\n"));
    }

    @Test
    public void nullSettersBecomeEmptyStrings() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.setStackTrace(null).setThreadDump(null).setLogcat(null);
        String json = r.toJson();
        Assert.assertTrue(json.contains("\"stackTrace\":\"\""));
        Assert.assertTrue(json.contains("\"threadDump\":\"\""));
        Assert.assertTrue(json.contains("\"logcat\":\"\""));
    }

    @Test
    public void jsonAlwaysCarriesTheThreeEvidenceBlocks() {
        String json = new CrashReport("r", CrashReport.KIND_NATIVE_CRASH, 1L).toJson();
        Assert.assertTrue(json.contains("\"stackTrace\":"));
        Assert.assertTrue(json.contains("\"threadDump\":"));
        Assert.assertTrue(json.contains("\"logcat\":"));
    }

    @Test
    public void bracesInEvidenceDoNotBreakStructure() {
        CrashReport r = new CrashReport("r", CrashReport.KIND_JAVA_CRASH, 1L);
        r.setLogcat("E/AndroidRuntime: FATAL EXCEPTION: main {unbalanced");
        String json = r.toJson();
        Assert.assertTrue(json.endsWith("\"}"));
        Assert.assertEquals(1, countOccurrences(json, "\"logcat\":"));
    }

    // ---------------------------------------------------------------- 现场抓取

    @Test
    public void stackTraceOfIncludesMessageAndFrames() {
        String trace = CrashReport.stackTraceOf(new IllegalStateException("player not prepared"));
        Assert.assertTrue(trace.contains("java.lang.IllegalStateException: player not prepared"));
        Assert.assertTrue(trace.contains("\tat "));
    }

    @Test
    public void stackTraceOfUnwrapsCauseChain() {
        Throwable root = new java.io.IOException("ring buffer stalled");
        Throwable wrapped = new RuntimeException("native open failed", root);
        String trace = CrashReport.stackTraceOf(wrapped);
        Assert.assertTrue(trace.contains("Caused by: java.io.IOException: ring buffer stalled"));
    }

    @Test
    public void stackTraceOfNullIsEmpty() {
        Assert.assertEquals("", CrashReport.stackTraceOf(null));
    }

    @Test
    public void dumpAllThreadsPutsMainFirst() throws Exception {
        // Gradle 的执行线程叫 "Test worker"，JVM 单测里天然没有 main。
        // 车机上 UI 线程确实名为 main，这里显式造一个来验证排序契约本身。
        final Object started = new Object();
        final Object release = new Object();
        Thread fakeMain = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (started) { started.notifyAll(); }
                synchronized (release) {
                    try { release.wait(5000); } catch (InterruptedException ignored) {}
                }
            }
        }, "main");
        fakeMain.setDaemon(true);
        synchronized (started) {
            fakeMain.start();
            started.wait(5000);
        }
        Thread.sleep(100);

        try {
            String dump = CrashReport.dumpAllThreads();
            Assert.assertTrue("dump 应以 main 开头，实际: " + head(dump),
                    dump.startsWith("\"main\" "));
            Assert.assertTrue("当前线程不该被漏掉: " + head(dump),
                    dump.contains("\"" + Thread.currentThread().getName() + "\" "));
            Assert.assertTrue(dump.contains("state="));
        } finally {
            synchronized (release) { release.notifyAll(); }
            fakeMain.join(2000);
        }
    }

    @Test
    public void dumpAllThreadsIncludesWorkerThreadFrames() throws Exception {
        final Object started = new Object();
        final Object release = new Object();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (started) { started.notifyAll(); }
                synchronized (release) {
                    try { release.wait(5000); } catch (InterruptedException ignored) {}
                }
            }
        }, "DspPlayer-RenderLoop");
        worker.setDaemon(true);
        synchronized (started) {
            worker.start();
            started.wait(5000);
        }
        Thread.sleep(100); // 让它确实停在 wait 上

        String dump = CrashReport.dumpAllThreads();
        Assert.assertTrue(dump.contains("\"DspPlayer-RenderLoop\" daemon"));
        Assert.assertTrue("应能看到 worker 卡在 wait 上:\n" + dump,
                dump.contains("java.lang.Object.wait"));

        synchronized (release) { release.notifyAll(); }
        worker.join(2000);
    }

    @Test
    public void kindConstantsMatchUploadedVocabulary() {
        // 云端按 kind 分流，这四个字符串一旦改动就等于换了协议
        Assert.assertEquals("java_crash", CrashReport.KIND_JAVA_CRASH);
        Assert.assertEquals("native_crash", CrashReport.KIND_NATIVE_CRASH);
        Assert.assertEquals("abnormal_exit", CrashReport.KIND_ABNORMAL_EXIT);
        Assert.assertEquals("main_thread_blocked", CrashReport.KIND_MAIN_THREAD_BLOCKED);
    }

    private static int countOccurrences(String hay, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = hay.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String head(String s) {
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
