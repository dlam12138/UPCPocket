package com.upcpocket.app.ui.school

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class SchoolLoginWebViewClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
    private val view = WebView(activity).also { activity.setContentView(it) }
    private var loading = false
    private var error: String? = null
    private val client = SchoolLoginWebViewClient(scope, { it.startsWith("https://cas.upc.edu.cn/") },
        onLoading = { loading = it }, onError = { error = it })

    @After fun close() { client.dispose(view); view.destroy(); activity.finish(); scope.cancel() }

    @Test fun `HTTP error remains visible when page finished follows error callback`() {
        client.onPageStarted(view, CAS, null)
        assertTrue(loading)
        client.onReceivedHttpError(view, request(CAS, true), response(503))
        client.onPageFinished(view, CAS)
        assertFalse(loading)
        assertTrue(error.orEmpty().contains("503"))
        assertFalse(error.orEmpty().contains("fixture-ticket"))
    }

    @Test fun `CAS inner login frame failure is visible but image failure does not invalidate login`() {
        client.onReceivedHttpError(view, request("https://cas.upc.edu.cn/cas/logo.png", false), response(404))
        assertNull(error)
        client.onReceivedHttpError(view, request("https://cas.upc.edu.cn/cas/cas/login-mobile.html", false), response(404))
        assertTrue(error.orEmpty().contains("404"))
    }

    @Test fun `blocked main navigation reports failure instead of silently leaving a blank page`() {
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://outside.invalid/?token=fixture-secret", true)))
        assertTrue(error.orEmpty().contains("outside.invalid"))
        assertFalse(error.orEmpty().contains("fixture-secret"))
    }

    @Test fun `unfinished load times out and disposal cancels its callback`() {
        client.onPageStarted(view, CAS, null)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(26))
        assertFalse(loading)
        assertTrue(error.orEmpty().contains("25"))
        client.onPageStarted(view, CAS, null)
        client.dispose(view)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(26))
        assertNull(error)
    }

    @Test fun `CAS repair checks the document even when redirect callback retains entry URL`() {
        client.onPageCommitVisible(view, "https://libspace.upc.edu.cn/libseat/")
        assertEquals(CAS_VIEWPORT_REPAIR, shadowOf(view).lastEvaluatedJavascript)
        assertTrue(CAS_VIEWPORT_REPAIR.contains("location.origin !== 'https://cas.upc.edu.cn'"))
        assertTrue(CAS_VIEWPORT_REPAIR.contains("location.pathname !== '/cas/login'"))
    }

    private fun response(status: Int) = WebResourceResponse("text/html", "UTF-8", status,
        "Unavailable", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    private fun request(url: String, main: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    companion object { private const val CAS = "https://cas.upc.edu.cn/cas/login?ticket=fixture-ticket" }
}
