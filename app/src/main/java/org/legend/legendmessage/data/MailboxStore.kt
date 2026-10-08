package org.legend.legendmessage.data

import android.content.ContentValues
import org.legend.legendmessage.net.Wire

/**
 * Storage for a device running in mailbox mode: it holds ciphertext envelopes
 * deposited for recipients who were offline, keyed by the recipient's identity
 * hex, until that recipient authenticates and collects them. Everything stored
 * here is opaque ciphertext — the mailbox cannot read message contents.
 */
class MailboxStore(private val db: MessageDb) {

    /** Per-recipient cap to bound storage a mailbox will hold. */
    private val maxPerRecipient = 500

    fun deposit(recipientHex: String, envelope: Wire.Envelope): Boolean {
        if (count(recipientHex) >= maxPerRecipient) return false
        val values = ContentValues().apply {
            put("recipient", recipientHex)
            put("sender", envelope.senderId)
            put("type", envelope.type)
            put("body", envelope.body)
            put("ts", System.currentTimeMillis())
        }
        db.database.insert("mailbox", null, values)
        return true
    }

    private fun count(recipientHex: String): Int {
        db.database.rawQuery(
            "SELECT COUNT(*) FROM mailbox WHERE recipient=?",
            arrayOf(recipientHex),
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Read and remove every envelope held for [recipientHex] (atomic). */
    fun collectAndDelete(recipientHex: String): List<Wire.Envelope> {
        val result = mutableListOf<Wire.Envelope>()
        val ids = mutableListOf<Long>()
        db.database.rawQuery(
            "SELECT id, sender, type, body FROM mailbox WHERE recipient=? ORDER BY id",
            arrayOf(recipientHex),
        ).use { c ->
            while (c.moveToNext()) {
                ids.add(c.getLong(0))
                result.add(Wire.Envelope(c.getBlob(1), c.getInt(2), c.getBlob(3)))
            }
        }
        if (ids.isNotEmpty()) {
            val placeholders = ids.joinToString(",") { "?" }
            db.database.execSQL(
                "DELETE FROM mailbox WHERE id IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray(),
            )
        }
        return result
    }
}
