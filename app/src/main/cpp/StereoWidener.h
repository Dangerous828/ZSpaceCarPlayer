#pragma once
#include <cmath>

// Mid-Side 立体声展宽器: side 增益放大拉开声场 (纯 M/S, 无交叉馈送)
class StereoWidener {
public:
    StereoWidener() : width(1.0f) {}

    void setStrength(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        // 0% -> 1.0 (原声直通), 100% -> 3.2 (全景声场拉满)。
        // 车载听感 2026-09-06: 旧上限 2.2 对中置为主的音源感知太弱, 上调至 3.2;
        // side 过冲由 SoftLimiter 兜底, 不会硬削顶
        width = 1.0f + (percent / 100.0f) * 2.2f;
    }

    void reset() {}

    inline void process(float &left, float &right) {
        if (width <= 1.001f) return;

        // Mid = (L + R) * 0.5, Side = (L - R) * 0.5
        float mid = (left + right) * 0.5f;
        float side = (left - right) * 0.5f;

        // Scale side
        float sideWidened = side * width;

        // Reconstruct
        left = mid + sideWidened;
        right = mid - sideWidened;
    }

private:
    float width;
};
