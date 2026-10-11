package org.legend.legendmessage.crypto

import java.nio.ByteBuffer
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

    /**
     * Constant-time check of [pin] against the stored verifier. Refuses
     * (returns false without checking) while a lockout is running; failures
     * are counted persistently so restarting the app doesn't reset them.
     */
    @Synchronized
    fun verify(pin: CharArray): Boolean {
        if (lockoutRemainingMs() > 0) return false
        val salt = store.get(KEY_SALT) ?: return false
        val expected = store.get(KEY_HASH) ?: return false
        val ok = MessageDigest.isEqual(hash(pin, salt), expected)
        if (ok) {
            store.delete(KEY_FAILS)
            store.delete(KEY_LOCKED_UNTIL)
        } else {
            val fails = failures() + 1
            store.put(KEY_FAILS, ByteBuffer.allocate(4).putInt(fails).array())
            if (fails >= FREE_ATTEMPTS) {
                // 30 s, 60 s, 120 s, … capped at 1 h.
                val shift = (fails - FREE_ATTEMPTS).coerceAtMost(7)
                val delay = (30_000L shl shift).coerceAtMost(3_600_000L)
                val until = System.currentTimeMillis() + delay
                store.put(KEY_LOCKED_UNTIL, ByteBuffer.allocate(8).putLong(until).array())
            }
        }
        return ok
    }

    /** Milliseconds until another PIN attempt is allowed (0 = now). */
    fun lockoutRemainingMs(): Long {
        val until = store.get(KEY_LOCKED_UNTIL)?.takeIf { it.size == 8 }?.let { ByteBuffer.wrap(it).long } ?: return 0
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    private fun failures(): Int =
        store.get(KEY_FAILS)?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).int } ?: 0

    fun clear() {
        store.delete(KEY_HASH)
        store.delete(KEY_SALT)
        store.delete(KEY_BIOMETRIC)
        store.delete(KEY_FAILS)
        store.delete(KEY_LOCKED_UNTIL)
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
        private const val KEY_FAILS = "lock_fails"
        private const val KEY_LOCKED_UNTIL = "lock_until"
        private const val FREE_ATTEMPTS = 5
        private const val ITERATIONS = 200_000
    }
}
