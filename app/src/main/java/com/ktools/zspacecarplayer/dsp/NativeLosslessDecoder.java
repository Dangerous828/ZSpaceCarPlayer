package com.ktools.zspacecarplayer.dsp;

import android.util.Log;

import com.ktools.zspacecarplayer.player.stream.BufferedHttpSource;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v3 自研引擎的原生无损解码入口（C++ dr_flac / dr_wav / dr_mp3）。
 *
 * 吉利 8600 (Android 4.3) 的 MediaCodec 只开放了 MP3/AAC 解码器，FLAC/WAV
 * 直传必崩（Failed to allocate component）。本类把解码整体下沉到自有
 * libzspacecarplayer_dsp.so，数据经静态回调桥（bridgeRead/bridgeSeek）按绝对
 * 字节位从 LosslessStreamReader 读取——HttpSourceReader 包 BufferedHttpSource
 * 环形缓冲，阻塞语义天然被流式下载驱动；seek 触发环形缓冲重定位远端 Range
 * 下载，全程零本地文件。
 *
 * 用法：new → open(reader, sniffFormat()) → readSamples()/seekToMs() → close()。
 */
public final class NativeLosslessDecoder {
    private static final String TAG = "NativeLosslessDecoder";

    public static final int FMT_UNKNOWN = 0;
    public static final int FMT_FLAC = 1;
    public static final int FMT_WAV = 2;
    public static final int FMT_MP3 = 3;

    public static boolean isAvailable() {
        // 与 NativeDsp 打包在同一个 libzspacecarplayer_dsp.so 中：直接透传。
        // 严禁在 static {} 中缓存，避免 System.loadLibrary 触发 JNI_OnLoad 时
        // 跨类加载时序竞争（JNI_OnLoad 执行中 NativeDsp.isLoaded 尚未被置为 true）
        return NativeDsp.isAvailable();
    }

    /** 按文件头魔数嗅探容器格式 */
    public static int sniffFormat(byte[] header) {
        if (header == null || header.length < 4) return FMT_UNKNOWN;
        if (header[0] == 'f' && header[1] == 'L' && header[2] == 'a' && header[3] == 'C') {
            return FMT_FLAC;
        }
        if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header.length >= 12
                && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E') {
            return FMT_WAV;
        }
        // MP3: ID3 tag 或帧同步头 0xFF Ex/Fx
        if (header.length >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            return FMT_MP3;
        }
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0) {
            return FMT_MP3;
        }
        return FMT_UNKNOWN;
    }

    /**
     * 该容器是否走原生 dr_* 软解（false = 回退系统 MediaExtractor + MediaCodec）。
     *
     * 只有 FLAC / WAV 走原生：吉利 8600 的 MediaCodec 没注册这两种解码器，直传必崩。
     * MP3 虽然 native 层（dr_mp3）也解得动，但刻意不启用——系统有 audio/mpeg 硬解，
     * 交给 MediaCodec 更省 i.MX6Quad 的 CPU，软解只在系统缺解码器时才是必要的。
     */
    public static boolean isNativeSupportedFormat(int format) {
        return format == FMT_FLAC || format == FMT_WAV;
    }

    // ---- 实例状态 ----
    /** native 句柄。volatile：close() 可能与 open()/readSamples() 不在同一线程。 */
    private volatile long ptr;
    private LosslessStreamReader reader;
    private int sampleRate;
    private int channels;
    private long totalFrames; // 0 = 未知（如流式/VBR MP3）

    public NativeLosslessDecoder() {}

    /**
     * 打开解码器。嗅探 FMT_UNKNOWN（如 Ogg 容器 FLAC、AIFF）或容器头解析失败
     * 返回 false，由调用方回退 MediaCodec 链路。成功后 reader 归本类持有，
     * close() 时释放其数据源引用。
     */
    public boolean open(LosslessStreamReader reader, int sniffedFormat) {
        if (!isAvailable() || ptr != 0 || reader == null) return false;
        long p = nativeOpen(reader, sniffedFormat);
        if (p == 0) {
            Log.w(TAG, "nativeOpen failed, fmt=" + sniffedFormat);
            return false;
        }
        this.ptr = p;
        this.reader = reader;
        this.sampleRate = nativeGetSampleRate(p);
        this.channels = nativeGetChannels(p);
        this.totalFrames = nativeGetTotalFrames(p);
        Log.i(TAG, "native decoder open: fmt=" + sniffedFormat + " sr=" + sampleRate
                + " ch=" + channels + " frames=" + totalFrames);
        return true;
    }

    public int getSampleRate() { return sampleRate; }

    public int getChannels() { return channels; }

    /** @return 总时长毫秒；容器未提供总帧数时返回 0 */
    public int getDurationMs() {
        if (totalFrames > 0 && sampleRate > 0) {
            return (int) Math.min(Integer.MAX_VALUE, totalFrames * 1000L / sampleRate);
        }
        return 0;
    }

    /**
     * 解出 16-bit 交错 PCM。
     * @return 实际帧数；0 = EOF；-1 = 句柄已关闭或出错
     */
    public int readSamples(short[] dst, int dstOffset, int numFrames) {
        if (ptr == 0) return -1;
        return nativeReadSamples(ptr, dst, dstOffset, numFrames);
    }

    /**
     * 底层流是否已因错误中断（非干净 EOF）。
     * readSamples 返回 0 时用它区分「播完了」和「断流了」：前者该自动切下一首，
     * 后者必须如实报错让服务侧带断点重试同一首。
     */
    public boolean hasStreamFailed() {
        LosslessStreamReader r = reader;
        return r != null && r.hasFailed();
    }

    /** 毫秒级定位（按 sampleRate 换算 PCM 帧） */
    public boolean seekToMs(int msec) {
        if (ptr == 0 || sampleRate <= 0 || msec < 0) return false;
        return nativeSeekToFrame(ptr, msec * (long) sampleRate / 1000L);
    }

    public boolean seekToFrame(long frameIndex) {
        return ptr != 0 && nativeSeekToFrame(ptr, frameIndex);
    }

    public void close() {
        if (ptr != 0) {
            long p = ptr;
            ptr = 0;
            try {
                nativeClose(p);
            } catch (Throwable t) {
                Log.w(TAG, "nativeClose error", t);
            }
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {}
            reader = null;
        }
    }

    // ------------------------------------------------------------------ //
    //  C 回调桥（native 经 JNI 静态调用；运行在解码线程，禁止切线程）
    // ------------------------------------------------------------------ //

    /** 供 native 回调：从 fromPos 读最多 length 字节进 buf；EOF 返回 -1，错误返回 0 */
    static int bridgeRead(LosslessStreamReader reader, long fromPos, byte[] buf,
                          int offset, int length) {
        return reader == null ? 0 : reader.bridgeReadAt(fromPos, buf, offset, length);
    }

    /** 供 native 回调：绝对字节位重定位；不可达返回 false */
    static boolean bridgeSeek(LosslessStreamReader reader, long absolutePos) {
        return reader != null && reader.bridgeSeekTo(absolutePos);
    }

    /**
     * 供 native 回调（nativeClose 内）：取消 reader 正在进行的阻塞等待。
     * 解码线程可能正卡在环形缓冲等网络供数，不唤醒它 close 就拿不到 native 侧的
     * apiMutex，只能被迫泄漏解码器。
     */
    static void bridgeAbort(LosslessStreamReader reader) {
        if (reader != null) {
            reader.abort();
        }
    }

    // native methods（符号与 NativeLossless.cpp 对应）
    private native long nativeOpen(LosslessStreamReader reader, int sniffedFormat);
    private native int nativeGetSampleRate(long handle);
    private native int nativeGetChannels(long handle);
    private native long nativeGetTotalFrames(long handle);
    private native int nativeReadSamples(long handle, short[] dst, int dstOffset, int numFrames);
    private native boolean nativeSeekToFrame(long handle, long frameIndex);
    private native void nativeClose(long handle);

    // ------------------------------------------------------------------ //
    //  随机读取流适配
    // ------------------------------------------------------------------ //

    /**
     * native 解码器使用的随机读取流。契约：
     * - read 从当前游标顺序读取（阻塞直至有数据或 EOF）；EOF 返回 -1
     * - seek 绝对字节位重定位；后续 read 从新位置继续
     * - close 释放底层资源，幂等
     */
    public abstract static class LosslessStreamReader {
        /** 顺序读：返回实际字节数，EOF 返回 -1 */
        public abstract int read(byte[] dest, int offset, int length) throws IOException;

        /** 绝对定位；目标不可达返回 false */
        public abstract boolean seek(long absolutePos);

        /** 释放底层资源（幂等） */
        public abstract void close();

        /**
         * 取消正在进行的阻塞等待（幂等）。默认无操作：只有基于环形缓冲的网络
         * reader 会真的阻塞，本地文件 reader 不需要。
         */
        public void abort() {}

        /**
         * 流是否已因错误中断（而非干净 EOF）。默认 false。
         *
         * native 侧 bridgeReadAt 把 IOException 折叠成 0，dr_* 随即当成流结束，
         * readSamples 的返回值与真 EOF 完全同形；渲染循环靠这个标志决定上报
         * onCompletion（自动切下一首）还是 onError（服务侧带断点重试同一首）。
         */
        public boolean hasFailed() { return false; }

        // native 桥实现：把异常折叠成 dr_* 认识的返回值（0 = 流中断）
        int bridgeReadAt(long fromPos, byte[] dest, int offset, int length) {
            try {
                if (seek(fromPos)) {
                    return read(dest, offset, length);
                }
                return 0;
            } catch (Exception e) {
                return 0;
            }
        }

        boolean bridgeSeekTo(long absolutePos) {
            if (absolutePos < 0) return false;
            try {
                return seek(absolutePos);
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * BufferedHttpSource（本地回环流式环形缓冲）适配。读超出已下载窗口且流未
     * 结束时阻塞等待下载线程续供——解码被流式下载天然驱动；seek 触发环形缓冲
     * 重定位远端 Range 下载。构造前调用方须已 source.addRef()。
     */
    public static final class HttpSourceReader extends LosslessStreamReader {
        private static final long MAX_LIMIT = Long.MAX_VALUE >>> 1;
        private final BufferedHttpSource source;
        private final Object token = new Object();
        /** 置位后 readAt 立即抛出、后续 read/seek 一律失败：native close 靠它解开阻塞 */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** 环形缓冲侧真断流（fatal / 涓流饥饿）置位；主动 close/abort 不算 */
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private long cursor;
        private boolean closed;

        public HttpSourceReader(BufferedHttpSource source, long startPos) {
            this.source = source;
            this.cursor = Math.max(0, startPos);
            // 注册为读方：窗口回收绝不越过本读者，且保证下载线程持续供数
            source.addReadPos(token, cursor);
        }

        @Override
        public int read(byte[] dest, int offset, int length) throws IOException {
            if (closed || cancelled.get()) throw new IOException("reader closed");
            // dr_* onRead 契约等同 fread：短读即 EOF。窗口数据不足时必须阻塞等
            // 下载线程续供、读满为止，否则一次网络抖动就会被解码器判成流结束
            int total = 0;
            while (total < length) {
                int n;
                try {
                    n = source.readAt(cursor + total, dest, offset + total,
                            length - total, cancelled);
                } catch (IOException e) {
                    // 主动拆除（close/abort）不算流失败；环形缓冲 fatal（无进展断流、
                    // 涓流饥饿）才算——上层要靠它决定报 onError 而不是 onCompletion
                    if (!closed && !cancelled.get()) {
                        failed.set(true);
                    }
                    throw e;
                }
                if (n < 0) break; // 流结束
                total += n;
                // 每推进一块就登记：窗口回收以「最慢读者的未读位置」为下界，
                // 登记位落后会让 bufStart 滑不动、下载线程写满 capacity 后死等，
                // 与正在阻塞读的解码线程互相锁死。攒到整次 read 结束才登记也不行——
                // dr_* 一次就要几十 KB，单次大读足以跨过整个窗口
                source.updateReadPos(token, cursor + total);
            }
            if (total == 0) return -1;
            cursor += total;
            return total;
        }

        @Override
        public boolean hasFailed() {
            return failed.get();
        }

        /**
         * 目标位置可达吗 (2026-10-08，A1 第一刀)。
         *
         * <p>总长已知时，越过文件末尾的 seek <b>必须</b>返回 false。这不是防御性检查，而是把
         * dr_flac 自己的判据还给它：`drflac__seek_to_byte` 失败在 dr_flac 里就是"已越过流尾，
         * 该把折半上界收紧"的唯一信号（`dr_flac.h:5888` 原注释）。此前这里只校验
         * {@code <0} 与 {@code MAX_LIMIT}，越界一律返回 true，于是它的无 seektable 二分只能靠
         * "整帧解码失败"来驱动，迭代次数偏高（实车 9 次）；而<b>每一次跳读落在窗外就是一条新
         * 上游连接 + 约 1 秒首字节</b>（2026-10-08 对照实测：分段请求 133KB/s vs 长连接 797KB/s，
         * 差的就是这个）。
         *
         * <p>总长未知（chunked 转码流不给 Content-Length）时保持放行，行为与今天完全一致。
         */
        static boolean seekTargetReachable(long absolutePos, long contentLength,
                                           boolean eofGuardEnabled) {
            if (absolutePos < 0) {
                return false;
            }
            if (!eofGuardEnabled) {
                // 回退闸关掉时退回 vc20 行为：不校验越界（dr_flac 会失去"到流尾"的判据）
                return true;
            }
            if (contentLength <= 0L) {
                return true;
            }
            return absolutePos < contentLength;
        }

        @Override
        public boolean seek(long absolutePos) {
            if (closed || cancelled.get()) return false;
            if (absolutePos < 0 || absolutePos > MAX_LIMIT) return false;
            if (!seekTargetReachable(absolutePos, source.getContentLength(),
                    com.ktools.zspacecarplayer.player.stream.StreamTuning.seekEofGuardEnabled())) {
                // 越界的 seek 交给 dr_flac 当"到流尾了"的信号，让它自己收紧折半上界，
                // 而不是我们替它去清窗、断连、重下一条永远取不到字节的连接
                return false;
            }
            if (absolutePos == cursor) return true;
            cursor = absolutePos;
            // 强制同步读者登记位（updateReadPos 只进不退，回溯 seek 必须能落回小位）
            source.seekReadPos(token, absolutePos);
            return true; // 窗口未覆盖时由后续 readAt 触发重定位下载
        }

        @Override
        public void abort() {
            cancelled.set(true);
            source.cancelWaiters();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            cancelled.set(true);
            try {
                source.removeReadPos(token);
                source.release();
            } catch (Exception ignored) {}
        }
    }

    /** 本地文件适配（单测 / 本地曲库） */
    public static final class FileSourceReader extends LosslessStreamReader {
        private RandomAccessFile raf;

        public FileSourceReader(File file) throws IOException {
            this.raf = new RandomAccessFile(file, "r");
        }

        @Override
        public int read(byte[] dest, int offset, int length) throws IOException {
            RandomAccessFile f = raf;
            if (f == null) throw new IOException("reader closed");
            return f.read(dest, offset, length); // RAF 自带游标即读即推进
        }

        @Override
        public boolean seek(long absolutePos) {
            RandomAccessFile f = raf;
            if (f == null || absolutePos < 0) return false;
            try {
                if (absolutePos > f.length()) return false;
                f.seek(absolutePos);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void close() {
            RandomAccessFile f = raf;
            raf = null;
            if (f != null) {
                try {
                    f.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
