#pragma once
#include "dsp.h"
#include <numeric>
#include <atomic>

constexpr double PI_D = 3.14159265358979323846;

// ============================================================================ RatResampler
/** Resampler rasional polyphase (windowed-sinc). out = in * L/M. Streaming, tanpa alokasi di jalur audio (reserve di init). */
struct RatResampler {
    int L = 1, M = 1, taps = 32, half = 16;
    bool bypass = true;
    std::vector<float> tab, q;
    int64_t base = 0, j = 0;

    void init(double inRate, double outRate, int tapsPerPhase = 32) {
        taps = tapsPerPhase & ~1; half = taps / 2;
        long a = std::lround(inRate), b = std::lround(outRate);
        if (a <= 0 || b <= 0 || a == b) { bypass = true; L = M = 1; tab.clear(); q.clear(); return; }
        long g = std::gcd(a, b), m = a / g, l = b / g;
        if (l > 2048) { l = 1024; m = std::max(1L, std::lround(inRate * l / outRate)); }
        L = (int)l; M = (int)m; bypass = false;
        const double fc = 0.5 * std::min(1.0, (double)L / M) * 0.94;
        tab.assign((size_t)L * taps, 0.f);
        for (int p = 0; p < L; p++) {
            const double frac = (double)p / L; double sum = 0; float* t = &tab[(size_t)p * taps];
            for (int k = 0; k < taps; k++) {
                const double u = frac + half - 1 - k, x = u / half;
                const double s = std::fabs(u) < 1e-9 ? 2 * fc : std::sin(2 * PI_D * fc * u) / (PI_D * u);
                const double w = std::fabs(x) >= 1 ? 0.0 : 0.42 + 0.5 * std::cos(PI_D * x) + 0.08 * std::cos(2 * PI_D * x);
                t[k] = (float)(s * w); sum += s * w;
            }
            for (int k = 0; k < taps; k++) t[k] = (float)(t[k] / sum);
        }
        q.reserve(16384); reset();
    }
    void reset() { if (bypass) return; q.assign(half, 0.f); base = -half; j = 0; }

    /** Mengembalikan jumlah sampel output (≤ maxOut). */
    int process(const float* in, int n, float* out, int maxOut) {
        if (bypass) { int c = std::min(n, maxOut); std::memmove(out, in, (size_t)c * sizeof(float)); return c; }
        q.insert(q.end(), in, in + n);
        int c = 0; const int64_t qEnd = base + (int64_t)q.size();
        while (c < maxOut) {
            const int64_t num = j * M, i0 = num / L; const int p = (int)(num - i0 * L);
            if (i0 + half >= qEnd) break;
            const float* t = &tab[(size_t)p * taps]; const float* s = &q[(size_t)(i0 - half + 1 - base)];
            float acc = 0; for (int k = 0; k < taps; k++) acc += t[k] * s[k];
            out[c++] = acc; j++;
        }
        const int64_t keep = (j * M) / L - half + 1, drop = keep - base;
        if (drop > 0) { q.erase(q.begin(), q.begin() + (ptrdiff_t)drop); base += drop; }
        return c;
    }
};

// ============================================================================ Konvolusi partisi nol-latency (head langsung + ekor FFT)
struct ConvIR {
    static constexpr int B = 128, MAXP = 64;
    int len = 0, parts = 0; std::vector<float> head, spec;
    static ConvIR* build(const float* h, int n) {
        auto* r = new ConvIR(); n = std::min(n, B * (MAXP + 1)); r->len = n;
        r->head.assign(B, 0.f); for (int i = 0; i < std::min(n, B); i++) r->head[i] = h[i];
        r->parts = n > B ? (n - B + B - 1) / B : 0;
        r->spec.assign((size_t)r->parts * (B + 1) * 2, 0.f);
        FFT fft(2 * B); std::vector<std::complex<float>> a(2 * B);
        for (int k = 0; k < r->parts; k++) {
            std::fill(a.begin(), a.end(), std::complex<float>(0.f, 0.f));
            for (int i = 0; i < B; i++) { int idx = B + k * B + i; if (idx < n) a[i] = h[idx]; }
            fft.run(a);
            float* s = &r->spec[(size_t)k * (B + 1) * 2];
            for (int m = 0; m <= B; m++) { s[2 * m] = a[m].real(); s[2 * m + 1] = a[m].imag(); }
        }
        return r;
    }
};

struct ConvState {
    static constexpr int B = ConvIR::B, MAXP = ConvIR::MAXP, SL = (B + 1) * 2;
    std::vector<float> xh, fdl, tail, inBlk, prev, acc;
    std::vector<std::complex<float>> a; FFT fft{2 * B};
    int hpos = 0, pos = 0, fdlHead = 0;
    ConvState() { xh.assign(2 * B, 0.f); fdl.assign((size_t)MAXP * SL, 0.f); tail.assign(B, 0.f); inBlk.assign(B, 0.f); prev.assign(B, 0.f); acc.assign(SL, 0.f); a.assign(2 * B, {0.f, 0.f}); }
    void reset() {
        std::fill(xh.begin(), xh.end(), 0.f); std::fill(fdl.begin(), fdl.end(), 0.f); std::fill(tail.begin(), tail.end(), 0.f);
        std::fill(inBlk.begin(), inBlk.end(), 0.f); std::fill(prev.begin(), prev.end(), 0.f); hpos = pos = fdlHead = 0;
    }
    void block(const ConvIR* ir) {
        for (int i = 0; i < B; i++) { a[i] = {prev[i], 0.f}; a[B + i] = {inBlk[i], 0.f}; }
        fft.run(a);
        float* slot = &fdl[(size_t)fdlHead * SL];
        for (int m = 0; m <= B; m++) { slot[2 * m] = a[m].real(); slot[2 * m + 1] = a[m].imag(); }
        std::memcpy(prev.data(), inBlk.data(), B * sizeof(float));
        if (!ir || ir->parts == 0) { std::fill(tail.begin(), tail.end(), 0.f); fdlHead = (fdlHead + 1) % MAXP; return; }
        std::fill(acc.begin(), acc.end(), 0.f);
        for (int k = 0; k < ir->parts; k++) {
            const float* s = &ir->spec[(size_t)k * SL];
            const float* x = &fdl[(size_t)((fdlHead - k + MAXP) % MAXP) * SL];
            for (int m = 0; m <= B; m++) {
                const float sr = s[2 * m], si = s[2 * m + 1], xr = x[2 * m], xi = x[2 * m + 1];
                acc[2 * m] += sr * xr - si * xi; acc[2 * m + 1] += sr * xi + si * xr;
            }
        }
        for (int m = 0; m <= B; m++) a[m] = {acc[2 * m], -acc[2 * m + 1]};
        for (int m = 1; m < B; m++) a[2 * B - m] = {acc[2 * m], acc[2 * m + 1]};
        fft.run(a);
        const float sc = 1.f / (2 * B);
        for (int i = 0; i < B; i++) tail[i] = a[B + i].real() * sc;
        fdlHead = (fdlHead + 1) % MAXP;
    }
    /** In-place; hasil = konvolusi penuh (tanpa mix). Tanpa latency tambahan. */
    void process(float* x, int n, const ConvIR* ir) {
        const float* h = ir ? ir->head.data() : nullptr;
        for (int i = 0; i < n; i++) {
            const float in = x[i];
            hpos = hpos == 0 ? B - 1 : hpos - 1; xh[hpos] = xh[hpos + B] = in;
            float acc0 = tail[pos];
            if (h) { const float* xp = &xh[hpos]; for (int k = 0; k < B; k++) acc0 += h[k] * xp[k]; }
            x[i] = acc0; inBlk[pos] = in;
            if (++pos == B) { block(ir); pos = 0; }
        }
    }
};

// ============================================================================ Oversampler 2x (FIR 31 tap)
struct Over2 {
    static constexpr int N = 63, HU = 32, HD = 62;
    float h[N]; std::vector<float> ext, ext2, hu, hd, up;
    void init(int maxIn) {
        double s = 0;
        for (int n = 0; n < N; n++) {
            const int m = n - (N - 1) / 2; const double v = m == 0 ? 0.5 : std::sin(2 * PI_D * 0.25 * m) / (PI_D * m);
            const double ph = 2 * PI_D * n / (N - 1);   // Blackman-Harris 4 suku
            const double w = 0.35875 - 0.48829 * std::cos(ph) + 0.14128 * std::cos(2 * ph) - 0.01168 * std::cos(3 * ph);
            h[n] = (float)(v * w); s += v * w;
        }
        for (int n = 0; n < N; n++) h[n] = (float)(h[n] / s);
        ext.assign(HU + maxIn, 0.f); hu.assign(HU, 0.f); hd.assign(HD, 0.f); ext2.assign(HD + 2 * maxIn, 0.f); up.assign(2 * maxIn, 0.f);
    }
    void reset() { std::fill(hu.begin(), hu.end(), 0.f); std::fill(hd.begin(), hd.end(), 0.f); }
    /** x[n] -> up[2n] (gain 2 pada polyphase) */
    float* upsample(const float* x, int n) {
        std::memcpy(ext.data(), hu.data(), HU * sizeof(float)); std::memcpy(ext.data() + HU, x, n * sizeof(float));
        for (int k = 0; k < n; k++) {
            float y0 = 0, y1 = 0; const float* e = &ext[HU + k];
            for (int j = 0; j < HU; j++) y0 += h[2 * j] * e[-j];
            for (int j = 0; j < HU - 1; j++) y1 += h[2 * j + 1] * e[-j];
            up[2 * k] = 2.f * y0; up[2 * k + 1] = 2.f * y1;
        }
        std::memcpy(hu.data(), ext.data() + n, HU * sizeof(float));
        return up.data();
    }
    /** u[2n] -> x[n] */
    void downsample(const float* u, int n, float* x) {
        std::memcpy(ext2.data(), hd.data(), HD * sizeof(float)); std::memcpy(ext2.data() + HD, u, 2 * n * sizeof(float));
        for (int k = 0; k < n; k++) {
            float y = 0; const float* e = &ext2[HD + 2 * k];
            for (int t = 0; t < N; t++) y += h[t] * e[-t];
            x[k] = y;
        }
        std::memcpy(hd.data(), ext2.data() + 2 * n, HD * sizeof(float));
    }
};

// ============================================================================ Drive (overdrive) 2x oversampled
struct Drive {
    float fs = 48000.f, lpHp = 0, lpPre = 0, lpTone = 0, lpSh = 0, dc = 0; Over2 os;
    static float coef(float fc, float f) { return 1.f - std::exp(-2.f * PI_F * std::min(fc, f * 0.45f) / f); }
    void init(float f) { fs = f; os.init(1024); reset(); }
    void reset() { lpHp = lpPre = lpTone = lpSh = dc = 0; os.reset(); }
    void process(float* x, int n, int type, float gain, float tone, float level) {
        float* u = os.upsample(x, n);
        core(u, 2 * n, fs * 2.f, type, gain, tone, level);
        os.downsample(u, n, x);
    }
    void core(float* x, int n, float f, int type, float gain, float tone, float level) {
        gain = std::clamp(gain, 0.f, 1.f); tone = std::clamp(tone, 0.f, 1.f);
        if (type == 0) {   // Screamer
            const float aHp = coef(720.f, f), aPre = coef(5200.f, f), aTn = coef(700.f * std::pow(10.f, tone), f), G = 1.f + 60.f * gain * gain;
            for (int i = 0; i < n; i++) {
                const float in = x[i];
                lpHp += aHp * (in - lpHp);
                lpPre += aPre * (in + G * (in - lpHp) - lpPre);
                lpTone += aTn * (std::tanh(lpPre) - lpTone);
                x[i] = std::clamp(lpTone * 0.45f * level, -2.f, 2.f);
            }
        } else if (type == 1) {   // Precision
            const float aHp = coef(240.f, f), aPre = coef(6500.f, f), aTn = coef(1200.f * std::pow(10.f, tone * 0.9f), f), G = 1.f + 220.f * gain * gain;
            for (int i = 0; i < n; i++) {
                const float in = x[i];
                lpHp += aHp * (in - lpHp);
                lpPre += aPre * (G * (in - lpHp) - lpPre);
                const float y = std::tanh(1.5f * std::tanh(2.2f * lpPre));
                lpTone += aTn * (y - lpTone);
                x[i] = std::clamp(lpTone * 0.4f * level, -2.f, 2.f);
            }
        } else if (type == 3) {   // Overdrive: warm tube-style asymmetric soft clipping, bass kept
            const float aHp = coef(110.f, f), aPre = coef(8000.f, f), aTn = coef(1800.f * std::pow(10.f, tone * 0.85f), f), aDc = coef(18.f, f), G = 1.f + 28.f * gain * gain;
            for (int i = 0; i < n; i++) {
                const float in = x[i];
                lpHp += aHp * (in - lpHp);
                lpPre += aPre * (G * (in - lpHp) - lpPre);
                float y = lpPre >= 0.f ? std::tanh(lpPre) : 0.8f * std::tanh(lpPre / 0.8f * 1.25f);
                dc += aDc * (y - dc); y -= dc;
                lpTone += aTn * (y - lpTone);
                x[i] = std::clamp(lpTone * 0.5f * level, -2.f, 2.f);
            }
        } else if (type >= 4) {   // Fuzz: very high gain, asymmetric hard clipping, sustaining and gritty
            const float aHp = coef(140.f, f), aPre = coef(7500.f, f), aTn = coef(900.f * std::pow(10.f, tone * 1.1f), f), aDc = coef(20.f, f), G = 1.f + 300.f * gain * gain + 12.f * gain;
            for (int i = 0; i < n; i++) {
                const float in = x[i];
                lpHp += aHp * (in - lpHp);
                lpPre += aPre * (G * (in - lpHp) - lpPre);
                const float a = std::tanh(3.f * lpPre);
                float y = a >= 0.f ? a : std::max(-0.62f, 1.35f * a);
                y = std::tanh(1.8f * y);
                dc += aDc * (y - dc); y -= dc;
                lpTone += aTn * (y - lpTone);
                x[i] = std::clamp(lpTone * 0.38f * level, -2.f, 2.f);
            }
        } else {   // Centaur
            const float aHp = coef(300.f, f), aPre = coef(9000.f, f), aSh = coef(1500.f, f), aDc = coef(15.f, f);
            const float G = 1.f + 35.f * gain * gain, cleanW = 1.f - 0.6f * gain, driveW = 0.32f * (0.4f + 0.6f * gain), tg = 0.35f + 1.3f * tone;
            for (int i = 0; i < n; i++) {
                const float in = x[i];
                lpHp += aHp * (in - lpHp);
                lpPre += aPre * (G * (in - lpHp) - lpPre);
                float y = lpPre >= 0.f ? std::tanh(lpPre) : 0.85f * std::tanh(lpPre / 0.85f);
                dc += aDc * (y - dc); y -= dc;
                const float mix = in * cleanW + y * driveW;
                lpSh += aSh * (mix - lpSh);
                x[i] = std::clamp((lpSh + (mix - lpSh) * tg) * level, -2.f, 2.f);
            }
        }
    }
};

// ============================================================================ Wah (SVF TPT, pedal / auto)
struct Wah {
    float ic1 = 0, ic2 = 0, env = 0, posS = 0.5f;
    void reset() { ic1 = ic2 = env = 0; }
    void process(float* x, int n, float fs, int mode, float pos, float q, float sens) {
        const float aAtt = 1.f - std::exp(-1.f / (0.004f * fs)), aRel = 1.f - std::exp(-1.f / (0.09f * fs)), aPos = 1.f - std::exp(-1.f / (0.006f * fs));
        const float k = 1.f / std::clamp(q, 1.f, 12.f), fLo = 350.f, ratio = 2200.f / 350.f, lg = std::log2(ratio);
        for (int i = 0; i < n; i++) {
            const float in = x[i], a = std::fabs(in);
            env += (a > env ? aAtt : aRel) * (a - env);
            const float target = mode == 1 ? std::clamp(env * sens * 14.f, 0.f, 1.f) : pos;
            posS += aPos * (target - posS);
            const float fc = fLo * fastPow2(posS * lg);
            const float g = std::tan(PI_F * std::min(fc, fs * 0.45f) / fs);
            const float a1 = 1.f / (1.f + g * (g + k)), a2 = g * a1, a3 = g * a2;
            const float v3 = in - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2.f * v1 - ic1; ic2 = 2.f * v2 - ic2;
            x[i] = std::clamp(2.2f * k * v1 * std::sqrt(q) * 0.6f + 0.12f * in, -4.f, 4.f);
        }
    }
};

// ============================================================================ Octaver (flip-flop analog style)
struct Octaver {
    float lp1 = 0, lp2 = 0, env = 0, sq = 1, dlp = 0, ulp = 0, dcx = 0; bool hi = false;
    void reset() { lp1 = lp2 = env = dlp = ulp = dcx = 0; sq = 1; hi = false; }
    void process(float* x, int n, float fs, float down, float upL, float dry) {
        const float a1 = 1.f - std::exp(-2.f * PI_F * 650.f / fs), aA = 1.f - std::exp(-1.f / (0.001f * fs)), aR = 1.f - std::exp(-1.f / (0.04f * fs));
        const float aD = 1.f - std::exp(-2.f * PI_F * 450.f / fs), aU = 1.f - std::exp(-2.f * PI_F * 3500.f / fs), aDC = 1.f - std::exp(-2.f * PI_F * 60.f / fs);
        for (int i = 0; i < n; i++) {
            const float in = x[i];
            lp1 += a1 * (in - lp1); lp2 += a1 * (lp1 - lp2);
            const float a = std::fabs(lp2); env += (a > env ? aA : aR) * (a - env);
            const float th = 0.25f * env + 0.0015f;
            if (!hi && lp2 > th) { hi = true; sq = -sq; } else if (hi && lp2 < -th) hi = false;
            const float gate = env > 0.004f ? 1.f : 0.f;
            dlp += aD * (sq * env * 1.7f * gate - dlp);
            const float r = std::fabs(in); dcx += aDC * (r - dcx);
            ulp += aU * ((r - dcx) * 1.6f - ulp);
            x[i] = in * dry + dlp * down + ulp * upL;
        }
    }
};

// ============================================================================ Harmoni diatonik
inline int floorDivI(int a, int b) { int q = a / b; if ((a % b != 0) && ((a < 0) != (b < 0))) q--; return q; }
/** Offset semitone dari not (MIDI) ke not target pada skala (key 0..11, minor 0/1), steps = langkah diatonik (+2 = terts naik, dst). */
inline int harmOffset(int midiNote, int key, int minor, int steps) {
    static const int MAJ[7] = {0, 2, 4, 5, 7, 9, 11}, MIN[7] = {0, 2, 3, 5, 7, 8, 10};
    const int* sc = minor ? MIN : MAJ;
    const int rel = midiNote - key, pc = ((rel % 12) + 12) % 12;
    int best = 0, bd = 99;
    for (int d = 0; d < 7; d++) { int dist = std::abs(pc - sc[d]); if (dist < bd) { bd = dist; best = d; } }
    const int tgt = best + steps, toct = floorDivI(tgt, 7), td = ((tgt % 7) + 7) % 7;
    return sc[td] + 12 * toct - pc;
}

// ============================================================================ Delay (digital / tape) + ducking, ekor tetap berbunyi saat modul off
struct DelayFx {
    DelayLine line; float lp = 0, hp = 0, env = 0, dlyTime = 0, ph = 0, rndS = 0, rndT = 0, rndG = 0, tailEnv = 0; uint32_t seed = 12345;
    void init(float fs) { line.init((int)(fs * 2.2f)); reset(); }
    void reset() { std::fill(line.buf.begin(), line.buf.end(), 0.f); lp = hp = env = tailEnv = rndS = rndG = 0; ph = 0; rndT = 0; }
    inline float rnd() { seed = seed * 1664525u + 1013904223u; return ((seed >> 9) * (1.f / 4194304.f)) - 1.f; }
    /** x di tempat: x += wet. target = waktu delay (sampel). type 0 digital, 1 tape. */
    void process(float* x, int n, float fs, bool on, int type, float target, float fb, float mix, float toneHz, float duck) {
        const float lpC = std::exp(-2 * PI_F * toneHz / fs), hpC = 1.f - std::exp(-2 * PI_F * 140.f / fs);
        const float aA = 1.f - std::exp(-1.f / (0.005f * fs)), aR = 1.f - std::exp(-1.f / (0.25f * fs));
        const float maxT = (float)line.buf.size() - 8.f;
        for (int i = 0; i < n; i++) {
            if (!on && tailEnv < 1e-5f) { line.push(0.f); continue; }
            const float dry = x[i], in = on ? dry : 0.f;
            dlyTime += (target - dlyTime) * 0.001f;
            float t = dlyTime;
            if (type == 1) {
                ph += 0.55f / fs; if (ph >= 1.f) ph -= 1.f;
                if (--rndT <= 0) { rndG = rnd(); rndT = fs * 0.03f; }
                rndS += (rndG - rndS) * 0.002f;
                t *= 1.f + std::sin(2 * PI_F * ph) * 0.0022f + rndS * 0.0009f;
            }
            const float w = line.read(std::clamp(t, 2.f, maxT));
            lp = w * (1.f - lpC) + lp * lpC;
            float f = lp;
            if (type == 1) { hp += hpC * (f - hp); f -= hp; f = std::tanh(f * 1.4f) * (1.f / 1.4f); }
            line.push(in + f * fb);
            const float a = std::fabs(dry); env += (a > env ? aA : aR) * (a - env);
            const float g = 1.f - duck * std::min(1.f, env * 6.f);
            x[i] = dry + w * mix * g;
            tailEnv = std::max(std::fabs(w), tailEnv * 0.9997f);
        }
    }
};

// ============================================================================ Reverb + shimmer, ekor tetap berbunyi saat modul off
struct ReverbFx {
    Reverb rev; PitchShifter shim; float shimFeed = 0, tailEnv = 0;
    void init(float fs) { rev.init(fs); shim.init(fs); shimFeed = tailEnv = 0; }
    void process(float x, bool on, float size, float damp, float mix, int type, float shimAmt, float& l, float& r) {
        if (!on && tailEnv < 1e-6f) { l = r = x; return; }
        float in = on ? x : 0.f;
        if (type == 1) in += shimFeed;
        float o0, o1; rev.wet(in, size, damp, o0, o1);
        if (type == 1) {
            float s = (o0 + o1) * 0.5f; shim.process(&s, 1, 12.f, 1.f);
            shimFeed = std::clamp(s * shimAmt * 0.15f, -0.4f, 0.4f);   // loop-gain < 1: ekor shimmer meluruh, tidak meledak
        } else shimFeed = 0.f;
        const float dg = on ? (1.f - mix * 0.6f) : 1.f;
        l = x * dg + o0 * mix * 3.f; r = x * dg + o1 * mix * 3.f;
        tailEnv = std::max(std::max(std::fabs(o0), std::fabs(o1)), tailEnv * 0.99995f);
    }
};
