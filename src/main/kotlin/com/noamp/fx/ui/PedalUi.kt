package com.noamp.fx.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.Haptics
import com.noamp.fx.Rig
import kotlin.math.roundToInt

private val CARD = Color(0xFF14171C)
private val CHIP = Color(0xFF0C0E11)
private val ORANGE = Color(0xFFFF9A1F)
private val GRAYRING = Color(0xFFD3D6DB)
private val RING_OFF = Color(0xFF666B73)       // foot switch ring / key border when not active
private val CHIP_OFF = Color(0xFF4B515A)
private val ICON_OFF = Color(0xFFB3B8BF)
private val YELLOW = Color(0xFFFFD43B)
private val WHITE = Color(0xFFF4F5F7)
private val TXT = Color(0xFFE9EAEC)
private val SCENE_COLORS = listOf(ORANGE, C.Green, C.Accent, C.Pink, C.Violet)

// ================================================================================================ pedal controller
/**
 * Home page: an on-screen pedal board.
 *  top group  : Prev · Next · Tuner · Tap (tap tempo for delay + metronome) · Mode
 *  second group: Scene A-E  -  or, after Mode, the effect stomps (Comp · Dist · Mod · Delay · Reverb)
 *  right      : EXP 1 expression pedal (virtual MIDI CC 11 on channel 16, assign it with MIDI learn)
 */
@Composable
fun PedalController(rig: Rig) {
    var stomp by remember { mutableStateOf(false) }          // Mode: false = scenes, true = effect stomps
    var tapDlg by remember { mutableStateOf(false) }         // long press on the Tap switch: choose its action
    var stompDlg by remember { mutableStateOf(-1) }          // long press on a stomp: choose its module
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF101317)).border(1.dp, C.Line, shape).padding(10.dp)) {
            Text("PEDAL CONTROLLER", color = C.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(276.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PedalGroup(Modifier.weight(1f)) {
                        PedalSlot("Prev", TXT, GRAYRING, lit = false, chipOn = false, icon = { SkipIcon(false, ICON_OFF) }) { rig.prev() }
                        PedalSlot("Next", TXT, GRAYRING, lit = false, chipOn = false, icon = { SkipIcon(true, ICON_OFF) }) { rig.next() }
                        PedalSlot("Tuner", C.Violet, C.Violet, lit = rig.tunerOn, chipOn = rig.tunerOn, icon = { TunerIcon(if (rig.tunerOn) C.Violet else ICON_OFF, 12.dp) }) { rig.setTuner(!rig.tunerOn) }
                        PedalSlot(if (rig.tapAction == 0) "Tap" else "Next Exp", TXT, ORANGE, lit = false, chipOn = false, small = rig.tapAction != 0, icon = { if (rig.tapAction == 0) TapIcon(ICON_OFF) else ExprIcon(ICON_OFF) }, onLong = { tapDlg = true }) { rig.pressTapSwitch() }
                        PedalSlot("Mode", C.Accent, C.Accent, lit = stomp, chipOn = stomp, icon = { ModeIcon(if (stomp) C.Accent else ICON_OFF) }) { stomp = !stomp }
                    }
                    PedalGroup(Modifier.weight(1f)) {
                        if (!stomp) {
                            for (i in 0..4) {
                                val col = SCENE_COLORS[i]; val sel = rig.scene == i && rig.hasPreset()
                                PedalSlot("Scene ${"ABCDE"[i]}", col, col, lit = sel, chipOn = sel, small = true, icon = { BagIcon(if (sel) col else ICON_OFF) }) { rig.selectScene(i) }
                            }
                        } else {
                            for (i in 0..4) {
                                val id = rig.stompIds[i]; val tab = Tab.entries.firstOrNull { it.onId == id } ?: Tab.COMP; val on = rig.onAbs(id)
                                PedalSlot(tab.label, tab.color, tab.color, lit = on, chipOn = on, small = true, icon = { Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) tab.color else tab.color.copy(alpha = 0.4f))) }, onLong = { stompDlg = i }) { rig.toggleAbs(id) }
                            }
                        }
                    }
                }
                ExpPedal(rig, Modifier.width(88.dp).fillMaxHeight())
            }
        }
    }
    if (tapDlg) TapActionDialog(rig) { tapDlg = false }
    if (stompDlg >= 0) StompAssignDialog(rig, stompDlg) { stompDlg = -1 }
}

/** Long press on the Tap switch: what the switch does. */
@Composable
private fun TapActionDialog(rig: Rig, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Tap switch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose the function of this pedal switch.", color = C.Dim, fontSize = 12.sp)
                listOf("Tap tempo" to "Sets the delay tempo and the metronome BPM", "Next expression" to "Pitch shifter → Wah pedal → Master output").forEachIndexed { i, (n, d) ->
                    val sel = rig.tapAction == i; val shape = RoundedCornerShape(10.dp)
                    Column(Modifier.fillMaxWidth().clip(shape).background(if (sel) ORANGE.copy(alpha = 0.14f) else C.PanelHi).border(1.dp, if (sel) ORANGE else C.Line, shape).clickable { rig.chooseTapAction(i); onClose() }.padding(12.dp)) {
                        Text(n + if (sel) "  ✓" else "", color = if (sel) ORANGE else C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(d, color = C.Dim, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

/** Long press on a stomp: choose which module block this switch turns on / off. */
@Composable
private fun StompAssignDialog(rig: Rig, index: Int, onClose: () -> Unit) {
    val cur = rig.stompIds[index]
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Stomp ${index + 1}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Choose the module block this switch controls.", color = C.Dim, fontSize = 12.sp)
                Tab.entries.filter { it.onId != null }.forEach { tab ->
                    val sel = tab.onId == cur; val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(if (sel) tab.color.copy(alpha = 0.14f) else C.PanelHi).border(1.dp, if (sel) tab.color else C.Line, shape).clickable { rig.assignStomp(index, tab.onId!!); onClose() }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(tab.color)); Spacer(Modifier.width(10.dp))
                        Text(tab.label, color = if (sel) tab.color else C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (sel) Text("✓", color = tab.color, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun PedalGroup(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(CARD).border(1.dp, C.Line, shape).padding(horizontal = 6.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/** One column of a group: a small labelled key on top and the round foot switch below it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.PedalSlot(label: String, labelColor: Color, ring: Color, lit: Boolean, chipOn: Boolean, small: Boolean = false, icon: @Composable () -> Unit, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val view = LocalView.current
    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(6.dp)
        Row(
            Modifier.fillMaxWidth().height(30.dp).clip(shape).background(if (chipOn) ring.copy(alpha = 0.16f) else CHIP).border(1.dp, if (chipOn) ring else CHIP_OFF, shape).combinedClickable(onClick = { Haptics.tick(view); onClick() }, onLongClick = onLong?.let { f -> { Haptics.heavy(view); f() } }).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            icon(); Spacer(Modifier.width(2.dp))
            Text(label, color = if (chipOn) labelColor else TXT, fontSize = if (small) 8.sp else 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { FootSwitch(ring, lit, onLong, onClick) }
    }
}

/** Round foot switch: coloured ring (glows when active), brushed metal cap, centre dot. */
@Composable
private fun FootSwitch(ring: Color, lit: Boolean, onLong: (() -> Unit)?, onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val view = LocalView.current
    val sc by animateFloatAsState(if (down) 0.92f else 1f, spring(stiffness = 700f), label = "foot")
    Box(
        Modifier.fillMaxWidth(0.96f).aspectRatio(1f).graphicsLayer { scaleX = sc; scaleY = sc }
            .pointerInput(Unit) { detectTapGestures(onPress = { down = true; Haptics.tick(view); tryAwaitRelease(); down = false }, onLongPress = onLong?.let { f -> { _: Offset -> Haptics.heavy(view); f() } }, onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f; val c = center
            val shown = if (lit || down) ring else RING_OFF
            if (lit) drawCircle(ring.copy(alpha = 0.28f), r * 1.0f, c)
            drawCircle(Color(0xFF08090B), r * 0.92f, c)
            drawCircle(shown, r * 0.84f, c, style = Stroke(r * 0.16f))
            val cap = r * (if (down) 0.62f else 0.66f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF9A9DA3), Color(0xFF42454A)), center = Offset(c.x - cap * 0.25f, c.y - cap * 0.3f), radius = cap * 1.4f), cap, c)
            drawCircle(Color(0xFF17181B), r * 0.085f, c)
        }
    }
}

// ================================================================================================ EXP 1
/** On-screen expression pedal: drag (or tap) on the pad. Top = heel (0 %), bottom = toe (100 %). */
@Composable
private fun ExpPedal(rig: Rig, modifier: Modifier) {
    val v = rig.expValue; val rev = rig.expReverse
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.clip(shape).background(Color(0xFF0C0E11)).border(2.dp, C.Line, shape).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp)).background(CHIP).border(1.dp, C.Line, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text(rig.expName(), color = TXT, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) { Box(Modifier.fillMaxHeight().fillMaxWidth(v.coerceIn(0.001f, 1f)).background(C.Accent)) }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${(v * 100).roundToInt()}%", color = C.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(if (rev) "▲ REV" else "▼ REV", color = if (rev) C.Accent else C.Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, if (rev) C.Accent else C.Line, RoundedCornerShape(4.dp)).clickable { rig.toggleExpReverse() }.padding(horizontal = 4.dp, vertical = 1.dp))
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(rev) { detectTapGestures { val f = (it.y / size.height).coerceIn(0f, 1f); rig.setExp(if (rev) 1f - f else f) } }
                .pointerInput(rev) { detectDragGestures(onDragStart = { val f = (it.y / size.height).coerceIn(0f, 1f); rig.setExp(if (rev) 1f - f else f) }) { change, _ -> change.consume(); val f = (change.position.y / size.height).coerceIn(0f, 1f); rig.setExp(if (rev) 1f - f else f) } }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cols = 6; val rows = (size.height / 11.dp.toPx()).toInt().coerceAtLeast(6)
                val dx = size.width / cols; val dy = size.height / rows; val ly = (if (rev) 1f - v else v) * size.height
                for (r in 0 until rows) for (cc in 0 until cols) {
                    val y = dy * (r + 0.5f)
                    drawCircle(if (if (rev) y >= ly else y <= ly) C.Accent.copy(alpha = 0.55f) else Color(0xFF3A414B), 1.4.dp.toPx(), Offset(dx * (cc + 0.5f), y))
                }
                drawLine(Color(0xFFB9BDC4), Offset(0f, ly), Offset(size.width, ly), 2.dp.toPx())
            }
        }
        Text(if (rev) "HEEL ▲" else "TOE ▲", color = C.Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

// ================================================================================================ bottom menu
/** Bottom menu: Looper · Music · Record · Metronome · Mixer · Setting.  [selected] = tool page id (4 looper, 3 music, 2 record, 1 metronome, 6 mixer), 0 = none. */
@Composable
fun BottomMenu(selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(shape).background(Color(0xFF111418)).border(1.dp, C.Line, shape).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        MenuTile(4, "Looper", C.Green, selected == 4, onSelect) { LoopGlyph(C.Green) }
        MenuTile(3, "Music", C.Blue, selected == 3, onSelect) { NoteGlyph(C.Blue) }
        MenuTile(2, "Record", C.Red, selected == 2, onSelect) { RecordGlyph(C.Red) }
        MenuTile(1, "Metronome", YELLOW, selected == 1, onSelect) { MetronomeGlyph(YELLOW) }
        MenuTile(6, "Mixer", C.Accent, selected == 6, onSelect) { MixerGlyph(C.Accent) }
        MenuTile(5, "Setting", WHITE, selected == 5, onSelect) { GearIcon(WHITE, 26.dp) }
    }
}

@Composable
private fun MenuTile(id: Int, label: String, color: Color, selected: Boolean, onSelect: (Int) -> Unit, glyph: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val view = LocalView.current
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { Haptics.tick(view); onSelect(id) }.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(shape).background(if (selected) color.copy(alpha = 0.20f) else Color(0xFF16191E)).border(2.dp, if (selected) color else Color(0xFF59606A), shape), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) Color.White else Color(0xFF8B929B), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

// ================================================================================================ icons
@Composable private fun SkipIcon(next: Boolean, color: Color) {
    Canvas(Modifier.size(11.dp)) {
        val w = size.width
        if (next) { drawPath(Path().apply { moveTo(.05f * w, .12f * w); lineTo(.70f * w, .5f * w); lineTo(.05f * w, .88f * w); close() }, color); drawRect(color, Offset(.78f * w, .12f * w), Size(.16f * w, .76f * w)) }
        else { drawPath(Path().apply { moveTo(.95f * w, .12f * w); lineTo(.30f * w, .5f * w); lineTo(.95f * w, .88f * w); close() }, color); drawRect(color, Offset(.06f * w, .12f * w), Size(.16f * w, .76f * w)) }
    }
}

@Composable private fun TapIcon(color: Color) {
    Canvas(Modifier.size(12.dp)) { val w = size.width; drawCircle(color, w * .42f, center, style = Stroke(1.4.dp.toPx())); drawCircle(color, w * .17f, center) }
}

@Composable private fun ModeIcon(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width; val sw = 1.4.dp.toPx()
        drawLine(color, Offset(.08f * w, .32f * w), Offset(.88f * w, .32f * w), sw, StrokeCap.Round); drawPath(Path().apply { moveTo(.72f * w, .14f * w); lineTo(.92f * w, .32f * w); lineTo(.72f * w, .50f * w) }, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(.92f * w, .70f * w), Offset(.12f * w, .70f * w), sw, StrokeCap.Round); drawPath(Path().apply { moveTo(.28f * w, .52f * w); lineTo(.08f * w, .70f * w); lineTo(.28f * w, .88f * w) }, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable private fun BagIcon(color: Color) {
    Canvas(Modifier.size(11.dp)) {
        val w = size.width; val st = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(.08f * w, .34f * w), Size(.84f * w, .58f * w), CornerRadius(.1f * w), style = st)
        drawPath(Path().apply { moveTo(.32f * w, .34f * w); lineTo(.32f * w, .14f * w); lineTo(.68f * w, .14f * w); lineTo(.68f * w, .34f * w) }, color, style = st)
    }
}

@Composable fun MetronomeGlyph(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width; val st = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(.30f * w, .90f * w); lineTo(.70f * w, .90f * w); lineTo(.60f * w, .12f * w); lineTo(.40f * w, .12f * w); close() }, color, style = st)
        drawLine(color, Offset(.50f * w, .72f * w), Offset(.66f * w, .30f * w), 2.2.dp.toPx(), StrokeCap.Round)
        drawCircle(color, 2.4.dp.toPx(), Offset(.62f * w, .40f * w))
    }
}

@Composable fun RecordGlyph(color: Color) {
    Canvas(Modifier.size(28.dp)) { val w = size.width; drawCircle(color, w * .46f, center, style = Stroke(2.4.dp.toPx())); drawCircle(color, w * .26f, center) }
}

@Composable fun NoteGlyph(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        drawOval(color, Offset(.10f * w, .62f * w), Size(.30f * w, .24f * w)); drawOval(color, Offset(.56f * w, .52f * w), Size(.30f * w, .24f * w))
        drawLine(color, Offset(.37f * w, .72f * w), Offset(.37f * w, .16f * w), 2.6.dp.toPx(), StrokeCap.Round); drawLine(color, Offset(.83f * w, .62f * w), Offset(.83f * w, .06f * w), 2.6.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(.37f * w, .16f * w), Offset(.83f * w, .06f * w), 4.dp.toPx(), StrokeCap.Round)
    }
}

@Composable fun LoopGlyph(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width; val st = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round); val a = Size(.64f * w, .64f * w); val tl = Offset(.18f * w, .18f * w)
        drawArc(color, 200f, 140f, false, tl, a, style = st); drawArc(color, 20f, 140f, false, tl, a, style = st)
        drawPath(Path().apply { moveTo(.74f * w, .16f * w); lineTo(.80f * w, .36f * w); lineTo(.60f * w, .34f * w); close() }, color)
        drawPath(Path().apply { moveTo(.26f * w, .84f * w); lineTo(.20f * w, .64f * w); lineTo(.40f * w, .66f * w); close() }, color)
    }
}

@Composable private fun ExprIcon(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width; val st = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(.10f * w, .82f * w); lineTo(.62f * w, .56f * w); lineTo(.62f * w, .36f * w); lineTo(.10f * w, .62f * w); close() }, color, style = st)
        drawLine(color, Offset(.70f * w, .78f * w), Offset(.92f * w, .78f * w), 1.3.dp.toPx(), StrokeCap.Round); drawLine(color, Offset(.70f * w, .20f * w), Offset(.92f * w, .20f * w), 1.3.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(.81f * w, .26f * w), Offset(.81f * w, .72f * w), 1.3.dp.toPx(), StrokeCap.Round)
    }
}

@Composable fun MixerGlyph(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width; val ys = listOf(.62f, .34f, .52f)
        for (k in 0..2) {
            val x = w * (0.2f + 0.3f * k)
            drawLine(color, Offset(x, .10f * w), Offset(x, .90f * w), 2.dp.toPx(), StrokeCap.Round)
            drawRoundRect(color, Offset(x - .11f * w, ys[k] * w - .07f * w), Size(.22f * w, .14f * w), CornerRadius(2f, 2f))
        }
    }
}
