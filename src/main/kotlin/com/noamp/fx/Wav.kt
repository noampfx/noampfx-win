package com.noamp.fx

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Baca WAV (PCM16/24/32, float32) -> mono float @ targetRate (linear resample). */
object Wav {
    fun readMono(input: InputStream, targetRate: Int = 48000, maxSamples: Int = 8192): FloatArray? {
        val b = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (b.limit() < 44 || b.getInt(0) != 0x46464952) return null // "RIFF"
        var pos = 12; var fmt = 1; var ch = 1; var rate = 48000; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= b.limit()) {
            val id = b.getInt(pos); val len = b.getInt(pos + 4)
            if (id == 0x20746d66) { // "fmt "
                fmt = b.getShort(pos + 8).toInt() and 0xFFFF; ch = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt()
                if (fmt == 0xFFFE && len >= 26) fmt = b.getShort(pos + 32).toInt() and 0xFFFF
            } else if (id == 0x61746164) { dataOff = pos + 8; dataLen = minOf(len, b.limit() - dataOff); break }
            pos += 8 + len + (len and 1)
        }
        if (dataOff < 0 || ch < 1) return null
        val bytes = bits / 8; val frames = dataLen / (bytes * ch)
        val n = minOf(frames, (maxSamples.toLong() * rate / targetRate).toInt() + 2)
        val src = FloatArray(n)
        for (i in 0 until n) {
            val o = dataOff + i * bytes * ch
            src[i] = when {
                fmt == 3 && bits == 32 -> b.getFloat(o)
                bits == 16 -> b.getShort(o) / 32768f
                bits == 24 -> (((b.get(o + 2).toInt() shl 16) or ((b.get(o + 1).toInt() and 0xFF) shl 8) or (b.get(o).toInt() and 0xFF)) / 8388608f)
                bits == 32 -> b.getInt(o) / 2147483648f
                else -> 0f
            }
        }
        if (rate == targetRate) return src.copyOf(minOf(n, maxSamples))
        val outN = minOf(maxSamples, (n.toLong() * targetRate / rate).toInt())
        val out = FloatArray(outN); val r = rate.toDouble() / targetRate
        for (i in 0 until outN) {
            val p = i * r; val i0 = p.toInt(); val f = (p - i0).toFloat()
            val a = src[minOf(i0, n - 1)]; val c = src[minOf(i0 + 1, n - 1)]
            out[i] = a + (c - a) * f
        }
        return out
    }
}

/** WAV writer 24-bit PCM (interleaved float input). Header is patched on close(). */
class WavWriter(val file: File, private val channels: Int, private val rate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var bytes = 0L
    private var buf = ByteArray(0)
    init { raf.setLength(0); raf.write(ByteArray(44)) }
    fun write(f: FloatArray, count: Int) {
        val need = count * 3; if (buf.size < need) buf = ByteArray(need)
        var o = 0
        for (i in 0 until count) {
            val v = (f[i].coerceIn(-1f, 1f) * 8388607f).toInt()
            buf[o++] = v.toByte(); buf[o++] = (v shr 8).toByte(); buf[o++] = (v shr 16).toByte()
        }
        raf.write(buf, 0, need); bytes += need
    }
    fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + bytes).toInt()); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(rate); h.putInt(rate * channels * 3)
        h.putShort((channels * 3).toShort()); h.putShort(24); h.put("data".toByteArray()); h.putInt(bytes.toInt())
        raf.seek(0); raf.write(h.array()); raf.close()
    }
}
