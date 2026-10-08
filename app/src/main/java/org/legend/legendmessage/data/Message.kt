package org.legend.legendmessage.data

/** A chat message row. [body] is plaintext (kept only inside the encrypted DB) for display. */
data class Message(
    val id: Long,
    val peerHex: String,
    val outgoing: Boolean,
    val body: String,
    val timestamp: Long,
    val pending: Boolean,
)

/** A queued outgoing message: its already-ratcheted ciphertext, waiting to be delivered. */
data class PendingOutgoing(
    val id: Long,
    val peerHex: String,
    val cipherType: Int,
    val cipherBody: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingOutgoing) return false
        return id == other.id && peerHex == other.peerHex &&
            cipherType == other.cipherType && cipherBody.contentEquals(other.cipherBody)
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + peerHex.hashCode()
        r = 31 * r + cipherType
        r = 31 * r + cipherBody.contentHashCode()
        return r
    }
}
