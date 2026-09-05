#pragma once
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// RBJ Audio EQ Cookbook Biquad Filter Implementation
class BiquadFilter {
public:
    enum Type {
        LOWPASS = 0,
        HIGHPASS,
        BANDPASS,
        PEAKING,
        LOWSHELF,
        HIGHSHELF
    };

    BiquadFilter() {
        reset();
    }

    void reset() {
        b0 = 1.0f; b1 = 0.0f; b2 = 0.0f;
        a1 = 0.0f; a2 = 0.0f;
        x1 = 0.0f; x2 = 0.0f;
        y1 = 0.0f; y2 = 0.0f;
    }

    void configure(Type type, float sampleRate, float frequency, float q, float gainDb = 0.0f) {
        if (sampleRate <= 0.0f) sampleRate = 44100.0f;
        if (frequency <= 10.0f) frequency = 10.0f;
        if (frequency >= sampleRate * 0.49f) frequency = sampleRate * 0.49f;
        if (q <= 0.01f) q = 0.01f;

        float omega = 2.0f * (float)M_PI * frequency / sampleRate;
        float sinOmega = sinf(omega);
        float cosOmega = cosf(omega);
        float alpha = sinOmega / (2.0f * q);
        float A = powf(10.0f, gainDb / 40.0f); // sqrt(gainLinear)
        float a0 = 1.0f;

        switch (type) {
            case PEAKING: {
                b0 = 1.0f + alpha * A;
                b1 = -2.0f * cosOmega;
                b2 = 1.0f - alpha * A;
                a0 = 1.0f + alpha / A;
                a1 = -2.0f * cosOmega;
                a2 = 1.0f - alpha / A;
                break;
            }
            case LOWSHELF: {
                float sqrtA = sqrtf(A);
                b0 = A * ((A + 1.0f) - (A - 1.0f) * cosOmega + 2.0f * sqrtA * alpha);
                b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosOmega);
                b2 = A * ((A + 1.0f) - (A - 1.0f) * cosOmega - 2.0f * sqrtA * alpha);
                a0 = (A + 1.0f) + (A - 1.0f) * cosOmega + 2.0f * sqrtA * alpha;
                a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosOmega);
                a2 = (A + 1.0f) + (A - 1.0f) * cosOmega - 2.0f * sqrtA * alpha;
                break;
            }
            case HIGHSHELF: {
                float sqrtA = sqrtf(A);
                b0 = A * ((A + 1.0f) + (A - 1.0f) * cosOmega + 2.0f * sqrtA * alpha);
                b1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosOmega);
                b2 = A * ((A + 1.0f) + (A - 1.0f) * cosOmega - 2.0f * sqrtA * alpha);
                a0 = (A + 1.0f) - (A - 1.0f) * cosOmega + 2.0f * sqrtA * alpha;
                a1 = 2.0f * ((A - 1.0f) - (A + 1.0f) * cosOmega);
                a2 = (A + 1.0f) - (A - 1.0f) * cosOmega - 2.0f * sqrtA * alpha;
                break;
            }
            case LOWPASS: {
                b0 = (1.0f - cosOmega) * 0.5f;
                b1 = 1.0f - cosOmega;
                b2 = (1.0f - cosOmega) * 0.5f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosOmega;
                a2 = 1.0f - alpha;
                break;
            }
            case HIGHPASS: {
                b0 = (1.0f + cosOmega) * 0.5f;
                b1 = -(1.0f + cosOmega);
                b2 = (1.0f + cosOmega) * 0.5f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosOmega;
                a2 = 1.0f - alpha;
                break;
            }
            default:
                b0 = 1.0f; b1 = 0.0f; b2 = 0.0f;
                a0 = 1.0f; a1 = 0.0f; a2 = 0.0f;
                break;
        }

        // Normalize by a0
        float invA0 = 1.0f / a0;
        b0 *= invA0;
        b1 *= invA0;
        b2 *= invA0;
        a1 *= invA0;
        a2 *= invA0;
    }

    inline float process(float in) {
        // Direct Form I
        float out = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = in;
        y2 = y1;
        y1 = out;
        return out;
    }

private:
    float b0, b1, b2, a1, a2;
    float x1, x2, y1, y2;
};
