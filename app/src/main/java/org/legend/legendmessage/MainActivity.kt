package org.legend.legendmessage

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.databinding.ActivityMainBinding

/**
 * Stage 0 placeholder screen.
 *
 * LegendMessage is a Signal-encrypted, peer-to-peer messenger that runs
 * entirely over Tor onion services. Identity is a cryptographic keypair —
 * there is no phone number, no email, no account server. This Activity exists
 * only to prove the build pipeline end-to-end; the identity, pairing, Tor
 * transport and messaging layers land in later stages.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
