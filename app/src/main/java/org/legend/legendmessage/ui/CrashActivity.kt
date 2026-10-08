package org.legend.legendmessage.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * A deliberately dependency-free crash screen (plain Activity, no theme, no
 * resources, its own process) so it can display an exception even if the
 * failure is in app startup, resources, or a native library. Shows the trace
 * for the user to screenshot.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra(EXTRA_TRACE)
            ?: runCatching { File(filesDir, "last_crash.txt").readText() }.getOrNull()
            ?: "No crash details available."

        val text = TextView(this).apply {
            setPadding(40, 60, 40, 60)
            setTextIsSelectable(true)
            setTextColor(Color.parseColor("#C0CAF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            this.text = trace
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1A1B26"))
            addView(text)
        }
        setContentView(scroll)
    }

    companion object {
        const val EXTRA_TRACE = "trace"
    }
}
