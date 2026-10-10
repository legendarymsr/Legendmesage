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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class App : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        // The crash screen runs in its own ":crash" process; don't re-run app
        // init there (it could re-trigger a startup crash and loop).
        if (currentProcessName().endsWith(":crash")) return
        installCrashHandler()
        if (BuildConfig.SECURE_WINDOWS) installSecureWindows()
        instance = this
        services = ServiceLocator(this)
    }

    /**
     * Mark every window FLAG_SECURE so the OS blocks screenshots and screen
     * recording and shows a blank card (not chat content) in the app switcher.
     * Registered once for all activities so no screen can forget it. The debug
     * flavor sets SECURE_WINDOWS=false so self-test output stays shareable.
     */
    private fun installSecureWindows() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
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
