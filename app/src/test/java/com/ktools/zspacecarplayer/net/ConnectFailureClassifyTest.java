package com.ktools.zspacecarplayer.net;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import com.google.gson.JsonSyntaxException;

/**
 * 2026-09-12 #4: 「连接失败」必须可诊断。
 *
 * 实车反馈是界面显示"连接失败"、点刷新毫无反应。根因之一是底层无论超时、地址写错、
 * 密码错还是服务器 500, 一律只冒出一个泛化异常, UI 无从区分, 只能吐同一句文案。
 * JellyfinApiClient.classifyError / httpError 是把异常翻译成 ERR_* 类别的纯函数,
 * 直接决定车主看到的那一句提示与下一步指引, 因此单独锁死契约。
 *
 * 重点防的是继承关系造成的误判: SocketTimeoutException、UnknownHostException、
 * ConnectException、SSLException 全都是 IOException 的子类, 一旦判定顺序写错就会
 * 统统落到 ERR_NETWORK 兜底, 提示又退化成一句没用的"同步失败"。
 */
public class ConnectFailureClassifyTest {

    @Test
    public void testTimeoutFamilyMapsToTimeout() {
        // 离线时 OkHttp 抛的就是这个, 是最需要被认出来说"检查网络"的一类
        Assert.assertEquals(JellyfinApiClient.ERR_TIMEOUT,
                JellyfinApiClient.classifyError(new SocketTimeoutException("connect timed out")));
        Assert.assertEquals(JellyfinApiClient.ERR_TIMEOUT,
                JellyfinApiClient.classifyError(new SocketTimeoutException("timeout")));
        // OkHttp 的整轮超时走 InterruptedIOException("timeout"), 不能被当成普通 IO
        Assert.assertEquals(JellyfinApiClient.ERR_TIMEOUT,
                JellyfinApiClient.classifyError(new InterruptedIOException("timeout")));
    }

    @Test
    public void testUnreachableFamilyNotSwallowedByGenericIo() {
        Assert.assertEquals(JellyfinApiClient.ERR_UNREACHABLE,
                JellyfinApiClient.classifyError(new ConnectException("failed to connect")));
        Assert.assertEquals(JellyfinApiClient.ERR_UNREACHABLE,
                JellyfinApiClient.classifyError(new NoRouteToHostException("No route to host")));
    }

    @Test
    public void testDnsFailureSeparateFromUnreachable() {
        // 地址写错和没网是两种完全不同的处置: 前者要车主去设置页改地址
        Assert.assertEquals(JellyfinApiClient.ERR_DNS,
                JellyfinApiClient.classifyError(new UnknownHostException("jellyfin.local")));
    }

    @Test
    public void testTlsFailureRecognized() {
        // Android 4.3 老证书库场景的高发故障, 必须能和"断网"区分开
        Assert.assertEquals(JellyfinApiClient.ERR_TLS,
                JellyfinApiClient.classifyError(new SSLHandshakeException("handshake failed")));
        Assert.assertEquals(JellyfinApiClient.ERR_TLS,
                JellyfinApiClient.classifyError(new SSLException("certificate unknown")));
        Assert.assertEquals(JellyfinApiClient.ERR_TLS,
                JellyfinApiClient.classifyError(new CertificateException("trust anchor not found")));
    }

    @Test
    public void testParseAndGenericFallbacks() {
        Assert.assertEquals(JellyfinApiClient.ERR_PARSE,
                JellyfinApiClient.classifyError(new JsonSyntaxException("bad json")));
        Assert.assertEquals(JellyfinApiClient.ERR_NETWORK,
                JellyfinApiClient.classifyError(new IOException("connection reset")));
        Assert.assertEquals(JellyfinApiClient.ERR_UNKNOWN,
                JellyfinApiClient.classifyError(new RuntimeException("boom")));
        Assert.assertEquals(JellyfinApiClient.ERR_UNKNOWN, JellyfinApiClient.classifyError(null));
    }

    @Test
    public void testHttp401And403AreAuthFailures() {
        // 401 是「拿死 Token 反复撞」的判据: 上层据此清 Token 强制重登
        JellyfinApiClient.ApiException e401 = JellyfinApiClient.httpError(401, "Fetch items failed:");
        Assert.assertEquals(JellyfinApiClient.ERR_AUTH, e401.getKind());
        Assert.assertEquals(401, e401.getHttpCode());
        Assert.assertEquals(JellyfinApiClient.ERR_AUTH, JellyfinApiClient.classifyError(e401));
        Assert.assertEquals(JellyfinApiClient.ERR_AUTH,
                JellyfinApiClient.classifyError(JellyfinApiClient.httpError(403, "Auth failed:")));
    }

    @Test
    public void testHttp5xxIsServerFaultNotAuth() {
        // 5xx 只需重试, 绝不能误判成鉴权失效去清 Token 重登
        Assert.assertEquals(JellyfinApiClient.ERR_SERVER,
                JellyfinApiClient.classifyError(JellyfinApiClient.httpError(500, "Fetch items failed:")));
        Assert.assertEquals(JellyfinApiClient.ERR_SERVER,
                JellyfinApiClient.classifyError(JellyfinApiClient.httpError(503, "Fetch items failed:")));
    }

    @Test
    public void testOtherHttpCodesStayGeneric() {
        // 404 多半是服务器地址路径填错, 既不是鉴权也不是服务端故障
        JellyfinApiClient.ApiException e404 = JellyfinApiClient.httpError(404, "Fetch items failed:");
        Assert.assertEquals(JellyfinApiClient.ERR_HTTP, e404.getKind());
        Assert.assertEquals(404, e404.getHttpCode());
        Assert.assertTrue("提示里要带上原始状态码便于实车抓日志",
                e404.getMessage().contains("404"));
    }

    @Test
    public void testApiExceptionRoundTripsItsOwnKind() {
        JellyfinApiClient.ApiException budget =
                new JellyfinApiClient.ApiException(JellyfinApiClient.ERR_TIMEOUT, -1, "超出交互总时限");
        Assert.assertEquals(JellyfinApiClient.ERR_TIMEOUT, JellyfinApiClient.classifyError(budget));
        Assert.assertEquals(-1, budget.getHttpCode());
    }

    @Test
    public void testStaleTokenClearedSoNextRefreshRelogins() {
        JellyfinApiClient client = JellyfinApiClient.getInstance();
        client.setServerUrl("http://test.server/music");
        client.setAuthInfo("user-1", "dead-token");
        Assert.assertTrue(client.hasToken());

        // 收到 401 后必须把死 Token 清掉: 不清的话下次点刷新还是先拿它撞一遍
        client.clearAuth();
        Assert.assertFalse("失效 Token 必须被清除", client.hasToken());
        Assert.assertEquals("", client.getAccessToken());
        // 空 Token 下串流地址返回空串, 与 Service「等待鉴权后自动起播」的既有契约一致
        Assert.assertEquals("", client.getStreamUrl("ce0f2dd6cd326617841305dd96274b7f"));

        // 重复清除不得抛异常
        client.clearAuth();
        Assert.assertFalse(client.hasToken());
    }
}
