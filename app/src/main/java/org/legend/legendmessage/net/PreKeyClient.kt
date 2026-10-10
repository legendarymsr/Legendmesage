package org.legend.legendmessage.net

import org.legend.legendmessage.crypto.KyberBundle
import org.legend.legendmessage.tor.TorConfig
import org.legend.legendmessage.tor.TorState

/**
 * Fetches a peer's Kyber prekey over Tor. The ~1.5 KB post-quantum key is no
 * longer carried in the pairing QR (to keep it small and scannable); instead a
 * new peer asks for it here once, at first contact, to complete the session.
 */
object PreKeyClient {
    /** Returns the peer's Kyber prekey, or null if it couldn't be fetched / they had none. */
    fun fetchKyber(onion: String): KyberBundle? {
        if (onion.isBlank()) return null
        TorState.openThroughTor(onion, TorConfig.VIRTUAL_PORT).use { socket ->
            socket.soTimeout = 60_000
            val out = Wire.output(socket.getOutputStream())
            out.writeByte(Wire.OP_GET_PREKEY)
            out.flush()
            val (id, key, signature) = Wire.readKyber(Wire.input(socket.getInputStream()))
            return if (key.isEmpty()) null else KyberBundle(id, key, signature)
        }
    }
}
