package com.noamp.fx

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes an audio file straight into the native backing-track player: WAV here (any bit depth), MP3 / FLAC by the native decoder. */
object AudioFile {
    private const val MAX_SECONDS = 600     // the native player holds at most 10 minutes

    @Volatile private var cancelFlag = false
    /** Set to true to abort a decode that runs in the background (the caller waits for it to finish, then resets it). */
    var cancel: Boolean
        get() = cancelFlag
        set(v) { cancelFlag = v; if (Engine.loadError == null) Engine.nativeSetDecodeCancel(v) }

    /** Duration in seconds (fast), 0 when unknown. */
    fun probeDuration(file: File): Double = try { Engine.nativeProbeDuration(file.absolutePath) } catch (_: Throwable) { 0.0 }

    /**
     * Decodes at most [limitSec] seconds and hands them to the native player.
     * [keep] = this is the complete version of the song that is already playing: the engine swaps it in and continues from the same position.
     * Returns the decoded seconds, or null if the file could not be decoded (or [cancel] was set).
     */
    fun load(file: File, limitSec: Int = MAX_SECONDS, keep: Boolean = false, onProgress: (Float) -> Unit = {}): Double? {
        val wav = try { if (file.extension.equals("wav", true)) file.inputStream().use { loadWav(it, limitSec, keep, onProgress) } else null } catch (_: Exception) { null }
        if (wav != null) return wav
        if (cancel) return null
        val sec = try { Engine.nativeDecodeFile(file.absolutePath, limitSec, keep) } catch (_: Throwable) { -1.0 }
        if (sec >= 0) onProgress(1f)
        return if (sec >= 0 && !cancel) sec else null
    }

    private fun leInt(d: DataInputStream) = Integer.reverseBytes(d.readInt())
    private fun leShort(d: DataInputStream) = java.lang.Short.reverseBytes(d.readShort()).toInt() and 0xFFFF

    private fun loadWav(input: InputStream, limitSec: Int, keep: Boolean, onProgress: (Float) -> Unit): Double? {
        val d = DataInputStream(BufferedInputStream(input, 1 shl 16))
        if (leInt(d) != 0x46464952) return null           // "RIFF"
        leInt(d); if (leInt(d) != 0x45564157) return null   // size, "WAVE"
        var fmt = 1; var ch = 2; var rate = 48000; var bits = 16
        while (true) {
            val id = try { leInt(d) } catch (_: Exception) { return null }
            val len = leInt(d).toLong() and 0xFFFFFFFFL
            if (id == 0x20746d66) {                        // "fmt "
                fmt = leShort(d); ch = leShort(d); rate = leInt(d); leInt(d); leShort(d); bits = leShort(d)
                var used = 16L
                if (fmt == 0xFFFE && len >= 26) { d.skipBytes(8); fmt = leShort(d); used = 26 }   // WAVE_FORMAT_EXTENSIBLE
                d.skipBytes((len - used + (len and 1)).toInt().coerceAtLeast(0))
            } else if (id == 0x61746164) {                 // "data"
                if (ch < 1 || bits !in intArrayOf(8, 16, 24, 32) || (fmt != 1 && fmt != 3)) return null
                return streamPcm(d, if (len == 0L || len == 0xFFFFFFFFL) Long.MAX_VALUE else len, ch, rate, bits, fmt == 3, limitSec, keep, onProgress)
            } else {
                var left = len + (len and 1)
                while (left > 0) { val s = d.skipBytes(minOf(left, 1L shl 30).toInt()); if (s <= 0) return null; left -= s }
            }
        }
    }

    private fun streamPcm(d: DataInputStream, dataLen: Long, ch: Int, rate: Int, bits: Int, isFloat: Boolean, limitSec: Int, keep: Boolean, onProgress: (Float) -> Unit): Double? {
        val bytesPer = bits / 8; val bpf = bytesPer * ch
        val total = if (dataLen == Long.MAX_VALUE) 0L else dataLen / bpf
        val outCh = minOf(ch, 2)
        Engine.nativePlayerBegin(rate, outCh, minOf(total, rate.toLong() * limitSec))
        val frames = 4096; val raw = ByteArray(frames * bpf); val out = ShortArray(frames * outCh)
        var remaining = dataLen; var done = 0L; val maxFrames = rate.toLong() * limitSec
        while (remaining > 0 && done < maxFrames && !cancel) {
            val want = minOf(raw.size.toLong(), remaining).toInt() / bpf * bpf
            if (want <= 0) break
            var got = 0
            while (got < want) { val r = d.read(raw, got, want - got); if (r < 0) break; got += r }
            val n = got / bpf; if (n <= 0) break
            val bb = ByteBuffer.wrap(raw, 0, n * bpf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) for (c in 0 until outCh) {
                val o = i * bpf + c * bytesPer
                val v: Int = when {
                    isFloat -> (bb.getFloat(o).coerceIn(-1f, 1f) * 32767f).toInt()
                    bits == 8 -> ((raw[o].toInt() and 0xFF) - 128) shl 8
                    bits == 16 -> bb.getShort(o).toInt()
                    bits == 24 -> (raw[o + 2].toInt() shl 8) or (raw[o + 1].toInt() and 0xFF)
                    else -> bb.getInt(o) shr 16
                }
                out[i * outCh + c] = v.toShort()
            }
            Engine.nativePlayerPush(out, n * outCh)
            done += n; remaining -= n.toLong() * bpf
            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            if (got < want) break
        }
        if (cancel) return null
        return if (Engine.nativePlayerEnd(keep)) done.toDouble() / rate else null
    }
}
