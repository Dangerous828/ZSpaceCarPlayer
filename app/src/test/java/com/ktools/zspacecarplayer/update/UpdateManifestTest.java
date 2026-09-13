package com.ktools.zspacecarplayer.update;

import org.junit.Assert;
import org.junit.Test;

/**
 * 远程升级 (2026-09-12): 清单解析的容错回归。
 *
 * 为什么值得单测: 清单是人手写在服务器上的静态 JSON, 三种脏形态在现网都会出现 ——
 * 数字被加了引号、字段漏填、路径写错于是站点 SPA 兜底页回一整张 HTML (2026-09-12
 * 实测 web.kentonnie.top 就是这样)。任何一种都不能让车机崩溃, 更不能把「解析失败」
 * 静默当成「已是最新版本」—— 那等于 OTA 通道悄悄死掉, 没人知道。
 */
public class UpdateManifestTest {

    /** 64 位小写十六进制: "0123456789abcdef" × 4 */
    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String APK_URL =
            "https://web.kentonnie.top/zspace/update/zspacecarplayer-3.1.0.apk";

    /** 契约里那份完整清单 */
    private static String fullJson() {
        return "{"
                + "\"versionCode\":4,"
                + "\"versionName\":\"3.1.0\","
                + "\"apkUrl\":\"" + APK_URL + "\","
                + "\"fileName\":\"zspacecarplayer-3.1.0.apk\","
                + "\"sizeBytes\":3071328,"
                + "\"sha256\":\"" + SHA + "\","
                + "\"notes\":\"本次更新说明\","
                + "\"minVersionCode\":1,"
                + "\"mandatory\":false,"
                + "\"publishedAt\":\"2026-09-13T00:00:00+08:00\""
                + "}";
    }

    /** 只有必填三项的最小清单 */
    private static String minimalJson(int versionCode, String apkUrl, String sha256) {
        return "{\"versionCode\":" + versionCode
                + ",\"apkUrl\":\"" + apkUrl + "\""
                + ",\"sha256\":\"" + sha256 + "\"}";
    }

    // ------------------------------------------------------------ 正常解析

    @Test
    public void parsesEveryContractField() throws Exception {
        UpdateManifest m = UpdateManifest.fromJson(fullJson());
        Assert.assertEquals(4, m.getVersionCode());
        Assert.assertEquals("3.1.0", m.getVersionName());
        Assert.assertEquals(APK_URL, m.getApkUrl());
        Assert.assertEquals("zspacecarplayer-3.1.0.apk", m.getFileName());
        Assert.assertEquals(3071328L, m.getSizeBytes());
        Assert.assertEquals(SHA, m.getSha256());
        Assert.assertEquals("本次更新说明", m.getNotes());
        Assert.assertEquals(1, m.getMinVersionCode());
        Assert.assertFalse(m.isMandatory());
        Assert.assertEquals("2026-09-13T00:00:00+08:00", m.getPublishedAt());
    }

    @Test
    public void mandatoryTrueIsHonoured() throws Exception {
        UpdateManifest m = UpdateManifest.fromJson(
                fullJson().replace("\"mandatory\":false", "\"mandatory\":true"));
        Assert.assertTrue("mandatory=true 必须被读出来, 否则「必须更新」的措辞就没了", m.isMandatory());
    }

    @Test
    public void uppercaseSha256IsNormalizedToLowercase() throws Exception {
        UpdateManifest m = UpdateManifest.fromJson(fullJson().replace(SHA, SHA.toUpperCase()));
        Assert.assertEquals("存的时候就归一化, 比对时才不必两头操心", SHA, m.getSha256());
    }

    // ------------------------------------------------------------ 容错

    @Test
    public void toleratesNumbersWrittenAsStrings() throws Exception {
        // 手写 JSON 最常见的脏形态: 数字被加了引号
        String json = "{\"versionCode\":\"5\",\"apkUrl\":\"" + APK_URL + "\","
                + "\"sha256\":\"" + SHA + "\",\"sizeBytes\":\"3071328\","
                + "\"minVersionCode\":\"2\",\"mandatory\":\"true\"}";
        UpdateManifest m = UpdateManifest.fromJson(json);
        Assert.assertEquals(5, m.getVersionCode());
        Assert.assertEquals(3071328L, m.getSizeBytes());
        Assert.assertEquals(2, m.getMinVersionCode());
        Assert.assertTrue(m.isMandatory());
    }

    @Test
    public void floatVersionCodeIsTruncatedNotRejected() throws Exception {
        // Gson 把 4.0 读成 number, getAsInt 会截断成 4 而不是抛异常
        UpdateManifest m = UpdateManifest.fromJson(minimalJson(4, APK_URL, SHA)
                .replace("\"versionCode\":4", "\"versionCode\":4.0"));
        Assert.assertEquals(4, m.getVersionCode());
    }

    @Test
    public void missingOptionalFieldsFallBackToSafeDefaults() throws Exception {
        UpdateManifest m = UpdateManifest.fromJson(minimalJson(7, APK_URL, SHA));
        Assert.assertEquals("", m.getVersionName());
        Assert.assertEquals("", m.getNotes());
        Assert.assertEquals("", m.getPublishedAt());
        Assert.assertEquals("没声明大小就返回 -1, 让下载器跳过大小校验只认 sha256",
                -1L, m.getSizeBytes());
        Assert.assertEquals(1, m.getMinVersionCode());
        Assert.assertFalse(m.isMandatory());
    }

    @Test
    public void nullFieldsAreTreatedAsMissingNotAsCrash() throws Exception {
        String json = "{\"versionCode\":4,\"apkUrl\":\"" + APK_URL + "\",\"sha256\":\"" + SHA + "\","
                + "\"versionName\":null,\"notes\":null,\"sizeBytes\":null,\"mandatory\":null}";
        UpdateManifest m = UpdateManifest.fromJson(json);
        Assert.assertEquals("", m.getVersionName());
        Assert.assertEquals("", m.getNotes());
        Assert.assertEquals(-1L, m.getSizeBytes());
        Assert.assertFalse(m.isMandatory());
    }

    @Test
    public void negativeSizeBytesMeansUnknown() throws Exception {
        UpdateManifest m = UpdateManifest.fromJson(minimalJson(4, APK_URL, SHA)
                .replace("\"sha256\"", "\"sizeBytes\":-5,\"sha256\""));
        Assert.assertEquals(-1L, m.getSizeBytes());
    }

    @Test
    public void fileNameIsDerivedFromApkUrlWhenAbsent() throws Exception {
        UpdateManifest m = UpdateManifest.fromJson(minimalJson(4, APK_URL, SHA));
        Assert.assertEquals("zspacecarplayer-3.1.0.apk", m.getFileName());
    }

    @Test
    public void whitespaceAroundJsonIsIgnored() throws Exception {
        UpdateManifest m = UpdateManifest.fromJson("\n\t  " + fullJson() + "  \r\n");
        Assert.assertEquals(4, m.getVersionCode());
    }

    // ------------------------------------------------------------ 必须报错

    @Test
    public void rejectsNullEmptyAndBlankBody() {
        assertParseFails(null);
        assertParseFails("");
        assertParseFails("   \n\t ");
    }

    @Test
    public void rejectsHtmlCatchAllPageWithADiagnosableMessage() {
        // 现网真实形态: 路径不存在时站点回 200 + 一整张 SPA 首页
        String html = "<!doctype html>\n<html lang=\"zh-CN\"><head><title>Jarvis</title></head>"
                + "<body><div id=\"app\"></div></body></html>";
        try {
            UpdateManifest.fromJson(html);
            Assert.fail("HTML 兜底页绝不能被当成清单");
        } catch (UpdateManifest.ParseException e) {
            Assert.assertTrue("报错要点名兜底页, 否则实车无从下手: " + e.getMessage(),
                    e.getMessage().contains("不是 JSON"));
        }
    }

    @Test
    public void rejectsMalformedJson() {
        assertParseFails("{\"versionCode\":4,");
        assertParseFails("{versionCode:4}");
        assertParseFails("[1,2,3]");
        assertParseFails("\"just a string\"");
    }

    @Test
    public void rejectsMissingOrIllegalVersionCode() {
        assertParseFails("{\"apkUrl\":\"" + APK_URL + "\",\"sha256\":\"" + SHA + "\"}");
        assertParseFails(minimalJson(0, APK_URL, SHA));
        assertParseFails(minimalJson(-3, APK_URL, SHA));
        assertParseFails("{\"versionCode\":\"abc\",\"apkUrl\":\"" + APK_URL
                + "\",\"sha256\":\"" + SHA + "\"}");
    }

    @Test
    public void rejectsMissingOrNonHttpApkUrl() {
        assertParseFails("{\"versionCode\":4,\"sha256\":\"" + SHA + "\"}");
        assertParseFails(minimalJson(4, "", SHA));
        assertParseFails(minimalJson(4, "file:///sdcard/x.apk", SHA));
        assertParseFails(minimalJson(4, "javascript:alert(1)", SHA));
    }

    @Test
    public void rejectsMissingOrMalformedSha256() {
        // sha256 是「校验失败绝不安装」的唯一凭据, 缺了就没法证明包没被换过 → 按必填处理
        assertParseFails("{\"versionCode\":4,\"apkUrl\":\"" + APK_URL + "\"}");
        assertParseFails(minimalJson(4, APK_URL, ""));
        assertParseFails(minimalJson(4, APK_URL, "abc123"));
        assertParseFails(minimalJson(4, APK_URL, SHA.substring(0, 63)));
        assertParseFails(minimalJson(4, APK_URL, SHA.substring(0, 63) + "z"));
    }

    @Test
    public void describeCarriesTheFieldsNeededForFieldDebugging() throws Exception {
        String d = UpdateManifest.fromJson(fullJson()).describe();
        Assert.assertTrue(d, d.contains("vc=4"));
        Assert.assertTrue(d, d.contains("vn=3.1.0"));
        Assert.assertTrue(d, d.contains("mandatory=false"));
        Assert.assertTrue(d, d.contains("sha256=" + SHA));
    }

    private static void assertParseFails(String json) {
        try {
            UpdateManifest m = UpdateManifest.fromJson(json);
            Assert.fail("应当抛 ParseException, 却解析出了: " + m);
        } catch (UpdateManifest.ParseException expected) {
            Assert.assertNotNull("异常必须带可读原因", expected.getMessage());
            Assert.assertTrue("原因不能是空串", expected.getMessage().length() > 0);
        }
    }
}
