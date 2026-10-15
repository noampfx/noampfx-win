package com.noamp.fx.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.*

enum class Tab(val label: String, val color: Color, val onId: Int?, val inNav: Boolean = true) {
    DASH("Dashboard", C.Accent, null), GATE("Gate", C.Green, P.GATE_ON), COMP("Comp", C.Amber, P.COMP_ON),
    OCT("Octave", C.Blue, P.OCT_ON, false), PITCH("Pitch", C.Pink, P.PITCH_ON), HARM("Harmony", C.Violet, P.HARM_ON, false), WAH("Wah", C.Amber, P.WAH_ON),
    DRIVE("Dist", C.Orange, P.DRV_ON),
    NAM("Amp", C.Red, P.NAM_ON), CAB("Cab", C.Orange, P.CAB_ON), EQ("EQ", C.Blue, P.EQ_ON), MOD("Mod", C.Violet, P.MOD_ON),
    DLY("Delay", C.Pink, P.DLY_ON), REV("Reverb", C.Accent, P.REV_ON), LIM("Limiter", C.Green, P.LIM_ON),
    MIC("Mic", C.Green, null, false)                      // channel 2 module page (opened by long press on a channel 2 block)
}

/** Desktop layout: sidebar (engine, modules, tools) | preset bar + signal chain + page, content centred with a readable maximum width. */
@Composable
fun NoAmpApp(rig: Rig, onPower: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.DASH) }
    var stage by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(0) }       // page of the home screen: 0 = pedal controller, 1 metronome, 2 record, 3 player, 4 looper, 6 mixer
    var pitchMode by remember { mutableStateOf(0) } // 0 = Pitch Shifter, 1 = Octave, 2 = Harmony
    // bank 0 = original block, 1..2 = extra (duplicate) block: the module screen then edits that bank's parameters
    val openTab: (Tab, Int) -> Unit = { t, bank ->
        rig.uiBank = bank
        when (t) {
            Tab.OCT -> { pitchMode = 1; tab = Tab.PITCH }
            Tab.HARM -> { pitchMode = 2; tab = Tab.PITCH }
            Tab.PITCH -> { pitchMode = 0; tab = Tab.PITCH }
            else -> tab = t
        }
    }
    val home: () -> Unit = { rig.uiBank = 0; tab = Tab.DASH; tool = 0 }
    BackHandler(enabled = (tab != Tab.DASH || tool != 0) && !stage && !settings) { home() }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        when {
            stage -> StageScreen(rig, onPower) { stage = false }
            settings -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 900.dp).fillMaxSize()) { SettingsScreen(rig, onPower) { settings = false } } }
            else -> Row(Modifier.fillMaxSize()) {
                Sidebar(rig, tab, tool, onPower, onStage = { stage = true }, onSettings = { settings = true },
                    onModule = { t -> openTab(t, 0); tool = 0 },
                    onTool = { i -> rig.uiBank = 0; tool = if (tab == Tab.DASH && tool == i) 0 else i; tab = Tab.DASH },
                    onHome = home)
                Box(Modifier.width(1.dp).fillMaxHeight().background(C.Line))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    SignalChainBar(rig, openTab, onStage = { stage = true }, onHome = home)      // preset bar + signal chain + meters: fixed above every page
                    val lt = rig.learnTarget
                    if (lt != null) {
                        Row(Modifier.fillMaxWidth().background(C.Amber.copy(alpha = 0.15f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("MIDI learn: move a pedal / knob or press a switch…", color = C.Amber, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Btn("Cancel", C.Amber) { rig.startLearn(lt) }
                        }
                    }
                    val maxW = if (tab == Tab.DASH && tool == 0) 680.dp else if (tab == Tab.DASH) 820.dp else 960.dp
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = maxW).fillMaxSize()) {
                            when (tab) {
                                Tab.DASH -> if (tool == 0) PedalController(rig) else ToolsScreen(rig, tool)
                                Tab.GATE -> GateScreen(rig); Tab.COMP -> CompScreen(rig); Tab.PITCH, Tab.OCT, Tab.HARM -> PitchScreen(rig, pitchMode) { pitchMode = it }
                                Tab.WAH -> WahScreen(rig); Tab.DRIVE -> DriveScreen(rig); Tab.NAM -> NamScreen(rig); Tab.CAB -> CabScreen(rig)
                                Tab.EQ -> EqScreen(rig); Tab.MOD -> ModScreen(rig); Tab.DLY -> DelayScreen(rig); Tab.REV -> ReverbScreen(rig); Tab.LIM -> LimiterScreen(rig)
                                Tab.MIC -> MicModuleScreen(rig)
                            }
                        }
                    }
                    if (!(tab == Tab.DASH && tool == 3)) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.widthIn(max = 820.dp)) { MiniPlayer(rig) { rig.uiBank = 0; tab = Tab.DASH; tool = 3 } }       // song controls on every page
                    }
                }
            }
        }
    }
    TunerDialog(rig)
}

@Composable
private fun Sidebar(rig: Rig, tab: Tab, tool: Int, onPower: () -> Unit, onStage: () -> Unit, onSettings: () -> Unit, onModule: (Tab) -> Unit, onTool: (Int) -> Unit, onHome: () -> Unit) {
    Column(Modifier.width(208.dp).fillMaxHeight().background(C.Panel)) {
        Image(asset("logo_banner.png"), contentDescription = "NoAmp FX", modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp, vertical = 6.dp).clickable { onHome() }, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
        // ---- engine
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(shape).background(if (rig.running) C.Green.copy(alpha = 0.12f) else C.PanelHi)
                .border(1.dp, if (rig.running) C.Green else C.Line, shape).pointerHoverIcon(PointerIcon.Hand).clickable { onPower() }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).border(2.dp, if (rig.running) C.Green else C.Off, CircleShape), contentAlignment = Alignment.Center) { PowerIcon(if (rig.running) C.Green else C.Dim, 18.dp) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(if (rig.running) "ENGINE ON" else "ENGINE OFF", color = if (rig.running) C.Green else C.Dim, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                if (rig.running) {
                    val cpu = rig.meters[8]
                    Text("CPU %.0f%% · %.0f ms".format(cpu, rig.meters[14]), color = if (cpu > 90f) C.Red else if (cpu > 70f) C.Amber else C.Dim, fontSize = 10.sp, fontFamily = Mono, maxLines = 1)
                } else Text("click to start", color = C.Dim, fontSize = 10.sp)
            }
        }
        if (rig.status.isNotEmpty() && !rig.running) Text(rig.status, color = C.Amber, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SideHeader("Rig")
            NavItem("Pedalboard", C.Accent, tab == Tab.DASH && tool == 0, null) { onHome() }
            Tab.entries.filter { it.inNav && it != Tab.DASH }.forEach { t ->
                val sel = tab == t || (t == Tab.PITCH && (tab == Tab.OCT || tab == Tab.HARM))
                NavItem(t.label, t.color, sel && tool == 0, t.onId?.let { rig.on(it) }) { onModule(t) }
            }
            SideHeader("Tools")
            NavItem("Looper", C.Green, tab == Tab.DASH && tool == 4, null) { onTool(4) }
            NavItem("Music player", C.Blue, tab == Tab.DASH && tool == 3, null) { onTool(3) }
            NavItem("Recorder", C.Red, tab == Tab.DASH && tool == 2, null) { onTool(2) }
            NavItem("Metronome", Color(0xFFFFD84D), tab == Tab.DASH && tool == 1, null) { onTool(1) }
            NavItem("Mixer", C.Accent, tab == Tab.DASH && tool == 6, null) { onTool(6) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NavItem("Tuner", C.Violet, rig.tunerOn, null) { rig.setTuner(!rig.tunerOn) }
            NavItem("Stage mode", C.Green, false, null) { onStage() }
            NavItem("Settings", Color.White, false, null) { onSettings() }
            Text("Ctrl+←/→ presets · Ctrl+E engine · Ctrl+T tuner · Esc back · F11 full screen", color = C.Off, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun SideHeader(text: String) {
    Text(text.uppercase(), color = C.Dim, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
}

/** One sidebar row; [led] = null for pages without an on/off switch, otherwise the module's bypass state. */
@Composable
private fun NavItem(label: String, color: Color, selected: Boolean, led: Boolean?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(shape).background(if (selected) color.copy(alpha = 0.16f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand).clickable { onClick() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) color else Color.Transparent))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) color else C.Text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
        if (led != null) Led(led, color)
    }
}

@Composable
fun LatencyReadout(rig: Rig) {
    val m = rig.meters
    val txt = if (rig.bufMs <= 0f) "Buffer: default for the selected mode" else "Buffer target: %d ms".format(rig.bufMs.toInt())
    Text(txt, color = C.Text, fontSize = 12.sp, fontFamily = Mono)
    if (rig.running) Text("Actual ≈ %.1f ms (in+out) · period %d frames".format(m[14], m[15].toInt()),
        color = C.Dim, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(top = 2.dp))
}

@Composable
fun XrunText(rig: Rig) {
    val x = rig.meters[9]
    if (x > 0) Text("LATE CALLBACKS ${x.toInt()}", color = C.Amber, fontSize = 10.sp, fontFamily = Mono)
}

@Composable
fun NameDialog(title: String, initial: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { TextField(value = t, onValueChange = { t = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(t) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun DevPicker(label: String, devs: List<Dev>, sel: Int, onSel: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.PanelHi).border(1.dp, C.Line, RoundedCornerShape(10.dp))
            .pointerHoverIcon(PointerIcon.Hand).clickable { open = true }.padding(10.dp)) {
            Text(label.uppercase(), color = C.Dim, fontSize = 9.sp, letterSpacing = 1.sp)
            Text(devs.firstOrNull { it.id == sel }?.name ?: "System default", color = C.Text, fontSize = 13.sp, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            devs.forEach { d -> DropdownMenuItem(text = { Text(d.name) }, onClick = { onSel(d.id); open = false }) }
        }
    }
}
