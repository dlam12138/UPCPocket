package com.upcpocket.app.widget

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps each Todo snapshot read, cache update, and publication in one ordered operation. */
internal class TodoWidgetSnapshotUpdateGate {
    private val mutex = Mutex()

    suspend fun <T> withSerializedUpdate(block: suspend () -> T): T = mutex.withLock {
        block()
    }
}
