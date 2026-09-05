#pragma once
#include <cmath>
#include <algorithm>

// Soft Limiter: 快速平滑软压限，防止 EQ、BassBoost 与 Reverb 叠加后的削顶失真 (Clipping)
class SoftLimiter {
public:
    SoftLimiter() : threshold(0.95f) {}

    void setThreshold(float t) {
        threshold = t;
    }

    void reset() {}

    inline float limit(float x) {
        // 使用 tanh 软饱和曲线保持自然过渡，绝不出现方波硬切削爆音
        if (x > threshold) {
            return threshold + (1.0f - threshold) * tanhf((x - threshold) / (1.0f - threshold));
        } else if (x < -threshold) {
            return -threshold + (1.0f - threshold) * tanhf((x + threshold) / (1.0f - threshold));
        }
        return x;
    }

    inline void process(float &left, float &right) {
        left = limit(left);
        right = limit(right);
    }

private:
    float threshold;
};
