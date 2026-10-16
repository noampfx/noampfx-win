#include "engine.h"
#include "plugin_host.h"
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"
#include <chrono>
#include <filesystem>
#include <type_traits>
#include <thread>
#include <cstdio>
#include <cstring>
#if defined(__SSE__) || defined(_M_X64)
#include <xmmintrin.h>
#include <pmmintrin.h>
#endif
#ifdef _WIN32
#include <windows.h>
#endif
#include "NAM/dsp.h"
#include "NAM/get_dsp.h"
#include "NAM/activations.h"

static void logErr(const char* fmt, const char* a = "") { std::fprintf(stderr, "[NoAmp] "); std::fprintf(stderr, fmt, a); std::fprintf(stderr, "\n"); }

namespace {
// Windows: WASAPI only (lowest latency, no legacy WinMM / DirectSound layers). Elsewhere: miniaudio's default backend order (used for host tests).
ma_context g_ctx; bool g_ctxOk = false; std::mutex g_ctxMtx;
bool ensureContext() {
    std::lock_guard<std::mutex> lk(g_ctxMtx);
    if (g_ctxOk) return true;
    ma_context_config cc = ma_context_config_init();
#ifdef _WIN32
    ma_backend backends[] = { ma_backend_wasapi };
    g_ctxOk = ma_context_init(backends, 1, &cc, &g_ctx) == MA_SUCCESS;
#else
    g_ctxOk = ma_context_init(nullptr, 0, &cc, &g_ctx) == MA_SUCCESS;
#endif
    return g_ctxOk;
}
// stable id for a device: hash of its name (never 0, 0 = system default)
int devIdOf(const char* name) {
    uint32_t h = 2166136261u;
    for (const unsigned char* p = (const unsigned char*)name; *p; ++p) { h ^= *p; h *= 16777619u; }
    h &= 0x7fffffffu; return h ? (int)h : 1;
}
void dataCb(ma_device* d, void* out, const void* in, ma_uint32 n) { static_cast<Engine*>(d->pUserData)->onAudio(out, in, n); }
void notifyCb(const ma_device_notification* n) {
    if (n->type == ma_device_notification_type_stopped) static_cast<Engine*>(n->pDevice->pUserData)->onDeviceStopped();
}
}
#ifdef NAM_SAMPLE
static_assert(std::is_same<NAM_SAMPLE, float>::value, "NAM_SAMPLE must be float");
#endif

Engine& Engine::get() { static Engine e; return e; }

Engine::Engine() {
    for (auto& p : P) p.store(0.f);
    for (auto& m : M) m.store(-120.f);
    for (auto& s : S) s.store(0.f);
    for (int i = 0; i < 2; i++) { irPend[i] = nullptr; irGar[i] = nullptr; irClr[i] = false; irLoaded[i] = false; }
    ana.assign(8192, 0.f);
    for (auto& a : micP) a.store(0.f);
    micL.assign(CHUNK, 0.f); micR.assign(CHUNK, 0.f); micFx.init(48000.f, CHUNK);
    mono.assign(CHUNK, 0.f); micBuf.assign(CHUNK, 0.f); bufL.assign(CHUNK, 0.f); bufR.assign(CHUNK, 0.f);
    dryBuf.assign(CHUNK, 0.f); oldBuf.assign(CHUNK, 0.f); wetA.assign(CHUNK, 0.f); wetB.assign(CHUNK, 0.f);
    plL.assign(CHUNK, 0.f); plR.assign(CHUNK, 0.f); sideBuf.assign(CHUNK, 0.f); recTmp.assign(CHUNK * 2, 0.f); tun.assign(8192, 0.f);
    harmPs.init(48000.f, true);
    for (int b = 0; b < PARAM_BANKS; b++) { oct[b].reset(); pitchPs[b].init(48000.f, true); drive[b].init(48000.f); mod[b].init(48000.f); dfx[b].init(48000.f); rfx[b].init(48000.f); }
    nam::activations::Activation::enable_fast_tanh();
}

// ---------------------------------------------------------------------------- NamSlot
template <class T> static auto expectedRate(T* m, int) -> decltype((double)m->GetExpectedSampleRate()) { return (double)m->GetExpectedSampleRate(); }
template <class T> static double expectedRate(T*, long) { return -1.0; }

NamSlot::~NamSlot() { delete dsp; }

void NamSlot::configure(double fs) {
    double r = expectedRate(dsp, 0); if (!(r > 0.0)) r = 48000.0;   // model tanpa metadata dianggap 48 kHz
    rate = r; rs = std::fabs(r - fs) > 1.0;
    dsp->Reset(rs ? r : fs, CHUNK); dsp->prewarm();
    tmp.assign(CHUNK, 0.f);
    if (rs) {
        up.init(fs, r); down.init(r, fs);
        a.assign(CHUNK * 3 + 64, 0.f); b.assign(CHUNK * 3 + 64, 0.f); d.assign(CHUNK * 3 + 64, 0.f);
        fr = 0; fw = 8; fc = 8;                                   // prime 8 sampel nol agar fifo tidak underrun
    }
}

void NamSlot::run(float* io, int n) {
    NAM_SAMPLE* pin[1]; NAM_SAMPLE* pout[1];
    if (!rs) { pin[0] = io; pout[0] = tmp.data(); dsp->process(pin, pout, n); std::memcpy(io, tmp.data(), (size_t)n * sizeof(float)); return; }
    const int m = up.process(io, n, a.data(), (int)a.size());
    for (int off = 0; off < m; off += CHUNK) {
        const int c = std::min(CHUNK, m - off);
        pin[0] = a.data() + off; pout[0] = b.data() + off; dsp->process(pin, pout, c);
    }
    const int k = down.process(b.data(), m, d.data(), (int)d.size());
    for (int i = 0; i < k; i++) if (fc < 4096) { fifo[fw] = d[i]; fw = (fw + 1) & 4095; fc++; }
    for (int i = 0; i < n; i++) { if (fc > 0) { io[i] = fifo[fr]; fr = (fr + 1) & 4095; fc--; } else io[i] = 0.f; }
}

void Engine::prepare(float fs) {
    fsA.store(fs);
    pitchLowApplied = pitchLow.load(); harmPs.init(fs, pitchLowApplied);
    micFx.init(fs, CHUNK);
    for (int b = 0; b < PARAM_BANKS; b++) { mod[b].init(fs); dfx[b].init(fs); rfx[b].init(fs); pitchPs[b].init(fs, pitchLowApplied); drive[b].init(fs); oct[b].reset(); wah[b].reset(); enOct[b] = enPitch[b] = enWah[b] = enDrive[b] = 0.f; eqInit[b] = false; }
    enHarm = enNam = enCab = 0.f; limG = 1; memset(laBuf, 0, sizeof(laBuf)); cs[0].reset(); cs[1].reset(); sg[0] = sg[1] = 1.f;
    // Stream belum berjalan di sini, jadi aman menyentuh semua model.
    NamSlot* slots[3] = { namActive, namPending.load(), namOld };
    for (auto* s : slots) if (s) s->configure((double)fs);
    const int64_t lf = (int64_t)(fs * 60);
    if (loop.maxFrames != lf) loop.init(lf);
    metro.reset(); bp.seekFrames(0);
}

std::vector<std::string> Engine::listDevices(bool input) {
    std::vector<std::string> out;
    if (!ensureContext()) return out;
    ma_device_info *pb = nullptr, *cap = nullptr; ma_uint32 npb = 0, ncap = 0;
    std::lock_guard<std::mutex> lk(g_ctxMtx);
    if (ma_context_get_devices(&g_ctx, &pb, &npb, &cap, &ncap) != MA_SUCCESS) return out;
    const ma_device_info* l = input ? cap : pb; const ma_uint32 n = input ? ncap : npb;
    for (ma_uint32 i = 0; i < n; i++) out.push_back(std::to_string(devIdOf(l[i].name)) + "|" + l[i].name);
    return out;
}

// Open one full-duplex device (capture + playback share the callback). Returns nullptr on failure.
static ma_device* openDuplex(Engine* self, int inId, int outId, int mode, float bufMs, int sampleRate, int capCh, bool exclusive, std::string& why) {
    ma_device_id inDev, outDev; bool hasIn = false, hasOut = false;
    {
        std::lock_guard<std::mutex> lk(g_ctxMtx);
        ma_device_info *pb = nullptr, *cap = nullptr; ma_uint32 npb = 0, ncap = 0;
        if (ma_context_get_devices(&g_ctx, &pb, &npb, &cap, &ncap) == MA_SUCCESS) {
            for (ma_uint32 i = 0; i < ncap && inId > 0; i++) if (devIdOf(cap[i].name) == inId) { inDev = cap[i].id; hasIn = true; }
            for (ma_uint32 i = 0; i < npb && outId > 0; i++) if (devIdOf(pb[i].name) == outId) { outDev = pb[i].id; hasOut = true; }
        }
    }

    ma_device_config c = ma_device_config_init(ma_device_type_duplex);
    c.sampleRate = (ma_uint32)sampleRate;
    c.dataCallback = dataCb; c.notificationCallback = notifyCb; c.pUserData = self;
    c.performanceProfile = mode <= 1 ? ma_performance_profile_low_latency : ma_performance_profile_conservative;
    c.noPreSilencedOutputBuffer = MA_TRUE; c.noClip = MA_TRUE;
    // mode defaults (period in ms): Ultra 3 / Low 5 / Balanced 10 / Safe 20; the user buffer value is the total of 2 periods
    const float defMs[4] = { 3.f, 5.f, 10.f, 20.f };
    const float per = bufMs > 0.f ? std::max(1.f, bufMs / 2.f) : defMs[std::clamp(mode, 0, 3)];
    c.periodSizeInMilliseconds = (ma_uint32)std::ceil(per); c.periods = 2;
    c.capture.format = ma_format_f32; c.capture.channels = (ma_uint32)capCh; c.capture.channelMixMode = ma_channel_mix_mode_simple;
    c.capture.shareMode = exclusive ? ma_share_mode_exclusive : ma_share_mode_shared; c.capture.pDeviceID = hasIn ? &inDev : nullptr;
    c.playback.format = ma_format_f32; c.playback.channels = 2;
    c.playback.shareMode = exclusive ? ma_share_mode_exclusive : ma_share_mode_shared; c.playback.pDeviceID = hasOut ? &outDev : nullptr;
    c.wasapi.noAutoConvertSRC = MA_FALSE;

    auto* d = new ma_device();
    ma_result r = ma_device_init(&g_ctx, &c, d);
    if (r != MA_SUCCESS) { why = ma_result_description(r); delete d; return nullptr; }
    return d;
}

bool Engine::start(int inId, int outId, int mode, float bufMs, int sampleRate, int micDev) {
    (void)micDev;
    std::lock_guard<std::mutex> lk(mtx);
    if (sampleRate != 44100 && sampleRate != 48000 && sampleRate != 88200 && sampleRate != 96000) sampleRate = 48000;
    lastBackend = 0; lastIn = inId; lastOut = outId; lastMic = 0; lastMode = mode; lastSr = sampleRate; bufMsA.store(bufMs); xrunA.store(0.f);
    { std::lock_guard<std::mutex> el(errMtx); err.clear(); }
    if (!ensureContext()) { std::lock_guard<std::mutex> el(errMtx); err = "audio system not available"; return false; }
    closeDevicesLocked();

    // 1st try: requested mode (Ultra = exclusive WASAPI). 2nd try: shared mode. Input channels: 2 when the mic (channel 2) is on, else 1 (channel 1 of the interface).
    const int capCh = micWant.load() ? 2 : 1;
    std::string why;
    bool excl = (mode == 0);
    dev = openDuplex(this, inId, outId, mode, bufMs, sampleRate, capCh, excl, why);
    if (!dev && excl) dev = openDuplex(this, inId, outId, 1, bufMs, sampleRate, capCh, false, why);
    if (!dev) { std::lock_guard<std::mutex> el(errMtx); err = "could not open audio device: " + why; logErr("%s", why.c_str()); return false; }

    const int sr = (int)dev->sampleRate;
    outCh = 2;
    inCh = capCh;
    const bool hw2 = dev->capture.internalChannels >= 2;
    micUsing.store(micWant.load() && hw2);
    devPeriod = (int)dev->playback.internalPeriodSizeInFrames;
    burstA.store(devPeriod);
    const float frames = (float)(dev->playback.internalPeriodSizeInFrames * dev->playback.internalPeriods + dev->capture.internalPeriodSizeInFrames * dev->capture.internalPeriods);
    latMs.store(frames * 1000.f / (float)sr);
    prepare((float)sr);
    PluginHost::get().prepare((double)sr, CHUNK);
    backend = 0; discard = 20;
    running = true;
    if (ma_device_start(dev) != MA_SUCCESS) {
        running = false; ma_device_uninit(dev); delete dev; dev = nullptr;
        std::lock_guard<std::mutex> el(errMtx); err = "could not start audio device"; return false;
    }
    startHarmThread();
    return true;
}

void Engine::closeDevicesLocked() {
    running = false; stopping = true;
    asioStopDevice();
    if (dev) { ma_device_uninit(dev); delete dev; dev = nullptr; }
    stopping = false; backend = 0;
}

void Engine::startHarmThread() { if (!harmTh.joinable()) { harmRun = true; harmTh = std::thread([this] { harmLoop(); }); } }

void Engine::stop() {
    std::lock_guard<std::mutex> lk(mtx);
    harmRun = false; if (harmTh.joinable() && std::this_thread::get_id() != harmTh.get_id()) harmTh.join();
    closeDevicesLocked();
    for (int i = 0; i < 3; i++) M[i].store(-120.f);
}

// The device stopped by itself (unplugged / format change): try to reopen it once, like the Android build did.
void Engine::onDeviceStopped() {
    if (stopping.load() || !running.load()) return;
    std::thread([this] {
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        if (stopping.load() || !running.load()) return;
        const bool ok = lastBackend == 1 ? startAsio(lastAsioDrv, lastSr, lastAsioBuf, lastAsioIn, lastAsioOut) : start(lastIn, lastOut, lastMode, bufMsA.load(), lastSr, 0);
        if (!ok) running = false;
    }).detach();
}

std::string Engine::loadNam(const std::string& path) {
    std::lock_guard<std::mutex> lk(loadMtx);
    try {
        std::error_code ec; auto sz = std::filesystem::file_size(std::filesystem::u8path(path), ec);
        if (ec || sz == 0) return "empty or unreadable file";
        delete namGarbage.exchange(nullptr);
        auto m = nam::get_dsp(std::filesystem::u8path(path));
        if (!m) return "get_dsp returned null";
        auto* slot = new NamSlot(); slot->dsp = m.release();
        slot->configure((double)fsA.load());
        NamSlot* old = namPending.exchange(slot); delete old;
        namLoaded = true;
        return "";
    } catch (const std::exception& e) {
        logErr("NAM load error: %s", e.what());
        return std::string(e.what());
    } catch (...) { return "unknown exception"; }
}

void Engine::clearNam() { namLoaded = false; NamSlot* old = namPending.exchange(nullptr); delete old; namClearReq = true; }

void Engine::setIr(int slot, const float* d, int n) {
    if (slot < 0 || slot > 1) return;
    n = std::min(n, IR_MAX); if (n <= 0) return;
    delete irGar[slot].exchange(nullptr);
    std::vector<float> h(d, d + n);
    double e = 0; for (float v : h) e += (double)v * v;
    const float s = e > 1e-12 ? (float)(1.0 / std::sqrt(e)) : 1.f;
    for (int i = 0; i < n; i++) { float fade = i > n - 64 ? (float)(n - i) / 64.f : 1.f; h[i] *= s * fade; }
    float pk = 0; for (float v : h) pk = std::max(pk, std::fabs(v));
    int len = n; while (len > 8 && std::fabs(h[len - 1]) < pk * 3e-4f) len--;   // buang ekor < -70 dB
    ConvIR* ir = ConvIR::build(h.data(), std::max(8, len));
    ConvIR* old = irPend[slot].exchange(ir); delete old;
    irLoaded[slot] = true;
}
void Engine::clearIr(int slot) { if (slot < 0 || slot > 1) return; irLoaded[slot] = false; irClr[slot] = true; }

void Engine::updateEq(float fs, int bk) {
    static const int types[5] = {0, 1, 1, 1, 2};
    const int ob = bk * PARAM_BANK;
    bool ch = !eqInit[bk];
    for (int i = 0; i < 15; i++) { float v = P[ob + EQ_B0 + i].load(std::memory_order_relaxed); if (!eqInit[bk] || v != eqCache[bk][i]) { eqCache[bk][i] = v; ch = true; } }
    if (!ch) return;
    eqInit[bk] = true;
    for (int b = 0; b < 5; b++) eq[bk][b].set(types[b], fs, eqCache[bk][b * 3], eqCache[bk][b * 3 + 1], eqCache[bk][b * 3 + 2]);
}

void Engine::processBlock(float* L, float* R, int n) {
    const float fs = fsA.load();
    auto p = [&](int i) { return P[i].load(std::memory_order_relaxed); };
    float* x = mono.data();
    auto ramp = [&](float* b, float& prev, float cur) { const float d = (cur - prev) / (float)n; float g = prev; for (int i = 0; i < n; i++) { g += d; b[i] *= g; } prev = cur; };
    // fade masuk/keluar saat modul di-ON/OFF-kan supaya tidak klik
    auto stage = [&](float& en, bool target, auto&& proc) {
        const float t = target ? 1.f : 0.f;
        if (en <= 0.f && t <= 0.f) return;
        if (en >= 1.f && t >= 1.f) { proc(x); return; }
        std::memcpy(dryBuf.data(), x, (size_t)n * sizeof(float));
        proc(x);
        const float st = 1.f / 128.f;
        for (int i = 0; i < n; i++) { en += std::clamp(t - en, -st, st); x[i] = dryBuf[i] * (1.f - en) + x[i] * en; }
    };

    // ---- mode pitch shifter (low-latency / quality): ganti jendela bila diubah dari UI
    if (const bool pl = pitchLow.load(std::memory_order_relaxed); pl != pitchLowApplied) {
        pitchLowApplied = pl; harmPs.init(fs, pl); enHarm = 0.f;
        for (int b = 0; b < PARAM_BANKS; b++) { pitchPs[b].init(fs, pl); enPitch[b] = 0.f; }
    }

    // ---- perintah dari UI
    if (const int c = lpCmd.exchange(0)) { if (c == 1) loop.press(); else if (c == 2) loop.stop(); else if (c == 3) loop.clear(); }
    if (auto* np = pdPend.exchange(nullptr)) {
        const bool keepPos = plKeep.exchange(false); const double oldPos = bp.pos;           // same song, longer data: continue where we are
        delete pdGar.exchange(pdAct); pdAct = np; bp.attach(pdAct);
        if (keepPos) bp.seekFrames((int64_t)oldPos);
        plDurSec.store(pdAct->sr > 0 ? (double)pdAct->frames / pdAct->sr : 0.0);
    }
    if (const int64_t sk = plSeek.exchange(-1); sk >= 0 && pdAct) bp.seekFrames(sk);

    // ---- rekam DI + tuner/harmonizer (sinyal mentah, sebelum gain/efek)
    if (recDiOn.load(std::memory_order_relaxed)) recDI.write(x, n);
    if (tunOn.load(std::memory_order_relaxed) || harmWant.load(std::memory_order_relaxed)) {
        int tw = tunW.load(std::memory_order_relaxed);
        for (int i = 0; i < n; i++) tun[(tw + i) & 8191] = x[i];
        tunW.store((tw + n) & 8191, std::memory_order_release);
    }

    // ---- backing track (mix setelah efek, atau reamp ke dalam chain)
    const bool playing = plPlay.load() && pdAct && pdAct->frames > 0;
    const int route = plRoute.load(); const float pg = plGain.load();
    if (playing) {
        const double sr = pdAct->sr;
        const bool alive = bp.render(plL.data(), plR.data(), n, fs, plTempo.load(), plLoopOn.load(), plLoopA.load() * sr, plLoopB.load() * sr);
        if (!alive) { plPlay.store(false); plSeek.store(0); }
        plPosSec.store(bp.positionSec(), std::memory_order_relaxed);
        if (route == 1) for (int i = 0; i < n; i++) x[i] += 0.5f * (plL[i] + plR[i]) * pg;
    }

    // ---- input gain + meter
    float pk = 0;
    ramp(x, mInPrev, faderLin(p(MASTER_IN)));
    for (int i = 0; i < n; i++) pk = std::max(pk, std::fabs(x[i]));
    PluginHost::get().setTempo(metBpm.load(std::memory_order_relaxed));
    PluginHost::get().processPre(x, n);                       // VST3 slot, position "before the chain" (mono in / mono out)

    // ---- housekeeping model NAM + IR cab (selalu dijalankan, apa pun urutan chain)
    if (namClearReq.exchange(false)) { delete namGarbage.exchange(namActive); namActive = nullptr; if (namOld) { delete namGarbage.exchange(namOld); namOld = nullptr; } }
    if (auto* pn = namPending.exchange(nullptr)) {
        if (namOld) delete namGarbage.exchange(namOld);
        namOld = namActive; namActive = pn; namFadePos = 0;
        if (!namOld) namFadePos = 1 << 30;
    }
    for (int s = 0; s < 2; s++) {
        if (irClr[s].exchange(false)) { delete irGar[s].exchange(irAct[s]); irAct[s] = nullptr; cs[s].reset(); sg[s] = 1.f; }
        if (irPend[s].load(std::memory_order_relaxed) && (sg[s] <= 0.001f || !irAct[s])) {
            if (auto* pi = irPend[s].exchange(nullptr)) { delete irGar[s].exchange(irAct[s]); irAct[s] = pi; cs[s].reset(); }
        }
    }
    const bool haveA = irAct[0] != nullptr, haveB = irAct[1] != nullptr;

    // ---- urutan blok: slot CHAIN0..CHAIN0+15 berisi kode modul (1 gate, 2 comp, 3 octave, 4 pitch, 5 harmony, 6 wah,
    //      7 distortion, 8 NAM, 9 cab, 10 EQ, 11 mod, 12 delay, 13 reverb); 0 = kosong. Modul yang tidak ada di chain dilewati.
    uint64_t present = 0; int order[CHAIN_SLOTS]; int cnt = 0;
    for (int s = 0; s < CHAIN_SLOTS; s++) {
        const int c = (int)(p(CHAIN0 + s) + 0.5f), m = c & 15, bk = c >> 4;
        if (c < 1 || bk >= PARAM_BANKS || m < 2 || m > 13) continue;      // modul 1 (gate) tidak ada di slot: selalu di awal chain
        if (bk > 0 && (m == 5 || m == 8 || m == 9)) continue;      // harmonizer, NAM dan cab tidak bisa dikembarkan
        if (!((present >> c) & 1ull)) { present |= 1ull << c; order[cnt++] = c; }
    }
    const bool hOn = p(HARM_ON) > 0.5f && ((present >> 5) & 1ull);
    harmWant.store(hOn, std::memory_order_relaxed);

    float gGr = 0, cGr = 0; bool hasSide = false, anaDone = false;
    auto tapAna = [&]() { int w = anaW.load(std::memory_order_relaxed); for (int i = 0; i < n; i++) ana[(w + i) & 8191] = x[i]; anaW.store((w + n) & 8191, std::memory_order_release); };

    // gate: tetap di awal chain (dikendalikan dari potensio Input Gain)
    if (p(GATE_ON) > 0.5f) {
        const float thr = dbToLin(p(GATE_THR)), rng = dbToLin(p(GATE_RANGE)), aC = onePoleCoef(p(GATE_ATK), fs), rC = onePoleCoef(p(GATE_REL), fs);
        for (int i = 0; i < n; i++) x[i] = gate[0].process(x[i], thr, aC, rC, rng);
        gGr = std::max(0.f, -linToDb(gate[0].g));
    } else { gate[0].g = 1; }

    for (int oi = 0; oi < cnt; oi++) {
        const int bk = order[oi] >> 4, ob = bk * PARAM_BANK;
        auto q = [&](int i) { return P[ob + i].load(std::memory_order_relaxed); };
        switch (order[oi] & 15) {
        case 2: {   // compressor
            if (q(COMP_ON) > 0.5f) {
                const float t = q(COMP_THR), slope = 1.f / std::max(q(COMP_RATIO), 1.f) - 1.f, mk = q(COMP_MAKEUP), k = q(COMP_KNEE);
                const float aC = onePoleCoef(q(COMP_ATK), fs), rC = onePoleCoef(q(COMP_REL), fs);
                for (int i = 0; i < n; i++) x[i] = comp[bk].process(x[i], t, slope, aC, rC, mk, k);
                cGr = std::max(cGr, -comp[bk].cur);
            } else { comp[bk].cur = 0; }
        } break;
        case 3:     // octaver
            stage(enOct[bk], q(OCT_ON) > 0.5f, [&](float* y) { oct[bk].process(y, n, fs, q(OCT_DOWN), q(OCT_UP), q(OCT_DRY)); });
            break;
        case 4: {   // pitch shifter (+-24 st)
            if (q(PITCH_ON) > 0.5f && enPitch[bk] <= 0.f) pitchPs[bk].reset();
            const float st = std::clamp(q(PITCH_ST) + q(PITCH_FINE) * 0.01f, -24.f, 24.f);
            stage(enPitch[bk], q(PITCH_ON) > 0.5f, [&](float* y) { pitchPs[bk].process(y, n, st, std::clamp(q(PITCH_MIX), 0.f, 1.f)); });
        } break;
        case 5: {   // harmonizer
            if (hOn && enHarm <= 0.f) harmPs.reset();
            stage(enHarm, hOn, [&](float* y) { harmPs.process(y, n, harmSemis.load(std::memory_order_relaxed), std::clamp(q(HARM_MIX), 0.f, 1.f)); });
        } break;
        case 6:     // wah
            stage(enWah[bk], q(WAH_ON) > 0.5f, [&](float* y) { wah[bk].process(y, n, fs, (int)(q(WAH_MODE) + 0.5f), q(WAH_POS), q(WAH_Q), q(WAH_SENS)); });
            break;
        case 7:     // distortion (overdrive / distortion / fuzz, 2x oversampled)
            stage(enDrive[bk], q(DRV_ON) > 0.5f, [&](float* y) { drive[bk].process(y, n, (int)(q(DRV_TYPE) + 0.5f), q(DRV_GAIN), q(DRV_TONE), dbToLin(q(DRV_LEVEL))); });
            break;
        case 8:     // NAM (crossfade antar-model)
            stage(enNam, q(NAM_ON) > 0.5f && namActive != nullptr, [&](float* y) {
                if (!namActive) return;
                ramp(y, igPrev, dbToLin(q(NAM_IN)));
                if (namOld) std::memcpy(oldBuf.data(), y, (size_t)n * sizeof(float));
                namActive->run(y, n);
                if (namOld) {
                    namOld->run(oldBuf.data(), n);
                    const int XF = 2048;
                    for (int i = 0; i < n; i++) { const float g = std::min(1.f, (float)(namFadePos + i) / XF); y[i] = y[i] * g + oldBuf[i] * (1.f - g); }
                    namFadePos += n;
                    if (namFadePos >= XF) { delete namGarbage.exchange(namOld); namOld = nullptr; }
                }
                ramp(y, ogPrev, dbToLin(q(NAM_OUT)));
            });
            break;
        case 9:     // cabinet: 2 slot IR (konvolusi partisi, tanpa latency) + blend
            stage(enCab, q(CAB_ON) > 0.5f && (haveA || haveB), [&](float* y) {
                if (!haveA && !haveB) return;
                float* w[2] = { wetA.data(), wetB.data() };
                const float st = 1.f / 128.f;
                for (int s = 0; s < 2; s++) {
                    if (!irAct[s]) continue;
                    std::memcpy(w[s], y, (size_t)n * sizeof(float));
                    cs[s].process(w[s], n, irAct[s]);
                    const float tg = irPend[s].load(std::memory_order_relaxed) ? 0.f : 1.f;       // dip singkat saat ganti IR
                    for (int i = 0; i < n; i++) { sg[s] += std::clamp(tg - sg[s], -st, st); w[s][i] *= sg[s]; }
                }
                const float lev = dbToLin(q(CAB_LEVEL)), mix = q(CAB_MIX), bm = std::clamp(q(CAB2_MIX), 0.f, 1.f);
                for (int i = 0; i < n; i++) {
                    const float wet = (haveA && haveB) ? w[0][i] * (1.f - bm) + w[1][i] * bm : haveA ? w[0][i] : w[1][i];
                    y[i] = y[i] * (1.f - mix) + wet * lev * mix;
                }
            });
            break;
        case 10: {  // EQ (+ tap analisa spektrum)
            updateEq(fs, bk);
            if (q(EQ_ON) > 0.5f) {
                float og = dbToLin(q(EQ_OUT));
                for (int i = 0; i < n; i++) { float y = x[i]; for (int b = 0; b < 5; b++) y = eq[bk][b].process(y); x[i] = y * og; }
            }
            if (!anaDone) { tapAna(); anaDone = true; }
        } break;
        case 11: {  // modulation
            if (q(MOD_ON) > 0.5f) {
                int ty = (int)q(MOD_TYPE); float ra = q(MOD_RATE), de = q(MOD_DEPTH), mx = q(MOD_MIX), fb = q(MOD_FB);
                for (int i = 0; i < n; i++) x[i] = mod[bk].process(x[i], ty, ra, de, mx, fb, fs);
            }
        } break;
        case 12: {  // delay (digital/tape + ducking; ekor tetap berbunyi saat OFF)
            float dlyMs = q(DLY_TIME);
            if (const int dm = (int)(q(DLY_MODE) + 0.5f); dm > 0) {
                static const float beats[6] = {4.f, 2.f, 1.f, 0.5f, 0.25f, 0.125f};
                const float mul = dm == 2 ? 2.f / 3.f : dm == 3 ? 1.5f : 1.f;
                dlyMs = 60000.f / std::clamp(q(DLY_BPM), 20.f, 300.f) * beats[std::clamp((int)(q(DLY_DIV) + 0.5f), 0, 5)] * mul;
            }
            const float target = std::clamp(dlyMs * 0.001f * fs, 2.f, fs * 2.0f);
            dfx[bk].process(x, n, fs, q(DLY_ON) > 0.5f, (int)(q(DLY_TYPE) + 0.5f), target, q(DLY_FB), q(DLY_MIX), q(DLY_TONE), std::clamp(q(DLY_DUCK), 0.f, 1.f));
        } break;
        case 13: {  // reverb -> stereo. Mid tetap di chain mono, side disimpan dan digabung lagi di akhir (blok setelah reverb tetap berfungsi).
            const bool on = q(REV_ON) > 0.5f; const float sz = q(REV_SIZE), dm = q(REV_DAMP), mx = q(REV_MIX), sh = q(REV_SHIM); const int ty = (int)(q(REV_TYPE) + 0.5f);
            float* sd = sideBuf.data();
            const bool firstSide = !hasSide;
            for (int i = 0; i < n; i++) { float l, r; rfx[bk].process(x[i], on, sz, dm, mx, ty, sh, l, r); x[i] = 0.5f * (l + r); sd[i] = (firstSide ? 0.f : sd[i]) + 0.5f * (l - r); }
            hasSide = true;
        } break;
        default: break;
        }
    }
    for (int b = 0; b < PARAM_BANKS; b++) {
        if (b > 0) gate[b].g = 1;     // gate hanya bank 0 (selalu di awal chain); jangan reset gate[0] di sini
        if (!((present >> (b * 16 + 2)) & 1ull)) comp[b].cur = 0;
    }
    if (!anaDone) tapAna();
    if (hasSide) { const float* sd = sideBuf.data(); for (int i = 0; i < n; i++) { L[i] = x[i] + sd[i]; R[i] = x[i] - sd[i]; } }
    else for (int i = 0; i < n; i++) { L[i] = x[i]; R[i] = x[i]; }

    PluginHost::get().processPost(L, R, n);                   // VST3 slot, position "after the chain" (stereo)

    // ---- looper (stereo, setelah efek)
    loop.process(L, R, n, lpGain.load(std::memory_order_relaxed));
    lpState.store(loop.state, std::memory_order_relaxed);
    lpPosSec.store((double)loop.pos / fs, std::memory_order_relaxed); lpLenSec.store((double)(loop.state == Looper::REC ? loop.pos : loop.len) / fs, std::memory_order_relaxed);

    // ---- limiter (stereo linked, 64-sample lookahead) + master out
    const bool lOn = p(LIM_ON) > 0.5f;
    const float thr = p(LIM_THR), ceilDb = p(LIM_CEIL), thrL = dbToLin(thr), ceilL = dbToLin(ceilDb), mk = dbToLin(ceilDb - thr);
    const float aC = onePoleCoef(0.5f, fs), rC = onePoleCoef(p(LIM_REL), fs);
    float inPk = 0, outPk = 0, minG = 1, pkL2 = 0, pkR2 = 0;
    const float muteT = (tunOn.load(std::memory_order_relaxed) && tunMute.load(std::memory_order_relaxed)) ? 0.f : 1.f;
    const float mOutT = faderLin(p(MASTER_OUT)), mOutD = (mOutT - mOutPrev) / (float)n; float mOutC = mOutPrev;
    const bool addBt = playing && route == 0, mOn = metOn.load(std::memory_order_relaxed);
    const bool micMix = micUsing.load(std::memory_order_relaxed) && micWant.load(std::memory_order_relaxed);
    const float micT = micLvl.load(std::memory_order_relaxed); float micG = micGs;
    const float busT = busLvl.load(std::memory_order_relaxed); float busG = busGs, pg1 = 0.f, pgM = 0.f;
    float panT[6];   // balance gains (L, R) of channel 1, channel 2, bus
    for (int c = 0; c < 3; c++) { const float v = panP[c].load(std::memory_order_relaxed); panT[2 * c] = v > 0.f ? 1.f - v : 1.f; panT[2 * c + 1] = v < 0.f ? 1.f + v : 1.f; }      // master bus + output meters of channel 1 / 2
    if (micMix) {   // mic effects: gate -> comp -> EQ -> delay -> reverb (stereo), mixed in below
        float mp[MIC_PARAM_COUNT]; for (int k = 0; k < MIC_PARAM_COUNT; k++) mp[k] = micP[k].load(std::memory_order_relaxed);
        micFx.process(micBuf.data(), micL.data(), micR.data(), n, fs, mp);
    }
    for (int i = 0; i < n; i++) {
        float l = L[i], r = R[i], m = std::max(std::fabs(l), std::fabs(r)); inPk = std::max(inPk, m);
        float ol, orr;
        if (lOn) {
            float tg = m > thrL ? thrL / m : 1.f, c = tg < limG ? aC : rC;
            limG = c * limG + (1 - c) * tg;
            float dl = laBuf[0][laPos], dr = laBuf[1][laPos]; laBuf[0][laPos] = l; laBuf[1][laPos] = r; laPos = (laPos + 1) & 63;
            ol = std::clamp(dl * limG * mk, -ceilL, ceilL); orr = std::clamp(dr * limG * mk, -ceilL, ceilL);
            minG = std::min(minG, limG);
        } else { limG = 1; ol = l; orr = r; }
        outPk = std::max(outPk, std::max(std::fabs(ol), std::fabs(orr)));
        muteG += (muteT - muteG) * 0.01f; mOutC += mOutD; const float mo = mOutC * muteG;
        ol *= mo; orr *= mo;
        pg1 = std::max(pg1, std::max(std::fabs(ol), std::fabs(orr)));
        for (int k = 0; k < 6; k++) panS[k] += (panT[k] - panS[k]) * 0.002f;      // smooth balance changes (no clicks)
        ol *= panS[0]; orr *= panS[1];                                           // out channel 1 balance
        micG += (micT - micG) * 0.002f;                                  // smooth level changes (no clicks)
        if (micMix) { const float mg = micG * muteG; const float ma = micL[i] * mg * panS[2], mb = micR[i] * mg * panS[3]; ol += ma; orr += mb; pgM = std::max(pgM, std::max(std::fabs(ma), std::fabs(mb))); }
        if (addBt) { ol += plL[i] * pg; orr += plR[i] * pg; }
        busG += (busT - busG) * 0.002f; ol *= busG * panS[4]; orr *= busG * panS[5];       // master bus (stereo out) fader
        L[i] = std::clamp(ol, -1.f, 1.f); R[i] = std::clamp(orr, -1.f, 1.f);
    }
    mOutPrev = mOutT; micGs = micG; busGs = busG;
    if (mOn) { metro.process(L, R, n, fs, metBpm.load(), metBeats.load(), metLvl.load(), metSub.load()); metSeq.store(metro.seq, std::memory_order_relaxed); metIx.store(metro.lastBeat, std::memory_order_relaxed); }
    for (int i = 0; i < n; i++) { L[i] = std::clamp(L[i], -1.f, 1.f); R[i] = std::clamp(R[i], -1.f, 1.f); pkL2 = std::max(pkL2, std::fabs(L[i])); pkR2 = std::max(pkR2, std::fabs(R[i])); }
    if (recMixOn.load(std::memory_order_relaxed)) { for (int i = 0; i < n; i++) { recTmp[2 * i] = L[i]; recTmp[2 * i + 1] = R[i]; } recMix.write(recTmp.data(), 2 * n); }

    // ---- meters (peak with falloff)
    float drop = 40.f * n / fs;
    auto upd = [&](int idx, float v, float floorV) { float cur = M[idx].load(std::memory_order_relaxed); M[idx].store(std::max(std::max(v, cur - drop), floorV), std::memory_order_relaxed); };
    upd(0, linToDb(pk), -120); upd(1, linToDb(pkL2), -120); upd(2, linToDb(pkR2), -120);
    upd(3, gGr, 0); upd(4, cGr, 0); upd(5, lOn ? -linToDb(minG) : 0.f, 0);
    { float mp = 0.f; if (micMix) for (int i = 0; i < n; i++) mp = std::max(mp, std::fabs(micBuf[i])); const float v = micMix ? linToDb(mp) : -120.f, cur = micPk.load(std::memory_order_relaxed); micPk.store(std::max(std::max(v, cur - drop), -120.f), std::memory_order_relaxed); }   // channel 2 input meter (own slot: M[8] is the CPU load)
    { const float c1 = pkOut1.load(std::memory_order_relaxed), c2 = pkOut2.load(std::memory_order_relaxed);   // output meters of channel 1 (guitar) / channel 2 (mic)
      pkOut1.store(std::max(std::max(linToDb(pg1), c1 - drop), -120.f), std::memory_order_relaxed); pkOut2.store(std::max(std::max(linToDb(pgM), c2 - drop), -120.f), std::memory_order_relaxed); }
    M[6].store(linToDb(inPk), std::memory_order_relaxed); M[7].store(linToDb(outPk), std::memory_order_relaxed);
}

static inline void enableFtz() {
#if defined(__aarch64__)
    uint64_t fpcr; asm volatile("mrs %0, fpcr" : "=r"(fpcr)); fpcr |= (1ull << 24); asm volatile("msr fpcr, %0" :: "r"(fpcr));
#elif defined(__SSE__) || defined(_M_X64)
    _mm_setcsr(_mm_getcsr() | 0x8040);   // flush-to-zero + denormals-are-zero: no CPU spikes in reverb / filter tails
#endif
}

void Engine::onAudio(void* data, const void* input, uint32_t frames) {
    float* out = (float*)data; const int oc = outCh, ic = std::max(inCh, 1); const int n = (int)frames;
    if (!running || !input) { std::fill(out, out + (size_t)n * oc, 0.f); return; }
    static thread_local bool ftzDone = false; if (!ftzDone) { enableFtz(); ftzDone = true; }
    auto t0 = std::chrono::steady_clock::now();
    const float* in = (const float*)input;
    if (discard > 0) {          // let the device settle after (re)start
        discard--;
        std::fill(out, out + (size_t)n * oc, 0.f);
        return;
    }
    int done = 0;
    while (done < n) {
        const int c = std::min<int>(CHUNK, n - done);
        const float* src = in + (size_t)done * ic;
        const bool micUse = micUsing.load(std::memory_order_relaxed) && micWant.load(std::memory_order_relaxed) && ic >= 2;
        const float micPre = micUse ? faderLin(micP[MIC_PRE].load(std::memory_order_relaxed)) : 1.f;   // input 2 level (fader -50..+3 dB)
        for (int i = 0; i < c; i++) { mono[i] = src[i * ic]; micBuf[i] = micUse ? micSoft(src[i * ic + 1] * micPre) : 0.f; }
        processBlock(bufL.data(), bufR.data(), c);
        for (int i = 0; i < c; i++) {
            float* f = out + (size_t)(done + i) * oc;
            f[0] = bufL[i]; if (oc > 1) f[1] = bufR[i]; for (int k = 2; k < oc; k++) f[k] = 0.f;
        }
        done += c;
    }
    const float dt = std::chrono::duration<float>(std::chrono::steady_clock::now() - t0).count(), budget = n / fsA.load();
    cpu = cpu * 0.9f + 0.1f * (dt / budget); M[8].store(cpu * 100.f, std::memory_order_relaxed);
    if (dt > budget) xrunA.store(xrunA.load(std::memory_order_relaxed) + 1.f, std::memory_order_relaxed);   // callback took longer than its period = probable dropout
}

void Engine::getMeters(float* out) {
    for (int i = 0; i < 9; i++) out[i] = M[i].load(std::memory_order_relaxed);
    const float xr = xrunA.load(std::memory_order_relaxed), lat = latMs.load(std::memory_order_relaxed);
    out[9] = xr; out[10] = namLoaded ? 1.f : 0.f; out[11] = (irLoaded[0] || irLoaded[1]) ? 1.f : 0.f;
    out[12] = running ? 1.f : 0.f; out[13] = fsA.load(); out[14] = lat; out[15] = (float)burstA.load();
    out[16] = micPk.load(std::memory_order_relaxed);                       // channel 2 (microphone) input level, dB
    out[17] = pkOut1.load(std::memory_order_relaxed); out[18] = pkOut2.load(std::memory_order_relaxed);   // output level of channel 1 / channel 2, dB
}

void Engine::getStatus(float* out) {
    for (int i = 0; i < 16; i++) out[i] = 0.f;
    out[0] = (float)lpState.load(); out[1] = (float)lpPosSec.load(); out[2] = (float)lpLenSec.load();
    out[3] = (float)plPosSec.load(); out[4] = (float)plDurSec.load(); out[5] = plPlay.load() ? 1.f : 0.f;
    out[6] = (float)recDropped(); out[7] = harmSemis.load(); out[8] = namActive && namActive->rs ? 1.f : 0.f;
    out[9] = micUsing.load() ? 1.f : 0.f;
    out[10] = (float)metSeq.load(std::memory_order_relaxed); out[11] = (float)metIx.load(std::memory_order_relaxed);   // metronome beat counter / beat index
}

// ---------------------------------------------------------------------------- player / recorder
void Engine::playerBegin(int sr, int ch, int64_t reserveFrames) {
    delete pdBuild; pdBuild = new PlayerData(); pdBuild->sr = sr; pdBuild->ch = std::clamp(ch, 1, 2);
    pdBuild->pcm.reserve((size_t)std::min<int64_t>(std::max<int64_t>(reserveFrames, 0), (int64_t)sr * 600) * pdBuild->ch);
}
void Engine::playerPush(const int16_t* d, int count) {
    if (!pdBuild || count <= 0) return;
    const size_t cap = (size_t)pdBuild->sr * 600 * pdBuild->ch;          // maks. 10 menit
    if (pdBuild->pcm.size() + (size_t)count > cap) count = (int)(cap - pdBuild->pcm.size());
    if (count > 0) pdBuild->pcm.insert(pdBuild->pcm.end(), d, d + count);
}
bool Engine::playerEnd(bool keep) {
    if (!pdBuild) return false;
    pdBuild->frames = (int64_t)(pdBuild->pcm.size() / pdBuild->ch);
    const bool ok = pdBuild->frames > 0;
    plKeep.store(keep && ok);
    PlayerData* old = pdPend.exchange(pdBuild); delete old; pdBuild = nullptr;
    if (!keep) plPlay.store(false);
    delete pdGar.exchange(nullptr);
    return ok;
}
void Engine::playerClear() { delete pdBuild; pdBuild = new PlayerData(); playerEnd(); }
void Engine::playerCtl(int what, double v) {
    switch (what) {
        case 0: plPlay.store(v > 0.5); break;
        case 1: plTempo.store((float)std::clamp(v, 0.5, 1.5)); break;
        case 2: plGain.store(dbToLin((float)v)); break;
        case 3: plRoute.store((int)v); break;
        case 4: plLoopOn.store(v > 0.5); break;
        case 5: plLoopA.store(v); break;
        case 6: plLoopB.store(v); break;
        case 7: plSeek.store((int64_t)(std::max(v, 0.0) * (pdAct ? pdAct->sr : fsA.load()))); break;
    }
}
void Engine::recStart(bool di, bool mix) {
    if (di && recDI.b.empty()) recDI.init(1u << 21);
    if (mix && recMix.b.empty()) recMix.init(1u << 22);
    if (di) { recDI.w = 0; recDI.r = 0; recDI.dropped = 0; }
    if (mix) { recMix.w = 0; recMix.r = 0; recMix.dropped = 0; }
    recDiOn.store(di); recMixOn.store(mix);
}

void Engine::getSpectrum(float* out, int bands) {
    const int N = 2048; std::vector<std::complex<float>> a(N);
    int w = anaW.load(std::memory_order_acquire);
    for (int i = 0; i < N; i++) {
        float win = 0.5f - 0.5f * std::cos(2 * PI_F * i / (N - 1));
        a[i] = ana[(w - N + i + 8192) & 8191] * win;
    }
    fft.run(a);
    float fs = fsA.load();
    for (int b = 0; b < bands; b++) {
        float f0 = 20.f * std::pow(1000.f, (float)b / bands), f1 = 20.f * std::pow(1000.f, (float)(b + 1) / bands);
        int k0 = std::clamp((int)(f0 * N / fs), 1, N / 2 - 1), k1 = std::clamp((int)std::ceil(f1 * N / fs), k0 + 1, N / 2);
        float m = 0; for (int k = k0; k < k1; k++) m = std::max(m, std::abs(a[k]));
        out[b] = linToDb(m / (N / 4.f));
    }
}

void Engine::getEqResponse(float* out, int n) {
    static const int types[5] = {0, 1, 1, 1, 2}; float fs = fsA.load();
    Biquad q[5]; const int ob = eqRespBank.load() * PARAM_BANK;
    for (int b = 0; b < 5; b++) q[b].set(types[b], fs, P[ob + EQ_B0 + b * 3].load(), P[ob + EQ_B0 + b * 3 + 1].load(), P[ob + EQ_B0 + b * 3 + 2].load());
    bool on = P[ob + EQ_ON].load() > 0.5f;
    for (int i = 0; i < n; i++) {
        float f = 20.f * std::pow(1000.f, (float)i / (n - 1)), s = 0;
        if (on) for (int b = 0; b < 5; b++) s += q[b].magDb(fs, f);
        out[i] = s;
    }
}

// Deteksi pitch YIN pada 2048 sampel terakhir sinyal mentah (thread non-audio).
bool Engine::detectPitch(float* out) {
    out[0] = 0.f; out[1] = 0.f; out[2] = -120.f;
    if (!running) return false;
    const int N = 2048, W = 1024;
    std::vector<float> x(N);
    const int w = tunW.load(std::memory_order_acquire);
    double e = 0; for (int i = 0; i < N; i++) { x[i] = tun[(w - N + i + 8192) & 8191]; e += (double)x[i] * x[i]; }
    const float rms = (float)std::sqrt(e / N); out[2] = linToDb(rms);
    if (rms < 0.003f) return false;
    const float fs = fsA.load();
    const int tauMax = std::min(N - W - 1, (int)(fs / 45.f)), tauMin = std::max(2, (int)(fs / 1400.f));
    std::vector<float> cm(tauMax + 2, 1.f);
    double run = 0;
    for (int tau = 1; tau <= tauMax; tau++) {
        float d = 0; const float* a = x.data(); const float* b = x.data() + tau;
        for (int j = 0; j < W; j++) { float df = a[j] - b[j]; d += df * df; }
        run += d; cm[tau] = run > 1e-12 ? (float)(d * tau / run) : 1.f;
    }
    int best = -1;
    for (int t = tauMin; t < tauMax; t++) if (cm[t] < 0.15f) { while (t + 1 < tauMax && cm[t + 1] < cm[t]) t++; best = t; break; }
    if (best < 0) return false;
    float s0 = cm[best - 1], s1 = cm[best], s2 = cm[best + 1], den = 2.f * (2.f * s1 - s2 - s0);
    float tau = (float)best + (std::fabs(den) > 1e-9f ? (s2 - s0) / den : 0.f);
    out[0] = fs / tau; out[1] = std::clamp(1.f - s1, 0.f, 1.f);
    return true;
}
void Engine::getPitch(float* out) {
    out[0] = 0.f; out[1] = 0.f; out[2] = -120.f;
    if (!tunOn.load()) return;
    detectPitch(out);
}
// Harmonizer: deteksi nada ±30 ms sekali, tentukan interval diatonik untuk voice kedua.
void Engine::harmLoop() {
    static const int STEPS[8] = {2, 3, 4, 5, 7, -2, -4, -7};
    while (harmRun.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
        if (!harmWant.load()) continue;
        float o[3];
        if (detectPitch(o) && o[1] > 0.8f && o[0] > 30.f) {
            const int midi = (int)std::lround(69.0 + 12.0 * std::log2((double)o[0] / 440.0));
            const int key = std::clamp((int)(P[HARM_KEY].load() + 0.5f), 0, 11), minor = P[HARM_SCALE].load() > 0.5f ? 1 : 0;
            const int st = STEPS[std::clamp((int)(P[HARM_INT].load() + 0.5f), 0, 7)];
            harmSemis.store((float)std::clamp(harmOffset(midi, key, minor, st), -24, 24), std::memory_order_relaxed);
        }
    }
}

// ---------------------------------------------------------------------------- audio files (WAV / MP3 / FLAC) -> backing-track player
#ifdef _WIN32
static std::wstring widenUtf8(const std::string& s) {
    if (s.empty()) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    std::wstring w((size_t)n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], n);
    return w;
}
#endif

static bool openDecoder(const std::string& path, ma_decoder& dec, ma_uint32 channels) {
    ma_decoder_config dc = ma_decoder_config_init(ma_format_s16, channels, 0);   // 0 = keep the file's own channel count / sample rate
#ifdef _WIN32
    const std::wstring w = widenUtf8(path);
    return ma_decoder_init_file_w(w.c_str(), &dc, &dec) == MA_SUCCESS;
#else
    return ma_decoder_init_file(path.c_str(), &dc, &dec) == MA_SUCCESS;
#endif
}

double Engine::probeDuration(const std::string& path) {
    ma_decoder dec;
    if (!openDecoder(path, dec, 0)) return 0.0;
    ma_uint64 total = 0; ma_decoder_get_length_in_pcm_frames(&dec, &total);
    const double sr = dec.outputSampleRate > 0 ? (double)dec.outputSampleRate : 48000.0;
    ma_decoder_uninit(&dec);
    return (double)total / sr;
}

double Engine::decodeFile(const std::string& path, int limitSec, bool keep) {
    ma_decoder dec;
    if (!openDecoder(path, dec, 0)) return -1.0;
    if (dec.outputChannels > 2) { ma_decoder_uninit(&dec); if (!openDecoder(path, dec, 2)) return -1.0; }   // surround -> stereo
    const int ch = (int)dec.outputChannels, sr = (int)dec.outputSampleRate;
    if (ch < 1 || sr < 8000) { ma_decoder_uninit(&dec); return -1.0; }
    ma_uint64 total = 0; ma_decoder_get_length_in_pcm_frames(&dec, &total);
    const int64_t maxFrames = (int64_t)sr * limitSec;
    playerBegin(sr, ch, total > 0 ? std::min<int64_t>((int64_t)total, maxFrames) : 0);
    std::vector<int16_t> buf((size_t)4096 * ch);
    int64_t done = 0;
    while (done < maxFrames && !decodeCancel.load()) {
        ma_uint64 got = 0;
        const ma_uint64 want = (ma_uint64)std::min<int64_t>(4096, maxFrames - done);
        const ma_result r = ma_decoder_read_pcm_frames(&dec, buf.data(), want, &got);
        if (got > 0) { playerPush(buf.data(), (int)(got * ch)); done += (int64_t)got; }
        if (r != MA_SUCCESS || got == 0) break;
    }
    ma_decoder_uninit(&dec);
    if (decodeCancel.load()) return -1.0;
    return playerEnd(keep) ? (double)done / sr : -1.0;
}
