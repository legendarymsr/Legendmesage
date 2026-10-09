package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.IdentityManager
import org.legend.legendmessage.data.MailboxStore
import org.legend.legendmessage.tor.TorConfig
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * Inbound side of the transport. Listens on 127.0.0.1:LOCAL_PORT (the target of
 * our onion service) and hands each accepted connection to [MailboxProtocol].
 */
class PeerServer(
    private val identity: IdentityManager,
    private val inbound: InboundDelivery,
    private val mailbox: MailboxStore,
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
                workers.execute {
                    MailboxProtocol.handle(socket, inbound, mailbox, identity.mailboxEnabled)
                }
            }
        } catch (e: Exception) {
            if (!Thread.currentThread().isInterrupted) Log.w(TAG, "accept loop ended: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PeerServer"
    }
}
