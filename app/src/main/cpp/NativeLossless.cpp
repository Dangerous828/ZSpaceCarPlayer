// v3 自研引擎的原生无损软解码：FLAC / WAV / MP3 全部走 dr_* 单头库，
// 彻底绕开吉利 8600 (Android 4.3) 被阉割的 MediaCodec（厂商只开放了
// audio/mpeg 与 audio/mp4a-latm，无 audio/flac，导致直传无损必崩）。
// 数据经 JNI 回调桥回 Java 侧 BufferedHttpSource 环形缓冲（阻塞语义，
// 天然被流式下载驱动；seek 由环形缓冲重定位下载完成）。
#include <jni.h>
#include <android/log.h>
#include <new>
#include <cstring>
#include <atomic>
#include <mutex>
#include <chrono>

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#define DR_FLAC_NO_WCHAR
#include "dr_flac.h"

#define DR_WAV_IMPLEMENTATION
#define DR_WAV_NO_STDIO
#define DR_WAV_NO_WCHAR
#include "dr_wav.h"

#define DR_MP3_IMPLEMENTATION
#define DR_MP3_NO_STDIO
#include "dr_mp3.h"

#define TAG "NativeLossless"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

    enum Format { FMT_UNKNOWN = 0, FMT_FLAC = 1, FMT_WAV = 2, FMT_MP3 = 3 };

    // ---- JNI 回调桥 ----
    JavaVM *g_vm = nullptr;
    jclass g_cls = nullptr;
    jmethodID g_readMid = nullptr;
    jmethodID g_seekMid = nullptr;
    jmethodID g_abortMid = nullptr;

    struct StreamBridge {
        jobject readerGlobal;      // Java LosslessStreamReader
        jbyteArray bufGlobal;      // 预分配的 64KB 搬运缓冲区（生命周期内复用，避免高频 GC）
        std::atomic<bool> aborted; // release 后拒绝一切回调，防止悬垂引用
        long long pos;             // 当前绝对字节位：read 推进 / seek 重定位（单一权威游标）

        StreamBridge() : readerGlobal(nullptr), bufGlobal(nullptr), aborted(false), pos(0) {}
    };

    int detachIfNeeded() {
        JNIEnv *env = nullptr;
        if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
            return 0;
        }
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            return 1;
        }
        return -1;
    }

    void attachDetach(int needDetach) {
        if (needDetach == 1) {
            g_vm->DetachCurrentThread();
        }
    }

    // ---- 共享读/seek 实现 ----
    // 顺序读固定 from-position=b->pos（dr_* 顺序消费即读即推进；seek 走 bridgeSeek）
    size_t bridgeRead(StreamBridge *b, void *dst, size_t bytesToRead) {
        if (b->aborted.load() || bytesToRead == 0) return 0;
        int nd = detachIfNeeded();
        if (nd < 0) return 0;
        JNIEnv *env = nullptr;
        g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);

        const int CAP = 65536;
        size_t total = 0;
        while (total < bytesToRead) {
            int want = static_cast<int>(bytesToRead - total);
            if (want > CAP) want = CAP;
            jint n = env->CallStaticIntMethod(g_cls, g_readMid, b->readerGlobal,
                                              static_cast<jlong>(b->pos), b->bufGlobal, 0, want);
            if (n <= 0) break; // EOF 或 Java 侧读异常（bridgeRead 已折叠为 0/-1）
            env->GetByteArrayRegion(b->bufGlobal, 0, n, reinterpret_cast<jbyte *>(
                    static_cast<char *>(dst) + total));
            b->pos += n;
            total += static_cast<size_t>(n);
            if (n < want) break; // 短读：已到可用边界
        }
        attachDetach(nd);
        return total;
    }

    // origin: 0=SET 1=CUR 2=END（三库枚举逐一对齐）
    bool bridgeSeek(StreamBridge *b, long long offset, int origin) {
        if (b->aborted.load()) return false;
        long long target;
        if (origin == 0) {          // SEEK_SET
            target = offset;
        } else if (origin == 1) {   // SEEK_CUR
            target = b->pos + offset;
        } else {                    // SEEK_END：dr_* 仅在总长未知时探测。先流式扫到 EOF
            char probe[4096];       //（Java 侧允许读到 EOF 返回 -1），再相对 EOF 定位
            size_t r;
            do {
                r = bridgeRead(b, probe, sizeof(probe));
            } while (r == sizeof(probe));
            if (r != 0) return false; // 读出错（非 EOF）：明确失败
            target = b->pos + offset; // 循环退出时 b->pos 即 EOF 绝对位置
        }
        if (target < 0) return false; // 不支持负绝对位
        int nd = detachIfNeeded();
        if (nd < 0) return false;
        JNIEnv *env = nullptr;
        g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        jboolean ok = env->CallStaticBooleanMethod(g_cls, g_seekMid, b->readerGlobal,
                                                   static_cast<jlong>(target));
        attachDetach(nd);
        if (ok != JNI_TRUE) return false;
        b->pos = target;
        return true;
    }

    // 通知 Java 侧读者取消其阻塞等待（readAt 正在等下载线程供数时必须能被唤醒，
    // 否则 close 拿不到 apiMutex，只能选择泄漏解码器而不是野指针崩溃）
    void abortReader(StreamBridge *b) {
        if (b->readerGlobal == nullptr || g_abortMid == nullptr) return;
        int nd = detachIfNeeded();
        if (nd < 0) return;
        JNIEnv *env = nullptr;
        g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        env->CallStaticVoidMethod(g_cls, g_abortMid, b->readerGlobal);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        attachDetach(nd);
    }

    // 三个库各自的 proc 壳
    size_t flacRead(void *ud, void *dst, size_t n) { return bridgeRead((StreamBridge *) ud, dst, n); }

    drflac_bool32 flacSeek(void *ud, int off, drflac_seek_origin o) {
        return bridgeSeek((StreamBridge *) ud, off, static_cast<int>(o)) ? DRFLAC_TRUE : DRFLAC_FALSE;
    }

    size_t wavRead(void *ud, void *dst, size_t n) { return bridgeRead((StreamBridge *) ud, dst, n); }

    drwav_bool32 wavSeek(void *ud, int off, drwav_seek_origin o) {
        return bridgeSeek((StreamBridge *) ud, off, static_cast<int>(o)) ? DRWAV_TRUE : DRWAV_FALSE;
    }

    size_t mp3Read(void *ud, void *dst, size_t n) { return bridgeRead((StreamBridge *) ud, dst, n); }

    drmp3_bool32 mp3Seek(void *ud, int off, drmp3_seek_origin o) {
        return bridgeSeek((StreamBridge *) ud, off, static_cast<int>(o)) ? DRMP3_TRUE : DRMP3_FALSE;
    }

    struct NativeDecoder {
        StreamBridge bridge;
        int format;          // Format
        drflac *flac;
        drwav *wav;
        drmp3 *mp3;
        int sampleRate;
        int channels;
        long long totalFrames; // 0 = 未知（如流式 MP3）
        // 串行化解码/seek 与 close。bridge.aborted 只能拦住「尚未进入」的回调，
        // 拦不住已经深入 drflac_read_pcm_frames_s16 内部、正阻塞在 JNI 回调里的线程；
        // 没有这把锁，close 的 delete 与在跑的解码构成 heap use-after-free（SIGSEGV 闪退）。
        std::timed_mutex apiMutex;

        NativeDecoder() : format(FMT_UNKNOWN), flac(nullptr), wav(nullptr), mp3(nullptr),
                          sampleRate(0), channels(0), totalFrames(0) {}
    };

    // close 等待在飞解码收尾的上限。Java 侧 reader.abort() 会让阻塞的 readAt
    // 在一个 wait 周期内抛出，正常远小于此值；真超时则宁可泄漏也不释放。
    const int CLOSE_LOCK_TIMEOUT_MS = 3000;

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("com/ktools/zspacecarplayer/dsp/NativeLosslessDecoder");
    if (!cls) return JNI_ERR;
    g_cls = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
    g_readMid = env->GetStaticMethodID(g_cls, "bridgeRead",
                                       "(Lcom/ktools/zspacecarplayer/dsp/NativeLosslessDecoder$LosslessStreamReader;J[BII)I");
    g_seekMid = env->GetStaticMethodID(g_cls, "bridgeSeek",
                                       "(Lcom/ktools/zspacecarplayer/dsp/NativeLosslessDecoder$LosslessStreamReader;J)Z");
    if (!g_readMid || !g_seekMid) return JNI_ERR;
    // abort 缺失只降级为「close 超时时泄漏解码器」，不值得让整个 DSP 库加载失败
    g_abortMid = env->GetStaticMethodID(g_cls, "bridgeAbort",
                                        "(Lcom/ktools/zspacecarplayer/dsp/NativeLosslessDecoder$LosslessStreamReader;)V");
    if (!g_abortMid) {
        env->ExceptionClear();
        LOGE("bridgeAbort not found, close will leak decoder on timeout");
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeLosslessDecoder_nativeOpen(
        JNIEnv *env, jclass clazz, jobject reader, jint sniffedFormat) {
    if (!reader) return 0;

    NativeDecoder *dec = new(std::nothrow) NativeDecoder();
    if (!dec) return 0;
    dec->bridge.readerGlobal = env->NewGlobalRef(reader);
    if (!dec->bridge.readerGlobal) {
        delete dec;
        return 0;
    }
    jbyteArray localBuf = env->NewByteArray(65536);
    if (!localBuf) {
        env->DeleteGlobalRef(dec->bridge.readerGlobal);
        delete dec;
        return 0;
    }
    dec->bridge.bufGlobal = reinterpret_cast<jbyteArray>(env->NewGlobalRef(localBuf));
    env->DeleteLocalRef(localBuf);
    if (!dec->bridge.bufGlobal) {
        env->DeleteGlobalRef(dec->bridge.readerGlobal);
        delete dec;
        return 0;
    }

    // 打开时 dr_* 会从流头解析容器头（read 回调按需向环形缓冲取数并阻塞等待下载）
    if (sniffedFormat == FMT_FLAC) {
        dec->flac = drflac_open(flacRead, flacSeek, nullptr, &dec->bridge, nullptr);
        if (dec->flac) {
            dec->format = FMT_FLAC;
            dec->sampleRate = static_cast<int>(dec->flac->sampleRate);
            dec->channels = static_cast<int>(dec->flac->channels);
            dec->totalFrames = static_cast<long long>(dec->flac->totalPCMFrameCount);
        }
    } else if (sniffedFormat == FMT_WAV) {
        dec->wav = new(std::nothrow) drwav();
        if (dec->wav && drwav_init(dec->wav, wavRead, wavSeek, nullptr, &dec->bridge, nullptr)) {
            dec->format = FMT_WAV;
            dec->sampleRate = static_cast<int>(dec->wav->sampleRate);
            dec->channels = static_cast<int>(dec->wav->channels);
            dec->totalFrames = static_cast<long long>(dec->wav->totalPCMFrameCount);
        } else {
            delete dec->wav;
            dec->wav = nullptr;
        }
    } else if (sniffedFormat == FMT_MP3) {
        dec->mp3 = new(std::nothrow) drmp3();
        if (dec->mp3 && drmp3_init(dec->mp3, mp3Read, mp3Seek, nullptr, nullptr, &dec->bridge,
                                   nullptr)) {
            dec->format = FMT_MP3;
            dec->totalFrames = (static_cast<long long>(dec->mp3->totalPCMFrameCount) == static_cast<long long>(DRMP3_UINT64_MAX))
                                       ? 0 : static_cast<long long>(dec->mp3->totalPCMFrameCount); // 流式/VBR 未知记 0
            dec->sampleRate = static_cast<int>(dec->mp3->sampleRate);
            dec->channels = static_cast<int>(dec->mp3->channels);
        } else {
            delete dec->mp3;
            dec->mp3 = nullptr;
        }
    }

    if (dec->format == FMT_UNKNOWN) {
        if (dec->bridge.bufGlobal) env->DeleteGlobalRef(dec->bridge.bufGlobal);
        if (dec->bridge.readerGlobal) env->DeleteGlobalRef(dec->bridge.readerGlobal);
        delete dec;
        return 0;
    }
    LOGI("opened fmt=%d sr=%d ch=%d frames=%lld", dec->format, dec->sampleRate,
         dec->channels, dec->totalFrames);
    return reinterpret_cast<jlong>(dec);
}

JNIEXPORT jint JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeLosslessDecoder_nativeGetSampleRate(
        JNIEnv *env, jclass clazz, jlong handle) {
    NativeDecoder *dec = reinterpret_cast<NativeDecoder *>(handle);
    return dec ? dec->sampleRate : 0;
}

JNIEXPORT jint JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeLosslessDecoder_nativeGetChannels(
        JNIEnv *env, jclass clazz, jlong handle) {
    NativeDecoder *dec = reinterpret_cast<NativeDecoder *>(handle);
    return dec ? dec->channels : 0;
}

JNIEXPORT jlong JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeLosslessDecoder_nativeGetTotalFrames(
        JNIEnv *env, jclass clazz, jlong handle) {
    NativeDecoder *dec = reinterpret_cast<NativeDecoder *>(handle);
    return dec ? dec->totalFrames : 0;
}

JNIEXPORT jint JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeLosslessDecoder_nativeReadSamples(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray dst, jint dstOffset,
        jint numFrames) {
    NativeDecoder *dec = reinterpret_cast<NativeDecoder *>(handle);
    if (!dec || !dst || numFrames <= 0) return -1;
    // close 进行中/已完成：立即失败，不与之抢锁（Java 侧把 -1 当解码错误处理）
    std::unique_lock<std::timed_mutex> lk(dec->apiMutex, std::defer_lock);
    if (!lk.try_lock()) return -1;
    if (dec->bridge.aborted.load()) return -1;
    // dr_* 会往 dst 写 numFrames * channels 个 short。越界写是堆破坏（SIGSEGV 或静默踩内存），
    // Java 侧 try-catch 拦不住，只能在 JNI 边界挡住。校验放在拿锁之后，
    // 免得读到正在被 close 释放的字段。
    if (dstOffset < 0 || dec->channels <= 0) return -1;
    jsize dstLen = env->GetArrayLength(dst);
    if (static_cast<long long>(dstOffset)
            + static_cast<long long>(numFrames) * dec->channels
        > static_cast<long long>(dstLen)) {
        LOGE("readSamples rejected: dstLen=%d offset=%d frames=%d ch=%d",
             (int) dstLen, (int) dstOffset, numFrames, dec->channels);
        return -1;
    }
    jshort *buf = env->GetShortArrayElements(dst, nullptr);
    if (!buf) return -1;
    drflac_uint64 got = 0;
    if (dec->format == FMT_FLAC) {
        got = drflac_read_pcm_frames_s16(dec->flac, static_cast<drflac_uint64>(numFrames),
                                         reinterpret_cast<drflac_int16 *>(buf + dstOffset));
    } else if (dec->format == FMT_WAV) {
        got = drwav_read_pcm_frames_s16(dec->wav, static_cast<drwav_uint64>(numFrames),
                                        reinterpret_cast<drwav_int16 *>(buf + dstOffset));
    } else if (dec->format == FMT_MP3) {
        got = drmp3_read_pcm_frames_s16(dec->mp3, static_cast<drmp3_uint64>(numFrames),
                                        reinterpret_cast<drmp3_int16 *>(buf + dstOffset));
    }
    env->ReleaseShortArrayElements(dst, buf, 0);
    return static_cast<jint>(got);
}

JNIEXPORT jboolean JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeLosslessDecoder_nativeSeekToFrame(
        JNIEnv *env, jclass clazz, jlong handle, jlong frameIndex) {
    NativeDecoder *dec = reinterpret_cast<NativeDecoder *>(handle);
    if (!dec || frameIndex < 0) return JNI_FALSE;
    std::unique_lock<std::timed_mutex> lk(dec->apiMutex, std::defer_lock);
    if (!lk.try_lock()) return JNI_FALSE;
    if (dec->bridge.aborted.load()) return JNI_FALSE;
    bool ok = false;
    if (dec->format == FMT_FLAC) {
        ok = drflac_seek_to_pcm_frame(dec->flac, static_cast<drflac_uint64>(frameIndex)) == DRFLAC_TRUE;
    } else if (dec->format == FMT_WAV) {
        ok = drwav_seek_to_pcm_frame(dec->wav, static_cast<drwav_uint64>(frameIndex)) == DRWAV_TRUE;
    } else if (dec->format == FMT_MP3) {
        ok = drmp3_seek_to_pcm_frame(dec->mp3, static_cast<drmp3_uint64>(frameIndex)) == DRMP3_TRUE;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeLosslessDecoder_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    NativeDecoder *dec = reinterpret_cast<NativeDecoder *>(handle);
    if (!dec) return;
    // 1. 先断回调：uninit 期间的读/seek 一律快速失败
    dec->bridge.aborted.store(true);
    // 2. 唤醒可能正阻塞在环形缓冲等网络数据的解码线程，否则它一直持有 apiMutex
    abortReader(&dec->bridge);
    // 3. 拿到锁才允许释放。拿不到说明仍有线程在解码内部——此时释放就是野指针，
    //    宁可泄漏一个解码器（有崩溃上报可见）也不能让车机 SIGSEGV 闪退。
    std::unique_lock<std::timed_mutex> lk(dec->apiMutex, std::defer_lock);
    if (!lk.try_lock_for(std::chrono::milliseconds(CLOSE_LOCK_TIMEOUT_MS))) {
        LOGE("close timed out waiting for in-flight decode, leaking decoder %p to avoid UAF",
             (void *) dec);
        return;
    }
    if (dec->format == FMT_FLAC) {
        if (dec->flac) {
            drflac_close(dec->flac);
            dec->flac = nullptr;
        }
    } else if (dec->format == FMT_WAV) {
        if (dec->wav) {
            drwav_uninit(dec->wav);
            delete dec->wav;
            dec->wav = nullptr;
        }
    } else if (dec->format == FMT_MP3) {
        if (dec->mp3) {
            drmp3_uninit(dec->mp3);
            delete dec->mp3;
            dec->mp3 = nullptr;
        }
    }
    if (dec->bridge.bufGlobal) {
        env->DeleteGlobalRef(dec->bridge.bufGlobal);
        dec->bridge.bufGlobal = nullptr;
    }
    if (dec->bridge.readerGlobal) {
        env->DeleteGlobalRef(dec->bridge.readerGlobal);
        dec->bridge.readerGlobal = nullptr;
    }
    lk.unlock();
    delete dec;
}

} // extern "C"
