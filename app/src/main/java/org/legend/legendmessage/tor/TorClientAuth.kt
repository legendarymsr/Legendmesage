package org.legend.legendmessage.tor

import android.util.Log
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
            serviceId?.let { OnionResult(it, privKey) }
        }.onFailure { Log.w(TAG, "client-auth ADD_ONION failed: ${it.message}") }.getOrNull()
    }

    /** Register our private key so we can connect to a peer's authorized onion. */
    fun registerClientKey(
        control: TorControlConnection,
        peerServiceId: String,
        privateKeyBase32: String,
    ): Boolean = runCatching {
        control.onionClientAuthAdd(peerServiceId, privateKeyBase32)
        true
    }.onFailure { Log.w(TAG, "ONION_CLIENT_AUTH_ADD failed: ${it.message}") }.getOrDefault(false)

    @Suppress("UNCHECKED_CAST")
    private fun sendRaw(control: TorControlConnection, command: String): List<Any> {
        val method = TorControlConnection::class.java.getDeclaredMethod(
            "sendAndWaitForResponse",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        // The method expects the command WITHOUT the trailing CRLF (it adds it).
        return method.invoke(control, command + "\r\n", null) as List<Any>
    }

    private fun replyField(line: Any, name: String): String? = runCatching {
        val f = line.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.get(line) as? String
    }.getOrNull()
}
