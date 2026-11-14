package com.upcpocket.app.shiguang

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiguangWebLoginPageGateTest {
    @Test fun onlyOneConcurrentCallbackCanClaimWhileExchangeIsPending() {
        val gate = ShiguangWebLoginPageGate()
        val callbackCount = 12
        val ready = CountDownLatch(callbackCount)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(callbackCount)

        try {
            val claims = (0 until callbackCount).map {
                pool.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    gate.claimCompletion(gate.currentGeneration, attemptIsCurrent = true)
                }
            }
            assertTrue("callbacks did not reach the exchange barrier", ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(gate.isCompletionClaimed())
            assertFalse(gate.isPageActive(gate.currentGeneration, attemptIsCurrent = true))
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun canceledOrReplacedAttemptCannotClaimItsPage() {
        val gate = ShiguangWebLoginPageGate()
        val page = gate.currentGeneration

        assertFalse(gate.isPageActive(page, attemptIsCurrent = false))
        assertFalse(gate.claimCompletion(page, attemptIsCurrent = false))
        assertFalse(gate.isCompletionClaimed())
    }

    @Test fun oldPageCallbacksAreIgnoredAfterExplicitReload() {
        val gate = ShiguangWebLoginPageGate()
        val oldPage = gate.currentGeneration
        assertTrue(gate.claimPageFailure(oldPage, attemptIsCurrent = true))
        assertFalse(gate.isPageActive(oldPage, attemptIsCurrent = true))
        assertTrue(gate.claimReload(oldPage, attemptIsCurrent = true))
        val currentPage = gate.advanceForReload()

        assertEquals(oldPage + 1, currentPage)
        assertTrue(gate.isPageActive(currentPage, attemptIsCurrent = true))
        assertFalse(gate.claimCompletion(oldPage, attemptIsCurrent = true))
        assertTrue(gate.claimCompletion(currentPage, attemptIsCurrent = true))
    }

    @Test fun laterCallbacksCannotClearOrReplaceAnErrorBeforeReload() {
        val gate = ShiguangWebLoginPageGate()
        val page = gate.currentGeneration

        assertTrue(gate.claimPageFailure(page, attemptIsCurrent = true))
        assertFalse(gate.claimPageFailure(page, attemptIsCurrent = true))
        assertFalse(gate.claimCompletion(page, attemptIsCurrent = true))
        assertFalse(gate.isPageActive(page, attemptIsCurrent = true))
    }

    @Test fun reloadCannotReleaseAttemptAfterVerificationWasClaimed() {
        val gate = ShiguangWebLoginPageGate()
        val page = gate.currentGeneration
        assertTrue(gate.claimCompletion(page, attemptIsCurrent = true))

        try {
            gate.advanceForReload()
            throw AssertionError("reload unexpectedly advanced during verification")
        } catch (_: IllegalStateException) {
            // The attempt stays claimed until it succeeds, fails, or the dialog closes.
        }

        assertEquals(page, gate.currentGeneration)
    }

    @Test fun concurrentReloadClicksClaimOnceAndFailedDestroyCanBeRetried() {
        val gate = ShiguangWebLoginPageGate()
        val page = gate.currentGeneration
        val clickCount = 12
        val ready = CountDownLatch(clickCount)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(clickCount)

        try {
            val claims = (0 until clickCount).map {
                pool.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    gate.claimReload(page, attemptIsCurrent = true)
                }
            }
            assertTrue("reload clicks did not reach the barrier", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            start.countDown()
            pool.shutdownNow()
        }

        assertTrue(gate.isReloadClaimed())
        assertFalse(gate.isPageActive(page, attemptIsCurrent = true))
        assertFalse(gate.claimCompletion(page, attemptIsCurrent = true))

        // Simulate the old WebView refusing to destroy: release only the transient
        // click lock, while the old page remains inactive until a retry advances it.
        assertTrue(gate.releaseReloadClaim(page))
        assertFalse(gate.isPageActive(page, attemptIsCurrent = true))
        assertTrue(gate.claimReload(page, attemptIsCurrent = true))
        val replacement = gate.advanceForReload()
        assertEquals(page + 1, replacement)
        assertTrue(gate.isReloadClaimed())
        assertTrue(gate.isPageActive(replacement, attemptIsCurrent = true))
        assertFalse(gate.claimReload(replacement, attemptIsCurrent = true))
        assertTrue(gate.releaseReloadClaim(replacement))
        assertTrue(gate.claimReload(replacement, attemptIsCurrent = true))
    }
}
