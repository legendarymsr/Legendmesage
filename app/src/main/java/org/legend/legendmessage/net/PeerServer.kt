package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.CryptoEngine
import org.legend.legendmessage.data.ContactStore
import org.legend.legendmessage.data.MessageStore
import org.legend.legendmessage.tor.TorConfig
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Inbound side of the transport. Listens on 127.0.0.1:LOCAL_PORT (the target
 * of our onion service) and, for each incoming Tor stream, reads one envelope,
 * decrypts it against the sender's Signal session, and stores the plaintext.
 */
class PeerServer(
    private val contacts: ContactStore,
    private val messages: MessageStore,
    private val crypto: CryptoEngine,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newCachedThreadPool()

    @Synchronized
    fun start() {
        if (acceptThread?.isAlive == true) return
        acceptThread = Thread({ acceptLoop() }, "peer-server").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop() {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", TorConfig.LOCAL_PORT))
            serverSocket = server
            while (!Thread.currentThread().isInterrupted) {
                val socket = server.accept()
                workers.execute { handle(socket) }
            }
        } catch (e: Exception) {
            if (!Thread.currentThread().isInterrupted) {
                Log.w(TAG, "accept loop ended: ${e.message}")
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 60_000
                val envelope = Wire.read(it.getInputStream())
                val senderHex = CryptoEngine.hexOf(envelope.senderId)
                // Only accept from someone we've paired with.
                if (contacts.get(senderHex) == null) {
                    Log.w(TAG, "dropping message from unknown sender $senderHex")
                    return
                }
                val plaintext = crypto.decrypt(senderHex, envelope.type, envelope.body)
                messages.insertIncoming(senderHex, String(plaintext, Charsets.UTF_8))
                MessageBus.notifyChanged(senderHex)
            } catch (e: Exception) {
                Log.w(TAG, "failed to handle inbound message: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PeerServer"
    }
}
