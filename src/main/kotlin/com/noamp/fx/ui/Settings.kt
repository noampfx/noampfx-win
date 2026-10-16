package com.noamp.fx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.Rig

/** Settings: Audio interface + latency mode, and MIDI. Opened from the gear button at the top right. */
@Composable
fun SettingsScreen(rig: Rig, onPower: () -> Unit, startPage: Int = 0, onClose: () -> Unit) {
    BackHandler { onClose() }
    var page by remember { mutableIntStateOf(startPage) }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Row(Modifier.fillMaxWidth().background(C.Panel).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GearIcon(C.Accent, 22.dp)
            Spacer(Modifier.width(10.dp))
            Text("Settings", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Btn("Done", C.Accent) { onClose() }
        }
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { Segmented(listOf("Audio", "MIDI", "VST3", "About"), page, C.Accent) { page = it } }
        Box(Modifier.weight(1f)) { when (page) { 0 -> AudioSettings(rig, onPower); 1 -> MidiScreen(rig); 2 -> VstScreen(rig); else -> AboutScreen(rig) } }
    }
}

@Composable
private fun AudioSettings(rig: Rig, onPower: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AudioDriverPanels(rig, onPower)
        // ---- Microphone on input 2
        Panel(title = "Microphone (Input 2)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mix mic into output", color = C.Text, fontSize = 12.sp)
                    Text("Guitar = input 1, microphone = input 2. The mic is added to the NoAmp FX output and is included in recordings.", color = C.Dim, fontSize = 10.sp)
                }
                PowerToggle(rig.micOn, C.Green, if (rig.micOn) "ON" else "OFF") { rig.enableMic(!rig.micOn) }
            }
            if (rig.micOn) {
                Spacer(Modifier.height(10.dp))
                MicSourcePicker(rig)
                Spacer(Modifier.height(6.dp))
                Text("MIC TO OUTPUT  ${dbText(rig.micDb)}", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
                Slider(
                    value = rig.micDb, onValueChange = { rig.setMicLevel(Math.round(it).toFloat()) }, valueRange = -50f..3f,
                    colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Line)
                )
                Text(micStatusText(rig), color = if (rig.running && !rig.micActive) C.Amber else C.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Tips: use headphones to avoid feedback. Set the interface's Direct Monitor to off/playback so you do not hear the mic twice. Condenser mics need 48V phantom power on the interface.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // ---- Pitch shifter latency
        Panel(title = "Pitch Shifter") {
            Segmented(listOf("Low latency", "High quality"), if (rig.pitchLowLat) 0 else 1, C.Accent) { rig.updatePitchLowLat(it == 0) }
            Text(
                if (rig.pitchLowLat) "Low latency: about 10 ms extra delay while the Pitch / Harmony block is on. Best for playing live."
                else "High quality: about 26 ms extra delay, smoother sound on low notes. Better for recording than for live playing.",
                color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
            )
            Text("Tip: with the block OFF there is no extra delay at all. For the lowest total latency also use Ultra mode and a small buffer below.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        // ---- Latency mode (Windows audio only: ASIO has its own buffer size above)
        if (rig.backend == 0) Panel(title = "Latency Mode") {
            Segmented(listOf("Ultra", "Low", "Balanced", "Safe"), rig.latMode, C.Accent) { rig.updateLatMode(it) }
            Text(
                when (rig.latMode) {
                    0 -> "Ultra: exclusive + low-latency. Lowest latency; needs a stable audio interface."
                    1 -> "Low: shared low-latency mode. More compatible, slightly higher latency."
                    2 -> "Balanced: no low-latency mode. Safe for phones that often xrun."
                    else -> "Safe: power saving, large buffer. Highest latency but most stable."
                }, color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
            Text("BUFFER", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0f to "AUTO", 2f to "2 ms", 4f to "4 ms", 6f to "6 ms", 10f to "10 ms", 20f to "20 ms", 40f to "40 ms").forEach { (v, l) ->
                    Chip(l, rig.bufMs == v) { rig.setBuf(v) }
                }
            }
            Slider(
                value = rig.bufMs, onValueChange = { rig.setBuf(Math.round(it).toFloat()) }, valueRange = 0f..40f,
                colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Line)
            )
            LatencyReadout(rig)
            XrunText(rig)
        }
    }
}
