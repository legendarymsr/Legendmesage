package org.legend.legendmessage.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed secret wrapping.
 *
 * A single non-exportable AES-256-GCM key lives in the Android Keystore (and,
 * on devices that have it, the hardware TEE/StrongBox). It never leaves the
 * secure element; we only ask it to encrypt/decrypt small blobs. Everything
 * LegendMessage persists — the identity key, prekeys, ratchet sessions, and
 * later the database passphrase — is wrapped by this key, so a stolen phone's
 * raw storage reveals nothing without unlocking the Keystore.
 */
object KeystoreVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "legendmessage.master.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    @Synchronized
    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Encrypt [plain]; output is `[ivLen][iv][ciphertext+tag]`. */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + body.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(body)
            .array()
    }

    /** Reverse of [encrypt]. */
    fun decrypt(blob: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(blob)
        val ivLen = buffer.get().toInt() and 0xFF
        val iv = ByteArray(ivLen).also { buffer.get(it) }
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }
}
