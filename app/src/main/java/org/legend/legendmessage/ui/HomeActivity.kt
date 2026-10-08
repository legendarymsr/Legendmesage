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

        ensureTorRunning()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        TorState.addListener(torListener)
        App.services().sender.flush()
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
