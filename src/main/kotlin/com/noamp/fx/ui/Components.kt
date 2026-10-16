package com.noamp.fx.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.noamp.fx.PDef
import kotlin.math.cos
import kotlin.math.sin

val Mono = FontFamily.Monospace

@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Panel)
            .border(1.dp, C.Line, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        if (title != null) { Text(title.uppercase(), color = C.Dim, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(10.dp)) }
        content()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Knob(d: PDef, value: Float, color: Color, onChange: (Float) -> Unit, size: Dp = 70.dp, label: String = d.name, onLongPress: (() -> Unit)? = null, bound: Boolean = false) {
    val v by rememberUpdatedState(value); val cb by rememberUpdatedState(onChange); val lp by rememberUpdatedState(onLongPress)
    Column(Modifier.width(size + 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier.size(size).pointerHoverIcon(PointerIcon.Hand)
                // desktop: mouse wheel turns the knob (hold Shift for fine steps)
                .onPointerEvent(PointerEventType.Scroll) { ev ->
                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f) {
                        val step = if (ev.keyboardModifiers.isShiftPressed) 0.01f else 0.04f
                        cb(d.fromNorm((d.toNorm(v) - dy * step).coerceIn(0f, 1f)))
                        ev.changes.forEach { it.consume() }
                    }
                }
                .pointerInput(d) { detectVerticalDragGestures { ch, dy -> ch.consume(); cb(d.fromNorm(d.toNorm(v) - dy / (size.toPx() * 3.2f))) } }
                .pointerInput(d) { detectTapGestures(onDoubleTap = { cb(d.def) }, onLongPress = { lp?.invoke() }) }
        ) {
            val sw = 6.dp.toPx(); val r = this.size.minDimension / 2 - sw; val c = center
            val tl = Offset(c.x - r, c.y - r); val sz = Size(r * 2, r * 2)
            drawArc(C.Line, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            val n = d.toNorm(v)
            drawArc(color.copy(alpha = 0.25f), 135f, 270f * n, false, tl - Offset(2f, 2f), Size(sz.width + 4f, sz.height + 4f), style = Stroke(sw + 4f, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * n, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            val br = r - sw * 1.1f
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF2B333D), Color(0xFF151A20)), c.y - br, c.y + br), br, c)
            drawCircle(Color(0xFF3A444F), br, c, style = Stroke(1.dp.toPx()))
            if (bound) drawCircle(C.Pink, 4.dp.toPx(), Offset(this.size.width - 5.dp.toPx(), 5.dp.toPx()))   // MIDI-assigned
            val a = Math.toRadians((135 + 270 * n).toDouble())
            drawLine(color, Offset(c.x + cos(a).toFloat() * br * 0.35f, c.y + sin(a).toFloat() * br * 0.35f),
                Offset(c.x + cos(a).toFloat() * br * 0.92f, c.y + sin(a).toFloat() * br * 0.92f), 3.dp.toPx(), StrokeCap.Round)
        }
        Spacer(Modifier.height(2.dp))
        Text(d.fmt(value), color = C.Text, fontSize = 12.sp, fontFamily = Mono, maxLines = 1)
    }
}

@Composable
fun Led(on: Boolean, color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(if (on) color else C.Off)
        .then(if (on) Modifier.border(2.dp, color.copy(alpha = 0.3f), CircleShape) else Modifier))
}

@Composable
fun PowerToggle(on: Boolean, color: Color, label: String = if (on) "ON" else "BYPASS", onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) color.copy(alpha = 0.14f) else C.PanelHi)
            .border(1.dp, if (on) color else C.Line, RoundedCornerShape(20.dp)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { Led(on, color); Text(label, color = if (on) color else C.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

@Composable
fun Btn(text: String, color: Color = C.Accent, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = if (enabled) color else C.Off
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(C.PanelHi).border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default).clickable(enabled = enabled) { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text.uppercase(), color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

@Composable
fun Segmented(options: List<String>, selected: Int, color: Color, fontSize: TextUnit = 11.sp, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(C.PanelHi).border(1.dp, C.Line, RoundedCornerShape(10.dp))) {
        options.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).background(if (i == selected) color.copy(alpha = 0.2f) else Color.Transparent).pointerHoverIcon(PointerIcon.Hand).clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(o.uppercase(), color = if (i == selected) color else C.Dim, fontSize = fontSize, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, maxLines = 1)
            }
        }
    }
}

/** Meter LED horizontal; db -60..0. [src] dibaca di fase draw/komposisi lokal agar hanya meter yang di-recompose. */
@Composable
fun MeterBar(src: () -> Float, label: String, modifier: Modifier = Modifier, gr: Boolean = false, grMax: Float = 24f, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Dim, fontSize = if (compact) 9.sp else 10.sp, fontFamily = Mono, modifier = Modifier.width(if (compact) 30.dp else 34.dp))
        Canvas(Modifier.weight(1f).height(if (compact) 7.dp else 12.dp)) {
            val db = src()
            val segs = if (compact) 20 else 40; val gap = (if (compact) 1.dp else 2.dp).toPx(); val w = (size.width - gap * (segs - 1)) / segs
            val frac = if (gr) (db / grMax).coerceIn(0f, 1f) else ((db + 60f) / 60f).coerceIn(0f, 1f)
            for (i in 0 until segs) {
                val p = i / segs.toFloat(); val lit = p < frac
                val col = if (gr) C.Amber else when { p < 0.70f -> C.Green; p < 0.92f -> C.Amber; else -> C.Red }
                drawRoundRect(if (lit) col else C.Line, Offset(i * (w + gap), 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(2f, 2f))
            }
        }
        val db = src()
        val txt = if (gr) "-%.1f".format(db.coerceAtLeast(0f)) else if (db <= -99f) "-inf" else "%.1f".format(db)
        Text(txt, color = if (!gr && db > -0.5f) C.Red else C.Text, fontSize = if (compact) 9.sp else 10.sp, fontFamily = Mono, modifier = Modifier.width(if (compact) 34.dp else 44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
fun KnobRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly, content = content)
}

@Composable
fun Chip(text: String, sel: Boolean, color: Color = C.Accent, onClick: () -> Unit) {
    Text(text, color = if (sel) color else C.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) color.copy(alpha = 0.12f) else C.PanelHi)
            .border(1.dp, if (sel) color else C.Line, RoundedCornerShape(16.dp)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp))
}

/** Gear icon drawn with a Canvas (no icon library needed). */
@Composable
fun GearIcon(color: Color, size: Dp = 22.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val c = center; val r = this.size.minDimension / 2f
        for (i in 0 until 8) rotate(i * 45f, c) {
            drawRoundRect(color, Offset(c.x - r * 0.14f, c.y - r), Size(r * 0.28f, r * 0.36f), androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
        }
        drawCircle(color, r * 0.60f, c, style = Stroke(r * 0.30f))
    }
}

/** Tuner icon (tuning fork) drawn with a Canvas; same footprint as [GearIcon]. */
@Composable
fun TunerIcon(color: Color, size: Dp = 22.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.minDimension; val sw = w * 0.13f
        val cx = w / 2f; val l = cx - w * 0.2f; val r = cx + w * 0.2f; val y0 = w * 0.40f
        drawLine(color, Offset(l, w * 0.04f), Offset(l, y0), sw, StrokeCap.Round)
        drawLine(color, Offset(r, w * 0.04f), Offset(r, y0), sw, StrokeCap.Round)
        drawArc(color, 0f, 180f, false, Offset(l, y0 - w * 0.2f), Size(w * 0.4f, w * 0.4f), style = Stroke(sw))
        drawLine(color, Offset(cx, y0 + w * 0.2f), Offset(cx, w * 0.96f), sw, StrokeCap.Round)
    }
}

/** Compact info bar (CPU / sample rate / buffer) styled like [MeterBar] compact. [frac] 0..1; [traffic] colours green→amber→red. */
@Composable
fun InfoBar(label: String, frac: Float, value: String, modifier: Modifier = Modifier, traffic: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Dim, fontSize = 9.sp, fontFamily = Mono, modifier = Modifier.width(30.dp))
        Canvas(Modifier.weight(1f).height(7.dp)) {
            val segs = 20; val gap = 1.dp.toPx(); val w = (size.width - gap * (segs - 1)) / segs
            val f = frac.coerceIn(0f, 1f)
            for (i in 0 until segs) {
                val p = i / segs.toFloat(); val lit = p < f
                val col = if (!traffic) C.Accent else when { p < 0.70f -> C.Green; p < 0.92f -> C.Amber; else -> C.Red }
                drawRoundRect(if (lit) col else C.Line, Offset(i * (w + gap), 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(2f, 2f))
            }
        }
        Text(value, color = if (traffic && frac > 0.9f) C.Red else C.Text, fontSize = 9.sp, fontFamily = Mono, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
    }
}

/** Power symbol (open ring with a bar), drawn so it never depends on a font glyph. */
@Composable
fun PowerIcon(color: Color, size: Dp = 22.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.minDimension; val sw = w * 0.11f; val c = Offset(w / 2f, w * 0.54f); val r = w * 0.34f
        drawArc(color, -60f, 300f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2f, w * 0.08f), Offset(w / 2f, w * 0.50f), sw, StrokeCap.Round)
    }
}

/** Home icon (house outline). */
@Composable
fun HomeIcon(color: Color, size: Dp = 22.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.minDimension; val st = Stroke(w * 0.09f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val p = androidx.compose.ui.graphics.Path()
        p.moveTo(w * 0.10f, w * 0.50f); p.lineTo(w * 0.50f, w * 0.14f); p.lineTo(w * 0.90f, w * 0.50f)
        drawPath(p, color, style = st)
        val q = androidx.compose.ui.graphics.Path()
        q.moveTo(w * 0.20f, w * 0.44f); q.lineTo(w * 0.20f, w * 0.86f); q.lineTo(w * 0.42f, w * 0.86f); q.lineTo(w * 0.42f, w * 0.60f)
        q.lineTo(w * 0.58f, w * 0.60f); q.lineTo(w * 0.58f, w * 0.86f); q.lineTo(w * 0.80f, w * 0.86f); q.lineTo(w * 0.80f, w * 0.44f)
        drawPath(q, color, style = st)
    }
}
