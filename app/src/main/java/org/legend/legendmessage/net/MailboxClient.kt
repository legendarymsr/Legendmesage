package org.legend.legendmessage.net

import org.legend.legendmessage.tor.TorConfig
import org.legend.legendmessage.tor.TorState

/** Client side of the mailbox protocol: deposit for an offline peer, or collect our own mail. */
object MailboxClient {

    /** Drop one envelope into [mailboxOnion] addressed to [recipientId]. */
    fun deposit(mailboxOnion: String, recipientId: ByteArray, envelope: Wire.Envelope) {
        TorState.openThroughTor(mailboxOnion, TorConfig.VIRTUAL_PORT).use { socket ->
            socket.soTimeout = 60_000
            val out = Wire.output(socket.getOutputStream())
            out.writeByte(Wire.OP_DEPOSIT)
            Wire.writeFrame(out, recipientId)
            Wire.writeEnvelope(out, envelope)
            out.flush()
        }
    }

    /**
     * Collect all envelopes [mailboxOnion] holds for us. We prove ownership of
     * [myIdentityKey] by signing the server's challenge with [sign].
     */
    fun collect(
        mailboxOnion: String,
        myIdentityKey: ByteArray,
        sign: (ByteArray) -> ByteArray,
    ): List<Wire.Envelope> {
        TorState.openThroughTor(mailboxOnion, TorConfig.VIRTUAL_PORT).use { socket ->
            socket.soTimeout = 60_000
            val out = Wire.output(socket.getOutputStream())
            val input = Wire.input(socket.getInputStream())

            out.writeByte(Wire.OP_COLLECT)
            Wire.writeFrame(out, myIdentityKey)
            out.flush()

            val challenge = Wire.readFrame(input)
            Wire.writeFrame(out, sign(challenge))
            out.flush()

            val count = input.readInt()
            return (0 until count).map { Wire.readEnvelope(input) }
        }
    }
}
