package com.upcpocket.app.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticBodyType
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeEvidence
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeType
import com.upcpocket.app.platform.diagnostics.DiagnosticCredentialState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticMediaType
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticServiceParameter
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportError
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportProtocol
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportProxyType
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportSnapshot
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportStage
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
import org.json.JSONObject
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
        val failure = RuntimeException(
            "PASSWORD_canary token=secret cookie=COOKIE_canary account=ACCOUNT_canary " +
                "https://school.example/login?execution=EXECUTION_canary <html>HTML_canary</html> " +
                "amount=AMOUNT_canary order=ORDER_canary ip=192.0.2.44",
        )
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
        assertTrue(
            Diagnostics.record(
                module = DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STOP,
                result = DiagnosticResult.BLOCKED,
                reason = DiagnosticReason.INTERACTION,
                httpStatus = 403,
                operationId = "1a2b3c4d",
                phase = DiagnosticPhase.CAS_POST,
                detail = DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
                passwordSubmitted = 1,
                credentialState = DiagnosticCredentialState.AVAILABLE,
                challengeType = DiagnosticChallengeType.UNKNOWN,
                challengeEvidence = DiagnosticChallengeEvidence.UNKNOWN,
                serviceParameter = DiagnosticServiceParameter.MATCH,
                callbackState = DiagnosticCallbackState.NOT_CONSUMED,
                navigationFrame = DiagnosticNavigationFrame.MAIN,
                bindingSource = DiagnosticBindingSource.PASSWORD,
            ),
        )
        assertTrue(
            Diagnostics.record(
                module = DiagnosticModule.TIMETABLE,
                action = DiagnosticAction.QUERY_FAIL,
                result = DiagnosticResult.FAIL,
                reason = DiagnosticReason.SCHEMA,
                phase = DiagnosticPhase.PARSE,
                detail = DiagnosticDetail.WEEK_PARSE_ORDER,
                mediaType = DiagnosticMediaType.JSON,
                bodyType = DiagnosticBodyType.JSON_OBJECT,
                itemCount = 7,
            ),
        )
        assertTrue(
            Diagnostics.record(
                module = DiagnosticModule.CARD,
                action = DiagnosticAction.AUTH_STOP,
                result = DiagnosticResult.FAIL,
                reason = DiagnosticReason.NETWORK,
                phase = DiagnosticPhase.ENTRY_GET,
                passwordSubmitted = 0,
                transport = DiagnosticTransportSnapshot(DiagnosticTransportStage.DNS, totalMs = 12,
                    error = DiagnosticTransportError.DNS, dnsMs = 12, dnsQueryCount = 1),
            ),
        )
        assertTrue(
            Diagnostics.record(
                module = DiagnosticModule.CARD,
                action = DiagnosticAction.OPERATION_STAGE,
                result = DiagnosticResult.FAIL,
                reason = DiagnosticReason.NETWORK,
                phase = DiagnosticPhase.RESPONSE,
                detail = DiagnosticDetail.CARD_BALANCE_QUERY,
                operationId = "b1a2c3d4",
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
        assertTrue(all.contains("\"schemaVersion\":5"))
        assertTrue(all.contains("\"phase\":\"PARSE\""))
        assertTrue(all.contains("\"detail\":\"WEEK_PARSE_ORDER\""))
        assertTrue(all.contains("\"mediaType\":\"JSON\""))
        assertTrue(all.contains("\"bodyType\":\"JSON_OBJECT\""))
        assertTrue(all.contains("\"itemCount\":7"))
        assertTrue(all.contains("\"widthDp\":412"))
        assertTrue(all.contains("\"module\":\"SHIGUANG\""))
        assertTrue(all.contains("\"httpStatus\":403"))
        assertTrue(all.contains("\"detail\":\"CAS_POST_UNKNOWN_REJECTION\""))
        assertTrue(all.contains("\"passwordSubmitted\":1"))
        assertTrue(all.contains("\"challengeEvidence\":\"UNKNOWN\""))
        assertTrue(all.contains("\"callbackState\":\"NOT_CONSUMED\""))
        assertTrue(all.contains("\"bindingSource\":\"PASSWORD\""))
        assertTrue(all.contains("\"transport\":{"))
        assertTrue(all.contains("transport.stage=DNS"))
        assertTrue(all.contains("transport.error=DNS"))
        assertTrue(all.contains("\"detail\":\"CARD_BALANCE_QUERY\""))
        assertTrue(all.contains("authenticationFields=operationId,phase,detail,httpStatus"))
        assertTrue(all.contains("appVersion="))
        listOf("PASSWORD_canary", "secret", "COOKIE_canary", "ACCOUNT_canary", "EXECUTION_canary", "HTML_canary",
            "AMOUNT_canary", "ORDER_canary", "192.0.2.44", "school.example", "<html>")
            .forEach { assertFalse(all.contains(it)) }
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
    fun operationStartedBeforeAccountClearCannotWriteAfterTheEpochChanges() = runBlocking {
        val store = store()
        Diagnostics.install(store)
        val oldOperation = Diagnostics.beginOperation()
        store.clearForAccountChange()
        Diagnostics.record(
            module = DiagnosticModule.SERVICE_PORTAL,
            action = DiagnosticAction.AUTH_STOP,
            result = DiagnosticResult.FAIL,
            phase = DiagnosticPhase.CAS_POST,
            detail = DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
            operation = oldOperation,
            transport = DiagnosticTransportSnapshot(DiagnosticTransportStage.CONNECT, totalMs = 1,
                error = DiagnosticTransportError.CONNECT, connectAttemptCount = 1),
        )
        val currentOperation = Diagnostics.beginOperation()
        Diagnostics.record(
            module = DiagnosticModule.SERVICE_PORTAL,
            action = DiagnosticAction.AUTH_START,
            result = DiagnosticResult.PENDING,
            phase = DiagnosticPhase.ENTRY_GET,
            operation = currentOperation,
        )

        val result = store.export(device()) as DiagnosticExportResult.Success
        val events = unzip(result.uri).getValue("events.jsonl")
        assertFalse(events.contains(oldOperation.id))
        assertTrue(events.contains(currentOperation.id))
    }

    @Test
    fun legacySchemaThreeEventRemainsReadableAfterSchemaUpgrade() = runBlocking {
        val root = File(directory, "root").apply { mkdirs() }
        File(root, DiagnosticStore.EVENTS_FILE).writeText(
            """{"schemaVersion":3,"timestampEpochMillis":1700000000000,"module":"TIMETABLE","action":"AUTH_START","result":"PENDING","operationId":"1a2b3c4d","phase":"AUTH"}""",
        )

        val result = store().export(device()) as DiagnosticExportResult.Success
        val events = unzip(result.uri).getValue("events.jsonl")
        assertTrue(events.contains("\"schemaVersion\":3"))
        assertTrue(events.contains("\"operationId\":\"1a2b3c4d\""))
        assertTrue(events.contains("\"phase\":\"AUTH\""))
    }

    @Test
    fun schemaFiveTransportAndLegacyFourShareTheRealZipWithoutRewritingOldFields() = runBlocking {
        val root = File(directory, "root").apply { mkdirs() }
        val legacy = """{ "schemaVersion":4,"timestampEpochMillis":1700000000000,"module":"CARD","action":"AUTH_STOP","result":"FAIL","reason":"NETWORK","operationId":"a1b2c3d4","phase":"ENTRY_GET","passwordSubmitted":0 }"""
        File(root, DiagnosticStore.EVENTS_FILE).writeText("$legacy\n")
        val store = store()
        val trace = DiagnosticTransportSnapshot(
            stage = DiagnosticTransportStage.RESPONSE_BODY,
            totalMs = 321,
            error = DiagnosticTransportError.EOF,
            dnsMs = 17,
            connectMs = 40,
            tlsMs = 13,
            headersMs = 91,
            bodyMs = 9,
            dnsQueryCount = 2,
            dnsIpv4Count = 1,
            dnsIpv6Count = 1,
            connectAttemptCount = 2,
            tlsAttemptCount = 2,
            requestHeadersCount = 1,
            connectionReused = 0,
            protocol = DiagnosticTransportProtocol.HTTP_2,
            proxyType = DiagnosticTransportProxyType.HTTP,
        )
        store.record(DiagnosticEvent(
            module = DiagnosticModule.CARD,
            action = DiagnosticAction.AUTH_STOP,
            result = DiagnosticResult.FAIL,
            reason = DiagnosticReason.NETWORK,
            operationId = "ab12cd34",
            phase = DiagnosticPhase.ENTRY_GET,
            detail = DiagnosticDetail.TRANSPORT_PROTOCOL,
            passwordSubmitted = 0,
            httpStatus = 200,
            responseBytes = 312,
            transport = trace,
            timestampEpochMillis = now,
        ))
        val result = store.export(device()) as DiagnosticExportResult.Success
        val entries = unzip(result.uri)
        val rawLines = entries.getValue("events.jsonl").lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(2, result.eventCount)
        assertEquals(legacy, rawLines.first())
        val oldValue = JSONObject(rawLines.first())
        assertEquals(4, oldValue.getInt("schemaVersion"))
        assertFalse(oldValue.has("transport"))
        val newValue = JSONObject(rawLines.last())
        assertEquals(5, newValue.getInt("schemaVersion"))
        assertEquals(200, newValue.getInt("httpStatus"))
        assertEquals(312, newValue.getInt("responseBytes"))
        val encoded = newValue.getJSONObject("transport")
        assertEquals(setOf("stage", "error", "totalMs", "dnsMs", "connectMs", "tlsMs", "headersMs", "bodyMs",
            "dnsQueryCount", "dnsIpv4Count", "dnsIpv6Count", "connectAttemptCount", "tlsAttemptCount", "requestHeadersCount",
            "connectionReused", "protocol", "proxyType"), encoded.keys().asSequence().toSet())
        assertEquals("RESPONSE_BODY", encoded.getString("stage"))
        assertEquals("EOF", encoded.getString("error"))
        val numeric = mapOf("totalMs" to 321, "dnsMs" to 17, "connectMs" to 40, "tlsMs" to 13, "headersMs" to 91,
            "bodyMs" to 9, "dnsQueryCount" to 2, "dnsIpv4Count" to 1, "dnsIpv6Count" to 1, "connectAttemptCount" to 2,
            "tlsAttemptCount" to 2, "requestHeadersCount" to 1, "connectionReused" to 0)
        numeric.forEach { (name, number) -> assertEquals(number, encoded.getInt(name)) }
        assertEquals("HTTP_2", encoded.getString("protocol"))
        assertEquals("HTTP", encoded.getString("proxyType"))
        val summary = entries.getValue("summary.txt")
        val recent = summary.lineSequence().single { it.startsWith("- ") && it.contains("operationId=ab12cd34") }
        assertTrue(recent.contains("httpStatus=200"))
        assertTrue(recent.contains("responseBytes=312"))
        assertTrue(recent.contains("transport.stage=RESPONSE_BODY"))
        assertTrue(recent.contains("transport.error=EOF"))
        numeric.forEach { (name, number) -> assertTrue(recent.contains("transport.$name=$number")) }
        assertTrue(recent.contains("transport.protocol=HTTP_2"))
        assertTrue(recent.contains("transport.proxyType=HTTP"))
        assertFalse(recent.contains("transport.httpStatus"))
        assertFalse(recent.contains("transport.responseBytes"))
        val oldSummary = summary.lineSequence().single { it.startsWith("- ") && it.contains("operationId=a1b2c3d4") }
        assertFalse(oldSummary.contains("transport."))
        assertTrue(summary.contains("missing fields are unknown or unobserved"))
        assertTrue(summary.contains("connectMs can include tlsMs"))
    }

    @Test
    fun unobservedOptionalTransportFieldsStayAbsentWhileActualZeroRemainsZero() = runBlocking {
        val store = store()
        store.record(DiagnosticEvent(
            module = DiagnosticModule.CARD,
            action = DiagnosticAction.AUTH_STOP,
            result = DiagnosticResult.FAIL,
            transport = DiagnosticTransportSnapshot(DiagnosticTransportStage.CALL_START, totalMs = 0,
                error = DiagnosticTransportError.CONNECT),
            timestampEpochMillis = now,
        ))
        val entries = unzip((store.export(device()) as DiagnosticExportResult.Success).uri)
        val encoded = JSONObject(entries.getValue("events.jsonl").trim()).getJSONObject("transport")
        for (name in listOf("dnsMs", "connectMs", "tlsMs", "headersMs", "bodyMs", "dnsIpv4Count", "dnsIpv6Count", "connectionReused", "protocol", "proxyType")) {
            assertFalse(encoded.has(name))
            assertFalse(entries.getValue("summary.txt").contains("transport.$name="))
        }
        for (name in listOf("totalMs", "dnsQueryCount", "connectAttemptCount", "tlsAttemptCount", "requestHeadersCount")) {
            assertEquals(0, encoded.getInt(name))
            assertTrue(entries.getValue("summary.txt").contains("transport.$name=0"))
        }
    }

    @Test
    fun summaryAcceptsOnlyKnownEnumsAndBoundedIntegerPrimitivesFromRawRecords() = runBlocking {
        val root = File(directory, "root").apply { mkdirs() }
        // The existing raw-line retention contract remains intact; only summary fields are interpreted here.
        val lines = listOf(
            """{"schemaVersion":4,"timestampEpochMillis":1700000000000,"module":"MODULE_canary","action":"ACTION_canary","result":"FAIL","reason":"REASON_canary","operationId":"OPERATION_canary","phase":"PHASE_canary","detail":"DETAIL_canary","mediaType":"MIME_canary","bodyType":123,"exceptionType":"EXCEPTION_canary","passwordSubmitted":true,"httpStatus":"200","responseBytes":-1,"credentialState":"CREDENTIAL_canary","challengeType":"CHALLENGE_canary","challengeEvidence":"EVIDENCE_canary","serviceParameter":"SERVICE_canary","callbackState":"CALLBACK_canary","navigationFrame":"FRAME_canary","bindingSource":"BINDING_canary","transport":{"stage":"STAGE_canary","error":"ERROR_canary","protocol":"PROTOCOL_canary","proxyType":"PROXY_canary","totalMs":"10","dnsQueryCount":"0"}}""",
            """{"schemaVersion":5,"timestampEpochMillis":1700000000000,"module":"CARD","action":"AUTH_STOP","result":"FAIL","reason":"NETWORK","durationMs":3600001,"httpStatus":600,"responseBytes":8388609,"itemCount":100001,"transport":{"stage":"CONNECT","error":"CONNECT","protocol":"HTTP_1_1","proxyType":"DIRECT","totalMs":600001,"dnsMs":-1,"connectMs":0.5,"tlsMs":"12","headersMs":true,"bodyMs":{},"dnsQueryCount":0,"dnsIpv4Count":65,"dnsIpv6Count":-1,"connectAttemptCount":"1","tlsAttemptCount":false,"requestHeadersCount":1.0,"connectionReused":2}}""",
            """{"schemaVersion":5,"timestampEpochMillis":1700000000000,"module":"CARD","action":"AUTH_STOP","result":"RESULT_canary","transport":{"stage":"RESULT_STAGE_canary","totalMs":1}}""",
        )
        File(root, DiagnosticStore.EVENTS_FILE).writeText(lines.joinToString("\n", postfix = "\n"))
        val entries = unzip((store().export(device()) as DiagnosticExportResult.Success).uri)
        assertEquals(lines, entries.getValue("events.jsonl").lineSequence().filter { it.isNotBlank() }.toList())
        val summary = entries.getValue("summary.txt")
        assertFalse(summary.contains("canary"))
        val recent = summary.substringAfter("recentErrors:\n").substringBefore("\n\n")
        assertTrue(recent.contains("UNKNOWN UNKNOWN FAIL UNKNOWN"))
        assertTrue(recent.contains("transport.stage=CONNECT"))
        assertTrue(recent.contains("transport.error=CONNECT"))
        assertTrue(recent.contains("transport.protocol=HTTP_1_1"))
        assertTrue(recent.contains("transport.proxyType=DIRECT"))
        assertTrue(recent.contains("transport.dnsQueryCount=0"))
        for (name in listOf("totalMs", "dnsMs", "connectMs", "tlsMs", "headersMs", "bodyMs", "dnsIpv4Count", "dnsIpv6Count",
            "connectAttemptCount", "tlsAttemptCount", "requestHeadersCount", "connectionReused")) {
            assertFalse("unexpected coerced transport field: $name", recent.contains("transport.$name="))
        }
        for (name in listOf("durationMs", "httpStatus", "responseBytes", "itemCount", "passwordSubmitted")) {
            assertFalse("unexpected coerced event field: $name", recent.contains(" $name="))
        }
        assertEquals(2, recent.lineSequence().count { it.startsWith("- ") })
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
