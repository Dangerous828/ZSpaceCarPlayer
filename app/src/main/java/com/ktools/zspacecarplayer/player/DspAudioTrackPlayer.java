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
import android.util.Log;

import com.ktools.zspacecarplayer.dsp.NativeDsp;
import com.ktools.zspacecarplayer.player.stream.HttpProxyServer;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 针对 Android 4.3 (API 18) 与吉利 8600 车机优化的自研软解管线播放器:
 * 1. 使用 MediaExtractor + MediaCodec 将音频解出原始 PCM (16-bit)
 * 2. 经由私有 C++ NativeDsp 核心进行 EQ、BassBoost、全景声场 (Widener) 与 Reverb 处理
 * 3. 通过纯裸 AudioTrack 写入车机 AudioFlinger，彻底绕过系统缺陷 Virtualizer/Reverb
 */
public class DspAudioTrackPlayer implements IAudioPlayer {
    private static final String TAG = "DspAudioTrackPlayer";

    private static final int MSG_PREPARE = 1;
    private static final int MSG_SEEK = 2;
    private static final int MSG_RELEASE = 3;

    private String dataSourcePath;
    private OnEventListener eventListener;
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Object stateLock = new Object();
    private volatile boolean isPlaying = false;
    private volatile boolean isPrepared = false;
    private volatile boolean isReleased = false;

    private volatile int currentDurationMs = 0;
    private volatile long currentPresentationTimeUs = 0;
    private volatile int pendingSeekMs = -1;

    private AudioTrack audioTrack;
    private MediaExtractor extractor;
    private MediaCodec codec;

    private int sampleRate = 44100;
    private int channelCount = 2;
    private float volume = 1.0f;

    private volatile Thread renderThread;
    private final AtomicBoolean isRendering = new AtomicBoolean(false);

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
                }
            }
        };
    }

    @Override
    public void setDataSource(String pathOrUrl) throws Exception {
        synchronized (stateLock) {
            this.dataSourcePath = pathOrUrl;
            this.isPrepared = false;
            this.isPlaying = false;
            this.currentPresentationTimeUs = 0;
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

        try {
            stopRenderingThread();
            releaseDecoderComponents();

            extractor = new MediaExtractor();
            if (dataSourcePath.startsWith("http://") || dataSourcePath.startsWith("https://")) {
                // v3: 网络源经本地回环代理，前置大环形缓冲抗抖动
                extractor.setDataSource(HttpProxyServer.getInstance().getProxyUrl(dataSourcePath));
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

            Log.i(TAG, "Audio format: " + mime + ", sr=" + sampleRate + ", ch=" + channelCount + ", dur=" + currentDurationMs);

            // 初始化 Native DSP 核心
            NativeDsp.init(sampleRate, channelCount);

            // 配置 AudioTrack (Android 4.3 兼容)
            int channelConfig = (channelCount == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
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

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

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

        } catch (final Exception e) {
            Log.e(TAG, "doPrepare failed", e);
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

    private void startRenderingLoop() {
        stopRenderingThread();
        isRendering.set(true);
        renderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                renderLoop();
            }
        }, "DspPlayer-RenderLoop");
        renderThread.start();
    }

    private void stopRenderingThread() {
        isRendering.set(false);
        if (renderThread != null) {
            renderThread.interrupt();
            try {
                renderThread.join(500);
            } catch (InterruptedException ignored) {}
            renderThread = null;
        }
    }

    private void renderLoop() {
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        byte[] pcmTempBuf = new byte[8192];

        try {
            while (isRendering.get() && !sawOutputEOS) {
                if (!isPlaying) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                // 处理 Seek 请求
                int seekTarget = pendingSeekMs;
                if (seekTarget >= 0) {
                    pendingSeekMs = -1;
                    if (extractor != null && codec != null) {
                        try {
                            extractor.seekTo(seekTarget * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            codec.flush();
                            sawInputEOS = false;
                            sawOutputEOS = false;
                            inputBuffers = codec.getInputBuffers();
                            outputBuffers = codec.getOutputBuffers();
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
                            Log.w(TAG, "Seek error in render loop", e);
                        }
                    }
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
                        while (remaining > 0 && isRendering.get()) {
                            int toRead = Math.min(remaining, pcmTempBuf.length);
                            outputBuffer.get(pcmTempBuf, 0, toRead);

                            // ★★★ 核心：进入 Native C++ DSP 进行 EQ、BassBoost、全景声场与混响运算 ★★★
                            NativeDsp.processBytes(pcmTempBuf, 0, toRead);

                            // 写入裸 PCM AudioTrack
                            if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack.write(pcmTempBuf, 0, toRead);
                            }
                            remaining -= toRead;
                        }
                    }

                    codec.releaseOutputBuffer(res, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                        Log.i(TAG, "Reached end of audio stream");
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
                    NativeDsp.init(sampleRate, channelCount);
                }
            }
        } catch (Exception e) {
            if (isRendering.get()) {
                Log.e(TAG, "Exception in renderLoop", e);
            }
        }
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
        if (decodeHandler != null) {
            decodeHandler.obtainMessage(MSG_RELEASE).sendToTarget();
        }
    }

    private void doRelease() {
        stopRenderingThread();
        releaseDecoderComponents();
        if (decodeThread != null) {
            decodeThread.quit();
            decodeThread = null;
        }
    }

    @Override
    public void reset() {
        synchronized (stateLock) {
            isPlaying = false;
            isPrepared = false;
            currentDurationMs = 0;
            currentPresentationTimeUs = 0;
            pendingSeekMs = -1;
        }
        stopRenderingThread();
        releaseDecoderComponents();
        NativeDsp.reset();
    }

    private void releaseDecoderComponents() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
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
}
