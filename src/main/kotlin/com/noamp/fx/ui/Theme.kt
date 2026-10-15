package com.noamp.fx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Bg = Color(0xFF080A0D); val Panel = Color(0xFF12161B); val PanelHi = Color(0xFF1A2027); val Line = Color(0xFF262D37)
    val Text = Color(0xFFE6EAF0); val Dim = Color(0xFF7C8794); val Off = Color(0xFF39414C)
    val Accent = Color(0xFF29E0F0); val Green = Color(0xFF3DDC84); val Amber = Color(0xFFFFB02E); val Red = Color(0xFFFF4D5E)
    val Violet = Color(0xFFA78BFA); val Pink = Color(0xFFFF6FB5); val Blue = Color(0xFF5B9BFF); val Orange = Color(0xFFFF8A3D)
    val band = listOf(Color(0xFFFF4D5E), Color(0xFFFFB02E), Color(0xFF3DDC84), Color(0xFF29E0F0), Color(0xFFA78BFA))
}

@Composable
fun NoAmpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = C.Accent, background = C.Bg, surface = C.Panel, onSurface = C.Text, onBackground = C.Text),
        content = content
    )
}
