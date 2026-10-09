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

    /** Caps so an open relay can't be flooded off a device.*/
    private val maxPerRecipient = 500
    private val maxTotal = 20_000

    @Synchronized
    fun deposit(recipientHex: String, envelope: Wire.Envelope): Boolean {
        if (count(recipientHex) >= maxPerRecipient || total() >= maxTotal) return false
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

    private fun total(): Int {
        db.database.rawQuery("SELECT COUNT(*) FROM mailbox", null as Array<String>?)
            .use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Envelopes held for [recipientHex], WITHOUT deleting (for the streaming path). */
    @Synchronized
    fun peekAll(recipientHex: String): List<Pair<Long, Wire.Envelope>> {
        val list = mutableListOf<Pair<Long, Wire.Envelope>>()
        db.database.rawQuery(
            "SELECT id, sender, type, body FROM mailbox WHERE recipient=? ORDER BY id",
            arrayOf(recipientHex),
        ).use { c ->
            while (c.moveToNext()) {
                list.add(c.getLong(0) to Wire.Envelope(c.getBlob(1), c.getInt(2), c.getBlob(3)))
            }
        }
        return list
    }

    /** Delete one held message by id (after it has been delivered). */
    @Synchronized
    fun deleteById(id: Long) {
        db.database.execSQL("DELETE FROM mailbox WHERE id=?", arrayOf(id.toString()))
    }

    /** Read and remove every envelope held for [recipientHex] (atomic). */
    @Synchronized
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
