package com.noamp.fx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.noamp.fx.Clip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown on every launch until a valid license key for this device has been entered. */
@Composable
fun LicenseScreen(deviceCode: String, onActivate: (String) -> Boolean) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Activate NoAmp FX", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(
            "This app must be registered on every PC. Send the Device ID below to the developer, then paste the license key you receive.",
            color = C.Dim, fontSize = 12.sp
        )

        Text("DEVICE ID", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(
            deviceCode, color = C.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Panel).padding(14.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Copy ID") { Clip.copy(deviceCode) }
        }

        Text("LICENSE KEY", color = C.Dim, fontSize = 10.sp, letterSpacing = 1.sp)
        BasicTextField(
            value = key, onValueChange = { key = it; error = false },
            textStyle = TextStyle(color = C.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(C.Accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(10.dp)).background(C.PanelHi)
                .border(1.dp, if (error) C.Red else C.Line, RoundedCornerShape(10.dp)).padding(12.dp)
        )
        if (error) Text("Invalid license key for this device.", color = C.Red, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Paste") { key = Clip.paste(); error = false }
            Btn("Activate", C.Green, enabled = key.isNotBlank()) { error = !onActivate(key) }
        }
    }
}
