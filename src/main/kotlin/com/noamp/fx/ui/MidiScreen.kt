package com.noamp.fx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.MidiMsg
import com.noamp.fx.P
import com.noamp.fx.Rig
import com.noamp.fx.T

@Composable
fun MidiScreen(rig: Rig) {
    val midi = rig.midi
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text("MIDI", color = C.Violet, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Text("USB & Bluetooth MIDI · MIDI Learn", color = C.Dim, fontSize = 11.sp)
        }

        // ---- devices
        Panel(title = "MIDI Devices") {
            if (midi.devices.isEmpty()) {
                Text("No MIDI device found. Plug in a USB controller, or pair a Bluetooth MIDI device in Windows, then press Rescan.", color = C.Dim, fontSize = 11.sp)
            }
            midi.devices.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Led(true, if (d.bluetooth) C.Blue else C.Green)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = C.Text, fontSize = 13.sp, maxLines = 1)
                        Text("CONNECTED", color = if (d.bluetooth) C.Blue else C.Green, fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                    Btn("Disconnect", C.Dim) { midi.disconnect(d.id) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Btn("Rescan MIDI devices", C.Violet) { midi.rescan() }
            if (midi.note.isNotEmpty()) Text(midi.note, color = C.Amber, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Tip: USB controllers, Bluetooth-LE MIDI devices paired in Windows and virtual MIDI ports all appear here automatically.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
            if (rig.midiLast.isNotEmpty()) Text("Last received: ${rig.midiLast}", color = C.Dim, fontSize = 10.sp, fontFamily = Mono, modifier = Modifier.padding(top = 8.dp))
        }

        // ---- preset prev / next
        Panel(title = "Preset · Previous / Next") {
            BindRow(rig, "Preset Previous", T.PREV, C.Accent, module = false)
            Sep()
            BindRow(rig, "Preset Next", T.NEXT, C.Accent, module = false)
        }

        // ---- tuner
        Panel(title = "Tuner") {
            BindRow(rig, "Tuner on/off", T.TUNER, C.Green, module = true)
        }

        // ---- scenes / looper / tools
        Panel(title = "Scenes · Looper · Tools") {
            val rows = listOf(
                "Scene A" to T.SCENE_A, "Scene B" to T.SCENE_B, "Scene C" to T.SCENE_C, "Scene D" to T.SCENE_D, "Scene E" to T.SCENE_E, "Next scene (A→B→C→D→E)" to T.SCENE_TOGGLE, "Next expression (Pitch → Wah → Master)" to T.NEXT_EXPR,
                "Looper (rec / play / overdub)" to T.LOOPER, "Looper stop" to T.LOOP_STOP, "Looper clear" to T.LOOP_CLEAR,
                "Music player play / pause" to T.PLAYER, "Metronome on/off" to T.METRO
            )
            rows.forEachIndexed { i, (n, tg) -> if (i > 0) Sep(); BindRow(rig, n, tg, C.Green, module = true) }
        }

        // ---- program change
        Panel(title = "Program Change") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Program Change selects presets directly", color = C.Text, fontSize = 12.sp)
                    Text("PC 0 = first preset of the active setlist (or all presets), PC 1 = second, and so on. Learned controls take priority.", color = C.Dim, fontSize = 10.sp)
                }
                PowerToggle(rig.pcDirect, C.Accent, if (rig.pcDirect) "ON" else "OFF") { rig.updatePcDirect(!rig.pcDirect) }
            }
            if (rig.pcDirect) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Channel", color = C.Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Btn("−", C.Dim) { rig.updatePcChannel(rig.pcChannel - 1) }
                    Text(if (rig.pcChannel < 0) "Omni" else "${rig.pcChannel + 1}", color = C.Text, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(horizontal = 12.dp))
                    Btn("+", C.Dim) { rig.updatePcChannel(rig.pcChannel + 1) }
                }
            }
        }

        // ---- clock + feedback
        Panel(title = "MIDI Clock & Feedback") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sync delay tempo to MIDI clock", color = C.Text, fontSize = 12.sp)
                    Text("Works with the Straight / Triplet / Dotted delay modes.", color = C.Dim, fontSize = 10.sp)
                }
                PowerToggle(rig.clockSync, C.Accent, if (rig.clockSync) "ON" else "OFF") { rig.updateClockSync(!rig.clockSync) }
            }
            Text(if (midi.clockBpm > 0f) "Clock: %.1f BPM".format(midi.clockBpm) else "Clock: none received", color = C.Dim, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LED feedback to controller", color = C.Text, fontSize = 12.sp)
                    Text("Sends CC/Note 127 (on) and 0 (off) for assigned switches so the controller LEDs follow the app. Needs a controller with MIDI input.", color = C.Dim, fontSize = 10.sp)
                }
                PowerToggle(rig.midiFeedback, C.Accent, if (rig.midiFeedback) "ON" else "OFF") { rig.updateMidiFeedback(!rig.midiFeedback) }
            }
        }

        // ---- user presets
        Panel(title = "User Presets") {
            if (rig.presets.isEmpty()) {
                Text("No presets yet. Create one with the ⋯ preset menu (Save as), then assign a MIDI control to it here.", color = C.Dim, fontSize = 11.sp)
            }
            rig.presets.forEachIndexed { i, p ->
                if (i > 0) Sep()
                BindRow(rig, "${i + 1}. ${p.name}", T.preset(p.id), C.Green, module = false)
            }
        }

        // ---- parameters (expression pedal)
        Panel(title = "Parameters · Expression Pedal") {
            Text("Press Learn, then move the pedal (CC) — or long-press any knob in the app. Calibrate the pedal and set the range from heel-down to toe-down.", color = C.Dim, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            BindRow(rig, "Expression pedal → selected destination", T.EXPR, C.Pink, module = false, continuous = true)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Destination: " + listOf("Pitch shifter", "Wah pedal", "Master output")[rig.expDest.coerceIn(0, 2)], color = C.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Btn("Next expression", C.Pink) { rig.nextExpression() }
            }
            Sep()
            val curated = listOf(
                P.PITCH_ST to "Pitch Shift", P.PITCH_MIX to "Pitch Mix", P.WAH_POS to "Wah Pedal", P.DRV_GAIN to "Distortion Gain", P.DRV_LEVEL to "Distortion Level",
                P.MASTER_OUT to "Master Output", P.MASTER_IN to "Master Input",
                P.DLY_MIX to "Delay Mix", P.DLY_FB to "Delay Feedback", P.REV_MIX to "Reverb Mix"
            )
            val have = curated.map { it.first }.toSet()
            val learned = rig.binds.mapNotNull { b -> if (b.target.startsWith("par:")) b.target.removePrefix("par:").toIntOrNull() else null }
                .filter { it !in have }.map { it to (P.moduleOf(it) + " " + P.def(it).name) }
            val pars = curated + learned
            pars.forEachIndexed { i, (id, name) ->
                if (i > 0) Sep()
                ParamBindRow(rig, name, id, C.Pink)
            }
        }

        // ---- signal chain
        Panel(title = "Signal Chain") {
            val chain = Tab.entries.filter { it.onId != null }
            chain.forEachIndexed { i, t ->
                if (i > 0) Sep()
                BindRow(rig, t.label, T.mod(t.onId!!), t.color, module = true)
            }
        }
        Text(
            "How to use: press LEARN, then press a footswitch or move a knob on your controller. Program Change, Control Change (CC) and Note messages are supported.",
            color = C.Dim, fontSize = 10.sp
        )
    }
}

@Composable
private fun Sep() = Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(C.Line))

@Composable
private fun BindRow(rig: Rig, name: String, target: String, color: Color, module: Boolean, continuous: Boolean = false) {
    val b = rig.bindingFor(target)
    val learning = rig.learnTarget == target
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = C.Text, fontSize = 13.sp, maxLines = 1)
                Text(
                    when {
                        learning -> "Waiting for MIDI…"
                        b != null -> b.label()
                        else -> "Not assigned"
                    },
                    color = when { learning -> C.Amber; b != null -> color; else -> C.Dim },
                    fontSize = 11.sp, fontFamily = Mono
                )
            }
            Btn(if (learning) "Cancel" else "Learn", if (learning) C.Amber else color) { rig.startLearn(target) }
            if (b != null && !learning) {
                Spacer(Modifier.width(6.dp))
                Btn("✕", C.Dim) { rig.clearBind(target) }
            }
        }
        if (b != null && b.type == MidiMsg.CC && !learning && !continuous) {
            val label = if (module) { if (b.mode == 0) "Toggle on each press (value ≥ 64)" else "Follow value (≥ 64 = ON, < 64 = OFF)" }
                        else { if (b.mode == 0) "Momentary (value ≥ 64)" else "Latch (every CC message)" }
            Text(
                "Mode: $label ⇄", color = C.Dim, fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(C.PanelHi)
                    .border(1.dp, C.Line, RoundedCornerShape(8.dp)).clickable { rig.cycleBindMode(target) }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ParamBindRow(rig: Rig, name: String, id: Int, color: Color) {
    val target = T.par(id)
    val b = rig.bindingFor(target)
    val learning = rig.learnTarget == target
    val d = P.def(id)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = C.Text, fontSize = 13.sp, maxLines = 1)
                Text(
                    when { learning -> "Move the pedal / control…"; b != null -> b.label() + " · now " + d.fmt(rig.get(id)); else -> "Not assigned" },
                    color = when { learning -> C.Amber; b != null -> color; else -> C.Dim }, fontSize = 11.sp, fontFamily = Mono
                )
            }
            Btn(if (learning) "Cancel" else "Learn", if (learning) C.Amber else color) { rig.startLearn(target) }
            if (b != null && !learning) { Spacer(Modifier.width(6.dp)); Btn("✕", C.Dim) { rig.clearBind(target) } }
        }
        if (b != null && !learning) {
            Spacer(Modifier.height(4.dp))
            RangeRow("Heel down", b.lo, d.min, d.max, color) { rig.setBindRange(target, it, b.hi) }
            RangeRow("Toe down", b.hi, d.min, d.max, color) { rig.setBindRange(target, b.lo, it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${d.fmt(b.lo)} → ${d.fmt(b.hi)}", color = C.Dim, fontSize = 10.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
                Btn("Reverse", C.Dim) { rig.setBindRange(target, b.hi, b.lo) }
            }
            Spacer(Modifier.height(6.dp))
            if (rig.calibTarget == target) {
                Text("Move the pedal fully from heel to toe… min ${rig.calibMin.coerceAtMost(127)} · max ${rig.calibMax}", color = C.Amber, fontSize = 11.sp, fontFamily = Mono)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Btn("Done", C.Green) { rig.finishCalibrate(true) }
                    Btn("Cancel", C.Dim) { rig.finishCalibrate(false) }
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pedal ${b.ccLo}–${b.ccHi}", color = C.Dim, fontSize = 10.sp, fontFamily = Mono)
                    Btn("Calibrate", C.Amber) { rig.startCalibrate(target) }
                    if (b.ccLo != 0 || b.ccHi != 127) Btn("Reset", C.Dim) { rig.resetCalibration(target) }
                    Btn("Curve: " + listOf("Linear", "Slow start", "Fast start")[b.curve.coerceIn(0, 2)], C.Dim) { rig.cycleCurve(target) }
                }
            }
        }
    }
}

@Composable
private fun RangeRow(label: String, v: Float, min: Float, max: Float, color: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Dim, fontSize = 10.sp, modifier = Modifier.width(78.dp))
        Slider(
            value = v.coerceIn(min, max), onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = C.Line)
        )
    }
}
