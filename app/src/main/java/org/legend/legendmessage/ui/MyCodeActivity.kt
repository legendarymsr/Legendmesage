package org.legend.legendmessage.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityMyCodeBinding
import org.legend.legendmessage.pairing.Qr

/**
 * Shows our own pairing QR (the full [org.legend.legendmessage.pairing.ContactCard])
 * plus the identity fingerprint for out-of-band verification.
 */
class MyCodeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMyCodeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val services = App.services()
        binding.nameText.text = services.identity.displayName
        binding.fingerprintText.text = services.identity.fingerprint()

        // Onion address is empty until Tor is up (Stage 3); the card still pairs
        // the cryptographic session now.
        val onion = services.identity.onionAddress
        val card = services.crypto.myCard(onion)
        val sizePx = (resources.displayMetrics.density * 280).toInt()
        binding.qrImage.setImageBitmap(Qr.encode(card.encode(), sizePx))

        binding.routeText.text = if (onion.isBlank()) {
            getString(org.legend.legendmessage.R.string.mycode_no_onion)
        } else {
            onion
        }
    }
}
