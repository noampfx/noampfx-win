#pragma once
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

/**
 * One VST3 effect slot (stereo in / stereo out) hosted inside the audio engine.
 *   position 0 = after the whole chain (stereo, before the looper / limiter)   1 = before the chain (mono guitar in, mono out)
 * Real hosting code is compiled only when the VST3 SDK is present (CMake option NOAMP_VST3, see README_WINDOWS.md);
 * otherwise every call is a harmless stub and load() returns an explanation.
 */
class PluginHost {
public:
    struct Impl;      // defined in plugin_host.cpp (needs the VST3 SDK)
    static PluginHost& get();
    static bool compiled();

    /** "path|display name" for every *.vst3 found in the standard folders (+ [extraDirs], ';' separated). Does not load anything. */
    std::vector<std::string> scan(const std::string& extraDirs);
    /** "" on success, otherwise a message for the user. */
    std::string load(const std::string& path);
    void unload();
    std::string name();                     // "" when nothing is loaded
    bool hasEditor();
    int latencySamples();

    void setOn(bool on) { wantOn.store(on); }
    void setPosition(int pos) { position.store(pos); }
    void setTempo(double bpm) { tempo.store(bpm); }
    bool isOn() const { return wantOn.load(); }

    /** Audio thread. In place. Called by the engine once before and once after the chain; each call checks the configured position. */
    void processPost(float* L, float* R, int n);
    void processPre(float* x, int n);
    /** Engine (re)start: set sample rate / maximum block size. */
    void prepare(double sampleRate, int maxBlock);

    bool openEditor();
    void closeEditor();
    bool editorOpen();

    std::string getState();                 // base64, "" if nothing loaded
    bool setState(const std::string& b64);
    bool takeDirty() { return dirty.exchange(false); }

    std::atomic<bool> dirty{false};
private:
    std::atomic<bool> wantOn{true};
    std::atomic<int> position{0};
    std::atomic<double> tempo{120.0};
    std::atomic<Impl*> impl{nullptr};
    std::mutex mtx;
    double lastSr = 48000.0;
};
