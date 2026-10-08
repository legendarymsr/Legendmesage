package org.legend.legendmessage.net

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Notifies the UI (on the main thread) that a peer's conversation changed. */
object MessageBus {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged(peerHex: String) {
        main.post { listeners.forEach { it(peerHex) } }
    }
}
