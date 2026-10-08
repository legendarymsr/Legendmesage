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

    /** Build our own contact card, embedding [onionAddress] (may be empty before Tor is up). */
    fun myCard(onionAddress: String): ContactCard {
        ensurePreKeys()
        val signed = store.loadSignedPreKey(signedPreKeyId)
        val kyber = store.loadKyberPreKey(kyberPreKeyId)
        return ContactCard(
            displayName = identity.displayName,
            registrationId = identity.registrationId,
            identityKey = identity.identityKey().serialize(),
            signedPreKeyId = signed.id,
            signedPreKey = signed.keyPair.publicKey.serialize(),
            signedPreKeySignature = signed.signature,
            kyberPreKeyId = kyber.id,
            kyberPreKey = kyber.keyPair.publicKey.serialize(),
            kyberPreKeySignature = kyber.signature,
            onionAddress = onionAddress,
        )
    }

    /**
     * Pair with a scanned card: run X3DH/PQXDH to establish a session and save
     * the contact. Returns the created [Contact].
     */
    fun addContact(card: ContactCard): Contact {
        val identityKey = IdentityKey(card.identityKey, 0)
        val address = addressFor(card.identityKey)

        val bundle = PreKeyBundle(
            card.registrationId,
            localDeviceId,
            PreKeyBundle.NULL_PRE_KEY_ID,
            null,
            card.signedPreKeyId,
            ECPublicKey(card.signedPreKey),
            card.signedPreKeySignature,
            identityKey,
            card.kyberPreKeyId,
            KEMPublicKey(card.kyberPreKey),
            card.kyberPreKeySignature,
        )
        SessionBuilder(store, address).process(bundle, UsePqRatchet.YES)

        val contact = Contact(
            identityHex = hexOf(card.identityKey),
            displayName = card.displayName.ifBlank { "Unknown" },
            onionAddress = card.onionAddress,
            registrationId = card.registrationId,
            addedAt = System.currentTimeMillis(),
        )
        contacts.upsert(contact)
        return contact
    }

    fun hasSession(identityHex: String): Boolean =
        store.containsSession(SignalProtocolAddress(identityHex, localDeviceId))

    /** Encrypt [plaintext] for a contact, returning the ciphertext type and bytes. */
    fun encrypt(identityHex: String, plaintext: ByteArray): EncryptedMessage {
        val address = SignalProtocolAddress(identityHex, localDeviceId)
        val message: CiphertextMessage = SessionCipher(store, address).encrypt(plaintext)
        return EncryptedMessage(message.type, message.serialize())
    }

    /** Decrypt a ciphertext previously produced by [encrypt] on the peer side. */
    fun decrypt(identityHex: String, type: Int, body: ByteArray): ByteArray {
        val address = SignalProtocolAddress(identityHex, localDeviceId)
        val cipher = SessionCipher(store, address)
        return when (type) {
            CiphertextMessage.PREKEY_TYPE ->
                cipher.decrypt(PreKeySignalMessage(body), UsePqRatchet.YES)
            else -> cipher.decrypt(SignalMessage(body))
        }
    }

    private fun addressFor(identityKey: ByteArray) =
        SignalProtocolAddress(hexOf(identityKey), localDeviceId)

    companion object {
        fun hexOf(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }
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
