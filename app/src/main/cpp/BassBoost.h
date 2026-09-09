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
        // 0% -> 0dB, 100% -> +12dB。
        // 2026-09-09 实车反馈「低音太重压人声」: 旧曲线 LOWSHELF@100Hz 的增益会
        // 一直延伸到 150~250Hz, 与人声基频段 (男声 85~180Hz, 女声 165~255Hz) 正面
        // 相撞, 听感就是轰头+人声浑浊。改为 PEAKING@55Hz/Q1.4 (数值验算定稿,
        // scripts/verify_bass_curve.mjs):
        //   60Hz +11.1dB  低音打击感本体 (kick/贝斯下盘) 基本无损
        //   30Hz ~+3dB    次声/隆隆底噪不再无差别推高 (旧 +13.8dB), 省余量给 limiter
        //   150~255Hz     人声基频段最大残留 +1.25dB (旧 +2.82dB), 解耦完成
        float gainDb = (strengthPercent / 100.0f) * 12.0f;
        filterL.configure(BiquadFilter::PEAKING, sampleRate, 55.0f, 1.4f, gainDb);
        filterR.configure(BiquadFilter::PEAKING, sampleRate, 55.0f, 1.4f, gainDb);
    }

    float sampleRate;
    int strengthPercent;
    BiquadFilter filterL;
    BiquadFilter filterR;
};
