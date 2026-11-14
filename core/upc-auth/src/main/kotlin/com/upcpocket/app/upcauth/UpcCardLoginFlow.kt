package com.upcpocket.app.upcauth

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeEvidence
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeType
import com.upcpocket.app.platform.diagnostics.DiagnosticCredentialState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticServiceParameter
import com.upcpocket.app.platform.diagnostics.Diagnostics
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class CardCasForm(
    val url: String,
    val fields: Map<String, String>,
    val captchaId: String? = null,
    val interactionRequired: Boolean = captchaId != null,
    val actionUrl: String = url,
    val explicitCredentialRejection: Boolean = false,
) {
    override fun toString() = "CardCasForm([REDACTED])"
}
internal class CardCasRejected(val form: CardCasForm) : IOException("N-CAS：学校登录请求未完成")
internal class CardCasInteractionRequired : IOException("N-CAS：请在学校页面继续登录")
internal class CardCasFormException(
    val diagnosticDetail: DiagnosticDetail,
    message: String = "N-CAS-FORM：学校登录表单未通过校验",
) : IOException(message)

internal object CardCasProtocol {
    fun redirect(base: String, location: String): String {
        val resolved = URI(base).resolve(location)
        val url = if (resolved.scheme == "http" && resolved.host == CardAuthOrigins.HOST &&
            resolved.rawUserInfo == null && resolved.port in setOf(-1, 80)) {
            "https://" + CardAuthOrigins.HOST + resolved.rawPath +
                (resolved.rawQuery?.let { "?$it" } ?: "") + (resolved.rawFragment?.let { "#$it" } ?: "")
        } else resolved.toString()
        if (!CardAuthOrigins.allowed(url)) throw IOException("N-CAS-REDIRECT：学校跳转到未核对的服务")
        return url
    }
    fun query(url: String, key: String): String? {
        val values = URI(url).rawQuery.orEmpty().split('&').mapNotNull { item ->
            val pair = item.split('=', limit = 2)
            val name = runCatching { URLDecoder.decode(pair[0], "UTF-8") }
                .getOrElse { throw IOException("N-CAS-QUERY：学校登录结果参数无效") }
            if (name != key) return@mapNotNull null
            runCatching { URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8") }
                .getOrElse { throw IOException("N-CAS-QUERY：学校登录结果参数无效") }
        }
        if (values.size > 1) throw IOException("N-CAS-QUERY：学校登录结果参数有歧义")
        return values.singleOrNull()
    }
    fun encode(fields: Map<String, String>) = fields.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
    private fun attribute(tag: String, name: String): String? =
        Regex("\\b" + name + "\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(2)?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")

    fun form(html: String, url: String): CardCasForm {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "cas.upc.edu.cn" || uri.rawPath != "/cas/login" ||
            uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) throw IOException("N-CAS-URL：学校登录地址不匹配")
        val page = url.toHttpUrlOrNull() ?: throw IOException("N-CAS-URL：学校登录地址不匹配")
        val serviceValues = page.queryParameterValues("service")
        if (page.queryParameterNames != setOf("service") || serviceValues.size != 1 || serviceValues.single().isNullOrBlank()) {
            throw IOException("N-CAS-SERVICE：学校登录参数未通过校验")
        }
        val markup = CasCaptchaChallengeDetector.visibleMarkup(html)
        val form = Regex("(?is)<form\\b[^>]*id=[\"']loginForm[\"'][^>]*>(.*?)</form>").find(markup)?.value
        if (form == null) {
            if (requiresInteraction(html)) return CardCasForm(url, emptyMap(), interactionRequired = true)
            throw CardCasFormException(
                DiagnosticDetail.CAS_FORM_NOT_FOUND,
                "N-CAS-FORM：学校登录表单已变化",
            )
        }
        val fields = Regex("(?is)<input\\b[^>]*>").findAll(form).mapNotNull {
            val name = attribute(it.value, "name") ?: return@mapNotNull null
            name to attribute(it.value, "value").orEmpty()
        }.toMap()
        val fieldCounts = Regex("(?is)<input\\b[^>]*>").findAll(form).mapNotNull {
            attribute(it.value, "name")?.lowercase(Locale.ROOT)
        }.groupingBy { it }.eachCount()
        val open = Regex("(?is)^<form\\b[^>]*>").find(form)?.value.orEmpty()
        val rawAction = attribute(open, "action")
        val action = page.resolve(rawAction ?: url)
            ?: throw CardCasFormException(
                DiagnosticDetail.CAS_FORM_ACTION_REJECTED,
                "N-CAS-ACTION：学校登录表单地址未通过校验",
            )
        val actionService = action.queryParameterValues("service")
        val execution = fields["execution"]
        val hasPasswordField = Regex("(?is)<input\\b[^>]*name=[\"']password[\"'][^>]*type=[\"']password[\"']").containsMatchIn(form) ||
            Regex("(?is)<input\\b[^>]*type=[\"']password[\"'][^>]*name=[\"']password[\"']").containsMatchIn(form)
        val passwordForm = hasPasswordField && fieldCounts["password"] == 1 && fieldCounts["execution"] == 1 &&
            fieldCounts["type"] == 1 && fieldCounts["_eventid"] == 1 &&
            isValidCasExecution(execution) &&
            fields["_eventId"] == "submit" && fields["type"] == "username_password"
        val validAction = action.scheme == "https" && action.host == "cas.upc.edu.cn" &&
            action.port == 443 && action.encodedPath == "/cas/login" &&
            action.username.isEmpty() && action.password.isEmpty() && action.fragment == null &&
            action.queryParameterNames == setOf("service") && actionService.size == 1 &&
            actionService.single() == serviceValues.single()
        val captcha = CasCaptchaChallengeDetector.inspect(html)
        val explicitInteraction = requiresInteraction(html)
        if (!passwordForm) {
            if (explicitInteraction) return CardCasForm(url, fields, interactionRequired = true)
            val formDetail = when {
                !hasPasswordField || fieldCounts["password"] != 1 ->
                    DiagnosticDetail.CAS_FORM_PASSWORD_FIELD_MISSING
                fieldCounts["execution"] == 0 || execution == null ->
                    DiagnosticDetail.CAS_FORM_EXECUTION_MISSING
                !isValidCasExecution(execution) -> DiagnosticDetail.CAS_FORM_EXECUTION_INVALID
                fieldCounts["execution"] != 1 -> DiagnosticDetail.CAS_FORM_REJECTED
                fieldCounts["type"] == 0 -> DiagnosticDetail.CAS_FORM_TYPE_FIELD_MISSING
                fields["type"] != "username_password" -> DiagnosticDetail.CAS_FORM_TYPE_REJECTED
                fieldCounts["type"] != 1 -> DiagnosticDetail.CAS_FORM_REJECTED
                fieldCounts["_eventid"] == 0 -> DiagnosticDetail.CAS_FORM_EVENT_FIELD_MISSING
                fields["_eventId"] != "submit" -> DiagnosticDetail.CAS_FORM_EVENT_REJECTED
                else -> DiagnosticDetail.CAS_FORM_REJECTED
            }
            throw CardCasFormException(formDetail)
        }
        if (!validAction) {
            throw CardCasFormException(
                DiagnosticDetail.CAS_FORM_ACTION_REJECTED,
                "N-CAS-ACTION：学校登录表单地址未通过校验",
            )
        }
        val errorText = runCatching { parseCasLoginForm(html).errorText }.getOrNull()
        return CardCasForm(
            url = url,
            fields = fields,
            captchaId = captcha.captchaId,
            interactionRequired = explicitInteraction,
            actionUrl = action.toString(),
            explicitCredentialRejection = isExplicitCasCredentialRejection(errorText),
        )
    }
    /** A denied CAS response can still contain a usable login/challenge document. */
    fun recognizedDeniedLogin(html: String, url: String): Boolean {
        val parsed = runCatching { form(html, url) }.getOrNull() ?: return false
        val passwordForm = !parsed.fields["execution"].isNullOrBlank() &&
            parsed.fields["_eventId"] == "submit" && parsed.fields["type"] == "username_password"
        // Labels and alternate-tab text alone do not establish a denied challenge page.
        return passwordForm || (parsed.interactionRequired &&
            (CasCaptchaChallengeDetector.inspect(html).active || parsed.fields["type"] == "username_smstoken"))
    }

    private fun requiresInteraction(html: String): Boolean =
        CasCaptchaChallengeDetector.inspect(html).active || parseCasLoginForm(html).hasSecondFactor

    fun body(form: CardCasForm, username: String, password: String, captcha: String): String {
        require(username.matches(Regex("[0-9]{8,16}")) && password.isNotBlank())
        if (form.interactionRequired) throw IOException("N-CAS：请在学校页面继续登录")
        if (form.captchaId != null && captcha.isBlank()) throw IOException("N-CAS-CAPTCHA：请输入图片验证码")
        val submit = form.fields["submit"]?.takeIf { value ->
            value.length in 1..128 && value.none { it <= ' ' || it == '\u007f' }
        } ?: "LOGIN"
        return encode(form.fields.filterKeys { it !in setOf("username", "password", "captcha") } +
            mapOf("username" to username, "password" to password, "submit" to submit) +
            if (form.captchaId != null) mapOf("captcha" to captcha.trim().lowercase()) else emptyMap())
    }

    fun serviceParameterState(url: String): DiagnosticServiceParameter {
        val parsed = url.toHttpUrlOrNull() ?: return DiagnosticServiceParameter.UNKNOWN
        if (parsed.host != "cas.upc.edu.cn" || parsed.encodedPath != "/cas/login") {
            return DiagnosticServiceParameter.NOT_APPLICABLE
        }
        val values = parsed.queryParameterValues("service")
        return when {
            values.isEmpty() -> DiagnosticServiceParameter.MISSING
            values.size != 1 -> DiagnosticServiceParameter.DUPLICATE
            parsed.queryParameterNames != setOf("service") -> DiagnosticServiceParameter.MISMATCH
            values.single().isNullOrBlank() -> DiagnosticServiceParameter.MISMATCH
            else -> DiagnosticServiceParameter.MATCH
        }
    }
    fun ticket(url: String): String? = if (CardAuthOrigins.tokenPage(url)) query(url, "ticket")?.takeIf { it.isNotBlank() } else null

    // Official plat root created() adopts synjones-auth as its LoginAction token.
    // This is a completed login result, distinct from loginTransit's exchangeable ticket.
    fun directToken(url: String): String? {
        if (!CardAuthOrigins.tokenPage(url)) return null
        val raw = query(url, "synjones-auth") ?: return null
        if (query(url, "visitor") == "1") throw IOException("N-CAS-VISITOR：学校返回访客会话，尚未完成本人登录")
        val token = if (raw.startsWith("bearer ", ignoreCase = true)) raw.substring(7) else raw
        if (token.isBlank() || token.length > 16384 || token.any { it <= ' ' || it == '\u007f' })
            throw IOException("N-CAS-AUTH：学校返回的登录令牌格式不完整")
        return token
    }

    fun loginResult(url: String): Boolean = directToken(url) != null || ticket(url) != null

    fun hasLoginResultMarker(url: String): Boolean = runCatching {
        if (!CardAuthOrigins.tokenPage(url)) return@runCatching false
        hasQueryParameter(url, "synjones-auth") || hasQueryParameter(url, "ticket")
    }.getOrDefault(false)

    fun hasQueryParameter(url: String, key: String): Boolean = runCatching {
        URI(url).rawQuery.orEmpty().split('&').any { item ->
            URLDecoder.decode(item.substringBefore('='), "UTF-8") == key
        }
    }.getOrDefault(false)

    /** Read only literal browser redirects from the official callback; never execute JavaScript. */
    fun pageRedirect(body: String, url: String): String? {
        if (URI(url).host != CardAuthOrigins.HOST) return null
        val text = Regex("(?is)<script\\b[^>]*>(.*?)</script>").findAll(body).joinToString("\n") { it.groupValues[1] }
        val assignment = Regex("(?:window\\.)?location\\.href\\s*=\\s*([\"'])([^\r\n]*?)\\1\\s*[;]?")
            .find(text)?.groupValues?.get(2)
        val call = Regex("(?:window\\.)?location\\.(?:replace|assign)\\s*\\(\\s*([\"'])([^\r\n]*?)\\1\\s*\\)")
            .find(text)?.groupValues?.get(2)
        val target = assignment ?: call ?: return null
        return redirect(url, target.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&"))
    }

    fun callbackDiagnostic(url: String, body: String): String {
        val uri = URI(url)
        val page = when {
            uri.path.startsWith("/berserker-auth/cas/login/") -> "校园卡回调"
            uri.path.startsWith("/plat") -> "校园卡前端"
            else -> "其它学校页面"
        }
        val keys = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') }
            .filter { it.matches(Regex("[A-Za-z_][A-Za-z_0-9-]{0,31}")) }.distinct().take(8).joinToString(",")
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val code = (root?.get("code") as? JsonPrimitive)?.intOrNull
        val format = when { root != null -> "JSON"; body.contains("<script", true) -> "HTML脚本"; body.contains('<') -> "HTML"; else -> "文本" }
        return "$page/$format" + (code?.let { "/code=$it" } ?: "") + if (keys.isEmpty()) "" else "/参数:$keys"
    }
}

/** An isolated in-memory CAS cookie jar. Password POSTs are sent only to the exact HTTPS CAS form. */
internal class UpcCardLoginFlow(
    private val transport: UpcAuthTransport,
    private val progress: (String) -> Unit = {},
    private val operation: DiagnosticOperation = Diagnostics.beginOperation(),
) {
    private val cookies = UpcAuthCookieJar(setOf("cas.upc.edu.cn", CardAuthOrigins.HOST))
    private data class Response(val url: String, val status: Int, val location: String?, val bytes: ByteArray)

    fun begin(): CardCasForm {
        cardDiagnostic(
            operation, DiagnosticAction.AUTH_STAGE, DiagnosticResult.PENDING,
            DiagnosticPhase.ENTRY_GET, DiagnosticDetail.AUTH_REQUIRED,
            passwordSubmitted = 0,
        )
        val response = follow(CardAuthOrigins.ENTRY)
        val form = try {
            CardCasProtocol.form(response.bytes.toString(Charsets.UTF_8), response.url)
        } catch (failure: IOException) {
            val serviceParameter = CardCasProtocol.serviceParameterState(response.url)
            val detail = (failure as? CardCasFormException)?.diagnosticDetail ?: when (serviceParameter) {
                DiagnosticServiceParameter.MISSING -> DiagnosticDetail.SERVICE_PARAMETER_MISSING
                DiagnosticServiceParameter.MISMATCH -> DiagnosticDetail.SERVICE_PARAMETER_MISMATCH
                DiagnosticServiceParameter.DUPLICATE -> DiagnosticDetail.SERVICE_PARAMETER_DUPLICATE
                else -> DiagnosticDetail.CAS_FORM_REJECTED
            }
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                DiagnosticPhase.CAS_FORM,
                detail,
                reason = DiagnosticReason.SCHEMA,
                httpStatus = response.status,
                passwordSubmitted = 0,
                serviceParameter = serviceParameter,
            )
            throw failure
        }
        val challenge = cardChallenge(form)
        cardDiagnostic(
            operation,
            if (form.interactionRequired) DiagnosticAction.AUTH_INTERACTION else DiagnosticAction.AUTH_STAGE,
            if (form.interactionRequired) DiagnosticResult.BLOCKED else DiagnosticResult.OK,
            DiagnosticPhase.CAS_FORM,
            when {
                form.interactionRequired -> DiagnosticDetail.CAS_CHALLENGE_CONFIRMED
                response.status == 401 -> DiagnosticDetail.CAS_GET_401_FORM
                response.status == 403 -> DiagnosticDetail.CAS_GET_403_FORM
                else -> DiagnosticDetail.CAS_FORM_ACCEPTED
            },
            reason = if (form.interactionRequired) DiagnosticReason.INTERACTION else null,
            httpStatus = response.status,
            passwordSubmitted = 0,
            challengeType = challenge,
            challengeEvidence = if (form.interactionRequired) DiagnosticChallengeEvidence.CONFIRMED
                else DiagnosticChallengeEvidence.ABSENT,
            serviceParameter = DiagnosticServiceParameter.MATCH,
        )
        CardCasProtocol.query(form.url, "service")?.let { service ->
            cookies.save(form.url, listOf("service=$service; Path=/; Secure"))
        }
        return form
    }

    fun login(form: CardCasForm, username: String, password: String, captcha: String): String {
        val loginUri = URI(form.url)
        if (loginUri.scheme != "https" || loginUri.host != "cas.upc.edu.cn" || loginUri.rawPath != "/cas/login" ||
            loginUri.rawUserInfo != null || loginUri.port !in setOf(-1, 443)) throw IOException("N-CAS-URL：登录地址不匹配")
        val response = follow(form.actionUrl, CardCasProtocol.body(form, username, password, captcha))
        if (!CardCasProtocol.loginResult(response.url)) {
            if (URI(response.url).host == "cas.upc.edu.cn") {
                val returnedForm = runCatching {
                    CardCasProtocol.form(response.bytes.toString(Charsets.UTF_8), response.url)
                }.getOrNull()
                if (returnedForm?.interactionRequired == true) {
                    cardDiagnostic(
                        operation, DiagnosticAction.AUTH_INTERACTION, DiagnosticResult.BLOCKED,
                        DiagnosticPhase.CAS_POST, DiagnosticDetail.CAS_CHALLENGE_CONFIRMED,
                        reason = DiagnosticReason.INTERACTION, httpStatus = response.status,
                        passwordSubmitted = 1, challengeType = cardChallenge(returnedForm),
                        challengeEvidence = DiagnosticChallengeEvidence.CONFIRMED,
                    )
                    throw CardCasInteractionRequired()
                }
                if (returnedForm?.explicitCredentialRejection == true) {
                    cardDiagnostic(
                        operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                        DiagnosticPhase.CAS_POST, DiagnosticDetail.CAS_POST_EXPLICIT_REJECTION,
                        reason = DiagnosticReason.AUTH, httpStatus = response.status,
                        passwordSubmitted = 1, challengeType = DiagnosticChallengeType.NONE,
                        challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
                    )
                    throw CardCasRejected(returnedForm)
                }
                // No challenge evidence and no explicit rejection is still an unknown
                // refusal. The existing school page remains available to continue login.
                cardDiagnostic(
                    operation, DiagnosticAction.AUTH_INTERACTION, DiagnosticResult.BLOCKED,
                    DiagnosticPhase.CAS_POST, DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
                    reason = DiagnosticReason.INTERACTION, httpStatus = response.status,
                    passwordSubmitted = 1,
                    challengeType = returnedForm?.let(::cardChallenge) ?: DiagnosticChallengeType.UNKNOWN,
                    challengeEvidence = if (returnedForm == null) DiagnosticChallengeEvidence.UNKNOWN
                        else DiagnosticChallengeEvidence.ABSENT,
                )
                throw CardCasInteractionRequired()
            }
            throw IOException("N-CAS-TICKET：未取得新系统票据（" + CardCasProtocol.callbackDiagnostic(response.url, response.bytes.toString(Charsets.UTF_8)) + "）")
        }
        return completeLoginResult(response.url, DiagnosticBindingSource.PASSWORD)
    }

    /** Complete a result captured by the restricted manual school WebView. */
    fun completeLoginResult(
        url: String,
        bindingSource: DiagnosticBindingSource = DiagnosticBindingSource.VERIFIED_WEB,
    ): String {
        val (directToken, ticket) = try {
            CardCasProtocol.directToken(url) to CardCasProtocol.ticket(url)
        } catch (failure: IOException) {
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_REJECTED,
                reason = DiagnosticReason.POLICY, callbackState = DiagnosticCallbackState.REJECTED,
                bindingSource = bindingSource,
            )
            throw failure
        }
        if (directToken != null && ticket != null) {
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_REJECTED,
                reason = DiagnosticReason.POLICY, callbackState = DiagnosticCallbackState.REJECTED,
                bindingSource = bindingSource,
            )
            throw IOException("N-CAS-RESULT：学校登录结果有歧义")
        }
        cardDiagnostic(
            operation, DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK,
            DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_CONSUMED,
            callbackState = DiagnosticCallbackState.CONSUMED,
            serviceParameter = DiagnosticServiceParameter.NOT_APPLICABLE,
            bindingSource = bindingSource,
        )
        directToken?.let {
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_COMPLETE, DiagnosticResult.OK,
                DiagnosticPhase.SESSION_ADOPT, DiagnosticDetail.SESSION_ADOPTED,
                callbackState = DiagnosticCallbackState.CONSUMED,
                bindingSource = bindingSource,
            )
            return it
        }
        val validTicket = ticket
            ?: throw IOException("N-CAS-TICKET：未取得校园卡登录结果")
        if (validTicket.length > 16384 || validTicket.isBlank() || validTicket.any { it <= ' ' || it == '\u007f' })
            throw IOException("N-CAS-TICKET：学校返回的登录票据格式不完整")
        val body = CardCasProtocol.encode(mapOf("username" to validTicket, "password" to validTicket,
            "grant_type" to "password", "scope" to "all", "loginFrom" to "h5", "logintype" to "sso",
            "device_token" to "h5", "synAccessSource" to "h5"))
        val result = request(CardAuthOrigins.BASE + "/berserker-auth/oauth/token", body,
            mapOf("Authorization" to "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"))
        val root = runCatching { Json.parseToJsonElement(result.bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: throw IOException("N-CAS-TOKEN：新系统未返回登录令牌")
        val token = (root["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IOException("N-CAS-TOKEN：学校票据兑换未成功")
        cardDiagnostic(
            operation, DiagnosticAction.AUTH_COMPLETE, DiagnosticResult.OK,
            DiagnosticPhase.SESSION_ADOPT, DiagnosticDetail.SESSION_ADOPTED,
            callbackState = DiagnosticCallbackState.CONSUMED,
            bindingSource = bindingSource,
        )
        return token
    }

    private fun follow(start: String, post: String? = null): Response {
        var url = start
        var body = post
        repeat(12) {
            val response = request(url, body)
            if (response.status !in 300..399) {
                val target = CardCasProtocol.pageRedirect(response.bytes.toString(Charsets.UTF_8), response.url)
                    ?: return response
                url = target; body = null
                if (CardCasProtocol.loginResult(url)) return Response(url, 200, null, byteArrayOf())
                return@repeat
            }
            if (response.status !in setOf(301, 302, 303)) throw IOException("N-CAS-REDIRECT：学校登录跳转方式待核对")
            // The server redirects its HTTP callback to HTTPS. Go directly to HTTPS while
            // preserving the CAS service parameter and ticket query byte-for-byte.
            url = CardCasProtocol.redirect(url, response.location ?: throw IOException("N-CAS-REDIRECT：缺少跳转地址"))
            body = null
            if (CardCasProtocol.loginResult(url)) return Response(url, 200, null, byteArrayOf())
        }
        throw IOException("N-CAS-REDIRECT：学校登录跳转次数过多")
    }

    private fun request(url: String, body: String?, extra: Map<String, String> = emptyMap()): Response {
        if (!CardAuthOrigins.allowed(url)) throw IOException("N-CAS-URL：登录地址不匹配")
        val uri = URI(url)
        val (step, description) = when {
            uri.host == "cas.upc.edu.cn" && body != null -> "CAS-SUBMIT" to "正在提交学校账号验证…"
            uri.host == "cas.upc.edu.cn" && uri.path == "/cas/captcha" -> "CAPTCHA" to "正在获取学校验证码…"
            uri.host == "cas.upc.edu.cn" -> "CAS-FORM" to "正在获取学校登录表单…"
            uri.path == "/berserker-auth/oauth/token" -> "TOKEN" to "正在将学校票据换为校园卡令牌…"
            uri.path == "/berserker-auth/cas/login/wislearn" -> "CAS-CALLBACK" to "正在请求校园卡登录回调…"
            else -> "CAS-ENTRY" to "正在连接校园卡登录服务…"
        }
        progress(description)
        if (body != null && !((uri.scheme == "https" && uri.host == "cas.upc.edu.cn" && uri.path == "/cas/login") ||
                (uri.scheme == "https" && uri.host == CardAuthOrigins.HOST && uri.path == "/berserker-auth/oauth/token")))
            throw IOException("N-CAS-URL：拒绝向此地址提交登录资料")
        val headers = linkedMapOf(
            "Accept" to "text/html,application/json,image/*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        cookies.header(url)?.let { headers["Cookie"] = it }
        headers.putAll(extra)
        if (body != null) {
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            headers["Origin"] = "https://" + uri.host
            headers["Referer"] = if (uri.host == "cas.upc.edu.cn") url else CardAuthOrigins.BASE + "/plat/"
            if (uri.host == CardAuthOrigins.HOST) headers["synAccessSource"] = "h5"
        }
        val phase = cardRequestPhase(uri.host.orEmpty(), uri.path.orEmpty(), body != null)
        val passwordSubmitted = if (uri.host == "cas.upc.edu.cn" && uri.path == "/cas/login" && body != null) 1 else 0
        val response = try {
            transport.execute(UpcAuthHttpRequest(if (body == null) "GET" else "POST", url, headers, body))
        } catch (failure: UpcAuthTransportException) {
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                phase, if (failure.failure == UpcAuthTransportFailure.TLS) DiagnosticDetail.TRANSPORT_TLS
                    else DiagnosticDetail.TRANSPORT_NETWORK,
                reason = if (failure.failure == UpcAuthTransportFailure.TLS) DiagnosticReason.TLS
                    else DiagnosticReason.NETWORK,
                passwordSubmitted = passwordSubmitted,
            )
            throw failure
        }
        cookies.save(url, response.headers["set-cookie"].orEmpty())
        val exactCasLogin = uri.scheme == "https" && uri.host == "cas.upc.edu.cn" &&
            uri.rawPath == "/cas/login" && uri.rawUserInfo == null && uri.port in setOf(-1, 443)
        if (response.status == 429) {
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL, phase,
                DiagnosticDetail.CAS_RATE_LIMITED, DiagnosticReason.POLICY,
                httpStatus = response.status, passwordSubmitted = passwordSubmitted,
            )
            throw CardCasHttpFailure(UpcCardAuthFailure.ACCESS_DENIED)
        }
        if (response.status in 500..599) {
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL, phase,
                DiagnosticDetail.CAS_SERVER_ERROR, DiagnosticReason.HTTP,
                httpStatus = response.status, passwordSubmitted = passwordSubmitted,
            )
            throw CardCasHttpFailure(UpcCardAuthFailure.UPSTREAM_ERROR)
        }
        if (exactCasLogin && response.status in setOf(401, 403)) {
            // Preserve denied CAS responses for the form classifier. An ordinary GET form may
            // proceed to one POST; POST refusals are classified once by login() below.
            return Response(url, response.status, response.header("Location"), response.body)
        }
        if (response.status >= 400) {
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL, phase,
                DiagnosticDetail.RESPONSE_HTTP, DiagnosticReason.HTTP,
                httpStatus = response.status, passwordSubmitted = passwordSubmitted,
            )
            throw CardCasHttpFailure(UpcCardAuthFailure.ACCESS_DENIED)
        }
        if (phase in setOf(DiagnosticPhase.ENTRY_GET, DiagnosticPhase.CAS_GET, DiagnosticPhase.CAS_POST,
                DiagnosticPhase.CAS_RETURN, DiagnosticPhase.BUSINESS_CALLBACK)) {
            cardDiagnostic(
                operation, DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK, phase,
                when {
                    phase == DiagnosticPhase.CAS_GET && response.status == 401 -> DiagnosticDetail.CAS_GET_401_FORM
                    phase == DiagnosticPhase.CAS_GET && response.status == 403 -> DiagnosticDetail.CAS_GET_403_FORM
                    phase == DiagnosticPhase.CAS_POST -> DiagnosticDetail.CAS_FORM_ACCEPTED
                    else -> DiagnosticDetail.RESPONSE_BUSINESS
                },
                httpStatus = response.status, passwordSubmitted = passwordSubmitted,
            )
        }
        return Response(url, response.status, response.header("Location"), response.body)
    }

    private fun cardRequestPhase(host: String, path: String, post: Boolean): DiagnosticPhase = when {
        host == "cas.upc.edu.cn" && path == "/cas/login" && post -> DiagnosticPhase.CAS_POST
        host == "cas.upc.edu.cn" && path == "/cas/login" -> DiagnosticPhase.CAS_GET
        host == CardAuthOrigins.HOST && path == "/berserker-auth/oauth/token" -> DiagnosticPhase.SESSION_ADOPT
        host == CardAuthOrigins.HOST && path.startsWith("/berserker-auth/cas/login/") -> DiagnosticPhase.CAS_RETURN
        host == CardAuthOrigins.HOST && (path == "/plat" || path.startsWith("/plat/")) -> DiagnosticPhase.BUSINESS_CALLBACK
        else -> DiagnosticPhase.ENTRY_GET
    }
}

internal class CardCasHttpFailure(val authFailure: UpcCardAuthFailure) : IOException("N-CAS-HTTP")

private fun cardChallenge(form: CardCasForm): DiagnosticChallengeType = when {
    form.captchaId != null -> DiagnosticChallengeType.CAPTCHA
    form.fields["type"] == "username_smstoken" -> DiagnosticChallengeType.SECOND_FACTOR
    form.interactionRequired -> DiagnosticChallengeType.UNKNOWN
    form.fields.containsKey("password") -> DiagnosticChallengeType.PASSWORD_FORM
    else -> DiagnosticChallengeType.UNKNOWN
}

internal fun cardDiagnostic(
    operation: DiagnosticOperation,
    action: DiagnosticAction,
    result: DiagnosticResult,
    phase: DiagnosticPhase,
    detail: DiagnosticDetail,
    reason: DiagnosticReason? = null,
    httpStatus: Int? = null,
    passwordSubmitted: Int? = null,
    credentialState: DiagnosticCredentialState? = null,
    challengeType: DiagnosticChallengeType? = null,
    challengeEvidence: DiagnosticChallengeEvidence? = null,
    serviceParameter: DiagnosticServiceParameter? = null,
    callbackState: DiagnosticCallbackState? = null,
    bindingSource: DiagnosticBindingSource? = null,
) = recordUpcAuthDiagnostic(
    operation, DiagnosticModule.CARD, action, result, phase, detail, reason, httpStatus,
    passwordSubmitted = passwordSubmitted, credentialState = credentialState,
    challengeType = challengeType, challengeEvidence = challengeEvidence,
    serviceParameter = serviceParameter, callbackState = callbackState, bindingSource = bindingSource,
)

internal object CardAuthOrigins {
    const val HOST = "mb-xyk.upc.edu.cn"
    const val BASE = "https://$HOST"
    const val ENTRY = "$BASE/berserker-auth/cas/redirect/wislearn?targetUrl=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fplat%2F%3Fname%3DloginTransit"

    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.rawUserInfo != null) return@runCatching false
        when {
            uri.scheme == "https" && uri.port in setOf(-1, 443) -> uri.host in setOf(HOST, "cas.upc.edu.cn", "i.upc.edu.cn")
            else -> false
        }
    }.getOrDefault(false)

    fun tokenPage(url: String): Boolean = runCatching {
        val uri = URI(url)
        val path = uri.rawPath.orEmpty()
        uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(HOST, ignoreCase = true) &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
            !path.contains("%2f", ignoreCase = true) && !path.contains("%5c", ignoreCase = true) &&
            path.split('/').none { it == "." || it == ".." } &&
            uri.path.orEmpty().split('/').none { it == "." || it == ".." } &&
            (path == "/plat" || path.startsWith("/plat/") || path.startsWith("/campus-card/"))
    }.getOrDefault(false)

}
