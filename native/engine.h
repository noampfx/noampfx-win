#pragma once
#include <algorithm>
#include <cstdint>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "params.h"
#include "media.h"
#include "micfx.h"

namespace nam { class DSP; }
struct ma_device;

constexpr int CHUNK = 1024, IR_MAX = 8192;

/** Satu model NAM + resampler otomatis bila rate model (mis. 48 kHz) berbeda dari rate engine (mis. 44.1 kHz). */
struct NamSlot {
    nam::DSP* dsp = nullptr; double rate = 48000; bool rs = false;
    RatResampler up, down; std::vector<float> a, b, d, tmp;
    float fifo[4096] = {}; int fr = 0, fw = 0, fc = 0;
    ~NamSlot();
    void configure(double fs);
    void run(float* io, int n);
};

class Engine {
public:
    static Engine& get();
    Engine();
    ~Engine() { harmRun = false; if (harmTh.joinable()) harmTh.join(); }
    /** inId / outId: 0 = system default, otherwise the id from listDevices(). micDev is ignored on desktop (input 2 = channel 2 of the interface). */
    bool start(int inId, int outId, int mode, float bufMs, int sampleRate, int micDev = 0);
    /** Lines "id|name" for every capture (input = true) or playback device. */
    std::vector<std::string> listDevices(bool input);
    std::string lastError() { std::lock_guard<std::mutex> lk(errMtx); return err; }
    void setBigCore(bool on) { (void)on; }
    /** Mic on input channel 2 mixed into the output (guitar stays on channel 1). Needs an engine restart to (re)open a 2-channel input. */
    void setMic(bool on, float db) { micWant.store(on); micLvl.store(faderLin(db)); }
    void setBus(float db) { busLvl.store(faderLin(db)); }
    /** Balance of channel 1 / channel 2 / stereo bus: -1 = full left, 0 = centre, +1 = full right. */
    void setPan(int ch, float v) { if (ch >= 0 && ch < 3) panP[ch].store(std::clamp(v, -1.f, 1.f)); }
    /** Pitch shifter / harmonizer: true = short window (~10 ms latency), false = long window (~26 ms, smoother). */
    void setPitchLowLat(bool on) { pitchLow.store(on); }
    void setMicParam(int i, float v) { if (i >= 0 && i < MIC_PARAM_COUNT) micP[i].store(v); }
    void setBufferMs(float ms) { bufMsA.store(ms); }   // takes effect at the next start
    void stop();
    bool isRunning() const { return running.load(); }
    void setParam(int i, float v) { if (i >= 0 && i < PARAM_TOTAL) P[i].store(v, std::memory_order_relaxed); }
    std::string loadNam(const std::string& path);
    void clearNam();
    void setIr(int slot, const float* d, int n);
    void clearIr(int slot);
    void getMeters(float* out);
    void getStatus(float* out);
    void getSpectrum(float* out, int bands);
    void getEqResponse(float* out, int n);
    void setEqBank(int b) { eqRespBank.store(b < 0 ? 0 : b >= PARAM_BANKS ? PARAM_BANKS - 1 : b); }
    void setTuner(bool on, bool mute) { tunOn.store(on); tunMute.store(mute); }
    void getPitch(float* out);   // out[0]=Hz (0 = none), out[1]=confidence 0..1, out[2]=level dBFS

    // backing track / reamp
    void playerBegin(int sr, int ch, int64_t reserveFrames);
    void playerPush(const int16_t* d, int count);
    bool playerEnd(bool keep = false);      // keep = replace the data of the playing song and continue from the same position (background decode finished)
    void playerClear();
    void playerCtl(int what, double v);   // 0 play(1/0) 1 tempo 2 level dB 3 route 4 loopOn 5 loopA sec 6 loopB sec 7 seek sec
    // looper / metronome / recorder
    void looperCmd(int c) { lpCmd.store(c); }
    void looperLevel(float db) { lpGain.store(dbToLin(db)); }
    void metroSet(bool on, float bpm, int beats, float db, int sub) { metOn.store(on); metBpm.store(bpm); metBeats.store(beats); metLvl.store(dbToLin(db)); metSub.store(sub); }
    void recStart(bool di, bool mix);
    void recStop() { recDiOn.store(false); recMixOn.store(false); }
    int recRead(int kind, float* out, int maxN) { return kind == 0 ? recDI.read(out, maxN) : recMix.read(out, maxN); }
    int recDropped() { return (int)(recDI.dropped.load() + recMix.dropped.load()); }

    // audio file decoding straight into the backing-track player (WAV / MP3 / FLAC)
    double probeDuration(const std::string& path);
    double decodeFile(const std::string& path, int limitSec, bool keep);   // seconds decoded, < 0 on failure or cancel
    void setDecodeCancel(bool c) { decodeCancel.store(c); }

    void onAudio(void* out, const void* in, uint32_t frames);               // called from the audio device thread
    void onDeviceStopped();

private:
    void prepare(float fs);
        void processBlock(float* L, float* R, int n);
    void updateEq(float fs, int bk);
    bool detectPitch(float* out);
    void harmLoop();

    std::atomic<float> P[PARAM_TOTAL];
    std::atomic<float> M[16], S[16];
    std::atomic<bool> running{false};
    std::atomic<int> eqRespBank{0};
    std::atomic<float> fsA{48000.f};
    std::mutex mtx;
    ma_device* dev = nullptr;
    std::mutex errMtx; std::string err; std::atomic<bool> decodeCancel{false};
    std::atomic<float> latMs{0.f}, xrunA{0.f}; std::atomic<bool> stopping{false};
    int devPeriod = 0;
    int inCh = 1, outCh = 2, lastIn = 0, lastOut = 0, lastMic = 0, discard = 0, lastMode = 0, lastSr = 48000;
    std::atomic<float> panP[3] = {{0.f}, {0.f}, {0.f}}; float panS[6] = {1.f, 1.f, 1.f, 1.f, 1.f, 1.f};
    std::atomic<bool> pitchLow{true}; bool pitchLowApplied = true;
        std::atomic<float> bufMsA{0.f}; std::atomic<int> burstA{0};

    // tuner + harmonizer detection
    std::vector<float> tun; std::atomic<int> tunW{0}; std::atomic<bool> tunOn{false}, tunMute{false}, harmWant{false}; float muteG = 1.f;
    std::thread harmTh; std::atomic<bool> harmRun{false}; std::atomic<float> harmSemis{0.f};
    // effects
    // tiap blok bisa ada 3 instance (bank 0 = blok asli, bank 1..2 = blok kembar); harmonizer, NAM dan cab hanya bank 0
    Octaver oct[PARAM_BANKS]; PitchShifter pitchPs[PARAM_BANKS], harmPs; Wah wah[PARAM_BANKS]; Drive drive[PARAM_BANKS];
    Gate gate[PARAM_BANKS]; Compressor comp[PARAM_BANKS]; Modulation mod[PARAM_BANKS]; DelayFx dfx[PARAM_BANKS]; ReverbFx rfx[PARAM_BANKS];
    Biquad eq[PARAM_BANKS][5]; float eqCache[PARAM_BANKS][15]; bool eqInit[PARAM_BANKS] = {false, false, false};
    float enOct[PARAM_BANKS] = {}, enPitch[PARAM_BANKS] = {}, enWah[PARAM_BANKS] = {}, enDrive[PARAM_BANKS] = {};
    float enHarm = 0, enNam = 0, enCab = 0;
    float igPrev = 1, ogPrev = 1, mInPrev = 1, mOutPrev = 1;
    // NAM (dengan crossfade antar-model)
    std::atomic<NamSlot*> namPending{nullptr}, namGarbage{nullptr};
    NamSlot* namActive = nullptr; NamSlot* namOld = nullptr; int namFadePos = 0;
    std::atomic<bool> namLoaded{false}, namClearReq{false};
    std::mutex loadMtx;
    // Cab: dua slot IR, konvolusi partisi
    std::atomic<ConvIR*> irPend[2], irGar[2];
    ConvIR* irAct[2] = {nullptr, nullptr}; ConvState cs[2]; float sg[2] = {1.f, 1.f};
    std::atomic<bool> irClr[2], irLoaded[2];
    // limiter
    float laBuf[2][64] = {}; int laPos = 0; float limG = 1;
    // analysis
    std::vector<float> ana; std::atomic<int> anaW{0};
    std::vector<float> sideBuf, mono, bufL, bufR, dryBuf, oldBuf, wetA, wetB, plL, plR, ibuf;
    MicFx micFx; std::atomic<float> micP[MIC_PARAM_COUNT]; std::vector<float> micL, micR; std::atomic<float> micPk{-120.f}, pkOut1{-120.f}, pkOut2{-120.f}, busLvl{1.f}; float busGs = 1.f;   // pkOut1/2 = channel 1 / channel 2 output level (dB), busLvl = master bus gain   // micPk = channel 2 input level (dB)
    std::vector<float> micBuf; std::atomic<bool> micWant{false}, micUsing{false}; std::atomic<float> micLvl{1.f}; float micGs = 1.f;
    float pkIn = 0, pkL = 0, pkR = 0; float cpu = 0;
    FFT fft{2048};
    // player / looper / metronome / recorder
    std::atomic<bool> plKeep{false}; std::atomic<PlayerData*> pdPend{nullptr}, pdGar{nullptr}; PlayerData* pdAct = nullptr; PlayerData* pdBuild = nullptr; BackingPlayer bp;
    std::atomic<bool> plPlay{false}, plLoopOn{false}; std::atomic<float> plTempo{1.f}, plGain{1.f}; std::atomic<int> plRoute{0};
    std::atomic<double> plLoopA{0}, plLoopB{0}, plPosSec{0}, plDurSec{0}; std::atomic<int64_t> plSeek{-1};
    Looper loop; std::atomic<int> lpCmd{0}; std::atomic<float> lpGain{1.f}; std::atomic<int> lpState{0}; std::atomic<double> lpPosSec{0}, lpLenSec{0};
    Metronome metro; std::atomic<bool> metOn{false}; std::atomic<float> metBpm{100.f}, metLvl{0.5f}; std::atomic<int> metBeats{4}, metSub{0}, metSeq{0}, metIx{0};
    Ring recDI, recMix; std::atomic<bool> recDiOn{false}, recMixOn{false}; std::vector<float> recTmp;
};
