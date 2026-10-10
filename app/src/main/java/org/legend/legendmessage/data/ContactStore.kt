package org.legend.legendmessage.data

import org.json.JSONArray
import org.json.JSONObject
import org.legend.legendmessage.crypto.SecretStore

/**
 * The address book. Stored as a single Keystore-encrypted JSON blob via
 * [SecretStore]; small enough that we rewrite the whole list on each change.
 */
class ContactStore(private val store: SecretStore) {
    private val key = "contacts"

    @Synchronized
    fun all(): List<Contact> {
        val bytes = store.get(key) ?: return emptyList()
        val array = JSONArray(String(bytes, Charsets.UTF_8))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Contact(
                identityHex = o.getString("id"),
                displayName = o.getString("name"),
                onionAddress = o.optString("onion", ""),
                mailboxAddress = o.optString("mailbox", ""),
                registrationId = o.optInt("reg", 0),
                addedAt = o.optLong("at", 0L),
                verified = o.optBoolean("verified", false),
            )
        }
    }

    fun get(identityHex: String): Contact? = all().firstOrNull { it.identityHex == identityHex }

    @Synchronized
    fun upsert(contact: Contact) {
        val current = all().filter { it.identityHex != contact.identityHex } + contact
        persist(current)
    }

    @Synchronized
    fun setVerified(identityHex: String, verified: Boolean) {
        persist(all().map { if (it.identityHex == identityHex) it.copy(verified = verified) else it })
    }

    @Synchronized
    fun remove(identityHex: String) {
        persist(all().filter { it.identityHex != identityHex })
    }

    private fun persist(contacts: List<Contact>) {
        val array = JSONArray()
        contacts.sortedBy { it.displayName.lowercase() }.forEach { c ->
            array.put(
                JSONObject()
                    .put("id", c.identityHex)
                    .put("name", c.displayName)
                    .put("onion", c.onionAddress)
                    .put("mailbox", c.mailboxAddress)
                    .put("reg", c.registrationId)
                    .put("at", c.addedAt)
                    .put("verified", c.verified),
            )
        }
        store.put(key, array.toString().toByteArray(Charsets.UTF_8))
    }
}
