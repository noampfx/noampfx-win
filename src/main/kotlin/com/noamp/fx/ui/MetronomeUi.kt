package com.noamp.fx.ui

import androidx.compose.animation.core.Animatable
import com.noamp.fx.Tools
import com.noamp.fx.Haptics
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noamp.fx.Rig
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Click positions (in 12ths of a beat) of the 14 subdivision patterns - same table as SUB_PATTERNS in cpp/media.h. */
private val SUB_POS = listOf(
    listOf(0), listOf(0, 6), listOf(4, 8), listOf(0, 4, 8), listOf(8), listOf(0, 8), listOf(0, 4),
    listOf(4), listOf(0, 3, 6, 9), listOf(3, 9), listOf(0, 3, 6), listOf(0, 6, 9), listOf(0, 9), listOf(0, 3)
)
private val DENOMS = listOf(1, 2, 4, 8, 16)

/** Metronome controls: [Play / Tap] [BPM knob] [Volume knob] [Subdivision / Time signature]. The BPM knob blinks on every beat. */
@Composable
fun MetronomeUi(rig: Rig) {
    val t = rig.tools
    var subDlg by remember { mutableStateOf(false) }
    var sigDlg by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(t.beatSeq) { if (t.metOn && t.beatSeq > 0) { flash.snapTo(if (t.beatIdx == 0) 1f else 0.6f); flash.animateTo(0f, tween(240)) } }
    val white = Color(0xFFF1F3F6); val slate = Color(0xFF8FA0B8)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(onClick = { t.updateMetOn(!t.metOn) }) { Canvas(Modifier.size(28.dp)) { drawPlayStop(t.metOn, if (t.metOn) C.Green else white) } }
            KeyButton(onClick = { t.tapMetro() }) { Text("Tap", color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        RotaryKnob(t.metBpm.toFloat(), 30f, 300f, 900f, { t.updateMetBpm(it.roundToInt()) }, Modifier.weight(1f).aspectRatio(1f), glow = flash.value) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${t.metBpm}", color = white, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                Text("BPM", color = slate, fontSize = 12.sp)
            }
        }
        RotaryKnob(t.metDb, -30f, 6f, 400f, { t.updateMetDb(it) }, Modifier.weight(1f).aspectRatio(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val db = t.metDb.roundToInt()
                Text(if (db == 0) "0" else "%+d".format(db), color = white, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                Text("dB", color = slate, fontSize = 12.sp)
            }
        }
        Column(Modifier.width(56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(onClick = { subDlg = true }) { SubdivIcon(t.metSub, white, Modifier.size(width = 44.dp, height = 38.dp)) }
            KeyButton(onClick = { sigDlg = true }) { Text("${t.metBeats}/${t.metDen}", color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
    if (subDlg) SubdivisionDialog(t.metSub, { t.updateMetSub(it) }) { subDlg = false }
    if (sigDlg) TimeSignatureDialog(t.metBeats, t.metDen, { t.updateMetBeats(it) }, { t.updateMetDen(it) }) { sigDlg = false }
}

/** One dot per beat of the bar; the current beat lights up while the metronome runs. */
@Composable
fun BeatDots(t: Tools) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until t.metBeats.coerceIn(1, 12)) {
            val on = t.metOn && t.beatIdx == i
            Box(Modifier.size(if (i == 0) 9.dp else 7.dp).clip(CircleShape).background(if (on) C.Green else Color(0xFF263040)))
        }
    }
}

// ------------------------------------------------------------------------------------------------ controls
@Composable
private fun KeyButton(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val view = LocalView.current
    var down by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (down) 0.94f else 1f, spring(stiffness = 600f), label = "key")
    Box(
        Modifier.size(56.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF151D28), Color(0xFF0E141C)))).border(1.5.dp, Color(0xFF2B3647), shape)
            .pointerInput(Unit) { detectTapGestures(onPress = { down = true; Haptics.tick(view); tryAwaitRelease(); down = false }, onTap = { onClick() }) },
        contentAlignment = Alignment.Center, content = content
    )
}

/** Rotary knob: drag up / right = increase, down / left = decrease. [pxFull] = drag distance for the whole range. */
@Composable
private fun RotaryKnob(value: Float, min: Float, max: Float, pxFull: Float, onChange: (Float) -> Unit, modifier: Modifier, glow: Float = 0f, content: @Composable BoxScope.() -> Unit) {
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    var acc by remember { mutableStateOf(0f) }
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures(onDragStart = { acc = cur }) { change, drag ->
                change.consume()
                acc = (acc + (drag.x - drag.y) * (max - min) / pxFull).coerceIn(min, max)
                cb(acc)
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f; val c = center
            val sw = r * 0.14f; val ar = r - sw / 2f - 1.dp.toPx()                               // thick arc: dark track + green value
            val topLeft = Offset(c.x - ar, c.y - ar); val arcSize = Size(ar * 2f, ar * 2f)
            drawArc(Color(0xFF1E2733), 135f, 270f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (frac > 0.002f) drawArc(C.Green, 135f, 270f * frac, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            val dr = r * 0.72f                                                                    // dark disc with a thin slate rim (turns green on a beat)
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF1B2330), Color(0xFF111821)), startY = c.y - dr, endY = c.y + dr), dr, c)
            if (glow > 0.01f) drawCircle(C.Green.copy(alpha = 0.20f * glow), dr, c)
            drawCircle(lerp(Color(0xFF3C4759), C.Green, glow), dr, c, style = Stroke(2.dp.toPx()))
            val a = Math.toRadians((135f + 270f * frac).toDouble())                               // short thick indicator near the rim of the disc
            val p0 = Offset(c.x + cos(a).toFloat() * dr * 0.72f, c.y + sin(a).toFloat() * dr * 0.72f)
            val p1 = Offset(c.x + cos(a).toFloat() * dr * 0.95f, c.y + sin(a).toFloat() * dr * 0.95f)
            drawLine(C.Green, p0, p1, 3.6.dp.toPx(), StrokeCap.Round)
        }
        content()
    }
}

private fun DrawScope.drawPlayStop(running: Boolean, color: Color) {
    val w = size.width
    if (running) {
        drawRoundRect(color, Offset(.18f * w, .14f * w), Size(.2f * w, .72f * w), androidx.compose.ui.geometry.CornerRadius(2f, 2f))
        drawRoundRect(color, Offset(.62f * w, .14f * w), Size(.2f * w, .72f * w), androidx.compose.ui.geometry.CornerRadius(2f, 2f))
    } else {
        val p = Path().apply { moveTo(.22f * w, .10f * w); lineTo(.88f * w, .50f * w); lineTo(.22f * w, .90f * w); close() }
        drawPath(p, color)
    }
}

private fun DrawScope.drawSpeaker(color: Color) {
    val w = size.width; val sw = w * 0.08f
    val p = Path().apply { moveTo(.06f * w, .38f * w); lineTo(.26f * w, .38f * w); lineTo(.48f * w, .16f * w); lineTo(.48f * w, .84f * w); lineTo(.26f * w, .62f * w); lineTo(.06f * w, .62f * w); close() }
    drawPath(p, color)
    for (rad in listOf(.20f, .36f)) {
        val rr = rad * w
        drawArc(color, -42f, 84f, false, Offset(.48f * w - rr * 0.25f, .5f * w - rr), Size(rr * 2f, rr * 2f), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

// ------------------------------------------------------------------------------------------------ subdivision icons
/** Simplified music notation of a subdivision pattern: note heads, stems, beams / flags, rests, dots and the triplet "3". */
@Composable
fun SubdivIcon(idx: Int, color: Color, modifier: Modifier = Modifier.size(width = 52.dp, height = 40.dp)) {
    val trip = idx in 2..7
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawSubdiv(idx, color) }
        if (trip) Text("3", color = color, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopCenter))
    }
}

private fun DrawScope.drawSubdiv(idx: Int, color: Color) {
    val pos = SUB_POS[idx.coerceIn(0, 13)]
    val grid = when { idx == 0 -> 1; idx == 1 -> 2; idx in 2..7 -> 3; else -> 4 }
    val u = size.height / 40f; val pitch = 13f * u
    val x0 = (size.width - pitch * grid) / 2f + pitch / 2f
    val yHead = 32f * u; val stemTop = 12f * u; val sw = 1.5f * u
    val slots = pos.map { it / (12 / grid) }                                       // slot index of every click
    val durs = pos.mapIndexed { i, p -> (if (i + 1 < pos.size) pos[i + 1] else 12) - p }
    fun beams(i: Int) = when (durs[i]) { in 1..3 -> 2; in 4..9 -> 1; else -> 0 }

    for (g in 0 until grid) {                                                      // heads, stems, rests, dots
        val cx = x0 + g * pitch; val k = slots.indexOf(g)
        if (k >= 0) {
            drawOval(color, Offset(cx - 3.8f * u, yHead - 2.7f * u), Size(7.6f * u, 5.4f * u))
            drawLine(color, Offset(cx + 3.4f * u, yHead), Offset(cx + 3.4f * u, stemTop), sw)
            if (durs[k] == 9) drawCircle(color, 1.1f * u, Offset(cx + 8f * u, yHead - 1.5f * u))
        } else {                                                                   // eighth rest
            drawCircle(color, 1.6f * u, Offset(cx - 1.4f * u, yHead - 10f * u))
            drawLine(color, Offset(cx + 1.8f * u, yHead - 11.5f * u), Offset(cx - 1.4f * u, yHead + 1f * u), sw)
        }
    }
    for (b in 0..1) for (k in pos.indices) {                                       // beams and flags
        if (beams(k) <= b) continue
        val sx = x0 + slots[k] * pitch + 3.4f * u; val by = stemTop + b * 4.6f * u
        val next = k + 1 < pos.size && slots[k + 1] == slots[k] + 1 && beams(k + 1) > b
        val prev = k > 0 && slots[k - 1] == slots[k] - 1 && beams(k - 1) > b
        if (next) drawLine(color, Offset(sx, by), Offset(x0 + slots[k + 1] * pitch + 3.4f * u, by), 2.8f * u)
        else if (!prev) drawLine(color, Offset(sx, by), Offset(sx + 5.5f * u, by + 7f * u), sw)
    }
    if (grid == 3) {                                                               // triplet bracket (gap in the middle for the "3")
        val xa = x0 - 5f * u; val xb = x0 + 2 * pitch + 8f * u; val y = 4.5f * u; val mid = (xa + xb) / 2f
        drawLine(color, Offset(xa, y), Offset(mid - 6f * u, y), sw); drawLine(color, Offset(mid + 6f * u, y), Offset(xb, y), sw)
        drawLine(color, Offset(xa, y), Offset(xa, y + 3.5f * u), sw); drawLine(color, Offset(xb, y), Offset(xb, y + 3.5f * u), sw)
    }
}

// ------------------------------------------------------------------------------------------------ popups
@Composable
private fun PopupFrame(title: String, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(0.92f).clip(shape).background(C.Panel).border(1.dp, C.Line, shape)) {
            Box(Modifier.fillMaxWidth().background(C.Bg).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            content()
            Box(
                Modifier.padding(14.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(C.Bg).border(1.dp, C.Accent.copy(alpha = 0.6f), RoundedCornerShape(26.dp)).clickable { onDone() },
                contentAlignment = Alignment.Center
            ) { Text("Done", color = C.Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SubdivisionDialog(selected: Int, onSelect: (Int) -> Unit, onDone: () -> Unit) {
    PopupFrame("Subdivision", onDone) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (col in 0..1) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0..6) {
                        val i = col * 7 + row; val sel = i == selected
                        val shape = RoundedCornerShape(10.dp)
                        Box(
                            Modifier.fillMaxWidth().height(50.dp).clip(shape).background(if (sel) C.Bg else Color.Transparent).border(1.dp, if (sel) C.Accent else Color.Transparent, shape).clickable { onSelect(i) },
                            contentAlignment = Alignment.Center
                        ) { SubdivIcon(i, if (sel) C.Accent else C.Dim, Modifier.size(width = 52.dp, height = 40.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeSignatureDialog(num: Int, den: Int, onNum: (Int) -> Unit, onDen: (Int) -> Unit, onDone: () -> Unit) {
    val nums = (1..12).map { it.toString() }
    val dens = DENOMS.map { it.toString() }
    PopupFrame("Time signature", onDone) {
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(C.Bg))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WheelPicker(nums, (num - 1).coerceIn(0, 11), { onNum(it + 1) }, Modifier.weight(1f))
                Text("/", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                WheelPicker(dens, DENOMS.indexOf(den).coerceAtLeast(0), { onDen(DENOMS[it]) }, Modifier.weight(1f))
            }
        }
    }
}

/** Vertical wheel: drag to scroll, it snaps to the nearest value; tap a value to pick it. 7 rows are visible. */
@Composable
private fun WheelPicker(items: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val rowH = 44.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    val anim = remember { Animatable(selected.toFloat()) }
    val scope = rememberCoroutineScope()
    val cb by rememberUpdatedState(onSelected)
    fun settle() { scope.launch { val t = anim.value.roundToInt().coerceIn(0, items.lastIndex); anim.animateTo(t.toFloat(), tween(140)); cb(t) } }
    Box(
        modifier.height(rowH * 7).clipToBounds().pointerInput(items.size) {
            detectVerticalDragGestures(
                onDragStart = { scope.launch { anim.stop() } },
                onDragEnd = { settle() }, onDragCancel = { settle() },
                onVerticalDrag = { change, dy -> change.consume(); scope.launch { anim.snapTo((anim.value - dy / rowPx).coerceIn(0f, items.lastIndex.toFloat())) } }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        val p = anim.value
        for (i in items.indices) {
            val d = i - p
            if (abs(d) > 3.5f) continue
            Text(
                items[i], color = C.Text.copy(alpha = (1f - abs(d) * 0.3f).coerceIn(0.15f, 1f)),
                fontSize = if (abs(d) < 0.5f) 22.sp else 16.sp, fontWeight = if (abs(d) < 0.5f) FontWeight.Bold else FontWeight.Normal, fontFamily = Mono,
                modifier = Modifier.offset(y = rowH * d).clickable { scope.launch { anim.animateTo(i.toFloat(), tween(140)); cb(i) } }.padding(horizontal = 28.dp, vertical = 8.dp)
            )
        }
    }
}
