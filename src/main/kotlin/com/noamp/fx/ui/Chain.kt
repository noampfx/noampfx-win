package com.noamp.fx.ui

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.noamp.fx.*
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Modules (index = module code - 1). Gate (1) is fixed at the input pot; the chain slots offer modules 2..13.
 *  Movable modules. The module code used by the engine (P.CHAIN0 slots) is the index + 1. */
val ChainTabs: List<Tab> = listOf(Tab.GATE, Tab.COMP, Tab.OCT, Tab.PITCH, Tab.HARM, Tab.WAH, Tab.DRIVE, Tab.NAM, Tab.CAB, Tab.EQ, Tab.MOD, Tab.DLY, Tab.REV)

private const val COLS = 6

// grid geometry shared by the grid and the vertical meters beside it
private val BLOCK_S = 38.dp; private val LABEL_H = 14.dp; private val ROW_GAP = 16.dp
private val GRID_H = (BLOCK_S + LABEL_H + ROW_GAP) * 2 - ROW_GAP
private val MIC_CAP_H = 8.dp                                    // gap between the channel 1 grid and the channel 2 row
private val MIC_H = BLOCK_S + LABEL_H + 8.dp                     // one row of the grid + room so the labels are not cut by the bottom line
private val CHAIN_TOTAL_H = GRID_H + MIC_CAP_H + MIC_H         // channel 1 grid + channel 2 row (height of the side meters)

/**
 * Fixed header (below the top bar, visible on every tab):
 *   preset bar:  [⋯][⌃⌄] Preset name  [camera = scene] [Edit]
 *   chain:       IN meter | [IN] [Gain pot] [slot 1..8] [Vol pot] [OUT] | OUT L / R meters
 * Slots are empty by default: tap an empty slot to fill it with any module. Tap a filled block = bypass, long press = open its module.
 * Gain pot: drag = level, tap = Gate on/off, long press = Gate module. Vol pot: drag = level, tap = Limiter on/off, long press = Limiter module.
 * IN / OUT open the audio-interface popup. Edit: drag blocks between slots, tap to change the module, ✕ to empty the slot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SignalChainBar(rig: Rig, go: (Tab, Int) -> Unit, onStage: () -> Unit, onHome: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var pickSlot by remember { mutableStateOf(-1) }
    var io by remember { mutableStateOf(0) }            // 1 = input popup, 2 = output popup
    Column(Modifier.fillMaxWidth().background(C.Panel)) {
        PresetBar(rig, edit, { edit = !edit }, onStage, onHome)
        if (edit) Text("Drag a block to move it · tap to change the module · ✕ to empty", color = C.Dim, fontSize = 9.sp, modifier = Modifier.padding(start = 14.dp, bottom = 2.dp))
        Spacer(Modifier.height(10.dp))                                                                       // breathing room between preset bar and signal chain
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp), verticalAlignment = Alignment.Top) {       // meters sit a little inside the preset bar's black border (8 dp)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { VMeter({ rig.meters[0] }, "1", CHAIN_TOTAL_H); VMeter({ rig.meters[16] }, "2", CHAIN_TOTAL_H) }   // input 1 (guitar) / input 2 (mic)
            Spacer(Modifier.width(11.dp))                                                                    // wire stays clear of the meters
            Box(Modifier.weight(1f)) {
                Column {
                    ChainGrid(rig, edit, go, onPick = { pickSlot = it }, onIo = { io = it })
                    MicChainBar(rig, edit, onOut = { io = 2 }, onOpen = { slot -> rig.uiMicSlot = slot; go(Tab.MIC, 0) })                                               // channel 2 (microphone) chain, same grid as channel 1
                }
            }
            Spacer(Modifier.width(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { VMeter({ rig.meters[1] }, "L", CHAIN_TOTAL_H); VMeter({ rig.meters[2] }, "R", CHAIN_TOTAL_H) }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
    }
    if (pickSlot >= 0) SlotPickerDialog(rig, pickSlot) { pickSlot = -1 }
    if (io != 0) AudioIoDialog(rig, io == 1) { io = 0 }
}

/** Vertical LED level meter (-60..0 dBFS), same colours as the horizontal meters. [height] = label included. */
@Composable
private fun VMeter(src: () -> Float, label: String, height: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(7.dp).height(height - 12.dp)) {
            val segs = (size.height / 4.9.dp.toPx()).toInt().coerceAtLeast(10); val gap = 1.dp.toPx(); val h = (size.height - gap * (segs - 1)) / segs
            val frac = ((src() + 60f) / 60f).coerceIn(0f, 1f)
            for (i in 0 until segs) {
                val p = i / segs.toFloat(); val lit = p < frac
                val col = when { p < 0.70f -> C.Green; p < 0.92f -> C.Amber; else -> C.Red }
                drawRoundRect(if (lit) col else C.Line, Offset(0f, size.height - (i + 1) * h - i * gap), Size(size.width, h), CornerRadius(1.5f, 1.5f))
            }
        }
        Box(Modifier.height(12.dp), contentAlignment = Alignment.Center) { Text(label, color = C.Dim, fontSize = 8.sp, fontFamily = Mono, maxLines = 1) }
    }
}

private val ChainLabels = listOf("Gate", "Comp", "Octave", "Pitch", "Harmony", "Wah", "Dist", "Amp", "Cab", "EQ", "Mod", "Delay", "Reverb")
private val BlockBg = Color(0xFF15171B)
private val WireColor = Color(0xFF7C818A)
private val BlockBorder = Color(0xFF4B4F57)

/** Icon codes: 1..13 = modules (same as engine codes), 20 = input, 21 = output, 22 = limiter. */
private fun DrawScope.chainIcon(code: Int, col: Color) {
    val w = size.minDimension; val sw = w * 0.075f
    val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun pt(x: Float, y: Float) = Offset(x * w, y * w)
    fun poly(vararg v: Float, c: Color = col) {
        val p = Path(); var i = 0
        while (i + 1 < v.size) { if (i == 0) p.moveTo(v[i] * w, v[i + 1] * w) else p.lineTo(v[i] * w, v[i + 1] * w); i += 2 }
        drawPath(p, c, style = st)
    }
    fun sine(x0: Float, x1: Float, cy: Float, amp: Float, cyc: Float, c: Color = col) {
        val p = Path(); val n = 40
        for (i in 0..n) {
            val t = i / n.toFloat(); val x = (x0 + (x1 - x0) * t) * w; val y = (cy - amp * sin(2f * PI.toFloat() * cyc * t)) * w
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, c, style = st)
    }
    when (code) {
        1 -> poly(.10f, .68f, .32f, .68f, .32f, .30f, .68f, .30f, .68f, .68f, .90f, .68f)
        2 -> {
            drawLine(col.copy(alpha = 0.45f), pt(.15f, .85f), pt(.85f, .15f), sw * 0.6f, StrokeCap.Round)
            val p = Path(); p.moveTo(.15f * w, .85f * w); p.lineTo(.46f * w, .54f * w); p.quadraticBezierTo(.58f * w, .40f * w, .86f * w, .34f * w); drawPath(p, col, style = st)
        }
        3 -> { sine(.10f, .90f, .32f, .15f, 1f); sine(.10f, .90f, .72f, .15f, 2f) }
        4 -> { poly(.34f, .84f, .34f, .20f); poly(.22f, .34f, .34f, .20f, .46f, .34f); poly(.66f, .20f, .66f, .84f); poly(.54f, .70f, .66f, .84f, .78f, .70f) }
        5 -> {
            poly(.30f, .70f, .70f, .30f, c = col.copy(alpha = 0.45f))
            drawCircle(col, .095f * w, pt(.30f, .70f)); drawCircle(col, .095f * w, pt(.50f, .50f)); drawCircle(col, .095f * w, pt(.70f, .30f))
        }
        6 -> {
            poly(.34f, .88f, .66f, .88f, .60f, .16f, .40f, .16f, .34f, .88f)
            for (y in listOf(.38f, .54f, .70f)) drawLine(col.copy(alpha = 0.6f), pt(.38f, y), pt(.62f, y), sw * 0.6f, StrokeCap.Round)
        }
        7 -> poly(.10f, .68f, .10f, .30f, .30f, .30f, .30f, .70f, .50f, .70f, .50f, .30f, .70f, .30f, .70f, .70f, .90f, .70f)
        8 -> {
            drawRoundRect(col, pt(.12f, .40f), Size(.76f * w, .40f * w), CornerRadius(.06f * w), style = st)
            poly(.34f, .40f, .34f, .24f, .66f, .24f, .66f, .40f)
            for (x in listOf(.28f, .40f, .52f)) drawCircle(col, .035f * w, pt(x, .60f))
            drawLine(col, pt(.64f, .60f), pt(.76f, .60f), sw * 0.8f, StrokeCap.Round)
        }
        9 -> poly(.08f, .52f, .38f, .52f, .46f, .14f, .54f, .86f, .62f, .52f, .92f, .52f)
        10 -> {
            val p = Path(); p.moveTo(.10f * w, .50f * w)
            p.cubicTo(.25f * w, .50f * w, .30f * w, .20f * w, .45f * w, .30f * w); p.cubicTo(.60f * w, .40f * w, .60f * w, .78f * w, .75f * w, .66f * w); p.cubicTo(.82f * w, .60f * w, .85f * w, .50f * w, .90f * w, .50f * w)
            drawPath(p, col, style = st)
        }
        11 -> sine(.08f, .92f, .50f, .24f, 2f)
        12 -> { drawCircle(col.copy(alpha = 0.55f), .20f * w, pt(.38f, .50f), style = st); drawCircle(col, .26f * w, pt(.58f, .50f), style = st) }
        13 -> {
            poly(.50f, .12f, .84f, .31f, .84f, .69f, .50f, .88f, .16f, .69f, .16f, .31f, .50f, .12f)
            poly(.16f, .31f, .50f, .50f, .84f, .31f); poly(.50f, .50f, .50f, .88f)
        }
        20, 21 -> {
            val hs = listOf(.22f, .46f, .72f, .36f, .58f, .26f, .42f)
            hs.forEachIndexed { i, h -> val x = .18f + i * .107f; drawLine(col, pt(x, .5f - h / 2f), pt(x, .5f + h / 2f), sw * 0.75f, StrokeCap.Round) }
        }
        0 -> { poly(.5f, .2f, .5f, .8f); poly(.2f, .5f, .8f, .5f) }
        22 -> { poly(.12f, .82f, .52f, .32f, .90f, .32f); drawLine(col.copy(alpha = 0.4f), pt(.10f, .20f), pt(.90f, .20f), sw * 0.6f, StrokeCap.Round) }
    }
}

@Composable
private fun ChainIcon(code: Int, color: Color, size: Dp) { Canvas(Modifier.size(size)) { chainIcon(code, color) } }

@Composable
private fun ChainPot(label: String, def: PDef, value: Float, active: Boolean, color: Color, size: Dp, onChange: (Float) -> Unit, onToggle: () -> Unit, onOpen: () -> Unit) {
    val v by rememberUpdatedState(value); val cb by rememberUpdatedState(onChange)
    val tg by rememberUpdatedState(onToggle); val op by rememberUpdatedState(onOpen)
    val ring = if (active) color else BlockBorder
    Box(
        Modifier.size(size).clip(CircleShape).background(BlockBg).border(if (active) 2.dp else 1.5.dp, ring, CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { tg() }, onLongPress = { op() }) }
            .pointerInput(def.id) {
                val range = 150.dp.toPx()
                detectVerticalDragGestures { change, dy -> change.consume(); cb(def.fromNorm((def.toNorm(v) - dy / range).coerceIn(0f, 1f))) }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val sw = 2.5.dp.toPx(); val st = Stroke(sw, cap = StrokeCap.Round)
            drawArc(C.Line, 135f, 270f, false, style = st)
            drawArc(if (active) color else color.copy(alpha = 0.35f), 135f, 270f * def.toNorm(v), false, style = st)
        }
        Text("%.0f".format(v), color = if (active) C.Text else C.Dim, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChainGrid(rig: Rig, edit: Boolean, go: (Tab, Int) -> Unit, onPick: (Int) -> Unit, onIo: (Int) -> Unit) {
    val slots = rig.slots
    val items = P.CHAIN_SLOTS + 4                         // gain pot, IN, 8 slots, OUT, volume pot
    val rows = (items + COLS - 1) / COLS
    val density = LocalDensity.current
    var dragEntry by remember { mutableStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }      // absolute top-left (px) of the block being dragged
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideM = 4.dp; val blockS = BLOCK_S; val labelH = LABEL_H; val rowGap = ROW_GAP
        val rowH = blockS + labelH + rowGap
        val cellW = (maxWidth - sideM * 2) / COLS
        val cw = with(density) { cellW.toPx() }; val mPx = with(density) { sideM.toPx() }
        val sPx = with(density) { blockS.toPx() }; val rowPx = with(density) { rowH.toPx() }
        val labPx = with(density) { labelH.toPx() }; val gapPx = with(density) { rowGap.toPx() }
        fun cellLeft(k: Int) = mPx + (k % COLS) * cw
        fun cellTop(k: Int) = (k / COLS) * rowPx
        fun origin(k: Int) = Offset(cellLeft(k), cellTop(k))
        val SLOT0 = 2; val kVol = SLOT0 + P.CHAIN_SLOTS; val kOut = kVol + 1       // order: IN, Gain pot, slots 1..8, Vol pot, OUT
        Box(Modifier.fillMaxWidth().height(rowH * rows - rowGap)) {
            // ---- wire (behind everything): runs along each row, wraps to the next row with rounded corners
            Canvas(Modifier.fillMaxSize()) {
                val sw = 2.5.dp.toPx(); val wire = WireColor; val r = 8.dp.toPx()
                val xl = 2.dp.toPx(); val xr = size.width - 2.dp.toPx()
                fun cx(k: Int) = cellLeft(k) + cw / 2f
                fun cy(row: Int) = row * rowPx + sPx / 2f
                for (row in 0 until rows) {
                    val first = row * COLS; val last = minOf(items - 1, first + COLS - 1)
                    if (row == rows - 1) {
                        drawLine(wire, Offset(cx(first), cy(row)), Offset(cx(last), cy(row)), sw, StrokeCap.Round)
                    } else {
                        val y0 = cy(row); val y1 = cy(row + 1); val ym = row * rowPx + sPx + labPx + gapPx / 2f
                        val p = Path()
                        p.moveTo(cx(first), y0)
                        p.lineTo(xr - r, y0); p.quadraticBezierTo(xr, y0, xr, y0 + r)
                        p.lineTo(xr, ym - r); p.quadraticBezierTo(xr, ym, xr - r, ym)
                        p.lineTo(xl + r, ym); p.quadraticBezierTo(xl, ym, xl, ym + r)
                        p.lineTo(xl, y1 - r); p.quadraticBezierTo(xl, y1, xl + r, y1)
                        p.lineTo(cx((row + 1) * COLS), y1)
                        drawPath(p, wire, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
            // ---- IN / OUT nodes
            @Composable
            fun Node(k: Int, code: Int, label: String, color: Color) {
                val o = origin(k)
                Column(Modifier.offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }.width(cellW), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(blockS).clip(CircleShape).background(BlockBg).border(2.dp, WireColor, CircleShape).clickable { onIo(if (code == 20) 1 else 2) },
                        contentAlignment = Alignment.Center
                    ) { ChainIcon(code, color, 22.dp) }
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                }
            }
            val ioColor = if (rig.running) C.Green else Color(0xFFB9BDC4)
            Node(0, 20, "In", ioColor)
            Node(kOut, 21, "Out", ioColor)

            // ---- pots: input gain (Gate) before IN, output volume (Limiter) after OUT
            @Composable
            fun Pot(k: Int, label: String, paramId: Int, onId: Int, tab: Tab) {
                val o = origin(k); val on = rig.onAbs(onId); val def = P.def(paramId)
                Column(Modifier.offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }.width(cellW), horizontalAlignment = Alignment.CenterHorizontally) {
                    ChainPot(label, def, rig.getAbs(paramId), on, tab.color, blockS, { rig.setAbs(paramId, it) }, { rig.toggleAbs(onId) }, { go(tab, 0) })
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = if (on) tab.color else C.Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                }
            }
            Pot(1, "Gain", P.MASTER_IN, P.GATE_ON, Tab.GATE)
            Pot(kVol, "Vol", P.MASTER_OUT, P.LIM_ON, Tab.LIM)

            // ---- the 8 slots. Empty slots are keyed by index, filled blocks by their entry, so a block keeps its identity (and its drag gesture) while it moves between slots.
            for (s in 0 until P.CHAIN_SLOTS) if (slots[s] == 0) key("empty$s") {
                val o = origin(SLOT0 + s); val shape = RoundedCornerShape(9.dp)
                Column(Modifier.offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }.width(cellW).clickable { onPick(s) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(blockS).clip(shape).background(BlockBg.copy(alpha = 0.5f)).drawBehind {
                            drawRoundRect(WireColor.copy(alpha = if (edit) 0.9f else 0.6f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))), cornerRadius = CornerRadius(9.dp.toPx()))
                        },
                        contentAlignment = Alignment.Center
                    ) { Text("+", color = C.Dim, fontSize = 20.sp, fontWeight = FontWeight.Light) }
                }
            }
            slots.filter { it != 0 }.forEach { entry ->
                key(entry) {
                    val s = slots.indexOf(entry)
                    val m = P.entryModule(entry); val bank = P.entryBank(entry)
                    val tab = ChainTabs[m - 1]; val onId = bank * P.COUNT_BASE + tab.onId!!
                    val on = rig.onAbs(onId)
                    val dragging = dragEntry == entry
                    // the dragged block follows the finger exactly; every other block glides to its slot
                    val pos by animateOffsetAsState(if (dragging) dragPos else origin(SLOT0 + s), if (dragging) snap() else tween(170), label = "blk")
                    val posNow by rememberUpdatedState(pos)
                    val shape = RoundedCornerShape(9.dp)
                    var colMod = Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }.width(cellW).zIndex(if (dragging) 1f else 0f)
                    colMod = if (edit) {
                        colMod.pointerInput(entry, cw, rows) {
                            detectDragGestures(
                                onDragStart = { dragEntry = entry; dragPos = posNow },
                                onDragEnd = { dragEntry = -1 },
                                onDragCancel = { dragEntry = -1 },
                                onDrag = { change, amount ->
                                    change.consume(); dragPos += amount
                                    val cur = rig.slots.indexOf(entry)
                                    if (cur >= 0) {
                                        val c = dragPos + Offset(cw / 2f, sPx / 2f)
                                        val col = ((c.x - mPx) / cw).toInt().coerceIn(0, COLS - 1); val row = (c.y / rowPx).toInt().coerceIn(0, rows - 1)
                                        val tgt = (row * COLS + col - SLOT0).coerceIn(0, P.CHAIN_SLOTS - 1)
                                        if (tgt != cur) rig.swapSlots(cur, tgt)
                                    }
                                }
                            )
                        }.clickable { onPick(s) }
                    } else colMod.combinedClickable(onClick = { rig.toggleAbs(onId) }, onLongClick = { go(tab, bank) })
                    Column(colMod, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(blockS).clip(shape).background(BlockBg)
                                .border(if (dragging) 2.dp else if (on) 2.dp else 1.5.dp, if (dragging) Color.White else if (on) tab.color else BlockBorder, shape),
                            contentAlignment = Alignment.Center
                        ) {
                            ChainIcon(m, if (on) tab.color else tab.color.copy(alpha = 0.3f), 24.dp)
                            if (bank > 0) Text("${bank + 1}", color = if (on) tab.color else C.Dim, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp))
                            if (edit) Box(
                                Modifier.align(Alignment.TopEnd).padding(1.dp).size(15.dp).clip(CircleShape).background(C.Red).clickable { rig.clearSlot(rig.slots.indexOf(entry)) },
                                contentAlignment = Alignment.Center
                            ) { Text("✕", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black) }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(ChainLabels[m - 1] + if (bank > 0) " ${bank + 1}" else "", color = if (on) tab.color else C.Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotPickerDialog(rig: Rig, slot: Int, onClose: () -> Unit) {
    val cur = rig.slots.getOrElse(slot) { 0 }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Block ${slot + 1}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Choose the module for this block. The same module can be used more than once (e.g. 2 Distortions); Harmony, Amp and Cabinet exist once.", color = C.Dim, fontSize = 11.sp)
                P.SLOT_MODULES.forEach { m ->
                    val t = ChainTabs[m - 1]; val free = rig.freeBank(m, slot) != null; val isCur = cur != 0 && P.entryModule(cur) == m
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(if (isCur) t.color.copy(alpha = 0.12f) else C.PanelHi).border(1.dp, if (isCur) t.color else C.Line, shape)
                            .clickable(enabled = free) { rig.fillSlot(slot, m); onClose() }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChainIcon(m, if (free) t.color else t.color.copy(alpha = 0.3f), 22.dp); Spacer(Modifier.width(10.dp))
                        Text(t.label, color = if (free) C.Text else C.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (isCur) "current" else if (!free) "in use" else "+", color = if (free) t.color else C.Dim, fontSize = if (free && !isCur) 16.sp else 10.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        dismissButton = if (cur != 0) { { TextButton(onClick = { rig.clearSlot(slot); onClose() }) { Text("Empty this block") } } } else null
    )
}

/** IN / OUT node popup: choose the audio interface input or output device. */
@Composable
private fun AudioIoDialog(rig: Rig, input: Boolean, onClose: () -> Unit) {
    val devs = if (input) rig.inDevs else rig.outDevs
    val sel = if (input) rig.selIn else rig.selOut
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (input) "Audio Input" else "Audio Output") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (input) "Choose the audio interface input (guitar / line in)." else "Choose the audio interface output (monitors / headphones).", color = C.Dim, fontSize = 11.sp)
                devs.forEach { d ->
                    val on = d.id == sel; val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(if (on) C.Green.copy(alpha = 0.12f) else C.PanelHi).border(1.dp, if (on) C.Green else C.Line, shape)
                            .clickable {
                                if (d.id != sel) { if (input) rig.selIn = d.id else rig.selOut = d.id; rig.restartIfRunning() }
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Led(on, C.Green); Spacer(Modifier.width(10.dp))
                        Text(d.name, color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        if (d.usb) Text("USB", color = C.Accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }
                if (devs.none { it.usb }) Text("No audio interface detected. Connect an interface (USB/OTG) and tap Refresh.", color = C.Dim, fontSize = 11.sp)
                if (rig.status.isNotEmpty()) Text(rig.status, color = C.Amber, fontSize = 11.sp)
                Text(if (rig.running) "Engine running — changing the device restarts it." else "Engine stopped — the device is used on the next start.", color = C.Dim, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = { rig.refreshDevices() }) { Text("Refresh") } }
    )
}

// ============================================================================ Channel 2 (microphone) chain
private fun micTab(type: Int): Tab = when (type) { 1 -> Tab.GATE; 2 -> Tab.COMP; 3 -> Tab.EQ; 4 -> Tab.DLY; else -> Tab.REV }
private fun micLabel(type: Int): String = if (type == MicP.T_EQ) "Param EQ" else micTab(type).label
private fun micIcon(type: Int): Int = when (type) { 1 -> 1; 2 -> 2; 3 -> 10; 4 -> 12; else -> 13 }     // chainIcon codes

/**
 * Channel 2 chain under the main chain, built on the SAME grid as channel 1 (6 columns, same block size, wire, nodes and colours):
 *   [IN 2] [block 1] [block 2] [block 3] [block 4] [OUT]
 * IN 2 = microphone input menu, OUT = output device menu.  Filled block: tap = bypass, long press = parameters.
 * Empty block: tap = choose a module.  Edit mode: tap a block = change it, ✕ = empty it.  Each module exists once; the reverb (stereo) is always the last block.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MicChainBar(rig: Rig, edit: Boolean, onOut: () -> Unit, onOpen: (Int) -> Unit) {
    var pick by remember { mutableStateOf(-1) }
    var inDlg by remember { mutableStateOf(false) }
    var devDlg by remember { mutableStateOf(false) }
    val on = rig.micOn
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(MIC_CAP_H))
        MicChainGrid(rig, edit, onIn = { inDlg = true }, onOut = onOut, onPick = { pick = it }, onParam = onOpen)
    }
    if (pick >= 0) MicSlotPicker(rig, pick) { pick = -1 }
    if (inDlg) MicInputDialog(rig, onDevice = { inDlg = false; devDlg = true }) { inDlg = false }
    if (devDlg) AudioIoDialog(rig, true) { devDlg = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MicChainGrid(rig: Rig, edit: Boolean, onIn: () -> Unit, onOut: () -> Unit, onPick: (Int) -> Unit, onParam: (Int) -> Unit) {
    val density = LocalDensity.current
    val on = rig.micOn
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideM = 4.dp; val blockS = BLOCK_S
        val cellW = (maxWidth - sideM * 2) / COLS
        val cw = with(density) { cellW.toPx() }; val mPx = with(density) { sideM.toPx() }; val sPx = with(density) { blockS.toPx() }
        fun left(k: Int) = mPx + k * cw
        Box(Modifier.fillMaxWidth().height(MIC_H)) {
            Canvas(Modifier.fillMaxSize()) {                                                                    // wire, same style as channel 1
                val y = sPx / 2f
                drawLine(if (on) WireColor else WireColor.copy(alpha = 0.35f), Offset(left(0) + cw / 2f, y), Offset(left(COLS - 1) + cw / 2f, y), 2.5.dp.toPx(), StrokeCap.Round)
            }
            @Composable
            fun Node(k: Int, code: Int, label: String, color: Color, click: () -> Unit) {
                Column(Modifier.offset { IntOffset(left(k).roundToInt(), 0) }.width(cellW), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(blockS).clip(CircleShape).background(BlockBg).border(2.dp, WireColor, CircleShape).clickable { click() },
                        contentAlignment = Alignment.Center
                    ) { ChainIcon(code, color, 22.dp) }
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                }
            }
            val ioColor = if (on && rig.micActive) C.Green else Color(0xFFB9BDC4)
            Node(0, 20, "In 2", ioColor, onIn)
            Node(COLS - 1, 21, "Out", ioColor, onOut)
            for (s in 0..3) {
                val k = 1 + s; val t = rig.micSlot(s)
                if (t == 0) {
                    val shape = RoundedCornerShape(9.dp)
                    Column(Modifier.offset { IntOffset(left(k).roundToInt(), 0) }.width(cellW).clickable { onPick(s) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(blockS).clip(shape).background(BlockBg.copy(alpha = 0.5f)).drawBehind {
                                drawRoundRect(WireColor.copy(alpha = if (edit) 0.9f else 0.6f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))), cornerRadius = CornerRadius(9.dp.toPx()))
                            },
                            contentAlignment = Alignment.Center
                        ) { Text("+", color = C.Dim, fontSize = 20.sp, fontWeight = FontWeight.Light) }
                    }
                } else {
                    val tab = micTab(t); val m = micIcon(t); val onId = MicP.onId(t); val lit = on && rig.micFx[onId] > 0.5f
                    val shape = RoundedCornerShape(9.dp)
                    Column(
                        Modifier.offset { IntOffset(left(k).roundToInt(), 0) }.width(cellW)
                            .combinedClickable(onClick = { if (edit) onPick(s) else rig.setMicFx(onId, if (rig.micFx[onId] > 0.5f) 0f else 1f) }, onLongClick = { onParam(s) }),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(blockS).clip(shape).background(BlockBg).border(if (lit) 2.dp else 1.5.dp, if (lit) tab.color else BlockBorder, shape),
                            contentAlignment = Alignment.Center
                        ) {
                            ChainIcon(m, if (lit) tab.color else tab.color.copy(alpha = 0.3f), 24.dp)
                            if (edit) Box(
                                Modifier.align(Alignment.TopEnd).padding(1.dp).size(15.dp).clip(CircleShape).background(C.Red).clickable { rig.setMicSlot(s, 0) },
                                contentAlignment = Alignment.Center
                            ) { Text("✕", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black) }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(micLabel(t), color = if (lit) tab.color else C.Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
    }
}

/** "-∞" at the bottom of a fader (-50 dB), else "+1 dB". */
fun dbText(db: Float): String = if (db <= -49.9f) "-∞ dB" else "%+.0f dB".format(db)

/** Input 2 is channel 2 of the audio interface (guitar on 1, microphone on 2). */
@Composable
fun MicSourcePicker(rig: Rig) {
    Text("Input 1 and input 2 are the first two channels of the audio interface chosen as Input (guitar on 1, microphone on 2).", color = C.Dim, fontSize = 10.sp)
}

/** One status line for input 2, shared by the IN 2 popup and Settings. */
fun micStatusText(rig: Rig): String =
    if (!rig.running) "Start the engine to activate the microphone."
    else if (rig.micActive) "Microphone active (input 2)."
    else "This input has only 1 channel. Choose a 2-channel audio interface as Input."

/** IN 2 popup: microphone on/off, source, level, status and the interface chooser. */
@Composable
private fun MicInputDialog(rig: Rig, onDevice: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Input 2 · Microphone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use input 2 as microphone", color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    PowerToggle(rig.micOn, C.Green, if (rig.micOn) "ON" else "OFF") { rig.enableMic(!rig.micOn) }
                }
                Text("The microphone does not go through the guitar chain; it is mixed into the output, recordings and videos.", color = C.Dim, fontSize = 11.sp)
                if (rig.micOn) {
                    MicSourcePicker(rig)
                    Text("IN 2 LEVEL  ${dbText(rig.micFx[MicP.PRE])}", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
                    Slider(
                        value = rig.micFx[MicP.PRE], onValueChange = { rig.setMicFx(MicP.PRE, Math.round(it).toFloat()) }, valueRange = -50f..3f,
                        colors = SliderDefaults.colors(thumbColor = C.Amber, activeTrackColor = C.Amber, inactiveTrackColor = C.Line)
                    )
                    Text("MIC TO OUTPUT  ${dbText(rig.micDb)}", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
                    Slider(
                        value = rig.micDb, onValueChange = { rig.setMicLevel(Math.round(it).toFloat()) }, valueRange = -50f..3f,
                        colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Line)
                    )
                    Text("Same as the IN 2 and Out channel 2 faders in the Mixer (-50 dB = off, +3 dB = maximum).", color = C.Dim, fontSize = 10.sp)
                    Text(micStatusText(rig), color = if (rig.running && !rig.micActive) C.Amber else C.Dim, fontSize = 11.sp)
                }
                Btn("Choose audio interface…", C.Accent) { onDevice() }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
}

/** Choose the module for a block of the mic chain. */
@Composable
private fun MicSlotPicker(rig: Rig, slot: Int, onClose: () -> Unit) {
    val cur = rig.micSlot(slot)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Channel 2 · Block ${slot + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Choose the module for this block. Each module exists once; the reverb only fits block 4.", color = C.Dim, fontSize = 11.sp)
                (1..5).forEach { type ->
                    val tab = micTab(type); val ok = type != MicP.T_REV || slot == 3; val isCur = cur == type
                    val where = (0..3).firstOrNull { rig.micSlot(it) == type }
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(if (isCur) tab.color.copy(alpha = 0.12f) else C.PanelHi).border(1.dp, if (isCur) tab.color else C.Line, shape)
                            .clickable(enabled = ok) { rig.setMicSlot(slot, type); onClose() }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChainIcon(micIcon(type), if (ok) tab.color else tab.color.copy(alpha = 0.3f), 22.dp); Spacer(Modifier.width(10.dp))
                        Text(micLabel(type), color = if (ok) C.Text else C.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (isCur) "current" else if (!ok) "block 4 only" else if (where != null) "in block ${where + 1} (swap)" else "+",
                            color = if (ok) tab.color else C.Dim, fontSize = if (ok && !isCur && where == null) 16.sp else 10.sp, fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        dismissButton = if (cur != 0) { { TextButton(onClick = { rig.setMicSlot(slot, 0); onClose() }) { Text("Empty this block") } } } else null
    )
}
