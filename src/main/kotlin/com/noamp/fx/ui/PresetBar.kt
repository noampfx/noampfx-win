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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.*

private val BarBg = Color(0xFF050505)
private val KeyBg = Color(0xFF17181B)

/**
 * Preset bar above the signal chain:  [home][ ⋯ ][ ⌃ ⌄ ]  Preset name ...  (scene) (tuner) (edit)
 * ⋯ = preset menu (Save, Save as, Edit/rename, Delete, Export, Import, Stage mode); ⌃ ⌄ = previous / next preset;
 * scene = scene A-E popup (the letter in the bag shows the active scene); tuner; pencil = chain edit mode.
 */
@Composable
fun PresetBar(rig: Rig, edit: Boolean, onEdit: () -> Unit, onStage: () -> Unit, onHome: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var sceneMenu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf<String?>(null) }
    var browser by remember { mutableStateOf(false) }
    var expCur by remember { mutableStateOf(false) }
    val exportL = rememberSaveFile("Export presets", "zip") { rig.exportBundle(it, expCur) }
    val importL = rememberOpenFile("Import presets", "zip") { rig.importBundle(it) }
    val has = rig.hasPreset()
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BarBg).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            // ---- home -> dashboard
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(KeyBg).clickable { onHome() }, contentAlignment = Alignment.Center) { HomeIcon(Color(0xFFD0D2D6), 20.dp) }
            Spacer(Modifier.width(2.dp))
            // ---- ⋯ preset menu
            Box {
                Box(Modifier.size(width = 38.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(KeyBg).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(width = 20.dp, height = 6.dp)) { for (i in 0..2) drawCircle(Color(0xFFD0D2D6), 2.6.dp.toPx(), Offset(size.width * (0.12f + 0.38f * i), size.height / 2f)) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Browse presets…") }, onClick = { menu = false; browser = true })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Save") }, onClick = { menu = false; if (!rig.save()) dlg = "saveas" })
                    DropdownMenuItem(text = { Text("Save as…") }, onClick = { menu = false; dlg = "saveas" })
                    DropdownMenuItem(text = { Text("Edit (rename)…") }, enabled = has, onClick = { menu = false; dlg = "rename" })
                    DropdownMenuItem(text = { Text("Delete…") }, enabled = has, onClick = { menu = false; dlg = "delete" })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Export…") }, enabled = rig.presets.isNotEmpty(), onClick = { menu = false; dlg = "export" })
                    DropdownMenuItem(text = { Text("Import…") }, onClick = { menu = false; importL() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Stage mode") }, onClick = { menu = false; onStage() })
                    DropdownMenuItem(text = { Text("Setlist…") }, onClick = { menu = false; dlg = "setlist" })
                }
            }
            Spacer(Modifier.width(2.dp))
            // ---- previous / next preset
            Column(Modifier.width(38.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                val en = rig.presets.isNotEmpty()
                Box(Modifier.fillMaxWidth().height(17.5.dp).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 2.dp, bottomEnd = 2.dp)).background(KeyBg).clickable(enabled = en) { rig.prev() }, contentAlignment = Alignment.Center) { Chevron(true, if (en) Color(0xFFD0D2D6) else C.Off) }
                Box(Modifier.fillMaxWidth().height(17.5.dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 8.dp, bottomEnd = 8.dp)).background(KeyBg).clickable(enabled = en) { rig.next() }, contentAlignment = Alignment.Center) { Chevron(false, if (en) Color(0xFFD0D2D6) else C.Off) }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                (if (rig.edited) "● " else "") + rig.presetName, color = if (rig.edited) C.Amber else Color(0xFFE6E7EA), fontSize = 18.sp, fontWeight = FontWeight.Normal,
                maxLines = 1, modifier = Modifier.weight(1f).clickable { browser = true }
            )
            // ---- scene (bag icon with the active scene letter) · tuner · edit (pencil): round icon buttons
            Box {
                RoundIconBtn(false, Color(0xFFE6E7EA), { sceneMenu = true }) { SceneIcon("ABCDE"[rig.scene.coerceIn(0, 4)].toString()) }
                DropdownMenu(expanded = sceneMenu, onDismissRequest = { sceneMenu = false }) {
                    "ABCDE".forEachIndexed { i, ch ->
                        DropdownMenuItem(
                            text = { Text("Scene $ch" + if (i == rig.scene) "  ✓" else "", color = if (i == rig.scene) C.Accent else C.Text, fontWeight = if (i == rig.scene) FontWeight.Bold else FontWeight.Normal) },
                            enabled = has, onClick = { rig.selectScene(i); sceneMenu = false })
                    }
                    if (!has) Text("Save a preset first to use scenes", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
            RoundIconBtn(rig.tunerOn, C.Green, { rig.setTuner(!rig.tunerOn) }) { TunerIcon(if (rig.tunerOn) C.Green else Color(0xFFE6E7EA), 17.dp) }
            Spacer(Modifier.width(6.dp))
            RoundIconBtn(edit, C.Accent, { onEdit() }) { PencilIcon(if (edit) C.Accent else Color(0xFFE6E7EA)) }
            Spacer(Modifier.width(4.dp))
        }
        if (rig.status.isNotEmpty()) Text(rig.status, color = C.Amber, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(start = 6.dp, top = 3.dp).clickable { rig.status = "" })
    }
    if (browser) PresetBrowser(rig) { browser = false }
    when (dlg) {
        "saveas" -> NameDialog("Save as new preset", if (has) rig.presetName else "Preset ${rig.presets.size + 1}", { rig.saveAs(it); dlg = null }) { dlg = null }
        "rename" -> NameDialog("Rename preset", rig.presetName, { rig.rename(it); dlg = null }) { dlg = null }
        "export" -> AlertDialog(onDismissRequest = { dlg = null }, title = { Text("Export presets") },
            text = { Text("The .zip includes the NAM models and IRs the presets use, so you can restore or share them.") },
            confirmButton = { TextButton(onClick = { expCur = false; dlg = null; exportL("NoAmpFX-presets.zip") }) { Text("All presets") } },
            dismissButton = { TextButton(enabled = has, onClick = { expCur = true; dlg = null; exportL("NoAmpFX-" + rig.presetName.replace(Regex("[^A-Za-z0-9 _-]"), "") + ".zip") }) { Text("Current only") } })
        "setlist" -> SetlistDialog(rig) { dlg = null }
        "delete" -> AlertDialog(onDismissRequest = { dlg = null }, title = { Text("Delete preset?") }, text = { Text(rig.presetName) },
            confirmButton = { TextButton(onClick = { rig.delete(); dlg = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Chevron(up: Boolean, color: Color) {
    Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
        val p = Path(); val w = size.width; val h = size.height
        if (up) { p.moveTo(0.05f * w, 0.95f * h); p.lineTo(0.5f * w, 0.1f * h); p.lineTo(0.95f * w, 0.95f * h) }
        else { p.moveTo(0.05f * w, 0.05f * h); p.lineTo(0.5f * w, 0.9f * h); p.lineTo(0.95f * w, 0.05f * h) }
        drawPath(p, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Round outlined icon button (like the preset bar buttons of the NoAmp FX design). */
@Composable
private fun RoundIconBtn(active: Boolean, activeColor: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (active) activeColor.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.5.dp, if (active) activeColor else Color(0xFFE6E7EA), CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Scene icon: a bag with a handle; [letter] (the active scene) is written inside. */
@Composable
private fun SceneIcon(letter: String) {
    val c = Color(0xFFF2F3F5)
    Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val st = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(c, Offset(0.08f * w, 0.30f * h), Size(0.84f * w, 0.62f * h), CornerRadius(0.12f * w), style = st)
            val p = Path(); p.moveTo(0.32f * w, 0.30f * h); p.lineTo(0.32f * w, 0.14f * h); p.lineTo(0.68f * w, 0.14f * h); p.lineTo(0.68f * w, 0.30f * h)
            drawPath(p, c, style = st)
        }
        Text(letter, color = C.Accent, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Pencil icon (edit). */
@Composable
private fun PencilIcon(color: Color) {
    Canvas(Modifier.size(17.dp)) {
        val w = size.width; val st = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply { moveTo(0.14f * w, 0.86f * w); lineTo(0.24f * w, 0.58f * w); lineTo(0.62f * w, 0.20f * w); lineTo(0.80f * w, 0.38f * w); lineTo(0.42f * w, 0.76f * w); close() }
        drawPath(body, color, style = st)
        drawLine(color, Offset(0.52f * w, 0.30f * w), Offset(0.70f * w, 0.48f * w), 1.6.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(0.24f * w, 0.58f * w), Offset(0.42f * w, 0.76f * w), 1.6.dp.toPx(), StrokeCap.Round)
    }
}


/** Setlist manager (moved from the Dashboard into the preset menu). */
@Composable
private fun SetlistDialog(rig: Rig, onClose: () -> Unit) {
    var dlg by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Setlist") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All presets", rig.activeSetlist == null) { rig.selectSetlist(null) }
                rig.setlists.forEach { sl -> Chip(sl.name, rig.activeSetlist == sl.id) { rig.selectSetlist(sl.id) } }
            }
            val sl = rig.setlists.firstOrNull { it.id == rig.activeSetlist }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("New", C.Green) { dlg = "newset" }
                Btn("Rename", C.Amber, enabled = sl != null) { dlg = "renameset" }
                Btn("Delete", C.Red, enabled = sl != null) { dlg = "deleteset" }
            }
            if (sl != null) {
                Spacer(Modifier.height(8.dp))
                if (sl.ids.isEmpty()) Text("Empty setlist. Select a preset, then tap “Add current preset”.", color = C.Dim, fontSize = 11.sp)
                sl.ids.forEachIndexed { i, id ->
                    val idx = rig.presets.indexOfFirst { it.id == id }
                    if (idx >= 0) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", color = C.Dim, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.width(26.dp))
                        Text(rig.presets[idx].name, color = if (idx == rig.current) C.Accent else C.Text, fontSize = 13.sp, maxLines = 1,
                            modifier = Modifier.weight(1f).clickable { rig.applyPreset(idx) })
                        Btn("▲", C.Dim, enabled = i > 0) { rig.moveInSetlist(sl.id, id, -1) }
                        Spacer(Modifier.width(4.dp))
                        Btn("▼", C.Dim, enabled = i < sl.ids.size - 1) { rig.moveInSetlist(sl.id, id, 1) }
                        Spacer(Modifier.width(4.dp))
                        Btn("✕", C.Red) { rig.removeFromSetlist(sl.id, id) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Btn("Add current preset", C.Accent, enabled = rig.hasPreset() && rig.presets[rig.current].id !in sl.ids) { rig.addToSetlist(sl.id, rig.presets[rig.current].id) }
            }
            Text("Preset Previous/Next (buttons, MIDI and Stage Mode) follow the selected setlist.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        } },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
    when (dlg) {
        "newset" -> NameDialog("New setlist", "Setlist ${rig.setlists.size + 1}", { rig.createSetlist(it); dlg = null }) { dlg = null }
        "renameset" -> NameDialog("Rename setlist", rig.setlists.firstOrNull { it.id == rig.activeSetlist }?.name ?: "", { n -> rig.activeSetlist?.let { rig.renameSetlist(it, n) }; dlg = null }) { dlg = null }
        "deleteset" -> AlertDialog(onDismissRequest = { dlg = null }, title = { Text("Delete setlist?") }, text = { Text(rig.setlists.firstOrNull { it.id == rig.activeSetlist }?.name ?: "") },
            confirmButton = { TextButton(onClick = { rig.activeSetlist?.let { rig.deleteSetlist(it) }; dlg = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancel") } })
    }
}
