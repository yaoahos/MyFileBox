package com.yaoahos.myfilebox.android

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny process-wide cancellation registry. The transfer coroutine polls this
 * between chunks/files, while the notification action flips it from a
 * BroadcastReceiver. It also lets the UI cancel immediately without holding a
 * Job reference in Compose state.
 */
object TransferCancellation {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun request(id: String) {
        if (id.isNotBlank() && cancelled.add(id)) {
            listeners.forEach { it(id) }
        }
    }

    fun isCancelled(id: String): Boolean = cancelled.contains(id)

    fun clear(id: String) {
        cancelled -= id
    }

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }
}
