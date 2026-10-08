package org.legend.legendmessage.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.legend.legendmessage.R
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityHomeBinding
import org.legend.legendmessage.pairing.ContactCard

/**
 * Home: your contacts, plus entry points to show your own pairing code and to
 * scan someone else's. Scanning a card runs the X3DH handshake and adds them.
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var adapter: ContactAdapter

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) handleScanned(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.nameText.text = App.services().identity.displayName

        adapter = ContactAdapter(emptyList()) { contact ->
            // Stage 4 opens the chat here; for now confirm the secure session.
            Toast.makeText(
                this,
                getString(R.string.home_session_ready, contact.displayName),
                Toast.LENGTH_SHORT,
            ).show()
        }
        binding.contactsList.layoutManager = LinearLayoutManager(this)
        binding.contactsList.adapter = adapter

        binding.myCodeButton.setOnClickListener {
            startActivity(Intent(this, MyCodeActivity::class.java))
        }
        binding.addButton.setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setPrompt(getString(R.string.scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val contacts = App.services().contacts.all()
        adapter.submit(contacts)
        binding.emptyText.visibility = if (contacts.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun handleScanned(text: String) {
        if (!ContactCard.looksLikeCard(text)) {
            Toast.makeText(this, R.string.scan_not_a_card, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val card = ContactCard.decode(text)
            val contact = App.services().crypto.addContact(card)
            refresh()
            AlertDialog.Builder(this)
                .setTitle(R.string.pair_ok_title)
                .setMessage(getString(R.string.pair_ok_body, contact.displayName))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.pair_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
