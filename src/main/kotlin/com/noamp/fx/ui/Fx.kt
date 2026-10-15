package com.noamp.fx.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun FxPage(rig: Rig, tab: Tab, title: String, sub: String = "", top: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((title + if (rig.uiBank > 0) " ${rig.uiBank + 1}" else "").uppercase(), color = tab.color, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, letterSpacing = 1.sp)
                if (sub.isNotEmpty()) Text(sub, color = C.Dim, fontSize = 11.sp)
            }
            tab.onId?.let { id -> PowerToggle(rig.on(id), tab.color) { rig.toggle(id) } }
        }
        top()
        content()
    }
}

/** Knob bound to a parameter. Long-press starts MIDI learn (move an expression pedal / knob); a dot marks assigned knobs. */
@Composable private fun K(rig: Rig, id: Int, color: Color, size: androidx.compose.ui.unit.Dp = 70.dp, label: String? = null) =
    Knob(P.def(id), rig.get(id), color, { rig.set(id, it) }, size, label ?: P.def(id).name,
        onLongPress = { rig.startLearn(T.par(id + rig.uiBank * P.COUNT_BASE)) }, bound = rig.bindingFor(T.par(id + rig.uiBank * P.COUNT_BASE)) != null)

private fun DrawScope.grid(steps: Int) {
    for (i in 0..steps) {
        val x = size.width * i / steps; val y = size.height * i / steps
        drawLine(C.Line, Offset(x, 0f), Offset(x, size.height), 1f); drawLine(C.Line, Offset(0f, y), Offset(size.width, y), 1f)
    }
}
private fun Modifier.display(h: androidx.compose.ui.unit.Dp) = this.fillMaxWidth().height(h).clip(RoundedCornerShape(10.dp)).background(Color(0xFF05070A)).border(1.dp, C.Line, RoundedCornerShape(10.dp))

// ---------------------------------------------------------------- GATE
@Composable
fun GateScreen(rig: Rig) {
    val thr = rig.get(P.GATE_THR); val rg = rig.get(P.GATE_RANGE)
    FxPage(rig, Tab.GATE, "Noise Gate", "Reduces noise when you are not playing") {
        Panel {
            Canvas(Modifier.display(170.dp)) {
                grid(5)
                fun f(x: Float) = if (x < thr) (x + rg).coerceAtLeast(-90f) else x
                fun X(d: Float) = (d + 90f) / 90f * size.width
                fun Y(d: Float) = size.height - (d + 90f) / 90f * size.height
                val p = Path(); for (i in 0..90) { val x = -90f + i; val o = Offset(X(x), Y(f(x))); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                drawPath(p, Tab.GATE.color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawLine(C.Amber.copy(alpha = 0.6f), Offset(X(thr), 0f), Offset(X(thr), size.height), 1.5f)
                val x = rig.meters[0].coerceIn(-90f, 0f); drawCircle(C.Text, 5.dp.toPx(), Offset(X(x), Y(f(x))))
            }
            Spacer(Modifier.height(10.dp))
            MeterBar({ rig.meters[0] }, "IN"); Spacer(Modifier.height(4.dp)); MeterBar({ rig.meters[3] }, "GR", gr = true, grMax = 40f)
        }
        Panel { KnobRow { K(rig, P.GATE_THR, Tab.GATE.color); K(rig, P.GATE_RANGE, Tab.GATE.color); K(rig, P.GATE_ATK, Tab.GATE.color); K(rig, P.GATE_REL, Tab.GATE.color) } }
        Panel(title = "Position") {
            Text("The gate sits at the very start of the chain, right after the Input Gain pot. Tap that pot to switch the gate on/off, long press it to open this page.", color = C.Dim, fontSize = 10.sp)
        }
    }
}

// ---------------------------------------------------------------- COMP
@Composable
fun CompScreen(rig: Rig) {
    val t = rig.get(P.COMP_THR); val ra = rig.get(P.COMP_RATIO); val kn = rig.get(P.COMP_KNEE); val mk = rig.get(P.COMP_MAKEUP)
    fun out(x: Float): Float {
        val over = x - t; val slope = 1f / ra - 1f
        val g = if (2 * over < -kn) 0f else if (2 * abs(over) <= kn) { val q = over + kn / 2; slope * q * q / (2 * maxOf(kn, 0.01f)) } else slope * over
        return x + g + mk
    }
    FxPage(rig, Tab.COMP, "Compressor", "Soft-knee feed-forward compressor") {
        Panel {
            Canvas(Modifier.display(190.dp)) {
                grid(6)
                fun X(d: Float) = (d + 60f) / 60f * size.width
                fun Y(d: Float) = size.height - (d + 60f) / 60f * size.height
                drawLine(C.Off, Offset(0f, size.height), Offset(size.width, 0f), 1.5f)
                val p = Path(); for (i in 0..60) { val x = -60f + i; val o = Offset(X(x), Y(out(x).coerceIn(-60f, 0f))); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                drawPath(p, Tab.COMP.color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val x = rig.meters[0].coerceIn(-60f, 0f); drawCircle(C.Text, 5.dp.toPx(), Offset(X(x), Y(out(x).coerceIn(-60f, 0f))))
            }
            Spacer(Modifier.height(10.dp)); MeterBar({ rig.meters[4] }, "GR", gr = true, grMax = 20f)
        }
        Panel { KnobRow { K(rig, P.COMP_THR, Tab.COMP.color); K(rig, P.COMP_RATIO, Tab.COMP.color); K(rig, P.COMP_ATK, Tab.COMP.color) }
            Spacer(Modifier.height(10.dp))
            KnobRow { K(rig, P.COMP_REL, Tab.COMP.color); K(rig, P.COMP_KNEE, Tab.COMP.color); K(rig, P.COMP_MAKEUP, Tab.COMP.color) } }
    }
}

// ---------------------------------------------------------------- NAM
@Composable
fun NamScreen(rig: Rig) {
    val pick = rememberOpenFile("Choose a NAM model", "nam") { rig.importNam(it) }
    FxPage(rig, Tab.NAM, "Amp Loader", "Neural Amp Modeler · .nam files") {
        Panel(title = "Model") {
            Text(rig.namName?.let { NamFile.label(it) } ?: "No model loaded", color = if (rig.namName != null) C.Text else C.Dim, fontSize = 16.sp, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(if (rig.namName != null) "● LOADED" else "○ EMPTY", color = if (rig.namName != null) C.Green else C.Dim, fontSize = 11.sp, fontFamily = Mono)
            if (rig.namSlim) {
                Spacer(Modifier.height(12.dp))
                Text("MODEL SIZE", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("Lite", "Full"), rig.namQuality, Tab.NAM.color) { rig.updateNamQuality(it) }
                Text("This model contains several sizes. Lite uses far less CPU; Full sounds closest to the original.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Load .nam file", Tab.NAM.color) { pick() }
                Btn("Clear", C.Dim, enabled = rig.namName != null) { rig.clearNam() }
            }
            if (rig.status.isNotEmpty()) Text(rig.status, color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Panel(title = "Model Library") {
            if (rig.models.isEmpty()) Text("No models yet. Load a .nam file and it is kept here.", color = C.Dim, fontSize = 11.sp)
            rig.models.forEachIndexed { i, n ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                val loaded = rig.namName == n; val built = n in rig.builtinModels
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(NamFile.label(n), color = if (loaded) Tab.NAM.color else C.Text, fontSize = 13.sp, maxLines = 2)
                        Text(listOfNotNull(if (built) "BUILT-IN" else "MY MODEL", if (loaded) "LOADED" else null).joinToString(" · "), color = if (loaded) C.Green else C.Dim, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Mono)
                    }
                    Btn(if (loaded) "Reload" else "Load", Tab.NAM.color) { rig.loadModel(n) }
                    if (!built) { Spacer(Modifier.width(6.dp)); Btn("✕", C.Red) { rig.deleteModel(n) } }
                }
            }
        }
        Panel(title = "Gain Staging") {
            KnobRow { K(rig, P.NAM_IN, Tab.NAM.color, 84.dp); K(rig, P.NAM_OUT, Tab.NAM.color, 84.dp) }
            Spacer(Modifier.height(8.dp)); MeterBar({ rig.meters[0] }, "IN")
        }
    }
}

// ---------------------------------------------------------------- CAB
@Composable
private fun IrSlot(rig: Rig, slot: Int) {
    val pick = rememberOpenFile("Choose an impulse response (WAV)", "wav") { rig.importIr(it, slot) }
    val name = if (slot == 0) rig.cabName else rig.cab2Name; val taps = if (slot == 0) rig.cabTaps else rig.cab2Taps
    val fs = rig.meters[13].let { if (it > 1000f) it else 48000f }
    Panel(title = if (slot == 0) "Impulse Response · Slot A" else "Impulse Response · Slot B") {
        Text(name ?: "No IR loaded", color = if (name != null) C.Text else C.Dim, fontSize = 16.sp, maxLines = 2)
        Text(if (name != null) "● LOADED · $taps taps (%.0f ms)".format(taps * 1000f / fs) else "○ EMPTY", color = if (name != null) C.Green else C.Dim, fontSize = 11.sp, fontFamily = Mono)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Load IR", Tab.CAB.color) { pick() }
            Btn("Clear", C.Dim, enabled = name != null) { rig.clearIr(slot) }
        }
    }
}

@Composable
fun CabScreen(rig: Rig) {
    FxPage(rig, Tab.CAB, "Cabinet Simulator", "Two impulse-response slots (.wav) with a blend control") {
        IrSlot(rig, 0)
        IrSlot(rig, 1)
        Panel(title = "Blend") {
            KnobRow { K(rig, P.CAB2_MIX, Tab.CAB.color, 84.dp); K(rig, P.CAB_LEVEL, Tab.CAB.color, 84.dp); K(rig, P.CAB_MIX, Tab.CAB.color, 84.dp) }
            Text("Blend: A only ← → B only (only when both slots are loaded). Mix is the wet/dry amount of the whole cabinet. IRs up to 8192 samples, zero-latency partitioned convolution.",
                color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- EQ
@Composable
fun EqScreen(rig: Rig) {
    DisposableEffect(Unit) { rig.wantSpectrum = true; onDispose { rig.wantSpectrum = false } }
    var sel by remember { mutableIntStateOf(1) }
    val tm = rememberTextMeasurer()
    fun fx(f: Float, w: Float) = (log10(f / 20f) / 3f) * w
    fun gy(g: Float, h: Float) = h / 2f - g / 18f * h / 2f
    FxPage(rig, Tab.EQ, "Parametric EQ", "5 bands · real-time RTA · drag the points to adjust") {
        Panel {
            Canvas(
                Modifier.display(270.dp).pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            var best = -1; var bd = Float.MAX_VALUE
                            for (b in 0 until 5) {
                                val d = hypot(fx(rig.get(P.EQ_B0 + b * 3), size.width.toFloat()) - o.x, gy(rig.get(P.EQ_B0 + b * 3 + 1), size.height.toFloat()) - o.y)
                                if (d < bd) { bd = d; best = b }
                            }
                            if (bd < 90.dp.toPx()) sel = best
                        },
                        onDrag = { ch, _ ->
                            ch.consume()
                            val w = size.width.toFloat(); val h = size.height.toFloat(); val p = ch.position
                            val f = 20f * 1000f.pow((p.x / w).coerceIn(0f, 1f)); val g = ((h / 2 - p.y) / (h / 2) * 18f).coerceIn(-18f, 18f)
                            rig.set(P.EQ_B0 + sel * 3, f.coerceIn(20f, 20000f)); rig.set(P.EQ_B0 + sel * 3 + 1, g)
                        })
                }
            ) {
                val spec = rig.spectrum; val curve = rig.eqCurve
                val w = size.width; val h = size.height
                listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f).forEach { f ->
                    val x = fx(f, w); drawLine(C.Line, Offset(x, 0f), Offset(x, h), 1f)
                    drawText(tm, if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", Offset(x + 3f, h - 14.dp.toPx()), TextStyle(color = C.Dim, fontSize = 9.sp))
                }
                listOf(-12f, -6f, 0f, 6f, 12f).forEach { g ->
                    val y = gy(g, h); drawLine(if (g == 0f) C.Off else C.Line, Offset(0f, y), Offset(w, y), 1f)
                    drawText(tm, "%+.0f".format(g), Offset(3f, y - 12.dp.toPx()), TextStyle(color = C.Dim, fontSize = 9.sp))
                }
                // RTA
                val bw = w / spec.size
                for (i in spec.indices) {
                    val fr = ((spec[i] + 96f) / 90f).coerceIn(0f, 1f)
                    drawRect(Brush.verticalGradient(listOf(C.Accent.copy(alpha = 0.55f), C.Accent.copy(alpha = 0.05f)), h - fr * h, h),
                        Offset(i * bw + 1f, h - fr * h), Size(bw - 2f, fr * h))
                }
                // curve
                val p = Path(); val fill = Path()
                for (i in curve.indices) { val x = i / (curve.size - 1f) * w; val y = gy(curve[i], h); if (i == 0) { p.moveTo(x, y); fill.moveTo(x, h / 2); fill.lineTo(x, y) } else { p.lineTo(x, y); fill.lineTo(x, y) } }
                fill.lineTo(w, h / 2); fill.close()
                drawPath(fill, Tab.EQ.color.copy(alpha = 0.15f)); drawPath(p, Tab.EQ.color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                for (b in 0 until 5) {
                    val c = Offset(fx(rig.get(P.EQ_B0 + b * 3), w), gy(rig.get(P.EQ_B0 + b * 3 + 1), h))
                    drawCircle(C.band[b].copy(alpha = 0.3f), if (b == sel) 16.dp.toPx() else 12.dp.toPx(), c)
                    drawCircle(C.band[b], 7.dp.toPx(), c); if (b == sel) drawCircle(Color.White, 7.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("LOW", "LM", "MID", "HM", "HIGH").forEachIndexed { i, n ->
                    Text(n, color = if (i == sel) C.band[i] else C.Dim, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (i == sel) C.band[i].copy(alpha = 0.15f) else C.PanelHi)
                            .border(1.dp, if (i == sel) C.band[i] else C.Line, RoundedCornerShape(8.dp)).clickable { sel = i }.padding(vertical = 9.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        Panel {
            KnobRow {
                Knob(P.def(P.EQ_B0 + sel * 3), rig.get(P.EQ_B0 + sel * 3), C.band[sel], { rig.set(P.EQ_B0 + sel * 3, it) }, label = "Freq")
                Knob(P.def(P.EQ_B0 + sel * 3 + 1), rig.get(P.EQ_B0 + sel * 3 + 1), C.band[sel], { rig.set(P.EQ_B0 + sel * 3 + 1, it) }, label = "Gain")
                Knob(P.def(P.EQ_B0 + sel * 3 + 2), rig.get(P.EQ_B0 + sel * 3 + 2), C.band[sel], { rig.set(P.EQ_B0 + sel * 3 + 2, it) }, label = "Q")
                K(rig, P.EQ_OUT, C.Dim)
            }
            Text("LOW band = low shelf · HIGH band = high shelf · others = peaking. Double-tap a knob to reset.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- MOD
@Composable
fun ModScreen(rig: Rig) {
    FxPage(rig, Tab.MOD, "Modulation", "Chorus · Flanger · Phaser · Tremolo") {
        Panel(title = "Type") { Segmented(listOf("Chorus", "Flanger", "Phaser", "Tremolo"), rig.get(P.MOD_TYPE).toInt(), Tab.MOD.color) { rig.set(P.MOD_TYPE, it.toFloat()) } }
        Panel { KnobRow { K(rig, P.MOD_RATE, Tab.MOD.color); K(rig, P.MOD_DEPTH, Tab.MOD.color); K(rig, P.MOD_MIX, Tab.MOD.color); K(rig, P.MOD_FB, Tab.MOD.color) }
            Text("Tremolo uses only Rate & Depth. Feedback is used by Flanger & Phaser.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// ---------------------------------------------------------------- DELAY
@Composable
fun DelayScreen(rig: Rig) {
    var taps by remember { mutableStateOf(listOf<Long>()) }
    val mode = rig.get(P.DLY_MODE).toInt(); val div = rig.get(P.DLY_DIV).toInt(); val bpm = rig.get(P.DLY_BPM)
    val eff = P.delayMs(mode, div, bpm, rig.get(P.DLY_TIME))
    FxPage(rig, Tab.DLY, "Delay", "Digital or tape delay with ducking · repeats keep ringing when bypassed") {
        Panel(title = "Type") {
            Segmented(listOf("Digital", "Tape"), rig.get(P.DLY_TYPE).toInt(), Tab.DLY.color) { rig.set(P.DLY_TYPE, it.toFloat()) }
            Text("Tape adds wow/flutter, saturation and darker repeats. Ducking lowers the repeats while you play and lets them bloom when you stop.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Panel(title = "Delay Time") {
            Segmented(listOf("ms", "Straight", "Triplet", "Dotted"), mode, Tab.DLY.color) { rig.set(P.DLY_MODE, it.toFloat()) }
            Spacer(Modifier.height(12.dp))
            if (mode > 0) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    P.DIV_NAMES.forEachIndexed { i, n ->
                        val sel = i == div
                        Text(n, color = if (sel) Tab.DLY.color else C.Dim, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Tab.DLY.color.copy(alpha = 0.12f) else C.PanelHi)
                                .border(1.dp, if (sel) Tab.DLY.color else C.Line, RoundedCornerShape(16.dp))
                                .clickable { rig.set(P.DLY_DIV, i.toFloat()) }.padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            KnobRow {
                if (mode == 0) K(rig, P.DLY_TIME, Tab.DLY.color, 76.dp) else K(rig, P.DLY_BPM, Tab.DLY.color, 76.dp)
                K(rig, P.DLY_FB, Tab.DLY.color, 76.dp)
            }
            Text(
                if (mode == 0) "= %.0f ms".format(eff)
                else "%s %s = %.0f ms".format(P.DIV_NAMES[div.coerceIn(0, 5)], listOf("", "straight", "triplet", "dotted")[mode], eff),
                color = C.Text, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.padding(top = 8.dp)
            )
        }
        Panel { KnobRow { K(rig, P.DLY_MIX, Tab.DLY.color, 76.dp); K(rig, P.DLY_TONE, Tab.DLY.color, 76.dp); K(rig, P.DLY_DUCK, Tab.DLY.color, 76.dp) } }
        Panel(title = "Tap Tempo") {
            Btn("TAP", Tab.DLY.color, modifier = Modifier.fillMaxWidth()) {
                val now = System.currentTimeMillis()
                taps = (taps.filter { now - it < 2500 } + now).takeLast(5)
                if (taps.size >= 2) {
                    val iv = (taps.last() - taps.first()).toFloat() / (taps.size - 1)   // interval 1 ketukan (1/4)
                    if (mode == 0) rig.set(P.DLY_TIME, iv.coerceIn(20f, 1500f)) else rig.set(P.DLY_BPM, (60000f / iv).coerceIn(40f, 240f))
                }
            }
            Text(if (mode == 0) "Taps set the delay time (ms)." else "Taps set the tempo (BPM).", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ---------------------------------------------------------------- DISTORTION / PITCH
@Composable
private fun ShiftChip(text: String, sel: Boolean, color: Color, onClick: () -> Unit) {
    Text(text, color = if (sel) color else C.Dim, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) color.copy(alpha = 0.12f) else C.PanelHi)
            .border(1.dp, if (sel) color else C.Line, RoundedCornerShape(16.dp)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp))
}

@Composable
fun DriveScreen(rig: Rig) {
    val type = rig.get(P.DRV_TYPE).toInt().coerceIn(0, 4)
    FxPage(rig, Tab.DRIVE, "Distortion", "Overdrive · Distortion · Fuzz — place it anywhere with the Signal Chain") {
        Panel(title = "Type") {
            // (label, type id, group)
            val types = listOf(("Overdrive" to 3), ("Screamer" to 0), ("Centaur" to 2), ("Precision" to 1), ("Fuzz" to 4))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { (n, id) -> ShiftChip(n, id == type, Tab.DRIVE.color) { rig.set(P.DRV_TYPE, id.toFloat()) } }
            }
            Text(
                listOf(
                    "OVERDRIVE · Tube Screamer style: mid-focused, smooth soft clipping, bass stays intact.",
                    "DISTORTION · Precision Drive style: tight low end and hard, high-gain clipping for fast riffs.",
                    "OVERDRIVE · Centaur style: transparent boost that blends clean and driven signal.",
                    "OVERDRIVE · Classic warm tube-style overdrive: asymmetric soft clipping, touch-sensitive and full of body.",
                    "FUZZ · Fuzz-box style: very high gain with asymmetric hard clipping, huge sustain and a gritty, saturated tone."
                )[type],
                color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
        Panel { KnobRow { K(rig, P.DRV_GAIN, Tab.DRIVE.color, 76.dp); K(rig, P.DRV_TONE, Tab.DRIVE.color, 76.dp); K(rig, P.DRV_LEVEL, Tab.DRIVE.color, 76.dp) } }
    }
}

/** Pitch tab: one page with a 3-way selector — Pitch Shifter / Octave / Harmony. */
@Composable
fun PitchScreen(rig: Rig, modeIn: Int, onMode: (Int) -> Unit) {
    val extra = rig.uiBank > 0            // duplicate blocks have no Harmony
    val mode = if (extra && modeIn > 1) 1 else modeIn
    val col = when (mode) { 1 -> Tab.OCT.color; 2 -> Tab.HARM.color; else -> Tab.PITCH.color }
    val sel: @Composable () -> Unit = { Segmented(if (extra) listOf("Pitch Shifter", "Octave") else listOf("Pitch Shifter", "Octave", "Harmony"), mode, col, 10.sp, onMode) }
    when (mode) { 1 -> OctScreen(rig, sel); 2 -> HarmScreen(rig, sel); else -> PitchShiftScreen(rig, sel) }
}

@Composable
private fun PitchShiftScreen(rig: Rig, sel: @Composable () -> Unit) {
    val v = rig.get(P.PITCH_ST)
    FxPage(rig, Tab.PITCH, "Pitch Shifter", "Shift pitch ±24 semitones (±2 octaves) · use Mix for harmonies · controllable with an expression pedal", sel) {
        Panel {
            KnobRow { K(rig, P.PITCH_ST, Tab.PITCH.color, 80.dp); K(rig, P.PITCH_FINE, Tab.PITCH.color, 80.dp); K(rig, P.PITCH_MIX, Tab.PITCH.color, 80.dp) }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-24, -19, -12, -7, -5, -2, 0, 2, 5, 7, 12, 19, 24).forEach { st ->
                    ShiftChip(if (st > 0) "+$st" else "$st", abs(v - st) < 0.05f, Tab.PITCH.color) { rig.set(P.PITCH_ST, st.toFloat()) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("−1 st", Tab.PITCH.color) { rig.set(P.PITCH_ST, (Math.round(v) - 1f).coerceAtLeast(-24f)) }
                Btn("+1 st", Tab.PITCH.color) { rig.set(P.PITCH_ST, (Math.round(v) + 1f).coerceAtMost(24f)) }
            }
        }
        Panel(title = "Notes") {
            Text("Mix 100% = fully shifted · Mix 50% = harmony (+7 = fifth, +12 = octave, +24 = two octaves). Adds about 25 ms of latency while active. Works best on single notes and power chords.", color = C.Dim, fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------- REVERB
@Composable
fun ReverbScreen(rig: Rig) {
    val shim = rig.get(P.REV_TYPE) > 0.5f
    FxPage(rig, Tab.REV, "Reverb", "Stereo room/hall or shimmer reverb · tail keeps ringing when bypassed") {
        Panel(title = "Type") {
            Segmented(listOf("Room / Hall", "Shimmer"), if (shim) 1 else 0, Tab.REV.color) { rig.set(P.REV_TYPE, it.toFloat()) }
            Text("Shimmer feeds an octave-up copy of the tail back into the reverb for a glassy, ethereal wash.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Panel { KnobRow { K(rig, P.REV_SIZE, Tab.REV.color, 76.dp); K(rig, P.REV_DAMP, Tab.REV.color, 76.dp); K(rig, P.REV_MIX, Tab.REV.color, 76.dp); if (shim) K(rig, P.REV_SHIM, Tab.REV.color, 76.dp) } }
    }
}

// ---------------------------------------------------------------- LIMITER
@Composable
fun LimiterScreen(rig: Rig) {
    val thr = rig.get(P.LIM_THR); val ce = rig.get(P.LIM_CEIL)
    DisposableEffect(Unit) { rig.wantHist = true; onDispose { rig.wantHist = false } }
    FxPage(rig, Tab.LIM, "Limiter", "Brickwall lookahead · real-time transfer curve") {
        Panel(title = "Transfer Curve") {
            Canvas(Modifier.display(220.dp)) {
                val m = rig.meters
                grid(8)
                fun X(d: Float) = (d + 40f) / 40f * size.width
                fun Y(d: Float) = size.height - (d + 40f) / 40f * size.height
                fun f(x: Float) = (minOf(x, thr) - thr + ce).coerceIn(-40f, 0f)
                drawLine(C.Off, Offset(0f, size.height), Offset(size.width, 0f), 1.5f)
                val p = Path(); for (i in 0..80) { val x = -40f + i * 0.5f; val o = Offset(X(x), Y(f(x))); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                drawPath(p, Tab.LIM.color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawLine(C.Amber.copy(alpha = 0.6f), Offset(X(thr), 0f), Offset(X(thr), size.height), 1.5f)
                if (m[12] > 0.5f) {
                    val xi = m[6].coerceIn(-40f, 0f); val yo = m[7].coerceIn(-40f, 0f)
                    drawCircle(C.Text.copy(alpha = 0.25f), 11.dp.toPx(), Offset(X(xi), Y(yo))); drawCircle(C.Text, 5.dp.toPx(), Offset(X(xi), Y(yo)))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("X: input level (dB) · Y: output level (dB)", color = C.Dim, fontSize = 10.sp)
        }
        Panel(title = "Gain Reduction") {
            Canvas(Modifier.display(70.dp)) {
                val hist = rig.limHist; val n = hist.size; val bw = size.width / n
                for (i in 0 until n) { val hgt = (hist[i] / 12f).coerceIn(0f, 1f) * size.height; drawRect(C.Amber.copy(alpha = 0.8f), Offset(i * bw, 0f), Size(bw + 0.5f, hgt)) }
            }
            Spacer(Modifier.height(8.dp)); MeterBar({ rig.meters[5] }, "GR", gr = true, grMax = 12f)
            MeterBar({ rig.meters[1] }, "OUT")
        }
        Panel { KnobRow { K(rig, P.LIM_THR, Tab.LIM.color, 76.dp); K(rig, P.LIM_CEIL, Tab.LIM.color, 76.dp); K(rig, P.LIM_REL, Tab.LIM.color, 76.dp) }
            Text("Lower Threshold = louder (Ceiling is the final limit).", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// ---------------------------------------------------------------- OCTAVE / HARMONY / WAH
@Composable
fun OctScreen(rig: Rig, sel: @Composable () -> Unit) {
    FxPage(rig, Tab.OCT, "Octaver", "Analog-style octave down and up · works best on single notes", sel) {
        Panel { KnobRow { K(rig, P.OCT_DOWN, Tab.OCT.color, 80.dp); K(rig, P.OCT_UP, Tab.OCT.color, 80.dp); K(rig, P.OCT_DRY, Tab.OCT.color, 80.dp) } }
        Panel(title = "Notes") {
            Text("Play single notes for the cleanest tracking. Octave Down is a classic flip-flop sub-octave; Octave Up is a rectifier-style octave. Neither adds noticeable latency.", color = C.Dim, fontSize = 11.sp)
        }
    }
}

@Composable
fun HarmScreen(rig: Rig, sel: @Composable () -> Unit) {
    val key = rig.get(P.HARM_KEY).toInt(); val iv = rig.get(P.HARM_INT).toInt(); val minor = rig.get(P.HARM_SCALE) > 0.5f
    FxPage(rig, Tab.HARM, "Harmonizer", "Scale-aware harmony: follows your notes and stays in key", sel) {
        Panel(title = "Key") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                P.KEY_NAMES.forEachIndexed { i, n -> ShiftChip(n, i == key, Tab.HARM.color) { rig.set(P.HARM_KEY, i.toFloat()) } }
            }
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("Major", "Minor"), if (minor) 1 else 0, Tab.HARM.color) { rig.set(P.HARM_SCALE, it.toFloat()) }
        }
        Panel(title = "Interval") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                P.HARM_INTERVALS.forEachIndexed { i, n -> ShiftChip(n, i == iv, Tab.HARM.color) { rig.set(P.HARM_INT, i.toFloat()) } }
            }
            Spacer(Modifier.height(10.dp))
            KnobRow { K(rig, P.HARM_MIX, Tab.HARM.color, 80.dp) }
            Text("Current harmony: %+.0f semitones".format(rig.harmSemis), color = C.Text, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.padding(top = 8.dp))
        }
        Panel(title = "Notes") {
            Text("Pitch is detected from your guitar, so the harmony voice needs a moment (~50–80 ms) to lock onto each new note. Best for single-note lines. Adds about 25 ms of latency while active.", color = C.Dim, fontSize = 11.sp)
        }
    }
}

@Composable
fun WahScreen(rig: Rig) {
    val auto = rig.get(P.WAH_MODE) > 0.5f
    FxPage(rig, Tab.WAH, "Wah", "Pedal wah or auto-wah · long-press the Pedal knob to learn an expression pedal") {
        Panel(title = "Mode") {
            Segmented(listOf("Pedal", "Auto"), if (auto) 1 else 0, Tab.WAH.color) { rig.set(P.WAH_MODE, it.toFloat()) }
        }
        Panel {
            KnobRow {
                if (!auto) K(rig, P.WAH_POS, Tab.WAH.color, 90.dp) else K(rig, P.WAH_SENS, Tab.WAH.color, 90.dp)
                K(rig, P.WAH_Q, Tab.WAH.color, 90.dp)
            }
            Text(if (auto) "Auto mode sweeps the filter with your picking dynamics." else "Pedal: 0 = heel down (dark), 1 = toe down (bright). Assign it in the MIDI tab → Parameters.",
                color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
