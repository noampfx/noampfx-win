package com.noamp.fx

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class RecFile(val file: File) {
    val isDi get() = file.name.endsWith("_DI.wav")
    val isFull get() = file.name.endsWith("_Full.wav")
    val label: String get() = file.name.removePrefix("NoAmpFX_").removeSuffix(".wav")
}

/** One song in the music player queue. */
class QueueItem(val file: File, val title: String)

/** Practice and recording tools: backing-track player (with reamp), looper, metronome and WAV recorder. */
class Tools(private val ctx: Context, private val scope: CoroutineScope, private val sp: SharedPreferences) {
    // ------------------------------------------------------------ backing track / reamp
    var trackName by mutableStateOf<String?>(null)
    var trackDur by mutableDoubleStateOf(0.0)
    var trackPos by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loadProgress by mutableFloatStateOf(0f)
    var tempo by mutableFloatStateOf(1f)
    var trackDb by mutableFloatStateOf(0f)
    /** 0 = mixed into the output after the amp · 1 = reamp (sent through the signal chain) */
    var route by mutableIntStateOf(0)
    var loopOn by mutableStateOf(false)
    var loopA by mutableDoubleStateOf(0.0)
    var loopB by mutableDoubleStateOf(0.0)
    var message by mutableStateOf("")

    // ---- music player: queue, shuffle / repeat, tags and cover art
    val queue = mutableStateListOf<QueueItem>()
    var qIndex by mutableIntStateOf(-1)
    var shuffle by mutableStateOf(sp.getBoolean("shuffle", false))
    var repeat by mutableIntStateOf(sp.getInt("repeat", 0))          // 0 off · 1 all · 2 one
    var trackTitle by mutableStateOf<String?>(null)
    var trackArtist by mutableStateOf<String?>(null)
    var trackAlbum by mutableStateOf<String?>(null)
    var trackArt by mutableStateOf<ImageBitmap?>(null)
    private var userStop = true          // true when the user (not the end of the file) stopped playback
    init {
        sp.getString("queue", null)?.split("\n")?.forEach { l ->
            val p = l.split("\t", limit = 2)
            if (p[0].isNotBlank()) queue.add(QueueItem(File(p[0]), p.getOrElse(1) { p[0].substringAfterLast('\\').substringAfterLast('/') }))
        }
        qIndex = sp.getInt("qIndex", -1).takeIf { it in queue.indices } ?: -1
    }
    /** Saves a window of at most 300 songs around the current one (the queue can hold the whole library). */
    private fun saveQueue() {
        val from = (qIndex - 10).coerceAtLeast(0)
        val part = queue.drop(from).take(300)
        sp.edit().putString("queue", part.joinToString("\n") { it.file.path + "\t" + it.title }).putInt("qIndex", if (qIndex < 0) -1 else qIndex - from).apply()
    }
    /** Replaces the queue with [list] and plays [start] (used by the library: songs, albums, artists, folders, genres, playlists). */
    fun playSongs(list: List<Song>, start: Int) {
        if (list.isEmpty() || loading) return
        queue.clear(); queue.addAll(list.map { QueueItem(it.file, it.title) })
        playIndex(start.coerceIn(0, queue.lastIndex))
    }
    fun addToQueue(s: Song) { queue.add(QueueItem(s.file, s.title)); saveQueue() }
    private fun niceName(n: String) = n.substringBeforeLast('.', n).replace('_', ' ')

    /** Adds songs to the end of the queue (and keeps access to them after a restart). The first song is loaded if nothing is loaded yet. */
    fun addTracks(files: List<File>) {
        scope.launch(Dispatchers.IO) {
            val items = files.map { f -> QueueItem(f, niceName(f.name)) }
            val first = queue.size
            queue.addAll(items); saveQueue()
            if (trackName == null && !loading && first < queue.size) playIndex(first, autoplay = false)
        }
    }
    fun loadTrack(file: File) = addTracks(listOf(file))
    /** From the recorder: load one of the recordings into the player (reamp = send it through the signal chain). */
    fun loadTrackFile(f: File, reamp: Boolean) {
        route = if (reamp) 1 else 0; Engine.nativePlayerCtl(3, route.toDouble())
        var i = queue.indexOfFirst { it.file == f }
        if (i < 0) { queue.add(QueueItem(f, niceName(f.name))); i = queue.lastIndex }
        playIndex(i, autoplay = false)
    }
    fun playIndex(i: Int, autoplay: Boolean = true) {
        if (i !in queue.indices || loading) return
        qIndex = i; saveQueue()
        loadAsync(queue[i].title, queue[i].file, autoplay)
    }
    private fun nextIndex(auto: Boolean): Int {
        if (queue.isEmpty()) return -1
        if (shuffle && queue.size > 1) { var r: Int; do { r = Random.nextInt(queue.size) } while (r == qIndex); return r }
        val n = qIndex + 1
        return if (n < queue.size) n else if (repeat == 1 || !auto) 0 else -1
    }
    fun next() { val n = nextIndex(false); if (n >= 0) playIndex(n) }
    fun prev() {
        if (trackPos > 3.0 || queue.size < 2) { seek(0.0); return }
        playIndex(if (qIndex > 0) qIndex - 1 else queue.size - 1)
    }
    fun toggleShuffle() { shuffle = !shuffle; sp.edit().putBoolean("shuffle", shuffle).apply() }
    fun cycleRepeat() { repeat = (repeat + 1) % 3; sp.edit().putInt("repeat", repeat).apply() }
    fun removeFromQueue(i: Int) {
        if (i !in queue.indices) return
        queue.removeAt(i)
        if (i < qIndex) qIndex-- else if (i == qIndex) qIndex = -1
        saveQueue()
    }
    fun clearQueue() { if (loading) return; queue.clear(); qIndex = -1; saveQueue(); clearTrack() }
    private fun onTrackEnded() {
        if (repeat == 2) { playing = true; userStop = false; Engine.nativePlayerCtl(0, 1.0); return }
        val n = nextIndex(true)
        if (n >= 0) playIndex(n)
    }
    private fun readMeta(file: File) {
        trackTitle = null; trackArtist = null; trackAlbum = null; trackArt = null
        val t = TagReader.read(file, true) ?: return
        trackTitle = t.title; trackArtist = t.artist; trackAlbum = t.album
        t.art?.let { b -> trackArt = try { org.jetbrains.skia.Image.makeFromEncoded(b).toComposeImageBitmap() } catch (_: Throwable) { null } }
    }
    private var bgJob: kotlinx.coroutines.Job? = null
    private var decodedSec = Double.MAX_VALUE      // seconds of the song that are decoded so far (seeking further waits for the background decode)
    private val FIRST_SEC = 25

    /**
     * Long songs start after only the first [FIRST_SEC] seconds are decoded (almost instantly); the rest is decoded in the background
     * and swapped in by the engine without a gap. Short songs are decoded completely first.
     */
    private fun loadAsync(name: String, file: File, autoplay: Boolean = false) {
        if (loading) return
        loading = true
        scope.launch(Dispatchers.IO) {
            AudioFile.cancel = true; bgJob?.join(); AudioFile.cancel = false              // stop the background decode of the previous song
            loadProgress = 0f; message = ""; userStop = true; Engine.nativePlayerCtl(0, 0.0); playing = false; decodedSec = Double.MAX_VALUE
            val full = AudioFile.probeDuration(file)
            val quick = full > FIRST_SEC + 5
            val first = AudioFile.load(file, limitSec = if (quick) FIRST_SEC else 600) { loadProgress = it }
            if (first != null) {
                val dur = if (quick) minOf(full, 600.0) else first
                trackName = name; trackDur = dur; trackPos = 0.0; loopA = 0.0; loopB = dur; loopOn = false
                Engine.nativePlayerCtl(5, 0.0); Engine.nativePlayerCtl(6, dur); Engine.nativePlayerCtl(4, 0.0)
                Engine.nativePlayerCtl(1, tempo.toDouble()); Engine.nativePlayerCtl(2, trackDb.toDouble()); Engine.nativePlayerCtl(3, route.toDouble())
                if (dur >= 599) message = "Track was cut at 10 minutes."
                if (quick) decodedSec = first
                readMeta(file)
                if (autoplay) { userStop = false; playing = true; Engine.nativePlayerCtl(0, 1.0) }
                loading = false
                if (quick) bgJob = scope.launch(Dispatchers.IO) {
                    val whole = AudioFile.load(file, keep = true)
                    if (whole != null) {
                        val oldDur = trackDur; trackDur = whole
                        if (loopB >= oldDur - 0.01) { loopB = whole; Engine.nativePlayerCtl(6, whole) }
                    }
                    decodedSec = Double.MAX_VALUE
                }
            } else { message = "Could not read this audio file."; loading = false }
        }
    }
    fun clearTrack() {
        if (loading) return
        userStop = true; Engine.nativePlayerCtl(0, 0.0)
        trackName = null; trackDur = 0.0; trackPos = 0.0; playing = false; trackTitle = null; trackArtist = null; trackAlbum = null; trackArt = null
        scope.launch(Dispatchers.IO) { AudioFile.cancel = true; bgJob?.join(); AudioFile.cancel = false; Engine.nativePlayerClear() }   // wait for a background decode before clearing the engine data
    }
    fun togglePlay() { if (trackName == null) return; playing = !playing; userStop = !playing; Engine.nativePlayerCtl(0, if (playing) 1.0 else 0.0) }
    fun seek(sec: Double) { val t = sec.coerceIn(0.0, trackDur).coerceAtMost((decodedSec - 1.0).coerceAtLeast(0.0)); Engine.nativePlayerCtl(7, t); trackPos = t }
    fun updateTempo(t: Float) { tempo = t.coerceIn(0.5f, 1.5f); Engine.nativePlayerCtl(1, tempo.toDouble()) }
    fun updateTrackDb(db: Float) { trackDb = db.coerceIn(-30f, 6f); Engine.nativePlayerCtl(2, trackDb.toDouble()) }
    fun updateRoute(r: Int) { route = r; Engine.nativePlayerCtl(3, r.toDouble()) }
    fun markA() { loopA = trackPos; if (loopB <= loopA + 1.0) loopB = trackDur; pushLoop() }
    fun markB() { loopB = trackPos.coerceAtLeast(loopA + 1.0); pushLoop() }
    fun updateLoop(on: Boolean) { loopOn = on; Engine.nativePlayerCtl(4, if (on) 1.0 else 0.0) }
    private fun pushLoop() { Engine.nativePlayerCtl(5, loopA); Engine.nativePlayerCtl(6, loopB) }

    // ------------------------------------------------------------ looper
    var lpState by mutableIntStateOf(0)      // 0 empty · 1 recording · 2 playing · 3 overdub · 4 stopped
    var lpPos by mutableDoubleStateOf(0.0)
    var lpLen by mutableDoubleStateOf(0.0)
    var lpDb by mutableFloatStateOf(sp.getFloat("lpDb", 0f))
    fun lpPress() = Engine.nativeLooperCmd(1)
    fun lpStop() = Engine.nativeLooperCmd(2)
    fun lpClear() = Engine.nativeLooperCmd(3)
    fun updateLpDb(db: Float) { lpDb = db; sp.edit().putFloat("lpDb", db).apply(); Engine.nativeLooperLevel(db) }

    // ------------------------------------------------------------ metronome
    var metOn by mutableStateOf(false)
    var metBpm by mutableIntStateOf(sp.getInt("metBpm", 100))
    var metBeats by mutableIntStateOf(sp.getInt("metBeats", 4))          // numerator of the time signature
    var metDen by mutableIntStateOf(sp.getInt("metDen", 4))              // denominator (display; the BPM counts these beats)
    var metSub by mutableIntStateOf(sp.getInt("metSub", 0))              // subdivision pattern 0..13 (see SUB_PATTERNS in media.h)
    var metDb by mutableFloatStateOf(sp.getFloat("metDb", -8f))
    private var metTaps = listOf<Long>()
    fun applyMetro() { sp.edit().putInt("metBpm", metBpm).putInt("metBeats", metBeats).putInt("metDen", metDen).putInt("metSub", metSub).putFloat("metDb", metDb).apply(); Engine.nativeMetroSet(metOn, metBpm.toFloat(), metBeats, metDb, metSub) }
    fun updateMetOn(on: Boolean) { metOn = on; applyMetro() }
    fun updateMetBpm(b: Int) { metBpm = b.coerceIn(30, 300); applyMetro() }
    fun updateMetBeats(n: Int) { metBeats = n.coerceIn(1, 12); applyMetro() }
    fun updateMetDen(d: Int) { metDen = d; applyMetro() }
    fun updateMetSub(i: Int) { metSub = i.coerceIn(0, 13); applyMetro() }
    fun updateMetDb(db: Float) { metDb = db.coerceIn(-30f, 6f); applyMetro() }
    fun tapMetro() {
        val now = System.currentTimeMillis()
        metTaps = (metTaps.filter { now - it < 2500 } + now).takeLast(5)
        if (metTaps.size >= 2) updateMetBpm((60000f * (metTaps.size - 1) / (metTaps.last() - metTaps.first())).toInt())
    }

    // ------------------------------------------------------------ recorder (DI and processed mix, 24-bit WAV)
    private val recDir = File(ctx.filesDir, "recordings").apply { mkdirs() }
    val recFiles = mutableStateListOf<RecFile>()
    var recording by mutableStateOf(false)
    var recDi by mutableStateOf(true)
    var recMix by mutableStateOf(true)
    var recSecs by mutableIntStateOf(0)
    /** Full Record: true while the capture service (audio of other apps) is running. */
    var captureActive by mutableStateOf(false)
    /** Level of the captured music/video audio in the Full Record file (dB). */
    var fullDb by mutableFloatStateOf(sp.getFloat("fullDb", 0f))
    fun updateFullDb(db: Float) { fullDb = db.coerceIn(-30f, 12f); sp.edit().putFloat("fullDb", fullDb).apply() }
    private var recStartMs = 0L
    private var writer: Job? = null

    init { refreshRecs(); applyMetro(); Engine.nativeLooperLevel(lpDb) }

    fun refreshRecs() {
        recFiles.clear()
        recDir.listFiles { f -> f.extension.equals("wav", true) }?.sortedByDescending { it.lastModified() }?.forEach { recFiles.add(RecFile(it)) }
    }

    /** The processed-mix WAV of the newest recording (used by the video recorder). */
    var lastMix: File? = null

    /** [mixOnly] = record just the processed output (used while filming), ignoring the DI / Mix / Full switches. */
    fun startRec(sampleRate: Int, mixOnly: Boolean = false) {
        val full = !mixOnly && captureActive && CaptureHub.running
        if (recording || (!mixOnly && !recDi && !recMix && !full)) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val di = recDi && !mixOnly; val mix = recMix || mixOnly
        val fDi = File(recDir, "NoAmpFX_${stamp}_DI.wav"); val fMix = File(recDir, "NoAmpFX_${stamp}_Mix.wav"); val fFull = File(recDir, "NoAmpFX_${stamp}_Full.wav")
        recording = true; recSecs = 0; recStartMs = System.currentTimeMillis(); message = ""
        lastMix = if (mix) fMix else null
        if (full) { CaptureHub.reset(); CaptureHub.collecting = true }
        Engine.nativeRecStart(di, mix || full)
        writer = scope.launch(Dispatchers.IO) {
            val wd = if (di) WavWriter(fDi, 1, sampleRate) else null
            val wm = if (mix) WavWriter(fMix, 2, sampleRate) else null
            val wf = if (full) WavWriter(fFull, 2, sampleRate) else null
            val buf = FloatArray(8192); val cap = FloatArray(8192)
            try {
                while (isActive) {
                    var n1 = 0; var n2 = 0
                    if (wd != null) { n1 = Engine.nativeRecRead(0, buf); if (n1 > 0) wd.write(buf, n1) }
                    if (wm != null || wf != null) {
                        n2 = Engine.nativeRecRead(1, buf)
                        if (n2 > 0) {
                            wm?.write(buf, n2)
                            if (wf != null) {      // guitar mix + audio of other apps (YouTube, Spotify ...) captured by CaptureService
                                val got = CaptureHub.read(cap, n2); val g = Math.pow(10.0, fullDb / 20.0).toFloat()
                                for (i in 0 until n2) { val v = buf[i] + (if (i < got) cap[i] * g else 0f); buf[i] = if (v > 1f) 1f else if (v < -1f) -1f else v }
                                wf.write(buf, n2)
                            }
                        }
                    }
                    if (!recording && n1 == 0 && n2 == 0) break
                    if (n1 == 0 && n2 == 0) delay(30)
                }
            } finally { wd?.close(); wm?.close(); wf?.close(); CaptureHub.collecting = false }
            if (Engine.nativeRecDropped() > 0) message = "Recording was interrupted briefly (device too slow). Check the files."
            refreshRecs()
        }
    }
    fun stopRec() { if (!recording) return; recording = false; Engine.nativeRecStop() }
    /** Stops recording and suspends until the WAV files are completely written. */
    suspend fun stopRecAndWait() { stopRec(); writer?.join() }
    fun saveCopy(f: RecFile, dest: File) {
        scope.launch(Dispatchers.IO) { try { f.file.copyTo(dest, overwrite = true); message = "Saved." } catch (_: Exception) { message = "Could not save the file." } }
    }
    fun deleteRec(f: RecFile) { f.file.delete(); recFiles.remove(f) }

    val active: Boolean get() = recording || playing || lpState != 0 || metOn
    val fullSupported: Boolean get() = false      // capturing the audio of other apps is Android-only

    /** Called from the Rig polling loop with the native status array. */
    /** Metronome beat: [beatSeq] changes on every beat, [beatIdx] = position in the bar (0 = downbeat). Used for the blink in the UI. */
    var beatSeq by mutableIntStateOf(0)
    var beatIdx by mutableIntStateOf(0)
    private var lastSeq = -1
    fun poll(st: FloatArray) {
        lpState = st[0].toInt(); lpPos = st[1].toDouble(); lpLen = st[2].toDouble()
        val sq = st[10].toInt()
        if (sq != lastSeq) { if (lastSeq >= 0 && metOn) { beatIdx = st[11].toInt(); beatSeq++ }; lastSeq = sq }
        if (trackName != null) {
            val was = playing
            trackPos = st[3].toDouble(); playing = st[5] > 0.5f
            if (was && !playing && !userStop && !loading) onTrackEnded()          // the file played to its end
        }
        captureActive = CaptureHub.running
        if (recording) recSecs = ((System.currentTimeMillis() - recStartMs) / 1000).toInt()
    }
}
