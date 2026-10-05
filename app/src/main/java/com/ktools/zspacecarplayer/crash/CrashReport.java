package com.ktools.zspacecarplayer.crash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份崩溃 / 异常退出报告。
 *
 * 序列化为 JSON 上传。刻意不引第三方 JSON 库（依赖已锁死，且 org.json 在 JVM
 * 单测里是 android.jar 的空壳），自己拼串并做转义。
 *
 * 只用 java.*，便于单测。
 */
public final class CrashReport {

    /** Java 未捕获异常 */
    public static final String KIND_JAVA_CRASH = "java_crash";
    /** native 层致命信号（SIGSEGV 等）。Java 侧拿不到栈，靠 logcat crash 缓冲区还原 */
    public static final String KIND_NATIVE_CRASH = "native_crash";
    /** 上次会话没有正常收尾，但 logcat 里找不到致命信号证据 */
    public static final String KIND_ABNORMAL_EXIT = "abnormal_exit";
    /** 主线程被挂住（车机上的「卡死」）。进程没死，所以没有崩溃栈，只有线程快照 */
    public static final String KIND_MAIN_THREAD_BLOCKED = "main_thread_blocked";

    private final String reportId;
    private final String kind;
    private final long timestamp;
    private final Map<String, String> fields = new LinkedHashMap<String, String>();
    private final List<String> breadcrumbs = new ArrayList<String>();
    private String stackTrace = "";
    private String threadDump = "";
    private String logcat = "";

    public CrashReport(String reportId, String kind, long timestamp) {
        this.reportId = reportId;
        this.kind = kind;
        this.timestamp = timestamp;
    }

    public String getReportId() {
        return reportId;
    }

    public String getKind() {
        return kind;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /** 扁平字符串字段（设备信息、版本号、播放上下文等）。null 值忽略。 */
    public CrashReport put(String key, String value) {
        if (key != null && value != null) {
            fields.put(key, value);
        }
        return this;
    }

    public CrashReport put(String key, long value) {
        return put(key, Long.toString(value));
    }

    public CrashReport put(String key, boolean value) {
        return put(key, Boolean.toString(value));
    }

    public CrashReport setStackTrace(String trace) {
        this.stackTrace = trace == null ? "" : trace;
        return this;
    }

    public CrashReport setThreadDump(String dump) {
        this.threadDump = dump == null ? "" : dump;
        return this;
    }

    public CrashReport setLogcat(String text) {
        this.logcat = text == null ? "" : text;
        return this;
    }

    public CrashReport addBreadcrumbs(List<String> entries) {
        if (entries != null) {
            breadcrumbs.addAll(entries);
        }
        return this;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(1024 + stackTrace.length() + logcat.length());
        sb.append('{');
        appendStringField(sb, "reportId", reportId, true);
        appendStringField(sb, "kind", kind, false);
        sb.append(",\"timestamp\":").append(timestamp);
        for (Map.Entry<String, String> e : fields.entrySet()) {
            appendStringField(sb, e.getKey(), e.getValue(), false);
        }
        sb.append(",\"breadcrumbs\":[");
        for (int i = 0; i < breadcrumbs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(breadcrumbs.get(i))).append('"');
        }
        sb.append(']');
        appendStringField(sb, "stackTrace", stackTrace, false);
        appendStringField(sb, "threadDump", threadDump, false);
        appendStringField(sb, "logcat", logcat, false);
        sb.append('}');
        return sb.toString();
    }

    private static void appendStringField(StringBuilder sb, String key, String value,
                                          boolean first) {
        sb.append(first ? '"' : ",\"").append(escape(key)).append("\":\"")
                .append(escape(value)).append('"');
    }

    /** JSON 字符串转义。控制字符统一转 \\uXXXX，避免栈里的 \t/\b 破坏结构。 */
    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        String text = redact(raw);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static final String REDACTED = "[REDACTED]";

    /**
     * 密钥的三种落地形态：查询串 `api_key=xxx`、经本地代理 URL 编码后的
     * `api_key%3Dxxx`、以及 logcat 里请求头转储的 `X-Emby-Token: xxx`。
     * 值里排除 % —— 编码形态的分隔符是 %26，这样正好在值末尾停住。
     */
    private static final java.util.regex.Pattern SECRET_PARAM =
            java.util.regex.Pattern.compile(
                    "(?i)(api_?key|access_?token|x-emby-token|x-mediabrowser-token)"
                            + "(?:=|%3D|:)\\s*([^&\"'\\s,;)%]+)");

    /** 认证载荷 {"Username":"car","Pw":"…"}：密码比 api_key 更不能出门 */
    private static final java.util.regex.Pattern PASSWORD_FIELD =
            java.util.regex.Pattern.compile("(?i)(\"Pw\"\\s*:\\s*\")([^\"]*)(\")");

    /**
     * 抹掉报告里的凭据。
     *
     * 面包屑 "doPrepare begin path=…" 与 logcat 都会带上 Jellyfin 串流地址，而地址里
     * 就挂着 api_key；本地代理还会把它整体 URL 编码一次，于是同一个密钥在一份报告里
     * 以两种形态出现。报告是要 POST 出公网的，凭据跟着走等于把车机账号交出去。
     */
    static String redact(String raw) {
        if (raw == null || raw.length() == 0) {
            return raw == null ? "" : raw;
        }
        java.util.regex.Matcher m = SECRET_PARAM.matcher(raw);
        StringBuffer sb = new StringBuffer(raw.length() + 16);
        while (m.find()) {
            m.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(m.group(1) + "=" + REDACTED));
        }
        m.appendTail(sb);
        String out = sb.toString();

        m = PASSWORD_FIELD.matcher(out);
        sb = new StringBuffer(out.length() + 16);
        while (m.find()) {
            m.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(m.group(1) + REDACTED + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 把异常栈（含 cause 链）摊平成字符串 */
    public static String stackTraceOf(Throwable t) {
        if (t == null) {
            return "";
        }
        java.io.StringWriter sw = new java.io.StringWriter(512);
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /**
     * 抓全部线程栈。主线程卡死时 Java 侧没有异常，这份快照是唯一的现场证据
     * （能直接看出 main 停在哪把锁 / 哪个 join 上）。
     */
    public static String dumpAllThreads() {
        StringBuilder sb = new StringBuilder(4096);
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        // main 放最前：排查卡死时第一眼要看到的就是它
        Thread main = null;
        for (Thread t : all.keySet()) {
            if ("main".equals(t.getName())) {
                main = t;
                break;
            }
        }
        if (main != null) {
            appendThread(sb, main, all.get(main));
        }
        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            if (e.getKey() != main) {
                appendThread(sb, e.getKey(), e.getValue());
            }
        }
        return sb.toString();
    }

    /**
     * 只 dump 调用线程自己的栈。
     *
     * 异常退出后的首次启动 (CrashMonitor.inspectPreviousSession) 原先用 {@link #dumpAllThreads()}，
     * 抓的却是**新进程**的线程快照——它解释不了上一次为什么退，却要在 Application.onCreate 的
     * 主线程上等 {@code Thread.getAllStackTraces()} 把每个线程各停一遍 (2026-10-05 实测当日
     * 多条 abnormal_exit 的 threadDump 内容全是 CrashMonitor 自己的启动栈，零归因价值)。
     * 改成只记当前线程：字段仍在、成本消失。真正需要全现场的另外两处 (主线程卡死看门狗、
     * Java 未捕获异常) 继续用 dumpAllThreads()。
     */
    public static String dumpCurrentThreadStack() {
        StringBuilder sb = new StringBuilder(1024);
        Thread t = Thread.currentThread();
        appendThread(sb, t, t.getStackTrace());
        return sb.toString();
    }

    private static void appendThread(StringBuilder sb, Thread t, StackTraceElement[] frames) {
        sb.append('"').append(t.getName()).append("\" ")
                .append(t.isDaemon() ? "daemon " : "")
                .append("prio=").append(t.getPriority())
                .append(" state=").append(t.getState())
                .append('\n');
        if (frames == null || frames.length == 0) {
            sb.append("  (no frames)\n");
        } else {
            for (StackTraceElement f : frames) {
                sb.append("  at ").append(f.toString()).append('\n');
            }
        }
        sb.append('\n');
    }
}
