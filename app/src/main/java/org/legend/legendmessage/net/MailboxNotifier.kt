package org.legend.legendmessage.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Wakes a waiting mailbox subscriber when something is deposited for its
 * recipient, so the server can push promptly instead of the client polling.
 *
 * Uses a guarded flag so a signal that arrives just before a subscriber calls
 * [await] is not lost (the next await returns immediately instead of blocking).
 */
object MailboxNotifier {
    private class Gate {
        var signaled = false
    }

    private val gates = ConcurrentHashMap<String, Gate>()

    private fun gate(recipientHex: String): Gate = gates.computeIfAbsent(recipientHex) { Gate() }

    /** Block up to [timeoutMs] waiting for a deposit for [recipientHex]. */
    fun await(recipientHex: String, timeoutMs: Long) {
        val g = gate(recipientHex)
        synchronized(g) {
            if (!g.signaled) {
                runCatching { (g as java.lang.Object).wait(timeoutMs) }
            }
            g.signaled = false
        }
    }

    /** Wake any subscriber waiting on [recipientHex] (and remember it if none is). */
    fun signal(recipientHex: String) {
        val g = gate(recipientHex)
        synchronized(g) {
            g.signaled = true
            (g as java.lang.Object).notifyAll()
        }
    }
}
