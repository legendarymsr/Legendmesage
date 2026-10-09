package org.legend.legendmessage.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Wakes a waiting mailbox subscriber when something is deposited for its
 * recipient, so the server can push promptly instead of the client polling.
 */
object MailboxNotifier {
    private val monitors = ConcurrentHashMap<String, Object>()

    private fun monitor(recipientHex: String): Object =
        monitors.getOrPut(recipientHex) { Object() }

    /** Block up to [timeoutMs] waiting for a deposit for [recipientHex]. */
    fun await(recipientHex: String, timeoutMs: Long) {
        val m = monitor(recipientHex)
        synchronized(m) {
            runCatching { (m as java.lang.Object).wait(timeoutMs) }
        }
    }

    /** Wake any subscriber waiting on [recipientHex]. */
    fun signal(recipientHex: String) {
        val m = monitor(recipientHex)
        synchronized(m) {
            (m as java.lang.Object).notifyAll()
        }
    }
}
