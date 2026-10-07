package com.ktools.zspacecarplayer.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.ktools.zspacecarplayer.crash.CrashMonitor;
import com.ktools.zspacecarplayer.dsp.NativeDsp;
import com.ktools.zspacecarplayer.dsp.NativeLosslessDecoder;
import com.ktools.zspacecarplayer.player.stream.BufferedHttpSource;
import com.ktools.zspacecarplayer.player.stream.BufferingPolicy;
import com.ktools.zspacecarplayer.player.stream.HttpProxyServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 针对 Android 4.3 (API 18) 与吉利 8600 车机优化的自研软解管线播放器:
 * 1. 优先通过流头嗅探检测 FLAC / WAV 容器头；命中后由 C++ NativeLosslessDecoder
 *    (基于 dr_flac / dr_wav) 绕开系统残缺的 MediaCodec 直接硬核软解出原始 PCM (16-bit)
 * 2. 对非无损流 (如标准 MP3/AAC) 自动降级至 MediaExtractor + MediaCodec 解码出 PCM
 * 3. 解码后的 PCM 帧送入私有 C++ NativeDsp 核心进行 EQ、BassBoost、全景声场 (Widener) 与 Reverb 运算
 * 4. 通过纯裸 AudioTrack 写入车机 AudioFlinger，彻底绕过系统缺陷 Virtualizer/Reverb
 */
public class DspAudioTrackPlayer implements IAudioPlayer {
    private static final String TAG = "DspAudioTrackPlayer";

    private static final int MSG_PREPARE = 1;
    private static final int MSG_SEEK = 2;
    private static final int MSG_RELEASE = 3;
    private static final int MSG_TEARDOWN = 4;

    // 原生解码器 open 的阻塞判据全部收口在 {@link BufferingPolicy#openShouldAbort}：
    // 「一个字节都没下来」按 OPEN_FIRST_BYTE_MS 快速失败，「有字节但不再增长」才按 OPEN_STALL_MS，
    // 另有 OPEN_HARD_CAP_MS 封顶——不再用单一墙上时间把慢但有进展的开流掐掉。

    private String dataSourcePath;
    private OnEventListener eventListener;
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Object stateLock = new Object();
    private volatile boolean isPlaying = false;
    private volatile boolean isPrepared = false;
    private volatile boolean isReleased = false;
    /**
     * prefill 门槛等待期间的外部中止标志（2026-09-12 缓冲/预取）。
     * reset()/release() 置位，doPrepare() 起始清零；门槛轮询每 {@link BufferingPolicy#PREFILL_POLL_MS}
     * 检查一次，命中即让位切歌 / 释放，把解码后台线程的占用收敛到一个轮询周期内。
     */
    private volatile boolean abortPrepare = false;

    private volatile int currentDurationMs = 0;
    /**
     * 本轮起播是否被服务端判定"曲目已不存在"(HTTP 404/410)。
     * 与网络故障的区别在于它不可重试：只有换 Id 才有救，所以 {@link #doPrepare()} 见到它就
     * 直接抛错，不再走系统 MediaCodec 那第二轮（同一个 Id 照样 404，失败后还会留下
     * "Failed to instantiate extractor" 这种误导归因的现场）。
     */
    private volatile boolean resourceGone = false;
    private volatile int goneHttpStatus = -1;
    /**
     * 入库时已知的曲目时长 (Jellyfin 列表接口的 RunTimeTicks)。流式 FLAC 容器里 dr_flac
     * 拿不到总帧数 (frames=0)，currentDurationMs 就是 0，于是剩余时长算不出、
     * 「接近结尾算稳定」与「接近结尾无条件预取」两条判据全线失效、满屏 STUCK heartbeat。
     * native 报 0 时用它兜底。
     */
    private volatile int knownDurationMs = 0;
    private volatile long currentPresentationTimeUs = 0;
    private volatile int pendingSeekMs = -1;

    private AudioTrack audioTrack;

    // ---- 原生无损软解分支 (FLAC / WAV) ----
    private NativeLosslessDecoder nativeDecoder;
    private volatile boolean isNativeMode = false;
    private volatile long currentPresentationFrame = 0;

    // ---- 传统系统解码器分支 (MP3 / AAC 等) ----
    private MediaExtractor extractor;
    private MediaCodec codec;

    private int sampleRate = 44100;
    private int channelCount = 2;
    /**
     * 真正进 DSP 与 AudioTrack 的声道数：解码器报 &gt;2 时恒为 2（先经 {@link PcmDownmix} 下混）。
     * 与 channelCount 分开是因为 PCM 读取缓冲必须按母带声道数分配，不能跟着缩。
     */
    private int renderChannels = 2;
    private float volume = 1.0f;

    private volatile Thread renderThread;
    private final AtomicBoolean isRendering = new AtomicBoolean(false);
    private volatile boolean sawInputEOS = false;
    private volatile boolean sawOutputEOS = false;

    public DspAudioTrackPlayer() {
        startDecodeThread();
    }

    private void startDecodeThread() {
        decodeThread = new HandlerThread("DspPlayer-DecodeThread", Thread.MAX_PRIORITY);
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_PREPARE:
                        doPrepare();
                        break;
                    case MSG_SEEK:
                        doSeek(msg.arg1);
                        break;
                    case MSG_RELEASE:
                        doRelease();
                        break;
                    case MSG_TEARDOWN:
                        doTeardown();
                        break;
                }
            }
        };
    }

    /**
     * 提供入库时已知的时长，供容器不自报时长时兜底 (见 {@link #knownDurationMs})。
     * 必须在 prepare 之前调用；传 0 表示没有可用信息，行为与旧版一致。
     */
    @Override
    public void setKnownDurationMs(long durationMs) {
        this.knownDurationMs = durationMs > 0L
                ? (int) Math.min(durationMs, (long) Integer.MAX_VALUE) : 0;
    }

    @Override
    public boolean isResourceGone() {
        return resourceGone;
    }

    @Override
    public void setDataSource(String pathOrUrl) throws Exception {
        synchronized (stateLock) {
            this.dataSourcePath = pathOrUrl;
            this.isPrepared = false;
            this.isPlaying = false;
            this.currentPresentationTimeUs = 0;
            this.currentPresentationFrame = 0;
            this.pendingSeekMs = -1;
        }
    }

    @Override
    public void prepareAsync() {
        if (decodeHandler != null) {
            decodeHandler.obtainMessage(MSG_PREPARE).sendToTarget();
        }
    }

    private void doPrepare() {
        synchronized (stateLock) {
            if (isReleased) return;
        }
        abortPrepare = false; // 新一轮 prepare：清除上一首遗留的门槛中止标志
        resourceGone = false;
        goneHttpStatus = -1;

        // 这条同时是解码线程的存活证明：报告里有 prepareAsync 却没有 doPrepare begin，
        // 说明解码线程被上一次 open 占死，消息根本没排上队
        CrashMonitor.breadcrumb("v3", "doPrepare begin path=" + dataSourcePath);
        try {
            stopRenderingThread();
            releaseDecoderComponents();

            // 1. 尝试嗅探流格式，判断是否能直接走原生 C++ 软解管道 (FLAC / WAV)
            boolean nativePrepared = tryPrepareNativeLossless();
            if (nativePrepared) {
                // 原生软解管道准备就绪
                onPrepareSuccess();
                return;
            }

            // 2. 服务端已判定"这首没了"：系统管线再连同一个 Id 也是同样结果，且它抛回来的
            //    "Failed to instantiate extractor" 会把归因带偏成解码器问题
            if (resourceGone) {
                throw new IOException("resource gone: HTTP " + goneHttpStatus);
            }

            // 3. 原生软解未命中或失败，回退到系统 MediaExtractor + MediaCodec
            prepareMediaCodec();
            onPrepareSuccess();

        } catch (final Exception e) {
            Log.e(TAG, "doPrepare failed", e);
            CrashMonitor.breadcrumb("v3", "doPrepare failed: " + e);
            synchronized (stateLock) {
                isPrepared = false;
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (eventListener != null) {
                        eventListener.onError(-1, e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * 探测数据源头部：如果是 FLAC / WAV，直接走 C++ 原生无损软解。
     */
    private boolean tryPrepareNativeLossless() {
        Log.i(TAG, "tryPrepareNativeLossless: available=" + NativeLosslessDecoder.isAvailable()
                + " path=" + dataSourcePath);
        if (!NativeLosslessDecoder.isAvailable()) {
            return false;
        }

        NativeLosslessDecoder.LosslessStreamReader streamReader = null;
        NativeLosslessDecoder dec = null;
        Thread openWatchdog = null;
        String openProbeUrl = null;
        try {
            if (dataSourcePath.startsWith("http://") || dataSourcePath.startsWith("https://")) {
                String realUrl = HttpProxyServer.extractRemoteUrl(dataSourcePath);
                openProbeUrl = realUrl;
                Log.i(TAG, "tryPrepareNativeLossless: realUrl=" + realUrl);
                BufferedHttpSource source = HttpProxyServer.getInstance().acquireSource(realUrl, 0);
                streamReader = new NativeLosslessDecoder.HttpSourceReader(source, 0);
            } else {
                File file = new File(dataSourcePath);
                if (file.exists() && file.isFile()) {
                    streamReader = new NativeLosslessDecoder.FileSourceReader(file);
                }
            }

            if (streamReader == null) {
                Log.w(TAG, "tryPrepareNativeLossless: streamReader is null");
                return false;
            }

            // 头部嗅探读与 drflac_open 都会阻塞在环形缓冲等下载。不设上限的话一次弱网
            // 起播就能把唯一的解码线程永久占死，之后所有 prepare/seek/release 消息都排不
            // 上队（车机表现：点了不播，且这个进程再也不会播了）。
            final NativeLosslessDecoder.LosslessStreamReader guarded = streamReader;
            final String probeUrl = openProbeUrl;
            openWatchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    final long startedAt = System.currentTimeMillis();
                    long lastBytes = -1L;
                    long lastProgressAt = startedAt;
                    // open 期间没有消费者，环形缓冲只进不出，所以 lastBytes>0 就是"服务端真的吐过字节"
                    boolean gotFirstByte = false;
                    while (true) {
                        try {
                            Thread.sleep(BufferingPolicy.OPEN_POLL_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                        long now = System.currentTimeMillis();
                        long bytes = lastBytes;
                        if (probeUrl != null) {
                            try {
                                bytes = HttpProxyServer.getInstance().getBufferedBytes(probeUrl);
                            } catch (Throwable t) {
                                bytes = lastBytes; // 读不到进度就当无进展，交给 stall 判定
                            }
                        }
                        boolean advanced;
                        if (lastBytes < 0) {
                            // 第一轮只立基线：0 字节也是"起点"而不是"进展"
                            advanced = false;
                            lastBytes = bytes;
                        } else if (bytes > lastBytes) {
                            advanced = true;
                            lastBytes = bytes;
                            lastProgressAt = now;
                        } else {
                            advanced = false;
                        }
                        if (bytes > 0L) {
                            gotFirstByte = true;
                        }
                        if (!BufferingPolicy.openShouldAbort(advanced,
                                now - lastProgressAt, now - startedAt, gotFirstByte)) {
                            continue;
                        }
                        String why = gotFirstByte
                                ? "stalled no-progress=" + (now - lastProgressAt) + "ms"
                                : "no-first-byte=" + (now - startedAt) + "ms";
                        Log.e(TAG, "native open aborted: " + why
                                + " total " + (now - startedAt) + "ms, buffered=" + lastBytes
                                + "B (deadline=" + (gotFirstByte
                                        ? BufferingPolicy.OPEN_STALL_MS : BufferingPolicy.OPEN_FIRST_BYTE_MS)
                                + "ms), freeing decode thread");
                        CrashMonitor.putContext("nativeOpenTimeout", true);
                        CrashMonitor.breadcrumb("v3", "native open " + why
                                + " total=" + (now - startedAt)
                                + "ms buffered=" + lastBytes + "B, aborting reader");
                        try {
                            guarded.abort();
                        } catch (Throwable ignored) {}
                        return;
                    }
                }
            }, "DspPlayer-OpenWatchdog");
            openWatchdog.setDaemon(true);
            openWatchdog.start();

            byte[] header = new byte[16];
            int n = streamReader.read(header, 0, header.length);
            int format = (n >= 4) ? NativeLosslessDecoder.sniffFormat(header) : NativeLosslessDecoder.FMT_UNKNOWN;
            Log.i(TAG, "tryPrepareNativeLossless: sniff n=" + n + " fmt=" + format
                    + " bytes=[" + (n>0?header[0]:0) + "," + (n>1?header[1]:0) + "," + (n>2?header[2]:0) + "," + (n>3?header[3]:0) + "]");
            CrashMonitor.putContext("streamFormat", format);
            CrashMonitor.putContext("sniffBytes", n);
            streamReader.seek(0);

            // 回退边界由 NativeLosslessDecoder 统一裁定（吉利 8600 MediaCodec 缺 FLAC/WAV）
            if (!NativeLosslessDecoder.isNativeSupportedFormat(format)) {
                Log.i(TAG, "tryPrepareNativeLossless: format not FLAC/WAV (" + format + "), close & fallback");
                CrashMonitor.breadcrumb("v3", "sniff fmt=" + format + " not lossless, fallback");
                streamReader.close();
                return false;
            }

            dec = new NativeLosslessDecoder();
            // 先登记再 open：open 阻塞期间外部 reset()/release() 才能顺着这个引用取消读者，
            // 否则解码器对象和它持有的两个 JNI GlobalRef 无人可释放
            this.nativeDecoder = dec;
            if (!dec.open(streamReader, format)) {
                Log.w(TAG, "NativeLosslessDecoder open failed for format: " + format);
                CrashMonitor.breadcrumb("v3", "native open failed fmt=" + format + ", fallback");
                this.nativeDecoder = null;
                dec.close(); // ptr 为 0 时只关 reader，幂等
                return false;
            }

            this.isNativeMode = true;
            this.sampleRate = dec.getSampleRate() > 0 ? dec.getSampleRate() : 44100;
            this.channelCount = dec.getChannels() > 0 ? dec.getChannels() : 2;
            this.currentDurationMs = dec.getDurationMs();
            if (this.currentDurationMs <= 0) {
                this.currentDurationMs = knownDurationMs;
                if (knownDurationMs > 0) {
                    Log.i(TAG, "duration unknown in container, use library duration "
                            + knownDurationMs + "ms");
                }
            }
            this.currentPresentationFrame = 0;
            this.currentPresentationTimeUs = 0;

            Log.i(TAG, "Native lossless pipeline established: fmt=" + format + ", sr="
                    + sampleRate + ", ch=" + channelCount + ", durMs=" + currentDurationMs);
            CrashMonitor.putContext("sampleRate", sampleRate);
            CrashMonitor.putContext("channelCount", channelCount);
            CrashMonitor.breadcrumb("v3", "native pipeline up fmt=" + format
                    + " sr=" + sampleRate + " ch=" + channelCount
                    + " dur=" + currentDurationMs + "ms");

            initAudioTrackAndDsp();
            return true;

        } catch (Throwable t) {
            Log.w(TAG, "tryPrepareNativeLossless exception, fallback to MediaCodec", t);
            // 捕获的是 Throwable：UnsatisfiedLinkError 之类的 so 加载失败也走这里，
            // 静默回退后现场就没了，必须留痕
            if (t instanceof BufferedHttpSource.ResourceGoneException) {
                resourceGone = true;
                goneHttpStatus = ((BufferedHttpSource.ResourceGoneException) t).httpCode;
            }
            CrashMonitor.breadcrumb("v3", "native prepare threw, fallback: " + t);
            if (streamReader != null) {
                try { streamReader.close(); } catch (Throwable ignored) {}
            }
            if (nativeDecoder != null) {
                try { nativeDecoder.close(); } catch (Throwable ignored) {}
                nativeDecoder = null;
            }
            isNativeMode = false;
            return false;
        } finally {
            if (openWatchdog != null) {
                openWatchdog.interrupt();
            }
        }
    }

    /**
     * 针对标准 MP3/AAC 的系统 MediaExtractor + MediaCodec 管线
     */
    private void prepareMediaCodec() throws Exception {
        extractor = new MediaExtractor();
        if (dataSourcePath.startsWith("http://") || dataSourcePath.startsWith("https://")) {
            if (dataSourcePath.contains("127.0.0.1") && dataSourcePath.contains("/stream?u=")) {
                extractor.setDataSource(dataSourcePath);
            } else {
                extractor.setDataSource(HttpProxyServer.getInstance().getProxyUrl(dataSourcePath));
            }
        } else {
            File file = new File(dataSourcePath);
            FileInputStream fis = new FileInputStream(file);
            extractor.setDataSource(fis.getFD());
            fis.close();
        }

        int audioTrackIndex = -1;
        MediaFormat format = null;
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                format = f;
                break;
            }
        }

        if (audioTrackIndex < 0 || format == null) {
            throw new IllegalStateException("No audio track found in: " + dataSourcePath);
        }

        extractor.selectTrack(audioTrackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);
        sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            currentDurationMs = (int) (format.getLong(MediaFormat.KEY_DURATION) / 1000);
        } else {
            currentDurationMs = 0;
        }

        Log.i(TAG, "MediaCodec Audio format: " + mime + ", sr=" + sampleRate + ", ch=" + channelCount + ", dur=" + currentDurationMs);

        initAudioTrackAndDsp();

        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();
    }

    private void initAudioTrackAndDsp() {
        // 多声道母带（曲库里混有 5.1 WAV）在下混前不得进链路：C++ DSP 只实现了 1/2 声道分支，
        // ch>2 时一个样本都不处理；AudioTrack 又只能建 mono/stereo。两者叠加会把
        // FL/C/FR/LFE/BL/BR 两两错配进 L/R——相位抵消 + LFE 进全频单元 + 同样内容被拉成
        // 3 倍时长（6 声道样本按 2 声道消费），听感刺耳。
        this.renderChannels = PcmDownmix.effectiveChannels(channelCount);
        if (renderChannels != channelCount) {
            Log.i(TAG, "downmix " + channelCount + "ch -> " + renderChannels + "ch");
            CrashMonitor.putContext("downmixTo", renderChannels);
            CrashMonitor.breadcrumb("v3", "downmix " + channelCount + "ch->"
                    + renderChannels + "ch sr=" + sampleRate);
        }

        // 初始化 Native DSP 核心
        NativeDsp.init(sampleRate, renderChannels);

        // 配置 AudioTrack (Android 4.3 兼容)
        int channelConfig = (renderChannels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        // 给 4 倍 buffer 防止吉利 8600 车机 CPU 抖动产生 underrun
        int bufferSize = Math.max(minBufSize * 4, 32768);

        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
        );
        audioTrack.setStereoVolume(volume, volume);
    }

    private void onPrepareSuccess() {
        // 起播预缓冲门槛（2026-09-12 缓冲/预取）：出声前先让环形缓冲建立足够领先量，
        // 消除「首字节一到就 play、开头几秒最易被抽干」的卡顿。运行在解码后台线程，
        // 绝不阻塞主线程 / UI；有上限的等待，弱网超时也起播。
        awaitPrefillGate();
        if (abortPrepare || isReleased) {
            // 门槛等待期间发生切歌 / 释放：放弃本次起播，交由后续 MSG_TEARDOWN / 新 prepare 收尾
            Log.i(TAG, "prepare aborted during prefill gate, skip start");
            return;
        }
        synchronized (stateLock) {
            isPrepared = true;
        }
        startRenderingLoop();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (eventListener != null) {
                    eventListener.onPrepared(currentDurationMs);
                }
            }
        });
    }

    /**
     * prefill 门槛：轮询当前曲环形缓冲，直到达到起播领先量或超时。
     *
     * 字节 / 秒双判据由 {@link BufferingPolicy} 裁定；门槛期间以 ≥{@link BufferingPolicy#PREFILL_REPORT_INTERVAL_MS}
     * 的节流向上层上报「缓冲中 + 百分比」，达标 / 超时各留一行日志便于实车抓取。
     * 本地文件（非 http）没有缓冲窗口，直接放行。
     */
    private void awaitPrefillGate() {
        final String path = dataSourcePath;
        if (path == null) return;
        if (!path.startsWith("http://") && !path.startsWith("https://")) return; // 本地曲库无需门槛
        final String realUrl = HttpProxyServer.extractRemoteUrl(path);
        if (realUrl == null || realUrl.length() == 0) return;
        final HttpProxyServer proxy = HttpProxyServer.getInstance();

        final long startMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "prefill gate begin: url=" + realUrl);
        long lastReportMs = 0L;
        int lastReportedPercent = Integer.MIN_VALUE;
        while (!abortPrepare && !isReleased) {
            long bufferedBytes = proxy.getBufferedBytes(realUrl);
            int percent = proxy.getBufferedPercent(realUrl);
            boolean totalKnown = percent >= 0;
            long contentLength = proxy.getContentLength(realUrl);
            int capacity = proxy.getWindowCapacity(realUrl);
            if (capacity <= 0) capacity = BufferedHttpSource.DEFAULT_CAPACITY_BYTES;
            long target = BufferingPolicy.prefillTargetBytes(capacity, contentLength, currentDurationMs);

            if (BufferingPolicy.shouldPrefillStart(bufferedBytes, percent, totalKnown, target)) {
                Log.i(TAG, "prefill gate reached: buffered=" + bufferedBytes + "B target=" + target
                        + "B percent=" + percent + " waited="
                        + (SystemClock.elapsedRealtime() - startMs) + "ms");
                reportBuffering(percent, false); // 门槛达标：缓冲指示转「就绪」
                return;
            }
            long waited = SystemClock.elapsedRealtime() - startMs;
            if (waited >= BufferingPolicy.PREFILL_MAX_WAIT_MS) {
                Log.w(TAG, "prefill gate timeout after " + waited + "ms, start anyway: buffered="
                        + bufferedBytes + "B target=" + target + "B percent=" + percent
                        + " (weak link, do not block forever)");
                reportBuffering(percent, true);
                return;
            }
            // 节流上报，避免刷屏抖动
            long now = SystemClock.elapsedRealtime();
            if (percent != lastReportedPercent
                    || now - lastReportMs >= BufferingPolicy.PREFILL_REPORT_INTERVAL_MS) {
                reportBuffering(percent, true);
                lastReportMs = now;
                lastReportedPercent = percent;
            }
            // 可中断短睡：本方法在解码后台线程，绝不 Thread.sleep 卡 UI；被打断即让位
            try {
                Thread.sleep(BufferingPolicy.PREFILL_POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        Log.i(TAG, "prefill gate aborted (song switch / release) after "
                + (SystemClock.elapsedRealtime() - startMs) + "ms");
    }

    /** 缓冲进度上报，统一切回主线程回调（与其它事件一致，服务侧再转发给 UI）。 */
    private void reportBuffering(final int percent, final boolean buffering) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (eventListener != null) {
                    eventListener.onBufferingUpdate(percent, buffering);
                }
            }
        });
    }

    private void startRenderingLoop() {
        stopRenderingThread();
        sawInputEOS = false;
        sawOutputEOS = false;
        isRendering.set(true);
        renderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (isNativeMode) {
                    nativeRenderLoop();
                } else {
                    codecRenderLoop();
                }
            }
        }, "DspPlayer-RenderLoop");
        renderThread.start();
    }

    private void stopRenderingThread() {
        isRendering.set(false);
        AudioTrack at = audioTrack;
        if (at != null) {
            try { at.pause(); } catch (Exception ignored) {}
        }
        if (renderThread != null) {
            renderThread.interrupt();
            try {
                renderThread.join(3000);
            } catch (InterruptedException ignored) {}
            renderThread = null;
        }
    }

    /**
     * 原生 C++ 软解流式渲染主循环 (FLAC / WAV 直解 PCM)
     */
    private void nativeRenderLoop() {
        NativeLosslessDecoder dec = nativeDecoder;
        if (dec == null) return;

        final int framesPerRead = 2048;
        final int srcChannels = channelCount;
        final int outChannels = renderChannels;
        // 解码缓冲按母带声道数分配（dr_* 会写满 frames*channels 个 short），
        // 下混目标另开一块同等帧数的立体声 scratch，循环外分配一次复用
        short[] pcmBuf = new short[framesPerRead * srcChannels];
        final boolean needsDownmix = srcChannels > outChannels;
        final short[] mixBuf = needsDownmix ? new short[framesPerRead * outChannels] : null;

        try {
            while (isRendering.get() && !sawOutputEOS) {
                int seekTarget = pendingSeekMs;
                if (seekTarget >= 0) {
                    pendingSeekMs = -1;
                    if (!doNativeSeekInternal(seekTarget)) {
                        // seek 失败已如实上报 onError：解码器流位置不可信，
                        // 退出循环，服务层会带断点重试同一首
                        break;
                    }
                }

                if (!isPlaying) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                int framesGot = dec.readSamples(pcmBuf, 0, framesPerRead);
                if (framesGot <= 0) {
                    // 拆机会先置 isRendering=false 再 interrupt，阻塞在 JNI 回调里的
                    // readSamples 随即返回 0，与真 EOF 从返回值上无法区分。此时上报
                    // onCompletion 会让服务自动切下一首（表现为切歌/停止时曲目乱跳）。
                    if (!isRendering.get()) {
                        break;
                    }
                    // 断流（环形缓冲无进展 fatal / 涓流饥饿）经 bridgeReadAt 也折叠成 0。
                    // 误报 onCompletion = 歌自己乱跳；如实报 onError 才会走服务侧带断点的
                    // 同曲重试（handlePlayerError 对 v3 引擎的负值码一律按可恢复处理）。
                    if (framesGot < 0 || dec.hasStreamFailed()) {
                        reportNativeStreamFailure(framesGot,
                                (int) (currentPresentationTimeUs / 1000));
                        break;
                    }
                    sawOutputEOS = true;
                    Log.i(TAG, "Native lossless decoder reached end of stream");
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (eventListener != null) {
                                eventListener.onCompletion();
                            }
                        }
                    });
                    break;
                }

                currentPresentationFrame += framesGot;
                if (sampleRate > 0) {
                    currentPresentationTimeUs = currentPresentationFrame * 1_000_000L / sampleRate;
                }

                // ★★★ 核心：进入 Native C++ DSP 进行 EQ、BassBoost、全景声场与混响运算 ★★★
                // 下混必须在 DSP 之前：DSP 的声道数已按立体声初始化，喂多声道会整块透传
                short[] dspBuf = pcmBuf;
                if (needsDownmix) {
                    PcmDownmix.toStereo(pcmBuf, 0, framesGot, srcChannels, mixBuf, 0);
                    dspBuf = mixBuf;
                }
                NativeDsp.processShorts(dspBuf, 0, framesGot);

                // 写入裸 PCM AudioTrack (直接写 short 数组)
                if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.write(dspBuf, 0, framesGot * outChannels);
                }
            }
        } catch (Exception e) {
            if (isRendering.get()) {
                Log.e(TAG, "Exception in nativeRenderLoop", e);
                synchronized (stateLock) {
                    isPrepared = false;
                    isPlaying = false;
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (eventListener != null) {
                            eventListener.onError(MediaPlayerError.DECODE_FAILED,
                                    "native render loop died: " + e.getMessage());
                        }
                    }
                });
            }
        }
    }

    /**
     * 原生软解断流（非播完）：留痕并如实上报错误。
     *
     * 绝不能上报 onCompletion——那会让服务自动切下一首，用户看到的是「歌自己乱跳」。
     * 上报 onError 后 {@code AudioPlayerService.handlePlayerError} 对 v3 引擎的负值
     * 错误码一律按可恢复处理，会带当前进度重试同一首。
     */
    private void reportNativeStreamFailure(final int framesGot, final int atPositionMs) {
        CrashMonitor.putContext("nativeStreamStalled", true);
        CrashMonitor.putContext("nativeStallAtMs", atPositionMs);
        reportNativeFailure("native stream stalled: frames=" + framesGot
                + " at=" + atPositionMs + "ms/" + currentDurationMs + "ms"
                + " sr=" + sampleRate + " ch=" + channelCount
                + " path=" + dataSourcePath, atPositionMs);
    }

    /**
     * 原生软解 seek 失败：同样必须如实上报。
     *
     * 二分 seek 中途失败（网络超时/截止）后 dr_flac 的流位置停在二分中间，
     * 继续读只会解出乱数据；也不许静默按旧位置续播。上报后走服务层带断点的
     * 同曲重试（有 GIVE_UP 上限，不会无限循环）。
     */
    private void reportNativeSeekFailure(final int seekTargetMs) {
        reportNativeFailure("native seek failed: target=" + seekTargetMs
                + "ms at=" + (currentPresentationTimeUs / 1000) + "ms"
                + " path=" + dataSourcePath, (int) (currentPresentationTimeUs / 1000));
    }

    private void reportNativeFailure(final String detail, final int atPositionMs) {
        Log.e(TAG, detail);
        CrashMonitor.breadcrumb("v3", detail);
        synchronized (stateLock) {
            isPrepared = false;
            isPlaying = false;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (eventListener != null) {
                    eventListener.onError(MediaPlayerError.STREAM_STALL, detail);
                }
            }
        });
    }

    /**
     * 原生软解 Seek 执行。
     *
     * @return true = seek 完成（无论原目标位置还是旧位置），渲染循环继续；
     *         false = seek 失败且已上报 onError，渲染循环必须退出。
     *         失败时会把 pendingSeekMs 回填为本次目标，服务层重试时断点不丢
     *         （handlePlayerError 优先取 pendingSeekMs 作恢复位置）。
     */
    private boolean doNativeSeekInternal(int seekTargetMs) {
        NativeLosslessDecoder dec = nativeDecoder;
        if (dec == null) return true;
        final long startedAt = SystemClock.elapsedRealtime();
        boolean ok = false;
        try {
            ok = dec.seekToMs(seekTargetMs);
            if (ok) {
                currentPresentationFrame = (long) seekTargetMs * sampleRate / 1000L;
                currentPresentationTimeUs = currentPresentationFrame * 1_000_000L / sampleRate;
                long tookMs = SystemClock.elapsedRealtime() - startedAt;
                if (tookMs > 1000) {
                    // 装车复验量化点：>1s 的 seek 基本是弱网下 dr_flac 二分重定位串
                    Log.i(TAG, "native seek " + seekTargetMs + "ms took " + tookMs
                            + "ms (relocation burst on weak link)");
                    CrashMonitor.breadcrumb("v3", "slow native seek " + tookMs
                            + "ms -> " + seekTargetMs + "ms");
                }
            }
            sawOutputEOS = false;
            if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.play();
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (eventListener != null) {
                        eventListener.onSeekComplete();
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Seek error in native render loop", e);
        }
        // seek 失败后解码器内部流位置已不可信（二分中断点），绝不能静默续播旧数据；
        // 正在拆除（isRendering 已清）时不报错，由 close 流程收尾。
        // 展示位锚到本次目标：解码器已死、渲染循环即将退出，该值此后只作重试锚点——
        // 服务层 handlePlayerError 经 getCurrentPosition() 取恢复位置，锚在目标上
        // 重试断点不丢（锚在旧位 0ms 会丢断点）
        if (!ok && isRendering.get() && nativeDecoder == dec) {
            currentPresentationFrame = (long) seekTargetMs * sampleRate / 1000L;
            currentPresentationTimeUs = currentPresentationFrame * 1_000_000L / sampleRate;
            reportNativeSeekFailure(seekTargetMs);
            return false;
        }
        return true;
    }

    /**
     * 系统 MediaCodec 传统解码渲染循环 (MP3/AAC)
     */
    private void codecRenderLoop() {
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        byte[] pcmTempBuf = new byte[8192];
        byte[] mixTempBuf = null; // 多声道下混 scratch，声道数变化时按新帧长重算

        try {
            while (isRendering.get() && !sawOutputEOS) {
                int seekTarget = pendingSeekMs;
                if (seekTarget >= 0) {
                    pendingSeekMs = -1;
                    doCodecSeekInternal(seekTarget);
                }

                if (!isPlaying) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                // 1. 送数据到解码器
                if (!sawInputEOS) {
                    int inputBufIndex = codec.dequeueInputBuffer(10000);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufIndex];
                        inputBuffer.clear();
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            sawInputEOS = true;
                            codec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            codec.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // 2. 取解码后的 PCM
                int res = codec.dequeueOutputBuffer(info, 10000);
                if (res >= 0) {
                    ByteBuffer outputBuffer = outputBuffers[res];
                    if (info.size > 0 && audioTrack != null) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);

                        currentPresentationTimeUs = info.presentationTimeUs;

                        int remaining = info.size;
                        final int srcCh = channelCount;
                        final int outCh = renderChannels;
                        final int bytesPerSrcFrame = 2 * srcCh;
                        // 分块必须整帧对齐：8192 对 6 声道不整除，切在帧中间会让下混读到半个样本
                        final int chunkCap = pcmTempBuf.length - (pcmTempBuf.length % bytesPerSrcFrame);
                        final boolean needsDownmix = srcCh > outCh;
                        final int mixNeed = chunkCap / bytesPerSrcFrame * 4;
                        if (needsDownmix && (mixTempBuf == null || mixTempBuf.length < mixNeed)) {
                            mixTempBuf = new byte[mixNeed];
                        }
                        while (remaining > 0 && isRendering.get()) {
                            int toRead = Math.min(remaining, chunkCap);
                            outputBuffer.get(pcmTempBuf, 0, toRead);

                            byte[] out = pcmTempBuf;
                            int outLen = toRead;
                            if (needsDownmix) {
                                int frames = toRead / bytesPerSrcFrame;
                                PcmDownmix.toStereoBytes(pcmTempBuf, 0, frames, srcCh, mixTempBuf, 0);
                                out = mixTempBuf;
                                outLen = frames * 4;
                            }

                            // ★★★ 核心：进入 Native C++ DSP 进行音效运算 ★★★
                            NativeDsp.processBytes(out, 0, outLen);

                            // 写入裸 PCM AudioTrack
                            if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack.write(out, 0, outLen);
                            }
                            remaining -= toRead;
                        }
                    }

                    codec.releaseOutputBuffer(res, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                        Log.i(TAG, "MediaCodec reached end of audio stream");
                        if (!isRendering.get()) {
                            break; // 拆机期间的滞留 EOS：不是真播完，别触发自动切歌
                        }
                        // 这里**不**判断"下载侧是否已判死"来区分真播完与假播完：
                        // requestResetLocked() 会把 fatalError 清零，而真车的证据是判死之后流
                        // 又重连续下了约 2MB —— 等 EOS 到达时那个标志早没了。总长未知的
                        // close-delimited 响应下"中途断"和"播完"在抽取器视角本来就不可区分。
                        // 裁定放在服务层，用不会被清掉的证据（播到哪儿 vs 应该播到哪儿），
                        // 见 PlaybackStateMachine.isPrematureCompletion。
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (eventListener != null) {
                                    eventListener.onCompletion();
                                }
                            }
                        });
                    }
                } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    Log.i(TAG, "Decoder output format changed: " + newFormat);
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    renderChannels = PcmDownmix.effectiveChannels(channelCount);
                    if (renderChannels != channelCount) {
                        CrashMonitor.breadcrumb("v3", "downmix " + channelCount + "ch->"
                                + renderChannels + "ch (codec output format changed)");
                    }
                    // scratch 容量按源声道帧长算的，声道数一变就不可信，交给下一轮重分配
                    mixTempBuf = null;
                    NativeDsp.init(sampleRate, renderChannels);
                }
            }
        } catch (Exception e) {
            if (isRendering.get()) {
                Log.e(TAG, "Exception in codecRenderLoop", e);
                synchronized (stateLock) {
                    isPrepared = false;
                    isPlaying = false;
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (eventListener != null) {
                            eventListener.onError(MediaPlayerError.DECODE_FAILED,
                                    "codec render loop died: " + e.getMessage());
                        }
                    }
                });
            }
        }
    }

    /**
     * MediaCodec 渲染线程内执行 seek
     */
    private void doCodecSeekInternal(int seekTargetMs) {
        MediaExtractor ex = extractor;
        MediaCodec dec = codec;
        if (ex == null || dec == null) {
            return;
        }
        try {
            ex.seekTo(seekTargetMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            dec.flush();
            sawInputEOS = false;
            sawOutputEOS = false;
            if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.play();
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (eventListener != null) {
                        eventListener.onSeekComplete();
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Seek error in codec render loop", e);
        }
    }

    /** 错误码约定：负值与系统 MediaPlayer 错误码空间区分开 */
    public static final class MediaPlayerError {
        public static final int DECODE_FAILED = -10001;
        /** 供数断流（环形缓冲无进展 / 上游涓流饥饿），非解码器本身故障 */
        public static final int STREAM_STALL = -10002;
        private MediaPlayerError() {}
    }

    @Override
    public void start() {
        synchronized (stateLock) {
            if (!isPrepared || isReleased) return;
            isPlaying = true;
            if (audioTrack != null) {
                try {
                    audioTrack.play();
                } catch (Exception e) {
                    Log.w(TAG, "AudioTrack.play error", e);
                }
            }
        }
    }

    @Override
    public void pause() {
        synchronized (stateLock) {
            isPlaying = false;
            if (audioTrack != null) {
                try {
                    audioTrack.pause();
                } catch (Exception e) {
                    Log.w(TAG, "AudioTrack.pause error", e);
                }
            }
        }
    }

    @Override
    public void stop() {
        synchronized (stateLock) {
            isPlaying = false;
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                } catch (Exception e) {
                    Log.w(TAG, "AudioTrack.stop error", e);
                }
            }
        }
    }

    @Override
    public void seekTo(int msec) {
        pendingSeekMs = msec;
    }

    private void doSeek(int msec) {
        pendingSeekMs = msec;
    }

    @Override
    public void release() {
        synchronized (stateLock) {
            isReleased = true;
            isPlaying = false;
            isPrepared = false;
        }
        abortPrepare = true; // 打断可能正在进行的 prefill 门槛等待，让位释放
        if (decodeHandler != null) {
            decodeHandler.obtainMessage(MSG_RELEASE).sendToTarget();
        }
    }

    private void doRelease() {
        doTeardown();
        if (decodeThread != null) {
            decodeThread.quit();
            decodeThread = null;
        }
    }

    /** 停渲染 + 释放解码组件，但保留解码线程（reset 后还要能继续 prepare） */
    private void doTeardown() {
        long beginMs = System.currentTimeMillis();
        stopRenderingThread();
        releaseDecoderComponents();
        // 耗时能说明拆机是否踩在 join(3000) / native close 超时的边缘
        CrashMonitor.breadcrumb("v3", "teardown done in "
                + (System.currentTimeMillis() - beginMs) + "ms");
    }

    @Override
    public void reset() {
        synchronized (stateLock) {
            isPlaying = false;
            isPrepared = false;
            currentDurationMs = 0;
            currentPresentationTimeUs = 0;
            currentPresentationFrame = 0;
            pendingSeekMs = -1;
        }
        abortPrepare = true; // 切歌 / 重建：打断正在进行的 prefill 门槛等待
        NativeDsp.reset();
        // 拆机绝不能在调用方线程做：stopRenderingThread 里的 join(3000) 会挂住调用方，
        // 而 reset 是 AudioPlayerService 在主线程（含 500ms 看门狗 tick）直接调的
        Handler h = decodeHandler;
        if (h != null && decodeThread != null && decodeThread.isAlive()) {
            h.obtainMessage(MSG_TEARDOWN).sendToTarget();
        } else {
            doTeardown();
        }
    }

    private void releaseDecoderComponents() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
        if (nativeDecoder != null) {
            try {
                nativeDecoder.close();
            } catch (Exception ignored) {}
            nativeDecoder = null;
        }
        isNativeMode = false;
        currentPresentationFrame = 0;
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (Exception ignored) {}
            codec = null;
        }
        if (extractor != null) {
            try {
                extractor.release();
            } catch (Exception ignored) {}
            extractor = null;
        }
    }

    @Override
    public boolean isPlaying() {
        return isPlaying;
    }

    @Override
    public int getCurrentPosition() {
        return (int) (currentPresentationTimeUs / 1000);
    }

    @Override
    public int getDuration() {
        return currentDurationMs;
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        this.volume = (leftVolume + rightVolume) * 0.5f;
        if (audioTrack != null) {
            try {
                audioTrack.setStereoVolume(leftVolume, rightVolume);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void setOnEventListener(OnEventListener listener) {
        this.eventListener = listener;
    }

    @Override
    public int getAudioSessionId() {
        return 0; // 裸 PCM 直写 AudioFlinger，不经过系统 audiofx 会话
    }

    @Override
    public void attachAuxEffect(int effectId) {
        // 自研软件混响在 NativeDsp 内部完成，系统 aux 总线不再使用
    }

    @Override
    public void setAuxEffectSendLevel(float level) {
        // 同上，no-op
    }
}
