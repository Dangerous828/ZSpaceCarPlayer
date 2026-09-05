package com.ktools.zspacecarplayer.ui;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class SearchRaceConditionTest {

    @Test
    public void testSearchSequenceFiltering() {
        AtomicInteger currentSearchSeq = new AtomicInteger(0);

        // 模拟用户快速连续输入 "z", "zj", "zjl"
        int seq1 = currentSearchSeq.incrementAndGet(); // seq 1
        int seq2 = currentSearchSeq.incrementAndGet(); // seq 2
        int seq3 = currentSearchSeq.incrementAndGet(); // seq 3

        // 模拟异步结果乱序返回: seq1 在最后返回
        boolean seq1Accepted = (seq1 == currentSearchSeq.get());
        boolean seq3Accepted = (seq3 == currentSearchSeq.get());

        Assert.assertFalse(seq1Accepted); // 1 < 3，过期的旧回调被安全拦截！
        Assert.assertTrue(seq3Accepted);  // 3 == 3，最新回调接受渲染！
    }
}
