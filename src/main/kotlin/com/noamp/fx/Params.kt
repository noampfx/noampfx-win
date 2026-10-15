package com.noamp.fx

import kotlin.math.ln
import kotlin.math.exp

data class PDef(
    val id: Int, val name: String, val min: Float, val max: Float, val def: Float,
    val unit: String = "", val log: Boolean = false, val decimals: Int = 1, val toggle: Boolean = false
) {
    fun toNorm(v: Float): Float {
        val c = v.coerceIn(min, max)
        return if (log) (ln(c / min) / ln(max / min)) else (c - min) / (max - min)
    }
    fun fromNorm(n: Float): Float {
        val c = n.coerceIn(0f, 1f)
        return if (log) min * exp(c * ln(max / min)) else min + c * (max - min)
    }
    fun fmt(v: Float): String = when {
        decimals == 0 -> "%d%s".format(v.toInt(), unit)
        else -> "%.${decimals}f%s".format(v, unit)
    }
}

/** Urutan ID sama persis dengan cpp/params.h */
object P {
    const val GATE_ON = 0; const val GATE_THR = 1; const val GATE_ATK = 2; const val GATE_REL = 3; const val GATE_RANGE = 4
    const val COMP_ON = 5; const val COMP_THR = 6; const val COMP_RATIO = 7; const val COMP_ATK = 8; const val COMP_REL = 9
    const val COMP_MAKEUP = 10; const val COMP_KNEE = 11
    const val NAM_ON = 12; const val NAM_IN = 13; const val NAM_OUT = 14
    const val CAB_ON = 16; const val CAB_LEVEL = 17; const val CAB_MIX = 18
    const val EQ_ON = 19; const val EQ_B0 = 20; const val EQ_OUT = 35
    const val MOD_ON = 36; const val MOD_TYPE = 37; const val MOD_RATE = 38; const val MOD_DEPTH = 39; const val MOD_MIX = 40; const val MOD_FB = 41
    const val DLY_ON = 42; const val DLY_TIME = 43; const val DLY_FB = 44; const val DLY_MIX = 45; const val DLY_TONE = 46
    const val REV_ON = 47; const val REV_SIZE = 48; const val REV_DAMP = 49; const val REV_MIX = 50
    const val LIM_ON = 52; const val LIM_THR = 53; const val LIM_CEIL = 54; const val LIM_REL = 55
    const val MASTER_IN = 57; const val MASTER_OUT = 58
    const val DLY_MODE = 59; const val DLY_DIV = 60; const val DLY_BPM = 61
    const val PITCH_ON = 64; const val PITCH_ST = 65; const val PITCH_FINE = 66; const val PITCH_MIX = 67; const val DRV_ON = 70; const val DRV_TYPE = 71; const val DRV_GAIN = 72; const val DRV_TONE = 73; const val DRV_LEVEL = 74
    const val DLY_TYPE = 75; const val DLY_DUCK = 76; const val REV_TYPE = 77; const val REV_SHIM = 78; const val GATE_POS = 79
    const val WAH_ON = 80; const val WAH_MODE = 81; const val WAH_POS = 82; const val WAH_Q = 83; const val WAH_SENS = 84
    const val OCT_ON = 85; const val OCT_DOWN = 86; const val OCT_UP = 87; const val OCT_DRY = 88
    const val HARM_ON = 89; const val HARM_KEY = 90; const val HARM_SCALE = 91; const val HARM_INT = 92; const val HARM_MIX = 93
    const val CAB2_MIX = 94
    /** 96..111: urutan blok signal chain (entri = bank*16 + kode modul 1..13, 0 = kosong). 112 = versi format chain (0 = preset lama). */
    const val CHAIN0 = 96; const val CHAIN_SLOTS = 8; const val CHAIN_IDS = 16; const val CHAIN_VER = 112; const val CHAIN_MODULES = 13
    /** Modules that can be put in a chain slot (Gate is fixed at the input pot, Limiter at the output pot). */
    val SLOT_MODULES: List<Int> = (2..CHAIN_MODULES).toList()
    /** Parameter banks: bank 0 = original blocks, bank 1..2 = the two extra (duplicate) blocks. Parameter id of bank b = id + b * COUNT_BASE. */
    const val COUNT_BASE = 128; const val BANKS = 3; const val COUNT = COUNT_BASE * BANKS
    /** A chain entry is bank * 16 + module code (1..13). Harmony, NAM and Cab can not be duplicated (shared pitch tracker / loaded model files). */
    fun entryBank(e: Int) = e shr 4
    fun entryModule(e: Int) = e and 15
    fun entry(bank: Int, module: Int) = bank * 16 + module
    val DUPLICABLE: List<Int> = listOf(2, 3, 4, 6, 7, 10, 11, 12, 13)
    fun validEntry(e: Int): Boolean { val b = entryBank(e); val m = entryModule(e); return e > 0 && b < BANKS && m in 2..CHAIN_MODULES && (b == 0 || m in DUPLICABLE) }
    /** ON/bypass parameter of each module (index = module code - 1). */
    val MODULE_ON = intArrayOf(GATE_ON, COMP_ON, OCT_ON, PITCH_ON, HARM_ON, WAH_ON, DRV_ON, NAM_ON, CAB_ON, EQ_ON, MOD_ON, DLY_ON, REV_ON)

    val KEY_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val HARM_INTERVALS = listOf("3rd ↑", "4th ↑", "5th ↑", "6th ↑", "Octave ↑", "3rd ↓", "5th ↓", "Octave ↓")

    val defs: List<PDef> by lazy {
        val m = HashMap<Int, PDef>()
        fun d(id: Int, n: String, mn: Float, mx: Float, df: Float, u: String = "", log: Boolean = false, dec: Int = 1) {
            m[id] = PDef(id, n, mn, mx, df, u, log, dec)
        }
        fun t(id: Int, n: String, df: Float = 1f) { m[id] = PDef(id, n, 0f, 1f, df, toggle = true, decimals = 0) }
        t(GATE_ON, "Gate", 0f); d(GATE_THR, "Threshold", -90f, 0f, -60f, " dB"); d(GATE_ATK, "Attack", 0.1f, 50f, 1f, " ms", true)
        d(GATE_REL, "Release", 5f, 500f, 80f, " ms", true); d(GATE_RANGE, "Range", -90f, 0f, -60f, " dB")
        t(COMP_ON, "Compressor", 0f); d(COMP_THR, "Threshold", -60f, 0f, -18f, " dB"); d(COMP_RATIO, "Ratio", 1f, 20f, 3f, ":1")
        d(COMP_ATK, "Attack", 0.1f, 100f, 10f, " ms", true); d(COMP_REL, "Release", 10f, 1000f, 120f, " ms", true)
        d(COMP_MAKEUP, "Makeup", 0f, 24f, 3f, " dB"); d(COMP_KNEE, "Knee", 0f, 24f, 6f, " dB")
        t(NAM_ON, "NAM"); d(NAM_IN, "Input", -24f, 24f, 0f, " dB"); d(NAM_OUT, "Output", -24f, 24f, 0f, " dB")
        t(CAB_ON, "Cab"); d(CAB_LEVEL, "Level", -24f, 12f, 0f, " dB"); d(CAB_MIX, "Mix", 0f, 1f, 1f, "", dec = 2)
        t(EQ_ON, "EQ")
        val fr = floatArrayOf(90f, 250f, 800f, 2500f, 6500f); val gn = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        val qs = floatArrayOf(0.7f, 1f, 1f, 1f, 0.7f); val names = listOf("LOW", "LM", "MID", "HM", "HIGH")
        for (b in 0 until 5) {
            d(EQ_B0 + b * 3, "${names[b]} Freq", 20f, 20000f, fr[b], " Hz", true, 0)
            d(EQ_B0 + b * 3 + 1, "${names[b]} Gain", -18f, 18f, gn[b], " dB")
            d(EQ_B0 + b * 3 + 2, "${names[b]} Q", 0.2f, 10f, qs[b], "", true, 2)
        }
        d(EQ_OUT, "Output", -18f, 18f, 0f, " dB")
        t(MOD_ON, "Mod", 0f); d(MOD_TYPE, "Type", 0f, 3f, 0f, "", dec = 0); d(MOD_RATE, "Rate", 0.05f, 10f, 0.8f, " Hz", true, 2)
        d(MOD_DEPTH, "Depth", 0f, 1f, 0.5f, "", dec = 2); d(MOD_MIX, "Mix", 0f, 1f, 0.5f, "", dec = 2); d(MOD_FB, "Feedback", 0f, 0.95f, 0.3f, "", dec = 2)
        t(DLY_ON, "Delay", 0f); d(DLY_TIME, "Time", 20f, 1500f, 380f, " ms", true, 0); d(DLY_FB, "Feedback", 0f, 0.95f, 0.35f, "", dec = 2)
        d(DLY_MIX, "Mix", 0f, 1f, 0.3f, "", dec = 2); d(DLY_TONE, "Tone", 500f, 12000f, 4500f, " Hz", true, 0)
        t(REV_ON, "Reverb", 0f); d(REV_SIZE, "Size", 0f, 1f, 0.5f, "", dec = 2); d(REV_DAMP, "Damping", 0f, 1f, 0.5f, "", dec = 2)
        d(REV_MIX, "Mix", 0f, 1f, 0.2f, "", dec = 2)
        t(LIM_ON, "Limiter"); d(LIM_THR, "Threshold", -30f, 0f, -0.3f, " dB"); d(LIM_CEIL, "Ceiling", -12f, 0f, -0.3f, " dB")
        d(LIM_REL, "Release", 10f, 1000f, 100f, " ms", true, 0)
        d(DLY_MODE, "Mode", 0f, 3f, 0f, "", dec = 0); d(DLY_DIV, "Note", 0f, 5f, 2f, "", dec = 0); d(DLY_BPM, "Tempo", 40f, 240f, 120f, " BPM", dec = 0)
        t(PITCH_ON, "Pitch", 0f); d(PITCH_ST, "Shift", -24f, 24f, 0f, " st"); d(PITCH_FINE, "Fine", -50f, 50f, 0f, " ct", dec = 0); d(PITCH_MIX, "Mix", 0f, 1f, 1f, "", dec = 2)
        t(DRV_ON, "Distortion", 0f); d(DRV_TYPE, "Type", 0f, 4f, 0f, "", dec = 0); d(DRV_GAIN, "Gain", 0f, 1f, 0.5f, "", dec = 2)
        d(DRV_TONE, "Tone", 0f, 1f, 0.5f, "", dec = 2); d(DRV_LEVEL, "Level", -24f, 12f, 0f, " dB")
        d(DLY_TYPE, "Type", 0f, 1f, 0f, "", dec = 0); d(DLY_DUCK, "Ducking", 0f, 1f, 0f, "", dec = 2)
        d(REV_TYPE, "Type", 0f, 1f, 0f, "", dec = 0); d(REV_SHIM, "Shimmer", 0f, 1f, 0.5f, "", dec = 2); d(GATE_POS, "Position", 0f, 1f, 0f, "", dec = 0)
        t(WAH_ON, "Wah", 0f); d(WAH_MODE, "Mode", 0f, 1f, 0f, "", dec = 0); d(WAH_POS, "Pedal", 0f, 1f, 0.5f, "", dec = 2)
        d(WAH_Q, "Resonance", 1f, 12f, 5f, "", dec = 1); d(WAH_SENS, "Sensitivity", 0f, 1f, 0.5f, "", dec = 2)
        t(OCT_ON, "Octave", 0f); d(OCT_DOWN, "Octave Down", 0f, 1f, 0.6f, "", dec = 2); d(OCT_UP, "Octave Up", 0f, 1f, 0f, "", dec = 2); d(OCT_DRY, "Dry", 0f, 1f, 1f, "", dec = 2)
        t(HARM_ON, "Harmony", 0f); d(HARM_KEY, "Key", 0f, 11f, 0f, "", dec = 0); d(HARM_SCALE, "Scale", 0f, 1f, 0f, "", dec = 0)
        d(HARM_INT, "Interval", 0f, 7f, 0f, "", dec = 0); d(HARM_MIX, "Mix", 0f, 1f, 0.5f, "", dec = 2)
        d(CAB2_MIX, "Slot B Blend", 0f, 1f, 0.5f, "", dec = 2)
        for (i in 0 until CHAIN_IDS) d(CHAIN0 + i, "Chain ${i + 1}", 0f, 63f, 0f, "", dec = 0)
        d(CHAIN_VER, "Chain version", 0f, 2f, 2f, "", dec = 0)
        d(MASTER_IN, "Input Gain", -50f, 3f, 0f, " dB"); d(MASTER_OUT, "Output Vol", -50f, 3f, 0f, " dB")
        List(COUNT) { id -> val b = m[id % COUNT_BASE] ?: PDef(id % COUNT_BASE, "-", 0f, 1f, 0f); if (id < COUNT_BASE) b else b.copy(id = id) }
    }
    fun def(id: Int) = defs[id]
    /** Module name for a parameter id (used to label MIDI-learned knobs). */
    fun moduleOf(id: Int): String = (if (id >= COUNT_BASE) "${moduleOfBase(id % COUNT_BASE)} ${id / COUNT_BASE + 1}" else moduleOfBase(id))
    private fun moduleOfBase(id: Int): String = when (id) {
        in 0..4 -> "Gate"; in 5..11 -> "Comp"; in 12..14 -> "NAM"; in 16..18, 94 -> "Cab"; in 19..35 -> "EQ"; in 36..41 -> "Mod"
        in 42..46, in 59..61, 75, 76 -> "Delay"; in 47..50, 77, 78 -> "Reverb"; in 52..55 -> "Limiter"; 57, 58 -> "Master"
        in 64..67 -> "Pitch"; in 70..74 -> "Distortion"; in 80..84 -> "Wah"; in 85..88 -> "Octave"; in 89..93 -> "Harmony"; in 96..112 -> "Chain"; else -> "Param"
    }

    val DIV_NAMES = listOf("1/1", "1/2", "1/4", "1/8", "1/16", "1/32")
    private val DIV_BEATS = floatArrayOf(4f, 2f, 1f, 0.5f, 0.25f, 0.125f)
    /** Waktu delay efektif (ms) untuk mode 0 = ms, 1 = straight, 2 = triplet, 3 = dotted. */
    fun delayMs(mode: Int, div: Int, bpm: Float, ms: Float): Float {
        if (mode <= 0) return ms
        val mul = when (mode) { 2 -> 2f / 3f; 3 -> 1.5f; else -> 1f }
        return (60000f / bpm.coerceIn(20f, 300f) * DIV_BEATS[div.coerceIn(0, 5)] * mul).coerceIn(2f, 2000f)
    }
}

/** Microphone effect parameter ids - same order as enum MicParam in cpp/micfx.h. */
object MicP {
    const val GATE_ON = 0; const val GATE_THR = 1
    const val COMP_ON = 2; const val COMP_THR = 3; const val COMP_RATIO = 4; const val COMP_MAKEUP = 5
    const val EQ_ON = 6; const val EQ_LOW = 7; const val EQ_MID = 8; const val EQ_HIGH = 9
    const val DLY_ON = 10; const val DLY_TIME = 11; const val DLY_FB = 12; const val DLY_MIX = 13
    const val REV_ON = 14; const val REV_SIZE = 15; const val REV_MIX = 16
    const val SLOT0 = 17                                   // SLOT0..SLOT0+3 = module in each of the 4 chain slots
    const val PRE = 21                                     // input 2 level in dB (-50..+3, -50 = off) - the IN 2 fader of the mixer
    const val EQ_LOWF = 22; const val EQ_M1F = 23; const val EQ_M1Q = 24; const val EQ_M2F = 25; const val EQ_M2G = 26; const val EQ_M2Q = 27; const val EQ_HIGHF = 28; const val EQ_LC = 29
    /** Module types for the slots (0 = empty). */
    const val T_GATE = 1; const val T_COMP = 2; const val T_EQ = 3; const val T_DLY = 4; const val T_REV = 5
    val TYPE_NAMES = listOf("Empty", "Gate", "Comp", "Parametric EQ", "Delay", "Reverb")
    val DEFAULTS = floatArrayOf(0f, -50f, 0f, -18f, 3f, 3f, 0f, 0f, 0f, 0f, 0f, 250f, 0.3f, 0.2f, 0f, 0.5f, 0.2f, 1f, 2f, 3f, 5f, 0f, 120f, 400f, 1f, 2500f, 0f, 1f, 8000f, 80f)
    /** Slot type -> index of its "on" parameter. */
    fun onId(type: Int) = when (type) { T_GATE -> GATE_ON; T_COMP -> COMP_ON; T_EQ -> EQ_ON; T_DLY -> DLY_ON; T_REV -> REV_ON; else -> -1 }
}
