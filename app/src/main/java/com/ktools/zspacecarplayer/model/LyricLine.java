package com.ktools.zspacecarplayer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricLine implements Comparable<LyricLine> {
    private long timeMs;
    private String text;

    public LyricLine(long timeMs, String text) {
        this.timeMs = timeMs;
        this.text = text;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public String getText() {
        return text;
    }

    @Override
    public int compareTo(LyricLine o) {
        // 注意: 不能用 Long.compare (API 19+), 目标机 Android 4.3 (API 18) 会 NoSuchMethodError
        if (this.timeMs < o.timeMs) return -1;
        if (this.timeMs > o.timeMs) return 1;
        return 0;
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:[\\.:](\\d{2,3}))?\\]");

    public static List<LyricLine> parseLrc(String lrcContent) {
        List<LyricLine> lines = new ArrayList<>();
        if (lrcContent == null || lrcContent.trim().isEmpty()) {
            return lines;
        }

        String[] rawLines = lrcContent.split("\r?\n");
        for (String rawLine : rawLines) {
            if (rawLine.trim().isEmpty()) continue;
            Matcher matcher = TIME_PATTERN.matcher(rawLine);
            int lastEnd = 0;
            List<Long> timeMsList = new ArrayList<>();

            while (matcher.find()) {
                long min = Long.parseLong(matcher.group(1));
                long sec = Long.parseLong(matcher.group(2));
                long ms = 0;
                String msStr = matcher.group(3);
                if (msStr != null) {
                    if (msStr.length() == 2) {
                        ms = Long.parseLong(msStr) * 10;
                    } else if (msStr.length() == 3) {
                        ms = Long.parseLong(msStr);
                    }
                }
                long totalMs = min * 60 * 1000 + sec * 1000 + ms;
                timeMsList.add(totalMs);
                lastEnd = matcher.end();
            }

            String lyricText = rawLine.substring(lastEnd).trim();
            if (!lyricText.isEmpty()) {
                for (Long tMs : timeMsList) {
                    lines.add(new LyricLine(tMs, lyricText));
                }
            }
        }

        Collections.sort(lines);
        return lines;
    }
}
