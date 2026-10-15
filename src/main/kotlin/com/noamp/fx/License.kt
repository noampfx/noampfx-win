package com.noamp.fx

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/**
 * Offline license: each device shows a Device ID; the developer signs it with a PRIVATE key
 * (tools/license_tool.py) and returns a license key. The app only holds the PUBLIC key, so
 * it can verify but never create licenses. A license is valid only on the device it was issued for.
 */
object License {
    // Filled in automatically by:  python3 tools/license_tool.py init
    private const val PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpffSBT9huBMurBcZ18hE+aKY2MAauAkRlk15O82yMeeVUPqmGhVBQgW90pKhamKR/RgxutQ8tMuHRe35maW3Rw=="

    private const val PREFS = "lic"
    private const val PAYLOAD_PREFIX = "NOAMPFX1|"
    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I/O/0/1 to avoid typos

    /** Device ID like ABCD-EFGH-JKLM-NPQR (80-bit hash of the device's ANDROID_ID). */
    fun deviceCode(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val aid = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Throwable) { null }
        val seed = if (!aid.isNullOrBlank() && aid != "9774d56d682e549c") aid
        else prefs.getString("fallback", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("fallback", it).apply() }
        val h = MessageDigest.getInstance("SHA-256").digest(("noampfx|" + seed).toByteArray(Charsets.UTF_8))
        return b32(h.copyOf(10)).chunked(4).joinToString("-")
    }

    fun isLicensed(ctx: Context): Boolean {
        val key = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key", null) ?: return false
        return verify(deviceCode(ctx), key)
    }

    /** Verifies [licenseKey] for this device and stores it when valid. */
    fun activate(ctx: Context, licenseKey: String): Boolean {
        val k = licenseKey.filter { !it.isWhitespace() }
        if (!verify(deviceCode(ctx), k)) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key", k).apply()
        return true
    }

    private fun verify(code: String, licenseKey: String): Boolean = try {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pub)
        sig.update((PAYLOAD_PREFIX + code).toByteArray(Charsets.UTF_8))
        sig.verify(Base64.decode(licenseKey.filter { !it.isWhitespace() }, Base64.DEFAULT))
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
