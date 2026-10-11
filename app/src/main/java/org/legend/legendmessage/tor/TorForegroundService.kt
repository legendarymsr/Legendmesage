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
    @Volatile private var torService: TorService? = null

    // Onion we published on the current Tor run (null after Tor goes OFF), so
    // a repeated STATUS_ON doesn't ADD_ONION again and hit a collision.
    @Volatile private var publishedServiceId: String? = null
    private var controlRetries = 0

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
                TorService.STATUS_OFF -> {
                    publishedServiceId = null // ephemeral onion dies with Tor
                    TorState.update(status = TorState.Status.OFF)
                }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REPUBLISH) io.execute { republish() }
        return START_STICKY
    }

    /**
     * Ephemeral onions can't gain new ClientAuthV3 clients in place, so when
     * the authorized set changes (a contact added/removed) we DEL_ONION and
     * publish again with the same key; the address stays the same.
     */
    private fun republish() {
        val control = torService?.torControlConnection ?: return
        val current = publishedServiceId ?: return
        runCatching { control.delOnion(current) }
        publishedServiceId = null
        onTorOn()
    }

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
            // Control connection not ready at the instant ON arrived; retry a
            // bounded number of times on the scheduler (not by sleeping on io).
            if (controlRetries++ < MAX_CONTROL_RETRIES) {
                scheduler.schedule({ io.execute { onTorOn() } }, 800, TimeUnit.MILLISECONDS)
            } else {
                TorState.update(status = TorState.Status.ERROR)
                updateNotification(getString(R.string.tor_notif_error_generic))
            }
            return
        }
        controlRetries = 0
        try {
            val services = App.services()
            val clientAuthWanted = services.identity.torClientAuth
            var serviceId = publishedServiceId
            if (serviceId == null) {
                val ports: Map<Int, String> =
                    mapOf(TorConfig.VIRTUAL_PORT to "127.0.0.1:${TorConfig.LOCAL_PORT}")

                val stored = services.secretStore.get("onion_key")?.let { String(it, Charsets.UTF_8) }
                val keyBlob = stored ?: "NEW:BEST"

                var privKey: String?
                var authActive = false
                val authorizedPubs = if (clientAuthWanted) authorizedClientPubs() else emptyList()
                val authed = if (authorizedPubs.isNotEmpty()) {
                    TorClientAuth.addOnionWithClientAuth(
                        control, keyBlob, TorConfig.VIRTUAL_PORT, TorConfig.LOCAL_PORT, authorizedPubs,
                    )
                } else {
                    null
                }
                if (authed != null) {
                    serviceId = authed.serviceId
                    privKey = authed.privateKey
                    authActive = true
                } else {
                    // Normal onion. If the user asked for client auth this is a
                    // degraded state, surfaced below (never silent).
                    val result = control.addOnion(keyBlob, ports)
                    serviceId = result[TorControlCommands.HS_ADDRESS]
                    privKey = result[TorControlCommands.HS_PRIVKEY]
                }
                if (stored == null && privKey != null) {
                    services.secretStore.put("onion_key", privKey.toByteArray(Charsets.UTF_8))
                }
                publishedServiceId = serviceId
                TorState.setClientAuthDegraded(clientAuthWanted && !authActive)
            }
            if (clientAuthWanted) registerPeerClientKeys(control)

            val socks = torService?.socksPort ?: TorService.socksPort
            if (serviceId != null) {
                val onion = "$serviceId.onion"
                services.identity.onionAddress = onion
                TorState.update(status = TorState.Status.ON, onionAddress = onion, socksPort = socks)
                updateNotification(
                    getString(if (TorState.clientAuthDegraded) R.string.tor_notif_on_no_client_auth else R.string.tor_notif_on),
                )

                // Online: accept inbound streams, open the long-lived mailbox
                // subscription, and flush the outbox. A low-frequency fallback
                // retries the outbox and heals the subscription if it died.
                services.peerServer.start()
                services.mailboxPoller.start()
                services.sender.healContacts() // fetch new contacts' prekeys over Tor
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
            // Generic text: the notification can show on the lock screen.
            updateNotification(getString(R.string.tor_notif_error_generic))
        }
    }

    /** Base32 client-auth public keys of every contact that advertised one. */
    private fun authorizedClientPubs(): List<String> {
        val services = App.services()
        return services.contacts.all().mapNotNull { c ->
            val pub = services.crypto.peerCard(c.identityHex)?.clientAuthPub
            if (pub != null && pub.size == 32) services.clientAuth.peerPubToBase32(pub) else null
        }
    }

    /**
     * Register our client-auth private key against each contact's onion so we
     * can connect to peers who authorized us. Safe to run on every Tor start
     * (idempotent) and takes effect live, without recreating our own onion.
     */
    private fun registerPeerClientKeys(control: TorControlConnection) {
        val services = App.services()
        val privB64 = runCatching { services.clientAuth.privateKeyBase64() }.getOrNull() ?: return
        services.contacts.all().forEach { c ->
            val card = services.crypto.peerCard(c.identityHex) ?: return@forEach
            if (card.clientAuthPub.isEmpty() || !TorClientAuth.isOnionAddress(card.onionAddress)) return@forEach
            val ok = TorClientAuth.registerClientKey(control, card.onionAddress.removeSuffix(".onion"), privB64)
            if (!ok) TorState.setClientAuthDegraded(true)
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
        private const val MAX_CONTROL_RETRIES = 15
        private const val ACTION_REPUBLISH = "org.legend.legendmessage.tor.REPUBLISH"

        /** Re-publish our onion so a changed set of client-auth contacts takes effect. */
        fun republish(context: Context) {
            if (TorState.status != TorState.Status.ON) return // picked up on next start
            val intent = Intent(context, TorForegroundService::class.java).setAction(ACTION_REPUBLISH)
            runCatching { context.startService(intent) }
        }

        fun start(context: Context) {
            val intent = Intent(context, TorForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TorForegroundService::class.java))
        }
    }
}
