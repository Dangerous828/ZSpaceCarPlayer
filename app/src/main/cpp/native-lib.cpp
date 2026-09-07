#include <jni.h>
#include <android/log.h>
#include <new>
#include "DspEngine.h"

#define TAG "DspEngineJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static DspEngine *gDspEngine = nullptr;

namespace {

    // 脏参数防线：车机曲库只有单/立体声与常规采样率，超出范围一律拒绝。
    // 让 sr=0 进 init 会让滤波器系数计算除零，比崩溃更难查。
    const int MIN_SAMPLE_RATE = 4000;
    const int MAX_SAMPLE_RATE = 384000;
    const int MAX_CHANNELS = 8;

    /**
     * 数组范围校验。JNI 越界写不是 Java 异常——是 SIGSEGV 或静默踩堆，
     * Java 侧的 try-catch 一点都拦不住，只能在这里挡住。
     */
    bool shortsRangeOk(JNIEnv *env, jshortArray buffer, jint offset, jint numFrames, int channels) {
        if (offset < 0 || numFrames <= 0 || channels <= 0) return false;
        long long needed = static_cast<long long>(offset)
                           + static_cast<long long>(numFrames) * channels;
        return needed <= static_cast<long long>(env->GetArrayLength(buffer));
    }

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeInit(JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE
        || channels < 1 || channels > MAX_CHANNELS) {
        LOGE("nativeInit rejected: sr=%d ch=%d", (int) sampleRate, (int) channels);
        return;
    }
    if (!gDspEngine) {
        // nothrow：分配失败时返回空指针而不是抛 bad_alloc——C++ 异常穿过 JNI 边界是 UB，
        // 车机上表现为整个进程 abort
        gDspEngine = new(std::nothrow) DspEngine();
        if (!gDspEngine) {
            LOGE("nativeInit: DspEngine allocation failed");
            return;
        }
    }
    gDspEngine->init(sampleRate, channels);
    LOGI("NativeDsp initialized with sr=%d, ch=%d", sampleRate, channels);
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeReset(JNIEnv *env, jclass clazz) {
    if (gDspEngine) {
        gDspEngine->reset();
    }
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeSetEqualizerPreset(JNIEnv *env, jclass clazz, jint preset) {
    if (gDspEngine) {
        gDspEngine->setEqualizerPreset(preset);
    }
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeSetEqualizerBand(JNIEnv *env, jclass clazz, jint band, jfloat gainDb) {
    if (gDspEngine) {
        gDspEngine->setEqualizerBandGain(band, gainDb);
    }
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeSetBassBoost(JNIEnv *env, jclass clazz, jint percent) {
    if (gDspEngine) {
        gDspEngine->setBassBoostPercent(percent);
    }
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeSetVirtualizer(JNIEnv *env, jclass clazz, jint percent) {
    if (gDspEngine) {
        gDspEngine->setVirtualizerPercent(percent);
    }
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeSetReverb(JNIEnv *env, jclass clazz, jint mode) {
    if (gDspEngine) {
        gDspEngine->setReverbMode(mode);
    }
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeProcessShorts(JNIEnv *env, jclass clazz, jshortArray buffer, jint offset, jint numFrames) {
    if (!gDspEngine || !buffer) return;

    int channels = gDspEngine->getChannels();
    if (!shortsRangeOk(env, buffer, offset, numFrames, channels)) {
        LOGE("processShorts rejected: len=%d offset=%d frames=%d ch=%d",
             (int) env->GetArrayLength(buffer), (int) offset, (int) numFrames, channels);
        return;
    }

    jshort *bufPtr = env->GetShortArrayElements(buffer, nullptr);
    if (bufPtr) {
        gDspEngine->process(bufPtr + offset, numFrames);
        env->ReleaseShortArrayElements(buffer, bufPtr, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeProcessBytes(JNIEnv *env, jclass clazz, jbyteArray buffer, jint byteOffset, jint numBytes) {
    if (!gDspEngine || !buffer || numBytes <= 0 || byteOffset < 0) return;

    jsize len = env->GetArrayLength(buffer);
    // 16-bit PCM 必须偶数字节位起：奇数偏移会把 int16 读写推过对齐边界（ARM 上可能直接 fault）
    if ((byteOffset & 1) != 0
        || static_cast<long long>(byteOffset) + numBytes > static_cast<long long>(len)) {
        LOGE("processBytes rejected: len=%d offset=%d bytes=%d",
             (int) len, (int) byteOffset, (int) numBytes);
        return;
    }

    jbyte *bufPtr = env->GetByteArrayElements(buffer, nullptr);
    if (bufPtr) {
        // 16-bit PCM: 每样本 2 字节; 帧数按实际声道数换算 (单声道 /2, 立体声 /4)
        int bytesPerFrame = 2 * (gDspEngine->getChannels() > 0 ? gDspEngine->getChannels() : 2);
        int numFrames = numBytes / bytesPerFrame;
        if (numFrames > 0) {
            int16_t *pcm16 = reinterpret_cast<int16_t*>(bufPtr + byteOffset);
            gDspEngine->process(pcm16, numFrames);
        }
        env->ReleaseByteArrayElements(buffer, bufPtr, 0);
    }
}

} // extern "C"
