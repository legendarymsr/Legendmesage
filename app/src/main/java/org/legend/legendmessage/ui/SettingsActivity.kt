package org.legend.legendmessage.ui

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.R
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivitySettingsBinding
import java.util.concurrent.Executors

/**
 * Backup and restore of the device's identity and contacts, as a single
 * passphrase-encrypted file the user chooses the location of (Storage Access
 * Framework). Message history is not included.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val io = Executors.newSingleThreadExecutor()

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) askPassphrase(exporting = true) { pass -> doExport(uri, pass) }
        }

    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) askPassphrase(exporting = false) { pass -> doImport(uri, pass) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.exportButton.setOnClickListener { createDoc.launch("legendmessage-backup.lmbk") }
        binding.importButton.setOnClickListener { openDoc.launch(arrayOf("*/*")) }

        val identity = App.services().identity
        binding.mailboxAddressInput.setText(identity.mailboxAddress)
        binding.mailboxModeCheck.isChecked = identity.mailboxEnabled
        binding.mailboxThisDevice.text =
            identity.onionAddress.ifBlank { getString(R.string.mailbox_this_device_pending) }

        binding.mailboxSaveButton.setOnClickListener {
            identity.mailboxAddress = binding.mailboxAddressInput.text?.toString()?.trim().orEmpty()
            toast(getString(R.string.mailbox_saved))
        }
        binding.mailboxModeCheck.setOnCheckedChangeListener { _, checked ->
            identity.mailboxEnabled = checked
        }

        binding.panicWipeButton.setOnClickListener { confirmPanicWipe() }
    }

    /**
     * Irreversible "erase everything" — identity, keys, contacts, and all
     * message history. Double-confirmed (dialog + typed word) because there is
     * no undo and no server copy.
     */
    private fun confirmPanicWipe() {
        val confirmInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.settings_wipe_confirm_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_wipe_title)
            .setMessage(R.string.settings_wipe_body)
            .setView(confirmInput)
            .setPositiveButton(R.string.settings_wipe_confirm) { _, _ ->
                val typed = confirmInput.text?.toString()?.trim().orEmpty()
                if (typed.equals(getString(R.string.settings_wipe_word), ignoreCase = true)) {
                    org.legend.legendmessage.app.AppWipe.wipeAndRestart(this)
                } else {
                    toast(getString(R.string.settings_wipe_cancelled))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askPassphrase(exporting: Boolean, onEntered: (CharArray) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.settings_passphrase_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(if (exporting) R.string.settings_export else R.string.settings_import)
            .setMessage(R.string.settings_passphrase_body)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pass = input.text?.toString().orEmpty()
                if (pass.length < 6) {
                    toast(getString(R.string.settings_passphrase_short))
                } else {
                    onEntered(pass.toCharArray())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doExport(uri: Uri, passphrase: CharArray) {
        io.execute {
            val result = runCatching {
                val blob = App.services().backup.export(passphrase)
                contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                    ?: error("could not open file")
            }
            passphrase.fill('\u0000') // don't leave the passphrase in memory
            runOnUiThread {
                toast(
                    if (result.isSuccess) getString(R.string.settings_export_ok)
                    else getString(R.string.settings_failed, result.exceptionOrNull()?.message ?: ""),
                )
            }
        }
    }

    private fun doImport(uri: Uri, passphrase: CharArray) {
        io.execute {
            val result = runCatching {
                val blob = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("could not open file")
                App.services().backup.import(blob, passphrase)
            }
            passphrase.fill('\u0000') // don't leave the passphrase in memory
            runOnUiThread {
                if (result.isSuccess) {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.settings_import_ok)
                        .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                        .show()
                } else {
                    toast(getString(R.string.settings_failed, result.exceptionOrNull()?.message ?: ""))
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
