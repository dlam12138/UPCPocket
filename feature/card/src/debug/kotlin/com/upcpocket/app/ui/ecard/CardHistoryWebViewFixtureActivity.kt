package com.upcpocket.app.ui.ecard

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.foundation.layout.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.upcpocket.app.account.api.*
import com.upcpocket.app.data.cardhistory.*
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** All documents and HTTP responses are generated in process. No credential or network access. */
class CardHistoryWebViewFixtureActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: CardHistoryClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val account = object : SchoolAccountStudentIdentityReader {
            override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)))
            override val studentIdentity = MutableStateFlow<SchoolStudentIdentity?>(
                SchoolStudentIdentity(SchoolAccountId("fixture_account"), "2099999999", AccountGeneration(1)),
            )
        }
        val completions = AtomicInteger()
        val iframePosts = AtomicInteger()
        val pageCalls = AtomicInteger()
        val failNext = AtomicBoolean()
        var attempt by mutableStateOf<CardHistoryInteractiveAttempt?>(null)
        var status by mutableStateOf("尚未验证")
        val bridge = object : CardHistoryInteractiveAuthenticator {
            override val entryUrl = "$BASE/entry"
            override fun inspectNavigation(url: String, method: String, isMainFrame: Boolean): CardHistoryWebNavigation {
                val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return CardHistoryWebNavigation.Block
                if (uri.scheme != "https" || uri.host != HOST || uri.port != -1 || uri.rawUserInfo != null) return CardHistoryWebNavigation.Block
                if (uri.path == "/change-account" && isMainFrame) {
                    scope.launch {
                        account.studentIdentity.value = account.studentIdentity.value!!.copy(credentialGeneration = AccountGeneration(2))
                        val stopped = client.invalidateForAccountChange()
                        attempt = null
                        status = "账号已变化 清理完成=$stopped 完成次数=${completions.get()}"
                    }
                    return CardHistoryWebNavigation.Block
                }
                return when {
                    uri.path == "/result" && isMainFrame && method == "GET" -> CardHistoryWebNavigation.Complete(url)
                    uri.path == "/entry" && isMainFrame && method == "GET" -> CardHistoryWebNavigation.Allow
                    uri.path == "/cas/frame" && !isMainFrame && method == "GET" -> CardHistoryWebNavigation.Allow
                    uri.path == "/cas/login" && method == "POST" -> CardHistoryWebNavigation.Allow
                    else -> CardHistoryWebNavigation.Block
                }
            }
            override suspend fun completeResult(identity: SchoolStudentIdentity, resultUrl: String): String {
                completions.incrementAndGet()
                return "fixture-web-session"
            }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            // Never call proceed(): even the client identity check stays in process.
            val body = when (chain.request().url.encodedPath) {
                "/berserker-base/user" -> """{"code":200,"data":{"sno":"2099999999"}}"""
                "/berserker-search/statistics/turnover/count" -> """{"code":200,"data":{"income":0,"expenses":820}}"""
                "/berserker-search/search/personal/turnover" -> {
                    pageCalls.incrementAndGet()
                    scope.launch { status = "完成次数=${completions.get()} iframePOST=${iframePosts.get()} 流水请求=${pageCalls.get()}" }
                    """{"code":200,"data":{"current":1,"pages":3,"total":101,"records":[{"orderId":"synthetic-row","resume":"合成交易","tranamt":820,"typeFrom":"2","jndatetimeStr":"2026-11-13 12:00:00"}]}}"""
                }
                else -> """{"code":404,"data":{}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        client = CardHistoryClient(account, CardHistoryAuthenticator {
            throw CardHistoryException(CardHistoryFailure.INTERACTION_REQUIRED)
        }, http, bridge)
        val resources = CardHistoryWebResourceProvider { request ->
            val path = request.url.path
            when {
                path == "/entry" && failNext.compareAndSet(true, false) ->
                    html("""<script>window.location.href='$BASE/blocked-navigation';</script>""")
                path == "/entry" -> html("""<h2>验证码合成页面</h2><iframe title="验证码表单" src="/cas/frame" style="border:0;width:100%;height:520px"></iframe>""")
                path == "/cas/frame" -> html("""
                    <form method="POST" action="/cas/login">
                      <label>合成验证码<input name="captcha" inputmode="numeric" autocomplete="off"></label>
                      <button type="submit">完成验证</button>
                    </form>
                    <p><a href="/change-account" target="_top">模拟账号变化</a></p>
                """.trimIndent())
                path == "/cas/login" && request.method == "POST" && !request.isForMainFrame -> {
                    iframePosts.incrementAndGet()
                    html("""<script>window.top.location.href='$BASE/result?ticket=fixture';</script>""")
                }
                else -> html("Unexpected synthetic resource", 404)
            }
        }
        setContentView(ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    if (intent.getBooleanExtra("screen", false)) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Text(status, Modifier.padding(8.dp))
                            Box(Modifier.weight(1f)) {
                                CardHistoryScreenContent(client, back = { finish() }, accountSettings = {}, syntheticResources = resources)
                            }
                        }
                        return@MaterialTheme
                    }
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("流水验证合成夹具", style = MaterialTheme.typography.titleLarge)
                        Text(status)
                        Button(onClick = { attempt = client.beginInteractiveLogin() }) { Text("打开验证") }
                        Button(onClick = { failNext.set(true); attempt = client.beginInteractiveLogin() }) { Text("模拟受阻跳转") }
                        Text("仅合成数据，不访问学校或网络。")
                    }
                    attempt?.let { active ->
                        CardHistoryLoginDialog(client, active, resources,
                            onSuccess = {
                                status = "验证成功 完成次数=${completions.get()} iframePOST=${iframePosts.get()}"
                                attempt = null
                            },
                            onFailure = { status = "验证失败"; attempt = null },
                            onClose = { client.cancelInteractiveLogin(active.id); status = "已取消"; attempt = null },
                        )
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.clearSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun html(body: String, status: Int = 200) = WebResourceResponse(
        "text/html", "UTF-8", status, if (status == 200) "OK" else "Fixture error", emptyMap(),
        ByteArrayInputStream(("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:sans-serif;padding:16px}input,button,a{display:block;font-size:20px;margin-top:16px;min-height:48px}input{width:90%}</style>""" + body).toByteArray()),
    )

    private companion object {
        const val HOST = "card-fixture.invalid"
        const val BASE = "https://$HOST"
    }
}
