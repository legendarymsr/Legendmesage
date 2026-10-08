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

    /** Human-readable summary of the last delivery attempt, for the Diagnostics screen. */
    @Volatile
    var lastStatus: String = "idle"
        private set

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
        val pending = messages.pendingOutgoing()
        if (TorState.status != TorState.Status.ON) {
            if (pending.isNotEmpty()) lastStatus = "Tor offline — ${pending.size} message(s) queued"
            return
        }
        if (pending.isEmpty()) return
        for (message in pending) {
            val contact = contacts.get(message.peerHex) ?: continue
            val envelope = Wire.Envelope(crypto.myIdentityKeyBytes(), message.cipherType, message.cipherBody)
            var delivered = false
            var reason = "no route (no onion or mailbox for ${contact.displayName})"

            if (contact.onionAddress.isNotBlank()) {
                runCatching { deliverDirect(contact.onionAddress, envelope) }
                    .onSuccess { delivered = true; lastStatus = "Delivered to ${contact.displayName} (direct)" }
                    .onFailure { reason = "direct to ${contact.displayName} failed: ${it.message}" }
            }
            if (!delivered && contact.mailboxAddress.isNotBlank()) {
                val recipientId = CryptoEngine.bytesOfHex(message.peerHex)
                runCatching { MailboxClient.deposit(contact.mailboxAddress, recipientId, envelope) }
                    .onSuccess { delivered = true; lastStatus = "Delivered to ${contact.displayName}'s mailbox" }
                    .onFailure { reason = "mailbox for ${contact.displayName} failed: ${it.message}" }
            }

            if (delivered) {
                messages.markSent(message.id)
                MessageBus.notifyChanged(message.peerHex)
            } else {
                lastStatus = "Queued — $reason"
                Log.i(TAG, lastStatus)
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
