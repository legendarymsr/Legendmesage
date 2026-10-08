package org.legend.legendmessage.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.databinding.ActivitySelfTestBinding
import org.legend.legendmessage.selftest.SelfTest
import java.util.concurrent.Executors

/**
 * Runs the on-device two-peer [SelfTest] and streams its log to the screen,
 * along with the device's real RAM and storage (so a constrained device is
 * tested as itself).
 */
class SelfTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySelfTestBinding
    private val io = Executors.newSingleThreadExecutor()
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelfTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.deviceInfo.text = deviceInfo()
        binding.runButton.setOnClickListener { runTest() }
    }

    private fun runTest() {
        binding.runButton.isEnabled = false
        log.setLength(0)
        binding.logText.text = ""
        io.execute {
            SelfTest(applicationContext).run { line -> append(line) }
            runOnUiThread { binding.runButton.isEnabled = true }
        }
    }

    private fun append(line: String) {
        runOnUiThread {
            log.append(line).append('\n')
            binding.logText.text = log.toString()
            binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun deviceInfo(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalRamMb = mem.totalMem / (1024 * 1024)
        val availRamMb = mem.availMem / (1024 * 1024)
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeStorageMb = stat.availableBytes / (1024 * 1024)
        val totalStorageMb = stat.totalBytes / (1024 * 1024)
        return "RAM: ${availRamMb}MB free / ${totalRamMb}MB total\n" +
            "Storage: ${freeStorageMb}MB free / ${totalStorageMb}MB total"
    }
}
