package com.upcpocket.app.campusbackend

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CampusBackendClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `phase one auth fields follow OpenAPI and sensitive body is temporary`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"token":"opaque-token","deviceId":"11111111-1111-4111-8111-111111111111","expiresAt":"2026-11-28T10:00:00Z"}""",
            ),
        )
        val client = client()
        val password = "p\"ass-雪-🙂".toCharArray()

        val signedIn = client.createSession(
            loginName = "student-login",
            password = password,
            acceptCustody = true,
            installationId = INSTALLATION_ID,
            platform = CampusPlatform.ANDROID,
            kind = CampusDeviceKind.PHONE,
        )

        assertEquals(
            BackendResult.Success(
                CampusSessionResponse(
                    token = "opaque-token",
                    deviceId = DEVICE_ID,
                    expiresAt = "2026-11-28T10:00:00Z",
                ),
            ),
            signedIn,
        )
        val sessionRequest = server.takeRequest()
        assertEquals("POST", sessionRequest.method)
        assertEquals("/api/v1/auth/sessions", sessionRequest.path)
        val sessionBody = Json.parseToJsonElement(sessionRequest.body.readUtf8()) as JsonObject
        assertEquals("student-login", sessionBody["loginName"]?.jsonPrimitive?.content)
        assertEquals("p\"ass-雪-🙂", sessionBody["password"]?.jsonPrimitive?.content)
        assertEquals(true, sessionBody["acceptCustody"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(INSTALLATION_ID, sessionBody["installationId"]?.jsonPrimitive?.content)
        assertEquals("ANDROID", sessionBody["platform"]?.jsonPrimitive?.content)
        assertEquals("PHONE", sessionBody["kind"]?.jsonPrimitive?.content)
        assertFalse("device" in sessionBody)
        assertEquals("p\"ass-雪-🙂", password.concatToString())
    }

    @Test
    fun `credential update uses password field and accepts 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val input = "replacement".toCharArray()

        val result = client().updateCredential("student-login", input)

        assertEquals(BackendResult.NoContent, result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/credential-updates", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals("student-login", body["loginName"]?.jsonPrimitive?.content)
        assertEquals("replacement", body["password"]?.jsonPrimitive?.content)
        assertFalse("newPassword" in body)
        assertNull(request.getHeader("Authorization"))
        assertEquals("replacement", input.concatToString())
    }

    @Test
    fun `phase one account and device endpoints use bearer auth and correct methods`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"accountId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","state":"ACTIVE","credentialGeneration":7,"gateRevision":9}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                200,
                """[{"deviceId":"$DEVICE_ID","installationId":"$INSTALLATION_ID","platform":"ANDROID","kind":"PHONE","expiresAt":"2026-11-28T10:00:00Z"}]""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = client()

        assertEquals(BackendResult.Success(
            CampusAccount(
                accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                state = CampusAccountState.ACTIVE,
                credentialGeneration = 7,
                gateRevision = 9,
            ),
        ), client.getMe(TOKEN))
        assertEquals(1, (client.getDevices(TOKEN) as BackendResult.Success).value.size)
        assertEquals(BackendResult.NoContent, client.revokeDevice(TOKEN, DEVICE_ID))
        assertEquals(BackendResult.NoContent, client.deleteSession(TOKEN))
        assertEquals(BackendResult.NoContent, client.unbindAccount(TOKEN))

        val requests = List(5) { server.takeRequest() }
        assertEquals("GET", requests[0].method)
        assertEquals("/api/v1/me", requests[0].path)
        assertEquals("GET", requests[1].method)
        assertEquals("/api/v1/me/devices", requests[1].path)
        assertEquals("DELETE", requests[2].method)
        assertEquals("/api/v1/me/devices/$DEVICE_ID", requests[2].path)
        assertEquals("DELETE", requests[3].method)
        assertEquals("/api/v1/me/session", requests[3].path)
        assertEquals("DELETE", requests[4].method)
        assertEquals("/api/v1/me", requests[4].path)
        requests.forEach { assertEquals("Bearer $TOKEN", it.getHeader("Authorization")) }
    }

    @Test
    fun `phase two timetable card and grades endpoints parse contract fields`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"terms":[{"year":"2026-2027","term":"1","label":"2026-2027 第一学期","startDay":"2026-09-07","totalWeeks":18}],"currentYear":"2026-2027","currentTerm":"1","currentWeek":2,"weekDates":["2026-09-06","2026-09-07"],"compatibilityNote":"checked"}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                200,
                """{"year":"2026-2027","term":"1","week":2,"weekDates":["2026-09-06","2026-09-07","2026-09-08","2026-09-09","2026-09-10","2026-09-11","2026-09-12"],"meetings":[{"courseName":"Mathematics","date":"2026-09-07","dayOfWeek":1,"startPeriod":1,"endPeriod":2,"rawPeriod":"1-2","teacher":"Teacher","location":"Room 101","weeksLabel":"1-18","courseCode":"MATH1","classGroup":"A"}],"complete":true,"fetchedAtEpochMillis":1790000000000}""",
            ),
        )
        server.enqueue(jsonResponse(200, """{"balanceCents":1234,"transitionCents":25,"fetchedAtEpochMillis":1790000000000}"""))
        server.enqueue(
            jsonResponse(
                200,
                """{"page":1,"size":20,"total":1,"pages":1,"rows":[{"rowKey":"row-1","title":"消费","time":"2026-11-28 08:30","amountFen":-250}]}""",
            ),
        )
        server.enqueue(jsonResponse(200, """{"incomeFen":1000,"expenseFen":250}"""))
        server.enqueue(
            jsonResponse(
                200,
                """{"terms":[{"year":"2025-2026","term":"2"}],"currentTerm":{"year":"2025-2026","term":"2"}}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                200,
                """{"year":"2025-2026","term":"2","courses":[{"courseName":"Physics","score":92,"credits":3.5,"courseType":"Required","examType":"Final"}],"complete":true,"fetchedAtEpochMillis":1790000000000}""",
            ),
        )
        val client = client()

        val index = (client.getTimetableIndex(TOKEN) as BackendResult.Success).value
        assertEquals("2026-2027", index.currentYear)
        assertEquals("2026-09-07", index.terms.single().startDay)
        val week = client.getTimetableWeek(TOKEN, "2026-2027", "1", 2)
        assertEquals("Mathematics", (week as BackendResult.Success).value.meetings.single().courseName)
        assertEquals(1234L, (client.getCardBalance(TOKEN) as BackendResult.Success).value.balanceCents)
        assertEquals(1, (client.getCardTransactions(TOKEN, "2026-11", 1, 20) as BackendResult.Success).value.rows.size)
        assertEquals(1000L, (client.getCardTransactionsSummary(TOKEN, "2026-11") as BackendResult.Success).value.incomeFen)
        assertEquals("2025-2026", (client.getGradesTerms(TOKEN) as BackendResult.Success).value.currentTerm?.year)
        val courses = (client.getGradesCourses(TOKEN, "2025-2026", "2") as BackendResult.Success).value
        assertEquals("92", courses.courses.single().score?.content)
        assertEquals("3.5", courses.courses.single().credits?.content)

        val requests = List(7) { server.takeRequest() }
        assertEquals("/api/v1/timetable/index", requests[0].path)
        assertEquals("/api/v1/timetable/week?year=2026-2027&term=1&week=2", requests[1].path)
        assertEquals("/api/v1/card/balance", requests[2].path)
        assertEquals("/api/v1/card/transactions?month=2026-11&page=1&size=20", requests[3].path)
        assertEquals("/api/v1/card/transactions/summary?month=2026-11", requests[4].path)
        assertEquals("/api/v1/grades/terms", requests[5].path)
        assertEquals("/api/v1/grades/courses?year=2025-2026&term=2", requests[6].path)
        requests.forEach { request ->
            assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
            assertFalse(request.path.orEmpty().contains("studentId", ignoreCase = true))
            assertFalse(request.path.orEmpty().contains("student-login"))
            assertFalse(request.body.readUtf8().contains("student-login"))
        }
    }

    @Test
    fun `all dates card queries omit the optional month parameter`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"page":2,"size":50,"total":0,"pages":0,"rows":[]}"""))
        server.enqueue(jsonResponse(200, """{"incomeFen":0,"expenseFen":0}"""))
        val client = client()

        assertTrue(client.getCardTransactions(TOKEN, month = null, page = 2, size = 50) is BackendResult.Success)
        assertTrue(client.getCardTransactionsSummary(TOKEN, month = null) is BackendResult.Success)

        assertEquals("/api/v1/card/transactions?page=2&size=50", server.takeRequest().path)
        assertEquals("/api/v1/card/transactions/summary", server.takeRequest().path)
    }

    @Test
    fun `all phase one and phase two fixed error codes decode without response text`() = runBlocking {
        val client = client()
        BackendErrorCode.wireCodes.forEachIndexed { index, code ->
            val requestId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
            server.enqueue(jsonResponse(400, """{"code":"$code","requestId":"$requestId"}"""))

            val result = client.getMe(TOKEN)

            assertTrue("$code should decode as a fixed failure", result is BackendResult.Failure)
            val failure = (result as BackendResult.Failure).error
            assertEquals(code, failure.code.wireCode)
            assertEquals(requestId, failure.requestId)
            assertFalse(failure.toString().contains(requestId))
        }
    }

    @Test
    fun `rate limit preserves retry-after seconds and invalid request ids are discarded`() = runBlocking {
        server.enqueue(
            jsonResponse(
                429,
                """{"code":"RATE_LIMITED","requestId":"not-a-uuid"}""",
            ).addHeader("Retry-After", "12"),
        )

        val result = client().getMe(TOKEN) as BackendResult.Failure

        assertEquals(BackendErrorCode.RATE_LIMITED, result.error.code)
        assertEquals(12_000L, result.error.retryAfterMillis)
        assertNull(result.error.requestId)
    }

    @Test
    fun `cross-origin redirects are rejected without contacting redirect target`() = runBlocking {
        val target = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", target.url("/api/v1/me").toString()),
            )

            val result = client().getMe(TOKEN) as BackendResult.Failure

            assertEquals(BackendErrorCode.REDIRECT_REJECTED, result.error.code)
            assertEquals(0, target.requestCount)
            assertEquals("/api/v1/me", server.takeRequest().path)
        } finally {
            target.shutdown()
        }
    }

    @Test
    fun `missing or untrusted origins fail before any request`() = runBlocking {
        val disabled = CampusBackendClient.create(origin = null, policy = BackendOriginPolicy.Debug)
        assertFalse(disabled.isConfigured)
        assertEquals(
            BackendErrorCode.NOT_CONFIGURED,
            (disabled.getMe(TOKEN) as BackendResult.Failure).error.code,
        )

        val releasePolicy = BackendOriginPolicy.Release(setOf("https://trusted.example"))
        val insecure = CampusBackendClient.create("http://trusted.example", releasePolicy)
        val wrongOrigin = CampusBackendClient.create("https://untrusted.example", releasePolicy)
        val pathOrigin = CampusBackendClient.create("https://trusted.example/api", releasePolicy)
        assertEquals(BackendErrorCode.INVALID_ORIGIN, (insecure.getMe(TOKEN) as BackendResult.Failure).error.code)
        assertEquals(BackendErrorCode.INVALID_ORIGIN, (wrongOrigin.getMe(TOKEN) as BackendResult.Failure).error.code)
        assertEquals(BackendErrorCode.INVALID_ORIGIN, (pathOrigin.getMe(TOKEN) as BackendResult.Failure).error.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `redirected or invalid request data is never sent`() = runBlocking {
        val client = client()

        assertEquals(
            BackendErrorCode.VALIDATION_FAILED,
            (client.getTimetableWeek(TOKEN, "2026&studentId=123", "1", 2) as BackendResult.Failure).error.code,
        )
        assertEquals(
            BackendErrorCode.VALIDATION_FAILED,
            (client.getCardTransactions(TOKEN, "2026-11", 0, 20) as BackendResult.Failure).error.code,
        )
        assertEquals(0, server.requestCount)
    }

    private fun client(): CampusBackendClient = CampusBackendClient.create(
        origin = server.url("/").toString(),
        policy = BackendOriginPolicy.Debug,
        clockMillis = { Instant.parse("2026-11-28T00:00:00Z").toEpochMilli() },
    )

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private companion object {
        const val TOKEN = "test-pocket-token"
        const val INSTALLATION_ID = "22222222-2222-4222-8222-222222222222"
        const val DEVICE_ID = "11111111-1111-4111-8111-111111111111"
    }
}
