package com.ktools.zspacecarplayer.util;

import java.nio.charset.Charset;

/**
 * 修复服务器元数据中的历史编码乱码 (纯 JVM, 可单测)。
 *
 * 曲库里大量老 MP3 带 GBK 编码的 ID3 标签, 服务端读取时编码误判, 导致
 * Jellyfin 返回的元数据混入乱码。实测库表中有三种形态:
 * <ul>
 * <li>B1: GBK 字节被按 Latin-1 读出再存成 UTF-8 —— 整串只含 ASCII + U+0080..U+00FF,
 *     如 "(C2A3=C2A8)(D6D0)(CEC4)(B0E6)(C2A3=C2A9)" 形态 (= HISTORY (中文版)), 可逆;</li>
 * <li>B2: GBK 字节被按 UTF-8 误读 —— 字符落在 U+0100..U+07FF,
 *     如 "(C6AE)(D1A9)" 形态 (= 飘雪), 可逆;</li>
 * <li>B3: 误读时产生了替换符 U+FFFD —— 原始字节已丢失, 不可逆, 保持原样。</li>
 * </ul>
 *
 * 修复策略极度保守, 宁可不修、不错修:
 * <ul>
 * <li>输入含 U+FFFD 直接原样返回 (字节已丢失, 任何"修复"都是编造);</li>
 * <li>B1 路径要求: 非 ASCII 字符全部在 Latin-1 范围内、个数 >=3、且至少一个
 *     非 ASCII 字符不属于常见西欧加重音符集合 (否则 "rêvés" 这类合法文字会被误修);</li>
 * <li>B2 路径要求: U+0100..U+2FFF 段字符 >=2 且占非 ASCII 字符半数以上
 *     (否则西里尔文字等会误走此路);</li>
 * <li>两条路径解码后必须: 无 U+FFFD、CJK 语系字符 >=2 且占原非 ASCII 字符半数以上。
 *     CJK 语系含 U+4E00..U+9FFF 表意文字 + U+3000..U+303F 中文标点 + U+FF00..U+FFEF
 *     全角标点 ("HISTORY (中文版)" 的圆括号在全角区)。</li>
 * </ul>
 *
 * 已知残余风险 (本曲库中文+英文为主, 概率极低, 记录在案):
 * 密集西欧重音符串 (如四个加重音字母连用) 与 B1 乱码结构上不可分;
 * 纯西里尔文短串与 B2 乱码结构上不可分。
 */
public final class TextRepair {

    private TextRepair() {}

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset GBK = Charset.forName("GBK");

    public static String repair(String s) {
        if (s == null || s.isEmpty()) return s;
        // B3: 原始字节已含替换符, 不可逆; 且 FFFD 的 UTF-8 字节序列 (EF BF BD)
        // 按 GBK 会解出 "锟斤拷" 而不产生 FFFD, 输出侧校验拦不住, 必须在输入侧拦截
        if (s.indexOf(0xFFFD) >= 0) return s;

        int nonAscii = countNonAscii(s);
        if (nonAscii < 2) return s;

        // B1: GBK -> Latin-1 误读。Java 的 ISO-8859-1 编码器对 U+0100 以上字符
        // 静默替换为 '?', 不会抛异常, 因此这里显式要求全串都在 Latin-1 范围内
        if (nonAscii >= 3 && allLatin1(s) && hasNonAccentSymbol(s)) {
            String d = new String(s.getBytes(LATIN1), GBK);
            if (acceptable(d, nonAscii)) return d;
        }

        // B2: GBK -> UTF-8 误读, 中段字符 (U+0100..U+2FFF) 占主导
        int hi2 = countRange(s, 0x0100, 0x2FFF);
        if (hi2 >= 2 && hi2 * 2 >= nonAscii) {
            String d = new String(s.getBytes(UTF8), GBK);
            if (acceptable(d, nonAscii)) return d;
        }

        return s;
    }

    private static int countNonAscii(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) n++;
        }
        return n;
    }

    private static int countRange(String s, int lo, int hi) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= lo && c <= hi) n++;
        }
        return n;
    }

    private static boolean allLatin1(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) return false;
        }
        return true;
    }

    /** 常见西欧加重音符集合: 这些字符是合法外文里最常见的非 ASCII 字符,
     *  纯由它们组成的字符串不像乱码。注意 D0 (ETH) / FE (THORN) / F0 不能入列,
     *  它们是真实 B1 乱码的常见成分 (如 "中" 的 Latin-1 误读含 D0)。 */
    private static boolean inAccentSet(char c) {
        if (c >= 0xC0 && c <= 0xC5) return true;
        if (c == 0xC7) return true;
        if (c >= 0xC8 && c <= 0xCF) return true;
        if (c >= 0xD1 && c <= 0xD6) return true;
        if (c >= 0xD8 && c <= 0xDD) return true;
        if (c == 0xDF) return true;
        if (c >= 0xE0 && c <= 0xE5) return true;
        if (c == 0xE7) return true;
        if (c >= 0xE8 && c <= 0xEF) return true;
        if (c == 0xF1) return true;
        if (c >= 0xF2 && c <= 0xF6) return true;
        if (c >= 0xF8 && c <= 0xFD) return true;
        if (c == 0xFF) return true;
        return false;
    }

    private static boolean hasNonAccentSymbol(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80 && !inAccentSet(c)) return true;
        }
        return false;
    }

    /** CJK 语系: 表意文字 + 中文标点 + 全角标点 (全角括号必须算, 否则 "HISTORY (中文版)" 被误拒) */
    private static boolean isCjkFamily(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFFEF);
    }

    /** 解码结果必须无替换符、CJK 语系 >=2 且占原非 ASCII 字符半数以上 */
    private static boolean acceptable(String decoded, int nonAscii) {
        if (decoded == null) return false;
        int cjk = 0;
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == 0xFFFD) return false;
            if (isCjkFamily(c)) cjk++;
        }
        return cjk >= 2 && cjk * 2 >= nonAscii;
    }
}
