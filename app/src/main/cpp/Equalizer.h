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
        // 预设对照表:
        // 0: 普通 (Normal/Flat)
        // 1: 古典 (Classical)
        // 2: 流行 (Pop)
        // 3: 摇滚 (Rock)
        // 4: 人声 (Vocal)
        // 5: 爵士 (Jazz)
        // 6: 舞曲 (Dance)
        switch (preset) {
            case 0: // Flat
                setAllGains(0, 0, 0, 0, 0);
                break;
            case 1: // Classical
                setAllGains(4, 3, -2, 2, 4);
                break;
            case 2: // Pop
                setAllGains(-1, 2, 4, 1, -2);
                break;
            case 3: // Rock
                setAllGains(5, 3, -1, 3, 5);
                break;
            case 4: // Vocal
                setAllGains(-2, 1, 4, 3, 0);
                break;
            case 5: // Jazz
                setAllGains(3, 2, 1, 2, 3);
                break;
            case 6: // Dance
                setAllGains(6, 4, 1, 3, 2);
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
