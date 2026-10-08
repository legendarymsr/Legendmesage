package org.legend.legendmessage.tor

import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Ports shared between the onion service and the local peer listener. */
object TorConfig {
    /** Virtual port advertised on the onion address; peers dial onion:VIRTUAL_PORT. */
    const val VIRTUAL_PORT = 9999

    /** Local port the in-app peer server listens on; the onion forwards here. */
    const val LOCAL_PORT = 17761
}

/**
 * Process-wide, observable Tor status. [TorForegroundService] writes to it;
 * the UI observes. Also the single place that knows how to open an outbound
 * connection to a peer's onion through Tor's SOCKS proxy.
 */
object TorState {
    enum class Status { OFF, STARTING, ON, ERROR }

    @Volatile
    var status: Status = Status.OFF
        private set

    @Volatile
    var onionAddress: String = ""
        private set

    @Volatile
    var socksPort: Int = 0
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun update(
        status: Status = this.status,
        onionAddress: String = this.onionAddress,
        socksPort: Int = this.socksPort,
    ) {
        this.status = status
        this.onionAddress = onionAddress
        this.socksPort = socksPort
        main.post { listeners.forEach { it() } }
    }

    /**
     * Open a socket to [host]:[port] through Tor's SOCKS proxy. Uses an
     * unresolved address so the onion hostname is resolved by Tor, not locally.
     */
    @Throws(Exception::class)
    fun openThroughTor(host: String, port: Int, timeoutMs: Int = 60_000): Socket {
        check(socksPort > 0) { "Tor SOCKS port not known yet" }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val socket = Socket(proxy)
        socket.connect(InetSocketAddress.createUnresolved(host, port), timeoutMs)
        return socket
    }
}
