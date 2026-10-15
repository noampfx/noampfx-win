package com.noamp.fx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.Rig

/** Compact player above the bottom menu (visible on every page while a song is loaded): cover, title, previous / play / next and the seekbar. */
@Composable
fun MiniPlayer(rig: Rig, onOpen: () -> Unit) {
    val t = rig.tools
    if (t.trackName == null) return
    var drag by remember { mutableStateOf<Float?>(null) }
    val title = t.trackTitle ?: t.trackName ?: ""
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(shape).background(Color(0xFF14181E)).border(1.dp, C.Line, shape).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(C.PanelHi).clickable { onOpen() }, contentAlignment = Alignment.Center) {
                val art = t.trackArt
                if (art != null) Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("♪", color = C.Dim, fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).clickable { onOpen() }) {
                Text(title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.trackArtist ?: "%d:%02d / %d:%02d".format(t.trackPos.toInt() / 60, t.trackPos.toInt() % 60, t.trackDur.toInt() / 60, t.trackDur.toInt() % 60), color = C.Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TransportBtn(3, C.Text, 18.dp) { t.prev() }
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (rig.running) C.Green else C.Off).clickable(enabled = rig.running) { t.togglePlay() },
                contentAlignment = Alignment.Center
            ) { Canvas(Modifier.size(16.dp)) { drawTransport(if (t.playing) 1 else 0, C.Bg) } }
            TransportBtn(2, C.Text, 18.dp) { t.next() }
        }
        Slider(
            value = drag ?: t.trackPos.toFloat(), onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { t.seek(it.toDouble()) }; drag = null },
            valueRange = 0f..t.trackDur.toFloat().coerceAtLeast(1f), modifier = Modifier.fillMaxWidth().height(22.dp),
            colors = SliderDefaults.colors(thumbColor = C.Green, activeTrackColor = C.Green, inactiveTrackColor = C.Line)
        )
    }
}
