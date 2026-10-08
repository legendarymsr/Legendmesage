package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.CryptoEngine
import org.legend.legendmessage.data.ContactStore
import org.legend.legendmessage.data.MessageStore
import org.legend.legendmessage.tor.TorConfig
import org.legend.legendmessage.tor.TorState
import java.util.concurrent.Executors

/**
 * Outbound side. A message is encrypted once at enqueue (ratchet advances
 * exactly once) and stored as pending ciphertext; delivery is retried without
 * re-encrypting. Delivery tries the peer's onion directly first, then falls
 * back to depositing in the peer's mailbox if they advertise one. If neither
 * works it stays queued — the offline queue — and flushes on the next attempt.
 */
class MessageSender(
    private val contacts: ContactStore,
    private val messages: MessageStore,
    private val crypto: CryptoEngine,
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun send(peerHex: String, text: String) {
        executor.execute {
            try {
                val encrypted = crypto.encrypt(peerHex, text.toByteArray(Charsets.UTF_8))
                messages.insertOutgoing(peerHex, text, encrypted.type, encrypted.body)
                MessageBus.notifyChanged(peerHex)
            } catch (e: Exception) {
                Log.w(TAG, "encrypt/enqueue failed: ${e.message}")
            }
            deliverPending()
        }
    }

    fun flush() {
        executor.execute { deliverPending() }
    }

    private fun deliverPending() {
        if (TorState.status != TorState.Status.ON) return
        for (pending in messages.pendingOutgoing()) {
            val contact = contacts.get(pending.peerHex) ?: continue
            val envelope = Wire.Envelope(crypto.myIdentityKeyBytes(), pending.cipherType, pending.cipherBody)

            var delivered = false
            if (contact.onionAddress.isNotBlank()) {
                delivered = runCatching { deliverDirect(contact.onionAddress, envelope) }
                    .onFailure { Log.i(TAG, "direct delivery deferred: ${it.message}") }
                    .isSuccess
            }
            if (!delivered && contact.mailboxAddress.isNotBlank()) {
                val recipientId = CryptoEngine.bytesOfHex(pending.peerHex)
                delivered = runCatching { MailboxClient.deposit(contact.mailboxAddress, recipientId, envelope) }
                    .onFailure { Log.i(TAG, "mailbox deposit deferred: ${it.message}") }
                    .isSuccess
            }

            if (delivered) {
                messages.markSent(pending.id)
                MessageBus.notifyChanged(pending.peerHex)
            }
        }
    }

    private fun deliverDirect(onion: String, envelope: Wire.Envelope) {
        TorState.openThroughTor(onion, TorConfig.VIRTUAL_PORT).use { socket ->
            socket.soTimeout = 60_000
            val out = Wire.output(socket.getOutputStream())
            out.writeByte(Wire.OP_DIRECT)
            Wire.writeEnvelope(out, envelope)
            out.flush()
        }
    }

    companion object {
        private const val TAG = "MessageSender"
    }
}
