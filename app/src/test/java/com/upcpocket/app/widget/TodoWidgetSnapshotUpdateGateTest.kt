package com.upcpocket.app.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoWidgetSnapshotUpdateGateTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun snapshotReadAndPublishStayOrderedWhenTheNextUpdateArrivesMidRead() = runTest {
        val gate = TodoWidgetSnapshotUpdateGate()
        val events = mutableListOf<String>()
        val firstReadStarted = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        val secondReadStarted = CompletableDeferred<Unit>()

        val firstUpdate = async {
            gate.withSerializedUpdate {
                events += "read first"
                firstReadStarted.complete(Unit)
                releaseFirstRead.await()
                events += "publish first"
            }
        }
        firstReadStarted.await()

        val secondUpdate = async {
            gate.withSerializedUpdate {
                events += "read second"
                secondReadStarted.complete(Unit)
                events += "publish second"
            }
        }
        runCurrent()

        assertFalse(secondReadStarted.isCompleted)
        assertEquals(listOf("read first"), events)

        releaseFirstRead.complete(Unit)
        firstUpdate.await()
        secondUpdate.await()
        advanceUntilIdle()

        assertTrue(secondReadStarted.isCompleted)
        assertEquals(
            listOf("read first", "publish first", "read second", "publish second"),
            events,
        )
    }
}
