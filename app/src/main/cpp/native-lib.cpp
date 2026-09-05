#include <jni.h>
#include <android/log.h>
#include "DspEngine.h"

#define TAG "DspEngineJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static DspEngine *gDspEngine = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeInit(JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    if (!gDspEngine) {
        gDspEngine = new DspEngine();
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
    if (!gDspEngine || !buffer || numFrames <= 0) return;

    jshort *bufPtr = env->GetShortArrayElements(buffer, nullptr);
    if (bufPtr) {
        gDspEngine->process(bufPtr + offset, numFrames);
        env->ReleaseShortArrayElements(buffer, bufPtr, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_ktools_zspacecarplayer_dsp_NativeDsp_nativeProcessBytes(JNIEnv *env, jclass clazz, jbyteArray buffer, jint byteOffset, jint numBytes) {
    if (!gDspEngine || !buffer || numBytes <= 0) return;

    jbyte *bufPtr = env->GetByteArrayElements(buffer, nullptr);
    if (bufPtr) {
        // 16-bit PCM: 每样本 2 字节; 帧数按实际声道数换算 (单声道 /2, 立体声 /4)
        int bytesPerFrame = 2 * (gDspEngine->getChannels() > 0 ? gDspEngine->getChannels() : 2);
        int numFrames = numBytes / bytesPerFrame;
        int16_t *pcm16 = reinterpret_cast<int16_t*>(bufPtr + byteOffset);
        gDspEngine->process(pcm16, numFrames);
        env->ReleaseByteArrayElements(buffer, bufPtr, 0);
    }
}

} // extern "C"
