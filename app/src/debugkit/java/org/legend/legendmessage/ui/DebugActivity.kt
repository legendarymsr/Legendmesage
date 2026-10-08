package org.legend.legendmessage.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.BuildConfig
import org.legend.legendmessage.MainActivity
import org.legend.legendmessage.R
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityDebugBinding
import org.legend.legendmessage.tor.TorForegroundService
import org.legend.legendmessage.tor.TorState
import java.io.File

/**
 * One place for all the test/debug tools: the two-device self-test, live
 * transport diagnostics, the last crash, plus a few handy utilities.
 */
class DebugActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selfTestButton.setOnClickListener {
            startActivity(Intent(this, SelfTestActivity::class.java))
        }
        binding.diagnosticsButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        binding.crashButton.setOnClickListener { showLastCrash() }
        binding.copyOnionButton.setOnClickListener { copyOnion() }
        binding.restartTorButton.setOnClickListener { restartTor() }
        binding.resetButton.setOnClickListener { confirmReset() }
    }

    override fun onResume() {
        super.onResume()
        binding.debugInfo.text = info()
    }

    private fun info(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val mb = 1024L * 1024L
        val identity = App.services().identity
        return buildString {
            appendLine("Version:  ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("RAM:      ${mem.availMem / mb}MB free / ${mem.totalMem / mb}MB total")
            appendLine("Storage:  ${stat.availableBytes / mb}MB free / ${stat.totalBytes / mb}MB total")
            appendLine("Tor:      ${TorState.status}  (SOCKS ${TorState.socksPort})")
            append("Onion:    ${identity.onionAddress.ifBlank { "(not published)" }}")
        }
    }

    private fun showLastCrash() {
        val crash = File(filesDir, "last_crash.txt")
        val text = if (crash.exists()) crash.readText() else getString(R.string.debug_no_crash)
        AlertDialog.Builder(this)
            .setTitle(R.string.debug_crash)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.debug_copy) { _, _ -> copy("crash", text) }
            .show()
    }

    private fun copyOnion() {
        val onion = App.services().identity.onionAddress
        if (onion.isBlank()) {
            Toast.makeText(this, R.string.debug_no_onion, Toast.LENGTH_SHORT).show()
        } else {
            copy("onion", onion)
        }
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show()
    }

    private fun restartTor() {
        TorForegroundService.stop(this)
        TorForegroundService.start(this)
        Toast.makeText(this, R.string.debug_tor_restarting, Toast.LENGTH_SHORT).show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.debug_reset_title)
            .setMessage(R.string.debug_reset_body)
            .setPositiveButton(R.string.debug_reset) { _, _ -> doReset() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doReset() {
        TorForegroundService.stop(this)
        // Wipe all identity, keys, contacts, and message history.
        runCatching { File(filesDir, "secretstore").deleteRecursively() }
        runCatching {
            (context().filesDir.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("selftest_") }
                .forEach { it.deleteRecursively() }
        }
        runCatching { getDatabasePath("messages.db").parentFile?.listFiles()?.forEach { it.delete() } }
        runCatching { getSharedPreferences("identity", MODE_PRIVATE).edit().clear().apply() }

        // Relaunch from scratch.
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun context() = applicationContext
}
