package com.upcpocket.app.update

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = current
    }

    private fun manifest(code: Int = 23) = UpdateManifest(
        versionName = "2.0.${code - 20}",
        versionCode = code,
        minSdk = 31,
        tag = "v2.0.${code - 20}",
        assetName = "UPC-Pocket-2.0.${code - 20}.apk",
        sizeBytes = 300_000,
        sha256 = "ab".repeat(32),
        publishedAt = "2026-11-06T03:30:00Z",
        notes = listOf("one", "two", "three"),
    )

    private fun source() = UpdateSource(
        id = "one",
        label = "one",
        baseUrl = "https://example.com/one",
        publicKeyBase64 = "unused",
        cleartextAllowed = false,
        allowedRedirectHosts = setOf("example.com"),
    )

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        clock: MutableClock,
        checker: suspend (List<UpdateSource>) -> UpdateCheckOutcome,
        downloader: suspend (
            UpdateManifest,
            List<UpdateSource>,
            (String, Int, Int) -> Unit,
            (Long) -> Unit,
        ) -> UpdateDownloadOutcome = { _, _, _, _ -> UpdateDownloadOutcome.Failure(UpdateDownloadFailure.INTERNAL) },
    ): UpdateController {
        val root = temporaryFolder.newFolder()
        return UpdateController(
            scope = scope,
            installedVersionName = "2.0.1",
            installedVersionCode = 22,
            sources = listOf(source()),
            stateStore = UpdateStateStore(File(root, "state.json")),
            storage = UpdateStorage(File(root, "cache")),
            checker = checker,
            downloader = downloader,
            apkVerifier = null,
            installer = null,
            clock = clock,
        )
    }

    @Test
    fun automaticCheckRunsAtMostOncePerDay() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-06T00:00:00Z"))
        val calls = AtomicInteger()
        val controller = controller(scope = this, clock = clock, checker = {
            calls.incrementAndGet()
            UpdateCheckOutcome.UpToDate
        })
        controller.onForeground()
        advanceUntilIdle()
        assertEquals(1, calls.get())
        controller.onForeground()
        advanceUntilIdle()
        assertEquals(1, calls.get())

        clock.current = Instant.parse("2026-11-07T00:00:00Z")
        controller.onForeground()
        advanceUntilIdle()
        assertEquals(2, calls.get())
    }

    @Test
    fun disabledAutomaticCheckNeverRunsButManualDoes() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-06T00:00:00Z"))
        val calls = AtomicInteger()
        val controller = controller(scope = this, clock = clock, checker = {
            calls.incrementAndGet()
            UpdateCheckOutcome.UpToDate
        })
        controller.setAutoCheckEnabled(false)
        controller.onForeground()
        advanceUntilIdle()
        assertEquals(0, calls.get())
        controller.checkNow()
        advanceUntilIdle()
        assertEquals(1, calls.get())
        assertFalse(controller.state.value.autoCheckEnabled)
    }

    @Test
    fun duplicateManualChecksShareOneRequestAndOpenDialogAtEnd() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-06T00:00:00Z"))
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val update = VerifiedUpdate(manifest(), "one")
        val controller = controller(scope = this, clock = clock, checker = {
            calls.incrementAndGet()
            gate.await()
            UpdateCheckOutcome.Available(update)
        })
        controller.checkNow()
        runCurrent()
        assertEquals(1, calls.get())
        controller.checkNow()
        runCurrent()
        assertEquals(1, calls.get())
        assertTrue(controller.state.value.checking)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(controller.state.value.checking)
        assertTrue(controller.state.value.dialogOpen)
        assertNotNull(controller.state.value.available)
    }

    @Test
    fun autoAvailableDoesNotOpenDialog() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-06T00:00:00Z"))
        val update = VerifiedUpdate(manifest(), "one")
        val controller = controller(scope = this, clock = clock, checker = {
            UpdateCheckOutcome.Available(update)
        })
        controller.onForeground()
        advanceUntilIdle()
        assertNotNull(controller.state.value.available)
        assertFalse(controller.state.value.dialogOpen)
        assertFalse(controller.state.value.checking)
    }

    @Test
    fun dismissRecordsVersionAndDoesNotRemoveAvailableUpdate() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-06T00:00:00Z"))
        val update = VerifiedUpdate(manifest(), "one")
        val controller = controller(scope = this, clock = clock, checker = { UpdateCheckOutcome.Available(update) })
        controller.checkNow()
        advanceUntilIdle()
        controller.dismissDialog()
        assertFalse(controller.state.value.dialogOpen)
        assertEquals(23, controller.state.value.available?.manifest?.versionCode)
    }

    @Test
    fun duplicateDownloadStartsOnlyOnce() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-06T00:00:00Z"))
        val update = VerifiedUpdate(manifest(), "one")
        val gate = CompletableDeferred<Unit>()
        val downloads = AtomicInteger()
        val controller = controller(
            scope = this,
            clock = clock,
            checker = { UpdateCheckOutcome.Available(update) },
            downloader = { _, _, _, _ ->
                downloads.incrementAndGet()
                gate.await()
                UpdateDownloadOutcome.Success(File(temporaryFolder.root, "x.apk"), "one")
            },
        )
        controller.checkNow()
        advanceUntilIdle()
        controller.startDownload()
        runCurrent()
        controller.startDownload()
        runCurrent()
        assertEquals(1, downloads.get())
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(controller.state.value.download is DownloadUiState.Ready)
        assertNull((controller.state.value.download as? DownloadUiState.Failed))
    }
}
