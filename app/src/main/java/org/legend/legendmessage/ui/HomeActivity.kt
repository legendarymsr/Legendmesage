package org.legend.legendmessage.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.app.App
import org.legend.legendmessage.databinding.ActivityHomeBinding
import org.legend.legendmessage.pairing.IdentityCard
import org.legend.legendmessage.pairing.Qr

/**
 * Home screen. For Stage 1 it shows who you are: display name, identity
 * fingerprint, and a QR of your identity card. Later stages add the contact
 * list and the "add contact" / messaging entry points here.
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val identity = App.services().identity
        binding.nameText.text = identity.displayName
        binding.fingerprintText.text = identity.fingerprint()

        val card = IdentityCard(identity.displayName, identity.identityKey())
        val sizePx = (resources.displayMetrics.density * 240).toInt()
        binding.qrImage.setImageBitmap(Qr.encode(card.encode(), sizePx))
    }
}
