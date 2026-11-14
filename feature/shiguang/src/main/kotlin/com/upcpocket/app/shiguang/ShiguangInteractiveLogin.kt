package com.upcpocket.app.shiguang

/** Process-local request. Never put a callback URL or account identifier into UI saved state. */
class ShiguangInteractiveAttempt internal constructor(val id: Long, val entryUrl: String) {
    override fun toString() = "ShiguangInteractiveAttempt(id=$id, entryUrl=[REDACTED])"
}
interface ShiguangWebViewLease {
    fun dispose()
    suspend fun disposeAndAwait(): Boolean
}
