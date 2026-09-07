package com.ktools.zspacecarplayer.crash;

/**
 * v3 原生引擎的连续崩溃熔断策略。
 *
 * 车机在现场没有第二次机会：原生引擎一旦在某台 8600 上稳定 SIGSEGV，用户看到的就是
 * 「一放歌就闪退」，而他不会自己去设置页关引擎。这里把判定收口成一条规则——连续
 * {@link #CRASH_LIMIT} 次可归因于原生引擎的致命崩溃，就强制回退系统 MediaPlayer 并记住，
 * 直到用户在设置页显式重开。
 *
 * 只用 java.*：策略与 SharedPreferences / Context 解耦，可 JVM 单测；落盘与改偏好由
 * {@link CrashMonitor} 负责。
 */
public final class NativeEngineGuard {

    /** 连续几次可归因崩溃后熔断 */
    public static final int CRASH_LIMIT = 3;

    private NativeEngineGuard() {}

    /**
     * 这次异常退出能否归因到 v3 原生引擎。
     *
     * 只认 native 致命信号与 Java 未捕获异常两类铁证；{@code abnormal_exit}
     * （会话标记还在但 logcat 里找不到证据）不算——用户划掉应用、LMK 回收都会留下
     * 这种标记，把它计入的话日常关 APP 就能攒满计数，熔断退化成误伤。
     * 上一次会话没启用过 v3 引擎时同样不归因，否则系统引擎的崩溃会白白把 v3 关掉。
     */
    public static boolean countsAsEngineCrash(String previousSessionKind,
                                              boolean v3ActiveInPreviousSession) {
        if (!v3ActiveInPreviousSession) {
            return false;
        }
        return CrashReport.KIND_NATIVE_CRASH.equals(previousSessionKind)
                || CrashReport.KIND_JAVA_CRASH.equals(previousSessionKind);
    }

    /**
     * 更新后的连续崩溃计数：归因本次退出则 +1，否则清零。
     *
     * 清零是「连续」语义的关键——中间只要有一次干净会话，就说明原生引擎在这台机器上
     * 跑得通，之前的计数不该继续累积到熔断。
     */
    public static int nextCrashCount(String previousSessionKind,
                                     boolean v3ActiveInPreviousSession,
                                     int recordedCrashes) {
        if (!countsAsEngineCrash(previousSessionKind, v3ActiveInPreviousSession)) {
            return 0;
        }
        return Math.max(0, recordedCrashes) + 1;
    }

    /**
     * 是否应当处于熔断状态。
     *
     * 单向锁存：一旦熔断就恒为 true，只有用户在设置页显式重开
     * （{@link CrashMonitor#resetEngineGuard()}）才能清除。不能只看当前连击数——
     * 熔断后引擎已换成系统 MediaPlayer，之后的会话必然「不可归因」而把连击清零，
     * 若标志跟着清零，用户正常关一次 APP 就能让 v3 悄悄复活，回到「一放歌就闪退」
     * 的死循环。
     */
    public static boolean shouldAutoDisable(int crashCount, boolean alreadyAutoDisabled) {
        return alreadyAutoDisabled || crashCount >= CRASH_LIMIT;
    }
}
