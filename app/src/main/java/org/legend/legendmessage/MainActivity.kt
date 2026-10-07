package org.legend.legendmessage

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.legend.legendmessage.app.App
import org.legend.legendmessage.ui.HomeActivity
import org.legend.legendmessage.ui.OnboardingActivity

/**
 * Entry point / router. Sends the user to onboarding if there is no identity
 * yet, otherwise straight to the home screen. Holds no UI of its own.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (App.services().identity.exists()) {
            HomeActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
