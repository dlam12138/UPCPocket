package com.upcpocket.app.update

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeTransport(
        private val handler: (url: String, source: UpdateSource) -> ByteArray,
    ) : UpdateTransport {
        var fetchCount = 0
        override suspend fun fetch(url: String, source: UpdateSource, maxBytes: Long): ByteArray {
            fetchCount += 1
            return handler(url, source)
        }

        override suspend fun download(
            url: String,
            source: UpdateSource,
            expectedBytes: Long,
            destination: File,
            onProgress: (Long) -> Unit,
        ) = error("not used")
    }

    private fun repository(
        transport: UpdateTransport,
        installedCode: Int = 21,
        installedName: String = "2.0.0",
    ): UpdateRepository = UpdateRepository(
        transport = transport,
        stateStore = stateStore(),
        installedVersionCode = installedCode,
        installedVersionName = installedName,
        sdkInt = 36,
        totalTimeoutMs = 10_000,
        sourceTimeoutMs = 2_000,
    )

    private fun stateStore(): UpdateStateStore =
        UpdateStateStore(File(temporaryFolder.newFolder(), "state.json"))

    private fun signed(
        pair: java.security.KeyPair,
        payload: ByteArray = UpdateTestFixtures.manifest(),
    ): Pair<ByteArray, ByteArray> =
        payload to UpdateTestFixtures.signature(payload, pair.private)

    @Test
    fun switchesSourceWhenSignatureDoesNotMatchManifest() = runTest {
        val pair = UpdateTestFixtures.testKeyPair()
        val other = UpdateTestFixtures.testKeyPair()
        val one = UpdateTestFixtures.source("one", pair)
        val two = UpdateTestFixtures.source("two", pair)
        val good = signed(pair)
        val transport = FakeTransport { url, source ->
            if (source.id == "one") {
                if (url.endsWith("update.json")) good.first
                else UpdateTestFixtures.signature(good.first, other.private)
            } else {
                if (url.endsWith("update.json")) good.first else good.second
            }
        }
        val outcome = repository(transport).check(listOf(one, two))
        assertTrue(outcome is UpdateCheckOutcome.Available)
        assertEquals("two", (outcome as UpdateCheckOutcome.Available).update.sourceId)
    }

    @Test
    fun rejectsHtmlAndFallsBackToNextSource() = runTest {
        val pair = UpdateTestFixtures.testKeyPair()
        val one = UpdateTestFixtures.source("one", pair)
        val two = UpdateTestFixtures.source("two", pair)
        val good = signed(pair)
        val transport = FakeTransport { url, source ->
            if (source.id == "one") "<html>mirror error</html>".toByteArray()
            else if (url.endsWith("update.json")) good.first else good.second
        }
        val outcome = repository(transport).check(listOf(one, two))
        assertTrue(outcome is UpdateCheckOutcome.Available)
    }

    @Test
    fun allSourcesUnavailableIsFailureNotUpToDate() = runTest {
        val pair = UpdateTestFixtures.testKeyPair()
        val sources = listOf(
            UpdateTestFixtures.source("one", pair),
            UpdateTestFixtures.source("two", pair),
        )
        val transport = FakeTransport { _, _ ->
            throw UpdateTransportException(TransportFailure.HTTP_STATUS, 404)
        }
        assertEquals(
            UpdateCheckOutcome.Failure(CheckFailure.SOURCE_UNAVAILABLE),
            repository(transport).check(sources),
        )
    }

    @Test
    fun sameCodeIsUpToDateAndDoesNotOpenAvailable() = runTest {
        val pair = UpdateTestFixtures.testKeyPair()
        val source = UpdateTestFixtures.source("one", pair)
        val payload = UpdateTestFixtures.manifest(versionName = "2.0.1", versionCode = 22)
        val pairBytes = signed(pair, payload)
        val transport = FakeTransport { url, _ ->
            if (url.endsWith("update.json")) pairBytes.first else pairBytes.second
        }
        assertEquals(
            UpdateCheckOutcome.UpToDate,
            repository(transport, installedCode = 22, installedName = "2.0.1").check(listOf(source)),
        )
    }

    @Test
    fun verifiedOlderMetadataIsRejectedAfterHigherSeenVersion() = runTest {
        val pair = UpdateTestFixtures.testKeyPair()
        val source = UpdateTestFixtures.source("one", pair)
        val payload = UpdateTestFixtures.manifest(versionName = "2.0.1", versionCode = 22)
        val bytes = signed(pair, payload)
        val store = stateStore()
        store.write(UpdateState(highestVerifiedVersionCode = 23))
        val repository = UpdateRepository(
            transport = FakeTransport { url, _ ->
                if (url.endsWith("update.json")) bytes.first else bytes.second
            },
            stateStore = store,
            installedVersionCode = 21,
            installedVersionName = "2.0.0",
            sdkInt = 36,
        )
        assertEquals(
            UpdateCheckOutcome.Failure(CheckFailure.STALE_METADATA),
            repository.check(listOf(source)),
        )
    }

    @Test
    fun unsupportedMinSdkIsReportedAsUnsupportedNotUpdate() = runTest {
        val pair = UpdateTestFixtures.testKeyPair()
        val source = UpdateTestFixtures.source("one", pair)
        val payload = UpdateTestFixtures.manifest(minSdk = 99)
        val bytes = signed(pair, payload)
        val transport = FakeTransport { url, _ ->
            if (url.endsWith("update.json")) bytes.first else bytes.second
        }
        val outcome = repository(transport).check(listOf(source))
        assertTrue(outcome is UpdateCheckOutcome.UnsupportedDevice)
        assertEquals(23, (outcome as UpdateCheckOutcome.UnsupportedDevice).update.manifest.versionCode)
    }

    @Test
    fun signedWrongPackageStopsAsInvalidMetadata() = runTest {
        val pair = UpdateTestFixtures.testKeyPair()
        val source = UpdateTestFixtures.source("one", pair)
        val payload = UpdateTestFixtures.manifest(packageName = "com.example.app")
        val bytes = signed(pair, payload)
        val transport = FakeTransport { url, _ ->
            if (url.endsWith("update.json")) bytes.first else bytes.second
        }
        assertEquals(
            UpdateCheckOutcome.Failure(CheckFailure.INVALID_METADATA),
            repository(transport).check(listOf(source)),
        )
    }

    @Test
    fun badSignatureOnOnlySourceIsFailure() = runTest {
        val pair = UpdateTestFixtures.testKeyPair()
        val source = UpdateTestFixtures.source("one", pair)
        val payload = UpdateTestFixtures.manifest()
        val signature = UpdateTestFixtures.signature(payload, UpdateTestFixtures.testKeyPair().private)
        val transport = FakeTransport { url, _ ->
            if (url.endsWith("update.json")) payload else signature
        }
        assertEquals(
            UpdateCheckOutcome.Failure(CheckFailure.SIGNATURE_INVALID),
            repository(transport).check(listOf(source)),
        )
    }
}
