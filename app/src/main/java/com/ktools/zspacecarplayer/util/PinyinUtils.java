package com.ktools.zspacecarplayer.util;

public class PinyinUtils {

    /**
     * 提取字符串的汉字声母/拼音首字母与原串组合，用于车载拼音模糊搜索
     * 例如: "周杰伦 晴天" -> "周杰伦 晴天 zjl qt"
     */
    public static String getSearchKeywords(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder initials = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                initials.append(Character.toLowerCase(c));
            } else if (c == ' ') {
                initials.append(' ');
            } else if (isChineseChar(c)) {
                char p = getFirstLetterOfChar(c);
                if (p != 0) {
                    initials.append(p);
                }
            }
        }
        return input.toLowerCase() + " " + initials.toString();
    }

    private static boolean isChineseChar(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    /**
     * GB2312 常用汉字区段提取声母首字母 (Android 4.3 低版本零依赖)
     */
    private static char getFirstLetterOfChar(char ch) {
        try {
            byte[] bytes = String.valueOf(ch).getBytes("GB2312");
            if (bytes.length < 2) return Character.toLowerCase(ch);
            int code = (bytes[0] + 256) * 256 + (bytes[1] + 256);
            if (code >= 45217 && code <= 45252) return 'a';
            if (code >= 45253 && code <= 45760) return 'b';
            if (code >= 45761 && code <= 46317) return 'c';
            if (code >= 46318 && code <= 46825) return 'd';
            if (code >= 46826 && code <= 47009) return 'e';
            if (code >= 47010 && code <= 47296) return 'f';
            if (code >= 47297 && code <= 47613) return 'g';
            if (code >= 47614 && code <= 48118) return 'h';
            if (code >= 48119 && code <= 49061) return 'j';
            if (code >= 49062 && code <= 49323) return 'k';
            if (code >= 49324 && code <= 49895) return 'l';
            if (code >= 49896 && code <= 50370) return 'm';
            if (code >= 50371 && code <= 50613) return 'n';
            if (code >= 50614 && code <= 50621) return 'o';
            if (code >= 50622 && code <= 50905) return 'p';
            if (code >= 50906 && code <= 51386) return 'q';
            if (code >= 51387 && code <= 51445) return 'r';
            if (code >= 51446 && code <= 52217) return 's';
            if (code >= 52218 && code <= 52697) return 't';
            if (code >= 52698 && code <= 52979) return 'w';
            if (code >= 52980 && code <= 53688) return 'x';
            if (code >= 53689 && code <= 54480) return 'y';
            if (code >= 54481 && code <= 55289) return 'z';
        } catch (Exception ignored) {}
        return Character.toLowerCase(ch);
    }
}
