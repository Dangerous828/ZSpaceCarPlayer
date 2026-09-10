#pragma once
#include "BiquadFilter.h"
#include <vector>

// 5段车载参量均衡器: 60Hz(低沉), 230Hz(浑厚), 910Hz(清晰), 4000Hz(明亮), 14000Hz(空气感)
// 对应 Android 标准 5-Band Equalizer 频点
class Equalizer {
public:
    static const int NUM_BANDS = 5;

    Equalizer() : sampleRate(44100.0f), currentPreset(-1) {
        centerFreqs[0] = 60.0f;
        centerFreqs[1] = 230.0f;
        centerFreqs[2] = 910.0f;
        centerFreqs[3] = 4000.0f;
        centerFreqs[4] = 14000.0f;

        for (int i = 0; i < NUM_BANDS; ++i) {
            bandGains[i] = 0.0f; // dB
        }
        updateFilters();
    }

    void setSampleRate(float sr) {
        if (sr <= 0.0f) sr = 44100.0f;
        sampleRate = sr;
        updateFilters();
    }

    void setBandGain(int band, float gainDb) {
        if (band < 0 || band >= NUM_BANDS) return;
        bandGains[band] = gainDb;
        currentPreset = -1; // 自定义模式
        updateBand(band);
    }

    float getBandGain(int band) const {
        if (band < 0 || band >= NUM_BANDS) return 0.0f;
        return bandGains[band];
    }

    void setPreset(int preset) {
        currentPreset = preset;
        // 预设对照表 (与 Java 层 AudioPlayerService.getEqPresets() / MainActivity fallback 严格一致):
        // 0: 原声 (Flat)              1: 古典 (Classical)
        // 2: 流行 (Pop)               3: 摇滚 (Rock)
        // 4: 人声 (Vocal)             5: 爵士 (Jazz)
        // 6: 舞曲 (Dance)             7: 金属 (Metal)
        // 8: 蓝调 (Blues)             9: 电子 (Electronic)
        // 10: 电音舞曲 (EDM)          11: 嘻哈 (Hip-Hop)
        // 12: 男声 (Male Vocal)       13: 女声 (Female Vocal)
        // 14: 播客对话 (Speech)       15: 车载优化 (Car)
        // 16: 低音增强 (Bass Boost)
        // 索引 4 长期保留给人声, 避免已保存用户偏好错位。
        switch (preset) {
            case 0: // Flat
                setAllGains(0, 0, 0, 0, 0);
                break;
            case 1: // Classical
                setAllGains(-1, 0, 1, 1, 2);
                break;
            case 2: // Pop
                setAllGains(0, 1, 2, 2, 1);
                break;
            case 3: // Rock
                setAllGains(2, 0, 1, 3, 2);
                break;
            case 4: // Vocal
                // 2026-09-09 晚二次调优: 实车反馈人声仍模糊, 进一步:
                //   230Hz 从 -1 压到 -3 (箱声/浑浊主能量带, 去除人声"闷在箱子里")
                //   4kHz 从 +3 提到 +5 (人声咬字/清晰度, 车噪中最先被吃掉的频段)
                //   60Hz 从 -3 压到 -4 (进一步隔绝隆隆底噪对人声的掩蔽)
                //   910Hz 从 +4 降到 +3 (避免鼻音过重, 与中频 clarity 平衡)
                //   14kHz 从 +1 提到 +2 (空气感/齿音, 让人声更通透)
                setAllGains(-4, -3, 3, 5, 2);
                break;
            case 5: // Jazz
                setAllGains(0, 0, 1, 1, 1);
                break;
            case 6: // Dance
                setAllGains(2, -1, 0, 2, 1);
                break;
            case 7: // Metal
                setAllGains(3, -1, 0, 4, 3);
                break;
            case 8: // Blues
                setAllGains(1, 0, 2, 1, 1);
                break;
            case 9: // Electronic
                setAllGains(2, -1, 0, 3, 2);
                break;
            case 10: // EDM
                setAllGains(4, -2, 0, 3, 2);
                break;
            case 11: // Hip-Hop
                setAllGains(3, -2, 1, 2, 1);
                break;
            case 12: // Male Vocal
                setAllGains(-2, -2, 2, 4, 1);
                break;
            case 13: // Female Vocal
                setAllGains(-2, -3, 3, 5, 3);
                break;
            case 14: // Speech
                setAllGains(-3, -2, 4, 5, 1);
                break;
            case 15: // Car (车载优化: 压低频轰鸣, 提中高频穿透车噪)
                setAllGains(-2, -1, 3, 4, 2);
                break;
            case 16: // Bass Boost
                setAllGains(4, 0, 0, 1, 0);
                break;
            default:
                setAllGains(0, 0, 0, 0, 0);
                break;
        }
    }

    int getPreset() const {
        return currentPreset;
    }

    void reset() {
        for (int i = 0; i < NUM_BANDS; ++i) {
            filtersL[i].reset();
            filtersR[i].reset();
        }
    }

    inline void process(float &left, float &right) {
        for (int i = 0; i < NUM_BANDS; ++i) {
            if (fabsf(bandGains[i]) > 0.01f) {
                left = filtersL[i].process(left);
                right = filtersR[i].process(right);
            }
        }
    }

private:
    void setAllGains(float g0, float g1, float g2, float g3, float g4) {
        bandGains[0] = g0;
        bandGains[1] = g1;
        bandGains[2] = g2;
        bandGains[3] = g3;
        bandGains[4] = g4;
        updateFilters();
    }

    void updateBand(int band) {
        filtersL[band].configure(BiquadFilter::PEAKING, sampleRate, centerFreqs[band], 1.414f, bandGains[band]);
        filtersR[band].configure(BiquadFilter::PEAKING, sampleRate, centerFreqs[band], 1.414f, bandGains[band]);
    }

    void updateFilters() {
        for (int i = 0; i < NUM_BANDS; ++i) {
            updateBand(i);
        }
    }

    float sampleRate;
    int currentPreset;
    float centerFreqs[NUM_BANDS];
    float bandGains[NUM_BANDS];
    BiquadFilter filtersL[NUM_BANDS];
    BiquadFilter filtersR[NUM_BANDS];
};
