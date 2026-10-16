package com.noamp.fx

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Preset(
    val id: String, val name: String, val values: FloatArray, val nam: String?, val cab: String?,
    val cab2: String? = null, val valuesB: FloatArray? = null, val valuesC: FloatArray? = null, val valuesD: FloatArray? = null, val valuesE: FloatArray? = null,
    val pedal: String? = null          // pedal board of this preset: stomp modules, expression destination, tap switch action, expression direction
) {
    /** Scene values: 0 = A (values), 1 = B, 2 = C, 3 = D, 4 = E. Null when that scene was never created. */
    fun scene(s: Int): FloatArray? = when (s) { 0 -> values; 1 -> valuesB; 2 -> valuesC; 3 -> valuesD; 4 -> valuesE; else -> null }
    fun withScene(s: Int, v: FloatArray): Preset = when (s) { 0 -> copy(values = v); 1 -> copy(valuesB = v); 2 -> copy(valuesC = v); 3 -> copy(valuesD = v); else -> copy(valuesE = v) }
}
data class Setlist(val id: String, val name: String, val ids: List<String>)
data class Dev(val id: Int, val name: String, val usb: Boolean)

const val IR_MAX_SAMPLES = 8192

val SAMPLE_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000)

class Rig(app: Application) {
    private val ctx: Context = app
    /** UI-lifetime scope (replaces the Android viewModelScope). */
    val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sp = app.getSharedPreferences("noamp", Context.MODE_PRIVATE)
    private val modelsDir = File(app.filesDir, "models").apply { mkdirs() }
    private val irDir = File(app.filesDir, "irs").apply { mkdirs() }
    private val presetFile = File(app.filesDir, "presets.json")

    val params = mutableStateListOf<Float>().also { l -> P.defs.forEach { l.add(it.def) } }
    val presets = mutableStateListOf<Preset>()
    var current by mutableIntStateOf(0)
    var edited by mutableStateOf(false)
    var namName by mutableStateOf<String?>(null)
    /** True when the loaded file is a slimmable container (Lite / Full sub-model can be chosen). */
    var namSlim by mutableStateOf(false)
    /** 0 = Lite (smallest sub-model, lowest CPU) · 1 = Full */
    var namQuality by mutableIntStateOf(sp.getInt("namQ", 1))
    val models = mutableStateListOf<String>()
    val builtinModels = HashSet<String>()
    var cabName by mutableStateOf<String?>(null)
    var cabTaps by mutableIntStateOf(0)
    var cab2Name by mutableStateOf<String?>(null)
    var cab2Taps by mutableIntStateOf(0)
    /** 0..4 = scene A, B, C, D, E (alternate settings inside the same preset) */
    var scene by mutableIntStateOf(0)
    val setlists = mutableStateListOf<Setlist>()
    var activeSetlist by mutableStateOf<String?>(sp.getString("activeSet", null))
    var status by mutableStateOf("")

    var running by mutableStateOf(false)
    var inDevs by mutableStateOf(listOf<Dev>()); var outDevs by mutableStateOf(listOf<Dev>())
    var selIn by mutableIntStateOf(sp.getInt("selIn", 0)); var selOut by mutableIntStateOf(sp.getInt("selOut", 0))
    fun pickIn(id: Int) { selIn = id; sp.edit().putInt("selIn", id).apply(); restartIfRunning() }
    fun pickOut(id: Int) { selOut = id; sp.edit().putInt("selOut", id).apply(); restartIfRunning() }

    // ---------- audio backend: 0 = Windows audio (WASAPI), 1 = ASIO
    var backend by mutableIntStateOf(sp.getInt("backend", 0))
    val asioCompiled: Boolean = Engine.nativeAsioCompiled()
    var asioDrivers by mutableStateOf(listOf<Dev>())          // id = position in the Windows ASIO registry
    var asioDrv by mutableIntStateOf(0)
    private var asioName: String = sp.getString("asioName", "") ?: ""
    var asioInfo by mutableStateOf<AsioInfo?>(null)
    var asioMsg by mutableStateOf("")                         // probe error / hint shown in Settings
    var asioBuf by mutableIntStateOf(sp.getInt("asioBuf", 0)) // frames, 0 = driver default
    var asioIn by mutableIntStateOf(sp.getInt("asioIn", 0)); var asioOut by mutableIntStateOf(sp.getInt("asioOut", 0))
    /** 0 Ultra (exclusive) · 1 Low (shared) · 2 Balanced · 3 Safe (power saving) */
    var latMode by mutableIntStateOf(sp.getInt("latMode", 0))
    /** Buffer dalam ms; 0 = Auto */
    var bufMs by mutableFloatStateOf(sp.getFloat("bufMs", 0f))
    /** 44100 atau 48000 */
    var sampleRate by mutableIntStateOf(sp.getInt("sr", 48000).let { if (it in SAMPLE_RATES) it else 48000 })
    /** Pin thread audio ke core CPU tercepat (mengurangi beban/xrun di HP big.LITTLE). */
    var bigCore by mutableStateOf(sp.getBoolean("bigCore", true))
    /** Microphone on interface input 2, mixed into the output (and into recordings / videos). */
    var micOn by mutableStateOf(sp.getBoolean("micOn", false))
    var micDb by mutableFloatStateOf(sp.getFloat("micDb", 0f).coerceIn(-50f, 3f))
    var micActive by mutableStateOf(false)      // true when the opened input really has 2 channels
    /** Mic effect values, index = MicP.* (same order as MicParam in micfx.h). Saved in preferences. */
    val micFx = mutableStateListOf<Float>().also { l -> MicP.DEFAULTS.forEachIndexed { i, d -> l.add(sp.getFloat("micfx$i", d)) } }

    // ---------- Tuner
    var tunerOn by mutableStateOf(false)
    var tunerMute by mutableStateOf(sp.getBoolean("tunMute", true))
    var tunerRef by mutableIntStateOf(sp.getInt("tunRef", 440))
    var tunerHas by mutableStateOf(false)
    var tunerNote by mutableStateOf("—")
    var tunerCents by mutableFloatStateOf(0f)
    var tunerHz by mutableFloatStateOf(0f)
    private val pitchBuf = FloatArray(3); private var hzS = 0f; private var lastPitchAt = 0L

    // ---------- MIDI
    var pcDirect by mutableStateOf(sp.getBoolean("pcDirect", false))
    var pcChannel by mutableIntStateOf(sp.getInt("pcChannel", -1))
    var clockSync by mutableStateOf(sp.getBoolean("clockSync", false))
    var midiFeedback by mutableStateOf(sp.getBoolean("midiFb", false))
    var calibTarget by mutableStateOf<String?>(null)
    var calibMin by mutableIntStateOf(127)
    var calibMax by mutableIntStateOf(0)
    var harmSemis by mutableFloatStateOf(0f)
    val tools = Tools(app, viewModelScope, sp)
    private val fbLast = java.util.concurrent.ConcurrentHashMap<String, Int>(); private var fbDevCount = -1
    val binds = mutableStateListOf<MidiBinding>()
    var learnTarget by mutableStateOf<String?>(null)
    var midiLast by mutableStateOf("")
    val midi = MidiIO(app) { onMidi(it) }

    var meters by mutableStateOf(FloatArray(19) { -120f })      // [16] = channel 2 (mic) input, [17] / [18] = output of channel 1 / 2
    var spectrum by mutableStateOf(FloatArray(64) { -120f })
    var eqCurve by mutableStateOf(FloatArray(128))
    var limHist by mutableStateOf(FloatArray(150))
    var wantSpectrum = false
    var wantHist = false

    val presetName get() = presets.getOrNull(current)?.name ?: "No preset"
    private var presetGen = 0

    // ---- pedal board / favorites / haptics state: declared BEFORE init, because applyPreset() runs from init
    /** Where the expression pedal acts: 0 = pitch shifter, 1 = wah pedal, 2 = master output (cycle with "Next expression"). */
    var expDest by mutableIntStateOf(sp.getInt("expDest", 1))
    var expReverse by mutableStateOf(sp.getBoolean("expRev", false))        // the pad counts 0..100 % from the bottom instead of from the top
    var tapAction by mutableIntStateOf(sp.getInt("tapAction", 0))           // pedal "Tap" switch: 0 = tap tempo, 1 = next expression
    /** Module (its ON parameter id) of each of the 5 stomp switches. */
    val stompIds = mutableStateListOf<Int>().also { l -> val d = intArrayOf(P.COMP_ON, P.DRV_ON, P.MOD_ON, P.DLY_ON, P.REV_ON); for (i in 0..4) l.add(sp.getInt("stomp$i", d[i])) }
    val favs = mutableStateListOf<String>().also { l -> sp.getString("favs", "")?.split(",")?.filter { it.isNotBlank() }?.let { l.addAll(it) } }
    /** Master bus (stereo out) level in dB - the last fader of the mixer. */
    var busDb by mutableFloatStateOf(sp.getFloat("busDb", 0f).coerceIn(-50f, 3f))
    /** Balance of Out channel 1 / Out channel 2 / Stereo Out: -1 = full left, 0 = centre (C), +1 = full right. */
    var panOut1 by mutableFloatStateOf(sp.getFloat("pan1", 0f).coerceIn(-1f, 1f))
    var panOut2 by mutableFloatStateOf(sp.getFloat("pan2", 0f).coerceIn(-1f, 1f))
    var panBus by mutableFloatStateOf(sp.getFloat("panBus", 0f).coerceIn(-1f, 1f))
    /** Source of input 2: 0 = channel 2 of the audio interface, 1 = the phone's own (built-in) microphone. Input 1 (guitar) always comes from the interface selected as Input. */
    var micSrc by mutableIntStateOf(0)   // desktop: input 2 is always channel 2 of the interface
    /** Android device id of the built-in microphone (0 = none found). */
    var phoneMicId by mutableIntStateOf(0)
    /** Pitch shifter / harmonizer window: true = short (about 10 ms latency), false = long (about 26 ms, smoother). */
    var pitchLowLat by mutableStateOf(sp.getBoolean("pitchLow", true))
    var haptics by mutableStateOf(sp.getBoolean("haptic", true).also { Haptics.on = it })

    init {
        copyBuiltinModels(); refreshModels()
        loadPresets(); loadBinds(); loadSetlists()
        midi.onClock = { bpm -> if (clockSync) setAbs(P.DLY_BPM, bpm.coerceIn(40f, 240f), false) }
        val lastId = sp.getString("lastId", null)
        current = presets.indexOfFirst { it.id == lastId }
        Engine.nativeSetBigCore(bigCore)
        Engine.nativeSetMic(micOn, micDb)
        Engine.nativeSetBus(busDb)
        Engine.nativeSetPan(0, panOut1); Engine.nativeSetPan(1, panOut2); Engine.nativeSetPan(2, panBus)
        Engine.nativeSetPitchLowLat(pitchLowLat)
        micFx[MicP.PRE] = micFx[MicP.PRE].coerceIn(-50f, 3f)
        micFx.forEachIndexed { i, v -> Engine.nativeSetMicParam(i, v) }
        refreshDevices()
        if (backend == 1 && asioCompiled) refreshAsio() else if (backend == 1) backend = 0
        if (vstCompiled) { Engine.nativeVstSetOn(vstOn); Engine.nativeVstSetPosition(vstPos); scanVst(); if (vstPath.isNotEmpty() && java.io.File(vstPath).exists()) loadVst(vstPath, true) }
        if (current >= 0) applyPreset(current) else { for (k in 0 until P.COUNT) setAbs(k, P.defs[k].def, false); edited = false }
        midi.start(); midi.reconnectLast()
        viewModelScope.launch(Dispatchers.Default) {
            val t = FloatArray(19); val st = FloatArray(16); val sa = FloatArray(64); val eq = FloatArray(128)
            var hist = FloatArray(150)
            var devTick = 0; var vstTick = 0
            while (true) {
                if (vstCompiled && ++vstTick >= 12) { vstTick = 0; try { if (vstName.isNotEmpty()) { vstEditor = Engine.nativeVstEditorOpen(); if (Engine.nativeVstTakeDirty()) saveVstState() } } catch (_: Throwable) { } }
                if (++devTick >= (if (running) 70 else 12)) { devTick = 0; try { refreshDevices() } catch (_: Throwable) { } }
                val nr = Engine.nativeIsRunning()               // also notices when the device was lost / stopped by the engine
                if (nr != running) { running = nr; if (!nr) meters = FloatArray(19).also { for (i in intArrayOf(0, 1, 2, 16, 17, 18)) it[i] = -120f } }
                if (!running) { delay(250); continue }
                Engine.nativeGetMeters(t)
                val m = t.copyOf(); meters = m
                Engine.nativeGetStatus(st); tools.poll(st); harmSemis = st[7]; micActive = st[9] > 0.5f
                feedbackTick()
                if (tunerOn) pollTuner()
                if (wantHist) { hist = hist.copyOfRange(1, 150) + m[5].coerceAtLeast(0f); limHist = hist }
                if (wantSpectrum) {
                    Engine.nativeSetEqBank(uiBank); Engine.nativeGetSpectrum(sa); Engine.nativeGetEq(eq)
                    val old = spectrum
                    spectrum = FloatArray(64) { i -> maxOf(sa[i], old[i] - 2.5f) }
                    eqCurve = eq.copyOf()
                }
                delay(if (wantSpectrum || wantHist || tunerOn) 50 else 80)
            }
        }
    }

    // ---------- parameters
    /** Bank that the open module screen edits: 0 = original block, 1..2 = extra block. set/get/on/toggle map base ids (< 128) to that bank; *Abs versions never do. */
    var uiMicSlot by mutableIntStateOf(0)      // which block of the channel 2 chain the Mic page edits
    var uiBank by mutableIntStateOf(0)
    private fun idx(id: Int) = if (uiBank != 0 && id < P.COUNT_BASE && id !in P.LIM_ON..P.MASTER_OUT && id !in P.CHAIN0..P.CHAIN_VER) id + uiBank * P.COUNT_BASE else id
    fun setAbs(id: Int, v0: Float, markEdit: Boolean = true) {
        val v = if (id == P.MASTER_IN || id == P.MASTER_OUT) v0.coerceIn(-50f, 3f) else v0      // mixer faders: -50..+3 dB
        params[id] = v; Engine.nativeSetParam(id, v); if (markEdit) edited = true
    }
    fun getAbs(id: Int) = params[id]
    fun onAbs(id: Int) = params[id] > 0.5f
    fun toggleAbs(id: Int) = setAbs(id, if (onAbs(id)) 0f else 1f)
    fun set(id: Int, v: Float, markEdit: Boolean = true) = setAbs(idx(id), v, markEdit)
    fun get(id: Int) = params[idx(id)]
    fun on(id: Int) = onAbs(idx(id))
    fun toggle(id: Int) = toggleAbs(idx(id))

    // ---------- signal chain: 8 slots, each empty (0) or a module entry (bank * 16 + module code). Gate is fixed at the input, Limiter at the output.
    val slots: List<Int> get() = (0 until P.CHAIN_SLOTS).map { i -> params[P.CHAIN0 + i].toInt().let { if (P.validEntry(it)) it else 0 } }
    private fun writeSlots(l: List<Int>) { for (i in 0 until P.CHAIN_IDS) setAbs(P.CHAIN0 + i, (if (i < P.CHAIN_SLOTS) l.getOrNull(i) ?: 0 else 0).toFloat()) }
    fun swapSlots(a: Int, b: Int) {
        val l = slots.toMutableList(); if (a !in l.indices || b !in l.indices || a == b) return
        val t = l[a]; l[a] = l[b]; l[b] = t; writeSlots(l)
    }
    fun clearSlot(i: Int) { val l = slots.toMutableList(); if (i in l.indices) { l[i] = 0; writeSlots(l) } }
    /** Free parameter bank for [module] when it is put into slot [slot] (null = no instance left: Harmony, NAM and Cab exist once, the others up to 3 times). */
    fun freeBank(module: Int, slot: Int): Int? {
        val used = slots.filterIndexed { i, e -> i != slot && e != 0 && P.entryModule(e) == module }.map { P.entryBank(it) }.toSet()
        return (0 until (if (module in P.DUPLICABLE) P.BANKS else 1)).firstOrNull { it !in used }
    }
    /** Puts [module] into [slot], switched ON. A second or third copy of a module starts with default settings. */
    fun fillSlot(slot: Int, module: Int) {
        if (slot !in 0 until P.CHAIN_SLOTS || module !in P.SLOT_MODULES) return
        val bank = freeBank(module, slot) ?: return
        val l = slots.toMutableList()
        if (l[slot] != P.entry(bank, module)) {
            if (bank > 0) for (i in 0 until P.COUNT_BASE) if (i < P.CHAIN0 || i > P.CHAIN_VER) setAbs(bank * P.COUNT_BASE + i, P.defs[i].def, false)
            setAbs(bank * P.COUNT_BASE + P.MODULE_ON[module - 1], 1f)
        }
        l[slot] = P.entry(bank, module); writeSlots(l)
    }

    // ---------- audio devices / power
    fun refreshDevices() {
        fun map(raw: Array<String>): List<Dev> = raw.mapNotNull { line ->
            val p = line.indexOf('|'); if (p <= 0) null else Dev(line.substring(0, p).toIntOrNull() ?: return@mapNotNull null, line.substring(p + 1), false)
        }
        val i = map(Engine.nativeListDevices(true))
        val o = map(Engine.nativeListDevices(false))
        val newIn = listOf(Dev(0, "System default", false)) + i
        val newOut = listOf(Dev(0, "System default", false)) + o
        if (newIn != inDevs) inDevs = newIn
        if (newOut != outDevs) outDevs = newOut
        if (!inDevs.any { it.id == selIn }) selIn = 0
        if (!outDevs.any { it.id == selOut }) selOut = 0
    }

    // ---------- ASIO
    class AsioInfo(val min: Int, val max: Int, val pref: Int, val gran: Int, val latIn: Int, val latOut: Int, val rates: List<Int>, val inputs: List<String>, val outputs: List<String>) {
        /** Buffer sizes (frames) this driver accepts, from the usual list; granularity -1 = powers of two, 0 = only the preferred size. */
        fun bufferChoices(): List<Int> {
            val c = listOf(16, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 2048).filter { v ->
                v in min..max && when { gran == 0 -> v == pref; gran < 0 -> v >= min && (v and (v - 1)) == 0; else -> (v - min) % gran == 0 }
            }
            return (c + pref).distinct().sorted()
        }
    }
    fun setBackend(b: Int) {
        if (b == backend) return
        if (b == 1 && !asioCompiled) { asioMsg = "This build has no ASIO support (see README_WINDOWS.md)."; return }
        backend = b; sp.edit().putInt("backend", b).apply()
        if (b == 1) refreshAsio()
        restartIfRunning()
    }
    fun refreshAsio() {
        viewModelScope.launch(Dispatchers.Default) {
            val list = Engine.nativeAsioDrivers().mapNotNull { l -> val p = l.indexOf('|'); if (p <= 0) null else Dev(l.substring(0, p).toIntOrNull() ?: return@mapNotNull null, l.substring(p + 1), false) }
            asioDrivers = list
            val found = list.firstOrNull { it.name == asioName } ?: list.firstOrNull()
            if (found == null) { asioMsg = if (asioCompiled) "No ASIO driver is installed. Install your interface's driver (or ASIO4ALL)." else "This build has no ASIO support (see README_WINDOWS.md)."; asioInfo = null; return@launch }
            asioDrv = found.id; asioName = found.name
            probeAsio()
        }
    }
    fun pickAsio(d: Dev) {
        if (running && backend == 1) { asioMsg = "Stop the engine before choosing another ASIO driver."; return }
        asioDrv = d.id; asioName = d.name; sp.edit().putString("asioName", d.name).apply()
        probeAsio()
    }
    /** Reads the driver's buffer range, sample rates and channel names. Never while the engine plays through another driver. */
    fun probeAsio() {
        viewModelScope.launch(Dispatchers.Default) {
            val r = Engine.nativeAsioProbe(asioDrv).split('|')
            if (r.firstOrNull() != "OK" || r.size < 10) { asioInfo = null; asioMsg = r.getOrElse(1) { "The ASIO driver did not answer." }; return@launch }
            val i = AsioInfo(r[1].toInt(), r[2].toInt(), r[3].toInt(), r[4].toInt(), r[5].toInt(), r[6].toInt(),
                r[7].split(',').mapNotNull { it.toIntOrNull() }, r[8].split('\t').filter { it.isNotEmpty() }, r[9].split('\t').filter { it.isNotEmpty() })
            asioInfo = i; asioMsg = ""
            if (asioBuf !in i.bufferChoices()) asioBuf = i.pref
            if (asioIn !in 0 until maxOf(1, i.inputs.size)) asioIn = 0
            if (asioOut !in 0 until maxOf(1, i.outputs.size - 1)) asioOut = 0
            if (i.rates.isNotEmpty() && sampleRate !in i.rates) setSampleRateHz(if (48000 in i.rates) 48000 else i.rates.first())
        }
    }
    fun setAsioBuf(frames: Int) { asioBuf = frames; sp.edit().putInt("asioBuf", frames).apply(); restartIfRunning() }
    fun setAsioIn(ch: Int) { asioIn = ch; sp.edit().putInt("asioIn", ch).apply(); restartIfRunning() }
    fun setAsioOut(ch: Int) { asioOut = ch; sp.edit().putInt("asioOut", ch).apply(); restartIfRunning() }
    fun asioControlPanel() { viewModelScope.launch(Dispatchers.Default) { Engine.nativeAsioControlPanel() } }

    // ---------- VST3 effect slot (one plug-in, saved with the app, not with the preset)
    class VstEntry(val path: String, val name: String)
    val vstCompiled: Boolean = Engine.nativeVstCompiled()
    var vstList by mutableStateOf(listOf<VstEntry>())
    var vstScanning by mutableStateOf(false)
    var vstPath by mutableStateOf(sp.getString("vstPath", "") ?: "")
    var vstName by mutableStateOf("")                                   // "" = nothing loaded
    var vstOn by mutableStateOf(sp.getBoolean("vstOn", true))
    var vstPos by mutableIntStateOf(sp.getInt("vstPos", 0))             // 0 after the chain · 1 before the chain
    var vstDirs by mutableStateOf(sp.getString("vstDirs", "") ?: "")    // extra scan folders, ';' separated
    var vstMsg by mutableStateOf("")
    var vstEditor by mutableStateOf(false)
    var vstLatency by mutableIntStateOf(0)
    fun scanVst() {
        if (vstScanning) return
        vstScanning = true
        viewModelScope.launch(Dispatchers.IO) {
            try { vstList = Engine.nativeVstScan(vstDirs).mapNotNull { l -> val p = l.lastIndexOf('|'); if (p <= 0) null else VstEntry(l.substring(0, p), l.substring(p + 1)) } }
            finally { vstScanning = false }
        }
    }
    fun loadVst(path: String, restoreState: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            vstMsg = "Loading…"
            val state = if (restoreState || path == vstPath) sp.getString("vstState", "") ?: "" else ""
            val err = Engine.nativeVstLoad(path)
            if (err.isNotEmpty()) { vstMsg = err; vstName = ""; return@launch }
            Engine.nativeVstSetOn(vstOn); Engine.nativeVstSetPosition(vstPos)
            if (state.isNotEmpty()) Engine.nativeVstSetState(state)
            vstPath = path; vstName = Engine.nativeVstName(); vstLatency = Engine.nativeVstLatency(); vstMsg = ""
            sp.edit().putString("vstPath", path).apply()
            if (path != sp.getString("vstStatePath", "")) sp.edit().putString("vstState", "").putString("vstStatePath", path).apply()
        }
    }
    fun unloadVst() {
        viewModelScope.launch(Dispatchers.IO) {
            Engine.nativeVstUnload(); vstName = ""; vstPath = ""; vstEditor = false; vstMsg = ""
            sp.edit().putString("vstPath", "").putString("vstState", "").apply()
        }
    }
    fun setVstOn(on: Boolean) { vstOn = on; sp.edit().putBoolean("vstOn", on).apply(); Engine.nativeVstSetOn(on) }
    fun setVstPos(p: Int) { vstPos = p; sp.edit().putInt("vstPos", p).apply(); Engine.nativeVstSetPosition(p) }
    fun addVstDir(dir: String) { if (dir.isBlank() || dir in vstDirs.split(';')) return; vstDirs = (vstDirs.split(';').filter { it.isNotBlank() } + dir).joinToString(";"); sp.edit().putString("vstDirs", vstDirs).apply(); scanVst() }
    fun openVstEditor() { viewModelScope.launch(Dispatchers.IO) { if (!Engine.nativeVstOpenEditor()) vstMsg = "This plug-in has no editor window." else vstMsg = ""; vstEditor = Engine.nativeVstEditorOpen() } }
    fun closeVstEditor() { viewModelScope.launch(Dispatchers.IO) { Engine.nativeVstCloseEditor(); vstEditor = false } }
    private fun saveVstState() { val st = Engine.nativeVstGetState(); if (st.isNotEmpty()) sp.edit().putString("vstState", st).putString("vstStatePath", vstPath).apply() }

    fun togglePower() {
        viewModelScope.launch(Dispatchers.Default) {
            if (running) {
                Engine.nativeStop(); running = false; status = ""
                meters = FloatArray(19).also { for (i in intArrayOf(0, 1, 2, 16, 17, 18)) it[i] = -120f }
            } else {
                running = startAudio()
                status = if (running) "" else "Could not open audio: " + Engine.nativeLastError().ifBlank { "check the audio interface, or close other programs that use it exclusively." }
            }
        }
    }
    /** Opens the selected backend (ASIO driver or Windows audio) with the current settings. */
    private fun startAudio(): Boolean =
        if (backend == 1) Engine.nativeStartAsio(asioDrv, sampleRate, asioBuf, asioIn, asioOut)
        else Engine.nativeStart(selIn, selOut, latMode, bufMs, sampleRate, micDevArg())
    fun restartIfRunning() { if (running) viewModelScope.launch(Dispatchers.Default) { running = startAudio(); if (!running) status = "Could not open audio: " + Engine.nativeLastError() } }
    fun updateLatMode(m: Int) { latMode = m; sp.edit().putInt("latMode", m).apply(); restartIfRunning() }
    fun setBuf(ms: Float) {
        bufMs = ms; sp.edit().putFloat("bufMs", ms).apply()
        Engine.nativeSetBufferMs(ms); restartIfRunning()      // the buffer size is applied when the device is (re)opened
    }
    fun setSampleRateHz(hz: Int) {
        val v = if (hz in SAMPLE_RATES) hz else 48000
        if (v == sampleRate) return
        sampleRate = v; sp.edit().putInt("sr", v).apply(); restartIfRunning()
        viewModelScope.launch(Dispatchers.IO) { val e = edited; cabName?.let { loadIrFile(it, 0) }; cab2Name?.let { loadIrFile(it, 1) }; edited = e }   // IRs are resampled to the engine rate
    }
    // ---------- tuner
    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun setTuner(on: Boolean) {
        tunerOn = on; Engine.nativeSetTuner(on, tunerMute)
        if (!on) { tunerHas = false; hzS = 0f }
    }
    fun updateTunerMute(m: Boolean) { tunerMute = m; sp.edit().putBoolean("tunMute", m).apply(); Engine.nativeSetTuner(tunerOn, m) }
    fun updateTunerRef(hz: Int) { tunerRef = hz.coerceIn(430, 450); sp.edit().putInt("tunRef", tunerRef).apply() }
    private fun pollTuner() {
        Engine.nativeGetPitch(pitchBuf)
        val hz = pitchBuf[0]; val conf = pitchBuf[1]; val now = System.currentTimeMillis()
        if (hz > 30f && conf > 0.85f) {
            hzS = if (hzS > 0f && abs(ln(hz / hzS)) < 0.03f) hzS * 0.6f + hz * 0.4f else hz
            lastPitchAt = now
            val midi = 69.0 + 12.0 * ln(hzS.toDouble() / tunerRef) / ln(2.0)
            val n = midi.roundToInt()
            tunerNote = NOTE_NAMES[((n % 12) + 12) % 12] + (Math.floorDiv(n, 12) - 1)
            tunerCents = ((midi - n) * 100.0).toFloat(); tunerHz = hzS; tunerHas = true
        } else if (now - lastPitchAt > 500) { tunerHas = false; hzS = 0f }
    }

    fun enableMic(on: Boolean) { micOn = on; sp.edit().putBoolean("micOn", on).apply(); Engine.nativeSetMic(on, micDb); restartIfRunning() }
    /** Type (0..5) in chain slot [s] of the mic chain. */
    fun micSlot(s: Int) = micFx[MicP.SLOT0 + s].toInt()
    /** Put module [type] in slot [s]. Each module exists once (it swaps with its old slot); the reverb is stereo, so it only fits the last slot. */
    fun setMicSlot(s: Int, type: Int) {
        if (type == MicP.T_REV && s != 3) return
        val old = micSlot(s)
        if (type != 0) {
            val other = (0..3).firstOrNull { it != s && micSlot(it) == type }
            if (other != null) { if (old == MicP.T_REV && other != 3) return; setMicFx(MicP.SLOT0 + other, old.toFloat()) }
        }
        setMicFx(MicP.SLOT0 + s, type.toFloat())
    }
    fun setMicFx(i: Int, v: Float) { micFx[i] = v; sp.edit().putFloat("micfx$i", v).apply(); Engine.nativeSetMicParam(i, v) }
    /** Device id for the separate microphone stream; 0 = input 2 is channel 2 of the interface. */
    fun micDevArg(): Int = if (micSrc == 1) phoneMicId else 0
    fun setMicSource(src: Int) { if (src == micSrc) return; micSrc = src; sp.edit().putInt("micSrc", src).apply(); restartIfRunning() }
    /** ch: 0 = Out channel 1, 1 = Out channel 2, 2 = Stereo Out. */
    fun setPan(ch: Int, v: Float) {
        val x = v.coerceIn(-1f, 1f).let { if (kotlin.math.abs(it) < 0.03f) 0f else it }
        when (ch) { 0 -> { panOut1 = x; sp.edit().putFloat("pan1", x).apply() }; 1 -> { panOut2 = x; sp.edit().putFloat("pan2", x).apply() }; else -> { panBus = x; sp.edit().putFloat("panBus", x).apply() } }
        Engine.nativeSetPan(ch, x)
    }
    fun updatePitchLowLat(on: Boolean) { pitchLowLat = on; sp.edit().putBoolean("pitchLow", on).apply(); Engine.nativeSetPitchLowLat(on) }
    fun chooseBus(db: Float) { busDb = db; sp.edit().putFloat("busDb", db).apply(); Engine.nativeSetBus(db) }
    fun setMicLevel(db: Float) { micDb = db; sp.edit().putFloat("micDb", db).apply(); Engine.nativeSetMic(micOn, db) }
    fun setBigCoreOn(on: Boolean) { bigCore = on; sp.edit().putBoolean("bigCore", on).apply(); Engine.nativeSetBigCore(on) }
    fun shutdown() { try { if (vstName.isNotEmpty()) saveVstState(); Engine.nativeVstUnload() } catch (_: Throwable) { }; Engine.nativeStop(); midi.stop(); viewModelScope.cancel() }   // called when the window closes

    // ---------- file import (NAM model / cabinet IR)
    fun importNam(src: File) {
        viewModelScope.launch(Dispatchers.IO) {
            status = "Loading NAM model…"
            val name = src.name
            src.inputStream().use { i -> File(modelsDir, name).outputStream().use { o -> i.copyTo(o) } }
            refreshModels()
            loadNamFile(name)
        }
    }
    private fun loadNamFile(name: String) {
        val prep = NamFile.prepare(File(modelsDir, name), ctx.cacheDir, namQuality)
        val err = Engine.nativeLoadNam(prep.path)
        if (err.isEmpty()) { namName = name; namSlim = prep.slim; status = "" } else { namName = null; namSlim = false; status = "Failed to load $name: $err" }
        edited = true
    }
    // ---------- model library (built-in models shipped in assets/models + imported ones)
    private fun copyBuiltinModels() {
        try {
            val names = ctx.assets.list("models") ?: return
            for (n in names) {
                if (!n.endsWith(".nam", true)) continue
                builtinModels.add(n)
                val dest = File(modelsDir, n)
                val bytes = ctx.assets.open("models/$n").use { it.readBytes() }
                if (!dest.exists() || dest.length() != bytes.size.toLong()) dest.writeBytes(bytes)
            }
        } catch (_: Exception) { }
    }
    fun refreshModels() {
        val all = modelsDir.listFiles { f -> f.isFile && f.extension.equals("nam", true) }?.map { it.name } ?: emptyList()
        val sorted = all.filter { it in builtinModels }.sortedBy { it.lowercase() } + all.filter { it !in builtinModels }.sortedBy { it.lowercase() }
        models.clear(); models.addAll(sorted)
    }
    fun loadModel(name: String) { viewModelScope.launch(Dispatchers.IO) { status = "Loading NAM model…"; loadNamFile(name) } }
    fun deleteModel(name: String) {
        if (name in builtinModels) return
        File(modelsDir, name).delete(); refreshModels()
    }
    fun updateNamQuality(q: Int) {
        val v = if (q <= 0) 0 else 1
        if (v == namQuality) return
        namQuality = v; sp.edit().putInt("namQ", v).apply()
        val n = namName
        if (n != null && namSlim) viewModelScope.launch(Dispatchers.IO) { val e = edited; status = "Loading NAM model…"; loadNamFile(n); edited = e }
    }
    fun clearNam() { Engine.nativeClearNam(); namName = null; namSlim = false; edited = true }

    fun importIr(src: File, slot: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = src.name
            src.inputStream().use { i -> File(irDir, name).outputStream().use { o -> i.copyTo(o) } }
            loadIrFile(name, slot)
        }
    }
    private fun loadIrFile(name: String, slot: Int) {
        val d = try { File(irDir, name).inputStream().use { Wav.readMono(it, sampleRate, IR_MAX_SAMPLES) } } catch (e: Exception) { null }
        if (d == null || d.isEmpty()) { status = "IR must be a WAV file: $name"; if (slot == 0) cabName = null else cab2Name = null; return }
        Engine.nativeSetIr(slot, d)
        if (slot == 0) { cabName = name; cabTaps = d.size } else { cab2Name = name; cab2Taps = d.size }
        status = ""; edited = true
    }
    private fun clearIrQuiet(slot: Int) {
        Engine.nativeClearIr(slot)
        if (slot == 0) { cabName = null; cabTaps = 0 } else { cab2Name = null; cab2Taps = 0 }
    }
    fun clearIr(slot: Int = 0) { clearIrQuiet(slot); edited = true }

    // ---------- presets (user-made only)
    private fun sanitize(v: FloatArray) {
        // Chain format: 0 = before the free chain (fixed default order), 1 = 16 free slots (v1.8-2.0), 2 = 8 slots. Older presets keep their sound: modules that were ON go into the slots.
        val ver = v[P.CHAIN_VER]
        val src: List<Int> = if (ver < 0.5f) (2..P.CHAIN_MODULES).toList() else (0 until P.CHAIN_IDS).map { v[P.CHAIN0 + it].toInt() }
        val ent = src.filter { P.validEntry(it) }.distinct()
        val isOn = { e: Int -> v[P.entryBank(e) * P.COUNT_BASE + P.MODULE_ON[P.entryModule(e) - 1]] > 0.5f }
        val keep = if (ver >= 1.5f) ent.take(P.CHAIN_SLOTS) else (ent.filter(isOn) + ent.filter { !isOn(it) }).take(P.CHAIN_SLOTS)
        val ordered = ent.filter { it in keep }
        for (i in 0 until P.CHAIN_IDS) v[P.CHAIN0 + i] = (if (i < P.CHAIN_SLOTS) ordered.getOrNull(i) ?: 0 else 0).toFloat()
        v[P.CHAIN_VER] = 2f
        if (v[P.DRV_GAIN] == 0f && v[P.DRV_TONE] == 0f) { v[P.DRV_GAIN] = P.defs[P.DRV_GAIN].def; v[P.DRV_TONE] = P.defs[P.DRV_TONE].def }   // saved before Drive existed
        if (v[P.DLY_BPM] < 40f) v[P.DLY_BPM] = P.defs[P.DLY_BPM].def                                                                       // saved before delay sync existed
    }
    private fun presetFromJson(o: JSONObject, nameMap: Map<String, String> = emptyMap(), irMap: Map<String, String> = emptyMap()): Preset {
        fun arr(key: String): FloatArray? {
            val va = o.optJSONArray(key) ?: return null
            val v = FloatArray(P.COUNT) { k -> if (k < va.length()) va.getDouble(k).toFloat() else P.defs[k].def }
            sanitize(v); return v
        }
        fun nm(s: String) = o.optString(s).ifEmpty { null }
        var id = o.optString("id"); if (id.isEmpty()) id = UUID.randomUUID().toString()
        return Preset(id, o.optString("name", "Preset"), arr("v") ?: FloatArray(P.COUNT) { P.defs[it].def },
            nm("nam")?.let { nameMap[it] ?: it }, nm("cab")?.let { irMap[it] ?: it }, nm("cab2")?.let { irMap[it] ?: it }, arr("vb"), arr("vc"), arr("vd"), arr("ve"), nm("pedal"))
    }
    private fun presetToJson(p: Preset): JSONObject {
        fun ja(v: FloatArray) = JSONArray().also { j -> v.forEach { j.put(it.toDouble()) } }
        val o = JSONObject().put("id", p.id).put("name", p.name).put("nam", p.nam ?: "").put("cab", p.cab ?: "").put("cab2", p.cab2 ?: "").put("v", ja(p.values))
        p.valuesB?.let { o.put("vb", ja(it)) }; p.valuesC?.let { o.put("vc", ja(it)) }; p.valuesD?.let { o.put("vd", ja(it)) }; p.valuesE?.let { o.put("ve", ja(it)) }; p.pedal?.let { o.put("pedal", it) }
        return o
    }
    private fun loadPresets() {
        presets.clear()
        var migrated = false
        try {
            if (presetFile.exists()) {
                val a = JSONArray(presetFile.readText())
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id").isEmpty()) migrated = true; presets.add(presetFromJson(o)) }
            }
        } catch (_: Exception) { }
        if (migrated) persist()
    }
    private fun persist() {
        val a = JSONArray(); presets.forEach { a.put(presetToJson(it)) }
        presetFile.writeText(a.toString())
    }

    private fun curVals() = FloatArray(P.COUNT) { params[it] }

    fun applyPreset(i: Int) {
        if (i !in presets.indices) return
        current = i; sp.edit().putString("lastId", presets[i].id).apply()
        val p = presets[i]
        scene = 0
        for (k in 0 until P.COUNT) setAbs(k, p.values[k], false)
        applyPedal(p.pedal)
        edited = false
        val gen = ++presetGen
        viewModelScope.launch(Dispatchers.IO) {
            if (gen != presetGen) return@launch            // a newer preset change took over
            if (p.nam != null) { if (File(modelsDir, p.nam).exists() && p.nam != namName) loadNamFile(p.nam) }
            else if (namName != null) { Engine.nativeClearNam(); namName = null; namSlim = false }
            for (slot in 0..1) {
                if (gen != presetGen) return@launch
                val want = if (slot == 0) p.cab else p.cab2; val have = if (slot == 0) cabName else cab2Name
                if (want != null) { if (File(irDir, want).exists() && want != have) loadIrFile(want, slot) }
                else if (have != null) clearIrQuiet(slot)
            }
            if (gen == presetGen) edited = false
        }
    }

    // ---------- scenes (A/B/C/D/E inside one preset; amp model and cabinet are shared; each scene keeps its own chain order)
    fun selectScene(s: Int) {
        val p = presets.getOrNull(current) ?: return
        if (s == scene || s !in 0..4) return
        var pp = p.withScene(scene, curVals())                      // keep live edits of the scene we leave (Save persists them)
        if (pp.scene(s) == null) pp = pp.withScene(s, pp.values.copyOf())   // a new scene starts as a copy of A
        presets[current] = pp
        val vals = pp.scene(s)!!
        for (k in 0 until P.COUNT) setAbs(k, vals[k], false)
        scene = s
    }

    // ---------- navigation (honours the active setlist)
    fun navList(): List<Int> {
        val sl = setlists.firstOrNull { it.id == activeSetlist } ?: return presets.indices.toList()
        return sl.ids.mapNotNull { id -> presets.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    }
    fun next() { val l = navList(); if (l.isEmpty()) return; val pos = l.indexOf(current); applyPreset(l[if (pos < 0) 0 else (pos + 1) % l.size]) }
    fun prev() { val l = navList(); if (l.isEmpty()) return; val pos = l.indexOf(current); applyPreset(l[if (pos < 0) l.lastIndex else (pos - 1 + l.size) % l.size]) }

    private fun snapshot(id: String, name: String, old: Preset?): Preset {
        val cur = curVals()
        val base = old ?: Preset(id, name, cur, namName, cabName, cab2Name)
        return base.copy(id = id, name = name, nam = namName, cab = cabName, cab2 = cab2Name, pedal = pedalSnapshot()).withScene(scene, cur)
    }
    fun hasPreset() = current in presets.indices
    /** Overwrites the active preset (the active scene). Returns false if there is no active preset (use Save As). */
    fun save(): Boolean {
        val cur = presets.getOrNull(current) ?: return false
        presets[current] = snapshot(cur.id, cur.name, cur); persist(); edited = false
        return true
    }
    fun saveAs(name: String) {
        val n = name.trim().ifEmpty { "Preset ${presets.size + 1}" }
        scene = 0
        presets.add(Preset(UUID.randomUUID().toString(), n, curVals(), namName, cabName, cab2Name, null, pedal = pedalSnapshot())); current = presets.size - 1; persist(); edited = false
        sp.edit().putString("lastId", presets[current].id).apply()
    }
    fun rename(name: String) {
        val cur = presets.getOrNull(current) ?: return; if (name.isBlank()) return
        presets[current] = cur.copy(name = name.trim()); persist()
    }
    fun delete() {
        val p = presets.getOrNull(current) ?: return
        presets.removeAt(current); persist()
        binds.removeAll { it.target == T.preset(p.id) }; saveBinds()
        for (i in setlists.indices) setlists[i] = setlists[i].copy(ids = setlists[i].ids.filter { it != p.id })
        saveSetlists()
        if (presets.isEmpty()) { current = -1; sp.edit().remove("lastId").apply(); edited = true }
        else applyPreset(current.coerceAtMost(presets.lastIndex))
    }

    // ---------- setlists
    private fun loadSetlists() {
        try {
            val a = JSONArray(sp.getString("setlists", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val ia = o.getJSONArray("ids")
                setlists.add(Setlist(o.getString("id"), o.getString("name"), List(ia.length()) { ia.getString(it) }))
            }
        } catch (_: Exception) { }
        if (setlists.none { it.id == activeSetlist }) activeSetlist = null
    }
    private fun setlistsToJson(): JSONArray {
        val a = JSONArray()
        setlists.forEach { s -> a.put(JSONObject().put("id", s.id).put("name", s.name).put("ids", JSONArray().also { j -> s.ids.forEach { j.put(it) } })) }
        return a
    }
    private fun saveSetlists() { sp.edit().putString("setlists", setlistsToJson().toString()).apply() }
    fun selectSetlist(id: String?) { activeSetlist = id; sp.edit().apply { if (id == null) remove("activeSet") else putString("activeSet", id) }.apply() }
    fun createSetlist(name: String) {
        val s = Setlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "Setlist ${setlists.size + 1}" }, emptyList())
        setlists.add(s); saveSetlists(); selectSetlist(s.id)
    }
    fun renameSetlist(id: String, name: String) {
        val i = setlists.indexOfFirst { it.id == id }; if (i < 0 || name.isBlank()) return
        setlists[i] = setlists[i].copy(name = name.trim()); saveSetlists()
    }
    fun deleteSetlist(id: String) { setlists.removeAll { it.id == id }; saveSetlists(); if (activeSetlist == id) selectSetlist(null) }
    fun addToSetlist(slId: String, presetId: String) {
        val i = setlists.indexOfFirst { it.id == slId }; if (i < 0 || presetId in setlists[i].ids) return
        setlists[i] = setlists[i].copy(ids = setlists[i].ids + presetId); saveSetlists()
    }
    fun removeFromSetlist(slId: String, presetId: String) {
        val i = setlists.indexOfFirst { it.id == slId }; if (i < 0) return
        setlists[i] = setlists[i].copy(ids = setlists[i].ids.filter { it != presetId }); saveSetlists()
    }
    fun moveInSetlist(slId: String, presetId: String, delta: Int) {
        val i = setlists.indexOfFirst { it.id == slId }; if (i < 0) return
        val l = setlists[i].ids.toMutableList(); val p = l.indexOf(presetId); val q = p + delta
        if (p < 0 || q !in l.indices) return
        l.removeAt(p); l.add(q, presetId); setlists[i] = setlists[i].copy(ids = l); saveSetlists()
    }
    /** Name of the preset that Next would select (for Stage Mode). */
    fun nextName(): String { val l = navList(); if (l.size < 2) return ""; val pos = l.indexOf(current); return presets[l[if (pos < 0) 0 else (pos + 1) % l.size]].name }

    // ---------- export / import (presets + the models and IRs they use, as one .zip)
    private fun addZip(z: ZipOutputStream, entry: String, f: File) { if (!f.exists()) return; z.putNextEntry(ZipEntry(entry)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
    fun exportBundle(dest: File, onlyCurrent: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = if (onlyCurrent) listOfNotNull(presets.getOrNull(current)) else presets.toList()
                if (list.isEmpty()) { status = "Nothing to export yet."; return@launch }
                val root = JSONObject().put("app", "NoAmpFX").put("version", 2)
                root.put("presets", JSONArray().also { a -> list.forEach { a.put(presetToJson(it)) } })
                if (!onlyCurrent) root.put("setlists", setlistsToJson())
                dest.outputStream().use { os ->
                    ZipOutputStream(os).use { z ->
                        z.putNextEntry(ZipEntry("bundle.json")); z.write(root.toString().toByteArray()); z.closeEntry()
                        val models = HashSet<String>(); val irs = HashSet<String>()
                        list.forEach { p ->
                            p.nam?.let { if (models.add(it)) addZip(z, "models/$it", File(modelsDir, it)) }
                            listOfNotNull(p.cab, p.cab2).forEach { if (irs.add(it)) addZip(z, "irs/$it", File(irDir, it)) }
                        }
                    }
                }
                status = "Exported ${list.size} preset(s)."
            } catch (e: Exception) { status = "Export failed: ${e.message}" }
        }
    }
    private fun storeImported(dir: File, name: String, bytes: ByteArray): String {
        val f = File(dir, name)
        if (!f.exists()) { f.writeBytes(bytes); return name }
        if (f.length() == bytes.size.toLong()) return name                       // same file already present
        val dot = name.lastIndexOf('.'); val base = if (dot > 0) name.substring(0, dot) else name; val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2; var alt = File(dir, "$base ($n)$ext")
        while (alt.exists() && alt.length() != bytes.size.toLong()) { n++; alt = File(dir, "$base ($n)$ext") }
        if (!alt.exists()) alt.writeBytes(bytes)
        return alt.name
    }
    fun importBundle(src: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var json: JSONObject? = null
                val nameMap = HashMap<String, String>(); val irMap = HashMap<String, String>()
                src.inputStream().use { ins ->
                    ZipInputStream(ins).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val n = e.name
                            if (!e.isDirectory) {
                                if (n == "bundle.json") json = JSONObject(String(z.readBytes()))
                                else if (n.startsWith("models/") && !n.contains("..")) nameMap[n.removePrefix("models/")] = storeImported(modelsDir, n.removePrefix("models/"), z.readBytes())
                                else if (n.startsWith("irs/") && !n.contains("..")) irMap[n.removePrefix("irs/")] = storeImported(irDir, n.removePrefix("irs/"), z.readBytes())
                            }
                            z.closeEntry(); e = z.nextEntry
                        }
                    }
                }
                val root = json
                if (root == null) { status = "This is not a NoAmp FX bundle."; return@launch }
                val pa = root.getJSONArray("presets"); var count = 0
                for (i in 0 until pa.length()) {
                    val p = presetFromJson(pa.getJSONObject(i), nameMap, irMap)
                    val ex = presets.indexOfFirst { it.id == p.id }
                    if (ex >= 0) presets[ex] = p else presets.add(p)
                    count++
                }
                persist()
                root.optJSONArray("setlists")?.let { a ->
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val ia = o.getJSONArray("ids")
                        val s = Setlist(o.getString("id"), o.getString("name"), List(ia.length()) { ia.getString(it) })
                        val ex = setlists.indexOfFirst { it.id == s.id }
                        if (ex >= 0) setlists[ex] = s else setlists.add(s)
                    }
                    saveSetlists()
                }
                if (current < 0 && presets.isNotEmpty()) applyPreset(0)
                refreshModels()
                status = "Imported $count preset(s)."
            } catch (e: Exception) { status = "Import failed: ${e.message}" }
        }
    }

    // ---------- MIDI learn & dispatch
    private fun loadBinds() {
        try {
            val a = JSONArray(sp.getString("midi_binds", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                binds.add(MidiBinding(o.getString("t"), o.getInt("ty"), o.getInt("ch"), o.getInt("n"), o.optInt("m", 0),
                    o.optDouble("lo", 0.0).toFloat(), o.optDouble("hi", 1.0).toFloat(), o.optInt("cl", 0), o.optInt("chi", 127), o.optInt("cv", 0)))
            }
        } catch (_: Exception) { }
    }
    private fun saveBinds() {
        val a = JSONArray()
        binds.forEach {
            a.put(JSONObject().put("t", it.target).put("ty", it.type).put("ch", it.ch).put("n", it.num).put("m", it.mode)
                .put("lo", it.lo.toDouble()).put("hi", it.hi.toDouble()).put("cl", it.ccLo).put("chi", it.ccHi).put("cv", it.curve))
        }
        sp.edit().putString("midi_binds", a.toString()).apply()
    }
    fun bindingFor(target: String) = binds.firstOrNull { it.target == target }
    fun startLearn(target: String) { learnTarget = if (learnTarget == target) null else target }
    fun clearBind(target: String) { binds.removeAll { it.target == target }; if (learnTarget == target) learnTarget = null; if (calibTarget == target) calibTarget = null; saveBinds() }
    /** Default pedal range when learning: pitch = 0..+12 (whammy style), others = full range. */
    fun defaultRange(id: Int): Pair<Float, Float> = when (id) {
        P.PITCH_ST -> 0f to 12f
        else -> P.def(id).min to P.def(id).max
    }
    private fun editBind(target: String, f: (MidiBinding) -> MidiBinding) {
        val i = binds.indexOfFirst { it.target == target }; if (i < 0) return
        binds[i] = f(binds[i]); saveBinds()
    }
    fun setBindRange(target: String, lo: Float, hi: Float) = editBind(target) { it.copy(lo = lo, hi = hi) }
    fun cycleBindMode(target: String) = editBind(target) { it.copy(mode = 1 - it.mode) }
    fun cycleCurve(target: String) = editBind(target) { it.copy(curve = (it.curve + 1) % 3) }
    fun resetCalibration(target: String) = editBind(target) { it.copy(ccLo = 0, ccHi = 127) }
    /** Pedal calibration: move the pedal heel-to-toe, then tap Done. */
    fun startCalibrate(target: String) { calibTarget = target; calibMin = 127; calibMax = 0; learnTarget = null }
    fun finishCalibrate(apply: Boolean) {
        val t = calibTarget; calibTarget = null
        if (apply && t != null && calibMax - calibMin >= 8) editBind(t) { it.copy(ccLo = calibMin, ccHi = calibMax) }
    }

    fun updatePcDirect(on: Boolean) { pcDirect = on; sp.edit().putBoolean("pcDirect", on).apply() }
    fun updatePcChannel(ch: Int) { pcChannel = ch.coerceIn(-1, 15); sp.edit().putInt("pcChannel", pcChannel).apply() }
    fun updateClockSync(on: Boolean) { clockSync = on; sp.edit().putBoolean("clockSync", on).apply() }
    fun updateMidiFeedback(on: Boolean) { midiFeedback = on; fbLast.clear(); sp.edit().putBoolean("midiFb", on).apply() }

    // ---------- on-screen pedal controller
    private val EXP_NAMES = listOf("Pitch", "Wah", "Master")
    /** Expression pedal on the screen (0 = heel, 1 = toe). It sends a virtual MIDI CC (channel 16, CC 11) through the normal mapping, so MIDI learn works with it. */
    var expValue by mutableFloatStateOf(0f)

    fun setExp(v: Float) {
        expValue = v.coerceIn(0f, 1f)
        if (expAssigned()) onMidi(MidiMsg(MidiMsg.CC, 15, 11, (expValue * 127f).roundToInt()))   // the user learned this pad to a control: use the MIDI mapping
        else applyExpression(expValue)
    }
    fun expAssigned() = binds.any { it.type == MidiMsg.CC && it.ch == 15 && it.num == 11 }
    /** Name shown on the pedal: the assigned module / parameter. */
    fun expName(): String {
        val t = binds.firstOrNull { it.type == MidiMsg.CC && it.ch == 15 && it.num == 11 }?.target
        return when {
            t == null || t == T.EXPR -> EXP_NAMES[expDest.coerceIn(0, 2)]
            t.startsWith("par:") -> P.def(t.removePrefix("par:").toIntOrNull() ?: 0).name
            else -> t
        }
    }
    /** norm 0..1 -> the current expression destination. */
    fun applyExpression(norm: Float) {
        val x = norm.coerceIn(0f, 1f)
        when (expDest) {
            0 -> setAbs(P.PITCH_ST, -12f + 24f * x, false)
            1 -> setAbs(P.WAH_POS, x, false)
            else -> { val d = P.def(P.MASTER_OUT); setAbs(P.MASTER_OUT, d.min + (d.max - d.min) * x, false) }
        }
    }
    fun nextExpression() { expDest = (expDest + 1) % 3; sp.edit().putInt("expDest", expDest).apply(); if (hasPreset()) edited = true }
    fun toggleExpReverse() { expReverse = !expReverse; sp.edit().putBoolean("expRev", expReverse).apply(); if (hasPreset()) edited = true }
    fun chooseTapAction(a: Int) { tapAction = a; sp.edit().putInt("tapAction", a).apply(); if (hasPreset()) edited = true }
    fun assignStomp(i: Int, onId: Int) { if (i in 0..4) { stompIds[i] = onId; sp.edit().putInt("stomp$i", onId).apply(); if (hasPreset()) edited = true } }
    /** The pedal board is saved inside the preset ("stomp1,..,stomp5;expression destination;tap action;expression reversed"). */
    private fun pedalSnapshot() = stompIds.joinToString(",") + ";" + expDest + ";" + tapAction + ";" + (if (expReverse) 1 else 0)
    private fun applyPedal(s: String?) {
        if (s == null) return                                  // older presets keep the pedal board that is on the screen
        try {
            val p = s.split(";"); val st = p[0].split(",").map { it.toInt() }
            val e = sp.edit()
            if (st.size == 5) for (i in 0..4) { stompIds[i] = st[i]; e.putInt("stomp$i", st[i]) }
            expDest = p[1].toInt().coerceIn(0, 2); tapAction = p[2].toInt().coerceIn(0, 1); expReverse = p[3] == "1"
            e.putInt("expDest", expDest).putInt("tapAction", tapAction).putBoolean("expRev", expReverse).apply()
        } catch (_: Exception) { }
    }

    // ---------- favorites + haptics
    fun isFav(id: String) = id in favs
    fun toggleFav(id: String) { if (!favs.remove(id)) favs.add(id); sp.edit().putString("favs", favs.joinToString(",")).apply() }
    fun toggleHaptics() { haptics = !haptics; Haptics.on = haptics; sp.edit().putBoolean("haptic", haptics).apply() }
    /** Action of the pedal "Tap" switch. */
    fun pressTapSwitch() { if (tapAction == 1) nextExpression() else tapTempo() }
    /** Tap tempo: sets the metronome BPM and the delay tempo. */
    fun tapTempo() { tools.tapMetro(); set(P.DLY_BPM, tools.metBpm.toFloat().coerceIn(40f, 240f)) }

    private fun onMidi(m: MidiMsg) {
        midiLast = m.describe()
        val lt = learnTarget
        if (lt != null) {
            val isPar = lt.startsWith("par:")
            if ((isPar || lt == T.EXPR) && m.type != MidiMsg.CC) return          // continuous targets can only be mapped to CC (expression pedal)
            binds.removeAll { it.target == lt || (it.type == m.type && it.ch == m.ch && it.num == m.num) }
            val (lo, hi) = if (isPar) defaultRange(lt.removePrefix("par:").toIntOrNull() ?: 0) else (0f to 1f)
            binds.add(MidiBinding(lt, m.type, m.ch, m.num, 0, lo, hi)); saveBinds(); learnTarget = null
            return
        }
        val b = binds.firstOrNull { it.type == m.type && it.ch == m.ch && it.num == m.num }
        if (b == null) {
            if (m.type == MidiMsg.PC && pcDirect && (pcChannel < 0 || m.ch == pcChannel)) navList().getOrNull(m.num)?.let { applyPreset(it) }
            return
        }
        val cc = m.type == MidiMsg.CC
        if (cc && calibTarget == b.target) { calibMin = minOf(calibMin, m.value); calibMax = maxOf(calibMax, m.value); return }
        val press = !cc || m.value >= 64
        val t = b.target
        if (t == T.EXPR) { if (cc) applyExpression(b.mapValue(m.value)); return }       // expression pedal -> the selected destination (pitch / wah / master)
        if (t.startsWith("par:")) {                              // expression pedal -> parameter
            if (cc) { val id = t.removePrefix("par:").toIntOrNull() ?: return; setAbs(id, b.mapValue(m.value), false) }
            return
        }
        if (t == T.TUNER) { if (cc && b.mode == 1) setTuner(m.value >= 64) else if (press) setTuner(!tunerOn); return }
        if (t.startsWith("mod:")) {
            val id = t.removePrefix("mod:").toIntOrNull() ?: return
            if (cc && b.mode == 1) { val want = m.value >= 64; if (onAbs(id) != want) setAbs(id, if (want) 1f else 0f) }
            else if (press) toggleAbs(id)
            return
        }
        if (!(if (cc && b.mode == 1) true else press)) return
        when {
            t == T.NEXT -> next()
            t == T.NEXT_EXPR -> nextExpression()
            t == T.PREV -> prev()
            t.startsWith("preset:") -> { val id = t.removePrefix("preset:"); val i = presets.indexOfFirst { it.id == id }; if (i >= 0) applyPreset(i) }
            t == T.SCENE_A -> selectScene(0)
            t == T.SCENE_B -> selectScene(1)
            t == T.SCENE_C -> selectScene(2)
            t == T.SCENE_D -> selectScene(3)
            t == T.SCENE_E -> selectScene(4)
            t == T.SCENE_TOGGLE -> selectScene((scene + 1) % 5)
            t == T.LOOPER -> tools.lpPress()
            t == T.LOOP_STOP -> tools.lpStop()
            t == T.LOOP_CLEAR -> tools.lpClear()
            t == T.PLAYER -> tools.togglePlay()
            t == T.METRO -> tools.updateMetOn(!tools.metOn)
        }
    }

    // ---------- LED feedback to the controller (CC / Note value 127 = on, 0 = off)
    private fun feedbackValue(t: String): Int? = when {
        t.startsWith("mod:") -> t.removePrefix("mod:").toIntOrNull()?.let { if (onAbs(it)) 127 else 0 }
        t == T.TUNER -> if (tunerOn) 127 else 0
        t.startsWith("preset:") -> if (presets.getOrNull(current)?.id == t.removePrefix("preset:")) 127 else 0
        t == T.SCENE_A -> if (scene == 0) 127 else 0
        t == T.SCENE_B -> if (scene == 1) 127 else 0
        t == T.SCENE_C -> if (scene == 2) 127 else 0
        t == T.SCENE_D -> if (scene == 3) 127 else 0
        t == T.SCENE_E -> if (scene == 4) 127 else 0
        t == T.SCENE_TOGGLE -> if (scene != 0) 127 else 0
        t == T.LOOPER -> if (tools.lpState in 1..3) 127 else 0
        t == T.PLAYER -> if (tools.playing) 127 else 0
        t == T.METRO -> if (tools.metOn) 127 else 0
        else -> null
    }
    private fun feedbackTick() {
        if (!midiFeedback || !midi.hasOutput) return
        val cnt = midi.devices.size; if (cnt != fbDevCount) { fbDevCount = cnt; fbLast.clear() }
        for (b in binds.toList()) {
            val v = feedbackValue(b.target) ?: continue
            if (fbLast[b.target] == v) continue
            fbLast[b.target] = v
            when (b.type) {
                MidiMsg.CC -> midi.send(byteArrayOf((0xB0 or b.ch).toByte(), b.num.toByte(), v.toByte()))
                MidiMsg.NOTE -> midi.send(byteArrayOf((0x90 or b.ch).toByte(), b.num.toByte(), v.toByte()))
            }
        }
    }
}
