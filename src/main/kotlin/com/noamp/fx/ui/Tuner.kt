package com.noamp.fx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noamp.fx.Rig
import kotlin.math.abs

/** Tuner pop-up. Opened by the TUNER button (top right) or by a MIDI-learned control; closing it turns the tuner off. */
@Composable
fun TunerDialog(rig: Rig) {
    if (!rig.tunerOn) return
    Dialog(onDismissRequest = { rig.setTuner(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f).clip(RoundedCornerShape(18.dp)).background(C.Panel)
                .border(1.dp, C.Line, RoundedCornerShape(18.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TUNER", color = C.Green, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                Text("A4", color = C.Dim, fontSize = 11.sp, fontFamily = Mono)
                Spacer(Modifier.width(6.dp))
                Btn("−", C.Dim) { rig.updateTunerRef(rig.tunerRef - 1) }
                Text("${rig.tunerRef} Hz", color = C.Text, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.padding(horizontal = 8.dp))
                Btn("+", C.Dim) { rig.updateTunerRef(rig.tunerRef + 1) }
                Spacer(Modifier.width(10.dp))
                Btn("✕", C.Red) { rig.setTuner(false) }
            }
            if (!rig.running) {
                Text("Turn on the engine (⏻ button) so the tuner can hear your guitar.", color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(vertical = 20.dp))
            } else {
                val has = rig.tunerHas; val c = rig.tunerCents
                val col = when { !has -> C.Off; abs(c) <= 5f -> C.Green; abs(c) <= 15f -> C.Amber; else -> C.Red }
                Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (has) rig.tunerNote else "—", color = col, fontSize = 64.sp, fontWeight = FontWeight.Black, fontFamily = Mono)
                    Text(
                        if (has) "%+.0f ct · %.1f Hz".format(c, rig.tunerHz) else "Pluck a string…",
                        color = if (has) C.Text else C.Dim, fontSize = 13.sp, fontFamily = Mono
                    )
                    Spacer(Modifier.height(14.dp))
                    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                        val w = size.width; val h = size.height
                        for (i in -5..5) {
                            val x = (i + 5) / 10f * w; val big = i == 0
                            drawLine(if (big) C.Green else C.Line, Offset(x, if (big) 0f else h * 0.25f), Offset(x, if (big) h else h * 0.75f), if (big) 3f else 2f)
                        }
                        drawRoundRect(C.Line, Offset(0f, h / 2 - 1.5f), Size(w, 3f), CornerRadius(2f, 2f))
                        if (has) {
                            val x = ((c.coerceIn(-50f, 50f) + 50f) / 100f) * w
                            drawLine(col, Offset(x, 2f), Offset(x, h - 2f), 6.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("♭ flat", color = C.Dim, fontSize = 10.sp); Text("♯ sharp", color = C.Dim, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mute output while tuning", color = C.Text, fontSize = 12.sp)
                    Text("Silences the speaker/headphones; the tuner keeps working.", color = C.Dim, fontSize = 10.sp)
                }
                PowerToggle(rig.tunerMute, C.Amber, if (rig.tunerMute) "MUTE" else "OPEN") { rig.updateTunerMute(!rig.tunerMute) }
            }
        }
    }
}
