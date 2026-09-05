#pragma once
#include "BiquadFilter.h"
#include <cmath>

class BassBoost {
public:
    BassBoost() : sampleRate(44100.0f), strengthPercent(0) {
        updateFilters();
    }

    void setSampleRate(float sr) {
        if (sr <= 0.0f) sr = 44100.0f;
        sampleRate = sr;
        updateFilters();
    }

    void setStrength(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        strengthPercent = percent;
        updateFilters();
    }

    int getStrength() const {
        return strengthPercent;
    }

    void reset() {
        filterL.reset();
        filterR.reset();
    }

    inline void process(float &left, float &right) {
        if (strengthPercent <= 0) return;
        left = filterL.process(left);
        right = filterR.process(right);
    }

private:
    void updateFilters() {
        if (strengthPercent <= 0) {
            filterL.reset();
            filterR.reset();
            return;
        }
        // 0% -> 0dB, 100% -> +12dB 低频增益 (LowShelf at 120Hz)
        float gainDb = (strengthPercent / 100.0f) * 12.0f;
        filterL.configure(BiquadFilter::LOWSHELF, sampleRate, 120.0f, 0.707f, gainDb);
        filterR.configure(BiquadFilter::LOWSHELF, sampleRate, 120.0f, 0.707f, gainDb);
    }

    float sampleRate;
    int strengthPercent;
    BiquadFilter filterL;
    BiquadFilter filterR;
};
