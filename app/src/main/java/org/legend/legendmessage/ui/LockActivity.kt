package org.legend.legendmessage.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.legend.legendmessage.R
import org.legend.legendmessage.app.App
import org.legend.legendmessage.app.AppWipe
import org.legend.legendmessage.app.LockGate
import org.legend.legendmessage.databinding.ActivityLockBinding

/**
 * The unlock gate shown on cold start and when returning from the background
 * while a lock is set. Accepts the PIN, or a biometric if the user enabled it.
 * Pressing Back does not bypass it — it sends the app to the background.
 *
 * Implements [LockGate] so [App]'s lifecycle gating never re-gates this screen.
 */
class LockActivity : AppCompatActivity(), LockGate {
    private lateinit var binding: ActivityLockBinding
    private val lock get() = App.services().lock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.unlockButton.setOnClickListener { tryPin() }
        binding.forgotButton.setOnClickListener { confirmForgot() }

        if (lock.biometricEnabled && canUseBiometric()) {
            binding.biometricButton.visibility = android.view.View.VISIBLE
            binding.biometricButton.setOnClickListener { promptBiometric() }
        } else {
            binding.biometricButton.visibility = android.view.View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // If somehow already unlocked (e.g. another gate handled it), close.
        if (!App.services().lock.hasPin() || !isStillLocked()) finish()
        else if (lock.biometricEnabled && canUseBiometric()) promptBiometric()
    }

    private fun isStillLocked(): Boolean = App.instance.isLocked()

    private fun tryPin() {
        val pin = binding.pinInput.text?.toString()?.toCharArray() ?: CharArray(0)
        val ok = lock.verify(pin)
        pin.fill('\u0000')
        if (ok) {
            unlock()
        } else {
            binding.pinInput.text?.clear()
            Toast.makeText(this, R.string.lock_wrong_pin, Toast.LENGTH_SHORT).show()
        }
    }

    private fun unlock() {
        App.instance.markUnlocked()
        finish()
    }

    private fun canUseBiometric(): Boolean =
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun promptBiometric() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_biometric_title))
            .setNegativeButtonText(getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    private fun confirmForgot() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_forgot_title)
            .setMessage(R.string.lock_forgot_body)
            .setPositiveButton(R.string.settings_wipe_confirm) { _, _ -> AppWipe.wipeAndRestart(this) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("Back must not bypass the lock")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
