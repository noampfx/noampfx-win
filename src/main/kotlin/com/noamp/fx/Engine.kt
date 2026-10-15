package com.noamp.fx

import java.io.File

/** JNI bridge to the C++ audio engine (miniaudio / WASAPI + NAM core), noamp.dll. */
object Engine {
    /** Null when the native library loaded; otherwise the reason (shown on a start-up error screen). */
    val loadError: String? = try {
        val dir = System.getProperty("compose.application.resources.dir")
        val dll = dir?.let { File(it, "noamp.dll") }
        if (dll != null && dll.exists()) System.load(dll.absolutePath) else System.loadLibrary("noamp")
        null
    } catch (t: Throwable) { t.message ?: t.toString() }

    /** inId / outId: id from [listDevices] (0 = system default). micDev is unused on the desktop (input 2 = channel 2 of the interface). */
    external fun nativeStart(inId: Int, outId: Int, mode: Int, bufMs: Float, sampleRate: Int, micDev: Int): Boolean
    /** Entries "id|name". */
    external fun nativeListDevices(input: Boolean): Array<String>
    external fun nativeLastError(): String
    /** Balance: ch 0 = Out channel 1, 1 = Out channel 2, 2 = Stereo Out; v = -1 (L) .. 0 (C) .. +1 (R). */
    external fun nativeSetPan(ch: Int, v: Float)
    external fun nativeSetPitchLowLat(on: Boolean)
    external fun nativeSetBigCore(on: Boolean)
    external fun nativeSetMic(on: Boolean, db: Float)
    external fun nativeSetBus(db: Float)
    external fun nativeSetMicParam(i: Int, v: Float)
    external fun nativeSetBufferMs(ms: Float)
    external fun nativeStop()
    external fun nativeIsRunning(): Boolean
    external fun nativeSetParam(i: Int, v: Float)
    external fun nativeSetEqBank(b: Int)
    external fun nativeLoadNam(path: String): String
    external fun nativeClearNam()
    external fun nativeSetIr(slot: Int, data: FloatArray)
    external fun nativeClearIr(slot: Int)
    external fun nativeGetMeters(out: FloatArray)
    external fun nativeGetStatus(out: FloatArray)
    external fun nativeGetSpectrum(out: FloatArray)
    external fun nativeGetEq(out: FloatArray)
    external fun nativeSetTuner(on: Boolean, mute: Boolean)
    external fun nativeGetPitch(out: FloatArray)
    // backing track / reamp
    external fun nativePlayerBegin(sr: Int, ch: Int, frames: Long)
    external fun nativePlayerPush(data: ShortArray, count: Int)
    external fun nativePlayerEnd(keep: Boolean): Boolean
    external fun nativePlayerClear()
    /** what: 0 play · 1 tempo · 2 level dB · 3 route (0 mix, 1 reamp) · 4 loop on · 5 loop A (s) · 6 loop B (s) · 7 seek (s) */
    external fun nativePlayerCtl(what: Int, v: Double)
    // audio files (WAV / MP3 / FLAC) decoded natively
    external fun nativeProbeDuration(path: String): Double
    /** Decodes up to [limitSec] seconds into the player. Returns the decoded seconds, or < 0 on failure / cancel. */
    external fun nativeDecodeFile(path: String, limitSec: Int, keep: Boolean): Double
    external fun nativeSetDecodeCancel(c: Boolean)
    // looper / metronome / recorder
    external fun nativeLooperCmd(c: Int)
    external fun nativeLooperLevel(db: Float)
    external fun nativeMetroSet(on: Boolean, bpm: Float, beats: Int, db: Float, sub: Int)
    external fun nativeRecStart(di: Boolean, mix: Boolean)
    external fun nativeRecStop()
    external fun nativeRecRead(kind: Int, out: FloatArray): Int
    external fun nativeRecDropped(): Int
}
