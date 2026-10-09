package org.legend.legendmessage.data

import android.content.ContentValues
import java.util.concurrent.ConcurrentHashMap

/** CRUD over the encrypted [MessageDb]. */
class MessageStore(private val db: MessageDb) {

    // Pending outgoing plaintext is kept here, in memory only, and never written
    // to disk while the message is undelivered — it is persisted to the DB (as
    // encrypted-at-rest history) only once delivery is confirmed in markSent().
    // This minimizes the plaintext forensic footprint of in-flight messages.
    // Trade-off: a message composed while offline and lost to an app kill before
    // delivery will not show its text after restart.
    private val pendingPlaintext = ConcurrentHashMap<Long, String>()

    /** Store an outgoing message with its already-encrypted ciphertext, pending delivery. */
    fun insertOutgoing(peerHex: String, plaintext: String, cipherType: Int, cipherBody: ByteArray): Long {
        val values = ContentValues().apply {
            put("peer", peerHex)
            put("outgoing", 1)
            put("body", "") // plaintext is NOT persisted while pending
            put("ts", System.currentTimeMillis())
            put("pending", 1)
            put("ctype", cipherType)
            put("cbody", cipherBody)
        }
        val id = db.database.insert("messages", null, values)
        pendingPlaintext[id] = plaintext
        return id
    }

    /** Store a received, already-decrypted message. */
    fun insertIncoming(peerHex: String, plaintext: String) {
        val values = ContentValues().apply {
            put("peer", peerHex)
            put("outgoing", 0)
            put("body", plaintext)
            put("ts", System.currentTimeMillis())
            put("pending", 0)
        }
        db.database.insert("messages", null, values)
    }

    fun markSent(id: Long) {
        val values = ContentValues().apply {
            put("pending", 0)
            putNull("cbody")
            // Now that delivery is confirmed, persist the plaintext as history.
            pendingPlaintext.remove(id)?.let { put("body", it) }
        }
        db.database.update("messages", values, "id=?", arrayOf(id.toString()))
    }

    fun pendingOutgoing(): List<PendingOutgoing> {
        val list = mutableListOf<PendingOutgoing>()
        db.database.rawQuery(
            "SELECT id, peer, ctype, cbody FROM messages WHERE outgoing=1 AND pending=1 ORDER BY ts",
            null as Array<String>?,
        ).use { c ->
            while (c.moveToNext()) {
                val body = c.getBlob(3) ?: continue
                list.add(PendingOutgoing(c.getLong(0), c.getString(1), c.getInt(2), body))
            }
        }
        return list
    }

    fun history(peerHex: String): List<Message> {
        val list = mutableListOf<Message>()
        db.database.rawQuery(
            "SELECT id, outgoing, body, ts, pending FROM messages WHERE peer=? ORDER BY ts",
            arrayOf(peerHex),
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val pending = c.getInt(4) == 1
                val stored = c.getString(2)
                // For a still-pending outgoing message the plaintext lives only
                // in memory; fall back to it for display this session.
                val body = if (stored.isNullOrEmpty() && pending) {
                    pendingPlaintext[id] ?: ""
                } else {
                    stored ?: ""
                }
                list.add(
                    Message(
                        id = id,
                        peerHex = peerHex,
                        outgoing = c.getInt(1) == 1,
                        body = body,
                        timestamp = c.getLong(3),
                        pending = pending,
                    ),
                )
            }
        }
        return list
    }
}
