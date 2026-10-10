package org.legend.legendmessage.crypto

import org.legend.legendmessage.data.Contact
import org.legend.legendmessage.data.ContactStore
import org.legend.legendmessage.pairing.ContactCard
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UsePqRatchet
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * The high-level crypto surface the rest of the app uses. It wraps libsignal:
 * generating our prekeys, turning them into a shareable [ContactCard], turning
 * a scanned card into an established session (X3DH + PQXDH), and
 * encrypting/decrypting messages with the Double Ratchet.
 */
class CryptoEngine(
    private val identity: IdentityManager,
    private val store: SignalStore,
    private val contacts: ContactStore,
) {
    private val localDeviceId = 1
    private val signedPreKeyId = 1
    private val kyberPreKeyId = 1

    // libsignal session state is not safe to advance concurrently. All ratchet
    // operations — encrypt (send), decrypt (receive), and session setup — are
    // serialized on this lock, so a simultaneous outbox flush and mailbox
    // delivery for the same peer cannot desync the Double Ratchet.
    private val ratchetLock = Any()

    /** Ensure our signed + Kyber prekeys exist (idempotent; call after identity creation). */
    fun ensurePreKeys() {
        if (!store.containsSignedPreKey(signedPreKeyId)) {
            val keyPair = ECKeyPair.generate()
            val signature = identity.identityKeyPair().privateKey
                .calculateSignature(keyPair.publicKey.serialize())
            store.storeSignedPreKey(
                signedPreKeyId,
                SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature),
            )
        }
        if (!store.containsKyberPreKey(kyberPreKeyId)) {
            val kemKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identity.identityKeyPair().privateKey
                .calculateSignature(kemKeyPair.publicKey.serialize())
            store.storeKyberPreKey(
                kyberPreKeyId,
                KyberPreKeyRecord(kyberPreKeyId, System.currentTimeMillis(), kemKeyPair, signature),
            )
        }
    }

    /**
     * Build our own contact card, embedding [onionAddress] (may be empty before
     * Tor is up). [clientAuthPub] is our Tor client-auth public key, included
     * only when the experimental feature is on (empty otherwise).
     *
     * The ~1.5 KB post-quantum Kyber prekey is deliberately LEFT OUT (empty
     * slot) so the QR stays small and scannable; the peer fetches it over Tor
     * at first contact (see [myKyberBundle] / OP_GET_PREKEY).
     */
    fun myCard(onionAddress: String, clientAuthPub: ByteArray = ByteArray(0)): ContactCard {
        ensurePreKeys()
        val signed = store.loadSignedPreKey(signedPreKeyId)
        return ContactCard(
            displayName = identity.displayName,
            registrationId = identity.registrationId,
            identityKey = identity.identityKey().serialize(),
            signedPreKeyId = signed.id,
            signedPreKey = signed.keyPair.publicKey.serialize(),
            signedPreKeySignature = signed.signature,
            kyberPreKeyId = 0,
            kyberPreKey = ByteArray(0),
            kyberPreKeySignature = ByteArray(0),
            onionAddress = onionAddress,
            mailboxAddress = identity.mailboxAddress,
            clientAuthPub = clientAuthPub,
        )
    }

    /** Our current Kyber prekey, served over Tor so a new peer can complete our card. */
    fun myKyberBundle(): KyberBundle {
        ensurePreKeys()
        val kyber = store.loadKyberPreKey(kyberPreKeyId)
        return KyberBundle(kyber.id, kyber.keyPair.publicKey.serialize(), kyber.signature)
    }

    /** True once we've paired with [identityHex] but still lack their Kyber prekey. */
    fun cardNeedsKyber(identityHex: String): Boolean {
        if (hasSession(identityHex)) return false
        val card = peerCard(identityHex) ?: return false
        return card.kyberPreKey.isEmpty()
    }

    /** Fill in a peer's fetched Kyber prekey, completing their stored card. */
    fun attachKyber(identityHex: String, bundle: KyberBundle) = synchronized(ratchetLock) {
        val card = peerCard(identityHex) ?: return@synchronized
        val completed = card.copy(
            kyberPreKeyId = bundle.id,
            kyberPreKey = bundle.key,
            kyberPreKeySignature = bundle.signature,
        )
        store.rawPut("card_$identityHex", completed.encode().toByteArray(Charsets.UTF_8))
    }

    /** A paired peer's stored card, or null if we have none. */
    fun peerCard(identityHex: String): ContactCard? {
        val cardBytes = store.rawGet("card_$identityHex") ?: return null
        return runCatching { ContactCard.decode(String(cardBytes, Charsets.UTF_8)) }.getOrNull()
    }

    /** Our own identity public key bytes, sent on the wire so a peer can pick the session. */
    fun myIdentityKeyBytes(): ByteArray = identity.identityKey().serialize()

    /**
     * Pair with a scanned card: save the contact and keep the card so we can
     * build the session the first time we send. We deliberately do NOT run
     * X3DH at scan time — if both peers initiated at pairing, they would derive
     * two different sessions. Instead the first sender initiates and the other
     * side establishes its session by receiving that first (PreKey) message.
     */
    fun addContact(card: ContactCard): Contact = synchronized(ratchetLock) {
        val hex = hexOf(card.identityKey)
        store.rawPut("card_$hex", card.encode().toByteArray(Charsets.UTF_8))

        // Re-pairing with the same identity key (same hex) keeps a prior
        // verification — the safety number is derived from the keys, which are
        // unchanged. A different key is a different hex, hence a new contact.
        val existing = contacts.get(hex)
        val contact = Contact(
            identityHex = hex,
            displayName = card.displayName.ifBlank { "Unknown" },
            onionAddress = card.onionAddress,
            mailboxAddress = card.mailboxAddress,
            registrationId = card.registrationId,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            verified = existing?.verified ?: false,
        )
        contacts.upsert(contact)
        contact
    }

    fun hasSession(identityHex: String): Boolean =
        store.containsSession(SignalProtocolAddress(identityHex, localDeviceId))

    /** Establish a session from the stored card if we don't have one yet (as initiator). */
    private fun ensureSession(identityHex: String) {
        if (hasSession(identityHex)) return
        val cardBytes = store.rawGet("card_$identityHex")
            ?: throw IllegalStateException("no pairing card for $identityHex; re-pair to start a session")
        val card = ContactCard.decode(String(cardBytes, Charsets.UTF_8))
        if (card.kyberPreKey.isEmpty()) {
            // The Kyber prekey isn't in the QR anymore; it must be fetched over
            // Tor from the peer first (MessageSender heals the card before this).
            throw IllegalStateException("peer prekey not fetched yet for $identityHex")
        }
        val bundle = PreKeyBundle(
            card.registrationId,
            localDeviceId,
            PreKeyBundle.NULL_PRE_KEY_ID,
            null,
            card.signedPreKeyId,
            ECPublicKey(card.signedPreKey),
            card.signedPreKeySignature,
            IdentityKey(card.identityKey, 0),
            card.kyberPreKeyId,
            KEMPublicKey(card.kyberPreKey),
            card.kyberPreKeySignature,
        )
        SessionBuilder(store, SignalProtocolAddress(identityHex, localDeviceId))
            .process(bundle, UsePqRatchet.YES)
    }

    /** Encrypt [plaintext] for a contact, returning the ciphertext type and bytes. */
    fun encrypt(identityHex: String, plaintext: ByteArray): EncryptedMessage =
        synchronized(ratchetLock) {
            ensureSession(identityHex)
            val address = SignalProtocolAddress(identityHex, localDeviceId)
            val message: CiphertextMessage = SessionCipher(store, address).encrypt(plaintext)
            EncryptedMessage(message.type, message.serialize())
        }

    /** Decrypt a ciphertext previously produced by [encrypt] on the peer side. */
    fun decrypt(identityHex: String, type: Int, body: ByteArray): ByteArray =
        synchronized(ratchetLock) {
            val address = SignalProtocolAddress(identityHex, localDeviceId)
            val cipher = SessionCipher(store, address)
            when (type) {
                CiphertextMessage.PREKEY_TYPE ->
                    cipher.decrypt(PreKeySignalMessage(body), UsePqRatchet.YES)
                else -> cipher.decrypt(SignalMessage(body))
            }
        }

    private fun addressFor(identityKey: ByteArray) =
        SignalProtocolAddress(hexOf(identityKey), localDeviceId)

    /** The peer's identity public key, from the pinned session or the stored card. */
    fun peerIdentityKey(identityHex: String): ByteArray? {
        store.getIdentity(SignalProtocolAddress(identityHex, localDeviceId))?.let { return it.serialize() }
        val cardBytes = store.rawGet("card_$identityHex") ?: return null
        return ContactCard.decode(String(cardBytes, Charsets.UTF_8)).identityKey
    }

    /**
     * A symmetric safety number for a pair: both parties compute the same digits
     * from the two identity keys, so reading them aloud confirms there is no
     * machine-in-the-middle. (Order-independent by sorting the two keys.)
     */
    fun safetyNumber(identityHex: String): String? {
        val mine = identity.identityKey().serialize()
        val theirs = peerIdentityKey(identityHex) ?: return null
        val ordered = listOf(mine, theirs).sortedBy { hexOf(it) }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ordered[0] + ordered[1])
        val groups = (0 until 10).map { i ->
            val chunk = ((digest[i * 3].toInt() and 0xFF) shl 16) or
                ((digest[i * 3 + 1].toInt() and 0xFF) shl 8) or
                (digest[i * 3 + 2].toInt() and 0xFF)
            "%05d".format(chunk % 100000)
        }
        return groups.joinToString(" ")
    }

    companion object {
        fun hexOf(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        fun bytesOfHex(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
    }
}

/** A peer's Kyber prekey, fetched over Tor to complete their pairing card. */
data class KyberBundle(val id: Int, val key: ByteArray, val signature: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KyberBundle) return false
        return id == other.id && key.contentEquals(other.key) && signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int = 31 * (31 * id + key.contentHashCode()) + signature.contentHashCode()
}

/** A serialized ciphertext: its libsignal [type] (PREKEY_TYPE / WHISPER_TYPE) and [body]. */
data class EncryptedMessage(val type: Int, val body: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedMessage) return false
        return type == other.type && body.contentEquals(other.body)
    }

    override fun hashCode(): Int = 31 * type + body.contentHashCode()
}
