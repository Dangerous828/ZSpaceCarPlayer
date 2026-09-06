#pragma once
#include <vector>
#include <cstring>
#include <algorithm>

// Freeverb (Jezar at Dreampoint 经典算法 - 4 Allpass + 8 Comb 滤波器)
class CombFilter {
public:
    CombFilter() : buffer(nullptr), bufSize(0), bufIdx(0), feedback(0.0f), filterStore(0.0f), damp(0.0f) {}
    ~CombFilter() { delete[] buffer; }

    void setBufferSize(int size) {
        delete[] buffer;
        bufSize = size;
        buffer = new float[bufSize];
        mute();
    }

    void mute() {
        if (buffer) memset(buffer, 0, bufSize * sizeof(float));
        filterStore = 0.0f;
        bufIdx = 0;
    }

    void setFeedback(float val) { feedback = val; }
    void setDamp(float val) { damp = val; }

    inline float process(float input) {
        if (!buffer || bufSize == 0) return input;
        float output = buffer[bufIdx];
        filterStore = (output * (1.0f - damp)) + (filterStore * damp);
        buffer[bufIdx] = input + (filterStore * feedback);
        if (++bufIdx >= bufSize) bufIdx = 0;
        return output;
    }

private:
    float *buffer;
    int bufSize;
    int bufIdx;
    float feedback;
    float filterStore;
    float damp;
};

class AllpassFilter {
public:
    AllpassFilter() : buffer(nullptr), bufSize(0), bufIdx(0), feedback(0.5f) {}
    ~AllpassFilter() { delete[] buffer; }

    void setBufferSize(int size) {
        delete[] buffer;
        bufSize = size;
        buffer = new float[bufSize];
        mute();
    }

    void mute() {
        if (buffer) memset(buffer, 0, bufSize * sizeof(float));
        bufIdx = 0;
    }

    void setFeedback(float val) { feedback = val; }

    inline float process(float input) {
        if (!buffer || bufSize == 0) return input;
        float bufOut = buffer[bufIdx];
        float output = -input + bufOut;
        buffer[bufIdx] = input + (bufOut * feedback);
        if (++bufIdx >= bufSize) bufIdx = 0;
        return output;
    }

private:
    float *buffer;
    int bufSize;
    int bufIdx;
    float feedback;
};

class ReverbEffect {
public:
    enum Mode {
        MODE_OFF = 0,
        MODE_ROOM = 1,
        MODE_HALL = 2,
        MODE_THEATER = 3
    };

    ReverbEffect() : sampleRate(44100.0f), currentMode(MODE_OFF), wet(0.0f), dry(1.0f) {
        initTuning();
    }

    void setSampleRate(float sr) {
        sampleRate = sr;
        initTuning();
    }

    void setMode(int mode) {
        currentMode = (Mode)mode;
        switch (currentMode) {
            case MODE_ROOM:
                setRoomSize(0.5f);
                setDamp(0.5f);
                setWet(0.45f);
                setDry(0.9f);
                break;
            case MODE_HALL:
                setRoomSize(0.75f);
                setDamp(0.35f);
                setWet(0.62f);
                setDry(0.85f);
                break;
            case MODE_THEATER:
                setRoomSize(0.88f);
                setDamp(0.25f);
                setWet(0.78f);
                setDry(0.80f);
                break;
            case MODE_OFF:
            default:
                setWet(0.0f);
                setDry(1.0f);
                break;
        }
    }

    int getMode() const {
        return (int)currentMode;
    }

    void reset() {
        for (int i = 0; i < 8; ++i) {
            combL[i].mute();
            combR[i].mute();
        }
        for (int i = 0; i < 4; ++i) {
            allpassL[i].mute();
            allpassR[i].mute();
        }
    }

    inline void process(float &left, float &right) {
        if (currentMode == MODE_OFF || wet <= 0.001f) return;

        float inL = left;
        float inR = right;
        // 0.015 -> 0.022: 车载实测混响感知偏弱, 提高激励; 输出峰值由 SoftLimiter 兜底
        float input = (inL + inR) * 0.022f; // Scale down for headroom

        float outL = 0.0f;
        float outR = 0.0f;

        // Comb filters in parallel
        for (int i = 0; i < 8; ++i) {
            outL += combL[i].process(input);
            outR += combR[i].process(input);
        }

        // Allpass filters in series
        for (int i = 0; i < 4; ++i) {
            outL = allpassL[i].process(outL);
            outR = allpassR[i].process(outR);
        }

        left = inL * dry + outL * wet;
        right = inR * dry + outR * wet;
    }

private:
    void initTuning() {
        float srScale = sampleRate / 44100.0f;
        const int combTuningL[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        const int combTuningR[8] = {1116 + 23, 1188 + 23, 1277 + 23, 1356 + 23, 1422 + 23, 1491 + 23, 1557 + 23, 1617 + 23};
        const int allpassTuningL[4] = {556, 441, 341, 225};
        const int allpassTuningR[4] = {556 + 23, 441 + 23, 341 + 23, 225 + 23};

        for (int i = 0; i < 8; ++i) {
            combL[i].setBufferSize((int)(combTuningL[i] * srScale));
            combR[i].setBufferSize((int)(combTuningR[i] * srScale));
        }
        for (int i = 0; i < 4; ++i) {
            allpassL[i].setBufferSize((int)(allpassTuningL[i] * srScale));
            allpassR[i].setBufferSize((int)(allpassTuningR[i] * srScale));
            allpassL[i].setFeedback(0.5f);
            allpassR[i].setFeedback(0.5f);
        }
        setMode(currentMode);
    }

    void setRoomSize(float value) {
        roomSize = value;
        for (int i = 0; i < 8; ++i) {
            combL[i].setFeedback(roomSize);
            combR[i].setFeedback(roomSize);
        }
    }

    void setDamp(float value) {
        damp = value;
        for (int i = 0; i < 8; ++i) {
            combL[i].setDamp(damp);
            combR[i].setDamp(damp);
        }
    }

    void setWet(float value) { wet = value; }
    void setDry(float value) { dry = value; }

    float sampleRate;
    Mode currentMode;
    float roomSize;
    float damp;
    float wet;
    float dry;

    CombFilter combL[8];
    CombFilter combR[8];
    AllpassFilter allpassL[4];
    AllpassFilter allpassR[4];
};
