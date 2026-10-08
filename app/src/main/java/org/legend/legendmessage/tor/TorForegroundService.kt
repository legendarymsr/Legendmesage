package org.legend.legendmessage.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import net.freehaven.tor.control.TorControlCommands
import org.legend.legendmessage.MainActivity
import org.legend.legendmessage.R
import org.legend.legendmessage.app.App
import org.torproject.jni.TorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The always-on piece. A persistent foreground service that runs an embedded
 * Tor ([TorService], bound here) and publishes this device's onion v3 service,
 * mapping onion:VIRTUAL_PORT to the local peer listener. The onion key is
 * persisted (Keystore-encrypted) so the address is stable across restarts.
 *
 * Android fights long-lived background networking: this needs the foreground
 * service plus a battery-optimization exemption, and aggressive OEM killers
 * can still stop it. That tension is inherent to mobile P2P, not a bug.
 */
class TorForegroundService : Service() {

    private val io = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var polling = false
    private var torService: TorService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            torService = (binder as? TorService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torService = null
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_ON -> io.execute { onTorOn() }
                TorService.STATUS_STARTING -> TorState.update(status = TorState.Status.STARTING)
                TorService.STATUS_OFF -> TorState.update(status = TorState.Status.OFF)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(getString(R.string.tor_notif_starting))
        TorState.update(status = TorState.Status.STARTING)

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(TorService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // BIND_AUTO_CREATE starts TorService, which launches the embedded Tor.
        bindService(Intent(this, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { App.services().peerServer.stop() }
        runCatching { unbindService(connection) }
        runCatching { unregisterReceiver(statusReceiver) }
        io.shutdownNow()
        scheduler.shutdownNow()
        TorState.update(status = TorState.Status.OFF)
        super.onDestroy()
    }

    private fun onTorOn() {
        val control = torService?.torControlConnection
        if (control == null) {
            // Control connection not ready at the instant ON arrived; retry shortly.
            io.execute {
                Thread.sleep(800)
                onTorOn()
            }
            return
        }
        try {
            val services = App.services()
            val ports: Map<Int, String> =
                mapOf(TorConfig.VIRTUAL_PORT to "127.0.0.1:${TorConfig.LOCAL_PORT}")

            val stored = services.secretStore.get("onion_key")?.let { String(it, Charsets.UTF_8) }
            val result = if (stored != null) {
                control.addOnion(stored, ports)
            } else {
                control.addOnion("NEW:BEST", ports)
            }

            val serviceId = result[TorControlCommands.HS_ADDRESS]
            val privKey = result[TorControlCommands.HS_PRIVKEY]
            if (stored == null && privKey != null) {
                services.secretStore.put("onion_key", privKey.toByteArray(Charsets.UTF_8))
            }

            val socks = torService?.socksPort ?: TorService.socksPort
            if (serviceId != null) {
                val onion = "$serviceId.onion"
                services.identity.onionAddress = onion
                TorState.update(status = TorState.Status.ON, onionAddress = onion, socksPort = socks)
                updateNotification(getString(R.string.tor_notif_on))

                // Online: accept inbound streams, flush the outbox, collect mail,
                // and keep polling the mailbox + retrying the outbox periodically.
                services.peerServer.start()
                services.sender.flush()
                services.mailboxPoller.poll()
                if (!polling) {
                    polling = true
                    scheduler.scheduleWithFixedDelay({
                        if (TorState.status == TorState.Status.ON) {
                            App.services().sender.flush()
                            App.services().mailboxPoller.poll()
                        }
                    }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
                }
            }
        } catch (e: Exception) {
            TorState.update(status = TorState.Status.ERROR)
            updateNotification(getString(R.string.tor_notif_error, e.message ?: ""))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tor_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "legendmessage.tor"
        private const val NOTIF_ID = 1001
        private const val POLL_SECONDS = 120L

        fun start(context: Context) {
            val intent = Intent(context, TorForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TorForegroundService::class.java))
        }
    }
}
