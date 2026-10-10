package org.legend.legendmessage.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app-lock secret: a PIN verifier (PBKDF2 hash + salt) kept in the
 * Keystore-encrypted [SecretStore], plus a flag for biometric unlock.
 *
 * This gates access to the UI and message history only. It deliberately does
 * NOT derive the message-database key — that stays wrapped by the Android
 * Keystore so the background Tor service can keep receiving while the UI is
 * locked. The lock stops someone who picks up an unlocked phone from opening
 * the app; it is not a defense against a forensic dump of a running process.
 */
class LockManager(private val store: SecretStore) {

    fun hasPin(): Boolean = store.contains(KEY_HASH) && store.contains(KEY_SALT)

    /** Set (or replace) the unlock PIN. The caller should zero [pin] afterwards. */
    fun setPin(pin: CharArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.put(KEY_SALT, salt)
        store.put(KEY_HASH, hash(pin, salt))
    }

    /** Constant-time check of [pin] against the stored verifier. */
    fun verify(pin: CharArray): Boolean {
        val salt = store.get(KEY_SALT) ?: return false
        val expected = store.get(KEY_HASH) ?: return false
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    fun clear() {
        store.delete(KEY_HASH)
        store.delete(KEY_SALT)
        store.delete(KEY_BIOMETRIC)
    }

    var biometricEnabled: Boolean
        get() = store.get(KEY_BIOMETRIC)?.firstOrNull() == 1.toByte()
        set(value) = store.put(KEY_BIOMETRIC, byteArrayOf(if (value) 1 else 0))

    private fun hash(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val KEY_PREFIX = "lock_"
        private const val KEY_HASH = "lock_hash"
        private const val KEY_SALT = "lock_salt"
        private const val KEY_BIOMETRIC = "lock_biometric"
        private const val ITERATIONS = 200_000
    }
}
