package org.legend.legendmessage.pairing

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal length-prefixed binary codec used to pack contact cards into a
 * single compact byte string for a QR code. Each field is written as a 2-byte
 * big-endian length followed by its bytes; strings are UTF-8.
 */
class CardWriter {
    private val out = ByteArrayOutputStream()

    fun putByte(value: Int): CardWriter {
        out.write(value and 0xFF)
        return this
    }

    fun putInt(value: Int): CardWriter {
        out.write(ByteBuffer.allocate(4).putInt(value).array())
        return this
    }

    fun putBytes(value: ByteArray): CardWriter {
        require(value.size <= 0xFFFF) { "field too large: ${value.size}" }
        out.write((value.size ushr 8) and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value)
        return this
    }

    fun putString(value: String): CardWriter = putBytes(value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}

class CardReader(private val data: ByteArray) {
    private var pos = 0

    fun readByte(): Int {
        check(pos < data.size) { "card underflow" }
        return data[pos++].toInt() and 0xFF
    }

    fun readInt(): Int {
        check(pos + 4 <= data.size) { "card underflow" }
        val value = ByteBuffer.wrap(data, pos, 4).int
        pos += 4
        return value
    }

    fun readBytes(): ByteArray {
        check(pos + 2 <= data.size) { "card underflow" }
        val len = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        check(pos + len <= data.size) { "card underflow" }
        return data.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)
}

object B64 {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun encode(data: ByteArray): String = Base64.encodeToString(data, FLAGS)

    fun decode(text: String): ByteArray = Base64.decode(text, FLAGS)
}
