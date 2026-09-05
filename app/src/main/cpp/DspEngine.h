#pragma once
#include "BiquadFilter.h"
#include "BassBoost.h"
#include "StereoWidener.h"
#include "ReverbEffect.h"
#include "Equalizer.h"
#include "SoftLimiter.h"
#include <cstdint>
#include <mutex>

class DspEngine {
public:
    DspEngine() : sampleRate(44100), channels(2) {
        updateSampleRate();
    }

    void init(int sr, int ch) {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        sampleRate = sr;
        channels = ch;
        updateSampleRate();
        resetInternal();
    }

    void reset() {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        resetInternal();
    }

    void setEqualizerPreset(int preset) {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        eq.setPreset(preset);
    }

    void setEqualizerBandGain(int band, float gainDb) {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        eq.setBandGain(band, gainDb);
    }

    void setBassBoostPercent(int percent) {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        bassBoost.setStrength(percent);
    }

    int getBassBoostPercent() {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        return bassBoost.getStrength();
    }

    void setVirtualizerPercent(int percent) {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        widener.setStrength(percent);
    }

    void setReverbMode(int mode) {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        reverb.setMode(mode);
    }

    int getReverbMode() {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        return reverb.getMode();
    }

    int getChannels() {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        return channels;
    }

    // 处理交错 16-bit PCM (Interleaved Stereo)
    void process(int16_t *buffer, int numFrames) {
        std::lock_guard<std::recursive_mutex> lock(dspMutex);
        const float invScale = 1.0f / 32768.0f;
        const float scale = 32767.0f;

        if (channels == 2) {
            for (int i = 0; i < numFrames; ++i) {
                int idx = i * 2;
                float left = (float)buffer[idx] * invScale;
                float right = (float)buffer[idx + 1] * invScale;

                // 1. Equalizer
                eq.process(left, right);

                // 2. Bass Boost
                bassBoost.process(left, right);

                // 3. Stereo Widener (Virtualizer)
                widener.process(left, right);

                // 4. Spatial Reverb
                reverb.process(left, right);

                // 5. Soft Limiter (Prevent Clipping)
                limiter.process(left, right);

                // Convert back to 16-bit integer with clamping
                float clampedL = left * scale;
                float clampedR = right * scale;
                if (clampedL > 32767.0f) clampedL = 32767.0f;
                else if (clampedL < -32768.0f) clampedL = -32768.0f;

                if (clampedR > 32767.0f) clampedR = 32767.0f;
                else if (clampedR < -32768.0f) clampedR = -32768.0f;

                buffer[idx] = (int16_t)clampedL;
                buffer[idx + 1] = (int16_t)clampedR;
            }
        } else if (channels == 1) {
            for (int i = 0; i < numFrames; ++i) {
                float sample = (float)buffer[i] * invScale;
                float dummyR = sample;
                eq.process(sample, dummyR);
                bassBoost.process(sample, dummyR);
                limiter.process(sample, dummyR);
                float clamped = sample * scale;
                if (clamped > 32767.0f) clamped = 32767.0f;
                else if (clamped < -32768.0f) clamped = -32768.0f;
                buffer[i] = (int16_t)clamped;
            }
        }
    }

private:
    void resetInternal() {
        eq.reset();
        bassBoost.reset();
        widener.reset();
        reverb.reset();
        limiter.reset();
    }

    void updateSampleRate() {
        float sr = (float)sampleRate;
        eq.setSampleRate(sr);
        bassBoost.setSampleRate(sr);
        reverb.setSampleRate(sr);
    }

    int sampleRate;
    int channels;
    std::recursive_mutex dspMutex;

    Equalizer eq;
    BassBoost bassBoost;
    StereoWidener widener;
    ReverbEffect reverb;
    SoftLimiter limiter;
};

