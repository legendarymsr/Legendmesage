package org.legend.legendmessage.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.WindowManager
import org.legend.legendmessage.BuildConfig
import org.legend.legendmessage.ui.CrashActivity
import org.legend.legendmessage.ui.LockActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/** Marker for screens that must NOT be gated by the app lock (the lock screen itself). */
interface LockGate

class App : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        // The crash screen runs in its own ":crash" process; don't re-run app
        // init there (it could re-trigger a startup crash and loop).
        if (currentProcessName().endsWith(":crash")) return
        installCrashHandler()
        instance = this
        services = ServiceLocator(this)
        installActivityCallbacks()
    }

    // ---- App lock state (gates the UI, not the background Tor service) ----

    @Volatile
    private var locked = true // demand unlock on cold start if a lock is set
    private var startedActivities = 0

    private fun lockConfigured(): Boolean = runCatching { services.lock.hasPin() }.getOrDefault(false)

    /** Called by the lock screen after a successful PIN/biometric unlock. */
    fun markUnlocked() {
        locked = false
    }

    fun isLocked(): Boolean = locked && lockConfigured()

    /**
     * One place for every per-activity concern:
     *  - FLAG_SECURE on create (standard flavor only), so no screen can forget it;
     *  - app-lock gating on resume, and relocking when the app goes to background.
     * The Tor foreground service is not an activity, so it keeps running while
     * the UI is locked.
     */
    private fun installActivityCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (BuildConfig.SECURE_WINDOWS) {
                    activity.window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                }
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityResumed(activity: Activity) {
                if (isLocked() && activity !is LockGate) {
                    activity.startActivity(
                        Intent(activity, LockActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                // Whole app in background: relock so returning requires unlock.
                if (startedActivities <= 0 && lockConfigured()) locked = true
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim { it <= ' ' } }.getOrDefault("")
        }

    /**
     * Surface any uncaught exception on screen (in a separate process) instead
     * of a silent force-close, and persist it — so a crash can be diagnosed on
     * the device without a cable or adb. Set as early as possible.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val text = "LegendMessage crashed in thread \"${thread.name}\":\n\n$trace"
            runCatching { File(filesDir, "last_crash.txt").writeText(text) }
            runCatching {
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(CrashActivity.EXTRA_TRACE, text),
                )
            }
            previous?.uncaughtException(thread, throwable)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    companion object {
        lateinit var instance: App
            private set

        fun services(): ServiceLocator = instance.services
    }
}
