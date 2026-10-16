#include <jni.h>
#include <string>
#include "engine.h"

#define JF(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_noamp_fx_Engine_##name

static std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr); std::string r(c ? c : ""); if (c) env->ReleaseStringUTFChars(s, c); return r;
}

JF(jboolean, nativeStart)(JNIEnv*, jobject, jint in, jint out, jint mode, jfloat ms, jint sr, jint micDev) { return Engine::get().start(in, out, mode, ms, sr, micDev); }
JF(jobjectArray, nativeListDevices)(JNIEnv* env, jobject, jboolean input) {
    auto v = Engine::get().listDevices(input);
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)v.size(), sc, nullptr);
    for (size_t i = 0; i < v.size(); i++) env->SetObjectArrayElement(arr, (jsize)i, env->NewStringUTF(v[i].c_str()));
    return arr;
}
JF(jstring, nativeLastError)(JNIEnv* env, jobject) { return env->NewStringUTF(Engine::get().lastError().c_str()); }
JF(jdouble, nativeProbeDuration)(JNIEnv* env, jobject, jstring path) { return Engine::get().probeDuration(jstr(env, path)); }
JF(jdouble, nativeDecodeFile)(JNIEnv* env, jobject, jstring path, jint limitSec, jboolean keep) { return Engine::get().decodeFile(jstr(env, path), limitSec, keep); }
JF(void, nativeSetDecodeCancel)(JNIEnv*, jobject, jboolean c) { Engine::get().setDecodeCancel(c); }
JF(void, nativeSetPan)(JNIEnv*, jobject, jint ch, jfloat v) { Engine::get().setPan(ch, v); }
JF(void, nativeSetPitchLowLat)(JNIEnv*, jobject, jboolean on) { Engine::get().setPitchLowLat(on); }
JF(void, nativeSetBigCore)(JNIEnv*, jobject, jboolean on) { Engine::get().setBigCore(on); }
JF(void, nativeSetMic)(JNIEnv*, jobject, jboolean on, jfloat db) { Engine::get().setMic(on, db); }
JF(void, nativeSetMicParam)(JNIEnv*, jobject, jint i, jfloat v) { Engine::get().setMicParam(i, v); }
JF(void, nativeSetBus)(JNIEnv*, jobject, jfloat db) { Engine::get().setBus(db); }
JF(void, nativeSetBufferMs)(JNIEnv*, jobject, jfloat ms) { Engine::get().setBufferMs(ms); }
JF(void, nativeStop)(JNIEnv*, jobject) { Engine::get().stop(); }
JF(jboolean, nativeIsRunning)(JNIEnv*, jobject) { return Engine::get().isRunning(); }
JF(void, nativeSetParam)(JNIEnv*, jobject, jint i, jfloat v) { Engine::get().setParam(i, v); }
JF(void, nativeSetEqBank)(JNIEnv*, jobject, jint b) { Engine::get().setEqBank(b); }
JF(jstring, nativeLoadNam)(JNIEnv* env, jobject, jstring path) {
    const char* c = env->GetStringUTFChars(path, nullptr); std::string s(c); env->ReleaseStringUTFChars(path, c);
    return env->NewStringUTF(Engine::get().loadNam(s).c_str());
}
JF(void, nativeClearNam)(JNIEnv*, jobject) { Engine::get().clearNam(); }
JF(void, nativeSetIr)(JNIEnv* env, jobject, jint slot, jfloatArray arr) {
    jsize n = env->GetArrayLength(arr); jfloat* d = env->GetFloatArrayElements(arr, nullptr);
    Engine::get().setIr(slot, d, n); env->ReleaseFloatArrayElements(arr, d, JNI_ABORT);
}
JF(void, nativeClearIr)(JNIEnv*, jobject, jint slot) { Engine::get().clearIr(slot); }
JF(void, nativeGetMeters)(JNIEnv* env, jobject, jfloatArray arr) {
    float t[19]; Engine::get().getMeters(t); env->SetFloatArrayRegion(arr, 0, 19, t);
}
JF(void, nativeGetStatus)(JNIEnv* env, jobject, jfloatArray arr) {
    float t[16]; Engine::get().getStatus(t); env->SetFloatArrayRegion(arr, 0, 16, t);
}
JF(void, nativeGetSpectrum)(JNIEnv* env, jobject, jfloatArray arr) {
    int n = env->GetArrayLength(arr); std::vector<float> t(n); Engine::get().getSpectrum(t.data(), n); env->SetFloatArrayRegion(arr, 0, n, t.data());
}
JF(void, nativeGetEq)(JNIEnv* env, jobject, jfloatArray arr) {
    int n = env->GetArrayLength(arr); std::vector<float> t(n); Engine::get().getEqResponse(t.data(), n); env->SetFloatArrayRegion(arr, 0, n, t.data());
}
JF(void, nativeSetTuner)(JNIEnv*, jobject, jboolean on, jboolean mute) { Engine::get().setTuner(on, mute); }
JF(void, nativeGetPitch)(JNIEnv* env, jobject, jfloatArray arr) { float t[3]; Engine::get().getPitch(t); env->SetFloatArrayRegion(arr, 0, 3, t); }

// ---- backing track / reamp
JF(void, nativePlayerBegin)(JNIEnv*, jobject, jint sr, jint ch, jlong frames) { Engine::get().playerBegin(sr, ch, frames); }
JF(void, nativePlayerPush)(JNIEnv* env, jobject, jshortArray arr, jint count) {
    jshort* d = env->GetShortArrayElements(arr, nullptr); Engine::get().playerPush((const int16_t*)d, count); env->ReleaseShortArrayElements(arr, d, JNI_ABORT);
}
JF(jboolean, nativePlayerEnd)(JNIEnv*, jobject, jboolean keep) { return Engine::get().playerEnd(keep); }
JF(void, nativePlayerClear)(JNIEnv*, jobject) { Engine::get().playerClear(); }
JF(void, nativePlayerCtl)(JNIEnv*, jobject, jint what, jdouble v) { Engine::get().playerCtl(what, v); }
// ---- looper / metronome / recorder
JF(void, nativeLooperCmd)(JNIEnv*, jobject, jint c) { Engine::get().looperCmd(c); }
JF(void, nativeLooperLevel)(JNIEnv*, jobject, jfloat db) { Engine::get().looperLevel(db); }
JF(void, nativeMetroSet)(JNIEnv*, jobject, jboolean on, jfloat bpm, jint beats, jfloat db, jint sub) { Engine::get().metroSet(on, bpm, beats, db, sub); }
JF(void, nativeRecStart)(JNIEnv*, jobject, jboolean di, jboolean mix) { Engine::get().recStart(di, mix); }
JF(void, nativeRecStop)(JNIEnv*, jobject) { Engine::get().recStop(); }
JF(jint, nativeRecRead)(JNIEnv* env, jobject, jint kind, jfloatArray arr) {
    jsize n = env->GetArrayLength(arr); jfloat* d = env->GetFloatArrayElements(arr, nullptr);
    int r = Engine::get().recRead(kind, d, n); env->ReleaseFloatArrayElements(arr, d, 0); return r;
}
JF(jint, nativeRecDropped)(JNIEnv*, jobject) { return Engine::get().recDropped(); }

// ---------------------------------------------------------------- ASIO + VST3
#include "plugin_host.h"
static jobjectArray toJArray(JNIEnv* env, const std::vector<std::string>& v) {
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)v.size(), sc, nullptr);
    for (size_t i = 0; i < v.size(); i++) env->SetObjectArrayElement(arr, (jsize)i, env->NewStringUTF(v[i].c_str()));
    return arr;
}
JF(jboolean, nativeAsioCompiled)(JNIEnv*, jobject) { return Engine::asioCompiled(); }
JF(jobjectArray, nativeAsioDrivers)(JNIEnv* env, jobject) { return toJArray(env, Engine::get().asioDrivers()); }
JF(jstring, nativeAsioProbe)(JNIEnv* env, jobject, jint drv) { return env->NewStringUTF(Engine::get().asioProbe(drv).c_str()); }
JF(jboolean, nativeStartAsio)(JNIEnv*, jobject, jint drv, jint sr, jint buf, jint inCh, jint outCh) { return Engine::get().startAsio(drv, sr, buf, inCh, outCh); }
JF(void, nativeAsioControlPanel)(JNIEnv*, jobject) { Engine::get().asioControlPanel(); }

JF(jboolean, nativeVstCompiled)(JNIEnv*, jobject) { return PluginHost::compiled(); }
JF(jobjectArray, nativeVstScan)(JNIEnv* env, jobject, jstring extra) { return toJArray(env, PluginHost::get().scan(jstr(env, extra))); }
JF(jstring, nativeVstLoad)(JNIEnv* env, jobject, jstring path) { return env->NewStringUTF(PluginHost::get().load(jstr(env, path)).c_str()); }
JF(void, nativeVstUnload)(JNIEnv*, jobject) { PluginHost::get().unload(); }
JF(jstring, nativeVstName)(JNIEnv* env, jobject) { return env->NewStringUTF(PluginHost::get().name().c_str()); }
JF(jint, nativeVstLatency)(JNIEnv*, jobject) { return PluginHost::get().latencySamples(); }
JF(void, nativeVstSetOn)(JNIEnv*, jobject, jboolean on) { PluginHost::get().setOn(on); }
JF(void, nativeVstSetPosition)(JNIEnv*, jobject, jint pos) { PluginHost::get().setPosition(pos); }
JF(jboolean, nativeVstOpenEditor)(JNIEnv*, jobject) { return PluginHost::get().openEditor(); }
JF(void, nativeVstCloseEditor)(JNIEnv*, jobject) { PluginHost::get().closeEditor(); }
JF(jboolean, nativeVstEditorOpen)(JNIEnv*, jobject) { return PluginHost::get().editorOpen(); }
JF(jstring, nativeVstGetState)(JNIEnv* env, jobject) { return env->NewStringUTF(PluginHost::get().getState().c_str()); }
JF(jboolean, nativeVstSetState)(JNIEnv* env, jobject, jstring st) { return PluginHost::get().setState(jstr(env, st)); }
JF(jboolean, nativeVstTakeDirty)(JNIEnv*, jobject) { return PluginHost::get().takeDirty(); }
