package org.legend.legendmessage.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.R
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
        // Advertise our Tor client-auth public key only when the experimental
        // feature is enabled, so a peer can authorize us on their onion.
        val clientAuthPub = if (services.identity.torClientAuth) {
            runCatching { services.clientAuth.publicKeyRaw() }.getOrDefault(ByteArray(0))
        } else {
            ByteArray(0)
        }
        val card = services.crypto.myCard(onion, clientAuthPub)
        // Render the QR from the base45 form: it encodes in QR alphanumeric mode
        // so it stays compact AND round-trips through the scanner reliably. A
        // binary byte-mode QR does not — the scanner guesses a charset and the
        // card's high bytes (e.g. the Kyber key) get corrupted on the way back.
        val sizePx = (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceIn(480, 1440)
        binding.qrImage.setImageBitmap(Qr.encode(card.encodeQr(), sizePx))

        binding.shareButton.setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, card.encode())
            }
            startActivity(Intent.createChooser(share, getString(R.string.mycode_share)))
        }

        binding.routeText.text = if (onion.isBlank()) {
            getString(org.legend.legendmessage.R.string.mycode_no_onion)
        } else {
            onion
        }
    }
}
