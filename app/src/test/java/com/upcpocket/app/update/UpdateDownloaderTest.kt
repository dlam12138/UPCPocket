package com.upcpocket.app.update

import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeTransport(
        private val write: suspend (source: UpdateSource, destination: File, progress: (Long) -> Unit) -> Unit,
    ) : UpdateTransport {
        var downloadCount = 0
        override suspend fun fetch(url: String, source: UpdateSource, maxBytes: Long): ByteArray =
            error("not used")

        override suspend fun download(
            url: String,
            source: UpdateSource,
            expectedBytes: Long,
            destination: File,
            onProgress: (Long) -> Unit,
        ) {
            downloadCount += 1
            write(source, destination, onProgress)
        }
    }

    private class FakeVerifier(var result: ApkVerification = ApkVerification.Valid) : ApkVerifier {
        override fun verify(file: File, manifest: UpdateManifest): ApkVerification = result
    }

    private fun manifest(bytes: ByteArray, code: Int = 23): UpdateManifest = UpdateManifest(
        versionName = "2.0.$code",
        versionCode = code,
        minSdk = 31,
        tag = "v2.0.$code",
        assetName = "UPC-Pocket-2.0.$code.apk",
        sizeBytes = bytes.size.toLong(),
        sha256 = UpdateTestFixtures.sha256(bytes),
        publishedAt = "2026-11-06T03:30:00Z",
        notes = listOf("one", "two", "three"),
    )

    private fun source(id: String) = UpdateSource(
        id = id,
        label = id,
        baseUrl = "https://example.com/$id",
        publicKeyBase64 = "unused",
        cleartextAllowed = false,
        allowedRedirectHosts = setOf("example.com"),
    )

    @Test
    fun writesPartThenPublishesOnlyAfterVerification() = runTest {
        val root = temporaryFolder.newFolder("updates")
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val storage = UpdateStorage(root)
        val transport = FakeTransport { _, destination, progress ->
            destination.writeBytes(bytes)
            progress(bytes.size.toLong())
        }
        val outcome = UpdateDownloader(transport, storage, FakeVerifier()).download(
            manifest(bytes),
            listOf(source("one")),
            { _, _, _ -> },
            {},
        )
        assertTrue(outcome is UpdateDownloadOutcome.Success)
        val final = (outcome as UpdateDownloadOutcome.Success).file
        assertTrue(final.isFile)
        assertEquals(bytes.size.toLong(), final.length())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun hashMismatchOnOneMirrorFallsBackToNext() = runTest {
        val root = temporaryFolder.newFolder("updates")
        val bytes = ByteArray(300_000) { (it % 249).toByte() }
        val storage = UpdateStorage(root)
        val transport = FakeTransport { source, destination, progress ->
            if (source.id == "one") {
                destination.writeBytes(ByteArray(bytes.size) { 7 })
                progress(bytes.size.toLong())
            } else {
                destination.writeBytes(bytes)
                progress(bytes.size.toLong())
            }
        }
        val outcome = UpdateDownloader(transport, storage, FakeVerifier()).download(
            manifest(bytes),
            listOf(source("one"), source("two")),
            { _, _, _ -> },
            {},
        )
        assertTrue(outcome is UpdateDownloadOutcome.Success)
        assertEquals(2, transport.downloadCount)
        assertEquals("two", (outcome as UpdateDownloadOutcome.Success).sourceId)
    }

    @Test
    fun packageSignatureMismatchIsFatalAndDeletesPart() = runTest {
        val root = temporaryFolder.newFolder("updates")
        val bytes = ByteArray(300_000) { 3 }
        val storage = UpdateStorage(root)
        val transport = FakeTransport { _, destination, progress ->
            destination.writeBytes(bytes)
            progress(bytes.size.toLong())
        }
        val outcome = UpdateDownloader(
            transport,
            storage,
            FakeVerifier(ApkVerification.Invalid(ApkInvalidReason.SIGNER)),
        ).download(
            manifest(bytes),
            listOf(source("one"), source("two")),
            { _, _, _ -> },
            {},
        )
        assertEquals(UpdateDownloadOutcome.Failure(UpdateDownloadFailure.APK_INVALID), outcome)
        assertEquals(1, transport.downloadCount)
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun cancellationDeletesPartialFile() = runTest {
        val root = temporaryFolder.newFolder("updates")
        val bytes = ByteArray(300_000) { 5 }
        val storage = UpdateStorage(root)
        val transport = FakeTransport { _, destination, _ ->
            destination.writeBytes(bytes.copyOf(1024))
            awaitCancellation()
        }
        val downloader = UpdateDownloader(transport, storage, FakeVerifier())
        val job = launch {
            downloader.download(
                manifest(bytes),
                listOf(source("one")),
                { _, _, _ -> },
                {},
            )
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".apk") })
    }

    @Test
    fun completeExistingFileIsReusedWithoutNetwork() = runTest {
        val root = temporaryFolder.newFolder("updates")
        val bytes = ByteArray(300_000) { 9 }
        val storage = UpdateStorage(root)
        storage.finalFile(23).writeBytes(bytes)
        val transport = FakeTransport { _, _, _ -> error("must not download") }
        val outcome = UpdateDownloader(transport, storage, FakeVerifier()).download(
            manifest(bytes),
            listOf(source("one")),
            { _, _, _ -> },
            {},
        )
        assertTrue(outcome is UpdateDownloadOutcome.Success)
        assertEquals(0, transport.downloadCount)
        assertFalse(storage.partFile(23).exists())
    }
}
