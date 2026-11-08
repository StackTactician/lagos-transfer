package com.lagostransfer.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 time-based one-time passwords (SHA-1, 6 digits, 30s).
 *
 * This is a plain authenticator: it turns a secret *the account owner already
 * has* into the code the server expects. It cannot produce codes for an
 * account whose secret it was never given.
 */
object Totp {
    const val PERIOD_MS = 30_000L
    const val DIGITS = 6

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Base32 (RFC 4648, no padding) as used by every authenticator URI. */
    fun base32Decode(input: String): ByteArray {
        val s = input.trim().replace(" ", "").replace("-", "").trimEnd('=').uppercase()
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>(s.length * 5 / 8)
        for (ch in s) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) throw IllegalArgumentException("Not a base32 secret")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    /** Counter for a moment in time - also the natural unit for replay protection. */
    fun step(atMillis: Long): Long = atMillis / PERIOD_MS

    fun code(secret: String, atMillis: Long = System.currentTimeMillis()): String {
        val key = base32Decode(secret)
        val counter = step(atMillis)
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val h = mac.doFinal(msg)
        val o = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or
                ((h[o + 1].toInt() and 0xFF) shl 16) or
                ((h[o + 2].toInt() and 0xFF) shl 8) or
                (h[o + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /** Milliseconds until the current code expires. */
    fun millisLeft(atMillis: Long = System.currentTimeMillis()): Long =
        PERIOD_MS - (atMillis % PERIOD_MS)

    fun isPlausibleSecret(secret: String): Boolean = try {
        base32Decode(secret).size >= 10
    } catch (_: Exception) {
        false
    }
}
