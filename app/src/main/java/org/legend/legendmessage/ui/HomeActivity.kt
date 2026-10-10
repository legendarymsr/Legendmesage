package org.legend.legendmessage.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.legend.legendmessage.R
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityHomeBinding
import org.legend.legendmessage.pairing.ContactCard
import org.legend.legendmessage.tor.TorForegroundService
import org.legend.legendmessage.tor.TorState

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

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startTor() }

    private val torListener: () -> Unit = { renderTorStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.nameText.text = App.services().identity.displayName
        binding.appVersion.text = getString(R.string.home_version, org.legend.legendmessage.BuildConfig.VERSION_NAME)

        adapter = ContactAdapter(emptyList()) { contact ->
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_PEER_HEX, contact.identityHex)
                    .putExtra(ChatActivity.EXTRA_PEER_NAME, contact.displayName),
            )
        }
        binding.contactsList.layoutManager = LinearLayoutManager(this)
        binding.contactsList.adapter = adapter

        binding.myCodeButton.setOnClickListener {
            startActivity(Intent(this, MyCodeActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.addButton.setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setPrompt(getString(R.string.scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
        binding.pasteButton.setOnClickListener { pasteInvite() }

        ensureTorRunning()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        sweepDisappearing()
        TorState.addListener(torListener)
        App.services().sender.flush()
    }

    /** Apply every conversation's disappearing-messages policy, even unopened ones. */
    private fun sweepDisappearing() {
        val services = App.services()
        Thread {
            runCatching {
                services.contacts.all().forEach { c ->
                    if (c.disappearingSeconds > 0) services.messages.expireOld(c.identityHex, c.disappearingSeconds)
                }
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        TorState.removeListener(torListener)
    }

    /** Start Tor if it isn't up yet, after securing the notification permission. */
    private fun ensureTorRunning() {
        if (TorState.status == TorState.Status.ON || TorState.status == TorState.Status.STARTING) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startTor()
        }
    }

    private fun startTor() {
        TorForegroundService.start(this)
        maybeAskBatteryExemption()
    }

    private fun maybeAskBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.tor_battery_title)
                .setMessage(R.string.tor_battery_body)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun renderTorStatus() {
        binding.torStatus.text = when (TorState.status) {
            TorState.Status.OFF -> getString(R.string.tor_status_off)
            TorState.Status.STARTING -> getString(R.string.tor_status_starting)
            TorState.Status.ON -> getString(R.string.tor_status_on, TorState.onionAddress.take(16) + "…")
            TorState.Status.ERROR -> getString(R.string.tor_status_error)
        }
    }

    private fun refresh() {
        val contacts = App.services().contacts.all()
        adapter.submit(contacts)
        binding.emptyText.visibility = if (contacts.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun handleScanned(text: String) {
        val card = try {
            decodeCard(text)
        } catch (e: Exception) {
            // A LegendMessage code was recognised but didn't parse: either the
            // camera misread a dense QR, or the other phone is on an older build
            // whose code this version can't read. Both are worth saying.
            Toast.makeText(this, R.string.scan_misread, Toast.LENGTH_LONG).show()
            return
        }
        if (card == null) {
            Toast.makeText(this, R.string.scan_not_a_card, Toast.LENGTH_LONG).show()
            return
        }
        // Warn if we already know someone by this name with a *different* key:
        // either an innocent name clash or an impersonation attempt. The user
        // decides whether to proceed.
        val incomingHex = org.legend.legendmessage.crypto.CryptoEngine.hexOf(card.identityKey)
        val nameClash = App.services().contacts.all().firstOrNull {
            it.displayName.equals(card.displayName, ignoreCase = true) && it.identityHex != incomingHex
        }
        if (nameClash != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pair_namecollision_title)
                .setMessage(getString(R.string.pair_namecollision_body, card.displayName))
                .setPositiveButton(R.string.pair_namecollision_add) { _, _ -> commitContact(card) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        commitContact(card)
    }

    private fun commitContact(card: ContactCard) {
        try {
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

    /** A scanned/pasted invite may be the text link or the binary QR payload. */
    private fun decodeCard(text: String): ContactCard? {
        if (ContactCard.looksLikeCard(text.trim())) return ContactCard.decode(text.trim())
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        if (ContactCard.looksBinary(bytes)) return ContactCard.decodeBinary(bytes)
        return null
    }

    private fun pasteInvite() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.paste_hint)
            setText(clipboardText())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.home_paste)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handleScanned(input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clipboardText(): String {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        return item?.coerceToText(this)?.toString()?.takeIf { ContactCard.looksLikeCard(it.trim()) } ?: ""
    }
}
