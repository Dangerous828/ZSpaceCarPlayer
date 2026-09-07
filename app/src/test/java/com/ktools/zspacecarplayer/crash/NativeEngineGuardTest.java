package com.ktools.zspacecarplayer.crash;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v3 原生引擎熔断策略单测。
 *
 * 关注两类误判：把正常关闭 APP 当成引擎崩溃（会误伤，用户莫名被切回系统引擎），
 * 以及把系统引擎的崩溃算到 v3 头上（同样误伤）。真崩溃必须连续攒满阈值才熔断。
 */
public class NativeEngineGuardTest {

    @Test
    public void nativeAndJavaCrashesWithV3ActiveAreAttributed() {
        assertTrue(NativeEngineGuard.countsAsEngineCrash(CrashReport.KIND_NATIVE_CRASH, true));
        assertTrue(NativeEngineGuard.countsAsEngineCrash(CrashReport.KIND_JAVA_CRASH, true));
    }

    @Test
    public void abnormalExitIsNeverAttributed() {
        // 用户划掉应用 / LMK 回收都会留下会话标记但找不到致命信号证据
        assertFalse(NativeEngineGuard.countsAsEngineCrash(CrashReport.KIND_ABNORMAL_EXIT, true));
        assertFalse(NativeEngineGuard.countsAsEngineCrash(CrashReport.KIND_MAIN_THREAD_BLOCKED, true));
        assertFalse(NativeEngineGuard.countsAsEngineCrash(null, true));
    }

    @Test
    public void crashesWhileSystemEngineIsInUseAreNotAttributed() {
        assertFalse(NativeEngineGuard.countsAsEngineCrash(CrashReport.KIND_NATIVE_CRASH, false));
        assertEquals(0, NativeEngineGuard.nextCrashCount(CrashReport.KIND_NATIVE_CRASH, false, 2));
    }

    @Test
    public void breakerTripsOnlyAfterThreeConsecutiveAttributedCrashes() {
        int count = 0;
        for (int crash = 1; crash <= NativeEngineGuard.CRASH_LIMIT; crash++) {
            count = NativeEngineGuard.nextCrashCount(CrashReport.KIND_NATIVE_CRASH, true, count);
            assertEquals(crash, count);
            assertEquals(crash >= NativeEngineGuard.CRASH_LIMIT,
                    NativeEngineGuard.shouldAutoDisable(count, false));
        }
        // 熔断后继续崩也不许把计数涨成负数或回落
        assertTrue(NativeEngineGuard.shouldAutoDisable(
                NativeEngineGuard.nextCrashCount(CrashReport.KIND_JAVA_CRASH, true, count), false));
    }

    @Test
    public void aCleanSessionClearsTheStreak() {
        // 中间只要有一次不可归因的退出，之前的计数就不再累积
        assertEquals(0, NativeEngineGuard.nextCrashCount(CrashReport.KIND_ABNORMAL_EXIT, true, 2));
        assertFalse(NativeEngineGuard.shouldAutoDisable(
                NativeEngineGuard.nextCrashCount(CrashReport.KIND_ABNORMAL_EXIT, true, 2), false));
    }

    /**
     * 回归：熔断一旦落定就是单向的。
     *
     * 熔断后引擎已换成系统 MediaPlayer，之后的会话必然「不可归因」而把连击清零；
     * 若锁存跟着连击一起清零，用户正常关一次 APP 就能让 v3 悄悄复活，回到
     * 「一放歌就闪退」的死循环——模拟器上实测到过这个行为。
     */
    @Test
    public void aCleanSessionAfterTheBreakerTripsKeepsItLatched() {
        int latched = NativeEngineGuard.nextCrashCount(
                CrashReport.KIND_NATIVE_CRASH, true, NativeEngineGuard.CRASH_LIMIT - 1);
        assertTrue(NativeEngineGuard.shouldAutoDisable(latched, false));

        int streakAfterCleanExit = NativeEngineGuard.nextCrashCount(
                CrashReport.KIND_ABNORMAL_EXIT, false, latched);
        assertEquals(0, streakAfterCleanExit);
        assertTrue(NativeEngineGuard.shouldAutoDisable(streakAfterCleanExit, true));

        // 已锁存时即便再来一次可归因崩溃，判定也不许翻转
        int streakAfterAnotherCrash = NativeEngineGuard.nextCrashCount(
                CrashReport.KIND_NATIVE_CRASH, true, streakAfterCleanExit);
        assertTrue(NativeEngineGuard.shouldAutoDisable(streakAfterAnotherCrash, true));
    }

    @Test
    public void corruptedStoredCountStillCountsFromOne() {
        assertEquals(1, NativeEngineGuard.nextCrashCount(CrashReport.KIND_NATIVE_CRASH, true, -5));
        assertFalse(NativeEngineGuard.shouldAutoDisable(1, false));
    }
}
