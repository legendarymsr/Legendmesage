package org.legend.legendmessage.crypto

import android.content.Context
import java.io.File

/**
 * A tiny key/value store of Keystore-encrypted blobs, one file per entry under
 * `filesDir/secretstore/`. It backs the Signal protocol stores (identity,
 * prekeys, sessions): the volume is small — a handful of keys and sessions —
 * so files are plenty, and every byte on disk is wrapped by [KeystoreVault].
 */
class SecretStore(context: Context, name: String = "secretstore") {
    private val dir = File(context.filesDir, name).apply { mkdirs() }

    private fun fileFor(name: String) = File(dir, sanitize(name) + ".bin")

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun put(name: String, value: ByteArray) {
        val tmp = File(dir, sanitize(name) + ".tmp")
        tmp.writeBytes(KeystoreVault.encrypt(value))
        if (!tmp.renameTo(fileFor(name))) {
            tmp.copyTo(fileFor(name), overwrite = true)
            tmp.delete()
        }
    }

    fun get(name: String): ByteArray? {
        val file = fileFor(name)
        return if (file.exists()) KeystoreVault.decrypt(file.readBytes()) else null
    }

    fun contains(name: String): Boolean = fileFor(name).exists()

    fun delete(name: String) {
        fileFor(name).delete()
    }

    /** Names (original, not sanitized is not recoverable) of entries whose sanitized name starts with [prefix]. */
    fun keysWithPrefix(prefix: String): List<String> =
        (dir.listFiles() ?: emptyArray())
            .map { it.name.removeSuffix(".bin") }
            .filter { it.startsWith(sanitize(prefix)) }

    /** All stored entry names (used by backup). */
    fun allKeys(): List<String> =
        (dir.listFiles() ?: emptyArray()).map { it.name.removeSuffix(".bin") }
}
