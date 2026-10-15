package com.noamp.fx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.Synthesizer
import javax.swing.SwingUtilities

/** Pesan MIDI yang disederhanakan: Program Change, Control Change, atau Note On. */
data class MidiMsg(val type: Int, val ch: Int, val num: Int, val value: Int) {
    companion object { const val PC = 0; const val CC = 1; const val NOTE = 2 }
    fun describe() = MidiBinding.label(type, ch, num) + if (type == CC) " = $value" else ""
}

/** Satu pemetaan MIDI -> target. mode 0: tekan/toggle, mode 1: ikuti nilai CC (≥64 = on) atau setiap pesan CC memicu. */
data class MidiBinding(val target: String, val type: Int, val ch: Int, val num: Int, val mode: Int = 0, val lo: Float = 0f, val hi: Float = 1f,
                       val ccLo: Int = 0, val ccHi: Int = 127, val curve: Int = 0) {
    /** CC value (0..127) -> parameter value, applying pedal calibration, response curve and the lo..hi range. */
    fun mapValue(v: Int): Float {
        val span = (ccHi - ccLo).coerceAtLeast(1)
        val x = ((v - ccLo).toFloat() / span).coerceIn(0f, 1f)
        val s = when (curve) { 1 -> x * x; 2 -> kotlin.math.sqrt(x); else -> x }
        return lo + (hi - lo) * s
    }
    fun label() = label(type, ch, num)
    companion object {
        private val NN = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun label(type: Int, ch: Int, num: Int) = when (type) {
            MidiMsg.PC -> "PC $num"
            MidiMsg.CC -> "CC $num"
            else -> "Note ${NN[num % 12]}${num / 12 - 1}"
        } + " · ch ${ch + 1}"
    }
}

/** ID target MIDI learn. */
object T {
    const val PREV = "prev"
    const val NEXT = "next"
    fun preset(id: String) = "preset:$id"
    const val TUNER = "tuner"
    const val SCENE_A = "sceneA"; const val SCENE_B = "sceneB"; const val SCENE_C = "sceneC"; const val SCENE_D = "sceneD"; const val SCENE_E = "sceneE"; const val NEXT_EXPR = "nextExpr"; const val EXPR = "expr"; const val SCENE_TOGGLE = "sceneT"
    const val LOOPER = "looper"; const val LOOP_STOP = "loopStop"; const val LOOP_CLEAR = "loopClear"
    const val PLAYER = "player"; const val METRO = "metro"
    fun mod(paramId: Int) = "mod:$paramId"
    /** Parameter kontinu (expression pedal, CC 0..127 -> lo..hi). */
    fun par(paramId: Int) = "par:$paramId"
}

data class MidiDev(val id: Int, val name: String, val bluetooth: Boolean)

/**
 * MIDI input for every USB / Bluetooth-LE / virtual MIDI device Windows exposes (javax.sound.midi).
 * New devices are picked up automatically (the list is re-read every 2 seconds). Callbacks to [onMsg] arrive on the UI thread.
 */
class MidiIO(@Suppress("UNUSED_PARAMETER") ctx: Context, private val onMsg: (MidiMsg) -> Unit) {
    private class Opened(val dev: MidiDevice, val name: String, var out: Receiver?)
    private val opened = ConcurrentHashMap<String, Opened>()
    private val ignored = HashSet<String>()
    @Volatile private var alive = false
    private var poller: Thread? = null

    /** MIDI clock (24 ppqn): BPM measured from incoming 0xF8 ticks, delivered on the UI thread. */
    var onClock: ((Float) -> Unit)? = null
    var clockBpm by mutableStateOf(0f)
    private val tickTimes = LongArray(25); private var tickCount = 0; private var lastClockPost = 0f; private var lastTickAt = 0L

    val devices = mutableStateListOf<MidiDev>()
    var note by mutableStateOf("")
    val scanning: Boolean get() = false
    val btConnected: Boolean get() = false
    val btName: String get() = ""
    val usbConnected: Boolean get() = devices.isNotEmpty()

    /** Sends raw MIDI bytes to every connected device that also has an output port with the same name (LED feedback etc.). */
    fun send(bytes: ByteArray) {
        opened.values.forEach { o ->
            try { o.out?.send(javax.sound.midi.ShortMessage().also { m -> if (bytes.size >= 3) m.setMessage(bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0x7F, bytes[2].toInt() and 0x7F) else if (bytes.size == 2) m.setMessage(bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0x7F, 0) }, -1) } catch (_: Exception) { }
        }
    }
    val hasOutput: Boolean get() = opened.values.any { it.out != null }

    private fun onTick(nowNs: Long) {
        synchronized(tickTimes) {
            if (nowNs - lastTickAt > 500_000_000L) tickCount = 0       // clock restarted
            lastTickAt = nowNs
            if (tickCount < 25) tickTimes[tickCount++] = nowNs
            else { System.arraycopy(tickTimes, 1, tickTimes, 0, 24); tickTimes[24] = nowNs }
            if (tickCount >= 25) {
                val bpm = (60e9 / ((tickTimes[24] - tickTimes[0]) / 24.0 * 24.0)).toFloat()
                if (bpm in 20f..400f && kotlin.math.abs(bpm - lastClockPost) > 0.4f) { lastClockPost = bpm; SwingUtilities.invokeLater { clockBpm = bpm; onClock?.invoke(bpm) } }
            }
        }
    }

    fun start() {
        if (alive) return
        alive = true
        poller = Thread({
            while (alive) {
                try { scanOnce() } catch (_: Throwable) { }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }, "noamp-midi-scan").apply { isDaemon = true; start() }
    }

    fun stop() {
        alive = false; poller?.interrupt()
        opened.keys.toList().forEach { close(it) }
    }

    /** Forget the "disconnected" list and look for devices again right away. */
    fun rescan() { ignored.clear(); Thread { try { scanOnce() } catch (_: Throwable) { } }.start() }
    fun startScan() = rescan()
    fun reconnectLast() { }

    private fun scanOnce() {
        val infos = MidiSystem.getMidiDeviceInfo()
        val inputs = HashSet<String>()
        for (info in infos) {
            val dev = try { MidiSystem.getMidiDevice(info) } catch (_: Exception) { continue }
            if (dev is Sequencer || dev is Synthesizer) continue
            val name = info.name
            if (dev.maxTransmitters != 0 && !dev.isOpen) {                          // an input port (device -> app)
                if (name in ignored || opened.containsKey(name)) continue
                try {
                    dev.open()
                    dev.transmitter.receiver = Rx()
                    opened[name] = Opened(dev, name, null)
                    inputs.add(name)
                    refreshList()
                } catch (_: Exception) { try { dev.close() } catch (_: Exception) { } }
            }
        }
        // matching output ports (same name) for LED feedback
        for (info in infos) {
            val o = opened[info.name] ?: continue
            if (o.out != null) continue
            val dev = try { MidiSystem.getMidiDevice(info) } catch (_: Exception) { continue }
            if (dev is Sequencer || dev is Synthesizer || dev.maxReceivers == 0 || dev === o.dev) continue
            try { if (!dev.isOpen) dev.open(); o.out = dev.receiver } catch (_: Exception) { }
        }
        // devices that were unplugged (their port closed itself or vanished)
        val present = infos.map { it.name }.toSet()
        opened.keys.toList().forEach { n -> if (n !in present) close(n) }
    }

    private fun close(name: String) {
        val o = opened.remove(name) ?: return
        try { o.out?.close() } catch (_: Exception) { }
        try { o.dev.close() } catch (_: Exception) { }
        refreshList()
    }

    /** Disconnects one device until the next [rescan]. */
    fun disconnect(id: Int) {
        val n = opened.values.firstOrNull { it.name.hashCode() == id }?.name ?: return
        ignored.add(n); close(n)
    }

    private fun refreshList() {
        val list = opened.values.map { MidiDev(it.name.hashCode(), it.name, false) }.sortedBy { it.name }
        SwingUtilities.invokeLater { devices.clear(); devices.addAll(list) }
    }

    // ------------------------------------------------------------ MIDI byte parser (running status, SysEx, realtime)
    private inner class Rx : Receiver {
        private var status = 0; private var d1 = 0; private var n = 0; private var sysex = false
        override fun close() { }
        override fun send(message: MidiMessage, timeStamp: Long) {
            val now = System.nanoTime()
            val b = message.message
            for (x in b) feed(x.toInt() and 0xFF, now)
        }
        private fun emit(m: MidiMsg) { SwingUtilities.invokeLater { onMsg(m) } }
        private fun feed(b: Int, now: Long) {
            if (b >= 0xF8) { if (b == 0xF8) onTick(now); return }   // realtime: only the clock tick is used
            if (b >= 0x80) {
                when {
                    b == 0xF0 -> { sysex = true; status = 0 }
                    b == 0xF7 -> sysex = false
                    b >= 0xF0 -> status = 0
                    else -> { sysex = false; status = b; n = 0 }
                }
                return
            }
            if (sysex || status == 0) return
            val ch = status and 0x0F
            when (status and 0xF0) {
                0xC0 -> emit(MidiMsg(MidiMsg.PC, ch, b, 0))
                0x80, 0x90, 0xB0 -> if (n == 0) { d1 = b; n = 1 } else {
                    n = 0
                    when (status and 0xF0) {
                        0x90 -> if (b > 0) emit(MidiMsg(MidiMsg.NOTE, ch, d1, b))
                        0xB0 -> if (d1 < 120) emit(MidiMsg(MidiMsg.CC, ch, d1, b))
                    }
                }
                else -> { }                             // aftertouch, pitch bend, etc.: ignored
            }
        }
    }
}
