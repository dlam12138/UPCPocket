package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.data.cardhistory.CardHistoryReadTransport
import com.upcpocket.app.data.cardhistory.CardHistoryRequestPolicyException
import com.upcpocket.app.data.cardhistory.CardReadBodyTooLargeException
import com.upcpocket.app.data.cardhistory.awaitCardReadResponse
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportSnapshot
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A fixed GET allowlist used only while loading read-only recharge preparation data. */
internal object CardRechargePreparationReadPolicy {
    private const val SCHOOL_HOST = "mb-xyk.upc.edu.cn"

    fun allows(request: Request): Boolean {
        val url = request.url
        val hostHeaders = request.headers.values("Host")
        if (request.method != "GET" || request.body != null || !url.isHttps || url.host != SCHOOL_HOST ||
            url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            request.header("Authorization") != null || request.header("Proxy-Authorization") != null ||
            request.header("Cookie") != null ||
            hostHeaders.size > 1 ||
            hostHeaders.singleOrNull()?.let { it !in setOf(SCHOOL_HOST, "$SCHOOL_HOST:443") } == true
        ) return false

        val tokenValues = request.headers.values("synjones-auth")
        return when (url.encodedPath) {
            "/berserker-base/user",
            "/berserker-app/frontInfo" -> sessionHeadersMatch(request, url, "synAccessSource", "h5", tokenValues)
            "/berserker-app/ykt/tsm/queryCard" ->
                sessionHeadersMatch(request, url, "scene", "recharge", tokenValues)
            "/charge/feeitem/singleFeeitem" -> {
                val id = singleQueryValue(url, "feeitemid")
                url.queryParameterNames == setOf("feeitemid") &&
                    id != null && id.matches(Regex("[0-9]{1,10}")) && hasSession(request, tokenValues)
            }
            else -> url.toString() == CardRechargeProtocol.SIGNER_SOURCE &&
                url.querySize == 0 && tokenValues.isEmpty() &&
                request.headers.values("synAccessSource").isEmpty()
        }
    }

    private fun sessionHeadersMatch(
        request: Request,
        url: okhttp3.HttpUrl,
        queryName: String,
        queryValue: String,
        tokenValues: List<String>,
    ): Boolean = url.queryParameterNames == setOf(queryName) &&
        singleQueryValue(url, queryName) == queryValue && hasSession(request, tokenValues)

    private fun singleQueryValue(url: okhttp3.HttpUrl, name: String): String? =
        url.queryParameterValues(name).singleOrNull()

    private fun hasSession(request: Request, tokenValues: List<String>): Boolean {
        val token = tokenValues.singleOrNull() ?: return false
        return token.startsWith("bearer ") && token.length > "bearer ".length &&
            request.headers.values("synAccessSource").singleOrNull() == "h5"
    }
}

internal class CardRechargeReadFailure(
    val stage: String,
    val transport: DiagnosticTransportSnapshot?,
) : IOException(stage)

/** Captures the approved preparation request so later interceptors cannot substitute another allowed read. */
private class CardRechargePreparationRequestBinding private constructor(
    private val url: HttpUrl,
    private val method: String,
    private val hasBody: Boolean,
    private val sessionHeaders: List<String>,
    private val accessSourceHeaders: List<String>,
) {
    fun matches(request: Request): Boolean =
        request.url == url && request.method == method && (request.body != null) == hasBody &&
            request.headers.values("synjones-auth") == sessionHeaders &&
            request.headers.values("synAccessSource") == accessSourceHeaders

    override fun toString(): String = "CardRechargePreparationRequestBinding([REDACTED])"

    companion object {
        fun capture(request: Request) = CardRechargePreparationRequestBinding(
            url = request.url,
            method = request.method,
            hasBody = request.body != null,
            sessionHeaders = request.headers.values("synjones-auth").toList(),
            accessSourceHeaders = request.headers.values("synAccessSource").toList(),
        )
    }
}

/**
 * Builds one bounded OkHttp Call per preparation GET. The base client supplied by the existing
 * recharge transport retains its TLS configuration; only this explicitly scoped reader enables
 * connection recovery.
 */
internal class CardRechargeReadTransport(client: OkHttpClient) {
    private val transport = CardHistoryReadTransport(
        http = client,
        requestPolicy = { request ->
            CardRechargePreparationReadPolicy.allows(request) &&
                request.tag(CardRechargePreparationRequestBinding::class.java)?.matches(request) == true
        },
        connectTimeoutCapMs = PREPARATION_CONNECT_TIMEOUT_MS,
    )

    suspend fun get(
        request: Request,
        onTransport: (DiagnosticTransportSnapshot?) -> Unit = {},
    ): RechargeResponse {
        val boundRequest = request.newBuilder()
            .tag(CardRechargePreparationRequestBinding::class.java, CardRechargePreparationRequestBinding.capture(request))
            .build()
        if (!CardRechargePreparationReadPolicy.allows(boundRequest)) throw CardRechargeReadFailure("P-ORIGIN", null)
        val prepared = transport.prepare(boundRequest)
        val trace = prepared.trace
        fun snapshot(): DiagnosticTransportSnapshot? = runCatching { trace?.snapshot() }.getOrNull()
        fun reportSnapshot() = runCatching { onTransport(snapshot()) }
        try {
            val response = prepared.client.newCall(prepared.request)
                .awaitCardReadResponse(trace, MAX_RECHARGE_RESPONSE_BYTES)
            reportSnapshot()
            return RechargeResponse(
                status = response.code,
                body = response.body.orEmpty(),
                location = response.location,
                transportSnapshot = trace?.snapshot(),
            )
        } catch (_: CardHistoryRequestPolicyException) {
            reportSnapshot()
            throw CardRechargeReadFailure("P-ORIGIN", snapshot())
        } catch (_: CardReadBodyTooLargeException) {
            trace?.responseTooLarge()
            reportSnapshot()
            throw CardRechargeReadFailure("P-SIZE", snapshot())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            reportSnapshot()
            throw cancelled
        } catch (_: Exception) {
            reportSnapshot()
            throw CardRechargeReadFailure("P-NETWORK", snapshot())
        }
    }

    private companion object {
        const val MAX_RECHARGE_RESPONSE_BYTES = 2_000_000
        val PREPARATION_CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10)
    }
}
