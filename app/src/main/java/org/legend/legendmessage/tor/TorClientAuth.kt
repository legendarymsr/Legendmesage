package org.legend.legendmessage.tor

import org.legend.legendmessage.app.Log
import net.freehaven.tor.control.TorControlConnection

/**
 * EXPERIMENTAL Tor v3 onion **client authorization** helper.
 *
 * jtorctl's `addOnion` can only emit a `Flags=` field, not the per-client
 * `ClientAuthV3=<pubkey>` arguments that authorize specific clients. So when
 * the feature is on we craft the raw `ADD_ONION` command and send it through
 * the control connection's (protected) send method by reflection, then parse
 * the ServiceID / PrivateKey out of the reply.
 *
 * Every entry point is best-effort and returns null on any failure, so the
 * caller can fall back to a normal (unauthenticated) onion and never strand
 * the user with a dead transport.
 */
object TorClientAuth {
    private const val TAG = "TorClientAuth"

    private val BASE32_X25519 = Regex("^[A-Z2-7]{52}$")
    private val SERVICE_ID = Regex("^[a-z2-7]{56}$")
    private val BASE64_X25519 = Regex("^[A-Za-z0-9+/]{43}=$")
    private val KEY_BLOB = Regex("^(NEW:BEST|ED25519-V3:[A-Za-z0-9+/]+={0,2})$")

    /** A v3 onion service id (no ".onion"), lowercase base32, 56 chars. */
    fun isServiceId(id: String) = SERVICE_ID.matches(id)

    /** A full v3 onion address ("<56 chars>.onion"). */
    fun isOnionAddress(addr: String) = addr.endsWith(".onion") && isServiceId(addr.removeSuffix(".onion"))

    data class OnionResult(val serviceId: String, val privateKey: String?)

    /**
     * ADD_ONION [keyBlob] Port=VIRT,127.0.0.1:LOCAL ClientAuthV3=<pub> …
     * [authorizedPubsBase32] are raw x25519 public keys, base32-encoded.
     */
    fun addOnionWithClientAuth(
        control: TorControlConnection,
        keyBlob: String,
        virtualPort: Int,
        localPort: Int,
        authorizedPubsBase32: List<String>,
    ): OnionResult? {
        if (authorizedPubsBase32.isEmpty()) return null
        // Everything below goes into a raw control-port command: validate so a
        // malformed contact card can't inject extra arguments or a CRLF.
        if (!KEY_BLOB.matches(keyBlob)) return null
        if (authorizedPubsBase32.any { !BASE32_X25519.matches(it) }) {
            Log.w(TAG, "refusing malformed client-auth public key")
            return null
        }
        val sb = StringBuilder("ADD_ONION ").append(keyBlob)
        sb.append(" Port=").append(virtualPort).append(",127.0.0.1:").append(localPort)
        for (pub in authorizedPubsBase32) sb.append(" ClientAuthV3=").append(pub)
        return runCatching {
            val replies = sendRaw(control, sb.toString())
            var serviceId: String? = null
            var privKey: String? = null
            for (line in replies) {
                val msg = replyField(line, "msg") ?: continue
                when {
                    msg.startsWith("ServiceID=") -> serviceId = msg.removePrefix("ServiceID=").trim()
                    msg.startsWith("PrivateKey=") -> privKey = msg.removePrefix("PrivateKey=").trim()
                }
            }
            serviceId?.takeIf { isServiceId(it) }?.let { OnionResult(it, privKey) }
        }.onFailure { Log.w(TAG, "client-auth ADD_ONION failed (${it.javaClass.simpleName})") }.getOrNull()
    }

    /** Register our private key so we can connect to a peer's authorized onion. */
    fun registerClientKey(
        control: TorControlConnection,
        peerServiceId: String,
        privateKeyBase64: String,
    ): Boolean {
        if (!isServiceId(peerServiceId) || !BASE64_X25519.matches(privateKeyBase64)) return false
        // jtorctl throws TorControlError on any 4xx/5xx reply, so returning
        // normally means Tor accepted it (250/251/252).
        return runCatching {
            control.onionClientAuthAdd(peerServiceId, privateKeyBase64)
            true
        }.onFailure { Log.w(TAG, "ONION_CLIENT_AUTH_ADD failed (${it.javaClass.simpleName})") }.getOrDefault(false)
    }

    @Suppress("UNCHECKED_CAST")
    private fun sendRaw(control: TorControlConnection, command: String): List<Any> {
        val method = TorControlConnection::class.java.getDeclaredMethod(
            "sendAndWaitForResponse",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        // The method writes the string verbatim, so the command must carry its
        // own trailing CRLF (jtorctl's own callers append it the same way).
        // NOTE: reflection on a protected jtorctl method; see proguard-rules.pro.
        return method.invoke(control, command + "\r\n", null) as List<Any>
    }

    private fun replyField(line: Any, name: String): String? = runCatching {
        val f = line.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.get(line) as? String
    }.getOrNull()
}
