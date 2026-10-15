package com.noamp.fx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.License
import com.noamp.fx.Rig

/** Firmware (controller / engine protocol) version shown in About. */
private const val FIRMWARE_VERSION = "1.0"

@Composable
private fun InfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = C.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = C.Dim, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
    }
}

/** Settings > About: app identity, versions, license status, system info and credits. */
@Composable
fun AboutScreen(rig: Rig) {
    val ctx = LocalContext.current
    val info = remember { "2.4.0" to 24 }
    val deviceId = remember { License.deviceCode(ctx) }
    val licensed = remember { License.isLicensed(ctx) }
    val deviceModel = System.getProperty("os.name") + " " + System.getProperty("os.version")
    val android = "Java " + System.getProperty("java.version")
    val abi = System.getProperty("os.arch") ?: "-"
    var copied by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- identity
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(asset("logo_banner.png"), contentDescription = "NoAmp FX", modifier = Modifier.height(84.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.height(8.dp))
            Text("Guitar multi-effects & amp modeler", color = C.Dim, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Version ${info.first}", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
        }

        Panel(title = "Version") {
            InfoRow("App version", info.first)
            InfoRow("Build", info.second.toString())
            InfoRow("Firmware version", FIRMWARE_VERSION)
            InfoRow("Data folder", com.noamp.fx.AppDirs.root.path)
        }

        Panel(title = "License") {
            InfoRow("Status", if (licensed) "Registered" else "Not registered", if (licensed) C.Green else C.Red)
            InfoRow("Device ID", deviceId, C.Accent)
        }

        Panel(title = "System") {
            InfoRow("Windows", deviceModel)
            InfoRow("Runtime", android)
            InfoRow("CPU", abi)
            InfoRow("Sample rate", "${rig.sampleRate} Hz")
            InfoRow("Audio engine", if (rig.running) "Running" else "Stopped", if (rig.running) C.Green else C.Dim)
            Spacer(Modifier.height(8.dp))
            Btn(if (copied) "Copied" else "Copy PC info", C.Accent) {
                val txt = "NoAmp FX ${info.first} (build ${info.second})\nFirmware: $FIRMWARE_VERSION\nDevice ID: $deviceId\n$deviceModel · $android · $abi"
                com.noamp.fx.Clip.copy(txt)
                copied = true
            }
        }

        Panel(title = "Credits") {
            Text("Amp models use the Neural Amp Modeler format (.nam) via NeuralAmpModelerCore (MIT License).", color = C.Dim, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text("Audio I/O (WASAPI) and file decoding by miniaudio (public domain / MIT-0).", color = C.Dim, fontSize = 11.sp)
        }

        Text("© 2026 NoAmp FX. All rights reserved.", color = C.Dim, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
}
