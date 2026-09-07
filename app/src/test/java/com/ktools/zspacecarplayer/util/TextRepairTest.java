package com.ktools.zspacecarplayer.util;

import org.junit.Test;

import java.io.UnsupportedEncodingException;

import static org.junit.Assert.assertEquals;

/**
 * 乱码修复单测。样本来自实机 DB dump 的真实乱码行 (2026-09-02)。
 * 所有非 ASCII 字面量必须用 Unicode 转义或字节数组构造 (构建链会二次编码裸非 ASCII)。
 */
public class TextRepairTest {

    /** 实机 DB 里存的是 UTF-8 字节流, 先按 UTF-8 还原成字符串再交给 repair */
    private static String utf8(byte... bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- B1: GBK 被按 Latin-1 误读 (字符全在 U+0080..U+00FF) ----------------

    @Test
    public void repairsHistoryTitle() {
        // 存储字节 = UTF-8("HISTORY£¨ÖÐÎÄ°æ£©"): £=C2A3 Ö=C396 Ð=C390 Î=C38E Ä=C384 °=C2B0 æ=C3A6
        String in = utf8((byte) 0x48, (byte) 0x49, (byte) 0x53, (byte) 0x54, (byte) 0x4F,
                (byte) 0x52, (byte) 0x59, (byte) 0xC2, (byte) 0xA3, (byte) 0xC2, (byte) 0xA8,
                (byte) 0xC3, (byte) 0x96, (byte) 0xC3, (byte) 0x90, (byte) 0xC3, (byte) 0x8E,
                (byte) 0xC3, (byte) 0x84, (byte) 0xC2, (byte) 0xB0, (byte) 0xC3, (byte) 0xA6,
                (byte) 0xC2, (byte) 0xA3, (byte) 0xC2, (byte) 0xA9);
        assertEquals("HISTORY\uff08\u4e2d\u6587\u7248\uff09", TextRepair.repair(in));
    }

    @Test
    public void repairsMamaTitle() {
        // "MAMA" + GBK(（Chinese Ver.）)
        String in = utf8((byte) 0x4D, (byte) 0x41, (byte) 0x4D, (byte) 0x41,
                (byte) 0xC2, (byte) 0xA3, (byte) 0xC2, (byte) 0xA8,
                (byte) 0x43, (byte) 0x68, (byte) 0x69, (byte) 0x6E, (byte) 0x65,
                (byte) 0x73, (byte) 0x65, (byte) 0x20, (byte) 0x56, (byte) 0x65,
                (byte) 0x72, (byte) 0x2E,
                (byte) 0xC2, (byte) 0xA3, (byte) 0xC2, (byte) 0xA9);
        assertEquals("MAMA\uff08Chinese Ver.\uff09", TextRepair.repair(in));
    }

    // ---------------- B2: GBK 被按 UTF-8 误读 (字符落在 U+0100..U+07FF) ----------------

    @Test
    public void repairsPiaoxue() {
        // GBK(飘雪)=C6AE D1A9 被按 UTF-8 误读成 U+01AE U+0469 (实机 DB 样本 "Ʈѩ")
        assertEquals("\u98D8\u96EA", TextRepair.repair("\u01AE\u0469"));
    }

    @Test
    public void repairsTanyong() {
        // GBK(谭咏)=CCB7 D3BD 被按 UTF-8 误读成 U+0337 U+04FD (实机 DB 样本 "̷ӽ")
        assertEquals("\u8C2D\u548F", TextRepair.repair("\u0337\u04FD"));
    }

    // ---------------- 幂等: 缓存写侧存已修复文本, 读侧会再修一次 ----------------

    @Test
    public void repairIsIdempotentOnAlreadyRepairedText() {
        String[] repaired = {
                "HISTORY\uff08\u4e2d\u6587\u7248\uff09",
                "MAMA\uff08Chinese Ver.\uff09",
                "\u98D8\u96EA",
                "\u8C2D\u548F",
                "\u8C2D\u548F\u9E9F - \u8BB2\u4E0D\u51FA\u518D\u89C1",
                "Kelly Clarkson\uff08\u51EF\u8389\u00B7\u514B\u83B1\u68EE\uff09",
                "Monica",
                "Caf\u00E9",
                "Sigur R\u00F3s",
        };
        for (String s : repaired) {
            assertEquals(s, TextRepair.repair(TextRepair.repair(s)));
        }
    }

    // ---------------- 合法文字必须原样返回 ----------------

    @Test
    public void keepsProperChinese() {
        assertEquals("\u8C2D\u548F\u9E9F - \u8BB2\u4E0D\u51FA\u518D\u89C1",
                TextRepair.repair("\u8C2D\u548F\u9E9F - \u8BB2\u4E0D\u51FA\u518D\u89C1"));
    }

    @Test
    public void keepsProperMixedWithFullwidthParens() {
        assertEquals("Kelly Clarkson\uff08\u51EF\u8389\u00B7\u514B\u83B1\u68EE\uff09",
                TextRepair.repair("Kelly Clarkson\uff08\u51EF\u8389\u00B7\u514B\u83B1\u68EE\uff09"));
    }

    @Test
    public void keepsAscii() {
        assertEquals("Monica", TextRepair.repair("Monica"));
    }

    @Test
    public void keepsCafe() {
        assertEquals("Caf\u00E9", TextRepair.repair("Caf\u00E9"));
    }

    @Test
    public void keepsNaoE() {
        assertEquals("n\u00E3o \u00E9", TextRepair.repair("n\u00E3o \u00E9"));
    }

    @Test
    public void keepsSigurRos() {
        assertEquals("Sigur R\u00F3s", TextRepair.repair("Sigur R\u00F3s"));
    }

    @Test
    public void keepsSebastien() {
        assertEquals("S\u00E9bastien \u00E9ge", TextRepair.repair("S\u00E9bastien \u00E9ge"));
    }

    @Test
    public void keepsDenseAccents() {
        // 与 B1 乱码结构上不可分, 但全是加重音符且 <3 个非重音符号, 必须保守不修
        assertEquals("\u00E3\u00E9\u00F5\u00F2", TextRepair.repair("\u00E3\u00E9\u00F5\u00F2"));
    }

    @Test
    public void keepsReves() {
        assertEquals("r\u00EAves", TextRepair.repair("r\u00EAves"));
    }

    @Test
    public void keepsSingleMisreadHanzi() {
        // 单字误读 (2 字节) 信息量不足, 不修
        assertEquals(utf8((byte) 0xC4, (byte) 0xA7), TextRepair.repair(utf8((byte) 0xC4, (byte) 0xA7)));
    }

    // ---------------- B3: 含 U+FFFD 不可逆, 任何路径都不许"修复" ----------------

    @Test
    public void keepsFffdPadded() {
        assertEquals("DAN\uFFFD\uFFFDDAN", TextRepair.repair("DAN\uFFFD\uFFFDDAN"));
    }

    @Test
    public void keepsFffdCyrillic() {
        assertEquals(utf8((byte) 0xD0, (byte) 0xA6) + "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD",
                TextRepair.repair(utf8((byte) 0xD0, (byte) 0xA6) + "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD"));
    }

    @Test
    public void keepsFffdWithB2LookalikePrefix() {
        // 回归: "123" + U+013E U+0377 + U+FFFD×2 —— FFFD 的 UTF-8 字节 EF BF BD 按 GBK
        // 会解出 \u951F\u65A4\u62F7 且不产生 FFFD, 输出侧校验拦不住, 输入侧必须先拦
        assertEquals("123\u013E\u0377\uFFFD\uFFFD", TextRepair.repair("123\u013E\u0377\uFFFD\uFFFD"));
    }

    @Test
    public void keepsSingleHiChar() {
        // U+04BD 单个中段字符, hi2=1 < 2, 不修
        assertEquals("z\u04BD", TextRepair.repair("z\u04BD"));
    }
}
