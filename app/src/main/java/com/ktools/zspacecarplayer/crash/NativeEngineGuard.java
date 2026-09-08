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

    // ------------------------------------------------------------------ //
    //  崩溃证据归属：谁的 logcat 行才算我们的崩溃
    // ------------------------------------------------------------------ //

    private static final String[] FATAL_SIGNAL_MARKS = {
            "Fatal signal", "SIGSEGV", "SIGABRT", "tombstone"
    };

    private static final String[] FATAL_EXCEPTION_MARKS = {"FATAL EXCEPTION"};

    /**
     * logcat 里是否存在**属于指定进程**的 native 致命信号证据。
     *
     * 必须按 pid 过滤：车机上第三方进程（厂商服务、adb 的 {@code top}/{@code ps}）崩出
     * SIGPIPE/SIGSEGV 是家常便饭，而 {@code logcat -d} 的尾巴里混着它们的行。早先只做
     * 全文子串匹配，于是「别的进程崩了」会被记成 v3 引擎的 native 崩溃，攒满
     * {@link #CRASH_LIMIT} 次就把原生引擎永久熔断——用户看到的「一放歌就闪退」被误判到
     * 我们头上，而我们的引擎根本没崩。
     *
     * @param victimPid 上次会话的 pid（取自会话标记）。为空时退化为全文匹配，
     *                  调用方应据此下调证据置信度。
     */
    public static boolean hasFatalSignalForPid(String logcat, String victimPid) {
        return hasMarkForPid(logcat, victimPid, FATAL_SIGNAL_MARKS);
    }

    /** Java 未捕获异常的归属判定，语义同 {@link #hasFatalSignalForPid}。 */
    public static boolean hasFatalExceptionForPid(String logcat, String victimPid) {
        return hasMarkForPid(logcat, victimPid, FATAL_EXCEPTION_MARKS);
    }

    private static boolean hasMarkForPid(String logcat, String victimPid, String[] marks) {
        if (logcat == null || logcat.length() == 0) {
            return false;
        }
        boolean strict = victimPid != null && victimPid.length() > 0;
        String[] lines = logcat.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!containsAny(lines[i], marks)) {
                continue;
            }
            // 拿不到上次 pid（旧标记 / 标记被截断）时只能退回全文匹配，
            // 但调用方会把 confidence 降级，不再当作 high 铁证
            if (!strict || victimPid.equals(pidOf(lines[i]))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String line, String[] marks) {
        if (line == null) {
            return false;
        }
        for (int i = 0; i < marks.length; i++) {
            if (line.indexOf(marks[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取一行 logcat 的归属进程号，认两种真实形态：
     * <ul>
     * <li>前缀式 {@code F/libc(25261): Fatal signal 13 ...}；</li>
     * <li>debuggerd 正文式 {@code ... pid: 21288, tid: 21310 ... signal 11 (SIGSEGV)}——
     * 这种行的前缀 pid 是 debuggerd 自己的，必须优先取正文里的 {@code pid:}，
     * 否则我们自己进程的真崩溃会被判成「不是我们崩的」。</li>
     * </ul>
     * 认不出时返回空串（调用方按「不匹配」处理）。
     */
    static String pidOf(String line) {
        if (line == null) {
            return "";
        }
        String fromBody = readNumberAfter(line, "pid:");
        if (fromBody != null) {
            return fromBody;
        }
        String fromPrefix = readNumberInsideParen(line);
        return fromPrefix == null ? "" : fromPrefix;
    }

    /** 在 {@code key} 之后跳过空白取一串数字；没有数字则返回 null。 */
    private static String readNumberAfter(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) {
            return null;
        }
        int i = at + key.length();
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        return i > start ? line.substring(start, i) : null;
    }

    /** 取第一个括号里的数字，即 logcat 前缀的 pid 字段。 */
    private static String readNumberInsideParen(String line) {
        int open = line.indexOf('(');
        if (open < 0) {
            return null;
        }
        int i = open + 1;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i == start || i >= line.length() || line.charAt(i) != ')') {
            return null;
        }
        return line.substring(start, i);
    }
}
