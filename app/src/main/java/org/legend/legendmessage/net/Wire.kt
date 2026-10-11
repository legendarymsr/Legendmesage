package org.legend.legendmessage.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * On-the-wire framing carried over a Tor stream. Every connection starts with
 * a one-byte opcode:
 *
 *  - [OP_DIRECT]  : [envelope]                       — deliver straight to the peer
 *  - [OP_DEPOSIT] : [recipientIdKey][envelope]       — drop into the peer's mailbox
 *  - [OP_COLLECT] : [recipientIdKey] then a signed challenge, then a batch of
 *                   envelopes streamed back            — pull mail addressed to me
 *
 * An envelope is [senderIdentityKey][type][ciphertextBody]; the body is always
 * a libsignal ciphertext, so the stream (and any mailbox) only ever sees
 * already-encrypted message content.
 */
object Wire {
    const val OP_DIRECT = 1
    const val OP_DEPOSIT = 2
    const val OP_COLLECT = 3

    /** Long-lived authenticated mailbox stream: backlog then live pushes. */
    const val OP_SUBSCRIBE = 4

    /** Fetch the server's Kyber prekey (so a new peer can complete our card). */
    const val OP_GET_PREKEY = 5

    /**
     * Mailbox auth v2. The v1 opcodes ([OP_COLLECT], [OP_SUBSCRIBE]) signed a
     * bare challenge, so a hostile mailbox could relay another mailbox's
     * challenge and replay our signature there. v2 signs a domain-separated
     * message bound to the mailbox onion and opcode (see [mailboxAuthMessage]).
     * Servers refuse v1; old and new builds do not interoperate for mailbox
     * collection, by design.
     */
    const val OP_COLLECT_V2 = 6
    const val OP_SUBSCRIBE_V2 = 7

    /** Per-field size limits (bytes). */
    const val IDENTITY_KEY_LEN = 33
    const val MAX_BODY = 256 * 1024
    const val CHALLENGE_LEN = 32
    const val MAX_SIGNATURE = 128
    const val MAX_KYBER_KEY = 4096

    /** Stream frame kinds (after a SUBSCRIBE is authenticated). */
    const val STREAM_ENVELOPE = 0
    const val STREAM_KEEPALIVE = 1

    private const val MAX_FRAME = 1 shl 20 // 1 MiB

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

    fun output(stream: OutputStream) = DataOutputStream(stream)

    fun input(stream: InputStream) = DataInputStream(stream)

    fun writeFrame(out: DataOutputStream, data: ByteArray) {
        out.writeInt(data.size)
        out.write(data)
    }

    fun readFrame(input: DataInputStream, max: Int = MAX_FRAME): ByteArray {
        val len = input.readInt()
        require(len in 0..minOf(max, MAX_FRAME)) { "bad frame length $len" }
        return ByteArray(len).also { input.readFully(it) }
    }

    /** A serialized Curve25519 identity key: 33 bytes, type prefix 0x05. */
    fun isIdentityKey(bytes: ByteArray) = bytes.size == IDENTITY_KEY_LEN && bytes[0] == 0x05.toByte()

    fun readIdentityKey(input: DataInputStream): ByteArray =
        readFrame(input, IDENTITY_KEY_LEN).also { require(isIdentityKey(it)) { "bad identity key" } }

    /**
     * The exact bytes a mailbox client signs (v2): a fixed domain tag, the
     * mailbox's onion (normalized), the opcode, the recipient id, and the
     * server's random challenge. Binding the onion stops cross-mailbox relays;
     * binding the opcode and tag stops cross-protocol reuse of the signature.
     */
    fun mailboxAuthMessage(op: Int, mailboxOnion: String, recipientId: ByteArray, challenge: ByteArray): ByteArray {
        val onion = mailboxOnion.trim().lowercase().removeSuffix(".onion").toByteArray(Charsets.US_ASCII)
        val buf = java.io.ByteArrayOutputStream()
        DataOutputStream(buf).apply {
            write("LegendMessage-mailbox-auth-v2".toByteArray(Charsets.US_ASCII))
            writeByte(0)
            writeInt(onion.size); write(onion)
            writeByte(op)
            writeInt(recipientId.size); write(recipientId)
            writeInt(challenge.size); write(challenge)
            flush()
        }
        return buf.toByteArray()
    }

    fun writeEnvelope(out: DataOutputStream, envelope: Envelope) {
        writeFrame(out, envelope.senderId)
        out.writeInt(envelope.type)
        writeFrame(out, envelope.body)
    }

    fun readEnvelope(input: DataInputStream): Envelope {
        val senderId = readIdentityKey(input)
        val type = input.readInt()
        val body = readFrame(input, MAX_BODY)
        require(body.isNotEmpty()) { "empty envelope" }
        return Envelope(senderId, type, body)
    }

    // ---- stream frames (mailbox subscribe) ----

    fun writeStreamEnvelope(out: DataOutputStream, envelope: Envelope) {
        out.writeInt(STREAM_ENVELOPE)
        writeEnvelope(out, envelope)
        out.flush()
    }

    fun writeStreamKeepalive(out: DataOutputStream) {
        out.writeInt(STREAM_KEEPALIVE)
        out.flush()
    }

    /** Reads the next stream frame kind; the caller reads the envelope if it is [STREAM_ENVELOPE]. */
    fun readStreamKind(input: DataInputStream): Int = input.readInt()

    // ---- Kyber prekey exchange (OP_GET_PREKEY response) ----

    fun writeKyber(out: DataOutputStream, id: Int, key: ByteArray, signature: ByteArray) {
        out.writeInt(id)
        writeFrame(out, key)
        writeFrame(out, signature)
        out.flush()
    }

    /** Returns (id, key, signature); an empty key means the peer had none. */
    fun readKyber(input: DataInputStream): Triple<Int, ByteArray, ByteArray> {
        val id = input.readInt()
        val key = readFrame(input, MAX_KYBER_KEY)
        val signature = readFrame(input, MAX_SIGNATURE)
        return Triple(id, key, signature)
    }
}
