package org.legend.legendmessage.crypto

import android.content.Context
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.MessageDigest

/**
 * Owns this device's long-term cryptographic identity — the thing that stands
 * in for a phone number or an account. It is a Curve25519 [IdentityKeyPair]
 * generated once on first run and wrapped in the Keystore; there is no server
 * registration and nothing that ties it to a real-world identifier.
 */
class IdentityManager(context: Context, private val store: SecretStore) {

    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    fun exists(): Boolean = store.contains(KEY_IDENTITY)

    fun identityKeyPair(): IdentityKeyPair =
        IdentityKeyPair(requireNotNull(store.get(KEY_IDENTITY)) { "identity not created yet" })

    fun identityKeyPairOrNull(): IdentityKeyPair? = store.get(KEY_IDENTITY)?.let { IdentityKeyPair(it) }

    val registrationId: Int get() = prefs.getInt(PREF_REG_ID, 0)

    var displayName: String
        get() = prefs.getString(PREF_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(PREF_NAME, value).apply()
        }

    /** This device's Tor onion address, once published (Stage 3). Empty until then. */
    var onionAddress: String
        get() = prefs.getString(PREF_ONION, "") ?: ""
        set(value) {
            prefs.edit().putString(PREF_ONION, value).apply()
        }

    /** Generate and persist a fresh identity. Safe to call only when [exists] is false. */
    fun create(displayName: String): IdentityKeyPair {
        val keyPair = IdentityKeyPair.generate()
        store.put(KEY_IDENTITY, keyPair.serialize())
        prefs.edit()
            .putInt(PREF_REG_ID, KeyHelper.generateRegistrationId(false))
            .putString(PREF_NAME, displayName)
            .apply()
        return keyPair
    }

    /** The public half of the identity, used in contact cards and fingerprints. */
    fun identityKey(): IdentityKey = identityKeyPair().publicKey

    /**
     * A human-comparable fingerprint of our identity public key: SHA-256 of the
     * serialized key, shown as groups of hex. Two people reading these aloud can
     * confirm they hold each other's real key. (The full per-pair Signal safety
     * number lands with verification in a later stage.)
     */
    fun fingerprint(): String = fingerprintOf(identityKey())

    companion object {
        private const val KEY_IDENTITY = "identity_keypair"
        private const val PREF_REG_ID = "registration_id"
        private const val PREF_NAME = "display_name"
        private const val PREF_ONION = "onion_address"

        fun fingerprintOf(identityKey: IdentityKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(identityKey.serialize())
            val hex = digest.take(16).joinToString("") { "%02X".format(it) }
            return hex.chunked(4).joinToString(" ")
        }
    }
}
