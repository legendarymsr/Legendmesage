package org.legend.legendmessage.crypto

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-protected export/import of the whole at-rest secret set — identity
 * key, prekeys, onion key, contacts, and pairing cards — so the user can move
 * their identity to a new device. The backup is NOT wrapped by the Keystore
 * (that key never leaves the device); instead it is encrypted with a key
 * derived from the user's passphrase (PBKDF2) so it is portable.
 *
 * Message history (the SQLCipher database) is deliberately not included.
 */
class BackupManager(
    private val secretStore: SecretStore,
    private val identity: IdentityManager,
) {
    fun export(passphrase: CharArray): ByteArray {
        val secrets = JSONObject()
        for (name in secretStore.allKeys()) {
            // The app lock is device-local; don't carry it into a restore.
            if (name.startsWith(LockManager.KEY_PREFIX)) continue
            val value = secretStore.get(name) ?: continue
            secrets.put(name, Base64.encodeToString(value, Base64.NO_WRAP))
        }
        val prefs = JSONObject()
            .put("reg", identity.registrationId)
            .put("name", identity.displayName)
            .put("onion", identity.onionAddress)
        val payload = JSONObject()
            .put("v", 1)
            .put("secrets", secrets)
            .put("prefs", prefs)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS))
        val iv = cipher.iv
        val body = cipher.doFinal(payload)

        // LMBK2 is self-describing: it records the PBKDF2 iteration count so the
        // count can be raised in future builds without breaking older backups.
        return ByteBuffer.allocate(MAGIC2.size + 4 + salt.size + 1 + iv.size + body.size)
            .put(MAGIC2)
            .putInt(ITERATIONS)
            .put(salt)
            .put(iv.size.toByte())
            .put(iv)
            .put(body)
            .array()
    }

    fun import(blob: ByteArray, passphrase: CharArray) {
        val buffer = ByteBuffer.wrap(blob)
        val magic = ByteArray(MAGIC2.size).also { buffer.get(it) }
        val iterations = when {
            magic.contentEquals(MAGIC2) -> buffer.getInt()
            magic.contentEquals(MAGIC1) -> LEGACY_ITERATIONS // older fixed-count backup
            else -> throw IllegalArgumentException("not a LegendMessage backup")
        }
        val salt = ByteArray(16).also { buffer.get(it) }
        val ivLen = buffer.get().toInt() and 0xFF
        val iv = ByteArray(ivLen).also { buffer.get(it) }
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))

        val secrets = json.getJSONObject("secrets")
        for (name in secrets.keys()) {
            secretStore.put(name, Base64.decode(secrets.getString(name), Base64.NO_WRAP))
        }
        val prefs = json.getJSONObject("prefs")
        identity.importPrefs(prefs.getInt("reg"), prefs.getString("name"), prefs.getString("onion"))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        // PBKDF2-HMAC-SHA256. A real Argon2id KDF would resist GPU cracking
        // better but needs a native dependency; this is the strongest portable
        // choice from the platform JCA.
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            // Don't leave the passphrase sitting in the spec's internal copy.
            spec.clearPassword()
        }
    }

    companion object {
        // 600k PBKDF2 iterations (OWASP 2023 floor), up from the original 120k.
        private const val ITERATIONS = 600_000
        private const val LEGACY_ITERATIONS = 120_000
        private val MAGIC1 = "LMBK1".toByteArray(Charsets.US_ASCII)
        private val MAGIC2 = "LMBK2".toByteArray(Charsets.US_ASCII)
    }
}
