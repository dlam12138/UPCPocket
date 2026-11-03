package com.upcpocket.app.widget

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.card.CardWidgetRead
import com.upcpocket.app.card.CardWidgetRevision
import com.upcpocket.app.card.CardWidgetViewState
import com.upcpocket.app.data.ecard.CardBalanceBinding
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组件发布时序的确定性测试。全部使用虚构账号/金额，不读取真实凭据、不联网。
 */
class CardWidgetPublisherTest {

    private val idA = "2099000001"
    private val idB = "2099000002"
    private val bindingA = CardBalanceBinding(
        accountId = SchoolAccountId("acct_test_publish_a"),
        studentId = idA,
        credentialGeneration = AccountGeneration(1),
    )
    private val bindingB = CardBalanceBinding(
        accountId = SchoolAccountId("acct_test_publish_b"),
        studentId = idB,
        credentialGeneration = AccountGeneration(1),
    )
    private val snapshotA = DirectCardSnapshot(
        accountId = bindingA.accountId.value,
        studentId = idA,
        credentialGeneration = 1,
        balanceCents = 12345,
        transitionCents = 678,
        fetchedAt = 1_700_000_000_000,
    )
    private val stateA = CardWidgetViewState(
        CardBindingStatus(CardBindingAvailability.READY, bindingA),
        snapshotA,
    )
    private val stateB = CardWidgetViewState(
        CardBindingStatus(CardBindingAvailability.READY, bindingB),
        snapshot = null,
    )
    private val revA = CardWidgetRevision(
        availability = CardBindingAvailability.READY,
        bindingToken = "${bindingA.accountId.value}:1",
        businessEpoch = 0,
        snapshotRevision = 1,
    )
    private val revB = CardWidgetRevision(
        availability = CardBindingAvailability.READY,
        bindingToken = "${bindingB.accountId.value}:1",
        businessEpoch = 0,
        snapshotRevision = 2,
    )

    private class Recorder {
        val states = mutableListOf<CardWidgetViewState>()
        fun accept(state: CardWidgetViewState) {
            synchronized(states) { states += state }
        }
        fun last(): CardWidgetViewState? = synchronized(states) { states.lastOrNull() }
        fun snapshots(): List<DirectCardSnapshot?> = synchronized(states) { states.map { it.snapshot } }
    }

    @Test
    fun `late A read after B empty cannot publish old balance`() = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference(revA)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val recorder = Recorder()
        val publisher = CardWidgetPublisher(
            readSuspending = {
                if (calls.getAndIncrement() == 0) {
                    started.complete(Unit)
                    release.await()
                    CardWidgetRead(stateA, revA)
                } else {
                    CardWidgetRead(stateB, revB)
                }
            },
            readBlocking = { CardWidgetRead(stateB, revB) },
            currentRevision = { current.get() },
            sink = recorder::accept,
        )

        val lateA = async(Dispatchers.Default) { publisher.publishLatest() }
        started.await()
        current.set(revB)
        val emptyB = async(Dispatchers.Default) { publisher.publishLatest() }
        assertTrue(emptyB.await())
        release.complete(Unit)
        assertFalse("旧 A 读取不得在 B 空态之后发布", lateA.await())

        val last = recorder.last()!!
        assertEquals(bindingB.accountId, last.status.binding?.accountId)
        assertNull(last.snapshot)
        assertFalse(recorder.snapshots().any { it?.studentId == idA })
    }

    @Test
    fun `Provider synchronous publish can supersede a suspended stale read`() = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference(revA)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val recorder = Recorder()
        val publisher = CardWidgetPublisher(
            readSuspending = {
                if (calls.getAndIncrement() == 0) {
                    started.complete(Unit)
                    release.await()
                    CardWidgetRead(stateA, revA)
                } else {
                    CardWidgetRead(stateB, revB)
                }
            },
            readBlocking = { CardWidgetRead(stateB, revB) },
            currentRevision = { current.get() },
            sink = recorder::accept,
        )

        val stale = async(Dispatchers.Default) { publisher.publishLatest() }
        started.await()
        current.set(revB)
        assertTrue(publisher.publishLatestNow())
        release.complete(Unit)
        assertFalse(stale.await())
        assertEquals(bindingB.accountId, recorder.last()?.status?.binding?.accountId)
        assertNull(recorder.last()?.snapshot)
    }

    @Test
    fun `revision change without a new publish still rejects the stale read`() = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference(revA)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recorder = Recorder()
        val publisher = CardWidgetPublisher(
            readSuspending = {
                started.complete(Unit)
                release.await()
                CardWidgetRead(stateA, revA)
            },
            readBlocking = { CardWidgetRead(stateA, revA) },
            currentRevision = { current.get() },
            sink = recorder::accept,
        )

        val stale = async(Dispatchers.Default) { publisher.publishLatest() }
        started.await()
        current.set(revB)
        release.complete(Unit)
        assertFalse(stale.await())
        assertTrue(recorder.states.isEmpty())
    }

    @Test
    fun `later publish wins when old publish already reached the sink`() = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference(revA)
        val next = java.util.concurrent.atomic.AtomicReference(CardWidgetRead(stateA, revA))
        val recorder = Recorder()
        val publisher = CardWidgetPublisher(
            readSuspending = { next.get() },
            readBlocking = { next.get() },
            currentRevision = { current.get() },
            sink = recorder::accept,
        )

        assertTrue(publisher.publishLatest())
        assertEquals(idA, recorder.last()?.snapshot?.studentId)

        current.set(revB)
        next.set(CardWidgetRead(stateB, revB))
        assertTrue(publisher.publishLatest())
        assertEquals(bindingB.accountId, recorder.last()?.status?.binding?.accountId)
        assertNull(recorder.last()?.snapshot)
        assertEquals(2, recorder.states.size)
    }

    @Test
    fun `delete empty state cannot be overwritten by a suspended A read`() = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference(revA)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val deletedState = CardWidgetViewState(
            CardBindingStatus(CardBindingAvailability.NO_ACCOUNT),
            snapshot = null,
        )
        val deletedRevision = CardWidgetRevision(
            availability = CardBindingAvailability.NO_ACCOUNT,
            bindingToken = null,
            businessEpoch = 1,
            snapshotRevision = 2,
        )
        val recorder = Recorder()
        val publisher = CardWidgetPublisher(
            readSuspending = {
                if (calls.getAndIncrement() == 0) {
                    started.complete(Unit)
                    release.await()
                    CardWidgetRead(stateA, revA)
                } else {
                    CardWidgetRead(deletedState, deletedRevision)
                }
            },
            readBlocking = { CardWidgetRead(deletedState, deletedRevision) },
            currentRevision = { current.get() },
            sink = recorder::accept,
        )

        val stale = async(Dispatchers.Default) { publisher.publishLatest() }
        started.await()
        current.set(deletedRevision)
        assertTrue(publisher.publishLatestNow())
        release.complete(Unit)
        assertFalse(stale.await())
        assertEquals(CardBindingAvailability.NO_ACCOUNT, recorder.last()?.status?.availability)
        assertNull(recorder.last()?.snapshot)
    }

    @Test
    fun `clear empty state cannot be overwritten by a suspended A read`() = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference(revA)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val clearedState = CardWidgetViewState(
            CardBindingStatus(CardBindingAvailability.READY, bindingA),
            snapshot = null,
        )
        val clearedRevision = CardWidgetRevision(
            availability = CardBindingAvailability.READY,
            bindingToken = "${bindingA.accountId.value}:1",
            businessEpoch = 1,
            snapshotRevision = 2,
        )
        val recorder = Recorder()
        val publisher = CardWidgetPublisher(
            readSuspending = {
                if (calls.getAndIncrement() == 0) {
                    started.complete(Unit)
                    release.await()
                    CardWidgetRead(stateA, revA)
                } else {
                    CardWidgetRead(clearedState, clearedRevision)
                }
            },
            readBlocking = { CardWidgetRead(clearedState, clearedRevision) },
            currentRevision = { current.get() },
            sink = recorder::accept,
        )

        val stale = async(Dispatchers.Default) { publisher.publishLatest() }
        started.await()
        current.set(clearedRevision)
        assertTrue(publisher.publishLatestNow())
        release.complete(Unit)
        assertFalse(stale.await())
        assertEquals(CardBindingAvailability.READY, recorder.last()?.status?.availability)
        assertEquals(bindingA.accountId, recorder.last()?.status?.binding?.accountId)
        assertNull(recorder.last()?.snapshot)
    }

    @Test
    fun `same account password update keeps history only with the new revision`() = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference(revA)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val bindingA2 = bindingA.copy(credentialGeneration = AccountGeneration(2))
        val revA2 = CardWidgetRevision(
            availability = CardBindingAvailability.READY,
            bindingToken = "${bindingA.accountId.value}:2",
            businessEpoch = 0,
            snapshotRevision = 2,
        )
        // 同号改密后是历史值，但绑定代次必须是新的；这是“可保留历史值”路径。
        val historyState = CardWidgetViewState(
            CardBindingStatus(CardBindingAvailability.READY, bindingA2),
            snapshotA,
        )
        val recorder = Recorder()
        val publisher = CardWidgetPublisher(
            readSuspending = {
                if (calls.getAndIncrement() == 0) {
                    started.complete(Unit)
                    release.await()
                    CardWidgetRead(stateA, revA) // 旧代次，不能当作新值发布
                } else {
                    CardWidgetRead(historyState, revA2)
                }
            },
            readBlocking = { CardWidgetRead(historyState, revA2) },
            currentRevision = { current.get() },
            sink = recorder::accept,
        )

        val old = async(Dispatchers.Default) { publisher.publishLatest() }
        started.await()
        current.set(revA2)
        assertTrue(publisher.publishLatestNow())
        release.complete(Unit)
        assertFalse(old.await())
        val last = recorder.last()!!
        assertEquals(AccountGeneration(2), last.status.binding?.credentialGeneration)
        assertEquals(1L, last.snapshot?.credentialGeneration)
    }
}
