package com.upcpocket.app.serviceportal

import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.upcauth.ServicePortalAuthenticator
import com.upcpocket.app.upcauth.UpcServicePortalAuthFailure
import com.upcpocket.app.upcauth.UpcServicePortalAuthResult
import com.upcpocket.app.upcauth.UpcServicePortalCookie
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePortalPublicFlowTest {
    private class NoAccount : SchoolAccountReferenceReader {
        override val state = MutableStateFlow(SchoolAccountState(status = SchoolAccountStatus.NOT_CONFIGURED))
        override val accountReference = MutableStateFlow<SchoolAccountReference?>(null)
    }

    private class UnusedAuthenticator : ServicePortalAuthenticator {
        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcServicePortalAuthResult =
            UpcServicePortalAuthResult.Failure(UpcServicePortalAuthFailure.ACCOUNT_UNAVAILABLE)

        override fun clearSession() = Unit
    }

    @Test
    fun syntheticHttpCatalogSearchHandlesUnicodeWhitespaceOnPublicFlow() = runBlocking {
        val requestCount = AtomicInteger()
        val cookieFree = AtomicBoolean(false)
        val fixture = """{"e":0,"d":{"list":[{"id":7,"name":"a b c d e f g h","departName":"i j k l m n o","type":1}]}}"""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestCount.incrementAndGet()
                assertEquals("GET", request.method)
                assertEquals(UpcServicePortalCookie.HOST, request.url.host)
                assertEquals("/api/apps/list", request.url.encodedPath)
                cookieFree.set(request.header("Cookie") == null)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fixture.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        val terms = "abcdefghijklmno".map { it.toString() }
        val separators = listOf(
            " ", "\t\t", "\n", "\r\n", "\u0085", "\u00a0", "\u1680",
            "\u2000", "\u200a", "\u2028", "\u2029", "\u202f", "\u205f", "\u3000",
        )
        val keyword = buildString {
            terms.forEachIndexed { index, term ->
                append(term)
                separators.getOrNull(index)?.let(::append)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = ServicePortalService(
                account = NoAccount(),
                authenticator = UnusedAuthenticator(),
                api = ServicePortalApi(client),
                scope = scope,
            )
            service.search(keyword)
            val state = withTimeout(5_000) {
                service.state.first { !it.busy && it.results != null }
            }

            assertEquals(1, requestCount.get())
            assertTrue(cookieFree.get())
            assertNull(state.error)
            assertEquals(ServicePortalScope.PUBLIC, state.results?.scope)
            assertEquals(1, state.results?.total)
            assertEquals(1, state.results?.items?.size)
            assertTrue(state.results?.completeForPage == true)
            assertTrue(state.results?.completeForScope == true)
            assertEquals("a", ServicePortalProtocol.normalizeSearchKeyword("\u0085a\u3000"))
            assertEquals(0, state.results?.items?.single()?.rowKey)
            assertFalse(state.results?.items?.single()?.idAmbiguous ?: true)
        } finally {
            scope.cancel()
        }
    }
}
