package org.legend.legendmessage.app

import android.content.Context
import android.content.Intent
import org.legend.legendmessage.MainActivity
import org.legend.legendmessage.tor.TorForegroundService
import java.io.File

/**
 * Irreversibly destroys everything secret on this device: the identity key,
 * all Signal protocol state, pairing cards, contacts, the app lock, and the
 * encrypted message history. Used by the user-triggered "panic wipe" and by a
 * duress unlock. There is no recovery afterwards except restoring a backup.
 *
 * The order matters: stop the Tor service first (so nothing keeps the onion
 * key file open), then delete at-rest stores, then the keyed message database,
 * then plaintext prefs.
 */
object AppWipe {
    fun wipe(context: Context) {
        val app = context.applicationContext
        runCatching { TorForegroundService.stop(app) }
        // Keystore-encrypted secret set (identity, prekeys, onion key, contacts, cards, lock).
        runCatching { File(app.filesDir, "secretstore").deleteRecursively() }
        // SQLCipher message history (db + -wal/-shm siblings).
        runCatching { app.getDatabasePath("messages.db").parentFile?.listFiles()?.forEach { it.delete() } }
        // Plaintext prefs (display name, onion address, mailbox config).
        runCatching { app.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().clear().apply() }
        // Any debug self-test scratch files, just in case.
        runCatching {
            (app.filesDir.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("selftest_") || it.name == "last_crash.txt" }
                .forEach { it.deleteRecursively() }
        }
    }

    /** Wipe and relaunch into a clean first-run state. */
    fun wipeAndRestart(context: Context) {
        wipe(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
