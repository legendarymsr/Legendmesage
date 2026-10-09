package org.legend.legendmessage.net

import android.util.Log
import org.legend.legendmessage.crypto.IdentityManager
import org.legend.legendmessage.tor.TorConfig
import org.legend.legendmessage.tor.TorState
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Keeps a single long-lived, authenticated subscription open to our mailbox and
 * delivers messages the moment the mailbox pushes them — instead of rebuilding
 * a Tor circuit every couple of minutes to poll. A supervisor reconnects if the
 * stream drops (e.g. after a Wi-Fi/cellular switch).
 */
class MailboxPoller(
    private val identity: IdentityManager,
    private val inbound: InboundDelivery,
) {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var running = false
    @Volatile private var current: Socket? = null

    fun start() {
        if (running) return
        running = true
        executor.execute { supervise() }
    }

    fun stop() {
        running = false
        closeCurrent()
    }

    /** Drop the current stream so the supervisor reconnects (after a network change). */
    fun reconnect() {
        closeCurrent()
    }

    private fun supervise() {
        while (running) {
            val mailbox = identity.mailboxAddress
            if (mailbox.isBlank() || mailbox == identity.onionAddress || TorState.status != TorState.Status.ON) {
                sleep(5_000)
                continue
            }
            try {
                subscribe(mailbox)
            } catch (e: Exception) {
                Log.i(TAG, "mailbox stream ended: ${e.message}")
            } finally {
                closeCurrent()
            }
            if (running) sleep(RECONNECT_BACKOFF_MS)
        }
    }

    private fun subscribe(mailbox: String) {
        val socket = TorState.openThroughTor(mailbox, TorConfig.VIRTUAL_PORT)
        current = socket
        socket.use {
            val out = Wire.output(it.getOutputStream())
            val input = Wire.input(it.getInputStream())

            out.writeByte(Wire.OP_SUBSCRIBE)
            Wire.writeFrame(out, identity.identityKey().serialize())
            out.flush()

            val challenge = Wire.readFrame(input)
            Wire.writeFrame(out, identity.identityKeyPair().privateKey.calculateSignature(challenge))
            out.flush()

            // The server keepalives every ~90s; a read that stalls well past that
            // means the circuit died silently — time out and let the supervisor
            // rebuild rather than hanging forever.
            it.soTimeout = 150_000
            while (running && !it.isClosed) {
                when (Wire.readStreamKind(input)) {
                    Wire.STREAM_ENVELOPE -> inbound.deliver(Wire.readEnvelope(input))
                    Wire.STREAM_KEEPALIVE -> Unit
                    else -> throw IOException("bad mailbox stream frame")
                }
            }
        }
    }

    private fun closeCurrent() {
        runCatching { current?.close() }
        current = null
    }

    private fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    companion object {
        private const val TAG = "MailboxPoller"
        private const val RECONNECT_BACKOFF_MS = 5_000L
    }
}
