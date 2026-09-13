package com.ktools.zspacecarplayer.update;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * 远程升级 (2026-09-12): 升级判定与地址回退的纯逻辑回归。
 *
 * 这两个判定决定了「车机会不会提示升级」, 判错的代价是双向的:
 *  - 该提示不提示 → 车机永远停在 versionCode 3, 修好的 bug 一辆车都拿不到;
 *  - 不该提示乱提示 → 车主每次启动都被弹窗骚扰, 最后把这个功能当噪音关掉。
 * 都是纯函数, 所以把边界全钉死在这里。
 */
public class UpdateCheckerDecisionTest {

    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String URL = "https://web.kentonnie.top/zspace/update/x.apk";

    private static UpdateManifest manifest(int versionCode, int minVersionCode, boolean mandatory) {
        String json = "{\"versionCode\":" + versionCode
                + ",\"versionName\":\"3.1.0\""
                + ",\"apkUrl\":\"" + URL + "\""
                + ",\"sha256\":\"" + SHA + "\""
                + ",\"sizeBytes\":3071328"
                + ",\"minVersionCode\":" + minVersionCode
                + ",\"mandatory\":" + mandatory + "}";
        try {
            return UpdateManifest.fromJson(json);
        } catch (UpdateManifest.ParseException e) {
            throw new AssertionError("测试用的清单本身就不合法: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ isUpdateAvailable

    @Test
    public void newerManifestVersionCodeMeansUpdate() {
        Assert.assertTrue(UpdateChecker.isUpdateAvailable(manifest(4, 1, false), 3));
        Assert.assertTrue(UpdateChecker.isUpdateAvailable(manifest(5, 1, false), 3));
        Assert.assertTrue(UpdateChecker.isUpdateAvailable(manifest(100, 1, false), 99));
    }

    @Test
    public void equalVersionCodeIsNotAnUpdate() {
        // 相等必须是「已是最新」: 否则车主装完新版一启动又被提示, 永远关不掉
        Assert.assertFalse(UpdateChecker.isUpdateAvailable(manifest(3, 1, false), 3));
        Assert.assertFalse(UpdateChecker.isUpdateAvailable(manifest(1, 1, false), 1));
    }

    @Test
    public void olderManifestIsNotAnUpdate() {
        // 清单没跟上/灰度回滚时会出现 manifest < 本地, 一律不提示
        Assert.assertFalse(UpdateChecker.isUpdateAvailable(manifest(2, 1, false), 3));
        Assert.assertFalse(UpdateChecker.isUpdateAvailable(manifest(3, 1, false), 4));
    }

    @Test
    public void nullManifestIsNeverAnUpdate() {
        Assert.assertFalse(UpdateChecker.isUpdateAvailable(null, 3));
        Assert.assertFalse(UpdateChecker.shouldForceUpdate(null, 3));
    }

    @Test
    public void unknownCurrentVersionTreatsEveryManifestAsNewer() {
        // currentVersionCode() 取不到时返回 0: 宁可多提示一次, 也不能让车机永远升不了级
        Assert.assertTrue(UpdateChecker.isUpdateAvailable(manifest(1, 1, false), 0));
    }

    // ------------------------------------------------------------ shouldForceUpdate

    @Test
    public void mandatoryFlagForcesUpdate() {
        Assert.assertTrue(UpdateChecker.shouldForceUpdate(manifest(4, 1, true), 3));
        Assert.assertFalse(UpdateChecker.shouldForceUpdate(manifest(4, 1, false), 3));
    }

    @Test
    public void mandatoryForcesEvenWhenAlreadyUpToDate() {
        // 清单标了必须更新但本地已经是同一版本: 仍然按「必须」措辞, 提示车主重装/确认
        Assert.assertTrue(UpdateChecker.shouldForceUpdate(manifest(3, 1, true), 3));
    }

    @Test
    public void minVersionCodeAboveCurrentForcesUpdate() {
        // 旧版被服务端强制拉升: 当前 3 < 要求 5
        Assert.assertTrue(UpdateChecker.shouldForceUpdate(manifest(5, 5, false), 3));
        Assert.assertTrue(UpdateChecker.shouldForceUpdate(manifest(9, 4, false), 3));
    }

    @Test
    public void minVersionCodeAtOrBelowCurrentDoesNotForce() {
        Assert.assertFalse(UpdateChecker.shouldForceUpdate(manifest(4, 3, false), 3));
        Assert.assertFalse(UpdateChecker.shouldForceUpdate(manifest(4, 1, false), 3));
        Assert.assertFalse("边界: 恰好等于 minVersionCode 不算被强制",
                UpdateChecker.shouldForceUpdate(manifest(4, 4, false), 4));
    }

    @Test
    public void forcedUpdateWithoutNewerVersionStillPrompts() {
        // 契约里最容易漏的一种组合: 清单版本 == 本地版本, 但 minVersionCode 更高。
        // isUpdateAvailable 为 false, 却仍必须提示 —— UI 用的是 (newer || forced)。
        UpdateManifest m = manifest(3, 5, false);
        Assert.assertFalse(UpdateChecker.isUpdateAvailable(m, 3));
        Assert.assertTrue(UpdateChecker.shouldForceUpdate(m, 3));
        Assert.assertTrue("UI 的判定条件是 newer || forced",
                UpdateChecker.isUpdateAvailable(m, 3) || UpdateChecker.shouldForceUpdate(m, 3));
    }

    // ------------------------------------------------------------ 清单地址回退

    @Test
    public void resolveManifestUrlPrefersConfiguredValue() {
        Assert.assertEquals("https://example.com/latest.json",
                UpdateChecker.resolveManifestUrl("https://example.com/latest.json",
                        UpdateChecker.DEFAULT_MANIFEST_URL));
    }

    @Test
    public void resolveManifestUrlFallsBackWhenUnset() {
        // local.properties 没填 UPDATE_MANIFEST_URL 时 BuildConfig 会是空串, 必须回退默认地址
        Assert.assertEquals(UpdateChecker.DEFAULT_MANIFEST_URL,
                UpdateChecker.resolveManifestUrl(null, UpdateChecker.DEFAULT_MANIFEST_URL));
        Assert.assertEquals(UpdateChecker.DEFAULT_MANIFEST_URL,
                UpdateChecker.resolveManifestUrl("", UpdateChecker.DEFAULT_MANIFEST_URL));
        Assert.assertEquals(UpdateChecker.DEFAULT_MANIFEST_URL,
                UpdateChecker.resolveManifestUrl("   \t ", UpdateChecker.DEFAULT_MANIFEST_URL));
    }

    @Test
    public void resolveManifestUrlTrimsConfiguredValue() {
        Assert.assertEquals("https://example.com/a.json",
                UpdateChecker.resolveManifestUrl("  https://example.com/a.json  ", "fallback"));
    }

    @Test
    public void defaultManifestUrlIsTheAgreedEndpoint() {
        // 这个地址是与服务器侧约定死的, 改了要同步改服务端
        Assert.assertEquals("https://web.kentonnie.top/zspace/update/latest.json",
                UpdateChecker.DEFAULT_MANIFEST_URL);
    }

    @Test
    public void buildConfigValueOrFallbackNeverYieldsEmpty() {
        // manifestUrlOrDefault() 会读 BuildConfig (单测里是空串), 结果必须仍是可用地址
        String url = UpdateChecker.manifestUrlOrDefault();
        Assert.assertNotNull(url);
        Assert.assertTrue("生效地址不能是空串", url.trim().length() > 0);
        Assert.assertTrue("生效地址必须是 http(s): " + url,
                url.startsWith("http://") || url.startsWith("https://"));
    }

    // ------------------------------------------------------------ 错误文案

    @Test
    public void friendlyErrorCoversEveryNetworkFailureShape() {
        assertFriendly(new SocketTimeoutException("timeout"), "超时");
        assertFriendly(new UnknownHostException("web.kentonnie.top"), "解析");
        assertFriendly(new ConnectException("Connection refused"), "连不上");
        assertFriendly(new SSLException("handshake failed"), "证书");
        assertFriendly(new IOException("unexpected end of stream"), "网络异常");
        assertFriendly(new IllegalStateException("boom"), "检查更新失败");
    }

    @Test
    public void friendlyErrorKeepsHttpStatusCode() {
        // 实车排障第一条线索就是状态码, 不能被翻译掉
        String msg = UpdateChecker.friendlyError(new UpdateChecker.HttpException(404, "HTTP 404"));
        Assert.assertTrue(msg, msg.contains("404"));
    }

    @Test
    public void friendlyErrorExplainsManifestRejection() {
        String msg = UpdateChecker.friendlyError(
                new UpdateManifest.ParseException("清单缺少合法的 sha256"));
        Assert.assertTrue(msg, msg.contains("清单"));
        Assert.assertTrue("原始原因要带上, 否则车主截图给我们也看不出所以然",
                msg.contains("sha256"));
    }

    @Test
    public void friendlyErrorToleratesNullAndMessagelessThrowable() {
        Assert.assertNotNull(UpdateChecker.friendlyError(null));
        Assert.assertTrue(UpdateChecker.friendlyError(null).length() > 0);
        // 没有 message 的异常不能吐出 "null"
        String msg = UpdateChecker.friendlyError(new IOException());
        Assert.assertFalse(msg, msg.contains("null"));
        Assert.assertTrue(msg, msg.contains("IOException"));
    }

    private static void assertFriendly(Throwable t, String expectedKeyword) {
        String msg = UpdateChecker.friendlyError(t);
        Assert.assertNotNull(msg);
        Assert.assertTrue("文案应含「" + expectedKeyword + "」, 实际: " + msg,
                msg.contains(expectedKeyword));
        Assert.assertFalse("文案不该把英文异常原文直接甩给车主: " + msg,
                msg.contains("Exception:"));
    }
}
