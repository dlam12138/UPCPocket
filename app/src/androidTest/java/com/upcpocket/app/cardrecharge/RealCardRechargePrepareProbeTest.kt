package com.upcpocket.app.cardrecharge

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.upcauth.UpcCardAuthResult
import com.upcpocket.app.upcauth.UpcCardAuthenticator
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly authorized, one-use credential fixture. No account save, order, pay, UI or raw logging. */
@RunWith(AndroidJUnit4::class)
@Ignore("Native card recharge was removed; this legacy real-school probe is no longer applicable.")
class RealCardRechargePrepareProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val report = JSONObject().put("phase", "GUARD").put("result", "ABORTED")
        .put("failure", "NONE").put("auth_result", "NOT_RUN")
        .put("auth_request_count", 0).put("password_post_count", 0).put("oauth_post_count", 0)
        .put("prepare_request_count", 0).put("blocked_request_count", 0).put("order_pay_network_requests", 0)
        .put("fixture_consumed", false).put("account_saved", false)
    private class ProbeAbort(val code: String) : RuntimeException(null, null, false, false)
    private fun requireProbe(value: Boolean, code: String) { if (!value) throw ProbeAbort(code) }
    private fun call(method: Method, target: Any?, vararg args: Any?): Any? = try {
        method.invoke(target, *args)
    } catch (error: InvocationTargetException) { throw error.targetException }
    private fun getter(value: Any, name: String) = call(value.javaClass.getMethod(name), value)
    private fun increment(key: String): Int = (report.getInt(key) + 1).also { report.put(key, it) }
    private fun blocked(): Nothing { increment("blocked_request_count"); throw ProbeAbort("NETWORK_POLICY") }
    private fun safeOrigin(uri: URI) = uri.scheme == "https" && uri.port in setOf(-1, 443) &&
        uri.rawUserInfo == null && uri.rawFragment == null
    private fun noArg(className: String): Any = Class.forName(className).getDeclaredConstructor().newInstance()

    private fun guardedAuthTransport(): Any {
        val type = Class.forName("com.upcpocket.app.upcauth.UpcAuthTransport")
        val delegate = noArg("com.upcpocket.app.upcauth.OkHttpUpcAuthTransport")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            if (method.name != "execute") throw ProbeAbort("REFLECTION")
            val request = arguments!![0]
            val uri = URI(getter(request, "getUrl") as String)
            val verb = getter(request, "getMethod") as String
            if (!safeOrigin(uri) || report.getInt("auth_request_count") >= 14) blocked()
            val allowed = when (verb) {
                "GET" -> getter(request, "getBody") == null && when (uri.host) {
                    "cas.upc.edu.cn" -> uri.path in setOf("/cas/login", "/cas/captcha")
                    "mb-xyk.upc.edu.cn" -> uri.path in setOf(
                        "/berserker-auth/cas/redirect/wislearn", "/berserker-auth/cas/login/wislearn", "/plat", "/plat/", "/campus-card/",
                    )
                    else -> false
                }
                "POST" -> when {
                    uri.host == "cas.upc.edu.cn" && uri.path == "/cas/login" -> increment("password_post_count") <= 1
                    uri.host == "mb-xyk.upc.edu.cn" && uri.path == "/berserker-auth/oauth/token" -> increment("oauth_post_count") <= 1
                    else -> false
                }
                else -> false
            }
            if (!allowed) blocked()
            increment("auth_request_count")
            call(method, delegate, *arguments)
        }
    }

    private class Captured { var item: String? = null; var front: String? = null }
    private fun guardedPrepareTransport(captured: Captured): Any {
        val type = Class.forName("com.upcpocket.app.data.cardrecharge.RechargeTransport")
        val delegate = noArg("com.upcpocket.app.data.cardrecharge.RechargeHttpTransport")
        val seen = mutableSetOf<String>()
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            if (method.name != "request") throw ProbeAbort("REFLECTION")
            val uri = URI(arguments!![0] as String)
            if (!safeOrigin(uri) || uri.host != "mb-xyk.upc.edu.cn" || arguments[1] != null) blocked()
            val key = when {
                uri.path == "/berserker-base/user" && uri.rawQuery == "synAccessSource=h5" -> "USER"
                uri.path == "/berserker-app/frontInfo" && uri.rawQuery == "synAccessSource=h5" -> "FRONT"
                uri.path == "/berserker-app/ykt/tsm/queryCard" && uri.rawQuery == "scene=recharge" -> "CARD"
                uri.path == "/charge/feeitem/singleFeeitem" && uri.rawQuery?.matches(Regex("feeitemid=[0-9]{1,10}")) == true -> "FEE"
                uri.path == "/campus-card/js/chunk-1186b329.e153ac09.js" && uri.rawQuery == null -> "SIGNER"
                else -> blocked()
            }
            if ((key == "SIGNER") != (arguments[2] == null) || !seen.add(key) || seen.size > 5) blocked()
            increment("prepare_request_count")
            val response = call(method, delegate, *arguments)!!
            // These two responses remain only in this test process; never serialize them as evidence.
            if (key == "FEE") captured.item = getter(response, "getBody") as String
            if (key == "FRONT") captured.front = getter(response, "getBody") as String
            response
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    @Test fun readOnlyNativePrepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upc_recharge_probe_opt_in") == "AUTHORIZED_PREPARE_ONLY")
        var fixtureBytes: ByteArray? = null
        var password: CharArray? = null
        val captured = Captured()
        var completed = false
        try {
            val args = InstrumentationRegistry.getArguments()
            requireProbe(args.getString("upc_recharge_probe_opt_in") == "AUTHORIZED_PREPARE_ONLY", "OPT_IN_REQUIRED")
            val runId = args.getString("upc_recharge_probe_run_id").orEmpty()
            requireProbe(runId.matches(Regex("[a-f0-9]{16}")), "RUN_ID_INVALID")
            val fixture = File(context.filesDir, "card-recharge-prepare-$runId.json")
            val marker = File(context.filesDir, "card-recharge-prepare-$runId.once")
            requireProbe(!marker.exists() && marker.createNewFile(), "ALREADY_USED")
            report.put("phase", "FIXTURE")
            requireProbe(fixture.canonicalFile.parentFile == context.filesDir.canonicalFile && fixture.isFile && fixture.length() in 1L..4096L, "FIXTURE_INVALID")
            fixtureBytes = fixture.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    requireProbe(output.size() + count <= 4096, "FIXTURE_INVALID")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            // The unique run path and noclobber host seed establish ownership. Delete before parsing.
            requireProbe(fixture.delete() && !fixture.exists(), "FIXTURE_DELETE_FAILED")
            report.put("fixture_consumed", true)
            val json = JSONObject(String(fixtureBytes, Charsets.UTF_8))
            requireProbe(json.keys().asSequence().toSet() == setOf("run_id", "account", "password") && json.optString("run_id") == runId, "FIXTURE_INVALID")
            val loginName = json.opt("account") as? String ?: throw ProbeAbort("FIXTURE_INVALID")
            val passwordText = json.opt("password") as? String ?: throw ProbeAbort("FIXTURE_INVALID")
            requireProbe(loginName.matches(Regex("[0-9]{8,16}")) && passwordText.length in 1..512, "FIXTURE_INVALID")
            password = passwordText.toCharArray()
            json.remove("account"); json.remove("password")
            val owner = SchoolStudentIdentity(SchoolAccountId("ephemeral_recharge_probe"), loginName, AccountGeneration(1))
            var credentialUses = 0
            val credentials = object : SchoolCredentialAccess {
                override suspend fun <T> withLoginCredential(block: suspend (SchoolLoginCredential) -> T): SchoolCredentialUseResult<T> {
                    requireProbe(++credentialUses == 1, "CREDENTIAL_REUSE")
                    val credential = SchoolLoginCredential(owner.accountId, owner.credentialGeneration, loginName, password!!.copyOf())
                    return try { SchoolCredentialUseResult.Success(block(credential)) } finally { credential.close() }
                }
            }
            report.put("phase", "AUTH")
            val authType = Class.forName("com.upcpocket.app.upcauth.UpcAuthTransport")
            val authenticator = UpcCardAuthenticator::class.java.getDeclaredConstructor(SchoolCredentialAccess::class.java, authType)
                .newInstance(credentials, guardedAuthTransport())
            val auth = authenticator.authenticate(owner.accountId, owner.credentialGeneration.value)
            report.put("auth_result", if (auth is UpcCardAuthResult.Success) "SUCCESS" else (auth as UpcCardAuthResult.Failure).reason.name)
            requireProbe(auth is UpcCardAuthResult.Success, "AUTH_FAILED")
            report.put("phase", "PREPARE")
            val sessionType = Class.forName("com.upcpocket.app.data.cardhistory.CardRechargeSession")
            val session = sessionType.declaredConstructors.first { it.parameterCount == 4 }
                .newInstance(owner, (auth as UpcCardAuthResult.Success).token, { true }, {})
            val transportType = Class.forName("com.upcpocket.app.data.cardrecharge.RechargeTransport")
            val apiType = Class.forName("com.upcpocket.app.data.cardrecharge.CardRechargeApi")
            val api = apiType.getDeclaredConstructor(transportType, kotlin.jvm.functions.Function0::class.java)
                .newInstance(guardedPrepareTransport(captured), { true })
            val prepared = try {
                call(apiType.getMethod("prepare", sessionType), api, session)
                report.put("prepare_stage", "PASS")
                true
            } catch (error: Throwable) {
                report.put("prepare_stage", nativeFailure(error))
                false
            }
            report.put("phase", "COMPARE")
            compareCaptured(captured)
            requireProbe(report.getInt("blocked_request_count") == 0, "NETWORK_POLICY")
            report.put("result", if (prepared) "ACCEPTED" else "REJECTED")
            report.put("phase", "COMPLETE")
            completed = true
        } catch (abort: ProbeAbort) { report.put("failure", abort.code) }
        catch (_: Throwable) { report.put("failure", "INTERNAL") }
        finally {
            password?.fill('\u0000'); fixtureBytes?.fill(0)
            captured.item = null; captured.front = null
            instrumentation.sendStatus(0, Bundle().apply { putString("upc_probe_report", report.toString()) })
        }
        if (!completed) throw AssertionError("RECHARGE_PREPARE_PROBE_ABORTED")
    }

    private fun nativeFailure(error: Throwable): String {
        if (error is ProbeAbort) return error.code
        val stage = runCatching { getter(error, "getStage") as? String }.getOrNull()
        return when (stage) {
            "P-FEE-CONFIG" -> "P_FEE_CONFIG"
            "P-SIGN-FORMAT" -> "P_SIGN_FORMAT"
            "P-LOGIN" -> "P_LOGIN"
            "P-ACCOUNT" -> "P_ACCOUNT"
            "P-CARD-BLOCKED" -> "P_CARD_BLOCKED"
            "P-NETWORK" -> "P_NETWORK"
            else -> "NATIVE_REJECTED"
        }
    }

    private fun compareCaptured(captured: Captured) {
        val raw = captured.item ?: return
        val protocolType = Class.forName("com.upcpocket.app.data.cardrecharge.CardRechargeProtocol")
        val protocol = protocolType.getField("INSTANCE").get(null)
        val parse = protocolType.getMethod("json", String::class.java)
        val root = call(parse, protocol, raw) as Map<*, *>
        val fee = root["feeitem"]
        report.put("feeitem_kind", kind(fee))
        if (fee !is Map<*, *>) return
        val shapes = JSONObject()
        for (field in listOf("retain_money", "maxmoney", "flag", "layout")) shapes.put(field, shape(fee[field]))
        val frontRoot = captured.front?.let { call(parse, protocol, it) as? Map<*, *> }
        val front = (frontRoot?.get("data") as? Map<*, *>)?.get("getFrontConfig")
        val settings = if (kind(front) == "STRING") primitiveContent(front)?.let {
            runCatching { call(parse, protocol, it) as? Map<*, *> }.getOrNull()
        } else front as? Map<*, *>
        shapes.put("front_recharge_maximum", shape(settings?.get("rechargeMaxAmount")))
        report.put("field_shapes", shapes)
        val rules = protocolType.methods.single { it.name == "rules" }
        val currentOk = try { call(rules, protocol, fee, settings ?: call(parse, protocol, "{}")); true }
        catch (_: Throwable) { false }
        // Frozen native validator from 2a80e29; the same in-memory item is compared after a future fix.
        val legacyFailure = try { legacyRules(fee); "NONE" }
        catch (error: ProbeAbort) { error.code }
        catch (_: Throwable) { "INTERNAL" }
        report.put("current_rules_ok", currentOk).put("legacy_rules_ok", legacyFailure == "NONE")
            .put("legacy_failure", legacyFailure)
    }

    private fun primitiveContent(value: Any?): String? {
        if (value == null || value.javaClass.name == "kotlinx.serialization.json.JsonNull") return null
        return runCatching { getter(value, "getContent") as? String }.getOrNull()
    }
    private fun kind(value: Any?): String = when {
        value == null -> "ABSENT"
        value.javaClass.name == "kotlinx.serialization.json.JsonNull" -> "NULL"
        value is Map<*, *> -> "OBJECT"
        value is List<*> -> "ARRAY"
        runCatching { getter(value, "isString") == true }.getOrDefault(false) -> "STRING"
        primitiveContent(value) in setOf("true", "false") -> "BOOLEAN"
        primitiveContent(value) != null -> "NUMBER"
        else -> "OTHER"
    }
    private fun shape(value: Any?): JSONObject {
        // Native JsonPrimitive.content preserves lexical trailing zeros; JSONObject would not.
        val text = primitiveContent(value).orEmpty()
        val decimal = text.takeIf { it.length in 1..128 }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        return JSONObject().put("kind", kind(value)).put("length", text.length)
            .put("native_decimal_places", decimal?.scale()?.coerceIn(0, 32) ?: -1)
            .put("native_strict_decimal", text.matches(Regex("(?:0|[1-9][0-9]{0,10})(?:\\.[0-9]{1,2})?")))
            .put("positive", decimal?.let { it > BigDecimal.ZERO } ?: false)
            .put("zero", decimal?.let { it.compareTo(BigDecimal.ZERO) == 0 } ?: false)
    }
    private fun legacyRules(item: Map<*, *>) {
        fun field(key: String) = primitiveContent(item[key]).orEmpty()
        fun bound(key: String, failure: String): BigDecimal {
            val raw = field(key)
            requireProbe(raw.matches(Regex("(?:0|[1-9][0-9]{0,10})(?:\\.[0-9]{1,2})?")), failure)
            return BigDecimal(raw)
        }
        fun amount(raw: String): BigDecimal = boundAmount(raw).also { require(it > BigDecimal.ZERO) }
        val minimum = bound("retain_money", "MIN_FORMAT"); val maximum = bound("maxmoney", "MAX_FORMAT")
        requireProbe(minimum >= BigDecimal.ZERO && maximum > BigDecimal.ZERO && minimum <= maximum, "BOUND_RELATION")
        val flag = field("flag"); requireProbe(flag.length >= 8 && flag[7] in "012", "FLAG")
        val presets = field("layout").split(',').filter { it.isNotBlank() }.map {
            try { amount(it.trim()) } catch (_: Throwable) { throw ProbeAbort("LAYOUT") }
        }
            .filter { it >= minimum && it <= maximum && (flag[7] != '2' || it.stripTrailingZeros().scale() <= 0) }
            .distinctBy { it.stripTrailingZeros() }
        requireProbe(presets.size <= 30, "LAYOUT")
        requireProbe(flag[7] != '1' || presets.isNotEmpty(), "PRESET_EMPTY")
    }
    private fun boundAmount(raw: String): BigDecimal {
        require(raw.matches(Regex("(?:0|[1-9][0-9]{0,10})(?:\\.[0-9]{1,2})?")))
        return BigDecimal(raw)
    }
}
