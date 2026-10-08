package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.IdentityManager
import org.legend.legendmessage.tor.TorState
import java.util.concurrent.Executors

/**
 * Pulls our own mail from the mailbox we've configured. We prove ownership of
 * our identity by signing the mailbox's challenge with our identity private
 * key, then deliver each collected envelope through the normal inbound path.
 */
class MailboxPoller(
    private val identity: IdentityManager,
    private val inbound: InboundDelivery,
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun poll() {
        executor.execute { pollNow() }
    }

    private fun pollNow() {
        val mailbox = identity.mailboxAddress
        if (mailbox.isBlank() || TorState.status != TorState.Status.ON) return
        if (mailbox == identity.onionAddress) return // a device doesn't poll itself
        try {
            val envelopes = MailboxClient.collect(
                mailbox,
                identity.identityKey().serialize(),
            ) { challenge -> identity.identityKeyPair().privateKey.calculateSignature(challenge) }
            envelopes.forEach { inbound.deliver(it) }
            if (envelopes.isNotEmpty()) Log.i(TAG, "collected ${envelopes.size} from mailbox")
        } catch (e: Exception) {
            Log.i(TAG, "mailbox poll failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MailboxPoller"
    }
}
