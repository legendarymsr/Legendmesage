package org.legend.legendmessage.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.BuildConfig
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityDebugBinding
import org.legend.legendmessage.selftest.SelfTest
import org.legend.legendmessage.tor.TorForegroundService
import org.legend.legendmessage.tor.TorState
import java.io.File
import java.util.concurrent.Executors

/**
 * The whole debug app: one black terminal screen. It prints a short status
 * header (and the last crash, if any), runs the on-device two-device
 * self-test streaming into the same console, and offers restart-Tor and wipe.
 * Nothing else — it is only ever used for testing.
 */
class DebugActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDebugBinding
    private val io = Executors.newSingleThreadExecutor()
    private val buffer = StringBuilder()
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.runButton.setOnClickListener { runSelfTest() }
        binding.restartTorButton.setOnClickListener { restartTor() }
        binding.wipeButton.setOnClickListener { confirmWipe() }

        printHeader()
    }

    private fun printHeader() {
        buffer.setLength(0)
        buffer.append(header())
        val crash = File(filesDir, "last_crash.txt")
        if (crash.exists()) buffer.append("\n\n--- last crash ---\n").append(crash.readText())
        render()
    }

    private fun header(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val mb = 1024L * 1024L
        val onion = App.services().identity.onionAddress.ifBlank { "(not published)" }
        return buildString {
            appendLine("LegendMsg Debug ${BuildConfig.VERSION_NAME}")
            appendLine("RAM     ${mem.availMem / mb}/${mem.totalMem / mb} MB free")
            appendLine("Storage ${stat.availableBytes / mb}/${stat.totalBytes / mb} MB free")
            appendLine("Tor     ${TorState.status}  SOCKS ${TorState.socksPort}")
            append("Onion   $onion")
        }
    }

    private fun runSelfTest() {
        if (running) return
        running = true
        binding.runButton.isEnabled = false
        buffer.setLength(0)
        render()
        io.execute {
            SelfTest(applicationContext).run { line -> appendLine(line) }
            runOnUiThread {
                running = false
                binding.runButton.isEnabled = true
            }
        }
    }

    private fun appendLine(line: String) = runOnUiThread {
        buffer.append(line).append('\n')
        render()
    }

    private fun render() {
        binding.console.text = buffer
        binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun restartTor() {
        TorForegroundService.stop(this)
        TorForegroundService.start(this)
        appendLine("\n[restarting Tor…]")
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle(org.legend.legendmessage.R.string.debug_reset_title)
            .setMessage(org.legend.legendmessage.R.string.debug_reset_body)
            .setPositiveButton(org.legend.legendmessage.R.string.debug_reset) { _, _ -> doWipe() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doWipe() {
        TorForegroundService.stop(this)
        runCatching { File(filesDir, "secretstore").deleteRecursively() }
        runCatching {
            (filesDir.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("selftest_") }
                .forEach { it.deleteRecursively() }
        }
        runCatching { getDatabasePath("messages.db").parentFile?.listFiles()?.forEach { it.delete() } }
        runCatching { getSharedPreferences("identity", MODE_PRIVATE).edit().clear().apply() }
        // Relaunch the debug console (the debug app stays in debugging).
        startActivity(
            Intent(this, DebugActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}
