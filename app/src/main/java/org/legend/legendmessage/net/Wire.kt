package org.legend.legendmessage.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The on-the-wire message framing carried over a Tor stream. One envelope per
 * connection:
 *   [senderIdentityKey][ciphertextType][ciphertextBody]
 * The sender's identity key lets the receiver pick the right Signal session;
 * the body is a libsignal ciphertext, so the Tor stream carries only
 * already-encrypted bytes.
 */
object Wire {
    private const val MAX_ID = 1024
    private const val MAX_BODY = 1 shl 20 // 1 MiB ciphertext ceiling

    data class Envelope(val senderId: ByteArray, val type: Int, val body: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Envelope) return false
            return type == other.type &&
                senderId.contentEquals(other.senderId) &&
                body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var r = senderId.contentHashCode()
            r = 31 * r + type
            r = 31 * r + body.contentHashCode()
            return r
        }
    }

    fun write(out: OutputStream, senderId: ByteArray, type: Int, body: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(senderId.size)
        d.write(senderId)
        d.writeInt(type)
        d.writeInt(body.size)
        d.write(body)
        d.flush()
    }

    fun read(input: InputStream): Envelope {
        val d = DataInputStream(input)
        val idLen = d.readInt()
        require(idLen in 1..MAX_ID) { "bad id length $idLen" }
        val id = ByteArray(idLen).also { d.readFully(it) }
        val type = d.readInt()
        val bodyLen = d.readInt()
        require(bodyLen in 1..MAX_BODY) { "bad body length $bodyLen" }
        val body = ByteArray(bodyLen).also { d.readFully(it) }
        return Envelope(id, type, body)
    }
}
