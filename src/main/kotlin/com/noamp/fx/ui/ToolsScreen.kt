package com.noamp.fx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.noamp.fx.openInShell
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.noamp.fx.RecFile
import com.noamp.fx.Rig

private fun fmtTime(s: Double): String { val t = s.toInt().coerceAtLeast(0); return "%d:%02d".format(t / 60, t % 60) }

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, color: Color, text: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Dim, fontSize = 10.sp, modifier = Modifier.width(70.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = C.Line))
        Text(text, color = C.Text, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.width(62.dp))
    }
}

/** One tool page of the bottom menu: 1 = metronome, 2 = record, 3 = music player, 4 = looper, 6 = mixer. */
@Composable
fun ToolsScreen(rig: Rig, page: Int) {
    if (page == 6) { MixerScreen(rig); return }
    val t = rig.tools
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!rig.running) Text("Start the engine to use these tools.", color = C.Amber, fontSize = 12.sp)
        when (page) {
            1 -> MetroPanel(rig)
            2 -> RecorderPanel(rig)
            3 -> MusicPlayerPanel(rig)
            else -> Panel(title = "Looper") { LooperBody(rig) }
        }
        if (t.message.isNotEmpty()) Text(t.message, color = C.Amber, fontSize = 11.sp)
    }
}

@Composable
private fun LooperBody(rig: Rig) {
    val t = rig.tools
    val (label, col) = when (t.lpState) {
        0 -> "● REC" to C.Red; 1 -> "▶ PLAY" to C.Red; 2 -> "OVERDUB" to C.Amber; 3 -> "▶ PLAY" to C.Green; else -> "▶ PLAY" to C.Green
    }
    val state = listOf("EMPTY", "RECORDING", "PLAYING", "OVERDUBBING", "STOPPED")[t.lpState.coerceIn(0, 4)]
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Led(t.lpState in 1..3, if (t.lpState == 1) C.Red else C.Green)
            Spacer(Modifier.width(8.dp))
            Text(state, color = C.Text, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
            Text("${fmtTime(t.lpPos)} / ${fmtTime(t.lpLen)}", color = C.Dim, fontSize = 11.sp, fontFamily = Mono)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(C.Line)) {
            val frac = if (t.lpLen > 0.01 && t.lpState != 1) (t.lpPos / t.lpLen).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(col))
        }
        Spacer(Modifier.height(10.dp))
        Btn(label, col, enabled = rig.running, modifier = Modifier.fillMaxWidth()) { t.lpPress() }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Stop", C.Dim, enabled = t.lpState == 2 || t.lpState == 3) { t.lpStop() }
            Btn("Clear", C.Red, enabled = t.lpState != 0) { t.lpClear() }
        }
        LabeledSlider("Level", t.lpDb, -30f..6f, C.Green, "%.0f dB".format(t.lpDb)) { t.updateLpDb(it) }
        Text("Press once to record, again to start the loop, again to overdub. Records the final stereo mix (up to 60 s).", color = C.Dim, fontSize = 10.sp)
    }
}

@Composable
private fun MetroPanel(rig: Rig) {
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF0C1118)).border(1.5.dp, Color(0xFF1C2532), shape).padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("METRONOME", color = Color(0xFF8FA0B8), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            BeatDots(rig.tools)
        }
        Spacer(Modifier.height(16.dp))
        MetronomeUi(rig)
    }
}

/** Recorder: DI (dry guitar) and/or processed mix to WAV, plus the looper. */
@Composable
private fun RecorderPanel(rig: Rig) {
    Panel(title = "Recorder") { AudioRecorderBody(rig) }
}

@Composable
private fun AudioRecorderBody(rig: Rig) {
    val t = rig.tools
    var looperView by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RecFile?>(null) }
    val saveL = rememberSaveFile("Save recording", "wav") { dest -> val f = pending; if (f != null) t.saveCopy(f, dest); pending = null }
    val sr = rig.meters[13].toInt().let { if (it > 1000) it else rig.sampleRate }
    Column(Modifier.fillMaxWidth()) {
        if (!t.recording) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PowerToggle(looperView, C.Amber, "Looper") { looperView = !looperView }                       // looper view <-> recorder view
                PowerToggle(t.recDi, C.Red, "DI Dry") { t.recDi = !t.recDi; looperView = false }
                PowerToggle(t.recMix, C.Red, "Mix Processed") { t.recMix = !t.recMix; looperView = false }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (looperView && !t.recording) { LooperBody(rig); return@Column }
        Btn(if (t.recording) "■ Stop  ${fmtTime(t.recSecs.toDouble())}" else "● Record", C.Red, enabled = rig.running && (t.recording || t.recDi || t.recMix), modifier = Modifier.fillMaxWidth()) {
            if (t.recording) t.stopRec() else t.startRec(sr)
        }
        Text("The DI file is your clean guitar signal (24-bit WAV). Load it back as a Reamp to re-amp it with any preset.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        if (t.recFiles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            t.recFiles.forEach { rf ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (rf.isDi) "DI" else if (rf.isFull) "FULL" else "MIX", color = if (rf.isDi) C.Amber else if (rf.isFull) C.Red else C.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, modifier = Modifier.width(38.dp))
                        Text(rf.label.removeSuffix("_DI").removeSuffix("_Mix").removeSuffix("_Full"), color = C.Text, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
                        Text("%.1f MB".format(rf.file.length() / 1048576.0), color = C.Dim, fontSize = 10.sp, fontFamily = Mono)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Save…", C.Green) { pending = rf; saveL(rf.file.name) }
                        if (rf.isDi) Btn("Reamp", C.Amber, enabled = !t.loading) { t.loadTrackFile(rf.file, true) }
                        Btn("Player", C.Accent, enabled = !t.loading) { t.loadTrackFile(rf.file, false) }
                        Btn("Show", C.Dim) { openInShell(rf.file.parentFile ?: rf.file) }
                        Btn("Delete", C.Red) { t.deleteRec(rf) }
                    }
                }
            }
        }
    }
}

// ================================================================================================ Music Player
/** Compact music player: cover + title, seekbar, transport, [Library] [File] [Clear] [Practice & Reamp], queue. */
@Composable
private fun MusicPlayerPanel(rig: Rig) {
    val t = rig.tools
    val pick = rememberOpenFiles("Add songs", "mp3", "wav", "flac") { t.addTracks(it) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var practice by remember { mutableStateOf(false) }
    var lib by remember { mutableStateOf(false) }
    val has = t.trackName != null
    val title = t.trackTitle ?: t.trackName ?: t.queue.getOrNull(t.qIndex)?.title
    val sub = listOfNotNull(t.trackArtist, t.trackAlbum).joinToString(" · ")
    val pos = (drag ?: t.trackPos.toFloat()).toDouble()
    Panel(title = "Music Player") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(C.PanelHi, C.Bg))).border(1.dp, C.Line, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                val art = t.trackArt
                if (art != null) Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("♪", color = C.Off, fontSize = 40.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title ?: "No song loaded", color = if (title != null) C.Text else C.Dim, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (sub.isNotEmpty()) sub else if (title != null) "Unknown artist" else "Open the Library to pick songs", color = C.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(if (t.loading) "Loading…" else "${fmtTime(pos)} / ${fmtTime(t.trackDur)}", color = if (t.loading) C.Amber else C.Dim, fontSize = 11.sp, fontFamily = Mono)
            }
        }
        Slider(
            value = drag ?: t.trackPos.toFloat(), onValueChange = { drag = it }, enabled = has,
            onValueChangeFinished = { drag?.let { t.seek(it.toDouble()) }; drag = null },
            valueRange = 0f..t.trackDur.toFloat().coerceAtLeast(1f), modifier = Modifier.fillMaxWidth().height(30.dp),
            colors = SliderDefaults.colors(thumbColor = C.Green, activeTrackColor = C.Green, inactiveTrackColor = C.Line)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TransportBtn(4, if (t.shuffle) C.Green else C.Dim, 20.dp) { t.toggleShuffle() }
            TransportBtn(3, C.Text, 26.dp) { t.prev() }
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(if (rig.running) C.Green else C.Off).clickable(enabled = rig.running) {
                    if (!has && t.queue.isNotEmpty()) t.playIndex(if (t.qIndex in t.queue.indices) t.qIndex else 0) else t.togglePlay()
                },
                contentAlignment = Alignment.Center
            ) { Canvas(Modifier.size(24.dp)) { drawTransport(if (t.playing) 1 else 0, C.Bg) } }
            TransportBtn(2, C.Text, 26.dp) { t.next() }
            Box(contentAlignment = Alignment.Center) {
                TransportBtn(5, if (t.repeat != 0) C.Green else C.Dim, 20.dp) { t.cycleRepeat() }
                if (t.repeat == 2) Text("1", color = C.Green, fontSize = 8.sp, fontWeight = FontWeight.Black)
            }
        }
        if (!rig.running) Text("Start the engine to play.", color = C.Amber, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction("Library", C.Green, 1f, !t.loading) { lib = true }
            MiniAction("File", C.Dim, 0.7f, !t.loading) { pick() }
            MiniAction("Clear", C.Dim, 0.75f, t.queue.isNotEmpty() && !t.loading) { t.clearQueue() }
            MiniAction("Practice & Reamp", C.Amber, 1.5f, true) { practice = true }
        }
        if (t.queue.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("QUEUE · ${t.queue.size}", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                itemsIndexed(t.queue) { i, q ->
                    val cur = i == t.qIndex
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (cur) C.Green.copy(alpha = 0.10f) else Color.Transparent).clickable(enabled = !t.loading) { t.playIndex(i) }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (cur && t.playing) "♪" else "${i + 1}", color = if (cur) C.Green else C.Dim, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text(q.title, color = if (cur) C.Green else C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (cur && t.trackArtist != null) Text(t.trackArtist ?: "", color = C.Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("✕", color = C.Dim, fontSize = 13.sp, modifier = Modifier.clickable { t.removeFromQueue(i) }.padding(horizontal = 12.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
    if (lib) LibraryDialog(rig) { lib = false }
    if (practice) PracticeDialog(rig) { practice = false }
}

/** Small outlined action button for a row of several buttons ([w] = share of the row width). */
@Composable
private fun RowScope.MiniAction(text: String, color: Color, w: Float, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp); val c = if (enabled) color else C.Off
    Box(
        Modifier.weight(w).height(40.dp).clip(shape).background(c.copy(alpha = 0.08f)).border(1.dp, c.copy(alpha = 0.6f), shape).clickable(enabled = enabled) { onClick() }.padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp) }
}

/** Popup: tempo, level, reamp route and A-B loop of the music player. */
@Composable
private fun PracticeDialog(rig: Rig, onClose: () -> Unit) {
    val t = rig.tools
    val has = t.trackName != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Practice & Reamp") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledSlider("Tempo", t.tempo, 0.5f..1.5f, C.Green, "${(t.tempo * 100).toInt()}%") { t.updateTempo(it) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f).forEach { v -> Chip("${(v * 100).toInt()}%", kotlin.math.abs(t.tempo - v) < 0.01f, C.Green) { t.updateTempo(v) } }
                }
                Text("Tempo changes keep the pitch (time-stretch).", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                LabeledSlider("Level", t.trackDb, -30f..6f, C.Green, "%.0f dB".format(t.trackDb)) { t.updateTrackDb(it) }
                Spacer(Modifier.height(4.dp))
                Segmented(listOf("Mix after amp", "Reamp through amp"), t.route, C.Green) { t.updateRoute(it) }
                Text(if (t.route == 1) "Reamp: the song is fed into the signal chain like a guitar signal (amp, cab, effects)." else "The song is mixed into the output without going through the amp.",
                    color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Set A", C.Amber, enabled = has) { t.markA() }
                    Btn("Set B", C.Amber, enabled = has) { t.markB() }
                    PowerToggle(t.loopOn, C.Amber, "LOOP A–B") { t.updateLoop(!t.loopOn) }
                }
                Text("A ${fmtTime(t.loopA)} → B ${fmtTime(t.loopB)}", color = C.Dim, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
}

@Composable
fun TransportBtn(kind: Int, color: Color, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(size + 20.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) { Canvas(Modifier.size(size)) { drawTransport(kind, color) } }
}

/** 0 play · 1 pause · 2 next · 3 previous · 4 shuffle · 5 repeat */
fun DrawScope.drawTransport(kind: Int, color: Color) {
    val w = size.width; val sw = w * 0.09f; val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun tri(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) { drawPath(Path().apply { moveTo(x0 * w, y0 * w); lineTo(x1 * w, y1 * w); lineTo(x2 * w, y2 * w); close() }, color) }
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(color, Offset(x0 * w, y0 * w), Offset(x1 * w, y1 * w), sw, StrokeCap.Round)
    when (kind) {
        0 -> tri(.26f, .14f, .86f, .5f, .26f, .86f)
        1 -> { drawRoundRect(color, Offset(.2f * w, .14f * w), Size(.22f * w, .72f * w), CornerRadius(2f, 2f)); drawRoundRect(color, Offset(.58f * w, .14f * w), Size(.22f * w, .72f * w), CornerRadius(2f, 2f)) }
        2 -> { tri(.12f, .18f, .70f, .5f, .12f, .82f); drawRoundRect(color, Offset(.76f * w, .18f * w), Size(.12f * w, .64f * w), CornerRadius(2f, 2f)) }
        3 -> { tri(.88f, .18f, .30f, .5f, .88f, .82f); drawRoundRect(color, Offset(.12f * w, .18f * w), Size(.12f * w, .64f * w), CornerRadius(2f, 2f)) }
        4 -> { line(.10f, .30f, .90f, .70f); line(.10f, .70f, .90f, .30f); tri(.96f, .70f, .74f, .62f, .80f, .84f); tri(.96f, .30f, .80f, .16f, .74f, .38f) }
        else -> {
            drawPath(Path().apply { moveTo(.16f * w, .56f * w); lineTo(.16f * w, .34f * w); lineTo(.74f * w, .34f * w) }, color, style = st)
            drawPath(Path().apply { moveTo(.84f * w, .44f * w); lineTo(.84f * w, .66f * w); lineTo(.26f * w, .66f * w) }, color, style = st)
            tri(.92f, .34f, .72f, .22f, .72f, .46f); tri(.08f, .66f, .28f, .54f, .28f, .78f)
        }
    }
}
