package com.upcpocket.app.shiguang

/** Main-thread ownership of a browser and its account-cleanup registration. */
internal class ShiguangWebViewOwnership<T : Any>(private val destroy: (T) -> Unit) {
    private var current: T? = null
    private var registration: AutoCloseable? = null
    private val rejected = mutableListOf<T>()
    private var closed = false

    /** A reload cannot replace a browser whose previous destruction failed. */
    fun attach(view: T): Boolean {
        if (!closed && current == null && registration == null && rejected.isEmpty()) {
            current = view
            return true
        }
        if (current === view) return !closed
        rejected += view
        // Never let a rejected browser take the old cleanup registration. Retain
        // its handle too if destroying the unused candidate unexpectedly fails.
        runCatching { release(view) }
        return false
    }

    fun setRegistration(handle: AutoCloseable?) {
        check(current != null && registration == null)
        registration = handle
    }

    fun release(view: T) {
        when {
            current === view -> {
                destroy(view)
                current = null
            }
            rejected.any { it === view } -> {
                destroy(view)
                rejected.removeAll { it === view }
            }
            else -> return
        }
        unregisterIfEmpty()
    }

    fun close() {
        closed = true
        releaseAll()
    }

    /** Release before the next factory runs; unlike close(), the lease remains reusable. */
    fun releaseForReload() {
        check(!closed)
        releaseAll()
    }

    private fun releaseAll() {
        var failure: Throwable? = null
        (listOfNotNull(current) + rejected.toList()).forEach { view ->
            try { release(view) } catch (error: Throwable) { if (failure == null) failure = error }
        }
        if (current == null && rejected.isEmpty()) {
            try { unregisterIfEmpty() } catch (error: Throwable) { if (failure == null) failure = error }
        }
        failure?.let { throw it }
    }

    private fun unregisterIfEmpty() {
        if (current == null && rejected.isEmpty()) {
            registration?.close()
            registration = null
        }
    }
}
