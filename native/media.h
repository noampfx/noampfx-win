#pragma once
#include <cstdint>
#include "dsp2.h"

// ============================================================================ Ring buffer SPSC (recorder)
struct Ring {
    std::vector<float> b; uint32_t mask = 0; std::atomic<uint32_t> w{0}, r{0}, dropped{0};
    void init(uint32_t pow2) { b.assign(pow2, 0.f); mask = pow2 - 1; w = 0; r = 0; dropped = 0; }
    void write(const float* d, int n) {
        const uint32_t ww = w.load(std::memory_order_relaxed), rr = r.load(std::memory_order_acquire);
        if (b.empty() || ww - rr + (uint32_t)n > b.size()) { dropped.fetch_add(n); return; }
        for (int i = 0; i < n; i++) b[(ww + i) & mask] = d[i];
        w.store(ww + n, std::memory_order_release);
    }
    int read(float* out, int maxN) {
        if (b.empty()) return 0;
        const uint32_t rr = r.load(std::memory_order_relaxed), ww = w.load(std::memory_order_acquire);
        const int n = (int)std::min<uint32_t>(ww - rr, (uint32_t)maxN);
        for (int i = 0; i < n; i++) out[i] = b[(rr + i) & mask];
        r.store(rr + n, std::memory_order_release);
        return n;
    }
};

// ============================================================================ Backing track (WSOLA time-stretch + resample Hermite)
struct PlayerData { std::vector<int16_t> pcm; int ch = 2, sr = 48000; int64_t frames = 0; };

struct BackingPlayer {
    static constexpr int N = 2048, HS = 1024, SRCH = 384;
    const PlayerData* d = nullptr;
    double pos = 0; int64_t last = -1; double rp = 0;
    std::vector<float> win, olaL, olaR, qL, qR; size_t qOff = 0;
    bool ended = false;
    BackingPlayer() {
        win.resize(N); for (int i = 0; i < N; i++) win[i] = 0.5f - 0.5f * std::cos(2.f * PI_F * i / N);
        olaL.assign(N, 0.f); olaR.assign(N, 0.f); qL.reserve(16384); qR.reserve(16384);
    }
    void attach(const PlayerData* p) { d = p; seekFrames(0); }
    void seekFrames(int64_t f) {
        pos = (double)std::max<int64_t>(f, 0); last = -1; rp = 0; ended = false;
        qL.clear(); qR.clear(); qOff = 0; std::fill(olaL.begin(), olaL.end(), 0.f); std::fill(olaR.begin(), olaR.end(), 0.f);
    }
    inline float sL(int64_t i) const { return (i < 0 || i >= d->frames) ? 0.f : d->pcm[(size_t)i * d->ch] * (1.f / 32768.f); }
    inline float sR(int64_t i) const { return (i < 0 || i >= d->frames) ? 0.f : d->pcm[(size_t)i * d->ch + (d->ch > 1 ? 1 : 0)] * (1.f / 32768.f); }
    inline float sM(int64_t i) const { return 0.5f * (sL(i) + sR(i)); }

    /** hasilkan satu frame WSOLA (HS sampel sumber-rate) ke antrian */
    void frame(float tempo, bool loopOn, double loopA, double loopB) {
        double s = pos;
        if (loopOn && loopB > loopA + 4096) {
            double c = s + N * 0.5;
            if (c >= loopB) { c = loopA + (c - loopB); s = c - N * 0.5; pos = s; last = -1; }
        }
        if (s >= (double)d->frames) { ended = true; return; }
        const int64_t t0 = (int64_t)std::llround(s);
        int64_t c0 = t0;
        if (last >= 0) {
            const int64_t r0 = last + HS; float best = -1e30f;
            for (int64_t c = t0 - SRCH; c <= t0 + SRCH; c += 2) {
                float cr = 0, eb = 1e-6f;
                for (int i = 0; i < HS; i += 4) { const float a = sM(r0 + i), b = sM(c + i); cr += a * b; eb += b * b; }
                const float sc = cr / std::sqrt(eb);
                if (sc > best) { best = sc; c0 = c; }
            }
        }
        for (int i = 0; i < N; i++) { const float w = win[i]; olaL[i] += w * sL(c0 + i); olaR[i] += w * sR(c0 + i); }
        qL.insert(qL.end(), olaL.begin(), olaL.begin() + HS); qR.insert(qR.end(), olaR.begin(), olaR.begin() + HS);
        std::memmove(olaL.data(), olaL.data() + HS, (N - HS) * sizeof(float)); std::memmove(olaR.data(), olaR.data() + HS, (N - HS) * sizeof(float));
        std::fill(olaL.begin() + (N - HS), olaL.end(), 0.f); std::fill(olaR.begin() + (N - HS), olaR.end(), 0.f);
        last = c0; pos += HS * (double)tempo;
    }
    /** Render n sampel stereo pada rate keluaran fs. Mengembalikan false bila sudah habis. */
    bool render(float* L, float* R, int n, float fs, float tempo, bool loopOn, double loopA, double loopB) {
        if (!d || d->frames <= 0) { std::fill(L, L + n, 0.f); std::fill(R, R + n, 0.f); return false; }
        const double step = (double)d->sr / fs; bool alive = true;
        for (int i = 0; i < n; i++) {
            const int64_t ip = (int64_t)rp;
            while (qL.size() - qOff < (size_t)ip + 4 + 1 && !ended) frame(tempo, loopOn, loopA, loopB);
            if (qL.size() - qOff < (size_t)ip + 3) { L[i] = R[i] = 0.f; alive = false; continue; }
            const float f = (float)(rp - (double)ip); const size_t b = qOff + (size_t)ip;
            auto herm = [&](const std::vector<float>& q) {
                const float y0 = b > 0 ? q[b - 1] : 0.f, y1 = q[b], y2 = q[b + 1], y3 = q[b + 2];
                const float c1 = 0.5f * (y2 - y0), c2 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3, c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
                return ((c3 * f + c2) * f + c1) * f + y1;
            };
            L[i] = herm(qL); R[i] = herm(qR);
            rp += step;
        }
        const int64_t ipi = (int64_t)rp;
        if (ipi > 2) { const size_t drop = (size_t)(ipi - 2); qOff += drop; rp -= (double)drop; }
        if (qOff > 8192) { qL.erase(qL.begin(), qL.begin() + qOff); qR.erase(qR.begin(), qR.begin() + qOff); qOff = 0; }
        return alive;
    }
    double positionSec() const { return d && d->sr > 0 ? std::min(pos, (double)d->frames) / d->sr : 0.0; }
};

// ============================================================================ Looper (stereo, post-efek)
struct Looper {
    enum { IDLE = 0, REC = 1, PLAY = 2, OVER = 3, STOP = 4 };
    std::vector<float> buf;    // 2 * maxFrames (interleaved)
    int64_t maxFrames = 0, len = 0, pos = 0; int state = IDLE;
    void init(int64_t frames) { maxFrames = frames; buf.assign((size_t)frames * 2, 0.f); len = pos = 0; state = IDLE; }
    void clear() { std::fill(buf.begin(), buf.end(), 0.f); len = pos = 0; state = IDLE; }
    /** tombol utama: idle→rec→play→overdub→play */
    void press() {
        switch (state) {
            case IDLE: pos = 0; len = 0; state = REC; break;
            case REC: if (pos > 256) { len = pos; pos = 0; state = PLAY; } else clear(); break;
            case PLAY: state = OVER; break;
            case OVER: state = PLAY; break;
            case STOP: pos = 0; state = PLAY; break;
        }
    }
    void stop() { if (state == PLAY || state == OVER) state = STOP; }
    /** Menambah playback ke L/R; merekam bila REC/OVER. */
    void process(float* L, float* R, int n, float gain) {
        if (buf.empty()) return;
        for (int i = 0; i < n; i++) {
            if (state == REC) {
                if (pos >= maxFrames) { len = maxFrames; pos = 0; state = PLAY; }
                else { buf[(size_t)pos * 2] = L[i]; buf[(size_t)pos * 2 + 1] = R[i]; pos++; }
            } else if (state == PLAY || state == OVER) {
                const size_t k = (size_t)pos * 2; const float pl = buf[k], pr = buf[k + 1];
                if (state == OVER) { buf[k] = pl * 0.98f + L[i]; buf[k + 1] = pr * 0.98f + R[i]; }
                L[i] += pl * gain; R[i] += pr * gain;
                if (++pos >= len) pos = 0;
            }
        }
    }
};

// ============================================================================ Metronom
// Subdivision patterns: bit k = a click at k/12 of the beat (12 = lcm of triplets and sixteenths).
constexpr uint16_t subMask(int a, int b = -1, int c = -1, int d = -1) { return (uint16_t)((1u << a) | (b >= 0 ? 1u << b : 0u) | (c >= 0 ? 1u << c : 0u) | (d >= 0 ? 1u << d : 0u)); }
constexpr uint16_t SUB_PATTERNS[14] = {
    subMask(0), subMask(0, 6), subMask(4, 8), subMask(0, 4, 8), subMask(8), subMask(0, 8), subMask(0, 4),            // quarter, 8ths, triplet variants
    subMask(4), subMask(0, 3, 6, 9), subMask(3, 9), subMask(0, 3, 6), subMask(0, 6, 9), subMask(0, 9), subMask(0, 3)  // 16th variants, dotted
};
struct Metronome {
    double cnt = 0; int beat = 0, step = 0, clickPos = -1; float freq = 1000.f, amp = 1.f;
    int seq = 0, lastBeat = 0;                          // seq counts every beat (for the UI blink), lastBeat = index of that beat in the bar
    void reset() { cnt = 0; beat = 0; step = 0; clickPos = -1; }
    void process(float* L, float* R, int n, float fs, float bpm, int beats, float lvl, int sub = 0) {
        const double per = 60.0 * fs / std::clamp(bpm, 20.f, 400.f) / 12.0; const int len = (int)(0.03f * fs);   // one step = 1/12 beat
        const uint16_t mask = SUB_PATTERNS[std::clamp(sub, 0, 13)];
        const int nb = std::max(beats, 1);
        for (int i = 0; i < n; i++) {
            if (cnt <= 0) {
                cnt += per;
                if (step == 0) { seq = (seq + 1) & 0xFFFFF; lastBeat = beat; }
                if (mask & (1u << step)) {
                    const bool acc = beat == 0 && step == 0, main = step == 0;
                    freq = acc ? 1600.f : (main ? 1000.f : 800.f); amp = acc ? 1.f : (main ? 0.65f : 0.4f); clickPos = 0;
                }
                if (++step >= 12) { step = 0; beat = (beat + 1) % nb; }
            }
            cnt -= 1.0;
            if (clickPos >= 0) {
                const float t = clickPos / fs, v = std::sin(2.f * PI_F * freq * t) * std::exp(-t * 140.f) * amp * lvl;
                L[i] += v; R[i] += v;
                if (++clickPos > len) clickPos = -1;
            }
        }
    }
};
