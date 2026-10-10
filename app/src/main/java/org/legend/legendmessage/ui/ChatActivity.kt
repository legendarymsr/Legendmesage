package org.legend.legendmessage.ui

import android.os.Bundle
import android.view.View
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
 *
 * Verification: tapping the title opens the safety number, where the user can
 * mark the contact verified after comparing it out-of-band. Until then an
 * "unverified" banner shows, and — if verified-only send is on — sending is
 * blocked until verification.
 */
class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var peerHex: String
    private lateinit var peerName: String

    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val busListener: (String) -> Unit = { changed ->
        if (changed == peerHex) reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerHex = intent.getStringExtra(EXTRA_PEER_HEX).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        binding.chatTitle.text = getString(R.string.chat_title_verify, peerName)
        binding.chatTitle.setOnClickListener { showSafetyNumber() }
        binding.chatTitle.setOnLongClickListener { showDisappearingPicker(); true }
        binding.verifyBanner.setOnClickListener { showSafetyNumber() }

        adapter = MessageAdapter(emptyList())
        binding.messagesList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messagesList.adapter = adapter

        binding.sendButton.setOnClickListener { trySend() }
    }

    override fun onResume() {
        super.onResume()
        MessageBus.addListener(busListener)
        App.services().sender.healContacts() // fetch prekey for a new contact if needed
        App.services().sender.flush()
        renderVerifyState()
        reload()
    }

    override fun onPause() {
        super.onPause()
        MessageBus.removeListener(busListener)
    }

    private fun isVerified(): Boolean = App.services().contacts.get(peerHex)?.verified == true

    private fun renderVerifyState() {
        binding.verifyBanner.visibility = if (isVerified()) View.GONE else View.VISIBLE
    }

    private fun trySend() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (App.services().identity.verifiedOnlySend && !isVerified()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.chat_verify_required_title)
                .setMessage(getString(R.string.chat_verify_required_body, peerName))
                .setPositiveButton(R.string.chat_verify_now) { _, _ -> showSafetyNumber() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        App.services().sender.send(peerHex, text)
        binding.input.text?.clear()
    }

    private fun showSafetyNumber() {
        val number = App.services().crypto.safetyNumber(peerHex)
            ?: getString(R.string.chat_safety_unavailable)
        val verified = isVerified()
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_safety_title, peerName))
            .setMessage(
                getString(R.string.chat_safety_body, number) + "\n\n" +
                    getString(if (verified) R.string.chat_safety_is_verified else R.string.chat_safety_not_verified),
            )
            .setPositiveButton(android.R.string.ok, null)
        if (verified) {
            builder.setNeutralButton(R.string.chat_safety_clear) { _, _ ->
                App.services().contacts.setVerified(peerHex, false)
                renderVerifyState()
            }
        } else {
            builder.setNeutralButton(R.string.chat_safety_mark_verified) { _, _ ->
                App.services().contacts.setVerified(peerHex, true)
                renderVerifyState()
            }
        }
        builder.show()
    }

    private fun reload() {
        loadExecutor.execute {
            // Apply the disappearing-messages policy before loading history.
            val ttl = App.services().contacts.get(peerHex)?.disappearingSeconds ?: 0
            App.services().messages.expireOld(peerHex, ttl)
            val history = App.services().messages.history(peerHex)
            runOnUiThread {
                adapter.submit(history)
                if (history.isNotEmpty()) binding.messagesList.scrollToPosition(history.size - 1)
            }
        }
    }

    private fun showDisappearingPicker() {
        val labels = resources.getStringArray(R.array.disappearing_labels)
        val values = resources.getIntArray(R.array.disappearing_values)
        val current = App.services().contacts.get(peerHex)?.disappearingSeconds ?: 0
        val checked = values.indexOf(current).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_disappearing_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                App.services().contacts.setDisappearing(peerHex, values[which])
                reload()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_PEER_HEX = "peer_hex"
        const val EXTRA_PEER_NAME = "peer_name"
    }
}
