// ASIO host for the NoAmp engine. The Steinberg ASIO SDK headers are NOT part of this project: put the SDK into third_party/asiosdk
// (asio.h, asiosys.h, iasiodrv.h ... ) and configure CMake with -DNOAMP_ASIO=ON, which defines NOAMP_HAS_ASIO.
#include "engine.h"
#include <cstdio>
#include <cstring>
#include <string>

#ifndef NOAMP_HAS_ASIO
// ------------------------------------------------------------------------------------------------ stubs (built without the ASIO SDK)
bool Engine::asioCompiled() { return false; }
std::vector<std::string> Engine::asioDrivers() { return {}; }
std::string Engine::asioProbe(int) { return "ERR|This build has no ASIO support. Put the ASIO SDK into third_party/asiosdk and rebuild (see README_WINDOWS.md)."; }
bool Engine::startAsio(int, int, int, int, int) { std::lock_guard<std::mutex> el(errMtx); err = "This build has no ASIO support (see README_WINDOWS.md)."; return false; }
void Engine::asioControlPanel() {}
void Engine::asioStopDevice() {}
#else
// ------------------------------------------------------------------------------------------------ real ASIO host
#include <windows.h>
#include <objbase.h>
#include <condition_variable>
#include <functional>
#include <future>
#include <thread>
#include "asiosys.h"
#include "asio.h"
#include "iasiodrv.h"
#include "plugin_host.h"

namespace {

// ASIO drivers are COM objects that expect a single-threaded apartment with a message loop: every driver call runs on this thread.
struct Worker {
    std::thread th; DWORD tid = 0; std::mutex m; std::condition_variable cv; bool ready = false;
    void ensure() {
        if (th.joinable()) return;
        th = std::thread([this] {
            MSG msg; PeekMessageW(&msg, nullptr, WM_USER, WM_USER, PM_NOREMOVE);
            CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
            { std::lock_guard<std::mutex> lk(m); tid = GetCurrentThreadId(); ready = true; }
            cv.notify_all();
            while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
                if (msg.message == WM_APP) { auto* f = (std::function<void()>*)msg.lParam; (*f)(); delete f; }
                else { TranslateMessage(&msg); DispatchMessageW(&msg); }
            }
            CoUninitialize();
        });
        std::unique_lock<std::mutex> lk(m); cv.wait(lk, [this] { return ready; });
    }
    void run(std::function<void()> fn) {
        ensure();
        std::promise<void> done; auto fut = done.get_future();
        auto* task = new std::function<void()>([&] { try { fn(); } catch (...) { } done.set_value(); });
        PostThreadMessageW(tid, WM_APP, 0, (LPARAM)task);
        fut.get();
    }
};
Worker& worker() { static Worker* w = new Worker();  return *w; }      // never destroyed: the thread simply ends with the process

struct Entry { std::string name; CLSID clsid; };
std::vector<Entry> listRegistry() {
    std::vector<Entry> out; HKEY k;
    if (RegOpenKeyExA(HKEY_LOCAL_MACHINE, "SOFTWARE\\ASIO", 0, KEY_READ, &k) != ERROR_SUCCESS) return out;
    for (DWORD i = 0;; i++) {
        char name[256]; DWORD n = sizeof name;
        if (RegEnumKeyExA(k, i, name, &n, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
        HKEY s; if (RegOpenKeyExA(k, name, 0, KEY_READ, &s) != ERROR_SUCCESS) continue;
        char cl[128] = {}; DWORD sz = sizeof cl, type = 0;
        if (RegQueryValueExA(s, "CLSID", nullptr, &type, (LPBYTE)cl, &sz) == ERROR_SUCCESS && type == REG_SZ) {
            wchar_t w[128]; MultiByteToWideChar(CP_ACP, 0, cl, -1, w, 128); CLSID c;
            if (CLSIDFromString(w, &c) == NOERROR) out.push_back({ name, c });
        }
        RegCloseKey(s);
    }
    RegCloseKey(k); return out;
}

// state of the open driver (touched on the worker thread; the audio callback only reads what start() set before d->start())
struct Drv {
    IASIO* d = nullptr; int index = -1; HWND hwnd = nullptr; bool started = false, outReady = false;
    long inCh = 0, outCh = 0, minB = 0, maxB = 0, prefB = 0, gran = 0, bufSize = 0;
    ASIOBufferInfo infos[16]; ASIOSampleType types[16]; int nIn = 0, nOut = 0;
    ASIOCallbacks cb{}; Engine* eng = nullptr; std::vector<float> inter, outInter; std::string info;
} g;

bool supportedType(ASIOSampleType t) {
    return t == ASIOSTInt16LSB || t == ASIOSTInt24LSB || t == ASIOSTInt32LSB || t == ASIOSTFloat32LSB || t == ASIOSTFloat64LSB ||
           t == ASIOSTInt32LSB16 || t == ASIOSTInt32LSB18 || t == ASIOSTInt32LSB20 || t == ASIOSTInt32LSB24;
}
void toFloat(ASIOSampleType t, const void* src, int n, float* dst, int stride) {
    switch (t) {
    case ASIOSTInt16LSB: { auto* s = (const int16_t*)src; for (int i = 0; i < n; i++) dst[i * stride] = s[i] * (1.f / 32768.f); } break;
    case ASIOSTInt24LSB: { auto* s = (const uint8_t*)src; for (int i = 0; i < n; i++) { int32_t v = (s[3 * i] | s[3 * i + 1] << 8 | s[3 * i + 2] << 16); if (v & 0x800000) v |= ~0xFFFFFF; dst[i * stride] = v * (1.f / 8388608.f); } } break;
    case ASIOSTInt32LSB: { auto* s = (const int32_t*)src; for (int i = 0; i < n; i++) dst[i * stride] = (float)(s[i] * (1.0 / 2147483648.0)); } break;
    case ASIOSTFloat32LSB: { auto* s = (const float*)src; for (int i = 0; i < n; i++) dst[i * stride] = s[i]; } break;
    case ASIOSTFloat64LSB: { auto* s = (const double*)src; for (int i = 0; i < n; i++) dst[i * stride] = (float)s[i]; } break;
    case ASIOSTInt32LSB16: { auto* s = (const int32_t*)src; for (int i = 0; i < n; i++) dst[i * stride] = s[i] * (1.f / 32768.f); } break;
    case ASIOSTInt32LSB18: { auto* s = (const int32_t*)src; for (int i = 0; i < n; i++) dst[i * stride] = s[i] * (1.f / 131072.f); } break;
    case ASIOSTInt32LSB20: { auto* s = (const int32_t*)src; for (int i = 0; i < n; i++) dst[i * stride] = s[i] * (1.f / 524288.f); } break;
    case ASIOSTInt32LSB24: { auto* s = (const int32_t*)src; for (int i = 0; i < n; i++) dst[i * stride] = s[i] * (1.f / 8388608.f); } break;
    default: for (int i = 0; i < n; i++) dst[i * stride] = 0.f;
    }
}
inline float clip1(float v) { return v > 1.f ? 1.f : v < -1.f ? -1.f : v; }
void fromFloat(ASIOSampleType t, void* dst, int n, const float* src, int stride) {
    switch (t) {
    case ASIOSTInt16LSB: { auto* d = (int16_t*)dst; for (int i = 0; i < n; i++) d[i] = (int16_t)(clip1(src[i * stride]) * 32767.f); } break;
    case ASIOSTInt24LSB: { auto* d = (uint8_t*)dst; for (int i = 0; i < n; i++) { int32_t v = (int32_t)(clip1(src[i * stride]) * 8388607.f); d[3 * i] = (uint8_t)v; d[3 * i + 1] = (uint8_t)(v >> 8); d[3 * i + 2] = (uint8_t)(v >> 16); } } break;
    case ASIOSTInt32LSB: { auto* d = (int32_t*)dst; for (int i = 0; i < n; i++) d[i] = (int32_t)(clip1(src[i * stride]) * 2147483520.f); } break;
    case ASIOSTFloat32LSB: { auto* d = (float*)dst; for (int i = 0; i < n; i++) d[i] = src[i * stride]; } break;
    case ASIOSTFloat64LSB: { auto* d = (double*)dst; for (int i = 0; i < n; i++) d[i] = src[i * stride]; } break;
    case ASIOSTInt32LSB16: { auto* d = (int32_t*)dst; for (int i = 0; i < n; i++) d[i] = (int32_t)(clip1(src[i * stride]) * 32767.f); } break;
    case ASIOSTInt32LSB18: { auto* d = (int32_t*)dst; for (int i = 0; i < n; i++) d[i] = (int32_t)(clip1(src[i * stride]) * 131071.f); } break;
    case ASIOSTInt32LSB20: { auto* d = (int32_t*)dst; for (int i = 0; i < n; i++) d[i] = (int32_t)(clip1(src[i * stride]) * 524287.f); } break;
    case ASIOSTInt32LSB24: { auto* d = (int32_t*)dst; for (int i = 0; i < n; i++) d[i] = (int32_t)(clip1(src[i * stride]) * 8388607.f); } break;
    default: break;
    }
}
int bytesOf(ASIOSampleType t) { return t == ASIOSTInt16LSB ? 2 : t == ASIOSTInt24LSB ? 3 : t == ASIOSTFloat64LSB ? 8 : 4; }

// ---- driver callbacks (they run on the driver's own audio thread; there is no user pointer, hence the global)
void cbSwitch(long idx, ASIOBool) {
    Engine* e = g.eng; if (!e || !g.started) return;
    const int n = (int)g.bufSize;
    for (int k = 0; k < g.nIn; k++) toFloat(g.types[k], g.infos[k].buffers[idx], n, g.inter.data() + k, g.nIn);
    e->onAudio(g.outInter.data(), g.inter.data(), (uint32_t)n);
    for (int k = 0; k < g.nOut; k++) {
        const int src = g.nOut == 1 ? 0 : k;                    // a single output channel gets the left side
        fromFloat(g.types[g.nIn + k], g.infos[g.nIn + k].buffers[idx], n, g.outInter.data() + src, 2);
    }
    if (g.outReady) g.d->outputReady();
}
ASIOTime* cbSwitchTime(ASIOTime* t, long idx, ASIOBool direct) { cbSwitch(idx, direct); return t; }
void cbRate(ASIOSampleRate) {}
long cbMessage(long selector, long value, void*, double*) {
    switch (selector) {
    case kAsioSelectorSupported: return (value == kAsioEngineVersion || value == kAsioResetRequest || value == kAsioBufferSizeChange || value == kAsioResyncRequest || value == kAsioLatenciesChanged) ? 1 : 0;
    case kAsioEngineVersion: return 2;
    case kAsioResetRequest: case kAsioBufferSizeChange: if (g.eng) g.eng->onDeviceStopped(); return 1;    // reopen the driver (settings were changed in its control panel)
    case kAsioResyncRequest: case kAsioLatenciesChanged: return 1;
    case kAsioSupportsTimeInfo: case kAsioSupportsTimeCode: return 0;
    }
    return 0;
}

// ---- helpers that run on the worker thread
void stopWorker() {
    if (!g.d) return;
    if (g.started) { g.started = false; g.d->stop(); }
    g.d->disposeBuffers(); g.d->Release(); g.d = nullptr; g.index = -1; g.nIn = g.nOut = 0;
}
bool openWorker(int idx, std::string& why) {
    if (g.d && g.index == idx) return true;
    stopWorker();
    auto list = listRegistry();
    if (idx < 0 || idx >= (int)list.size()) { why = "ASIO driver not found."; return false; }
    IASIO* d = nullptr;
    HRESULT hr = CoCreateInstance(list[(size_t)idx].clsid, nullptr, CLSCTX_INPROC_SERVER, list[(size_t)idx].clsid, (void**)&d);
    if (FAILED(hr) || !d) { why = "Could not create the ASIO driver '" + list[(size_t)idx].name + "' (is it a 32-bit driver?)."; return false; }
    if (!g.hwnd) g.hwnd = CreateWindowExA(0, "STATIC", "NoAmpASIO", 0, 0, 0, 0, 0, nullptr, nullptr, GetModuleHandleA(nullptr), nullptr);
    if (!d->init(g.hwnd)) { char m[256] = {}; d->getErrorMessage(m); d->Release(); why = std::string("The ASIO driver could not start: ") + (m[0] ? m : "is the device connected?"); return false; }
    g.d = d; g.index = idx;
    d->getChannels(&g.inCh, &g.outCh); d->getBufferSize(&g.minB, &g.maxB, &g.prefB, &g.gran);
    return true;
}
std::string describe() {
    long li = 0, lo = 0; g.d->getLatencies(&li, &lo);
    std::string s = "OK|" + std::to_string(g.minB) + "|" + std::to_string(g.maxB) + "|" + std::to_string(g.prefB) + "|" + std::to_string(g.gran) + "|" + std::to_string(li) + "|" + std::to_string(lo) + "|";
    const double rates[] = { 44100, 48000, 88200, 96000, 176400, 192000 }; bool first = true;
    for (double r : rates) if (g.d->canSampleRate(r) == ASE_OK) { if (!first) s += ","; s += std::to_string((int)r); first = false; }
    s += "|";
    for (int dir = 1; dir >= 0; dir--) {
        const long cnt = dir ? g.inCh : g.outCh;
        for (long i = 0; i < cnt && i < 64; i++) {
            ASIOChannelInfo ci; std::memset(&ci, 0, sizeof ci); ci.channel = i; ci.isInput = dir ? ASIOTrue : ASIOFalse;
            std::string nm = g.d->getChannelInfo(&ci) == ASE_OK ? ci.name : "channel " + std::to_string(i + 1);
            s += (i ? "\t" : "") + nm;
        }
        if (dir) s += "|";
    }
    return s;
}
}  // namespace

bool Engine::asioCompiled() { return true; }

std::vector<std::string> Engine::asioDrivers() {
    std::vector<std::string> out; auto l = listRegistry();
    for (size_t i = 0; i < l.size(); i++) out.push_back(std::to_string(i) + "|" + l[i].name);
    return out;
}

std::string Engine::asioProbe(int drv) {
    std::string res;
    std::lock_guard<std::mutex> lk(mtx);
    worker().run([&] {
        if (g.started && g.index != drv) { res = "ERR|Stop the engine first: another ASIO driver is in use."; return; }
        if (g.started && g.index == drv) { res = g.info; return; }
        std::string why; if (!openWorker(drv, why)) { res = "ERR|" + why; return; }
        res = describe(); g.info = res; stopWorker();            // do not keep the driver open: other programs may need it
    });
    return res;
}

void Engine::asioControlPanel() {
    worker().run([&] { if (g.d) g.d->controlPanel(); else { std::string why; if (openWorker(lastAsioDrv, why)) { g.d->controlPanel(); } } });
}

void Engine::asioStopDevice() {
    if (backend.load() != 1 && !g.d) return;
    worker().run([&] { stopWorker(); g.eng = nullptr; });
}

bool Engine::startAsio(int drv, int sampleRate, int bufFrames, int firstIn, int firstOut) {
    std::lock_guard<std::mutex> lk(mtx);
    if (sampleRate != 44100 && sampleRate != 48000 && sampleRate != 88200 && sampleRate != 96000 && sampleRate != 176400 && sampleRate != 192000) sampleRate = 48000;
    lastBackend = 1; lastAsioDrv = drv; lastAsioBuf = bufFrames; lastAsioIn = firstIn; lastAsioOut = firstOut; lastSr = sampleRate; xrunA.store(0.f);
    { std::lock_guard<std::mutex> el(errMtx); err.clear(); }
    closeDevicesLocked();
    std::string why; bool ok = false; double actualSr = sampleRate; long li = 0, lo = 0;
    worker().run([&] {
        if (!openWorker(drv, why)) return;
        IASIO* d = g.d;
        const int wantIn = micWant.load() ? 2 : 1;
        g.nIn = (int)std::min<long>(wantIn, g.inCh - firstIn); g.nOut = (int)std::min<long>(2, g.outCh - firstOut);
        if (g.nIn < 1 || g.nOut < 1) { why = "The chosen input / output channel does not exist on this driver."; stopWorker(); return; }
        if (d->canSampleRate((ASIOSampleRate)sampleRate) != ASE_OK) { why = "The driver does not support " + std::to_string(sampleRate) + " Hz."; stopWorker(); return; }
        d->setSampleRate((ASIOSampleRate)sampleRate);
        ASIOSampleRate got = 0; if (d->getSampleRate(&got) == ASE_OK && got > 1000) actualSr = got;
        long bs = bufFrames > 0 ? bufFrames : g.prefB;
        if (g.gran == 0) bs = g.prefB; else bs = std::max(g.minB, std::min(g.maxB, bs));
        for (int k = 0; k < g.nIn; k++) { g.infos[k].isInput = ASIOTrue; g.infos[k].channelNum = firstIn + k; g.infos[k].buffers[0] = g.infos[k].buffers[1] = nullptr; }
        for (int k = 0; k < g.nOut; k++) { g.infos[g.nIn + k].isInput = ASIOFalse; g.infos[g.nIn + k].channelNum = firstOut + k; g.infos[g.nIn + k].buffers[0] = g.infos[g.nIn + k].buffers[1] = nullptr; }
        g.cb.bufferSwitch = cbSwitch; g.cb.sampleRateDidChange = cbRate; g.cb.asioMessage = cbMessage; g.cb.bufferSwitchTimeInfo = cbSwitchTime;
        if (d->createBuffers(g.infos, g.nIn + g.nOut, bs, &g.cb) != ASE_OK) { why = "The driver refused this buffer size. Try another one."; stopWorker(); return; }
        g.bufSize = bs;
        for (int k = 0; k < g.nIn + g.nOut; k++) {
            ASIOChannelInfo ci; std::memset(&ci, 0, sizeof ci); ci.channel = g.infos[k].channelNum; ci.isInput = g.infos[k].isInput; d->getChannelInfo(&ci);
            g.types[k] = ci.type;
            if (!supportedType(ci.type)) { why = "The driver uses a sample format that is not supported (type " + std::to_string((int)ci.type) + ")."; stopWorker(); return; }
        }
        g.inter.assign((size_t)bs * (size_t)g.nIn, 0.f); g.outInter.assign((size_t)bs * 2, 0.f);
        g.outReady = d->outputReady() == ASE_OK;
        d->getLatencies(&li, &lo);
        g.info = describe();
        ok = true;
    });
    if (!ok) { std::lock_guard<std::mutex> el(errMtx); err = why; return false; }

    outCh = 2;                        // engine side: always stereo out
    inCh = g.nIn;
    micUsing.store(micWant.load() && g.nIn >= 2);
    burstA.store((int)g.bufSize);
    latMs.store((float)((li + lo) * 1000.0 / actualSr));
    fsA.store((float)actualSr);
    prepare((float)actualSr);
    PluginHost::get().prepare(actualSr, CHUNK);
    backend = 1; discard = 4;
    running = true;
    bool started = false;
    worker().run([&] { g.eng = this; g.started = true; started = g.d->start() == ASE_OK; if (!started) g.started = false; });
    if (!started) { running = false; worker().run([&] { stopWorker(); }); std::lock_guard<std::mutex> el(errMtx); err = "The ASIO driver could not be started."; return false; }
    startHarmThread();
    return true;
}
#endif
