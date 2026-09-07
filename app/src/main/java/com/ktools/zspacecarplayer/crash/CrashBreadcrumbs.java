package com.ktools.zspacecarplayer.crash;

import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃现场面包屑：定长环形缓冲，记录崩溃前最近发生的业务事件。
 *
 * 车机上的崩溃（尤其 native SIGSEGV 与主线程卡死）拿不到有用的 Java 栈，
 * 只有「崩溃前发生了什么」能把 8:40 这类现场还原出来：切分类 → 点歌 →
 * 起播 → 原生 open 超时 → reset。所以每个关键路径都往这里丢一行。
 *
 * 只用 java.*，不依赖 Android 类，便于 JVM 单测。
 * 线程安全：所有方法在 {@link #lock} 下操作。
 */
public final class CrashBreadcrumbs {

    /** 保留条数。每条约 80 字节，64 条 ≈ 5KB，写进崩溃报告不会撑爆上传体。 */
    static final int MAX_ENTRIES = 64;
    /** 单条消息截断长度，防止某处塞进整个播放列表 */
    static final int MAX_MSG_LEN = 240;

    private static final Object lock = new Object();
    private static final String[] slots = new String[MAX_ENTRIES];
    private static int writeIndex = 0;
    private static int size = 0;

    private CrashBreadcrumbs() {}

    /** 记录一条面包屑。tag 用于标出子系统（player / stream / dsp / ui）。 */
    public static void record(String tag, String message) {
        if (tag == null || message == null) {
            return;
        }
        String msg = message.length() > MAX_MSG_LEN
                ? message.substring(0, MAX_MSG_LEN) + "…"
                : message;
        String entry = System.currentTimeMillis() + " [" + tag + "] " + msg;
        synchronized (lock) {
            slots[writeIndex] = entry;
            writeIndex = (writeIndex + 1) % MAX_ENTRIES;
            if (size < MAX_ENTRIES) {
                size++;
            }
        }
    }

    /** 按时间先后返回全部面包屑（最旧的在前）。 */
    public static List<String> snapshot() {
        synchronized (lock) {
            List<String> out = new ArrayList<String>(size);
            // 写指针指向「下一个要覆盖的位置」，即当前最旧的一条
            int start = (size < MAX_ENTRIES) ? 0 : writeIndex;
            for (int i = 0; i < size; i++) {
                String e = slots[(start + i) % MAX_ENTRIES];
                if (e != null) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    /** 清空（单测 / 新会话起点用） */
    public static void clear() {
        synchronized (lock) {
            for (int i = 0; i < MAX_ENTRIES; i++) {
                slots[i] = null;
            }
            writeIndex = 0;
            size = 0;
        }
    }
}
