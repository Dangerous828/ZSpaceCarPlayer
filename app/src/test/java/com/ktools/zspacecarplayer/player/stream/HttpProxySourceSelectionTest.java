package com.ktools.zspacecarplayer.player.stream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 见 {@link HttpProxyServer#pickReporterIndex}：同一首歌同一时刻可以有好几条活动源
 * （重定位分出的 fork、预取晋升），指示器问错了对象就会读出互相矛盾的数字。
 *
 * <p>触发这条的真实上报（2026-10-08，vc21）：相隔 0.8 秒的两行写着
 * {@code upstream conns=4 socket=900627B} 与 {@code conns=1 socket=5209600B}。旧实现按
 * 登记顺序取<b>最早</b>那条，两行问的其实是两条源；而 conns 是"存活源之和"，旧源一关就
 * 凭空变小——"这首歌只用了一条连接"这个结论会从假象里来。
 */
public class HttpProxySourceSelectionTest {

    @Test
    public void rejectsEmptyAndMismatchedInput() {
        assertEquals("没源就没得选", -1,
                HttpProxyServer.pickReporterIndex(new int[0], new long[0]));
        assertEquals(-1, HttpProxyServer.pickReporterIndex(null, new long[]{1L}));
        assertEquals(-1, HttpProxyServer.pickReporterIndex(new int[]{1}, null));
        assertEquals("两个数组长度不一致是调用方写错了，宁可不选", -1,
                HttpProxyServer.pickReporterIndex(new int[]{1, 1}, new long[]{5L}));
    }

    /** 有读者=正在被消费，这一条压倒进度更靠前的。 */
    @Test
    public void readerCountOutranksProgress() {
        assertEquals("老源下载头更靠前，但新源才有读者", 1,
                HttpProxyServer.pickReporterIndex(new int[]{0, 1}, new long[]{9_000_000L, 100L}));
        assertEquals("读者多的赢，不看谁更大", 2,
                HttpProxyServer.pickReporterIndex(new int[]{1, 1, 2},
                        new long[]{9_000_000L, 8_000_000L, 1_000L}));
    }

    /** 全部没读者（预取还没被接管、或切歌空档）时退化成"下载头最靠前的"。 */
    @Test
    public void fallsBackToMostAdvancedWhenNobodyIsReading() {
        assertEquals(1, HttpProxyServer.pickReporterIndex(new int[]{0, 0, 0},
                new long[]{1_000L, 7_000_000L, 2_000L}));
        assertEquals("完全并列取最先登记的，保证同一个 tick 问到的始终是同一条", 0,
                HttpProxyServer.pickReporterIndex(new int[]{0, 0}, new long[]{5L, 5L}));
    }
}
