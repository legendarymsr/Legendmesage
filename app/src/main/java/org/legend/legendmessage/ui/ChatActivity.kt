package org.legend.legendmessage.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import org.legend.legendmessage.R
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityChatBinding
import org.legend.legendmessage.net.MessageBus
import java.util.concurrent.Executors

/**
 * One conversation. Shows the (locally decrypted) history and sends new
 * messages through [org.legend.legendmessage.net.MessageSender], which
 * encrypts, queues, and delivers over Tor.
 */
class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var peerHex: String

    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val busListener: (String) -> Unit = { changed ->
        if (changed == peerHex) reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerHex = intent.getStringExtra(EXTRA_PEER_HEX).orEmpty()
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        binding.chatTitle.text = getString(R.string.chat_title_verify, peerName)
        binding.chatTitle.setOnClickListener { showSafetyNumber(peerName) }

        adapter = MessageAdapter(emptyList())
        binding.messagesList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messagesList.adapter = adapter

        binding.sendButton.setOnClickListener {
            val text = binding.input.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                App.services().sender.send(peerHex, text)
                binding.input.text?.clear()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MessageBus.addListener(busListener)
        App.services().sender.flush()
        reload()
    }

    override fun onPause() {
        super.onPause()
        MessageBus.removeListener(busListener)
    }

    private fun showSafetyNumber(peerName: String) {
        val number = App.services().crypto.safetyNumber(peerHex)
            ?: getString(R.string.chat_safety_unavailable)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_safety_title, peerName))
            .setMessage(getString(R.string.chat_safety_body, number))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun reload() {
        loadExecutor.execute {
            val history = App.services().messages.history(peerHex)
            runOnUiThread {
                adapter.submit(history)
                if (history.isNotEmpty()) binding.messagesList.scrollToPosition(history.size - 1)
            }
        }
    }

    companion object {
        const val EXTRA_PEER_HEX = "peer_hex"
        const val EXTRA_PEER_NAME = "peer_name"
    }
}
