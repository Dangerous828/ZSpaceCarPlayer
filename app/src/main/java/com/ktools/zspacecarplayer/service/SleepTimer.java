package com.ktools.zspacecarplayer.service;

/**
 * 睡眠定时的判定核心（2026-10-08 T1 基准补齐）。
 *
 * <p>为什么是纯函数：车机上唯一可信的"过了多久"是 {@code SystemClock.elapsedRealtime()}
 * （墙钟会在开机后被同步改掉，本仓在 ElderWatch 上踩过同样的坑），而它在 JVM 单测里恒为 0。
 * 把时钟取到调用方，这里才能把边界钉死。
 *
 * <p>语义选<b>到点立即暂停</b>，不是"播完当前曲再停"：后者在人已经睡着之后还会把一整首歌唱完，
 * 反而更吵。剩余时间由设置页显示，所以这里只回答"到没到"和"还剩多少"。
 */
public final class SleepTimer {

    /** 关。 */
    public static final long OFF = 0L;
    /** 可选分钟数，数组顺序就是设置页的循环顺序。 */
    public static final int[] PRESET_MINUTES = {15, 30, 45, 60, 90};

    private SleepTimer() {
    }

    /**
     * 到点没有。{@code stopAtMs <= 0} 表示没开定时，永远 false。
     */
    public static boolean isExpired(long nowMs, long stopAtMs) {
        return stopAtMs > 0L && nowMs >= stopAtMs;
    }

    /** 还剩多少毫秒；未开定时或已过点都返回 0（不返回负数，UI 直接拿来显示）。 */
    public static long remainingMs(long nowMs, long stopAtMs) {
        if (stopAtMs <= 0L) {
            return 0L;
        }
        long left = stopAtMs - nowMs;
        return left > 0L ? left : 0L;
    }

    /** 分钟数 → 截止时刻；非正值一律当"关"。 */
    public static long deadlineFor(long nowMs, int minutes) {
        if (minutes <= 0) {
            return OFF;
        }
        return nowMs + minutes * 60_000L;
    }

    /** 循环到下一个预设；返回 0 表示回到"关"。 */
    public static int nextPreset(int currentMinutes) {
        if (currentMinutes <= 0) {
            return PRESET_MINUTES[0];
        }
        for (int i = 0; i < PRESET_MINUTES.length; i++) {
            if (PRESET_MINUTES[i] == currentMinutes) {
                return i + 1 < PRESET_MINUTES.length ? PRESET_MINUTES[i + 1] : 0;
            }
        }
        return 0;
    }

    /** 给用户看的剩余时间。最后一分钟显示秒，避免出现"剩 0 分钟"这种废话。 */
    public static String formatRemaining(long remainingMs) {
        if (remainingMs <= 0L) {
            return "已结束";
        }
        long minutes = remainingMs / 60_000L;
        if (minutes >= 1L) {
            return "剩 " + minutes + " 分钟";
        }
        return "剩 " + Math.max(1L, remainingMs / 1000L) + " 秒";
    }
}
