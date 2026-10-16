#include "plugin_host.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <thread>

PluginHost& PluginHost::get() { static PluginHost h; return h; }

#ifndef NOAMP_HAS_VST3
// ------------------------------------------------------------------------------------------------ stubs (built without the VST3 SDK)
static const char* kNoSdk = "This build has no VST3 support. Put the VST3 SDK into third_party/vst3sdk and rebuild (see README_WINDOWS.md).";
bool PluginHost::compiled() { return false; }
std::vector<std::string> PluginHost::scan(const std::string&) { return {}; }
std::string PluginHost::load(const std::string&) { return kNoSdk; }
void PluginHost::unload() {}
std::string PluginHost::name() { return ""; }
bool PluginHost::hasEditor() { return false; }
int PluginHost::latencySamples() { return 0; }
void PluginHost::processPost(float*, float*, int) {}
void PluginHost::processPre(float*, int) {}
void PluginHost::prepare(double, int) {}
bool PluginHost::openEditor() { return false; }
void PluginHost::closeEditor() {}
bool PluginHost::editorOpen() { return false; }
std::string PluginHost::getState() { return ""; }
bool PluginHost::setState(const std::string&) { return false; }
#else
// ------------------------------------------------------------------------------------------------ real VST3 hosting
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <filesystem>
#include <future>
#include <map>
#include "pluginterfaces/base/ftypes.h"
#include "pluginterfaces/base/funknown.h"
#include "pluginterfaces/base/ipluginbase.h"
#include "pluginterfaces/base/ibstream.h"
#include "pluginterfaces/gui/iplugview.h"
#include "pluginterfaces/vst/ivstcomponent.h"
#include "pluginterfaces/vst/ivstaudioprocessor.h"
#include "pluginterfaces/vst/ivsteditcontroller.h"
#include "pluginterfaces/vst/ivsthostapplication.h"
#include "pluginterfaces/vst/ivstmessage.h"
#include "pluginterfaces/vst/ivstparameterchanges.h"
#include "pluginterfaces/vst/ivstevents.h"
#include "pluginterfaces/vst/ivstprocesscontext.h"
#include "pluginterfaces/vst/vstspeaker.h"

using namespace Steinberg;
using namespace Steinberg::Vst;
namespace fsys = std::filesystem;

#ifndef kVstAudioEffectClass
#define kVstAudioEffectClass "Audio Module Class"
#endif

namespace {

std::wstring wide(const std::string& s) {
    if (s.empty()) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    std::wstring w((size_t)n, L'\0'); MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], n); return w;
}
std::string utf8(const std::wstring& w) {
    if (w.empty()) return "";
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s((size_t)n, '\0'); WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], n, nullptr, nullptr); return s;
}
const char* B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
std::string b64enc(const std::vector<uint8_t>& d) {
    std::string o; o.reserve((d.size() + 2) / 3 * 4);
    for (size_t i = 0; i < d.size(); i += 3) {
        uint32_t v = d[i] << 16 | (i + 1 < d.size() ? d[i + 1] << 8 : 0) | (i + 2 < d.size() ? d[i + 2] : 0);
        o += B64[v >> 18 & 63]; o += B64[v >> 12 & 63]; o += i + 1 < d.size() ? B64[v >> 6 & 63] : '='; o += i + 2 < d.size() ? B64[v & 63] : '=';
    }
    return o;
}
std::vector<uint8_t> b64dec(const std::string& s) {
    std::vector<uint8_t> o; uint32_t v = 0; int bits = 0;
    for (char c : s) {
        const char* p = std::strchr(B64, c); if (c == '=' || c == 0 || !p) continue;
        v = v << 6 | (uint32_t)(p - B64); bits += 6;
        if (bits >= 8) { bits -= 8; o.push_back((uint8_t)(v >> bits & 0xFF)); }
    }
    return o;
}

// ---- tiny FUnknown helpers (objects owned by the slot never delete themselves; heap objects handed to plugins do)
#define NA_STATIC_RC uint32 PLUGIN_API addRef() override { return 2; } uint32 PLUGIN_API release() override { return 1; }
#define NA_HEAP_RC std::atomic<uint32> rc_{1}; \
    uint32 PLUGIN_API addRef() override { return ++rc_; } \
    uint32 PLUGIN_API release() override { uint32 r = --rc_; if (r == 0) delete this; return r; }

class MemStream : public IBStream {
public:
    std::vector<uint8_t> data; int64 pos = 0;
    NA_STATIC_RC
    tresult PLUGIN_API queryInterface(const TUID _iid, void** obj) override {
        QUERY_INTERFACE(_iid, obj, FUnknown::iid, IBStream)
        QUERY_INTERFACE(_iid, obj, IBStream::iid, IBStream)
        *obj = nullptr; return kNoInterface;
    }
    tresult PLUGIN_API read(void* buffer, int32 numBytes, int32* numBytesRead) override {
        int64 n = std::min<int64>(numBytes, (int64)data.size() - pos); if (n < 0) n = 0;
        if (n > 0) std::memcpy(buffer, data.data() + pos, (size_t)n);
        pos += n; if (numBytesRead) *numBytesRead = (int32)n; return kResultOk;
    }
    tresult PLUGIN_API write(void* buffer, int32 numBytes, int32* numBytesWritten) override {
        if (pos + numBytes > (int64)data.size()) data.resize((size_t)(pos + numBytes));
        std::memcpy(data.data() + pos, buffer, (size_t)numBytes); pos += numBytes;
        if (numBytesWritten) *numBytesWritten = numBytes; return kResultOk;
    }
    tresult PLUGIN_API seek(int64 p, int32 mode, int64* result) override {
        int64 np = mode == IBStream::kIBSeekSet ? p : mode == IBStream::kIBSeekCur ? pos + p : (int64)data.size() + p;
        if (np < 0) return kResultFalse; pos = np; if (result) *result = pos; return kResultOk;
    }
    tresult PLUGIN_API tell(int64* p) override { if (p) *p = pos; return kResultOk; }
};

// ---- IAttributeList / IMessage (plugins use them to talk between their component and controller)
class AttrList : public IAttributeList {
    struct V { int kind = 0; int64 i = 0; double f = 0; std::vector<char16> s; std::vector<uint8_t> b; };
    std::map<std::string, V> m;
public:
    NA_HEAP_RC
    tresult PLUGIN_API queryInterface(const TUID _iid, void** obj) override {
        QUERY_INTERFACE(_iid, obj, FUnknown::iid, IAttributeList)
        QUERY_INTERFACE(_iid, obj, IAttributeList::iid, IAttributeList)
        *obj = nullptr; return kNoInterface;
    }
    tresult PLUGIN_API setInt(AttrID id, int64 v) override { V& x = m[id]; x.kind = 0; x.i = v; return kResultOk; }
    tresult PLUGIN_API getInt(AttrID id, int64& v) override { auto it = m.find(id); if (it == m.end() || it->second.kind != 0) return kResultFalse; v = it->second.i; return kResultOk; }
    tresult PLUGIN_API setFloat(AttrID id, double v) override { V& x = m[id]; x.kind = 1; x.f = v; return kResultOk; }
    tresult PLUGIN_API getFloat(AttrID id, double& v) override { auto it = m.find(id); if (it == m.end() || it->second.kind != 1) return kResultFalse; v = it->second.f; return kResultOk; }
    tresult PLUGIN_API setString(AttrID id, const TChar* s) override {
        V& x = m[id]; x.kind = 2; x.s.clear(); while (*s) x.s.push_back(*s++); x.s.push_back(0); return kResultOk;
    }
    tresult PLUGIN_API getString(AttrID id, TChar* s, uint32 bytes) override {
        auto it = m.find(id); if (it == m.end() || it->second.kind != 2) return kResultFalse;
        uint32 cap = bytes / sizeof(TChar); if (cap == 0) return kResultFalse;
        uint32 n = std::min<uint32>(cap, (uint32)it->second.s.size()); std::memcpy(s, it->second.s.data(), n * sizeof(TChar)); s[n - 1] = 0; return kResultOk;
    }
    tresult PLUGIN_API setBinary(AttrID id, const void* d, uint32 bytes) override {
        V& x = m[id]; x.kind = 3; x.b.assign((const uint8_t*)d, (const uint8_t*)d + bytes); return kResultOk;
    }
    tresult PLUGIN_API getBinary(AttrID id, const void*& d, uint32& bytes) override {
        auto it = m.find(id); if (it == m.end() || it->second.kind != 3) return kResultFalse;
        d = it->second.b.data(); bytes = (uint32)it->second.b.size(); return kResultOk;
    }
};

class Message : public IMessage {
    std::string id; AttrList* attrs = new AttrList();
public:
    ~Message() { attrs->release(); }
    NA_HEAP_RC
    tresult PLUGIN_API queryInterface(const TUID _iid, void** obj) override {
        QUERY_INTERFACE(_iid, obj, FUnknown::iid, IMessage)
        QUERY_INTERFACE(_iid, obj, IMessage::iid, IMessage)
        *obj = nullptr; return kNoInterface;
    }
    FIDString PLUGIN_API getMessageID() override { return id.c_str(); }
    void PLUGIN_API setMessageID(FIDString i) override { id = i ? i : ""; }
    IAttributeList* PLUGIN_API getAttributes() override { return attrs; }
};

class HostApp : public IHostApplication {
public:
    NA_STATIC_RC
    tresult PLUGIN_API queryInterface(const TUID _iid, void** obj) override {
        QUERY_INTERFACE(_iid, obj, FUnknown::iid, IHostApplication)
        QUERY_INTERFACE(_iid, obj, IHostApplication::iid, IHostApplication)
        *obj = nullptr; return kNoInterface;
    }
    tresult PLUGIN_API getName(String128 name) override {
        const char16 t[] = { 'N', 'o', 'A', 'm', 'p', ' ', 'F', 'X', 0 };
        for (int i = 0; i < 9; i++) name[i] = t[i];
        return kResultOk;
    }
    tresult PLUGIN_API createInstance(TUID cid, TUID _iid, void** obj) override {
        if (FUnknownPrivate::iidEqual(cid, IMessage::iid) && FUnknownPrivate::iidEqual(_iid, IMessage::iid)) { *obj = static_cast<IMessage*>(new Message()); return kResultOk; }
        if (FUnknownPrivate::iidEqual(cid, IAttributeList::iid) && FUnknownPrivate::iidEqual(_iid, IAttributeList::iid)) { *obj = static_cast<IAttributeList*>(new AttrList()); return kResultOk; }
        *obj = nullptr; return kResultFalse;
    }
};

// ---- parameter changes (no allocation on the audio thread)
class ParamQueue : public IParamValueQueue {
public:
    ParamID id = 0; int cnt = 0; int32 off[8]; ParamValue val[8];
    NA_STATIC_RC
    tresult PLUGIN_API queryInterface(const TUID _iid, void** obj) override {
        QUERY_INTERFACE(_iid, obj, FUnknown::iid, IParamValueQueue)
        QUERY_INTERFACE(_iid, obj, IParamValueQueue::iid, IParamValueQueue)
        *obj = nullptr; return kNoInterface;
    }
    ParamID PLUGIN_API getParameterId() override { return id; }
    int32 PLUGIN_API getPointCount() override { return cnt; }
    tresult PLUGIN_API getPoint(int32 i, int32& o, ParamValue& v) override { if (i < 0 || i >= cnt) return kResultFalse; o = off[i]; v = val[i]; return kResultOk; }
    tresult PLUGIN_API addPoint(int32 o, ParamValue v, int32& index) override {
        if (cnt >= 8) { val[7] = v; index = 7; return kResultOk; }     // keep the newest value when a plugin floods one parameter
        off[cnt] = o; val[cnt] = v; index = cnt++; return kResultOk;
    }
};
class ParamChanges : public IParameterChanges {
public:
    static constexpr int MAXQ = 96; ParamQueue q[MAXQ]; int n = 0;
    NA_STATIC_RC
    tresult PLUGIN_API queryInterface(const TUID _iid, void** obj) override {
        QUERY_INTERFACE(_iid, obj, FUnknown::iid, IParameterChanges)
        QUERY_INTERFACE(_iid, obj, IParameterChanges::iid, IParameterChanges)
        *obj = nullptr; return kNoInterface;
    }
    void clear() { for (int i = 0; i < n; i++) q[i].cnt = 0; n = 0; }
    int32 PLUGIN_API getParameterCount() override { return n; }
    IParamValueQueue* PLUGIN_API getParameterData(int32 i) override { return i >= 0 && i < n ? &q[i] : nullptr; }
    IParamValueQueue* PLUGIN_API addParameterData(const ParamID& id, int32& index) override {
        for (int i = 0; i < n; i++) if (q[i].id == id) { index = i; return &q[i]; }
        if (n >= MAXQ) return nullptr;
        q[n].id = id; q[n].cnt = 0; index = n; return &q[n++];
    }
};

class EventList : public IEventList {
public:
    NA_STATIC_RC
    tresult PLUGIN_API queryInterface(const TUID _iid, void** obj) override {
        QUERY_INTERFACE(_iid, obj, FUnknown::iid, IEventList)
        QUERY_INTERFACE(_iid, obj, IEventList::iid, IEventList)
        *obj = nullptr; return kNoInterface;
    }
    int32 PLUGIN_API getEventCount() override { return 0; }
    tresult PLUGIN_API getEvent(int32, Event&) override { return kResultFalse; }
    tresult PLUGIN_API addEvent(Event&) override { return kResultFalse; }
};

struct Module {
    HMODULE h = nullptr; IPluginFactory* factory = nullptr;
    typedef bool(PLUGIN_API* DllFn)(); typedef IPluginFactory*(PLUGIN_API* FactoryFn)();
    DllFn exitFn = nullptr;
    static fsys::path binaryOf(const fsys::path& p) {
        if (fsys::is_directory(p)) {
            fsys::path c = p / "Contents" / "x86_64-win" / (p.stem().wstring() + L".vst3");
            if (fsys::exists(c)) return c;
            std::error_code ec; for (auto& e : fsys::directory_iterator(p / "Contents" / "x86_64-win", ec)) if (e.path().extension() == ".vst3") return e.path();
        }
        return p;
    }
    std::string open(const fsys::path& p) {
        const fsys::path bin = binaryOf(p);
        h = LoadLibraryExW(bin.wstring().c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
        if (!h) return "Could not load " + utf8(bin.filename().wstring()) + " (error " + std::to_string(GetLastError()) + "). It may be a 32-bit plug-in or miss a DLL.";
        if (auto init = (DllFn)GetProcAddress(h, "InitDll")) init();
        exitFn = (DllFn)GetProcAddress(h, "ExitDll");
        auto get = (FactoryFn)GetProcAddress(h, "GetPluginFactory");
        if (!get) return "Not a VST3 plug-in (GetPluginFactory is missing).";
        factory = get();
        return factory ? "" : "The plug-in returned no factory.";
    }
    void close() {
        if (factory) { factory->release(); factory = nullptr; }
        if (h) { if (exitFn) exitFn(); FreeLibrary(h); h = nullptr; }
    }
};

LRESULT CALLBACK EditorProc(HWND, UINT, WPARAM, LPARAM);
}  // namespace

// ------------------------------------------------------------------------------------------------ one loaded plug-in
struct PluginHost::Impl : public IComponentHandler, public IPlugFrame {
    HostApp host; ParamChanges pcIn, pcOut; EventList evIn, evOut; ProcessContext ctx{};
    Module mod; IComponent* comp = nullptr; IAudioProcessor* proc = nullptr; IEditController* ctrl = nullptr;
    IConnectionPoint *cpComp = nullptr, *cpCtrl = nullptr; bool separate = false;
    int inCh = 2, outCh = 2, inBuses = 1, outBuses = 1; std::string name; double sr = 48000; int maxBlock = 1024;
    std::vector<float> iL, iR, oL, oR, tmpA, tmpB;
    std::atomic<bool> inProc{false}, ready{false}; float en = 0.f;
    std::mutex qm; std::vector<std::pair<ParamID, ParamValue>> editQ;
    // editor window (own thread + message loop)
    std::thread edTh; HWND hwnd = nullptr; IPlugView* view = nullptr; std::atomic<bool> edOpen{false}; bool viewRemoved = false;

    // FUnknown (the slot owns this object; plug-ins only borrow the interfaces)
    uint32 PLUGIN_API addRef() override { return 2; }
    uint32 PLUGIN_API release() override { return 1; }
    tresult PLUGIN_API queryInterface(const TUID _iid, void** obj) override {
        QUERY_INTERFACE(_iid, obj, FUnknown::iid, IComponentHandler)
        QUERY_INTERFACE(_iid, obj, IComponentHandler::iid, IComponentHandler)
        QUERY_INTERFACE(_iid, obj, IPlugFrame::iid, IPlugFrame)
        *obj = nullptr; return kNoInterface;
    }
    // IComponentHandler: the plug-in's own GUI changes a parameter -> forward it to the audio thread
    tresult PLUGIN_API beginEdit(ParamID) override { return kResultOk; }
    tresult PLUGIN_API performEdit(ParamID id, ParamValue v) override { { std::lock_guard<std::mutex> lk(qm); if (editQ.size() < 512) editQ.emplace_back(id, v); } PluginHost::get().dirty.store(true); return kResultOk; }
    tresult PLUGIN_API endEdit(ParamID) override { PluginHost::get().dirty.store(true); return kResultOk; }
    tresult PLUGIN_API restartComponent(int32) override { PluginHost::get().dirty.store(true); return kResultOk; }
    // IPlugFrame
    tresult PLUGIN_API resizeView(IPlugView* v, ViewRect* r) override {
        if (!hwnd || !r) return kResultFalse;
        RECT rc = { 0, 0, r->getWidth(), r->getHeight() };
        AdjustWindowRect(&rc, (DWORD)GetWindowLongPtrW(hwnd, GWL_STYLE), FALSE);
        SetWindowPos(hwnd, nullptr, 0, 0, rc.right - rc.left, rc.bottom - rc.top, SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
        if (v) v->onSize(r);
        return kResultOk;
    }

    std::string setup(double rate, int block) {
        sr = rate; maxBlock = block;
        ProcessSetup ps; ps.processMode = kRealtime; ps.symbolicSampleSize = kSample32; ps.maxSamplesPerBlock = block; ps.sampleRate = rate;
        if (proc->setupProcessing(ps) != kResultOk) return "The plug-in rejected the sample rate / block size.";
        iL.assign((size_t)block, 0.f); iR = oL = oR = tmpA = tmpB = iL;
        return "";
    }

    // audio thread -----------------------------------------------------------------------------------------
    void run(float* L, float* R, int n, bool wantOn, double bpm) {
        const float target = wantOn ? 1.f : 0.f;
        if (en <= 0.f && target <= 0.f) return;
        for (int off = 0; off < n; off += maxBlock) {
            const int c = std::min(maxBlock, n - off); float* l = L + off; float* r = R + off;
            std::memcpy(iL.data(), l, (size_t)c * sizeof(float)); std::memcpy(iR.data(), r, (size_t)c * sizeof(float));
            if (inCh == 1) for (int i = 0; i < c; i++) iL[(size_t)i] = 0.5f * (iL[(size_t)i] + iR[(size_t)i]);
            float* inP[2] = { iL.data(), iR.data() }; float* outP[2] = { oL.data(), oR.data() };
            AudioBusBuffers ib[2] = {}, ob[2] = {};
            ib[0].numChannels = inCh; ib[0].channelBuffers32 = inP;
            ob[0].numChannels = outCh; ob[0].channelBuffers32 = outP;
            pcIn.clear(); pcOut.clear();
            if (qm.try_lock()) {                                   // never block the audio thread: edits wait for the next block
                for (auto& e : editQ) { int32 idx; if (auto* q = pcIn.addParameterData(e.first, idx)) { int32 pi; q->addPoint(0, e.second, pi); } }
                editQ.clear(); qm.unlock();
            }
            ctx.state = ProcessContext::kTempoValid | ProcessContext::kTimeSigValid | ProcessContext::kPlaying;
            ctx.sampleRate = sr; ctx.tempo = bpm; ctx.timeSigNumerator = 4; ctx.timeSigDenominator = 4;
            ProcessData d; d.processMode = kRealtime; d.symbolicSampleSize = kSample32; d.numSamples = c;
            d.numInputs = std::min(inBuses, 2); d.inputs = ib; d.numOutputs = std::min(outBuses, 2); d.outputs = ob;
            d.inputParameterChanges = &pcIn; d.outputParameterChanges = &pcOut; d.inputEvents = &evIn; d.outputEvents = &evOut; d.processContext = &ctx;
            if (proc->process(d) != kResultOk) return;
            ctx.projectTimeSamples += c;
            const float st = 1.f / 256.f;
            for (int i = 0; i < c; i++) {
                en += std::clamp(target - en, -st, st);
                const float wl = oL[(size_t)i], wr = outCh == 1 ? wl : oR[(size_t)i];
                l[i] = l[i] * (1.f - en) + wl * en; r[i] = r[i] * (1.f - en) + wr * en;
            }
        }
    }

    // editor ---------------------------------------------------------------------------------------------------
    void editorThread(std::promise<std::string>& result) {
        CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        static bool reg = false;
        if (!reg) {
            WNDCLASSW wc = {}; wc.lpfnWndProc = EditorProc; wc.hInstance = GetModuleHandleW(nullptr); wc.lpszClassName = L"NoAmpVstEditor";
            wc.hCursor = LoadCursorW(nullptr, IDC_ARROW); wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH); RegisterClassW(&wc); reg = true;
        }
        view = ctrl->createView(ViewType::kEditor);
        if (!view) { result.set_value("This plug-in has no editor window."); CoUninitialize(); return; }
        if (view->isPlatformTypeSupported(kPlatformTypeHWND) != kResultTrue) { view->release(); view = nullptr; result.set_value("The editor does not support Windows."); CoUninitialize(); return; }
        ViewRect vr; view->getSize(&vr);
        DWORD style = WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX | (view->canResize() == kResultTrue ? (WS_THICKFRAME | WS_MAXIMIZEBOX) : 0);
        RECT rc = { 0, 0, vr.getWidth(), vr.getHeight() }; AdjustWindowRect(&rc, style, FALSE);
        viewRemoved = false;
        hwnd = CreateWindowExW(0, L"NoAmpVstEditor", wide(name).c_str(), style, CW_USEDEFAULT, CW_USEDEFAULT, rc.right - rc.left, rc.bottom - rc.top, nullptr, nullptr, GetModuleHandleW(nullptr), this);
        view->setFrame(this);
        if (!hwnd || view->attached(hwnd, kPlatformTypeHWND) != kResultOk) {
            view->setFrame(nullptr); view->release(); view = nullptr; if (hwnd) DestroyWindow(hwnd); hwnd = nullptr;
            result.set_value("The editor could not be opened."); CoUninitialize(); return;
        }
        edOpen.store(true); ShowWindow(hwnd, SW_SHOW); UpdateWindow(hwnd);
        result.set_value("");
        MSG m; while (GetMessageW(&m, nullptr, 0, 0) > 0) { TranslateMessage(&m); DispatchMessageW(&m); }
        if (view) { if (!viewRemoved) view->removed(); view->setFrame(nullptr); view->release(); view = nullptr; }
        hwnd = nullptr; edOpen.store(false); CoUninitialize();
    }
    void closeEditor() {
        if (hwnd) PostMessageW(hwnd, WM_CLOSE, 0, 0);
        if (edTh.joinable() && std::this_thread::get_id() != edTh.get_id()) edTh.join();
    }
};

namespace {
LRESULT CALLBACK EditorProc(HWND h, UINT msg, WPARAM w, LPARAM l) {
    if (msg == WM_NCCREATE) SetWindowLongPtrW(h, GWLP_USERDATA, (LONG_PTR)((CREATESTRUCTW*)l)->lpCreateParams);
    auto* s = (PluginHost::Impl*)GetWindowLongPtrW(h, GWLP_USERDATA);
    switch (msg) {
    case WM_CLOSE: if (s && s->view && !s->viewRemoved) { s->view->removed(); s->viewRemoved = true; } DestroyWindow(h); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    case WM_SIZE: if (s && s->view && s->viewRemoved == false && w != SIZE_MINIMIZED) { RECT rc; GetClientRect(h, &rc); ViewRect vr = { 0, 0, rc.right, rc.bottom }; s->view->onSize(&vr); } return 0;
    }
    return DefWindowProcW(h, msg, w, l);
}
}  // namespace

// ------------------------------------------------------------------------------------------------ PluginHost API
bool PluginHost::compiled() { return true; }

std::vector<std::string> PluginHost::scan(const std::string& extraDirs) {
    std::vector<fsys::path> dirs; auto env = [](const char* k) { const char* v = std::getenv(k); return v ? std::string(v) : std::string(); };
    if (!env("CommonProgramFiles").empty()) dirs.emplace_back(fsys::path(env("CommonProgramFiles")) / "VST3");
    if (!env("CommonProgramW6432").empty()) dirs.emplace_back(fsys::path(env("CommonProgramW6432")) / "VST3");
    if (!env("LOCALAPPDATA").empty()) dirs.emplace_back(fsys::path(env("LOCALAPPDATA")) / "Programs" / "Common" / "VST3");
    size_t a = 0; while (a < extraDirs.size()) { size_t b = extraDirs.find(';', a); if (b == std::string::npos) b = extraDirs.size(); if (b > a) dirs.emplace_back(fsys::u8path(extraDirs.substr(a, b - a))); a = b + 1; }
    std::map<std::string, std::string> found;      // lower-case path -> line
    for (auto& d : dirs) {
        std::error_code ec; if (!fsys::is_directory(d, ec)) continue;
        for (fsys::recursive_directory_iterator it(d, fsys::directory_options::skip_permission_denied, ec), end; it != end; it.increment(ec)) {
            if (ec) break;
            const fsys::path& p = it->path();
            if (p.extension() == ".vst3") {
                if (it->is_directory(ec)) it.disable_recursion_pending();
                std::string full = utf8(p.wstring()), low = full; std::transform(low.begin(), low.end(), low.begin(), ::tolower);
                found[low] = full + "|" + utf8(p.stem().wstring());
            }
        }
    }
    std::vector<std::string> out; for (auto& kv : found) out.push_back(kv.second);
    std::sort(out.begin(), out.end(), [](const std::string& x, const std::string& y) { return x.substr(x.find('|')) < y.substr(y.find('|')); });
    return out;
}

std::string PluginHost::load(const std::string& path) {
    unload();
    std::lock_guard<std::mutex> lk(mtx);
    auto s = std::make_unique<Impl>();
    auto fail = [&](const std::string& why) { if (s->comp) { s->comp->setActive(false); s->comp->terminate(); s->comp->release(); } if (s->separate && s->ctrl) { s->ctrl->terminate(); s->ctrl->release(); } s->mod.close(); return why; };
    std::string e = s->mod.open(fsys::u8path(path));
    if (!e.empty()) { s->mod.close(); return e; }
    IPluginFactory* f = s->mod.factory; PClassInfo ci; bool found = false;
    for (int32 i = 0; i < f->countClasses(); i++) if (f->getClassInfo(i, &ci) == kResultOk && std::strcmp(ci.category, kVstAudioEffectClass) == 0) { found = true; break; }
    if (!found) return fail("No audio effect found in this file.");
    s->name = ci.name;
    if (f->createInstance(ci.cid, IComponent::iid, (void**)&s->comp) != kResultOk || !s->comp) { s->comp = nullptr; return fail("The plug-in could not be created."); }
    if (s->comp->initialize(&s->host) != kResultOk) return fail("The plug-in failed to initialise.");
    if (s->comp->queryInterface(IAudioProcessor::iid, (void**)&s->proc) != kResultOk || !s->proc) return fail("This is not an audio effect.");
    if (s->proc->canProcessSampleSize(kSample32) != kResultTrue) return fail("The plug-in cannot process 32-bit audio.");
    // edit controller: same object as the component, or a separate class
    if (s->comp->queryInterface(IEditController::iid, (void**)&s->ctrl) != kResultOk || !s->ctrl) {
        s->ctrl = nullptr; TUID cc;
        if (s->comp->getControllerClassId(cc) == kResultOk && f->createInstance(cc, IEditController::iid, (void**)&s->ctrl) == kResultOk && s->ctrl) { s->separate = true; s->ctrl->initialize(&s->host); }
    }
    if (s->ctrl) {
        s->ctrl->setComponentHandler(s.get());
        s->comp->queryInterface(IConnectionPoint::iid, (void**)&s->cpComp); s->ctrl->queryInterface(IConnectionPoint::iid, (void**)&s->cpCtrl);
        if (s->cpComp && s->cpCtrl) { s->cpComp->connect(s->cpCtrl); s->cpCtrl->connect(s->cpComp); }
    }
    // buses: main bus stereo, auxiliary buses (side-chain) off
    s->inBuses = std::max(1, (int)s->comp->getBusCount(kAudio, kInput)); s->outBuses = std::max(1, (int)s->comp->getBusCount(kAudio, kOutput));
    if (s->comp->getBusCount(kAudio, kInput) < 1 || s->comp->getBusCount(kAudio, kOutput) < 1) return fail("This plug-in has no audio input (instrument?). Only effects can be used.");
    std::vector<SpeakerArrangement> ia((size_t)s->inBuses, SpeakerArr::kStereo), oa((size_t)s->outBuses, SpeakerArr::kStereo);
    for (int k = 1; k < s->inBuses; k++) s->proc->getBusArrangement(kInput, k, ia[(size_t)k]);
    for (int k = 1; k < s->outBuses; k++) s->proc->getBusArrangement(kOutput, k, oa[(size_t)k]);
    s->proc->setBusArrangements(ia.data(), s->inBuses, oa.data(), s->outBuses);
    SpeakerArrangement a0 = 0, b0 = 0; s->proc->getBusArrangement(kInput, 0, a0); s->proc->getBusArrangement(kOutput, 0, b0);
    s->inCh = SpeakerArr::getChannelCount(a0); s->outCh = SpeakerArr::getChannelCount(b0);
    if (s->inCh < 1 || s->inCh > 2 || s->outCh < 1 || s->outCh > 2) return fail("Only mono / stereo effects are supported.");
    s->comp->activateBus(kAudio, kInput, 0, true); s->comp->activateBus(kAudio, kOutput, 0, true);
    for (int k = 1; k < s->inBuses; k++) s->comp->activateBus(kAudio, kInput, k, false);
    for (int k = 1; k < s->outBuses; k++) s->comp->activateBus(kAudio, kOutput, k, false);
    const double sr = lastSr > 0 ? lastSr : 48000.0;
    std::string err = s->setup(sr, 1024);
    if (!err.empty()) return fail(err);
    s->comp->setActive(true); s->proc->setProcessing(true);
    s->ready.store(true);
    impl.store(s.release(), std::memory_order_release);
    dirty.store(true);
    return "";
}

void PluginHost::unload() {
    std::lock_guard<std::mutex> lk(mtx);
    Impl* s = impl.exchange(nullptr); if (!s) return;
    s->closeEditor();
    for (int i = 0; i < 2000 && s->inProc.load(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    s->ready.store(false);
    if (s->proc) { s->proc->setProcessing(false); s->proc->release(); }
    if (s->cpComp && s->cpCtrl) { s->cpComp->disconnect(s->cpCtrl); s->cpCtrl->disconnect(s->cpComp); }
    if (s->cpComp) s->cpComp->release(); if (s->cpCtrl) s->cpCtrl->release();
    if (s->comp) s->comp->setActive(false);
    if (s->ctrl) { s->ctrl->setComponentHandler(nullptr); if (s->separate) { s->ctrl->terminate(); } s->ctrl->release(); }
    if (s->comp) { s->comp->terminate(); s->comp->release(); }
    s->mod.close();
    delete s;
}

std::string PluginHost::name() { std::lock_guard<std::mutex> lk(mtx); Impl* s = impl.load(); return s ? s->name : ""; }
bool PluginHost::hasEditor() { std::lock_guard<std::mutex> lk(mtx); Impl* s = impl.load(); return s && s->ctrl; }
int PluginHost::latencySamples() { std::lock_guard<std::mutex> lk(mtx); Impl* s = impl.load(); return s && s->proc ? (int)s->proc->getLatencySamples() : 0; }

void PluginHost::prepare(double sampleRate, int maxBlock) {
    lastSr = sampleRate;
    std::lock_guard<std::mutex> lk(mtx);
    Impl* s = impl.load(); if (!s || (s->sr == sampleRate && s->maxBlock == maxBlock)) return;
    s->ready.store(false);
    for (int i = 0; i < 2000 && s->inProc.load(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    s->proc->setProcessing(false); s->comp->setActive(false);
    s->setup(sampleRate, maxBlock);
    s->comp->setActive(true); s->proc->setProcessing(true);
    s->ready.store(true);
}

static inline void guardedRun(PluginHost::Impl* s, std::atomic<PluginHost::Impl*>& cur, float* L, float* R, int n, bool on, double bpm) {
    s->inProc.store(true);
    if (cur.load() == s && s->ready.load()) s->run(L, R, n, on, bpm);
    s->inProc.store(false);
}
void PluginHost::processPost(float* L, float* R, int n) {
    if (position.load(std::memory_order_relaxed) != 0) return;
    Impl* s = impl.load(std::memory_order_acquire); if (!s) return;
    guardedRun(s, impl, L, R, n, wantOn.load(std::memory_order_relaxed), tempo.load(std::memory_order_relaxed));
}
void PluginHost::processPre(float* x, int n) {
    if (position.load(std::memory_order_relaxed) != 1) return;
    Impl* s = impl.load(std::memory_order_acquire); if (!s) return;
    if ((int)s->tmpA.size() < n) return;
    std::memcpy(s->tmpA.data(), x, (size_t)n * sizeof(float)); std::memcpy(s->tmpB.data(), x, (size_t)n * sizeof(float));
    guardedRun(s, impl, s->tmpA.data(), s->tmpB.data(), n, wantOn.load(std::memory_order_relaxed), tempo.load(std::memory_order_relaxed));
    for (int i = 0; i < n; i++) x[i] = 0.5f * (s->tmpA[(size_t)i] + s->tmpB[(size_t)i]);
}

bool PluginHost::openEditor() {
    std::lock_guard<std::mutex> lk(mtx);
    Impl* s = impl.load(); if (!s || !s->ctrl) return false;
    if (s->edOpen.load()) { if (s->hwnd) { ShowWindow(s->hwnd, SW_RESTORE); SetForegroundWindow(s->hwnd); } return true; }
    if (s->edTh.joinable()) s->edTh.join();
    std::promise<std::string> pr; auto fut = pr.get_future();
    s->edTh = std::thread([s, &pr] { s->editorThread(pr); });
    const bool ok = fut.get().empty();
    return ok;
}
void PluginHost::closeEditor() { std::lock_guard<std::mutex> lk(mtx); Impl* s = impl.load(); if (s) s->closeEditor(); }
bool PluginHost::editorOpen() { Impl* s = impl.load(); return s && s->edOpen.load(); }

std::string PluginHost::getState() {
    std::lock_guard<std::mutex> lk(mtx);
    Impl* s = impl.load(); if (!s) return "";
    MemStream a, b; s->comp->getState(&a); if (s->ctrl) s->ctrl->getState(&b);
    std::vector<uint8_t> blob = { 'N', 'A', 'P', 'V' };
    auto put = [&](const std::vector<uint8_t>& v) { uint32_t n = (uint32_t)v.size(); for (int i = 0; i < 4; i++) blob.push_back((uint8_t)(n >> (8 * i))); blob.insert(blob.end(), v.begin(), v.end()); };
    put(a.data); put(b.data);
    return b64enc(blob);
}
bool PluginHost::setState(const std::string& text) {
    std::lock_guard<std::mutex> lk(mtx);
    Impl* s = impl.load(); if (!s) return false;
    auto blob = b64dec(text); if (blob.size() < 12 || std::memcmp(blob.data(), "NAPV", 4) != 0) return false;
    size_t p = 4; auto get = [&](std::vector<uint8_t>& v) { if (p + 4 > blob.size()) return false; uint32_t n = 0; for (int i = 0; i < 4; i++) n |= (uint32_t)blob[p + (size_t)i] << (8 * i); p += 4; if (p + n > blob.size()) return false; v.assign(blob.begin() + (long)p, blob.begin() + (long)(p + n)); p += n; return true; };
    MemStream a, b; if (!get(a.data) || !get(b.data)) return false;
    if (!a.data.empty()) { s->comp->setState(&a); if (s->ctrl) { a.pos = 0; s->ctrl->setComponentState(&a); } }
    if (!b.data.empty() && s->ctrl) s->ctrl->setState(&b);
    return true;
}
#endif
