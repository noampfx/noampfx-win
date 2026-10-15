#pragma once
#include <cmath>
#include <vector>
#include <algorithm>
#include <complex>
#include <cstring>
#include <cstdint>

constexpr float PI_F = 3.14159265358979f;
inline float dbToLin(float d) { return std::pow(10.f, d * 0.05f); }
/** Mixer-fader law: range -50..+3 dB, the bottom of the fader (-50 dB) is silence. */
inline float faderLin(float d) { return d <= -49.9f ? 0.f : std::pow(10.f, std::min(d, 3.f) * 0.05f); }
inline float linToDb(float l) { return 20.f * std::log10(std::max(l, 1e-9f)); }
inline float fastLn(float x) {
    uint32_t i; std::memcpy(&i, &x, 4);
    float e = (float)((int)(i >> 23) - 127); i = (i & 0x007FFFFFu) | 0x3F800000u; float m; std::memcpy(&m, &i, 4);
    return e * 0.69314718f + (-1.7417939f + (2.8212026f + (-1.4699568f + (0.44717955f - 0.056570851f * m) * m) * m) * m);
}
inline float fastPow2(float p) {
    p = std::clamp(p, -126.f, 126.f); float fl = std::floor(p), f = p - fl;
    float m = 1.f + f * (0.69606564f + f * (0.22449434f + f * 0.07944023f));
    uint32_t i = (uint32_t)((int)fl + 127) << 23; float s; std::memcpy(&s, &i, 4); return m * s;
}
inline float fastLinToDb(float l) { return 8.6858896f * fastLn(l); }
inline float fastDbToLin(float d) { return fastPow2(d * 0.16609640f); }
inline float onePoleCoef(float ms, float fs) { return std::exp(-1.f / (0.001f * std::max(ms, 0.01f) * fs)); }

struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    inline float process(float x) { float y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
    void reset() { z1 = z2 = 0; }
    void norm(float B0, float B1, float B2, float A0, float A1, float A2) {
        b0 = B0 / A0; b1 = B1 / A0; b2 = B2 / A0; a1 = A1 / A0; a2 = A2 / A0;
    }
    // type: 0 lowshelf, 1 peak, 2 highshelf, 3 lowpass, 4 highpass
    void set(int type, float fs, float f, float gDb, float q) {
        f = std::clamp(f, 10.f, fs * 0.45f); q = std::max(q, 0.1f);
        float A = std::pow(10.f, gDb / 40.f), w = 2 * PI_F * f / fs, c = std::cos(w), s = std::sin(w);
        float al = s / (2 * q), be = 2 * std::sqrt(A) * al;
        switch (type) {
            case 0: norm(A * ((A + 1) - (A - 1) * c + be), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - be),
                         (A + 1) + (A - 1) * c + be, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - be); break;
            case 1: norm(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A); break;
            case 2: norm(A * ((A + 1) + (A - 1) * c + be), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - be),
                         (A + 1) - (A - 1) * c + be, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - be); break;
            case 3: norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al); break;
            default: norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al); break;
        }
    }
    float magDb(float fs, float f) const {
        float w = 2 * PI_F * f / fs;
        std::complex<float> z1_(std::cos(-w), std::sin(-w)), z2_ = z1_ * z1_;
        auto n = b0 + b1 * z1_ + b2 * z2_, d = 1.f + a1 * z1_ + a2 * z2_;
        return linToDb(std::abs(n) / std::max(std::abs(d), 1e-9f));
    }
};

struct FFT {  // radix-2 in-place
    int n; std::vector<std::complex<float>> tw;
    explicit FFT(int N) : n(N), tw(N / 2) { for (int i = 0; i < N / 2; i++) tw[i] = std::polar(1.f, -2 * PI_F * i / N); }
    void run(std::vector<std::complex<float>>& a) const {
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1; for (; j & bit; bit >>= 1) j ^= bit; j ^= bit;
            if (i < j) std::swap(a[i], a[j]);
        }
        for (int len = 2; len <= n; len <<= 1) {
            int step = n / len;
            for (int i = 0; i < n; i += len)
                for (int k = 0; k < len / 2; k++) {
                    auto u = a[i + k], v = a[i + k + len / 2] * tw[k * step];
                    a[i + k] = u + v; a[i + k + len / 2] = u - v;
                }
        }
    }
};

struct Gate {
    float env = 0, g = 1;
    inline float process(float x, float thr, float aC, float rC, float rng) {
        float a = std::fabs(x);
        env = a > env ? a : env * 0.9995f + a * 0.0005f;
        float tgt = env > thr ? 1.f : rng, c = tgt > g ? aC : rC;
        g = c * g + (1 - c) * tgt;
        return x * g;
    }
};

struct Compressor {
    float cur = 0;
    inline float process(float x, float thr, float slope, float aC, float rC, float makeup, float knee) {
        float over = fastLinToDb(std::fabs(x) + 1e-9f) - thr, g;
        if (2 * over < -knee) g = 0;
        else if (2 * std::fabs(over) <= knee) { float t = over + knee * 0.5f; g = slope * t * t / (2 * std::max(knee, 0.01f)); }
        else g = slope * over;
        float c = g < cur ? aC : rC;
        cur = c * cur + (1 - c) * g;
        return x * fastDbToLin(cur + makeup);
    }
};

struct DelayLine {
    std::vector<float> buf; int pos = 0, mask = 0;
    void init(int minSize) { int s = 1; while (s < minSize) s <<= 1; buf.assign(s, 0.f); mask = s - 1; pos = 0; }
    inline void push(float x) { pos = (pos + 1) & mask; buf[pos] = x; }
    inline float read(float d) const {
        float p = pos - d; int i = (int)std::floor(p); float fr = p - i;
        return buf[i & mask] * (1 - fr) + buf[(i + 1) & mask] * fr;
    }
};

struct Modulation {
    DelayLine dl; float ph = 0, fb = 0; float ap[4] = {0, 0, 0, 0}; float apFb = 0; float apA = 0; int cnt = 0;
    void init(float fs) { dl.init((int)(fs * 0.05f) + 8); }
    float process(float x, int type, float rate, float depth, float mix, float fbk, float fs) {
        ph += rate / fs; if (ph >= 1) ph -= 1;
        float lfo = std::sin(2 * PI_F * ph);
        switch (type) {
            case 0: { // chorus
                dl.push(x); float d = (0.012f + 0.006f * depth * lfo) * fs;
                return x * (1 - 0.5f * mix) + dl.read(d) * mix;
            }
            case 1: { // flanger
                dl.push(x + fb * fbk * 0.9f); float d = (0.003f + 0.0025f * depth * (lfo * 0.5f + 0.5f) * 2) * fs;
                float w = dl.read(d); fb = w;
                return x * (1 - 0.5f * mix) + w * mix;
            }
            case 2: { // phaser 4 stage
                if ((cnt++ & 15) == 0) {
                    float f = 300.f * std::pow(10.f, depth * 1.2f * (lfo * 0.5f + 0.5f));
                    float t = std::tan(PI_F * std::min(f, fs * 0.4f) / fs); apA = (t - 1) / (t + 1);
                }
                float a = apA;
                float y = x + apFb * fbk * 0.85f;
                for (int i = 0; i < 4; i++) { float o = a * y + ap[i]; ap[i] = y - a * o; y = o; }
                apFb = y;
                return x * (1 - 0.5f * mix) + y * 0.5f * mix * 2;
            }
            default: { // tremolo
                float g = 1 - depth * (0.5f + 0.5f * lfo);
                return x * g;
            }
        }
    }
};

struct Reverb {
    struct Comb { std::vector<float> b; int i = 0; float st = 0;
        float run(float in, float fbk, float damp) { float o = b[i]; st = o * (1 - damp) + st * damp; b[i] = in + st * fbk; if (++i >= (int)b.size()) i = 0; return o; } };
    struct AP { std::vector<float> b; int i = 0;
        float run(float in) { float bo = b[i]; float o = -in + bo; b[i] = in + bo * 0.5f; if (++i >= (int)b.size()) i = 0; return o; } };
    Comb c[2][8]; AP a[2][4]; DelayLine pre;
    void init(float fs) {
        static const int ct[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617}, at[4] = {556, 441, 341, 225};
        float k = fs / 44100.f;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 8; i++) c[ch][i].b.assign((int)((ct[i] + 23 * ch) * k), 0.f);
            for (int i = 0; i < 4; i++) a[ch][i].b.assign((int)((at[i] + 23 * ch) * k), 0.f);
        }
    }
    /** Hanya bagian wet (tank). */
    void wet(float x, float size, float damp, float& o0, float& o1) {
        float fbk = 0.7f + size * 0.28f, d = damp * 0.4f, in = x * 0.015f, o[2] = {0, 0};
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 8; i++) o[ch] += c[ch][i].run(in, fbk, d);
            for (int i = 0; i < 4; i++) o[ch] = a[ch][i].run(o[ch]);
        }
        o0 = o[0]; o1 = o[1];
    }
    void process(float x, float size, float damp, float mix, float& l, float& r) {
        float o0, o1; wet(x, size, damp, o0, o1);
        l = x * (1 - mix * 0.6f) + o0 * mix * 3.f; r = x * (1 - mix * 0.6f) + o1 * mix * 3.f;
    }
};

/**
 * Pitch shifter domain-waktu: satu delay line dengan head baca yang bergeser, lalu "dilompatkan" kembali
 * dengan crossfade pendek. Titik sambung dicari lewat korelasi (SOLA) supaya gelombang gitar menyambung mulus.
 * Latency efektif ≈ dmin + span/2 hanya saat modul aktif:
 *   mode kualitas  (low=false): 672 + 1152/2 = 1248 sampel (±26 ms @48k)
 *   mode low-latency (low=true): 192 +  576/2 =  480 sampel (±10 ms @48k)  <- default aplikasi
 */
struct PitchShifter {
    std::vector<float> buf; int mask = 8191, w = 0;
    float dA = 0, dB = 0, r = 1.f, dmin = 672, span = 1152, cs = 0.003f;
    int dryD = 1248, fade = 0, lx = 256, srch = 288; bool fresh = true;

    void init(float fs, bool low = false) {
        const float k = fs / 48000.f;
        buf.assign(8192, 0.f); mask = 8191;
        if (low) {   // jendela lebih pendek: latency ~10 ms, sambungan (splice) sedikit lebih sering
            dmin = std::round(192 * k); span = std::round(576 * k);
            lx = std::max(32, (int)std::round(128 * k)); srch = std::max(16, (int)std::round(160 * k));
        } else {
            dmin = std::round(672 * k); span = std::round(1152 * k);
            lx = std::max(32, (int)std::round(256 * k)); srch = std::max(16, (int)std::round(288 * k));
        }
        dryD = (int)(dmin + std::round(span * 0.5f));
        cs = 1.f - std::exp(-1.f / (0.008f * fs));
        reset();
    }
    void reset() { std::fill(buf.begin(), buf.end(), 0.f); w = 0; dA = dmin + span * 0.5f; dB = dA; fade = 0; fresh = true; }

    inline float rd(float d) const {   // interpolasi Hermite 4 titik; d = jarak (sampel) di belakang sampel terbaru
        float pos = (float)(w - 1) - d; int i = (int)std::floor(pos); float f = pos - (float)i;
        float y0 = buf[(i - 1) & mask], y1 = buf[i & mask], y2 = buf[(i + 1) & mask], y3 = buf[(i + 2) & mask];
        float c1 = 0.5f * (y2 - y0), c2 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3, c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * f + c2) * f + c1) * f + y1;
    }
    void splice(float jump) {
        const int ia = w - 1 - (int)std::lround(dA), st = 4;
        float ea = 0; for (int j = 0; j < lx; j += st) { float a = buf[(ia + j) & mask]; ea += a * a; }
        const float base = dA + jump; float bestO = 0, bestS = -1e30f;
        if (ea > 1e-9f) {
            for (int o = -srch; o <= srch; o += 2) {
                const int ib = w - 1 - (int)std::lround(base + (float)o);
                float c = 0, eb = 1e-9f;
                for (int j = 0; j < lx; j += st) { float a = buf[(ia + j) & mask], b = buf[(ib + j) & mask]; c += a * b; eb += b * b; }
                float sc = c / std::sqrt(eb) * (1.f - 0.0003f * (float)std::abs(o));
                if (sc > bestS) { bestS = sc; bestO = (float)o; }
            }
        }
        dB = std::clamp(base + bestO, (float)lx + 8.f, 8192.f - (float)lx - 16.f);
        fade = lx;
    }
    /** x diproses di tempat. semis = pergeseran nada, mix 0..1 (dry sudah disejajarkan latency-nya). */
    void process(float* x, int n, float semis, float mix) {
        const float target = std::exp2(semis / 12.f);
        if (fresh) { r = target; fresh = false; }
        for (int i = 0; i < n; i++) {
            const float in = x[i];
            buf[w] = in; w = (w + 1) & mask;
            r += (target - r) * cs;
            const float dd = 1.f - r;
            dA += dd;
            float y;
            if (fade > 0) {
                dB += dd;
                const float t = 1.f - (float)fade / (float)lx, g = t * t * (3.f - 2.f * t);
                y = rd(dA) * (1.f - g) + rd(dB) * g;
                if (--fade == 0) dA = dB;
            } else {
                if (dA < dmin) splice(span); else if (dA > dmin + span) splice(-span);
                y = rd(dA);
            }
            x[i] = mix >= 0.999f ? y : buf[(w - 1 - dryD) & mask] * (1.f - mix) + y * mix;
        }
    }
};
