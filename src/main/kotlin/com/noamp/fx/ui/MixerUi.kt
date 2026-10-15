package com.noamp.fx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.Haptics
import com.noamp.fx.MicP
import com.noamp.fx.P
import com.noamp.fx.Rig
import kotlin.math.roundToInt

private val PANEL = Color(0xFF0A0D10)
private val PANEL_LINE = Color(0xFF1B222A)
private val READ_A = Color(0xFF0F1318)
private val READ_B = Color(0xFF1D2229)
private val RED_TOP = Color(0xFFFF7A6B); private val RED_BOT = Color(0xFFC72F25)
private val WHITE_TOP = Color(0xFFF4F5F7); private val WHITE_BOT = Color(0xFFB4B8BE)

/** dB below full scale -> vertical position (0 = top) of the meter scale 0 / 12 / 24 / 40 like the reference design. */
private val SCALE = listOf(0f to 0.14f, 12f to 0.34f, 24f to 0.56f, 40f to 0.78f, 80f to 0.96f)
private fun meterPos(dbfs: Float): Float {
    val d = (-dbfs).coerceIn(0f, 80f)
    for (i in 1 until SCALE.size) if (d <= SCALE[i].first) { val a = SCALE[i - 1]; val b = SCALE[i]; return a.second + (b.second - a.second) * (d - a.first) / (b.first - a.first) }
    return SCALE.last().second
}

/**
 * Mixer: IN channel 1 (guitar input gain) · IN channel 2 (microphone preamp) · Out channel 1 (guitar chain output) ·
 * Out channel 2 (microphone output) · Stereo Out (master bus). Drag a fader, double-tap it to return to 0 dB.
 */
@Composable
fun MixerScreen(rig: Rig) {
    val m = rig.meters
    val micOff = !rig.micOn
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth().clip(shape).background(Color(0xFF0E1216)).border(1.dp, C.Line, shape)) {
            // every fader: -50 .. +3 dB (bottom = silence). Out 1 / Out 2 / Stereo Out also have a balance control (C = centre, drag to L or R).
            Strip("IN channel 1", red = true, pan = null, db = rig.get(P.MASTER_IN), levels = listOf(m[0]), dim = false, onPan = {}) { rig.set(P.MASTER_IN, it) }
            Divider()
            Strip("IN channel 2", red = true, pan = null, db = rig.micFx[MicP.PRE], levels = listOf(m[16]), dim = micOff, onPan = {}) { rig.setMicFx(MicP.PRE, it) }
            Divider()
            Strip("Out channel 1", red = false, pan = rig.panOut1, db = rig.get(P.MASTER_OUT), levels = listOf(m[17]), dim = false, onPan = { rig.setPan(0, it) }) { rig.set(P.MASTER_OUT, it) }
            Divider()
            Strip("Out channel 2", red = false, pan = rig.panOut2, db = rig.micDb, levels = listOf(m[18]), dim = micOff, onPan = { rig.setPan(1, it) }) { rig.setMicLevel(it) }
            Divider()
            Strip("Stereo Out", red = true, pan = rig.panBus, db = rig.busDb, levels = listOf(m[1], m[2]), dim = false, onPan = { rig.setPan(2, it) }) { rig.chooseBus(it) }
        }
        Text(
            if (micOff) "Channel 2 is off: switch the microphone on in the IN 2 menu (signal chain)." else "IN 1 = guitar input gain · IN 2 = mic input level · Out 1 = guitar chain output (Vol) · Out 2 = mic to output · Stereo Out = master. Range -50 to +3 dB. Drag C left/right for balance (L / R). Double-tap a fader for 0 dB, double-tap the balance for centre.",
            color = if (micOff) C.Amber else C.Dim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Divider() { Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFF07090B))) }

@Composable
private fun RowScope.Strip(name: String, red: Boolean, pan: Float?, db: Float, levels: List<Float>, dim: Boolean, onPan: (Float) -> Unit, onDb: (Float) -> Unit) {
    val alpha = if (dim) 0.5f else 1f
    val min = FADER_MIN; val max = FADER_MAX
    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // balance: C = centre, drag left / right for L / R (null = this strip has no balance)
        PanBox(pan, onPan)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f).fillMaxWidth().graphicsAlpha(alpha), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Fader(Modifier.weight(0.9f).fillMaxHeight(), red, db, min, max, onDb)
            MeterPane(Modifier.weight(1f).fillMaxHeight(), levels)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, PANEL_LINE, RoundedCornerShape(8.dp))) {
            Box(Modifier.weight(1f).fillMaxHeight().background(READ_A), contentAlignment = Alignment.Center) { Text(if (db <= -49.9f) "-∞" else "%.1f".format(db), color = Color(0xFFF1F3F6), fontSize = 12.sp, maxLines = 1, softWrap = false) }
            val pk = levels.maxOrNull() ?: -120f
            Box(Modifier.weight(1f).fillMaxHeight().background(READ_B), contentAlignment = Alignment.Center) { Text(if (pk < -80f) "-∞" else "${pk.roundToInt()}", color = Color(0xFFB5BDC7), fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp)).background(PANEL).border(1.dp, PANEL_LINE, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text(name, color = Color(0xFFF1F3F6), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 11.sp, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/**
 * Balance control above a fader: shows C (centre), or L / R with a percentage. Drag sideways to move it, double-tap = centre.
 * A thin marker inside the box shows the position. [pan] = -1 (left) .. +1 (right); null = no balance on this strip.
 */
@Composable
private fun PanBox(pan: Float?, onPan: (Float) -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    val cur by rememberUpdatedState(pan ?: 0f); val cb by rememberUpdatedState(onPan)
    val view = LocalView.current
    var wasCentre by remember { mutableStateOf(true) }
    var m = Modifier.fillMaxWidth().height(30.dp).clip(shape).background(PANEL).border(1.dp, PANEL_LINE, shape)
    if (pan != null) m = m
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { Haptics.heavy(view); cb(0f) }) }
        .pointerInput(Unit) {
            detectDragGestures { change, drag ->
                change.consume()
                val v = (cur + drag.x / (size.width * 0.5f)).coerceIn(-1f, 1f)
                val centred = kotlin.math.abs(v) < 0.03f
                if (centred && !wasCentre) Haptics.heavy(view)
                wasCentre = centred
                cb(if (centred) 0f else v)
            }
        }
    Box(m, contentAlignment = Alignment.Center) {
        if (pan != null) {
            Canvas(Modifier.fillMaxSize()) {                                           // track + position marker
                val x = size.width * (0.5f + 0.5f * pan.coerceIn(-1f, 1f) * 0.86f)
                drawLine(Color(0xFF2A323B), Offset(size.width * 0.07f, size.height - 4.dp.toPx()), Offset(size.width * 0.93f, size.height - 4.dp.toPx()), 1.dp.toPx())
                drawLine(Color(0xFF6C7580), Offset(size.width * 0.5f, size.height - 7.dp.toPx()), Offset(size.width * 0.5f, size.height - 1.dp.toPx()), 1.dp.toPx())
                if (pan != 0f) drawLine(Color(0xFF29E0F0), Offset(x, size.height - 8.dp.toPx()), Offset(x, size.height - 1.dp.toPx()), 2.dp.toPx())
            }
            if (pan == 0f) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Box(Modifier.width(9.dp).height(2.dp).background(Color(0xFFE9EBEE)))
                Text("C", color = Color(0xFFE9EBEE), fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
            } else Text((if (pan < 0f) "L " else "R ") + "${(kotlin.math.abs(pan) * 100f).roundToInt()}", color = Color(0xFF29E0F0), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.offset(y = (-2).dp))
        }
    }
}

private const val FADER_MIN = -50f
private const val FADER_MAX = 3f

private fun Modifier.graphicsAlpha(a: Float): Modifier = if (a >= 0.99f) this else this.alpha(a)

/** Vertical fader: dark slot, tick marks every 6 dB (0 dB longer), and the fader cap. Drag = change, double tap = 0 dB. */
@Composable
private fun Fader(modifier: Modifier, red: Boolean, db: Float, min: Float, max: Float, onDb: (Float) -> Unit) {
    val cur by rememberUpdatedState(db); val cb by rememberUpdatedState(onDb)
    val view = LocalView.current
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(PANEL).border(1.dp, PANEL_LINE, RoundedCornerShape(10.dp))
            .pointerInput(min, max) { detectTapGestures(onDoubleTap = { Haptics.heavy(view); cb(0f.coerceIn(min, max)) }) }
            .pointerInput(min, max) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val travel = (size.height - 2 * 20.dp.toPx()).coerceAtLeast(1f)
                    cb((cur - drag.y / travel * (max - min)).coerceIn(min, max))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 20.dp.toPx(); val travel = size.height - 2 * pad
            fun yOf(v: Float) = pad + (max - v) / (max - min) * travel
            val slotX = size.width * 0.30f
            drawRoundRect(Color.Black, Offset(slotX - 3.dp.toPx(), pad - 8.dp.toPx()), Size(6.dp.toPx(), travel + 16.dp.toPx()), CornerRadius(3.dp.toPx()))
            var v = Math.floor((max / 6f).toDouble()).toFloat() * 6f
            while (v >= min) {                                                         // ticks every 6 dB, longer at 0 dB and at the top
                val y = yOf(v); val len = if (v == 0f) size.width * 0.34f else size.width * 0.22f
                drawLine(Color(0xFF6C7580), Offset(size.width * 0.58f, y), Offset(size.width * 0.58f + len, y), 1.5.dp.toPx())
                v -= 6f
            }
            val cy = yOf(db.coerceIn(min, max)); val cw = size.width * 0.58f; val chh = 38.dp.toPx(); val cx = slotX - cw * 0.45f
            val top = if (red) RED_TOP else WHITE_TOP; val bot = if (red) RED_BOT else WHITE_BOT
            drawRoundRect(Brush.verticalGradient(listOf(top, bot), startY = cy - chh / 2, endY = cy + chh / 2), Offset(cx, cy - chh / 2), Size(cw, chh), CornerRadius(4.dp.toPx()))
            for (g in -1..1) drawLine(Color(0x55000000), Offset(cx + 3.dp.toPx(), cy + g * 7.dp.toPx()), Offset(cx + cw - 3.dp.toPx(), cy + g * 7.dp.toPx()), 1.2.dp.toPx())
            drawLine(Color(0xAAFFFFFF), Offset(size.width * 0.58f - 6.dp.toPx(), cy), Offset(size.width * 0.58f + size.width * 0.34f, cy), 1.5.dp.toPx())   // line through the cap = current value
        }
    }
}

/** Level meter with the 0 / 12 / 24 / 40 dB scale; the level is shown as a thin cyan line (one per channel). */
@Composable
private fun MeterPane(modifier: Modifier, levels: List<Float>) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp)).background(PANEL).border(1.dp, PANEL_LINE, RoundedCornerShape(10.dp))) {
        val h = maxHeight
        Canvas(Modifier.fillMaxSize()) {
            val n = levels.size; val gap = 3.dp.toPx(); val x0 = size.width * 0.08f; val usable = size.width * 0.84f; val w = (usable - gap * (n - 1)) / n
            levels.forEachIndexed { i, lv ->
                val y = size.height * meterPos(lv); val x = x0 + i * (w + gap)
                if (lv > -79f) drawRect(Color(0x2229E0F0), Offset(x, y), Size(w, size.height * 0.97f - y))
                drawLine(Color(0xFF29E0F0), Offset(x, y), Offset(x + w, y), 2.dp.toPx())
            }
            for ((_, pos) in SCALE.dropLast(1)) drawCircle(Color(0xFF8C95A0), 1.5.dp.toPx(), Offset(size.width * 0.08f, size.height * pos))
        }
        for ((d, pos) in SCALE.dropLast(1)) Text("${d.toInt()}", color = Color(0xFFA7B0BB), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.TopCenter).offset(y = h * pos - 9.dp))
    }
}
