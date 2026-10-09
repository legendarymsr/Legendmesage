package org.legend.legendmessage.selftest

import android.content.Context
import org.legend.legendmessage.crypto.CryptoEngine
import org.legend.legendmessage.crypto.IdentityManager
import org.legend.legendmessage.crypto.SecretStore
import org.legend.legendmessage.crypto.SignalStore
import org.legend.legendmessage.data.ContactStore
import org.legend.legendmessage.data.MailboxStore
import org.legend.legendmessage.data.MessageDb
import org.legend.legendmessage.data.MessageStore
import org.legend.legendmessage.net.InboundDelivery
import org.legend.legendmessage.net.MailboxProtocol
import org.legend.legendmessage.net.Wire
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An on-device "two devices talking" test. It builds two fully independent
 * peers (plus a third acting as a mailbox), each with its own isolated storage,
 * and runs the real pipeline between them — pairing, X3DH/PQXDH, the Double
 * Ratchet, the wire envelope, encrypted storage, and the mailbox deposit/collect
 * with its signed-challenge auth — using an in-memory transport instead of Tor.
 *
 * It exercises the exact classes two real devices use, on real device hardware
 * and native libraries, without needing a second phone, Tor, or an emulator.
 */
class SelfTest(private val context: Context) {

    private class Peer(
        val name: String,
        val hex: ByteArray,
        val hexStr: String,
        val identity: IdentityManager,
        val contacts: ContactStore,
        val crypto: CryptoEngine,
        val messages: MessageStore,
        val mailbox: MailboxStore,
        val inbound: InboundDelivery,
        val db: MessageDb,
    )

    /** Runs the test, streaming log lines; returns true if every check passed. */
    fun run(log: (String) -> Unit): Boolean {
        // Unique, underscore-free id so run-scoped cleanup can never touch another run.
        val runId = java.util.UUID.randomUUID().toString().replace("-", "")
        var ok = true
        val peers = mutableListOf<Peer>()

        fun check(name: String, condition: Boolean) {
            log((if (condition) "✓ " else "✗ ") + name)
            if (!condition) ok = false
        }

        try {
            log("LegendMsg Debug v${org.legend.legendmessage.BuildConfig.VERSION_NAME} — self-test")
            log("")
            deleteSelftest { !it.contains("_${runId}_") } // purge other runs, keep this one
            val alice = makePeer(runId, "a", "Alice")
            val bob = makePeer(runId, "b", "Bob")
            val box = makePeer(runId, "c", "Mailbox")
            peers += listOf(alice, bob, box)
            log("Built 3 independent identities (Alice, Bob, Mailbox).")
            log("")

            // Mutual pairing via contact cards (no onion needed for the test).
            alice.crypto.addContact(bob.crypto.myCard(""))
            bob.crypto.addContact(alice.crypto.myCard(""))
            check("Pairing: Alice has Bob", alice.contacts.get(bob.hexStr) != null)
            check("Pairing: Bob has Alice", bob.contacts.get(alice.hexStr) != null)

            // First message A -> B establishes the session (PreKey message).
            val first = "hello Bob — from Alice"
            transfer(alice, bob, first)
            check("A→B first message (X3DH) delivered & decrypted",
                bob.messages.history(alice.hexStr).any { it.body == first })

            // Reply B -> A (now a normal ratchet message).
            val reply = "hi Alice — from Bob"
            transfer(bob, alice, reply)
            check("B→A reply delivered & decrypted",
                alice.messages.history(bob.hexStr).any { it.body == reply })

            // Exercise the Double Ratchet over several messages each way.
            var ratchetOk = true
            for (i in 1..8) {
                val am = "A ping $i"
                val bm = "B pong $i"
                transfer(alice, bob, am)
                transfer(bob, alice, bm)
                if (bob.messages.history(alice.hexStr).none { it.body == am }) ratchetOk = false
                if (alice.messages.history(bob.hexStr).none { it.body == bm }) ratchetOk = false
            }
            check("Double Ratchet: 16 further messages both ways", ratchetOk)

            // Mailbox: Alice deposits for an 'offline' Bob at the Mailbox peer.
            box.identity.mailboxEnabled = true
            val offline = "offline message via mailbox"
            val envelope = encrypt(alice, bob.hexStr, offline)
            check("Mailbox deposit accepted", box.mailbox.deposit(bob.hexStr, envelope))

            // Collect auth: only Bob (holder of his key) can claim Bob's mail.
            val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val bobSig = bob.identity.identityKeyPair().privateKey.calculateSignature(challenge)
            val bobTrusted = ECPublicKey(bob.identity.identityKey().serialize())
                .verifySignature(challenge, bobSig)
            val imposterSig = alice.identity.identityKeyPair().privateKey.calculateSignature(challenge)
            val imposterRejected = !ECPublicKey(bob.identity.identityKey().serialize())
                .verifySignature(challenge, imposterSig)
            check("Mailbox auth: Bob's signature verifies", bobTrusted)
            check("Mailbox auth: an imposter is rejected", imposterRejected)

            // Bob collects and the message decrypts.
            val collected = box.mailbox.collectAndDelete(bob.hexStr)
            check("Mailbox held exactly 1 message", collected.size == 1)
            collected.forEach { bob.inbound.deliver(it) }
            check("Mailbox message decrypted by Bob",
                bob.messages.history(alice.hexStr).any { it.body == offline })
            check("Mailbox emptied after collect", box.mailbox.collectAndDelete(bob.hexStr).isEmpty())

            // --- Streaming mailbox (OP_SUBSCRIBE) over a real loopback socket ---
            log("")
            log("Testing the streaming mailbox over a loopback socket…")
            check("Bob's identity is intact before streaming", bob.identity.exists())
            val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
            val port = server.localPort
            val accept = Thread {
                runCatching {
                    while (!Thread.currentThread().isInterrupted) {
                        val sock = server.accept()
                        Thread { MailboxProtocol.handle(sock, box.inbound, box.mailbox, true) }
                            .apply { isDaemon = true }.start()
                    }
                }
            }.apply { isDaemon = true; start() }

            try {
                // Backlog delivered over the stream after a signed-challenge auth.
                val backlogText = "stream backlog msg"
                depositViaWire(port, bob.hex, encrypt(alice, bob.hexStr, backlogText))
                val backlogEnv = subscribeReadOne(port, bob, 6000)
                check("Stream: subscribe delivers backlog", backlogEnv != null)
                backlogEnv?.let { bob.inbound.deliver(it) }
                check("Stream: backlog message decrypted",
                    bob.messages.history(alice.hexStr).any { it.body == backlogText })

                // A wrong signature gets no stream.
                check("Stream: wrong signature is rejected",
                    !subscribeAcceptsBadAuth(port, recipient = bob, signer = alice))

                // Live push: subscribe first, then a deposit is pushed instantly.
                val pushText = "stream push msg"
                val queue: BlockingQueue<Wire.Envelope> = LinkedBlockingQueue()
                val sub = Thread { streamInto(port, bob, queue) }.apply { isDaemon = true; start() }
                Thread.sleep(600) // let it subscribe + consume the first keepalive
                depositViaWire(port, bob.hex, encrypt(alice, bob.hexStr, pushText))
                val pushed = queue.poll(6, TimeUnit.SECONDS)
                check("Stream: live push received", pushed != null)
                pushed?.let { bob.inbound.deliver(it) }
                check("Stream: pushed message decrypted",
                    bob.messages.history(alice.hexStr).any { it.body == pushText })
                sub.interrupt()
            } catch (e: Exception) {
                ok = false
                log("✗ Stream test error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { server.close() }
                accept.interrupt()
            }

            // A message from a stranger must be rejected.
            val stranger = makePeer(runId, "x", "Stranger")
            peers += stranger
            val strangerEnv = encryptNoSession(stranger, alice, "i am not your contact")
            val accepted = alice.inbound.deliver(strangerEnv)
            check("Unknown sender is dropped", !accepted)

            log("")
            if (ok) {
                log("✅ ALL CHECKS PASSED — two-device messaging works end to end.")
            } else {
                log("❌ SOME CHECKS FAILED — see the ✗ lines above.")
            }
        } catch (e: Throwable) {
            ok = false
            log("")
            log("❌ EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            log(e.stackTrace.take(6).joinToString("\n") { "   at $it" })
        } finally {
            peers.forEach { runCatching { it.db.close() } }
            deleteSelftest { it.contains("_${runId}_") } // only this run's storage
        }
        return ok
    }

    /** Encrypt [text] from sender to recipient, round-trip through Wire, deliver. */
    private fun transfer(sender: Peer, recipient: Peer, text: String) {
        val envelope = encrypt(sender, recipient.hexStr, text)
        val buffer = ByteArrayOutputStream()
        Wire.output(buffer).also { Wire.writeEnvelope(it, envelope); it.flush() }
        val parsed = Wire.readEnvelope(Wire.input(ByteArrayInputStream(buffer.toByteArray())))
        recipient.inbound.deliver(parsed)
    }

    private fun encrypt(sender: Peer, recipientHex: String, text: String): Wire.Envelope {
        val enc = sender.crypto.encrypt(recipientHex, text.toByteArray(Charsets.UTF_8))
        return Wire.Envelope(sender.crypto.myIdentityKeyBytes(), enc.type, enc.body)
    }

    // ---- streaming-mailbox test helpers (real loopback sockets) ----

    private fun depositViaWire(port: Int, recipientId: ByteArray, envelope: Wire.Envelope) {
        Socket("127.0.0.1", port).use { s ->
            val out = Wire.output(s.getOutputStream())
            out.writeByte(Wire.OP_DEPOSIT)
            Wire.writeFrame(out, recipientId)
            Wire.writeEnvelope(out, envelope)
            out.flush()
            Thread.sleep(250) // let the server store + signal before we close
        }
    }

    private fun subscribeHandshake(s: Socket, recipientId: ByteArray, signerKey: org.signal.libsignal.protocol.ecc.ECPrivateKey) {
        val out = Wire.output(s.getOutputStream())
        val input = Wire.input(s.getInputStream())
        out.writeByte(Wire.OP_SUBSCRIBE)
        Wire.writeFrame(out, recipientId)
        out.flush()
        val challenge = Wire.readFrame(input)
        Wire.writeFrame(out, signerKey.calculateSignature(challenge))
        out.flush()
    }

    private fun subscribeReadOne(port: Int, peer: Peer, timeoutMs: Int): Wire.Envelope? {
        val s = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 3000) }
        s.use {
            subscribeHandshake(it, peer.hex, peer.identity.identityKeyPair().privateKey)
            val input = Wire.input(it.getInputStream())
            it.soTimeout = timeoutMs
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val kind = try { Wire.readStreamKind(input) } catch (e: Exception) { return null }
                when (kind) {
                    Wire.STREAM_ENVELOPE -> return Wire.readEnvelope(input)
                    Wire.STREAM_KEEPALIVE -> Unit
                    else -> return null
                }
            }
            return null
        }
    }

    /** Returns true if the server streamed anything despite a wrong signature (i.e. auth failed open). */
    private fun subscribeAcceptsBadAuth(port: Int, recipient: Peer, signer: Peer): Boolean {
        val s = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 3000) }
        s.use {
            subscribeHandshake(it, recipient.hex, signer.identity.identityKeyPair().privateKey)
            val input = Wire.input(it.getInputStream())
            it.soTimeout = 3000
            return try {
                Wire.readStreamKind(input)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun streamInto(port: Int, peer: Peer, queue: BlockingQueue<Wire.Envelope>) {
        runCatching {
            val s = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 3000) }
            s.use {
                subscribeHandshake(it, peer.hex, peer.identity.identityKeyPair().privateKey)
                val input = Wire.input(it.getInputStream())
                it.soTimeout = 10_000
                while (!Thread.currentThread().isInterrupted) {
                    when (Wire.readStreamKind(input)) {
                        Wire.STREAM_ENVELOPE -> queue.put(Wire.readEnvelope(input))
                        Wire.STREAM_KEEPALIVE -> Unit
                        else -> break
                    }
                }
            }
        }
    }

    /** Sender hasn't paired with recipient; build an envelope anyway (for the drop test). */
    private fun encryptNoSession(sender: Peer, recipient: Peer, text: String): Wire.Envelope {
        sender.crypto.addContact(recipient.crypto.myCard(""))
        val enc = sender.crypto.encrypt(recipient.hexStr, text.toByteArray(Charsets.UTF_8))
        return Wire.Envelope(sender.crypto.myIdentityKeyBytes(), enc.type, enc.body)
    }

    private fun makePeer(runId: String, tag: String, name: String): Peer {
        val ns = "selftest_${runId}_$tag"
        val secret = SecretStore(context, ns)
        val identity = IdentityManager(context, secret, "${ns}_id")
        if (!identity.exists()) identity.create(name)
        val contacts = ContactStore(secret)
        val signal = SignalStore(secret, identity)
        val crypto = CryptoEngine(identity, signal, contacts)
        crypto.ensurePreKeys()
        val db = MessageDb(context, secret, "$ns.db")
        val messages = MessageStore(db)
        val mailbox = MailboxStore(db)
        val inbound = InboundDelivery(contacts, messages, crypto)
        val idBytes = identity.identityKey().serialize()
        return Peer(name, idBytes, CryptoEngine.hexOf(idBytes), identity, contacts, crypto, messages, mailbox, inbound, db)
    }

    /**
     * Delete self-test storage whose directory/db name matches [predicate].
     * Run-scoped so a concurrent or late-finishing run can never wipe another
     * run's in-progress files.
     */
    private fun deleteSelftest(predicate: (String) -> Boolean) {
        runCatching {
            (context.filesDir.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("selftest_") && predicate(it.name) }
                .forEach { it.deleteRecursively() }
        }
        runCatching {
            val dbDir = context.getDatabasePath("x").parentFile
            (dbDir?.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("selftest_") && predicate(it.name) }
                .forEach { it.delete() }
        }
    }
}
