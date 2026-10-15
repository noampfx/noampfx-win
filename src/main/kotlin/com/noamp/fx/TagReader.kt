package com.noamp.fx

import java.io.File
import java.io.RandomAccessFile

/** Title / artist / album / genre and cover art from an ID3v2 tag (MP3). Other formats simply have no tags here. */
class Tags(val title: String?, val artist: String?, val album: String?, val genre: String?, val art: ByteArray?)

object TagReader {
    fun read(f: File, wantArt: Boolean): Tags? {
        if (!f.extension.equals("mp3", true)) return null
        return try { RandomAccessFile(f, "r").use { parse(it, wantArt) } } catch (_: Throwable) { null }
    }

    private fun syncsafe(b: ByteArray, o: Int) = ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)
    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun parse(r: RandomAccessFile, wantArt: Boolean): Tags? {
        val h = ByteArray(10); r.readFully(h)
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
        val ver = h[3].toInt()
        if (ver != 3 && ver != 4) return null
        val size = syncsafe(h, 6).coerceIn(0, if (wantArt) 16 shl 20 else 256 shl 10)
        val tag = ByteArray(size); r.readFully(tag)
        var title: String? = null; var artist: String? = null; var album: String? = null; var genre: String? = null; var art: ByteArray? = null
        var p = 0
        while (p + 10 <= tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val fs = if (ver == 4) syncsafe(tag, p + 4) else be32(tag, p + 4)
            val start = p + 10
            if (fs <= 0 || start + fs > tag.size) break
            when (id) {
                "TIT2" -> title = text(tag, start, fs)
                "TPE1" -> artist = text(tag, start, fs)
                "TALB" -> album = text(tag, start, fs)
                "TCON" -> genre = text(tag, start, fs)?.let { g -> Regex("^\\((\\d+)\\)").replace(g, "").trim().takeIf { it.isNotEmpty() && !it.all(Char::isDigit) } }
                "APIC" -> if (wantArt && art == null) art = picture(tag, start, fs)
            }
            p = start + fs
        }
        return Tags(title?.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() }, genre?.takeIf { it.isNotBlank() }, art)
    }

    private fun charset(enc: Int) = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }

    private fun text(b: ByteArray, o: Int, len: Int): String? {
        if (len < 2) return null
        return String(b, o + 1, len - 1, charset(b[o].toInt())).trim('\u0000', ' ', '\uFEFF').substringBefore('\u0000')
    }

    private fun picture(b: ByteArray, o: Int, len: Int): ByteArray? {
        val enc = b[o].toInt(); var p = o + 1; val end = o + len
        while (p < end && b[p].toInt() != 0) p++          // MIME type
        p += 2                                            // terminator + picture type
        if (p >= end) return null
        if (enc == 1 || enc == 2) { while (p + 1 < end && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2; p += 2 }
        else { while (p < end && b[p].toInt() != 0) p++; p += 1 }
        return if (p < end) b.copyOfRange(p, end) else null
    }
}
