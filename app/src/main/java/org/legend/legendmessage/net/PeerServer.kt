package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.IdentityManager
import org.legend.legendmessage.data.MailboxStore
import org.legend.legendmessage.tor.TorConfig
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Inbound side of the transport. Listens on 127.0.0.1:LOCAL_PORT (the target of
 * our onion service) and dispatches by opcode:
 *  - OP_DIRECT  : decrypt + store a message sent straight to us
 *  - OP_DEPOSIT : (mailbox mode) hold ciphertext for an offline recipient
 *  - OP_COLLECT : (mailbox mode) release held ciphertext after the recipient
 *                 proves ownership of their identity key via a signed challenge
 */
class PeerServer(
    private val identity: IdentityManager,
    private val inbound: InboundDelivery,
    private val mailbox: MailboxStore,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newCachedThreadPool()
    private val random = SecureRandom()

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
            if (!Thread.currentThread().isInterrupted) Log.w(TAG, "accept loop ended: ${e.message}")
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 60_000
                val input = Wire.input(it.getInputStream())
                when (input.readByte().toInt()) {
                    Wire.OP_DIRECT -> inbound.deliver(Wire.readEnvelope(input))
                    Wire.OP_DEPOSIT -> handleDeposit(input)
                    Wire.OP_COLLECT -> handleCollect(it, input)
                    else -> Log.w(TAG, "unknown opcode")
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to handle inbound connection: ${e.message}")
            }
        }
    }

    private fun handleDeposit(input: java.io.DataInputStream) {
        if (!identity.mailboxEnabled) return
        val recipientId = Wire.readFrame(input)
        val envelope = Wire.readEnvelope(input)
        mailbox.deposit(hex(recipientId), envelope)
    }

    private fun handleCollect(socket: Socket, input: java.io.DataInputStream) {
        if (!identity.mailboxEnabled) return
        val recipientId = Wire.readFrame(input)
        val output = Wire.output(socket.getOutputStream())

        // Challenge-response: only the holder of the recipient identity private
        // key can collect that recipient's mail.
        val challenge = ByteArray(32).also { random.nextBytes(it) }
        Wire.writeFrame(output, challenge)
        output.flush()

        val signature = Wire.readFrame(input)
        val trusted = try {
            ECPublicKey(recipientId).verifySignature(challenge, signature)
        } catch (e: Exception) {
            false
        }
        if (!trusted) {
            output.writeInt(0)
            output.flush()
            return
        }

        val messages = mailbox.collectAndDelete(hex(recipientId))
        output.writeInt(messages.size)
        messages.forEach { Wire.writeEnvelope(output, it) }
        output.flush()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "PeerServer"
    }
}
