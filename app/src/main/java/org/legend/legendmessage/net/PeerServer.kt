package org.legend.legendmessage.net

import org.legend.legendmessage.app.Log
import org.legend.legendmessage.crypto.IdentityManager
import org.legend.legendmessage.crypto.KyberBundle
import org.legend.legendmessage.data.MailboxStore
import org.legend.legendmessage.tor.TorConfig
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Inbound side of the transport. Listens on 127.0.0.1:LOCAL_PORT (the target of
 * our onion service) and hands each accepted connection to [MailboxProtocol].
 */
class PeerServer(
    private val identity: IdentityManager,
    private val inbound: InboundDelivery,
    private val mailbox: MailboxStore,
    private val kyberProvider: () -> KyberBundle?,
    /** Whether this mailbox holds mail for a recipient (hex identity); default: its contacts. */
    private val acceptRecipient: (String) -> Boolean = { true },
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    // Bounded: anyone who knows the onion can connect, so cap concurrent
    // handlers and refuse (close) extra connections instead of spawning
    // unlimited threads. Long-lived mailbox subscriptions count against this.
    private val workers = ThreadPoolExecutor(
        MAX_WORKERS, MAX_WORKERS, 30, TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

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
                // Idle/slow peers can't pin a worker forever. Authenticated
                // subscriptions lift this (the server only writes on them).
                runCatching { socket.soTimeout = READ_TIMEOUT_MS }
                try {
                    workers.execute {
                        MailboxProtocol.handle(
                            socket, inbound, mailbox, identity.mailboxEnabled, kyberProvider,
                            selfOnion = identity.onionAddress,
                            acceptRecipient = acceptRecipient,
                        )
                    }
                } catch (e: RejectedExecutionException) {
                    runCatching { socket.close() }
                }
            }
        } catch (e: Exception) {
            if (!Thread.currentThread().isInterrupted) Log.w(TAG, "accept loop ended: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PeerServer"
        private const val MAX_WORKERS = 32
        private const val READ_TIMEOUT_MS = 30_000
    }
}
