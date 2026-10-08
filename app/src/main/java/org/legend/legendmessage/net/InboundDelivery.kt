package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.CryptoEngine
import org.legend.legendmessage.data.ContactStore
import org.legend.legendmessage.data.MessageStore

/**
 * Shared "a ciphertext arrived for me" path, used both by direct delivery
 * ([PeerServer]) and by mailbox pickups ([MailboxPoller]): identify the sender,
 * decrypt against their session, and store the plaintext.
 */
class InboundDelivery(
    private val contacts: ContactStore,
    private val messages: MessageStore,
    private val crypto: CryptoEngine,
) {
    /** Returns true if the envelope was from a known contact and was stored. */
    fun deliver(envelope: Wire.Envelope): Boolean {
        val senderHex = CryptoEngine.hexOf(envelope.senderId)
        if (contacts.get(senderHex) == null) {
            Log.w(TAG, "dropping message from unknown sender $senderHex")
            return false
        }
        return try {
            val plaintext = crypto.decrypt(senderHex, envelope.type, envelope.body)
            messages.insertIncoming(senderHex, String(plaintext, Charsets.UTF_8))
            MessageBus.notifyChanged(senderHex)
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to decrypt/store from $senderHex: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "InboundDelivery"
    }
}
