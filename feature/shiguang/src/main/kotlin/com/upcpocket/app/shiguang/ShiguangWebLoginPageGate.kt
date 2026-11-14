package com.upcpocket.app.shiguang

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Thread-safe page generations and one-time completion claim for a single login attempt. */
internal class ShiguangWebLoginPageGate {
    private val generation = AtomicInteger(0)
    private val completionClaimed = AtomicBoolean(false)
    private val pageInactive = AtomicBoolean(false)
    private var reloadClaimed = false

    val currentGeneration: Int get() = generation.get()

    fun isCurrentGeneration(pageGeneration: Int): Boolean = generation.get() == pageGeneration

    @Synchronized
    fun isPageActive(pageGeneration: Int, attemptIsCurrent: Boolean): Boolean =
        isCurrentGeneration(pageGeneration) && attemptIsCurrent && !completionClaimed.get() && !pageInactive.get()

    /** Keep a failed page inactive until an explicit, successful reload. */
    @Synchronized
    fun claimPageFailure(pageGeneration: Int, attemptIsCurrent: Boolean): Boolean {
        if (generation.get() != pageGeneration || !attemptIsCurrent || completionClaimed.get() || pageInactive.get()) return false
        pageInactive.set(true)
        reloadClaimed = false
        return true
    }

    /** Claim atomically with reload so a stale page cannot win while its replacement starts. */
    @Synchronized
    fun claimCompletion(pageGeneration: Int, attemptIsCurrent: Boolean): Boolean {
        if (generation.get() != pageGeneration || !attemptIsCurrent || completionClaimed.get() || pageInactive.get()) return false
        completionClaimed.set(true)
        return true
    }

    fun isCompletionClaimed(): Boolean = completionClaimed.get()

    /** Claim the synchronous UI action before destroying the old WebView. */
    @Synchronized
    fun claimReload(pageGeneration: Int, attemptIsCurrent: Boolean): Boolean {
        if (generation.get() != pageGeneration || !attemptIsCurrent || completionClaimed.get() || reloadClaimed) return false
        reloadClaimed = true
        // Close the current page to callbacks before asking WebView to destroy it.
        pageInactive.set(true)
        return true
    }

    @Synchronized
    fun isReloadClaimed(): Boolean = reloadClaimed

    /** Release a failed destroy or finish loading the replacement page. */
    @Synchronized
    fun releaseReloadClaim(pageGeneration: Int): Boolean {
        if (generation.get() != pageGeneration || completionClaimed.get() || !reloadClaimed) return false
        reloadClaimed = false
        return true
    }

    /** A submitted result owns this attempt until it succeeds, fails, or the dialog closes. */
    @Synchronized
    fun advanceForReload(): Int {
        check(!completionClaimed.get()) { "Cannot reload while login verification is in progress" }
        check(reloadClaimed) { "Reload must be claimed before replacing the page" }
        pageInactive.set(false)
        return generation.incrementAndGet()
    }
}
