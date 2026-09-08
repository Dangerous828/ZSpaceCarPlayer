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

    // ---- 崩溃证据归属：夹具是 2026-09-08 吉利 8600 真车 logcat 原文 ---- //

    /** APP 自己的 pid；下面两条 SIGPIPE 来自 adb shell 的 top / ps，属第三方进程 */
    private static final String OUR_PID = "21288";

    private static final String FOREIGN_FATALS =
            "09-08 08:43:08.000 F/libc    (25261): Fatal signal 13 (SIGPIPE) at 0x000062ad (code=0), thread 25261 (ps)\n"
            + "09-08 08:43:27.030 F/libc    (25500): Fatal signal 13 (SIGPIPE) at 0x0000639c (code=0), thread 25500 (top)\n"
            + "09-08 08:43:27.100 I/AudioPlayerService(21288): Player engine: DspAudioTrackPlayer\n";

    @Test
    public void anotherProcessFatalIsNotOurCrash() {
        // 旧实现只做全文子串匹配，这两行会把 v3 引擎记上一笔 native 崩溃
        assertFalse(NativeEngineGuard.hasFatalSignalForPid(FOREIGN_FATALS, OUR_PID));
        assertFalse(NativeEngineGuard.hasFatalExceptionForPid(FOREIGN_FATALS, OUR_PID));
    }

    @Test
    public void ourOwnFatalSignalIsAttributedToUs() {
        String logcat = FOREIGN_FATALS
                + "09-08 08:44:01.200 F/libc    (21288): Fatal signal 11 (SIGSEGV) at 0x00000000 (code=1), thread 21310 (DspAudioTrack)\n";
        assertTrue(NativeEngineGuard.hasFatalSignalForPid(logcat, OUR_PID));
    }

    @Test
    public void debuggerdBodyPidWinsOverItsOwnPrefixPid() {
        // debuggerd 行的前缀 pid 是 debuggerd 自己的 (200)，真凶在正文 pid: 里
        String line = "09-08 08:44:01.300 F/DEBUG   (  200): pid: 21288, tid: 21310, name DspAudioTrack  "
                + ">>> com.ktools.zspacecarplayer <<< signal 11 (SIGSEGV), fault addr 0x0\n";
        assertTrue(NativeEngineGuard.hasFatalSignalForPid(line, OUR_PID));
        assertFalse(NativeEngineGuard.hasFatalSignalForPid(line, "200"));
        assertEquals("21288", NativeEngineGuard.pidOf(line));
    }

    @Test
    public void missingPidFallsBackToLooseMatch() {
        // 会话标记取不到 pid 时只能退回全文判定，由调用方把 confidence 降级
        assertTrue(NativeEngineGuard.hasFatalSignalForPid(FOREIGN_FATALS, ""));
        assertTrue(NativeEngineGuard.hasFatalSignalForPid(FOREIGN_FATALS, null));
    }

    @Test
    public void javaFatalExceptionAlsoRequiresOurPid() {
        String logcat = "09-08 08:45:00.000 E/AndroidRuntime( 3380): FATAL EXCEPTION: main\n";
        assertFalse(NativeEngineGuard.hasFatalExceptionForPid(logcat, OUR_PID));
        assertTrue(NativeEngineGuard.hasFatalExceptionForPid(
                "09-08 08:45:00.000 E/AndroidRuntime(21288): FATAL EXCEPTION: main\n", OUR_PID));
    }

    @Test
    public void emptyLogcatHasNoEvidence() {
        assertFalse(NativeEngineGuard.hasFatalSignalForPid("", OUR_PID));
        assertFalse(NativeEngineGuard.hasFatalSignalForPid(null, OUR_PID));
        assertFalse(NativeEngineGuard.hasFatalExceptionForPid(null, OUR_PID));
    }
}
