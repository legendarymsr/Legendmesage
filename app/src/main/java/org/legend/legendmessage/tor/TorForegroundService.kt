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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import net.freehaven.tor.control.TorControlCommands
import net.freehaven.tor.control.TorControlConnection
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

    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    // Android silently kills Tor circuits across a network switch (e.g. Wi-Fi ->
    // cellular). On a change we rebuild circuits (NEWNYM), drop and reconnect the
    // mailbox stream, and retry the outbox — instead of hanging on dead sockets.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
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

        runCatching { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
    }

    private fun onNetworkChanged() {
        if (TorState.status != TorState.Status.ON) return
        io.execute {
            runCatching { torService?.torControlConnection?.signal("NEWNYM") }
            runCatching { App.services().mailboxPoller.reconnect() }
            runCatching { App.services().sender.flush() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { App.services().peerServer.stop() }
        runCatching { App.services().mailboxPoller.stop() }
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
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
            val keyBlob = stored ?: "NEW:BEST"

            // Experimental: restrict the onion to authorized client keys. Only
            // possible when at least one contact has shared a client-auth key;
            // any failure falls back to a normal (unauthenticated) onion so the
            // transport never ends up dead.
            var serviceId: String?
            var privKey: String?
            val authorizedPubs = if (services.identity.torClientAuth) authorizedClientPubs() else emptyList()
            if (authorizedPubs.isNotEmpty()) {
                val authed = TorClientAuth.addOnionWithClientAuth(
                    control, keyBlob, TorConfig.VIRTUAL_PORT, TorConfig.LOCAL_PORT, authorizedPubs,
                )
                if (authed != null) {
                    serviceId = authed.serviceId
                    privKey = authed.privateKey
                } else {
                    val result = control.addOnion(keyBlob, ports)
                    serviceId = result[TorControlCommands.HS_ADDRESS]
                    privKey = result[TorControlCommands.HS_PRIVKEY]
                }
            } else {
                val result = control.addOnion(keyBlob, ports)
                serviceId = result[TorControlCommands.HS_ADDRESS]
                privKey = result[TorControlCommands.HS_PRIVKEY]
            }
            if (stored == null && privKey != null) {
                services.secretStore.put("onion_key", privKey.toByteArray(Charsets.UTF_8))
            }
            if (services.identity.torClientAuth) registerPeerClientKeys(control)

            val socks = torService?.socksPort ?: TorService.socksPort
            if (serviceId != null) {
                val onion = "$serviceId.onion"
                services.identity.onionAddress = onion
                TorState.update(status = TorState.Status.ON, onionAddress = onion, socksPort = socks)
                updateNotification(getString(R.string.tor_notif_on))

                // Online: accept inbound streams, open the long-lived mailbox
                // subscription, and flush the outbox. A low-frequency fallback
                // retries the outbox and heals the subscription if it died.
                services.peerServer.start()
                services.mailboxPoller.start()
                services.sender.flush()
                if (!polling) {
                    polling = true
                    scheduler.scheduleWithFixedDelay({
                        if (TorState.status == TorState.Status.ON) {
                            App.services().sender.flush()
                            App.services().mailboxPoller.start() // idempotent; restarts if stopped
                        }
                    }, FALLBACK_SECONDS, FALLBACK_SECONDS, TimeUnit.SECONDS)
                }
            }
        } catch (e: Exception) {
            TorState.update(status = TorState.Status.ERROR)
            updateNotification(getString(R.string.tor_notif_error, e.message ?: ""))
        }
    }

    /** Base32 client-auth public keys of every contact that advertised one. */
    private fun authorizedClientPubs(): List<String> {
        val services = App.services()
        return services.contacts.all().mapNotNull { c ->
            val pub = services.crypto.peerCard(c.identityHex)?.clientAuthPub
            if (pub != null && pub.isNotEmpty()) services.clientAuth.peerPubToBase32(pub) else null
        }
    }

    /**
     * Register our client-auth private key against each contact's onion so we
     * can connect to peers who authorized us. Safe to run on every Tor start
     * (idempotent) and takes effect live, without recreating our own onion.
     */
    private fun registerPeerClientKeys(control: TorControlConnection) {
        val services = App.services()
        val privBase32 = runCatching { services.clientAuth.privateKeyBase32() }.getOrNull() ?: return
        services.contacts.all().forEach { c ->
            val card = services.crypto.peerCard(c.identityHex) ?: return@forEach
            if (card.clientAuthPub.isEmpty() || card.onionAddress.isBlank()) return@forEach
            TorClientAuth.registerClientKey(control, card.onionAddress.removeSuffix(".onion"), privBase32)
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

        // The mailbox stream delivers promptly; this is just a safety-net retry
        // for the outbox and to heal a dead subscription, so it can be rare.
        private const val FALLBACK_SECONDS = 600L

        fun start(context: Context) {
            val intent = Intent(context, TorForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TorForegroundService::class.java))
        }
    }
}
