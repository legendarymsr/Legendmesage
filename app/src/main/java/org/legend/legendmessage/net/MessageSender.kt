package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.CryptoEngine
import org.legend.legendmessage.data.ContactStore
import org.legend.legendmessage.data.MessageStore
import org.legend.legendmessage.tor.TorConfig
import org.legend.legendmessage.tor.TorState
import java.util.concurrent.Executors

/**
 * Outbound side. A message is encrypted once at enqueue time (advancing the
 * ratchet exactly once) and stored as pending ciphertext; delivery is a
 * separate step that can be retried without re-encrypting. If the peer is
 * offline the message simply stays pending until the next [flush] — the
 * offline queue.
 */
class MessageSender(
    private val contacts: ContactStore,
    private val messages: MessageStore,
    private val crypto: CryptoEngine,
) {
    private val executor = Executors.newSingleThreadExecutor()

    /** Encrypt + persist [text] for [peerHex], then attempt delivery. */
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

    /** Try to deliver everything still pending (called on send and when Tor comes online). */
    fun flush() {
        executor.execute { deliverPending() }
    }

    private fun deliverPending() {
        if (TorState.status != TorState.Status.ON) return
        for (pending in messages.pendingOutgoing()) {
            val contact = contacts.get(pending.peerHex) ?: continue
            if (contact.onionAddress.isBlank()) continue
            try {
                TorState.openThroughTor(contact.onionAddress, TorConfig.VIRTUAL_PORT).use { socket ->
                    socket.soTimeout = 60_000
                    Wire.write(
                        socket.getOutputStream(),
                        crypto.myIdentityKeyBytes(),
                        pending.cipherType,
                        pending.cipherBody,
                    )
                }
                messages.markSent(pending.id)
                MessageBus.notifyChanged(pending.peerHex)
            } catch (e: Exception) {
                Log.i(TAG, "delivery deferred for ${pending.peerHex}: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "MessageSender"
    }
}
