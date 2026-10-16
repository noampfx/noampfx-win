package com.noamp.fx.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.Dev
import com.noamp.fx.Rig

/** Driver choice (Windows audio / ASIO) and the settings of the chosen driver. */
@Composable
fun AudioDriverPanels(rig: Rig, onPower: () -> Unit) {
    Panel(title = "Audio Driver") {
        Segmented(listOf("Windows audio (WASAPI)", "ASIO"), rig.backend, C.Accent) { rig.setBackend(it) }
        Text(
            if (rig.backend == 1) "ASIO talks to your interface's own driver: lowest latency and the interface's real input / output channels. Most ASIO drivers can be used by one program at a time, so close your DAW first."
            else "Windows audio works with any sound device. Use Latency Mode \"Ultra\" below for exclusive (low latency) access.",
            color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
        )
    }
    if (rig.backend == 1) AsioPanel(rig, onPower) else WasapiPanel(rig, onPower)
}

@Composable
private fun WasapiPanel(rig: Rig, onPower: () -> Unit) {
    Panel(title = "Audio Interface") {
        DevPicker("Input", rig.inDevs, rig.selIn) { rig.pickIn(it) }
        Spacer(Modifier.height(8.dp))
        DevPicker("Output", rig.outDevs, rig.selOut) { rig.pickOut(it) }
        Spacer(Modifier.height(12.dp))
        Text("SAMPLE RATE", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(44100, 48000, 96000).forEach { hz -> Chip(rateLabel(hz), rig.sampleRate == hz) { rig.setSampleRateHz(hz) } }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (rig.running) "Stop Engine" else "Start Engine", if (rig.running) C.Red else C.Green) { onPower() }
            Btn("Refresh", C.Dim) { rig.refreshDevices() }
        }
        if (rig.status.isNotEmpty()) Text(rig.status, color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        if (rig.inDevs.size <= 1) Text("No audio input detected. Connect your audio interface and press Refresh.", color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AsioPanel(rig: Rig, onPower: () -> Unit) {
    val info = rig.asioInfo
    Panel(title = "ASIO Device") {
        DevPicker("ASIO driver", rig.asioDrivers, rig.asioDrv) { id -> rig.asioDrivers.firstOrNull { it.id == id }?.let { rig.pickAsio(it) } }
        if (info != null) {
            Spacer(Modifier.height(8.dp))
            DevPicker("Input (guitar = this channel, microphone = the next one)", info.inputs.mapIndexed { i, n -> Dev(i, "${i + 1} · $n", false) }, rig.asioIn) { rig.setAsioIn(it) }
            Spacer(Modifier.height(8.dp))
            DevPicker("Output (stereo pair)", (0 until (info.outputs.size - 1).coerceAtLeast(1)).map { i ->
                Dev(i, "${i + 1}+${i + 2} · ${info.outputs.getOrElse(i) { "" }} / ${info.outputs.getOrElse(i + 1) { "" }}", false)
            }, rig.asioOut) { rig.setAsioOut(it) }
        }
        if (rig.asioMsg.isNotEmpty()) Text(rig.asioMsg, color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (rig.running) "Stop Engine" else "Start Engine", if (rig.running) C.Red else C.Green) { onPower() }
            Btn("Refresh drivers", C.Dim) { rig.refreshAsio() }
            Btn("Driver panel", C.Violet, enabled = rig.asioDrivers.isNotEmpty()) { rig.asioControlPanel() }
        }
        if (rig.status.isNotEmpty()) Text(rig.status, color = C.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
    if (info != null) {
        Panel(title = "Sample Rate") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                info.rates.forEach { hz -> Chip(rateLabel(hz), rig.sampleRate == hz) { rig.setSampleRateHz(hz) } }
            }
            Text("Higher rates lower the latency of a given buffer but raise the CPU load (NAM models run at 48 kHz internally and are resampled).", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Panel(title = "Buffer Size") {
            val choices = info.bufferChoices()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { f -> Chip("$f", rig.asioBuf == f) { rig.setAsioBuf(f) } }
            }
            val ms = rig.asioBuf * 1000f / rig.sampleRate
            Text("%d samples = %.1f ms per buffer".format(rig.asioBuf, ms), color = C.Text, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.padding(top = 8.dp))
            if (info.gran == 0) Text("This driver has a fixed buffer size. Change it in the driver panel.", color = C.Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            if (rig.running) Text("Round-trip latency reported by the driver ≈ %.1f ms".format(rig.meters[14]), color = C.Dim, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(top = 2.dp))
            XrunText(rig)
        }
    }
}

private fun rateLabel(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)
