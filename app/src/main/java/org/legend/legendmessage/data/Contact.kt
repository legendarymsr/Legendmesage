package org.legend.legendmessage.data

/**
 * A paired peer. The [identityHex] (hex of their identity public key) is the
 * stable address we key sessions and messages by; [onionAddress] is where we
 * reach them over Tor once that lands.
 */
data class Contact(
    val identityHex: String,
    val displayName: String,
    val onionAddress: String,
    val mailboxAddress: String,
    val registrationId: Int,
    val addedAt: Long,
    /**
     * True once the user has compared the safety number out-of-band and marked
     * this contact verified. Until then the chat shows an "unverified" banner,
     * and verified-only send mode (if enabled) refuses to message them.
     */
    val verified: Boolean = false,
    /**
     * Disappearing-messages timer for this conversation, in seconds. 0 = off.
     * Messages older than this (by send/receive time) are deleted from local
     * history. It is a local retention policy, not enforced on the peer.
     */
    val disappearingSeconds: Int = 0,
)
