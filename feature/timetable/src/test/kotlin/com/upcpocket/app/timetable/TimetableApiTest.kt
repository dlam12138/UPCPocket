package com.upcpocket.app.timetable

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimetableApiTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/$name")!!.readText()

    private val term: TimetableTerm = TimetableProtocol.parseIndex(
        fixture("timetable_index.json"),
    ).find("2026-2027", "1")!!

    private val cookies = listOf(
        TimetableCookie(
            name = "eai-sess",
            value = "synthetic-session",
            domain = "app.upc.edu.cn",
            path = "/",
            secure = true,
        ),
    )

    @Test
    fun fixedRequestShapeAndCookieHeader() = runTest {
        val seen = mutableListOf<TimetableHttpRequest>()
        val api = TimetableApi(
            transport = FakeTransport { request ->
                seen += request
                if (request.method == "GET") {
                    json(fixture("timetable_index.json"))
                } else {
                    json(fixture("timetable_week_08.json"))
                }
            },
        )
        api.getIndex(cookies)
        api.getWeek(cookies, term, 8)
        val get = seen.first { it.method == "GET" }
        val post = seen.first { it.method == "POST" }
        assertEquals("https://app.upc.edu.cn/timetable/wap/default/get-index", get.url)
        assertEquals("https://app.upc.edu.cn/timetable/wap/default/get-datatmp", post.url)
        assertEquals("XMLHttpRequest", get.headers["X-Requested-With"])
        assertEquals("eai-sess=synthetic-session", get.headers["Cookie"])
        assertFalse(get.headers.containsKey("Content-Type"))
        assertTrue(post.body!!.contains("year=2026-2027"))
        assertTrue(post.body!!.contains("type=2"))
        assertTrue(post.body!!.contains("week=8"))
    }

    @Test
    fun cookiePathAndSecureAndExpiryScopesAreEnforced() {
        val scoped = TimetableCookie(
            name = "scoped",
            value = "synthetic",
            path = "/timetable",
            secure = true,
        )
        assertTrue(
            scoped.matches(
                "app.upc.edu.cn",
                "/timetable/wap/default/get-index",
                secure = true,
                nowEpochSeconds = 1_000,
            ),
        )
        assertFalse(
            scoped.matches(
                "app.upc.edu.cn",
                "/timetablefoo",
                secure = true,
                nowEpochSeconds = 1_000,
            ),
        )
        assertFalse(
            scoped.matches(
                "app.upc.edu.cn",
                "/timetable/wap",
                secure = false,
                nowEpochSeconds = 1_000,
            ),
        )
        assertFalse(
            scoped.copy(expiresAtEpochSeconds = 999).matches(
                "app.upc.edu.cn",
                "/timetable/wap",
                secure = true,
                nowEpochSeconds = 1_000,
            ),
        )
    }

    @Test
    fun redirectAnd401AreAuthRequired() = runTest {
        listOf(302, 401).forEach { status ->
            val api = TimetableApi(FakeTransport { response(status) })
            try {
                api.getIndex(cookies)
                fail("expected auth required")
            } catch (failure: TimetableApiException) {
                assertEquals(TimetableErrorCode.AUTH_REQUIRED, failure.code)
            }
        }
    }

    @Test
    fun sevenOrEightRedirectAnd403AreNotAuthRequired() = runTest {
        listOf(307, 308, 403).forEach { status ->
            val api = TimetableApi(FakeTransport { response(status) })
            try {
                api.getIndex(cookies)
                fail("expected upstream error")
            } catch (failure: TimetableApiException) {
                assertEquals(TimetableErrorCode.UPSTREAM_ERROR, failure.code)
                assertFalse(failure.network)
            }
        }
    }

    @Test
    fun nonJsonAndBusinessAuthErrorsAreNotReportedAsEmptyWeek() = runTest {
        val api = TimetableApi(
            FakeTransport {
                TimetableHttpResponse(
                    200,
                    mapOf("content-type" to listOf("text/html")),
                    "<html>login</html>".toByteArray(),
                )
            },
        )
        try {
            api.getIndex(cookies)
            fail("expected schema error")
        } catch (failure: TimetableApiException) {
            assertEquals(TimetableErrorCode.SCHEMA_CHANGED, failure.code)
        }

        val expired = TimetableApi(
            FakeTransport { json("""{"e":10013,"d":{}}""") },
        )
        try {
            expired.getIndex(cookies)
            fail("expected auth required")
        } catch (failure: TimetableApiException) {
            assertEquals(TimetableErrorCode.AUTH_REQUIRED, failure.code)
        }
    }

    @Test
    fun transportFailureIsNetworkAndNoRetry() = runTest {
        var calls = 0
        val api = TimetableApi(
            FakeTransport {
                calls++
                throw TimetableTransportException(TimetableTransportFailure.NETWORK)
            },
        )
        try {
            api.getIndex(cookies)
            fail("expected network failure")
        } catch (failure: TimetableApiException) {
            assertEquals(TimetableErrorCode.UPSTREAM_ERROR, failure.code)
            assertTrue(failure.network)
        }
        assertEquals(1, calls)
    }

    private fun response(status: Int): TimetableHttpResponse = TimetableHttpResponse(
        status,
        mapOf("content-type" to listOf("application/json")),
        "{}".toByteArray(),
    )

    private fun json(body: String): TimetableHttpResponse = TimetableHttpResponse(
        200,
        mapOf("content-type" to listOf("application/json; charset=utf-8")),
        body.toByteArray(),
    )
}

private class FakeTransport(
    private val handler: (TimetableHttpRequest) -> TimetableHttpResponse,
) : TimetableHttpTransport {
    override suspend fun execute(request: TimetableHttpRequest): TimetableHttpResponse =
        handler(request)
}
