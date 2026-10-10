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
    /**
     * Optional raw 32-byte x25519 public key for Tor onion client
     * authorization. Appended after the original fields so older apps (which
     * stop reading at mailboxAddress) ignore it, and newer apps read it only
     * when present. Empty when the device hasn't enabled/created one.
     */
    val clientAuthPub: ByteArray = ByteArray(0),
) {
    private fun body(): ByteArray =
        CardWriter()
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
            .putBytes(clientAuthPub)
            .toByteArray()

    /** Text form, for sharing as an invite link (copy/paste, messaging apps). */
    fun encode(): String = PREFIX + B64.encode(body())

    /**
     * QR form: base45 so the whole string is QR-alphanumeric, which scans back
     * reliably (unlike a binary byte-mode QR, whose high bytes get corrupted by
     * the scanner's charset guessing) while staying compact.
     */
    fun encodeQr(): String = QR_PREFIX + Base45.encode(body())

    /**
     * Compact binary form, for the QR. The card carries a ~1.5 KB post-quantum
     * key, so encoding the raw bytes (byte mode) instead of base64 keeps the QR
     * ~25% lower-density — enough fewer modules to actually scan.
     */
    fun encodeBinary(): ByteArray = MAGIC + body()

    companion object {
        const val PREFIX = "LMC2:"
        // All chars are in the QR alphanumeric set (uppercase + digits + ':').
        const val QR_PREFIX = "LMC45:"
        private const val VERSION = 2
        private val MAGIC = byteArrayOf('L'.code.toByte(), 'M'.code.toByte())

        fun looksLikeCard(text: String) = text.startsWith(PREFIX) || text.startsWith(QR_PREFIX)

        fun looksBinary(bytes: ByteArray) =
            bytes.size > MAGIC.size && bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1]

        fun decode(text: String): ContactCard {
            val t = text.trim()
            return when {
                t.startsWith(QR_PREFIX) -> parse(CardReader(Base45.decode(t.removePrefix(QR_PREFIX))))
                t.startsWith(PREFIX) -> parse(CardReader(B64.decode(t.removePrefix(PREFIX))))
                else -> throw IllegalArgumentException("not a LegendMessage contact card")
            }
        }

        fun decodeBinary(bytes: ByteArray): ContactCard {
            require(looksBinary(bytes)) { "not a LegendMessage contact card" }
            return parse(CardReader(bytes.copyOfRange(MAGIC.size, bytes.size)))
        }

        private fun parse(r: CardReader): ContactCard {
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
                // Optional, appended field: present only on newer cards.
                clientAuthPub = if (r.hasRemaining()) r.readBytes() else ByteArray(0),
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
