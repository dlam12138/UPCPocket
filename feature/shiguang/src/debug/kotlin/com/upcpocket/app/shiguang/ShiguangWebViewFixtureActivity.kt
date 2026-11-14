package com.upcpocket.app.shiguang

import android.os.Bundle
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.upcpocket.app.account.api.*
import com.upcpocket.app.upcauth.*
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Debug-only: every document and business response is generated in process. No secret or network. */
class ShiguangWebViewFixtureActivity : ComponentActivity() {
    private lateinit var service: ShiguangService
    private val cookieProbeSeeded = AtomicBoolean(false)
    private val fixtureDestroyed = AtomicBoolean(false)

    private fun hasFixtureCookieProbe(): Boolean = runCatching {
        android.webkit.CookieManager.getInstance().getCookie(FIXTURE_COOKIE_URL)
            ?.split(';')
            ?.any { it.trim() == "$FIXTURE_COOKIE_NAME=$FIXTURE_COOKIE_VALUE" } == true
    }.getOrDefault(false)

    private fun clearFixtureCookieProbe() {
        android.webkit.CookieManager.getInstance().setCookie(
            FIXTURE_COOKIE_URL,
            "$FIXTURE_COOKIE_NAME=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax",
        ) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = intent.getStringExtra("mode").takeIf { it in setOf("top", "iframe", "resources", "reload", "manual") } ?: "top"
        val httpCallback = intent.getBooleanExtra("httpCallback", false)
        val oldCallback = if (httpCallback) OLD_HTTP_CALLBACK else OLD_HTTPS_CALLBACK
        val entryCallback = if (httpCallback) ENTRY_HTTP_CALLBACK else ENTRY_HTTPS_CALLBACK
        val account = object : SchoolAccountReferenceReader {
            override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)))
            override val accountReference = MutableStateFlow<SchoolAccountReference?>(SchoolAccountReference(SchoolAccountId("synthetic-web"), AccountGeneration(1)))
        }
        val native = AtomicInteger(); val complete = AtomicInteger(); val posts = AtomicInteger()
        val callbackHttpRequests = AtomicInteger(); val callbackHttpsRequests = AtomicInteger()
        val nativeHttpCallbacks = AtomicInteger(); val nativeHttpsCallbacks = AtomicInteger(); val nativeUnexpectedCallbacks = AtomicInteger()
        val oldTicketNative = AtomicInteger(); val entryTicketNative = AtomicInteger()
        val oldTicketRequests = AtomicInteger(); val entryTicketRequests = AtomicInteger()
        val reads = AtomicInteger(); val subNavigation = AtomicInteger(); val resourceBlocks = AtomicInteger()
        val entryReopen = AtomicInteger(); val entryGets = AtomicInteger()
        val casSessionEstablished = AtomicBoolean(false)
        val cookieAfterRecreate = AtomicBoolean(false)
        val blockNext = AtomicBoolean(mode == "reload")
        var counters by mutableStateOf("")
        var accountStopped by mutableStateOf(false)
        fun update() { lifecycleScope.launch {
            val httpUpgrade = when {
                !httpCallback -> "n/a"
                callbackHttpRequests.get() == 0 -> "pending"
                nativeHttpCallbacks.get() > 0 || nativeUnexpectedCallbacks.get() > 0 -> "failed"
                nativeHttpsCallbacks.get() > 0 -> "ok"
                else -> "pending"
            }
            counters = "nativeAuth=${native.get()} entryGET=${entryGets.get()} entryReopen=${entryReopen.get()} webComplete=${complete.get()} iframePOST=${posts.get()} casSession=${casSessionEstablished.get()} reads=${reads.get()} oldTicketRequests=${oldTicketRequests.get()} entryTicketRequests=${entryTicketRequests.get()} oldTicketNative=${oldTicketNative.get()} entryTicketNative=${entryTicketNative.get()} ticketHTTP=${callbackHttpRequests.get()} ticketHTTPS=${callbackHttpsRequests.get()} nativeHTTP=${nativeHttpCallbacks.get()} nativeHTTPS=${nativeHttpsCallbacks.get()} nativeOther=${nativeUnexpectedCallbacks.get()} httpUpgrade=$httpUpgrade cookieProbeReady=${cookieProbeSeeded.get()} cookieAfterRecreate=${cookieAfterRecreate.get()} SUB=${subNavigation.get()} resourceBlocked=${resourceBlocks.get()} stopped=$accountStopped"
        } }
        android.webkit.CookieManager.getInstance().setCookie(
            FIXTURE_COOKIE_URL,
            "$FIXTURE_COOKIE_NAME=$FIXTURE_COOKIE_VALUE; Path=/; Secure; HttpOnly; SameSite=Lax",
        ) { accepted ->
            if (fixtureDestroyed.get()) {
                clearFixtureCookieProbe()
            } else {
                cookieProbeSeeded.set(accepted && hasFixtureCookieProbe())
                update()
            }
        }
        val bridge = object : ShiguangInteractiveAuthenticator {
            override val entryUrl = UpcShiguangWebLoginPolicy.entryUrl
            override fun inspectWebNavigation(url: String, method: String, isMainFrame: Boolean, kind: ShiguangWebRequestKind, operation: DiagnosticOperation?): UpcShiguangWebNavigation {
                val decision = UpcShiguangWebLoginPolicy.inspect(url, method, isMainFrame, kind)
                if (url.startsWith("http://dekt.upc.edu.cn/caslogin.jsp?ticket=")) callbackHttpRequests.incrementAndGet()
                if (url.startsWith("https://dekt.upc.edu.cn/caslogin.jsp?ticket=")) callbackHttpsRequests.incrementAndGet()
                if (url in CALLBACK_URLS) {
                    if (url in OLD_CALLBACKS) oldTicketRequests.incrementAndGet()
                    if (url in ENTRY_CALLBACKS) entryTicketRequests.incrementAndGet()
                    if (kind == ShiguangWebRequestKind.NAVIGATION && !isMainFrame && decision is UpcShiguangWebNavigation.Complete) subNavigation.incrementAndGet()
                    if (kind == ShiguangWebRequestKind.RESOURCE && !isMainFrame && decision == UpcShiguangWebNavigation.Block) resourceBlocks.incrementAndGet()
                    update()
                }
                return decision
            }
            override suspend fun completeWebLoginResult(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long, callbackUrl: String, operation: DiagnosticOperation?): UpcShiguangAuthResult {
                when (callbackUrl.substringAfter("ticket=", "").substringBefore('&')) {
                    OLD_TICKET -> oldTicketNative.incrementAndGet()
                    ENTRY_TICKET -> entryTicketNative.incrementAndGet()
                }
                when {
                    callbackUrl.startsWith("http://") -> nativeHttpCallbacks.incrementAndGet()
                    callbackUrl.startsWith("https://") -> nativeHttpsCallbacks.incrementAndGet()
                    else -> nativeUnexpectedCallbacks.incrementAndGet()
                }
                complete.incrementAndGet(); update()
                return UpcShiguangAuthResult.Success(emptyList(), true)
            }
        }
        val auth = object : ShiguangAuthenticator {
            override suspend fun authenticate(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long): UpcShiguangAuthResult {
                native.incrementAndGet(); update()
                return UpcShiguangAuthResult.Failure(if (mode == "manual") UpcShiguangAuthFailure.NETWORK_UNAVAILABLE else UpcShiguangAuthFailure.INTERACTION_REQUIRED)
            }
        }
        val api = object : ShiguangDataSource {
            override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage {
                reads.incrementAndGet(); update()
                return ShiguangPage(page, 3, listOf(ShiguangActivity("123", ShiguangKind.ACTIVITY, "合成验证已返回", mapOf("页码" to page.toString()))))
            }
            override suspend fun detail(session: ShiguangSession, item: ShiguangActivity) = ShiguangDetail(item.title, "合成详情", emptyMap())
            override suspend fun summary(session: ShiguangSession) = ShiguangSummary(emptyList())
        }
        service = ShiguangService(account, auth, api, lifecycleScope, bridge)
        val entryUri = android.net.Uri.parse(bridge.entryUrl)
        val resources = object : ShiguangWebResourceProvider {
            override fun intercept(request: android.webkit.WebResourceRequest): WebResourceResponse? {
                val isCasLoginPath = request.url.scheme == "https" &&
                    request.url.host == UpcShiguangAuthEndpoints.CAS_HOST && request.url.path == "/cas/login"
                val isFixedEntryGet = request.isForMainFrame && request.method == "GET" &&
                    request.url.scheme == entryUri.scheme &&
                    request.url.host == entryUri.host &&
                    request.url.encodedPath == entryUri.encodedPath &&
                    request.url.queryParameterNames == entryUri.queryParameterNames &&
                    request.url.queryParameterNames.all { name ->
                        request.url.getQueryParameters(name) == entryUri.getQueryParameters(name)
                    }
                if (isFixedEntryGet) {
                    val count = entryGets.incrementAndGet()
                    if (count > 1) {
                        entryReopen.incrementAndGet()
                        cookieAfterRecreate.set(cookieProbeSeeded.get() && hasFixtureCookieProbe())
                    }
                    update()
                }
                return when {
                    isCasLoginPath && request.method == "GET" && blockNext.compareAndSet(true, false) ->
                        html("<script>location.href='https://blocked.invalid/';</script>")
                    isCasLoginPath && request.method == "GET" && mode == "iframe" && request.isForMainFrame && casSessionEstablished.get() ->
                        html("<script>top.location.href='$entryCallback';</script>")
                    isCasLoginPath && request.method == "GET" ->
                        html("<h2>合成学校验证</h2><iframe src='/cas/synthetic-frame' style='width:100%;height:520px;border:0'></iframe>")
                    request.url.host == UpcShiguangAuthEndpoints.CAS_HOST && request.url.path == "/cas/synthetic-frame" -> html("""
                        <form method="POST" action="/cas/login"><label>合成验证码<input name="captcha" inputmode="numeric" autocomplete="off"></label><button type="submit">完成验证</button></form>
                        <a href="/cas/synthetic-account">模拟账号变化</a>
                        ${if (mode == "resources") "<img src='$oldCallback'><script>fetch('$oldCallback').catch(()=>{});</script>" else ""}
                    """.trimIndent())
                    isCasLoginPath && request.method == "POST" -> {
                        posts.incrementAndGet()
                        casSessionEstablished.set(true)
                        update()
                        html(
                            if (mode == "iframe") "<script>location.href='$oldCallback';</script>"
                            else "<script>top.location.href='$entryCallback';</script>",
                        )
                    }
                    request.url.host == UpcShiguangAuthEndpoints.CAS_HOST && request.url.path == "/cas/synthetic-account" -> {
                        lifecycleScope.launch {
                            account.state.value = SchoolAccountState(SchoolAccountStatus.UPDATING, AccountGeneration(2), cleanupRequired = true)
                            account.accountReference.value = null
                            accountStopped = service.invalidateForAccountChange(); update()
                        }
                        html("账号变化合成页")
                    }
                    // No call to the real network, even for an unexpected allowed CAS resource.
                    else -> html("Synthetic unavailable", 404)
                }
            }
        }
        update()
        setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Text(counters, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) { ShiguangScreenContent(service, ::finish, {}, resources) }
            }
        } }
    }
    override fun onDestroy() {
        if (::service.isInitialized) service.state.value.interactiveAttempt?.let { service.cancelInteractiveLogin(it.id) }
        fixtureDestroyed.set(true)
        clearFixtureCookieProbe()
        super.onDestroy()
    }
    private fun html(body: String, status: Int = 200) = WebResourceResponse("text/html", "UTF-8", status,
        if (status == 200) "OK" else "Synthetic", emptyMap(), ByteArrayInputStream(("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;padding:12px}input,button,a{display:block;font-size:20px;margin-top:16px;min-height:48px}input{width:90%}</style>""" + body).toByteArray()))
    private companion object {
        const val OLD_TICKET = "ST-DEMO-OLD-FRAME"
        const val ENTRY_TICKET = "ST-DEMO-NEW-ENTRY"
        const val OLD_HTTP_CALLBACK = "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-DEMO-OLD-FRAME"
        const val OLD_HTTPS_CALLBACK = "https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-DEMO-OLD-FRAME"
        const val ENTRY_HTTP_CALLBACK = "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-DEMO-NEW-ENTRY"
        const val ENTRY_HTTPS_CALLBACK = "https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-DEMO-NEW-ENTRY"
        val OLD_CALLBACKS = setOf(OLD_HTTP_CALLBACK, OLD_HTTPS_CALLBACK)
        val ENTRY_CALLBACKS = setOf(ENTRY_HTTP_CALLBACK, ENTRY_HTTPS_CALLBACK)
        val CALLBACK_URLS = setOf(OLD_HTTP_CALLBACK, OLD_HTTPS_CALLBACK, ENTRY_HTTP_CALLBACK, ENTRY_HTTPS_CALLBACK)
        const val FIXTURE_COOKIE_URL = "https://fixture.invalid/"
        const val FIXTURE_COOKIE_NAME = "UPC_SHIGUANG_FIXTURE_PROBE"
        const val FIXTURE_COOKIE_VALUE = "synthetic-only"
    }
}
