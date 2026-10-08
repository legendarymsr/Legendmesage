package org.legend.legendmessage.data

import android.content.ContentValues

/** CRUD over the encrypted [MessageDb]. */
class MessageStore(private val db: MessageDb) {

    /** Store an outgoing message with its already-encrypted ciphertext, pending delivery. */
    fun insertOutgoing(peerHex: String, plaintext: String, cipherType: Int, cipherBody: ByteArray): Long {
        val values = ContentValues().apply {
            put("peer", peerHex)
            put("outgoing", 1)
            put("body", plaintext)
            put("ts", System.currentTimeMillis())
            put("pending", 1)
            put("ctype", cipherType)
            put("cbody", cipherBody)
        }
        return db.database.insert("messages", null, values)
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
                list.add(
                    Message(
                        id = c.getLong(0),
                        peerHex = peerHex,
                        outgoing = c.getInt(1) == 1,
                        body = c.getString(2),
                        timestamp = c.getLong(3),
                        pending = c.getInt(4) == 1,
                    ),
                )
            }
        }
        return list
    }
}
