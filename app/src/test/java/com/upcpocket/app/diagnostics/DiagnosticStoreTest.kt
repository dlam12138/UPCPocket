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
    }

    @After
    fun tearDown() {
        stores.forEach { it.close() }
        Diagnostics.install(null)
        directory.deleteRecursively()
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
        assertTrue(all.contains("DiagnosticsCanary.boom"))
        assertTrue(all.contains("\"schemaVersion\":1"))
        assertTrue(all.contains("\"widthDp\":412"))
        assertTrue(all.contains("appVersion="))
        assertFalse(all.contains("accountId"))
        assertFalse(all.contains("cookie"))
        assertFalse(all.contains("http://"))
        assertFalse(all.contains("https://"))
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

        val exportFile = File(directory, "example.zip").apply { writeText("x") }
        // Use the real provider path configuration only for the app cache directory.
        val realExportDir = File(context.cacheDir, "diagnostic_exports").apply { mkdirs() }
        val realFile = File(realExportDir, "probe.zip").apply { writeText("x") }
        try {
            val uri = FileProvider.getUriForFile(context, authority, realFile)
            assertNotNull(uri)
            assertFalse(
                runCatching {
                    FileProvider.getUriForFile(context, authority, exportFile)
                }.isSuccess,
            )
        } finally {
            realFile.delete()
            realExportDir.delete()
            exportFile.delete()
        }
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
            exportDirectory = File(directory, "exports"),
            maxEventBytes = maxEventBytes,
            maxAgeMs = DiagnosticStore.MAX_AGE_MS,
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
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("cannot open $uri")
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
