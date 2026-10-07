package org.legend.legendmessage.pairing

import org.signal.libsignal.protocol.IdentityKey

/**
 * Stage-1 minimal shareable identity: just the public identity key and a
 * display name, encoded as a scannable string. Stage 2 introduces the full
 * [ContactCard] (prekeys + onion address) needed to actually start a session;
 * this smaller card is enough to show "here is who I am" and to verify a key.
 */
data class IdentityCard(
    val displayName: String,
    val identityKey: IdentityKey,
) {
    fun encode(): String {
        val body = CardWriter()
            .putByte(VERSION)
            .putString(displayName)
            .putBytes(identityKey.serialize())
            .toByteArray()
        return PREFIX + B64.encode(body)
    }

    companion object {
        private const val PREFIX = "LMID1:"
        private const val VERSION = 1

        fun decode(text: String): IdentityCard {
            require(text.startsWith(PREFIX)) { "not a LegendMessage identity card" }
            val reader = CardReader(B64.decode(text.removePrefix(PREFIX)))
            val version = reader.readByte()
            require(version == VERSION) { "unsupported card version $version" }
            val name = reader.readString()
            val identityKey = IdentityKey(reader.readBytes(), 0)
            return IdentityCard(name, identityKey)
        }
    }
}
