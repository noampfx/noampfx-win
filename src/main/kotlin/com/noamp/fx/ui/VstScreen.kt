package com.noamp.fx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.Rig

/** Settings → VST3: one plug-in slot in the signal path. */
@Composable
fun VstScreen(rig: Rig) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!rig.vstCompiled) {
            Panel(title = "VST3") {
                Text("This build has no VST3 support.", color = C.Amber, fontSize = 13.sp)
                Text("Download the VST3 SDK into third_party/vst3sdk and rebuild (steps in README_WINDOWS.md).", color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            return
        }
        Panel(title = "Plug-in Slot") {
            if (rig.vstName.isEmpty()) {
                Text("No plug-in loaded. Pick one from the list below.", color = C.Dim, fontSize = 12.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rig.vstName, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (rig.vstLatency > 0) "Adds ${rig.vstLatency} samples of latency" else "No added latency", color = C.Dim, fontSize = 10.sp)
                    }
                    PowerToggle(rig.vstOn, C.Green) { rig.setVstOn(!rig.vstOn) }
                }
                Spacer(Modifier.height(10.dp))
                Text("POSITION", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("After the chain (stereo)", "Before the chain (mono)"), rig.vstPos, C.Accent) { rig.setVstPos(it) }
                Text(
                    if (rig.vstPos == 0) "Best for reverbs, delays, EQ, exciters, mastering tools: the plug-in hears the finished stereo sound, before looper and limiter."
                    else "Best for amp sims, pedals, noise gates: the plug-in hears the raw guitar before NoAmp's own effects (processed as mono).",
                    color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (rig.vstEditor) "Show editor" else "Open editor", C.Accent) { rig.openVstEditor() }
                    if (rig.vstEditor) Btn("Close editor", C.Dim) { rig.closeVstEditor() }
                    Btn("Unload", C.Red) { rig.unloadVst() }
                }
            }
            if (rig.vstMsg.isNotEmpty()) Text(rig.vstMsg, color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Panel(title = "Installed VST3 Plug-ins") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (rig.vstScanning) "Scanning…" else "Rescan", C.Accent, enabled = !rig.vstScanning) { rig.scanVst() }
                Btn("Add folder…", C.Dim) { chooseFolder("Folder with .vst3 plug-ins")?.let { rig.addVstDir(it.path) } }
            }
            Spacer(Modifier.height(8.dp))
            if (rig.vstList.isEmpty() && !rig.vstScanning) Text("No .vst3 files found in the standard folder (C:\\Program Files\\Common Files\\VST3). Use \"Add folder…\" for other locations.", color = C.Dim, fontSize = 11.sp)
            rig.vstList.forEach { e ->
                val cur = e.path == rig.vstPath && rig.vstName.isNotEmpty()
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Led(cur, C.Green); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, color = if (cur) C.Green else C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.path, color = C.Off, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Btn(if (cur) "Reload" else "Load", if (cur) C.Dim else C.Green) { rig.loadVst(e.path) }
                }
            }
            Text("Only 64-bit VST3 effects are supported. Plug-ins run inside NoAmp FX, so a faulty plug-in can crash the app. The plug-in's settings are saved automatically and restored at the next start.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
