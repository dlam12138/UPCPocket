package com.upcpocket.app.shiguang

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ShiguangHttpTest {
    @Test fun fixedReadOnlyRequestsNeverFollowAWriteRedirect() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("synthetic")
                .header("Location", "https://dekt.upc.edu.cn/actvMngAjax/actvMngFront!goEnrollInForWeb.action")
                .body("".toResponseBody("text/html".toMediaType())).build()
        }.build()
        try { ShiguangApi(client).page(ShiguangSession(emptyList(), true), 2, "合成", false); fail() }
        catch (e: ShiguangException) { assertEquals(ShiguangFailure.UPSTREAM, e.reason) }
        val request = requests.single()
        assertEquals("GET", request.method); assertEquals("https", request.url.scheme); assertEquals("dekt.upc.edu.cn", request.url.host)
        assertEquals(ShiguangEndpoint.ACTIVITIES.path, request.url.encodedPath)
        assertEquals(setOf("page", "oper", "searchWord"), request.url.queryParameterNames)
        assertEquals("2", request.url.queryParameter("page")); assertNull(request.body)
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", request.header("Accept"))
        assertEquals("zh-CN,zh;q=0.9", request.header("Accept-Language"))
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0", request.header("User-Agent"))
        assertEquals(5, ShiguangEndpoint.entries.size)
        assertTrue(ShiguangEndpoint.entries.none { "Ajax" in it.path || "Enroll" in it.path || "save" in it.path })
    }
    @Test fun unverifiedIdentityCannotSendPersonalRequests() = runTest {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; error("must not request") }.build()
        val session = ShiguangSession(emptyList(), false)
        for (operation in listOf<suspend () -> Unit>({ ShiguangApi(client).summary(session) }, { ShiguangApi(client).page(session, 1, "", true) })) {
            try { operation(); fail() } catch (e: ShiguangException) { assertEquals(ShiguangFailure.IDENTITY_UNVERIFIED, e.reason) }
        }
        assertEquals(0, calls)
    }
    @Test fun nonHtmlAndOversizedResponsesFailClosed() = runTest {
        for ((type, content) in listOf("application/json" to "{}", "text/html" to "x".repeat(3_000_001))) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body(content.toResponseBody(type.toMediaType())).build()
            }.build()
            try { ShiguangApi(client).page(ShiguangSession(emptyList(), true), 1, "", false); fail() }
            catch (e: ShiguangException) { assertEquals(ShiguangFailure.PROTOCOL, e.reason) }
        }
    }
    @Test fun exactSessionOutJsonIsExpiredWhileOtherJsonStillFailsClosed() = runTest {
        for ((body, expected) in listOf("{\"sessionOutFlag\":\"true\"}" to ShiguangFailure.AUTH_REQUIRED,
            "{\"sessionOutFlag\":true}" to ShiguangFailure.AUTH_REQUIRED,
            "{\"sessionOutFlag\":false}" to ShiguangFailure.PROTOCOL, "[]" to ShiguangFailure.PROTOCOL)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            try { ShiguangApi(client).page(ShiguangSession(emptyList(), true), 1, "", false); fail() }
            catch (e: ShiguangException) { assertEquals(expected, e.reason) }
        }
    }
}
