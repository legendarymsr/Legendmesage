package org.legend.legendmessage.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * The complete Signal protocol state store, persisted through [SecretStore]
 * (so every record is Keystore-encrypted at rest). libsignal drives all of
 * X3DH/PQXDH and the Double Ratchet through this interface; we just give it
 * durable, encrypted storage.
 *
 * Identity trust is trust-on-first-use: the first identity key we see for a
 * peer is pinned, and a later mismatch is treated as untrusted (a key change
 * the user must re-verify).
 */
class SignalStore(
    private val store: SecretStore,
    private val identity: IdentityManager,
) : SignalProtocolStore {

    // ---- raw passthrough for app-level blobs (e.g. stored pairing cards) ----

    fun rawPut(name: String, value: ByteArray) = store.put(name, value)

    fun rawGet(name: String): ByteArray? = store.get(name)

    // ---- IdentityKeyStore ----

    override fun getIdentityKeyPair(): IdentityKeyPair = identity.identityKeyPair()

    override fun getLocalRegistrationId(): Int = identity.registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val key = "ident_${address.name}"
        val existing = store.get(key)
        val incoming = identityKey.serialize()
        store.put(key, incoming)
        return if (existing != null && !existing.contentEquals(incoming)) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val existing = store.get("ident_${address.name}") ?: return true // TOFU
        return existing.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        store.get("ident_${address.name}")?.let { IdentityKey(it, 0) }

    // ---- PreKeyStore (one-time prekeys; not advertised in cards, but supported) ----

    @Throws(InvalidKeyIdException::class)
    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val bytes = store.get("opk_$preKeyId") ?: throw InvalidKeyIdException("no prekey $preKeyId")
        return PreKeyRecord(bytes)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        store.put("opk_$preKeyId", record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean = store.contains("opk_$preKeyId")

    override fun removePreKey(preKeyId: Int) {
        store.delete("opk_$preKeyId")
    }

    // ---- SignedPreKeyStore ----

    @Throws(InvalidKeyIdException::class)
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val bytes = store.get("spk_$signedPreKeyId")
            ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")
        return SignedPreKeyRecord(bytes)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        store.keysWithPrefix("spk_").mapNotNull { name ->
            store.get(name)?.let { SignedPreKeyRecord(it) }
        }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        store.put("spk_$signedPreKeyId", record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        store.contains("spk_$signedPreKeyId")

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        store.delete("spk_$signedPreKeyId")
    }

    // ---- KyberPreKeyStore ----

    @Throws(InvalidKeyIdException::class)
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val bytes = store.get("kpk_$kyberPreKeyId")
            ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")
        return KyberPreKeyRecord(bytes)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        store.keysWithPrefix("kpk_").mapNotNull { name ->
            store.get(name)?.let { KyberPreKeyRecord(it) }
        }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        store.put("kpk_$kyberPreKeyId", record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        store.contains("kpk_$kyberPreKeyId")

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        // Last-resort kyber prekeys are reusable; nothing to do.
    }

    // ---- SessionStore ----

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val bytes = store.get(sessionKey(address)) ?: return SessionRecord()
        return SessionRecord(bytes)
    }

    @Throws(NoSessionException::class)
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { address ->
            val bytes = store.get(sessionKey(address))
                ?: throw NoSessionException("no session for ${address.name}")
            SessionRecord(bytes)
        }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        store.put(sessionKey(address), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        store.contains(sessionKey(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        store.delete(sessionKey(address))
    }

    override fun deleteAllSessions(name: String) {
        store.keysWithPrefix("sess_${name}_").forEach { store.delete(it) }
    }

    private fun sessionKey(address: SignalProtocolAddress) =
        "sess_${address.name}_${address.deviceId}"

    // ---- SenderKeyStore (group messaging; implemented for completeness, unused) ----

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: java.util.UUID,
        record: SenderKeyRecord,
    ) {
        store.put(senderKey(sender, distributionId), record.serialize())
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: java.util.UUID,
    ): SenderKeyRecord? =
        store.get(senderKey(sender, distributionId))?.let { SenderKeyRecord(it) }

    private fun senderKey(sender: SignalProtocolAddress, distributionId: java.util.UUID) =
        "senderkey_${sender.name}_${sender.deviceId}_$distributionId"
}
