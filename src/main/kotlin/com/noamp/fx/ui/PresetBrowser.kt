package com.noamp.fx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noamp.fx.Preset
import com.noamp.fx.Rig

/**
 * Preset browser (tap the preset name): All presets with search, Favorites (★), and Setlist = presets in a fixed order for a gig
 * (Prev / Next and MIDI program change follow the active setlist).
 */
@Composable
fun PresetBrowser(rig: Rig, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(if (rig.activeSetlist != null) 2 else 0) }      // 0 all · 1 favorites · 2 setlist
    var q by remember { mutableStateOf("") }
    var add by remember { mutableStateOf(false) }
    var newSet by remember { mutableStateOf(false) }
    var setMenu by remember { mutableStateOf(false) }
    val active = rig.setlists.firstOrNull { it.id == rig.activeSetlist }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(C.Bg).padding(top = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Presets", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${rig.presets.size}", color = C.Dim, fontSize = 13.sp)
                Text("✕", color = C.Text, fontSize = 22.sp, modifier = Modifier.clickable { onClose() }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
            }
            Box(Modifier.padding(horizontal = 12.dp)) { Segmented(listOf("All", "Favorites", "Setlist"), tab, C.Accent) { tab = it; add = false; q = "" } }
            if (tab != 2 || add) SearchBox(q) { q = it }

            if (tab != 2) {
                val items = rig.presets.withIndex().filter { (_, p) -> (tab == 0 || rig.isFav(p.id)) && (q.isBlank() || p.name.contains(q, true)) }
                if (items.isEmpty()) Text(if (tab == 1) "No favorites yet. Tap ☆ next to a preset." else "No presets found.", color = C.Dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(items) { _, iv ->
                        PresetRow(rig, iv.index, iv.value, onClick = { rig.applyPreset(iv.index); onClose() }) {
                            if (active != null && iv.value.id !in active.ids)
                                Text("＋ set", color = C.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { rig.addToSetlist(active.id, iv.value.id) }.padding(horizontal = 10.dp, vertical = 8.dp))
                        }
                    }
                }
            } else {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Btn((active?.name ?: "All presets") + " ▾", C.Accent) { setMenu = true }
                        DropdownMenu(expanded = setMenu, onDismissRequest = { setMenu = false }) {
                            DropdownMenuItem(text = { Text("All presets (no setlist)") }, onClick = { rig.selectSetlist(null); setMenu = false; add = false })
                            rig.setlists.forEach { s -> DropdownMenuItem(text = { Text(s.name + "  (${s.ids.size})") }, onClick = { rig.selectSetlist(s.id); setMenu = false; add = false }) }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("New setlist…") }, onClick = { setMenu = false; newSet = true })
                        }
                    }
                    Btn("Delete", C.Red, enabled = active != null) { active?.let { rig.deleteSetlist(it.id) } }
                }
                if (active == null) {
                    Text("Prev / Next use all presets in their saved order. Create a setlist to play your presets in a fixed order for a gig.", color = C.Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Box(Modifier.padding(horizontal = 12.dp)) { Btn("＋ New setlist", C.Green) { newSet = true } }
                } else {
                    Text("Prev / Next and MIDI program change follow this setlist.", color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { Btn(if (add) "Done adding" else "＋ Add presets", C.Green) { add = !add; q = "" } }
                    if (add) {
                        val items = rig.presets.withIndex().filter { (_, p) -> p.id !in active.ids && (q.isBlank() || p.name.contains(q, true)) }
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(items) { _, iv ->
                                PresetRow(rig, iv.index, iv.value, onClick = { rig.addToSetlist(active.id, iv.value.id) }) { Text("＋", color = C.Green, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
                            }
                        }
                    } else {
                        val ids = active.ids
                        if (ids.isEmpty()) Text("This setlist is empty. Tap ＋ Add presets.", color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(ids) { n, id ->
                                val idx = rig.presets.indexOfFirst { it.id == id }
                                if (idx >= 0) PresetRow(rig, idx, rig.presets[idx], number = n + 1, onClick = { rig.applyPreset(idx); onClose() }) {
                                    Text("▲", color = if (n > 0) C.Text else C.Off, fontSize = 15.sp, modifier = Modifier.clickable(enabled = n > 0) { rig.moveInSetlist(active.id, id, -1) }.padding(horizontal = 9.dp, vertical = 8.dp))
                                    Text("▼", color = if (n < ids.lastIndex) C.Text else C.Off, fontSize = 15.sp, modifier = Modifier.clickable(enabled = n < ids.lastIndex) { rig.moveInSetlist(active.id, id, 1) }.padding(horizontal = 9.dp, vertical = 8.dp))
                                    Text("✕", color = C.Dim, fontSize = 15.sp, modifier = Modifier.clickable { rig.removeFromSetlist(active.id, id) }.padding(horizontal = 12.dp, vertical = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (newSet) NewNameDialog("New setlist", "Setlist ${rig.setlists.size + 1}", onCancel = { newSet = false }) { rig.createSetlist(it); newSet = false; tab = 2 }
}

@Composable
private fun PresetRow(rig: Rig, idx: Int, p: Preset, number: Int = 0, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    val cur = rig.current == idx; val fav = rig.isFav(p.id)
    Row(Modifier.fillMaxWidth().background(if (cur) C.Accent.copy(alpha = 0.10f) else Color.Transparent).clickable { onClick() }.padding(start = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (number > 0) Text("$number", color = C.Dim, fontSize = 12.sp, modifier = Modifier.width(26.dp).padding(start = 8.dp))
        Text(if (fav) "★" else "☆", color = if (fav) C.Amber else C.Dim, fontSize = 20.sp, modifier = Modifier.clickable { rig.toggleFav(p.id) }.padding(horizontal = 10.dp, vertical = 8.dp))
        Text((if (cur) "● " else "") + p.name, color = if (cur) C.Accent else C.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2027)).padding(horizontal = 14.dp, vertical = 11.dp)) {
        if (value.isEmpty()) Text("Search presets", color = C.Dim, fontSize = 14.sp)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 14.sp), cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NewNameDialog(title: String, initial: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel, title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2027)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 15.sp), cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onOk(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
