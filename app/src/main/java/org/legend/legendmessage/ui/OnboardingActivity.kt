package org.legend.legendmessage.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.R
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityOnboardingBinding

/**
 * First-run screen. Picks a display name (purely a local label — it is not
 * registered anywhere) and generates the cryptographic identity.
 */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.createButton.setOnClickListener {
            val name = binding.nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.onboarding_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.createButton.isEnabled = false
            val services = App.services()
            if (!services.identity.exists()) {
                services.identity.create(name)
                services.crypto.ensurePreKeys()
            }
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }
}
