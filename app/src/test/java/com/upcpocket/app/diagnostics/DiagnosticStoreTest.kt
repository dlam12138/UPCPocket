package com.upcpocket.app.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticStoreTest {

    private lateinit var context: Context
    private lateinit var directory: File
    private var now = 1_700_000_000_000L
    private val stores = mutableListOf<DiagnosticStore>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        directory = File.createTempFile("diagnostics-test", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        File(context.cacheDir, "diagnostic_exports").listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        stores.forEach { it.close() }
        Diagnostics.install(null)
        directory.deleteRecursively()
        File(context.cacheDir, "diagnostic_exports").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun exportContainsOnlyWhitelistedFieldsAndNoSecretCanary() = runBlocking {
        val store = store()
        Diagnostics.install(store)
        val failure = RuntimeException("PASSWORD_canary?token=secret")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.upcpocket.app.DiagnosticsCanary", "boom", "X.kt", 1),
        )
        assertTrue(
            Diagnostics.recordException(
                module = DiagnosticModule.TIMETABLE,
                action = DiagnosticAction.WEBVIEW_MAIN_FRAME_ERROR,
                result = DiagnosticResult.FAIL,
                reason = DiagnosticReason.NETWORK,
                failure = failure,
            ),
        )
        assertTrue(
            Diagnostics.record(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.WEBVIEW_VIEWPORT,
                result = DiagnosticResult.OK,
                reason = DiagnosticReason.VIEWPORT,
                widthDp = 412,
                heightDp = 839,
                operationId = "a1b2c3d4",
            ),
        )
        val result = store.export(device()) as DiagnosticExportResult.Success
        val entries = unzip(result.uri)
        assertTrue(entries.containsKey("summary.txt"))
        assertTrue(entries.containsKey("events.jsonl"))
        val all = entries.values.joinToString("\n")
        assertFalse(all.contains("PASSWORD_canary"))
        assertFalse(all.contains("token=secret"))
        assertFalse(all.contains("DiagnosticsCanary.kt"))
        assertTrue(all.contains("com.upcpocket.app.DiagnosticsCanary.boom"))
        assertTrue(all.contains("\"schemaVersion\":1"))
        assertTrue(all.contains("\"widthDp\":412"))
        assertTrue(all.contains("appVersion="))
        assertFalse(all.contains("accountId"))
        assertFalse(all.contains("cookie"))
        assertFalse(all.contains("http://"))
        assertFalse(all.contains("https://"))
    }

    @Test
    fun summaryDoesNotTreatPendingStartAsAnError() = runBlocking {
        val store = store()
        store.record(event(action = DiagnosticAction.QUERY_START))
        val result = store.export(device()) as DiagnosticExportResult.Success
        val summary = unzip(result.uri).getValue("summary.txt")
        assertFalse(summary.contains("QUERY_START PENDING"))
        assertTrue(summary.contains("recentErrors:\n- none"))
    }

    @Test
    fun noRecordsProducesNoZip() = runBlocking {
        val result = store().export(device())
        assertEquals(DiagnosticExportResult.NoRecords, result)
    }

    @Test
    fun ttlDropsOldEventsOnExport() = runBlocking {
        val store = store()
        store.record(event())
        now += DiagnosticStore.MAX_AGE_MS + 1
        assertEquals(DiagnosticExportResult.NoRecords, store.export(device()))
    }

    @Test
    fun sizeCapKeepsNewestBoundedLines() = runBlocking {
        val store = store(maxEventBytes = 700)
        repeat(30) { index ->
            store.record(
                event(
                    action = DiagnosticAction.QUERY_COMPLETE,
                    durationMs = index.toLong(),
                ),
            )
        }
        val result = store.export(device()) as DiagnosticExportResult.Success
        val content = unzip(result.uri).getValue("events.jsonl")
        assertTrue(content.toByteArray().size <= 700)
        assertTrue(content.isNotBlank())
    }

    @Test
    fun clearDropsQueuedOldEventsAndAllowsNewOnes() = runBlocking {
        val store = store()
        val jobs = (1..80).map {
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                store.record(event(action = DiagnosticAction.QUERY_COMPLETE))
            }
        }
        jobs.joinAll()
        assertTrue(store.clearNow())
        assertEquals(DiagnosticExportResult.NoRecords, store.export(device()))

        store.record(event(action = DiagnosticAction.QUERY_START))
        val result = store.export(device()) as DiagnosticExportResult.Success
        assertEquals(1, result.eventCount)
    }

    @Test
    fun eventsPersistAcrossStoreRecreation() = runBlocking {
        val first = store()
        first.record(event(action = DiagnosticAction.QUERY_START))
        // Export acts as a barrier: it is processed after the queued record.
        assertTrue(first.export(device()) is DiagnosticExportResult.Success)
        first.close()
        val second = store()
        val result = second.export(device()) as DiagnosticExportResult.Success
        assertEquals(1, result.eventCount)
    }

    @Test
    fun accountClearGateDropsQueuedEventsAndKeepsNewOnes() = runBlocking {
        val store = store()
        store.record(event(action = DiagnosticAction.AUTH_START))
        store.clearForAccountChange()
        store.record(event(action = DiagnosticAction.QUERY_START))
        val result = store.export(device()) as DiagnosticExportResult.Success
        assertEquals(1, result.eventCount)
        assertTrue(unzip(result.uri).getValue("events.jsonl").contains("QUERY_START"))
    }

    @Test
    fun exportRetentionKeepsOnlyThreeZips() = runBlocking {
        val exportDir = File(context.cacheDir, "diagnostic_exports").apply { mkdirs() }
        repeat(5) { index ->
            File(exportDir, "old-$index.zip").writeText("x")
        }
        val store = store()
        store.record(event())
        store.export(device())
        val count = exportDir.listFiles { file -> file.name.endsWith(".zip") }?.size ?: 0
        assertTrue(count <= DiagnosticStore.MAX_EXPORTS)
    }

    @Test
    fun malformedFileIsIgnoredInsteadOfCrashingExport() = runBlocking {
        val store = store()
        val root = File(directory, "root")
        root.mkdirs()
        File(root, DiagnosticStore.EVENTS_FILE).writeText(
            "not json\n" +
                "{\"timestampEpochMillis\":1700000000000,\"module\":\"APP\",\"action\":\"WIDGET_LAYOUT\",\"result\":\"OK\"}\n",
        )
        val result = store.export(device()) as DiagnosticExportResult.Success
        val content = unzip(result.uri).getValue("events.jsonl")
        assertFalse(content.contains("not json"))
        assertTrue(content.contains("WIDGET_LAYOUT"))
    }

    @Test
    fun fileProviderIsNotExportedAndOnlyServesExportDirectory() {
        val authority = context.packageName + DiagnosticStore.AUTHORITY_SUFFIX
        val info = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull(info)
        assertFalse(info!!.exported)
        assertTrue(info.grantUriPermissions)

        val parser = context.resources.getXml(com.upcpocket.app.R.xml.diagnostic_file_paths)
        var cachePath: String? = null
        var tagCount = 0
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                parser.name == "cache-path"
            ) {
                tagCount++
                cachePath = parser.getAttributeValue(null, "path")
                assertEquals("diagnostic_exports", parser.getAttributeValue(null, "name"))
            }
        }
        assertEquals(1, tagCount)
        assertEquals("diagnostic_exports/", cachePath)
        assertTrue(
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    authority,
                    File(context.filesDir, "outside.txt"),
                )
            }.isFailure,
        )
    }

    @Test
    fun shareIntentIsExplicitZipWithTemporaryReadGrantAndNoText() {
        val uri = android.net.Uri.parse("content://${
            context.packageName + DiagnosticStore.AUTHORITY_SUFFIX
        }/diagnostic_exports/test.zip")
        val intent = DiagnosticShare.intent(uri)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    private fun store(maxEventBytes: Long = DiagnosticStore.MAX_EVENT_BYTES): DiagnosticStore =
        DiagnosticStore(
            context = context,
            clock = { now },
            rootDirectory = File(directory, "root"),
            // FileProvider only serves cacheDir/diagnostic_exports; use the real
            // provider path so the test exercises the same URI contract as the app.
            exportDirectory = File(context.cacheDir, "diagnostic_exports"),
            maxEventBytes = maxEventBytes,
            maxAgeMs = DiagnosticStore.MAX_AGE_MS,
            uriFactory = { file -> android.net.Uri.fromFile(file) },
        ).also { stores += it }

    private fun event(
        action: DiagnosticAction = DiagnosticAction.WIDGET_LAYOUT,
        durationMs: Long = 1,
    ): DiagnosticEvent = DiagnosticEvent(
        module = DiagnosticModule.APP,
        action = action,
        result = DiagnosticResult.OK,
        durationMs = durationMs,
        timestampEpochMillis = now,
    )

    private fun device(): DiagnosticDeviceInfo = DiagnosticDeviceInfo(
        appVersion = "2.0.1",
        appVersionCode = 21,
        buildType = "release",
        androidRelease = "16",
        sdkInt = 36,
        brand = "test",
        model = "test",
        webViewPackage = "test.webview 1",
        fontScale = 1.0f,
        windowWidthDp = 412,
        windowHeightDp = 839,
    )

    private fun unzip(uri: android.net.Uri): Map<String, String> {
        val bytes = if (uri.scheme == "file") {
            File(uri.path ?: error("missing path")).readBytes()
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("cannot open $uri")
        }
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return result
    }
}
