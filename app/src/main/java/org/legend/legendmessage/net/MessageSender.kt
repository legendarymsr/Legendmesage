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

    // First messages to a brand-new contact whose Kyber prekey we don't have
    // yet: held in memory (never written to disk as plaintext) until we fetch
    // their prekey over Tor and can establish the session. Only touched on the
    // single-threaded [executor], so no extra synchronization is needed.
    private val preSession = HashMap<String, MutableList<String>>()

    /** Human-readable summary of the last delivery attempt, for the Diagnostics screen. */
    @Volatile
    var lastStatus: String = "idle"
        private set

    fun send(peerHex: String, text: String) {
        executor.execute {
            if (crypto.cardNeedsKyber(peerHex)) {
                // We paired but don't have their post-quantum prekey yet; hold
                // the message and go fetch it over Tor.
                preSession.getOrPut(peerHex) { mutableListOf() }.add(text)
                establish(peerHex)
                return@execute
            }
            encryptAndQueue(peerHex, text)
            deliverPending()
        }
    }

    fun flush() {
        executor.execute {
            preSession.keys.toList().forEach { establish(it) }
            deliverPending()
        }
    }

    /**
     * Proactively fetch prekeys for freshly paired contacts (so the first send
     * is instant) and flush any first messages held while offline.
     */
    fun healContacts() {
        executor.execute {
            if (TorState.status != TorState.Status.ON) return@execute
            contacts.all().forEach { c ->
                if (crypto.cardNeedsKyber(c.identityHex) && c.onionAddress.isNotBlank()) {
                    runCatching { PreKeyClient.fetchKyber(c.onionAddress) }
                        .getOrNull()?.let { crypto.attachKyber(c.identityHex, it) }
                }
            }
            preSession.keys.toList().forEach { establish(it) }
        }
    }

    private fun encryptAndQueue(peerHex: String, text: String) {
        try {
            val encrypted = crypto.encrypt(peerHex, text.toByteArray(Charsets.UTF_8))
            messages.insertOutgoing(peerHex, text, encrypted.type, encrypted.body)
            MessageBus.notifyChanged(peerHex)
        } catch (e: Exception) {
            Log.w(TAG, "encrypt/enqueue failed: ${e.message}")
        }
    }

    /** Fetch a new contact's Kyber prekey over Tor, then flush any messages held for them. */
    private fun establish(peerHex: String) {
        if (crypto.cardNeedsKyber(peerHex)) {
            if (TorState.status != TorState.Status.ON) {
                lastStatus = "Waiting for Tor to set up encryption"
                return
            }
            val contact = contacts.get(peerHex) ?: return
            val bundle = runCatching { PreKeyClient.fetchKyber(contact.onionAddress) }.getOrNull()
            if (bundle == null) {
                lastStatus = "Couldn't reach ${contact.displayName} to set up encryption — retry when they're online"
                return
            }
            crypto.attachKyber(peerHex, bundle)
        }
        val held = preSession.remove(peerHex) ?: return
        held.forEach { encryptAndQueue(peerHex, it) }
        deliverPending()
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
