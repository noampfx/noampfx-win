package com.noamp.fx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noamp.fx.MicP
import com.noamp.fx.PDef
import com.noamp.fx.Rig

/** Module page of the channel 2 chain (opened by long press on a block) - same layout and knobs as the channel 1 module pages. */
@Composable
fun MicModuleScreen(rig: Rig) {
    val slot = rig.uiMicSlot.coerceIn(0, 3)
    val type = rig.micSlot(slot)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (type == 0) {
            Text("This block is empty.", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Turn on Edit above the signal chain and tap the block to choose a module.", color = C.Dim, fontSize = 12.sp)
            return@Column
        }
        val tab = when (type) { MicP.T_GATE -> Tab.GATE; MicP.T_COMP -> Tab.COMP; MicP.T_EQ -> Tab.EQ; MicP.T_DLY -> Tab.DLY; else -> Tab.REV }
        val onId = MicP.onId(type); val isOn = rig.micFx[onId] > 0.5f
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${MicP.TYPE_NAMES[type].uppercase()} · CH 2", color = tab.color, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("Microphone effect · only input 2 goes through it", color = C.Dim, fontSize = 11.sp)
            }
            PowerToggle(isOn, tab.color) { rig.setMicFx(onId, if (isOn) 0f else 1f) }
        }
        if (type == MicP.T_EQ) {
            Panel(title = "Low") { KnobRow { MK(rig, MicP.EQ_LC, "Low cut", 20f, 300f, 80f, " Hz", 0, tab, 1f, true); MK(rig, MicP.EQ_LOWF, "Freq", 40f, 400f, 120f, " Hz", 0, tab, 1f, true); MK(rig, MicP.EQ_LOW, "Gain", -15f, 15f, 0f, " dB", 1, tab) } }
            Panel(title = "Mid 1") { KnobRow { MK(rig, MicP.EQ_M1F, "Freq", 100f, 2000f, 400f, " Hz", 0, tab, 1f, true); MK(rig, MicP.EQ_MID, "Gain", -15f, 15f, 0f, " dB", 1, tab); MK(rig, MicP.EQ_M1Q, "Q", 0.3f, 8f, 1f, "", 1, tab, 1f, true) } }
            Panel(title = "Mid 2") { KnobRow { MK(rig, MicP.EQ_M2F, "Freq", 500f, 8000f, 2500f, " Hz", 0, tab, 1f, true); MK(rig, MicP.EQ_M2G, "Gain", -15f, 15f, 0f, " dB", 1, tab); MK(rig, MicP.EQ_M2Q, "Q", 0.3f, 8f, 1f, "", 1, tab, 1f, true) } }
            Panel(title = "High") { KnobRow { MK(rig, MicP.EQ_HIGHF, "Freq", 2000f, 16000f, 8000f, " Hz", 0, tab, 1f, true); MK(rig, MicP.EQ_HIGH, "Gain", -15f, 15f, 0f, " dB", 1, tab) } }
        } else Panel {
            KnobRow {
                when (type) {
                    MicP.T_GATE -> MK(rig, MicP.GATE_THR, "Threshold", -80f, -20f, -50f, " dB", 0, tab)
                    MicP.T_COMP -> { MK(rig, MicP.COMP_THR, "Threshold", -40f, 0f, -18f, " dB", 0, tab); MK(rig, MicP.COMP_RATIO, "Ratio", 1f, 10f, 3f, ":1", 1, tab); MK(rig, MicP.COMP_MAKEUP, "Makeup", 0f, 18f, 3f, " dB", 0, tab) }
                    MicP.T_DLY -> { MK(rig, MicP.DLY_TIME, "Time", 50f, 1000f, 250f, " ms", 0, tab); MK(rig, MicP.DLY_FB, "Feedback", 0f, 80f, 30f, "%", 0, tab, 100f); MK(rig, MicP.DLY_MIX, "Mix", 0f, 100f, 20f, "%", 0, tab, 100f) }
                    else -> { MK(rig, MicP.REV_SIZE, "Size", 0f, 100f, 50f, "%", 0, tab, 100f); MK(rig, MicP.REV_MIX, "Mix", 0f, 100f, 20f, "%", 0, tab, 100f) }
                }
            }
        }
        Text(
            when (type) {
                MicP.T_GATE -> "Closes when you are not speaking or singing, to cut room noise."
                MicP.T_COMP -> "Evens out loud and quiet parts of the voice. Makeup raises the level after compression."
                MicP.T_EQ -> "4-band parametric EQ: low cut, low shelf, two peaking bands with adjustable frequency and Q, and a high shelf."
                MicP.T_DLY -> "Echo for the microphone only."
                else -> "Space for the microphone. The reverb is always the last block of the chain."
            }, color = C.Dim, fontSize = 11.sp
        )
    }
}

/** Knob for a mic parameter. [scale] 100 = the engine value is 0..1 but the knob shows 0..100 %. */
@Composable
private fun MK(rig: Rig, id: Int, name: String, min: Float, max: Float, def: Float, unit: String, dec: Int, tab: Tab, scale: Float = 1f, log: Boolean = false) {
    val d = PDef(id, name, min, max, def, unit, log, dec)
    Knob(d, rig.micFx[id] * scale, tab.color, { rig.setMicFx(id, it / scale) }, 76.dp, name)
}
