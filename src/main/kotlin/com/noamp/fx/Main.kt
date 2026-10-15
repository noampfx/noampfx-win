package com.noamp.fx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.noamp.fx.ui.*
import java.awt.Dimension
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

private val crashFile get() = File(AppDirs.root, "crash.txt")

fun main() {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try { crashFile.writeText(StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()) } catch (_: Throwable) { }
        prev?.uncaughtException(t, e)
    }
    application {
        val state = rememberWindowState(size = DpSize(1360.dp, 860.dp))
        var crash by remember { mutableStateOf(if (crashFile.exists()) crashFile.readText() else null) }
        var licensed by remember { mutableStateOf(License.isLicensed(appContext)) }
        val nativeError = Engine.loadError
        val rig = remember { if (nativeError == null) Rig(appContext) else null }

        fun power() {
            val r = rig ?: return
            if (!License.isLicensed(appContext)) { licensed = false; return }
            r.togglePower()
        }
        fun quit() { rig?.shutdown(); exitApplication() }

        Window(
            onCloseRequest = { quit() },
            state = state,
            title = "NoAmp FX",
            icon = asset("logo_mark.png"),
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when {
                    e.key == Key.Escape -> BackStack.fire()
                    e.key == Key.F11 -> { state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen; true }
                    rig != null && licensed && crash == null && e.isCtrlPressed && e.key == Key.DirectionRight -> { rig.next(); true }
                    rig != null && licensed && crash == null && e.isCtrlPressed && e.key == Key.DirectionLeft -> { rig.prev(); true }
                    rig != null && licensed && crash == null && e.isCtrlPressed && e.key == Key.E -> { power(); true }
                    rig != null && licensed && crash == null && e.isCtrlPressed && e.key == Key.T -> { rig.setTuner(!rig.tunerOn); true }
                    else -> false
                }
            }
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(1040, 660) }
            NoAmpTheme {
                val c = crash
                when {
                    nativeError != null -> ErrorPage("The audio engine (noamp.dll) could not be loaded", nativeError, null)
                    c != null -> ErrorPage("The app crashed last time", c) { crashFile.delete(); crash = null }
                    !licensed -> LicenseScreen(License.deviceCode(appContext)) { k -> License.activate(appContext, k).also { ok -> if (ok) licensed = true } }
                    rig != null -> NoAmpApp(rig, onPower = { power() })
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ErrorPage(title: String, text: String, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().background(C.Bg).padding(24.dp)) {
        Text(title, color = C.Red, fontSize = 18.sp)
        Text("Copy the text below and send it to the developer.", color = C.Dim, fontSize = 12.sp)
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Copy") { Clip.copy(text) }
            if (onRetry != null) Btn("Try again", C.Green) { onRetry() }
        }
        Text(text, color = C.Text, fontSize = 11.sp, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
    }
}
