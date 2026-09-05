package com.ktools.zspacecarplayer.util;

import org.junit.Assert;
import org.junit.Test;

public class PinyinUtilsTest {

    @Test
    public void testGetSearchKeywords() {
        String keywords = PinyinUtils.getSearchKeywords("周杰伦 晴天");
        // 应该提取出原字符串的 lowercase 以及声母首字母
        Assert.assertTrue(keywords.contains("周杰伦 晴天"));
        Assert.assertTrue(keywords.contains("zjl qt"));
    }
}
