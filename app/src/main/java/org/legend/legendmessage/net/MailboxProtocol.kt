package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.KyberBundle
import org.legend.legendmessage.data.MailboxStore
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.io.DataInputStream
import java.net.Socket
import java.security.SecureRandom

/**
 * The per-connection server protocol, shared by the real [PeerServer] and the
 * on-device self-test. One opcode per connection:
 *  - OP_DIRECT    : decrypt + store a message sent straight to us
 *  - OP_DEPOSIT   : (mailbox mode) hold ciphertext for an offline recipient
 *  - OP_COLLECT   : (mailbox mode) one-shot release after a signed challenge
 *  - OP_SUBSCRIBE : (mailbox mode) long-lived authenticated stream: backlog
 *                   then live pushes + keepalive
 */
object MailboxProtocol {
    private const val TAG = "MailboxProtocol"
    private const val KEEPALIVE_MS = 90_000L

    private val random = SecureRandom()

    /**
     * Handle one accepted connection to completion (closes [socket]).
     * [kyberProvider] supplies our Kyber prekey for OP_GET_PREKEY (null in the
     * self-test, which doesn't exercise that opcode).
     */
    fun handle(
        socket: Socket,
        inbound: InboundDelivery,
        mailbox: MailboxStore,
        mailboxEnabled: Boolean,
        kyberProvider: () -> KyberBundle? = { null },
    ) {
        socket.use {
            try {
                val input = Wire.input(it.getInputStream())
                when (input.readByte().toInt()) {
                    Wire.OP_DIRECT -> inbound.deliver(Wire.readEnvelope(input))
                    Wire.OP_DEPOSIT -> handleDeposit(input, mailbox, mailboxEnabled)
                    Wire.OP_COLLECT -> handleCollect(it, input, mailbox, mailboxEnabled)
                    Wire.OP_SUBSCRIBE -> handleSubscribe(it, input, mailbox, mailboxEnabled)
                    Wire.OP_GET_PREKEY -> handleGetPreKey(it, kyberProvider)
                    else -> Log.w(TAG, "unknown opcode")
                }
            } catch (e: Exception) {
                Log.w(TAG, "connection ended: ${e.message}")
            }
        }
    }

    private fun handleGetPreKey(socket: Socket, kyberProvider: () -> KyberBundle?) {
        val bundle = kyberProvider() ?: KyberBundle(0, ByteArray(0), ByteArray(0))
        Wire.writeKyber(Wire.output(socket.getOutputStream()), bundle.id, bundle.key, bundle.signature)
    }

    private fun handleDeposit(input: DataInputStream, mailbox: MailboxStore, enabled: Boolean) {
        if (!enabled) return
        val recipientId = Wire.readFrame(input)
        val envelope = Wire.readEnvelope(input)
        val recipientHex = hex(recipientId)
        if (mailbox.deposit(recipientHex, envelope)) {
            MailboxNotifier.signal(recipientHex) // wake a connected subscriber
        }
    }

    private fun handleSubscribe(socket: Socket, input: DataInputStream, mailbox: MailboxStore, enabled: Boolean) {
        if (!enabled) return
        val recipientId = Wire.readFrame(input)
        if (!authenticate(socket, input, recipientId)) return

        socket.soTimeout = 0 // only write after auth; block on the notifier, not a read
        val output = Wire.output(socket.getOutputStream())
        val recipientHex = hex(recipientId)
        while (!Thread.currentThread().isInterrupted && !socket.isClosed) {
            val held = mailbox.peekAll(recipientHex)
            if (held.isEmpty()) {
                Wire.writeStreamKeepalive(output) // also surfaces a dead socket
            } else {
                // Write THEN delete: a dead socket's write throws before the
                // delete, so a stale subscriber can't steal and lose the message.
                for ((id, envelope) in held) {
                    Wire.writeStreamEnvelope(output, envelope)
                    mailbox.deleteById(id)
                }
            }
            MailboxNotifier.await(recipientHex, KEEPALIVE_MS)
        }
    }

    private fun handleCollect(socket: Socket, input: DataInputStream, mailbox: MailboxStore, enabled: Boolean) {
        if (!enabled) return
        val recipientId = Wire.readFrame(input)
        val output = Wire.output(socket.getOutputStream())
        if (!authenticate(socket, input, recipientId)) {
            output.writeInt(0)
            output.flush()
            return
        }
        val messages = mailbox.collectAndDelete(hex(recipientId))
        output.writeInt(messages.size)
        messages.forEach { Wire.writeEnvelope(output, it) }
        output.flush()
    }

    /**
     * Signed-challenge check: send a random challenge; the caller must return a
     * signature over it that verifies against [recipientId]. Proves ownership of
     * the recipient identity key without revealing it.
     */
    private fun authenticate(socket: Socket, input: DataInputStream, recipientId: ByteArray): Boolean {
        val output = Wire.output(socket.getOutputStream())
        val challenge = ByteArray(32).also { random.nextBytes(it) }
        Wire.writeFrame(output, challenge)
        output.flush()
        val signature = Wire.readFrame(input)
        return try {
            ECPublicKey(recipientId).verifySignature(challenge, signature)
        } catch (e: Exception) {
            false
        }
    }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
