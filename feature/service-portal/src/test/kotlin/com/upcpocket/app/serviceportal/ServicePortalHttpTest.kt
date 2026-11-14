package com.upcpocket.app.serviceportal

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.upcauth.UpcServicePortalCookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePortalHttpTest {
    private val api = ServicePortalApi()

    @Test
    fun `all requests use fixed HTTPS host path and GET method`() {
        val cases = mapOf(
            ServicePortalEndpoint.CATALOG to "/api/apps/list",
            ServicePortalEndpoint.GUIDE to "/api/apps/detail",
            ServicePortalEndpoint.TODOS to "/site/my-task/todo",
            ServicePortalEndpoint.APPLICATIONS to "/site/process/inst-list",
            ServicePortalEndpoint.SESSION_CHECK to "/site/message/count",
        )
        cases.forEach { (endpoint, path) ->
            val request = api.requestFor(endpoint)
            assertEquals("https", request.url.scheme)
            assertEquals(UpcServicePortalCookie.HOST, request.url.host)
            assertEquals(path, request.url.encodedPath)
            assertEquals("GET", request.method)
            assertNull(request.body)
        }
    }

    @Test
    fun `guide parameters are query data and personal page always fixes status one`() {
        val guide = api.requestFor(ServicePortalEndpoint.GUIDE, mapOf("id" to "17", "userview" to "1"))
        assertEquals(listOf("17"), guide.url.queryParameterValues("id"))
        assertEquals(listOf("1"), guide.url.queryParameterValues("userview"))

        val parameters = api.personalParams(page = 5, pageSize = 30)
        assertEquals(mapOf("p" to "5", "page_size" to "30", "status" to "1"), parameters)
        assertReason(ServicePortalFailure.INVALID_INPUT) { api.personalParams(0, 20) }
        assertReason(ServicePortalFailure.INVALID_INPUT) { api.personalParams(1, 51) }
    }

    @Test
    fun `session cookies remain host scoped and clear without exposing values`() {
        val owner = ServicePortalOwner(
            SchoolAccountReference(SchoolAccountId("syntheticAccount"), AccountGeneration(1)),
        )
        val session = ServicePortalSession(
            listOf(
                UpcServicePortalCookie(
                    name = "SESSION",
                    value = "syntheticCookie",
                    domain = UpcServicePortalCookie.HOST,
                    path = "/site",
                    secure = true,
                    httpOnly = true,
                    expiresAtEpochSeconds = null,
                    hostOnly = true,
                ),
            ),
            owner,
        )
        val businessUrl = "https://${UpcServicePortalCookie.HOST}/site/my-task/todo".toHttpUrl()
        val otherUrl = "https://example.org/site/my-task/todo".toHttpUrl()

        assertEquals(listOf("SESSION"), session.loadForRequest(businessUrl).map { it.name })
        assertTrue(session.loadForRequest(otherUrl).isEmpty())
        assertFalse(session.toString().contains("syntheticCookie"))
        assertFalse(owner.toString().contains("syntheticAccount"))
        session.clear()
        assertTrue(session.loadForRequest(businessUrl).isEmpty())
    }

    private fun assertReason(expected: ServicePortalFailure, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected $expected")
        } catch (failure: ServicePortalException) {
            assertEquals(expected, failure.reason)
        }
    }
}
