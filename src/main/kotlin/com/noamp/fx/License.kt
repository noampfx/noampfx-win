package com.noamp.fx

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Offline license (same scheme as the Android app): each PC shows a Device ID; the developer signs it with the PRIVATE key
 * (tools/license_tool.py or the keygen window) and returns a license key. The app only holds the PUBLIC key, so it can verify
 * but never create licenses. A license is valid only on the PC it was issued for.
 *
 * Use the SAME key pair as the Android app: paste the same public key here (see README_WINDOWS.md).
 */
object License {
    // Filled in automatically by:  python tools/license_tool.py init   (or paste the public key of your Android License.kt)
    private const val PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpffSBT9huBMurBcZ18hE+aKY2MAauAkRlk15O82yMeeVUPqmGhVBQgW90pKhamKR/RgxutQ8tMuHRe35maW3Rw=="

    private const val PREFS = "lic"
    private const val PAYLOAD_PREFIX = "NOAMPFX1|"
    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I/O/0/1 to avoid typos

    @Volatile private var cachedCode: String? = null

    /** Device ID like ABCD-EFGH-JKLM-NPQR (80-bit hash of the Windows MachineGuid). */
    fun deviceCode(ctx: Context): String {
        cachedCode?.let { return it }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seed = machineGuid()
            ?: prefs.getString("fallback", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("fallback", it).apply() }
        val h = MessageDigest.getInstance("SHA-256").digest(("noampfx|" + seed).toByteArray(Charsets.UTF_8))
        return b32(h.copyOf(10)).chunked(4).joinToString("-").also { cachedCode = it }
    }

    fun isLicensed(ctx: Context): Boolean {
        val key = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key", null) ?: return false
        return verify(deviceCode(ctx), key)
    }

    /** Verifies [licenseKey] for this PC and stores it when valid. */
    fun activate(ctx: Context, licenseKey: String): Boolean {
        val k = licenseKey.filter { !it.isWhitespace() }
        if (!verify(deviceCode(ctx), k)) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key", k).commit()
        return true
    }

    private fun machineGuid(): String? = try {
        val p = ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        Regex("MachineGuid\\s+REG_SZ\\s+([0-9A-Fa-f-]{36})").find(out)?.groupValues?.get(1)?.lowercase()
    } catch (_: Throwable) { null }

    private fun verify(code: String, licenseKey: String): Boolean = try {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(PUBLIC_KEY_B64)))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pub)
        sig.update((PAYLOAD_PREFIX + code).toByteArray(Charsets.UTF_8))
        sig.verify(Base64.getMimeDecoder().decode(licenseKey.filter { !it.isWhitespace() }))
    } catch (_: Throwable) { false }

    private fun b32(b: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(ALPHA[(buf shr (bits - 5)) and 31]); bits -= 5 }
            buf = buf and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHA[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }
}
