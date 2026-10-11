package org.legend.legendmessage.app

import org.legend.legendmessage.BuildConfig

/**
 * App logging. The messenger ("standard" flavor) writes nothing to logcat:
 * even "harmless" lines name contacts, identity keys or delivery timing, and
 * logcat is readable by adb and bug-report tools. The debugkit flavor keeps
 * full logs for diagnostics. CI ships *debug* build types, so this keys off
 * the flavor, not BuildConfig.DEBUG.
 */
object Log {
    private val enabled = BuildConfig.VERBOSE_DIAGNOSTICS

    fun i(tag: String, msg: String) { if (enabled) android.util.Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (enabled) android.util.Log.w(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) { if (enabled) android.util.Log.e(tag, msg, t) }

    /** Short, non-identifying form of an identity hex for log lines. */
    fun short(hex: String): String = hex.take(8) + "…"
}
