package org.legend.legendmessage.data

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.legend.legendmessage.crypto.SecretStore
import java.security.SecureRandom

/**
 * The SQLCipher-encrypted message database. The 32-byte passphrase is random,
 * generated once, and itself wrapped by the Android Keystore via [SecretStore]
 * — so the on-disk database is encrypted and its key never lives in plaintext.
 */
class MessageDb(private val context: Context, private val secretStore: SecretStore) {

    val database: SQLiteDatabase by lazy { open() }

    private fun open(): SQLiteDatabase {
        runCatching { System.loadLibrary("sqlcipher") }
        val file = context.getDatabasePath("messages.db")
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, passphrase(), null, null)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                body TEXT NOT NULL,
                ts INTEGER NOT NULL,
                pending INTEGER NOT NULL DEFAULT 0,
                ctype INTEGER,
                cbody BLOB
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_peer_ts ON messages(peer, ts)")
        // When this device acts as a mailbox, it stores ciphertext deposited for
        // other recipients until they collect it. Content is opaque ciphertext.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mailbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recipient TEXT NOT NULL,
                sender BLOB NOT NULL,
                type INTEGER NOT NULL,
                body BLOB NOT NULL,
                ts INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mailbox_recipient ON mailbox(recipient, id)")
        return db
    }

    private fun passphrase(): ByteArray {
        secretStore.get(KEY)?.let { return it }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        secretStore.put(KEY, key)
        return key
    }

    companion object {
        private const val KEY = "db_passphrase"
    }
}
