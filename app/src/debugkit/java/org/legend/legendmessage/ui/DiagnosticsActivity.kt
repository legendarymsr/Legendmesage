package org.legend.legendmessage.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityDiagnosticsBinding
import org.legend.legendmessage.tor.TorState
import java.util.concurrent.Executors

/**
 * A live, read-only view of the transport state so a two-device test can be
 * diagnosed on the device: Tor status, our onion, SOCKS port, contact routes,
 * how many messages are queued, and the last delivery result.
 */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            loadAndRender()
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.retryButton.setOnClickListener {
            App.services().sender.flush()
            Toast.makeText(this, "Retrying delivery…", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
    }

    private fun loadAndRender() {
        io.execute {
            val s = App.services()
            val contacts = runCatching { s.contacts.all() }.getOrDefault(emptyList())
            val pending = runCatching { s.messages.pendingOutgoing().size }.getOrDefault(-1)

            val sb = StringBuilder()
            sb.appendLine("Tor:        ${TorState.status}")
            sb.appendLine("SOCKS port: ${TorState.socksPort}")
            sb.appendLine("My onion:   ${s.identity.onionAddress.ifBlank { "(not published yet)" }}")
            sb.appendLine("Fingerprint:")
            sb.appendLine("  ${runCatching { s.identity.fingerprint() }.getOrDefault("—")}")
            sb.appendLine()
            sb.appendLine("My mailbox: ${s.identity.mailboxAddress.ifBlank { "(none)" }}")
            sb.appendLine("Mailbox mode: ${if (s.identity.mailboxEnabled) "ON" else "off"}")
            sb.appendLine()
            sb.appendLine("Queued outgoing: $pending")
            sb.appendLine("Last send:  ${s.sender.lastStatus}")
            sb.appendLine()
            sb.appendLine("Contacts (${contacts.size}):")
            if (contacts.isEmpty()) {
                sb.appendLine("  none — pair with someone first")
            } else {
                contacts.forEach { c ->
                    val route = if (c.onionAddress.isBlank()) "NO ROUTE" else c.onionAddress.take(16) + "…"
                    val session = if (s.crypto.hasSession(c.identityHex)) "session✓" else "no session yet"
                    sb.appendLine("  ${c.displayName}: $route  $session")
                }
            }

            val text = sb.toString()
            main.post { binding.diagText.text = text }
        }
    }
}
