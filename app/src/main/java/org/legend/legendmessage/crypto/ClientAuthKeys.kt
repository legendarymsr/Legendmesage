package org.legend.legendmessage.crypto

import org.legend.legendmessage.tor.Base32
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey

/**
 * This device's x25519 keypair for Tor v3 onion **client authorization**
 * (experimental, opt-in). The public half travels in our contact card so a
 * peer can authorize us on their onion; the private half stays here and is
 * registered with Tor so we can connect to peers who authorized us.
 *
 * Curve25519 (what libsignal uses) and X25519 (what Tor client auth uses) are
 * the same curve, so the raw 32-byte scalar / point are interchangeable. This
 * is generated lazily and only used when the user enables client auth.
 */
class ClientAuthKeys(private val store: SecretStore) {

    /** Generate on first use; persist the raw private + public halves. */
    private fun ensure(): Pair<ECPrivateKey, ECPublicKey> {
        val priv = store.get(KEY_PRIV)
        val pub = store.get(KEY_PUB)
        if (priv != null && pub != null) {
            return ECPrivateKey(priv) to ECPublicKey(pub)
        }
        val pair = ECKeyPair.generate()
        store.put(KEY_PRIV, pair.privateKey.serialize())
        store.put(KEY_PUB, pair.publicKey.serialize())
        return pair.privateKey to pair.publicKey
    }

    /** Raw 32-byte x25519 public key (libsignal's 0x05 type prefix stripped). */
    fun publicKeyRaw(): ByteArray {
        val serialized = ensure().second.serialize()
        return if (serialized.size == 33) serialized.copyOfRange(1, 33) else serialized
    }

    /**
     * Our private key for ONION_CLIENT_AUTH_ADD. Tor's control spec requires
     * the x25519 private key there as **base64** (jtorctl prepends "x25519:").
     * Base32 is only for the ADD_ONION ClientAuthV3 public keys.
     */
    fun privateKeyBase64(): String {
        val raw = ensure().first.serialize()
        require(raw.size == 32) { "unexpected x25519 private key length ${raw.size}" }
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }

    /** Base32 of a peer's raw 32-byte public key, for the ClientAuthV3 argument. */
    fun peerPubToBase32(raw32: ByteArray): String = Base32.encode(raw32)

    companion object {
        const val KEY_PRIV = "clientauth_priv"
        const val KEY_PUB = "clientauth_pub"
    }
}
