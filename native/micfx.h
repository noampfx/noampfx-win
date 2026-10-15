#pragma once
// Efek khusus mikrofon (input 2): gate -> compressor -> EQ 3 band (+ low cut) -> delay -> reverb. Global, bukan bagian preset.
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>
#include "dsp.h"
#include "dsp2.h"

enum MicParam {
    MIC_GATE_ON = 0, MIC_GATE_THR,                       // dB
    MIC_COMP_ON, MIC_COMP_THR, MIC_COMP_RATIO, MIC_COMP_MAKEUP,
    MIC_EQ_ON, MIC_EQ_LOW, MIC_EQ_MID, MIC_EQ_HIGH,       // gain dB
    MIC_DLY_ON, MIC_DLY_TIME, MIC_DLY_FB, MIC_DLY_MIX,    // ms, 0..1, 0..1
    MIC_REV_ON, MIC_REV_SIZE, MIC_REV_MIX,                // 0..1
    MIC_SLOT0, MIC_SLOT1, MIC_SLOT2, MIC_SLOT3,           // module in each of the 4 chain slots: 0 empty, 1 gate, 2 comp, 3 eq, 4 delay, 5 reverb
    MIC_PRE,                                              // preamp boost in dB (0..36), applied to the microphone before the effects
    MIC_EQ_LOWF, MIC_EQ_M1F, MIC_EQ_M1Q, MIC_EQ_M2F, MIC_EQ_M2G, MIC_EQ_M2Q, MIC_EQ_HIGHF, MIC_EQ_LC,   // parametric EQ: shelf / band frequencies, Q, 2nd band gain, low cut (Hz)
    MIC_PARAM_COUNT
};

/** Gentle limiter for the boosted mic: transparent below 0.8, rounds the peaks off smoothly towards 1.0 (no harsh digital clipping). */
inline float micSoft(float y) {
    const float a = std::fabs(y);
    if (a <= 0.8f) return y;
    const float s = 0.8f + 0.2f * std::tanh((a - 0.8f) / 0.2f);
    return y < 0.f ? -s : s;
}

struct MicFx {
    Gate gate; Compressor comp; Biquad hp, lo, m1, m2, hi; DelayFx dly; ReverbFx rev;
    std::vector<float> tmp; float last[11] = {1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f}; float lastFs = 0.f; bool hpOn = false;

    /** Dipanggil dari prepare() (bukan thread audio): alokasi buffer delay/reverb. */
    void init(float fs, int maxN) {
        tmp.assign(maxN, 0.f); dly.init(fs); rev.init(fs);
        gate = Gate(); comp = Compressor(); hp.reset(); lo.reset(); m1.reset(); m2.reset(); hi.reset();
        for (float& v : last) v = 1e9f; lastFs = 0.f;
    }

    void runGate(float* x, int n, float fs, const float* p) {
        if (p[MIC_GATE_ON] > 0.5f) {
            const float thr = dbToLin(p[MIC_GATE_THR]), rng = dbToLin(-60.f), aC = onePoleCoef(2.f, fs), rC = onePoleCoef(150.f, fs);
            for (int i = 0; i < n; i++) x[i] = gate.process(x[i], thr, aC, rC, rng);
        } else gate.g = 1.f;
    }
    void runComp(float* x, int n, float fs, const float* p) {
        if (p[MIC_COMP_ON] > 0.5f) {
            const float t = p[MIC_COMP_THR], slope = 1.f / std::max(p[MIC_COMP_RATIO], 1.f) - 1.f, mk = p[MIC_COMP_MAKEUP];
            const float aC = onePoleCoef(10.f, fs), rC = onePoleCoef(120.f, fs);
            for (int i = 0; i < n; i++) x[i] = comp.process(x[i], t, slope, aC, rC, mk, 6.f);
        } else comp.cur = 0.f;
    }
    /** Parametric EQ: low cut, low shelf, two peaking bands (frequency + gain + Q), high shelf. */
    void runEq(float* x, int n, float fs, const float* p) {
        if (p[MIC_EQ_ON] > 0.5f) {
            const float cur[11] = { p[MIC_EQ_LC], p[MIC_EQ_LOWF], p[MIC_EQ_LOW], p[MIC_EQ_M1F], p[MIC_EQ_MID], p[MIC_EQ_M1Q], p[MIC_EQ_M2F], p[MIC_EQ_M2G], p[MIC_EQ_M2Q], p[MIC_EQ_HIGHF], p[MIC_EQ_HIGH] };
            bool changed = fs != lastFs;
            for (int k = 0; k < 11; k++) if (cur[k] != last[k]) changed = true;
            if (changed) {
                const float fmax = fs * 0.45f;
                auto fr = [&](float f) { return std::clamp(f, 20.f, fmax); };
                auto qq = [&](float q) { return std::clamp(q, 0.2f, 10.f); };
                hpOn = cur[0] >= 20.f;
                if (hpOn) hp.set(4, fs, fr(cur[0]), 0.f, 0.707f);                    // low cut (off below 20 Hz)
                lo.set(0, fs, fr(cur[1]), cur[2], 0.7f);                             // low shelf
                m1.set(1, fs, fr(cur[3]), cur[4], qq(cur[5]));                       // band 1
                m2.set(1, fs, fr(cur[6]), cur[7], qq(cur[8]));                       // band 2
                hi.set(2, fs, fr(cur[9]), cur[10], 0.7f);                            // high shelf
                for (int k = 0; k < 11; k++) last[k] = cur[k];
                lastFs = fs;
            }
            for (int i = 0; i < n; i++) { float v = x[i]; if (hpOn) v = hp.process(v); x[i] = hi.process(m2.process(m1.process(lo.process(v)))); }
        } else { hp.reset(); lo.reset(); m1.reset(); m2.reset(); hi.reset(); lastFs = 0.f; }
    }
    void runDelay(float* x, int n, float fs, const float* p) {
        const float target = std::clamp(p[MIC_DLY_TIME] * 0.001f * fs, 2.f, fs * 2.0f);
        dly.process(x, n, fs, p[MIC_DLY_ON] > 0.5f, 0, target, std::clamp(p[MIC_DLY_FB], 0.f, 0.9f), std::clamp(p[MIC_DLY_MIX], 0.f, 1.f), 5000.f, 0.f);
    }

    /** in (mono) -> L/R. Modules run in the order of the 4 chain slots; the reverb (stereo) is always applied last. */
    void process(const float* in, float* L, float* R, int n, float fs, const float* p) {
        float* x = tmp.data();
        std::memcpy(x, in, (size_t)n * sizeof(float));
        bool rv = false;
        for (int s = 0; s < 4; s++) {
            switch ((int)(p[MIC_SLOT0 + s] + 0.5f)) {
                case 1: runGate(x, n, fs, p); break;
                case 2: runComp(x, n, fs, p); break;
                case 3: runEq(x, n, fs, p); break;
                case 4: runDelay(x, n, fs, p); break;
                case 5: rv = true; break;
                default: break;
            }
        }
        if (rv) {
            const bool rOn = p[MIC_REV_ON] > 0.5f; const float sz = std::clamp(p[MIC_REV_SIZE], 0.f, 1.f), mx = std::clamp(p[MIC_REV_MIX], 0.f, 1.f);
            for (int i = 0; i < n; i++) rev.process(x[i], rOn, sz, 0.5f, mx, 0, 0.f, L[i], R[i]);
        } else for (int i = 0; i < n; i++) { L[i] = x[i]; R[i] = x[i]; }
    }
};
