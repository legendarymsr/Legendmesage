package org.legend.legendmessage.pairing

import java.io.ByteArrayOutputStream

/**
 * Base45 (RFC 9285). Its 45-character alphabet is exactly the QR-code
 * alphanumeric set, so a base45 string encodes in QR *alphanumeric* mode —
 * nearly as compact as raw byte mode, but pure ASCII, so it round-trips
 * through any scanner without the charset-guessing corruption that breaks
 * byte-mode (binary) QRs.
 */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sb.append(ALPHABET[n % 45])
            sb.append(ALPHABET[(n / 45) % 45])
            sb.append(ALPHABET[(n / 2025) % 45])
            i += 2
        }
        if (i < data.size) {
            val n = data[i].toInt() and 0xFF
            sb.append(ALPHABET[n % 45])
            sb.append(ALPHABET[(n / 45) % 45])
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val v = IntArray(text.length) { idx ->
            ALPHABET.indexOf(text[idx]).also { require(it >= 0) { "invalid base45 char" } }
        }
        val out = ByteArrayOutputStream()
        var i = 0
        while (i + 2 < v.size) {
            val n = v[i] + v[i + 1] * 45 + v[i + 2] * 2025
            require(n <= 0xFFFF) { "invalid base45 triple" }
            out.write((n ushr 8) and 0xFF)
            out.write(n and 0xFF)
            i += 3
        }
        when (v.size - i) {
            2 -> {
                val n = v[i] + v[i + 1] * 45
                require(n <= 0xFF) { "invalid base45 pair" }
                out.write(n and 0xFF)
            }
            0 -> {}
            else -> throw IllegalArgumentException("invalid base45 length")
        }
        return out.toByteArray()
    }
}
