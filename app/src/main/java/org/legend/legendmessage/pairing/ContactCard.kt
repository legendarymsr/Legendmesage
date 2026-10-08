package org.legend.legendmessage.pairing

/**
 * Everything a peer needs to open an encrypted session with us and (once Tor
 * is up) reach us: our registration id, identity public key, a signed prekey,
 * a post-quantum Kyber prekey, and our onion address. This is what the pairing
 * QR encodes. There is deliberately no name-server lookup — the card *is* the
 * directory entry, exchanged directly.
 *
 * One-time prekeys are intentionally omitted (bundle preKeyId = -1): a static
 * QR cannot hand out a fresh one-time prekey per scan without extra state, so
 * first-message forward secrecy rests on the rotating signed prekey plus the
 * Kyber prekey. This is a documented simplification for a personal-use build.
 */
data class ContactCard(
    val displayName: String,
    val registrationId: Int,
    val identityKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray,
    val onionAddress: String,
    val mailboxAddress: String,
) {
    fun encode(): String {
        val body = CardWriter()
            .putByte(VERSION)
            .putString(displayName)
            .putInt(registrationId)
            .putBytes(identityKey)
            .putInt(signedPreKeyId)
            .putBytes(signedPreKey)
            .putBytes(signedPreKeySignature)
            .putInt(kyberPreKeyId)
            .putBytes(kyberPreKey)
            .putBytes(kyberPreKeySignature)
            .putString(onionAddress)
            .putString(mailboxAddress)
            .toByteArray()
        return PREFIX + B64.encode(body)
    }

    companion object {
        const val PREFIX = "LMC2:"
        private const val VERSION = 2

        fun looksLikeCard(text: String) = text.startsWith(PREFIX)

        fun decode(text: String): ContactCard {
            require(text.startsWith(PREFIX)) { "not a LegendMessage contact card" }
            val r = CardReader(B64.decode(text.removePrefix(PREFIX)))
            val version = r.readByte()
            require(version == VERSION) { "unsupported card version $version" }
            return ContactCard(
                displayName = r.readString(),
                registrationId = r.readInt(),
                identityKey = r.readBytes(),
                signedPreKeyId = r.readInt(),
                signedPreKey = r.readBytes(),
                signedPreKeySignature = r.readBytes(),
                kyberPreKeyId = r.readInt(),
                kyberPreKey = r.readBytes(),
                kyberPreKeySignature = r.readBytes(),
                onionAddress = r.readString(),
                mailboxAddress = r.readString(),
            )
        }
    }

    // data class with ByteArray members: provide value-based equals/hashCode.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactCard) return false
        return displayName == other.displayName &&
            registrationId == other.registrationId &&
            identityKey.contentEquals(other.identityKey) &&
            signedPreKeyId == other.signedPreKeyId &&
            signedPreKey.contentEquals(other.signedPreKey) &&
            signedPreKeySignature.contentEquals(other.signedPreKeySignature) &&
            kyberPreKeyId == other.kyberPreKeyId &&
            kyberPreKey.contentEquals(other.kyberPreKey) &&
            kyberPreKeySignature.contentEquals(other.kyberPreKeySignature) &&
            onionAddress == other.onionAddress &&
            mailboxAddress == other.mailboxAddress
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + registrationId
        result = 31 * result + identityKey.contentHashCode()
        result = 31 * result + signedPreKeyId
        result = 31 * result + kyberPreKeyId
        result = 31 * result + onionAddress.hashCode()
        return result
    }
}
