package com.noamp.fx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.Rig

/** Full-screen, large-print performance view: big preset name, big Previous/Next, scene A/B. */
@Composable
fun StageScreen(rig: Rig, onPower: () -> Unit, onExit: () -> Unit) {
    BackHandler { onExit() }
    val nav = rig.navList(); val pos = nav.indexOf(rig.current)
    val setName = rig.setlists.firstOrNull { it.id == rig.activeSetlist }?.name ?: "All presets"
    Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("STAGE MODE", color = C.Green, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Text(setName, color = C.Dim, fontSize = 12.sp, maxLines = 1)
            }
            if (rig.midi.btConnected || rig.midi.usbConnected) {
                Led(true, if (rig.midi.btConnected) C.Blue else C.Green); Spacer(Modifier.width(4.dp))
                Text(if (rig.midi.btConnected) "BT MIDI" else "MIDI", color = if (rig.midi.btConnected) C.Blue else C.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
            }
            Btn(if (rig.running) "Engine ON" else "Engine OFF", if (rig.running) C.Green else C.Red) { onPower() }
            Spacer(Modifier.width(8.dp))
            Btn("TUNER", C.Green) { rig.setTuner(true) }
            Spacer(Modifier.width(8.dp))
            Btn("Exit", C.Dim) { onExit() }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (pos >= 0) "${pos + 1} / ${nav.size}" else "—", color = C.Dim, fontSize = 18.sp, fontFamily = Mono)
            Spacer(Modifier.height(8.dp))
            Text(rig.presetName, color = if (rig.edited) C.Amber else C.Text, fontSize = 46.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 3)
            val nx = rig.nextName()
            if (nx.isNotEmpty()) Text("NEXT: $nx", color = C.Dim, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
            if (rig.hasPreset()) {
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("A", "B", "C", "D", "E").forEachIndexed { i, l ->
                        val sel = rig.scene == i
                        Box(
                            Modifier.weight(1f).height(70.dp).clip(RoundedCornerShape(16.dp)).background(if (sel) C.Accent.copy(alpha = 0.2f) else C.PanelHi)
                                .border(2.dp, if (sel) C.Accent else C.Line, RoundedCornerShape(16.dp)).clickable { rig.selectScene(i) },
                            contentAlignment = Alignment.Center
                        ) { Text("SCENE $l", color = if (sel) C.Accent else C.Dim, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(0.8f)) { MeterBar({ rig.meters[1] }, "OUT") }
        }
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StageBtn("◀  PREV", Modifier.weight(1f)) { rig.prev() }
            StageBtn("NEXT  ▶", Modifier.weight(1f)) { rig.next() }
        }
    }
}

@Composable
private fun StageBtn(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(C.PanelHi).border(2.dp, C.Accent, RoundedCornerShape(24.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(text, color = C.Accent, fontSize = 30.sp, fontWeight = FontWeight.Black) }
}
