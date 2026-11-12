package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.account.api.blocksPersonalSessions
import java.io.IOException
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Implemented by the host's restricted auth adapter, never by a password-reading feature. */
fun interface CardHistoryAuthenticator {
    suspend fun authenticate(identity: SchoolStudentIdentity): String
}

enum class CardHistoryFailure { ACCOUNT_UNAVAILABLE, ACCOUNT_CHANGED, LOGIN_REQUIRED, NETWORK, PROTOCOL, AUTHENTICATION, INTERACTION_REQUIRED, INVALID_CREDENTIALS }
class CardHistoryException(val reason: CardHistoryFailure) : IOException(reason.name)
data class CardHistorySummary(val incomeFen: Long, val expenseFen: Long)
data class BoundCardHistoryPage(val owner: SchoolStudentIdentity, val month: YearMonth?, val current: Int, val data: CardHistoryPage)

/** Only fixed HTTPS, read-only endpoints; a session is checked against the current account generation. */
class CardHistoryClient(
    private val account: SchoolAccountStudentIdentityReader,
    private val authenticator: CardHistoryAuthenticator,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
) {
    private class Session(val owner: SchoolStudentIdentity, val token: String)
    @Volatile private var session: Session? = null
    private val epoch = AtomicLong()
    val studentIdentity get() = account.studentIdentity
    val accountState get() = account.state
    fun hasSession(): Boolean = session?.let { isCurrent(it.owner) } == true

    fun clearSession() { epoch.incrementAndGet(); session = null }
    private fun identity(): SchoolStudentIdentity = account.studentIdentity.value
        ?.takeUnless { account.state.value.blocksPersonalSessions }
        ?: throw CardHistoryException(CardHistoryFailure.ACCOUNT_UNAVAILABLE)
    fun isCurrent(owner: SchoolStudentIdentity): Boolean =
        !account.state.value.blocksPersonalSessions && account.studentIdentity.value == owner

    suspend fun login(): Unit = withContext(Dispatchers.IO) {
        val owner = identity()
        val generation = epoch.get()
        val token = authenticator.authenticate(owner)
        val candidate = Session(owner, token)
        CardHistoryProtocol.verifyIdentity(get("/berserker-base/user?synAccessSource=h5", candidate), owner.studentId)
        if (!isCurrent(owner) || epoch.get() != generation) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
        session = candidate
        if (!isCurrent(owner) || epoch.get() != generation) {
            session = null
            throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
        }
    }

    suspend fun page(month: YearMonth? = null, current: Int = 1, size: Int = 50): BoundCardHistoryPage = withContext(Dispatchers.IO) {
        val active = requireSession()
        val generation = epoch.get()
        val body = get("/berserker-search/search/personal/turnover?" + query(month, current, size), active)
        val actual = (CardHistoryProtocol.data(body)["current"] as? JsonPrimitive)?.intOrNull
        if (actual != null && actual != current) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        val parsed = CardHistoryProtocol.parseRows(body)
        checkCurrent(active, generation)
        BoundCardHistoryPage(active.owner, month, current, parsed)
    }

    /** Explicit user action only. Failure never returns a partial list as a complete history. */
    suspend fun all(month: YearMonth? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): BoundCardHistoryPage {
        val owner = requireSession().owner
        val generation = epoch.get()
        val records = linkedMapOf<String, CardHistoryRow>()
        var current = 1
        do {
            val result = page(month, current)
            if (result.owner != owner || epoch.get() != generation) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
            val previous = records.size
            result.data.rows.forEach { records[it.id] = it }
            if (current > 1 && records.size == previous) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
            onProgress(records.size, result.data.total)
            if (current >= result.data.pages) {
                if (!isCurrent(owner) || epoch.get() != generation) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
                if (records.size != result.data.total) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
                return result.copy(data = result.data.copy(rows = records.values.toList()))
            }
            if (result.data.rows.isEmpty()) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
            current++
            delay(250)
        } while (true)
    }

    suspend fun summary(month: YearMonth? = null): CardHistorySummary = withContext(Dispatchers.IO) {
        val active = requireSession()
        val generation = epoch.get()
        val data = CardHistoryProtocol.data(get("/berserker-search/statistics/turnover/count?" + query(month, 1, 50), active))
        fun amount(key: String): Long = (data[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            ?: throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        val result = CardHistorySummary(amount("income"), amount("expenses"))
        checkCurrent(active, generation)
        result
    }

    private fun requireSession(): Session {
        val active = session ?: throw CardHistoryException(CardHistoryFailure.LOGIN_REQUIRED)
        if (!isCurrent(active.owner)) { clearSession(); throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED) }
        return active
    }
    private fun checkCurrent(active: Session, generation: Long) {
        if (!isCurrent(active.owner) || epoch.get() != generation) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
    }
    private fun get(path: String, active: Session): String {
        val token = active.token
        if (token.isBlank() || token.length > 16384 || token.any { it <= ' ' || it == '\u007f' })
            throw CardHistoryException(CardHistoryFailure.AUTHENTICATION)
        val request = Request.Builder().url(CardHistoryProtocol.BASE + path)
            .header("Accept", "application/json").header("synAccessSource", "h5")
            .header("synjones-auth", "bearer $token").build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    if (session === active) clearSession()
                    throw CardHistoryException(CardHistoryFailure.LOGIN_REQUIRED)
                }
                if (response.code != 200) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
                val body = response.body ?: throw CardHistoryException(CardHistoryFailure.PROTOCOL)
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 2 * 1024 * 1024) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
                        output.write(buffer, 0, count)
                    }
                }
                val text = output.toString("UTF-8")
                val code = runCatching {
                    (Json.parseToJsonElement(text).jsonObject["code"] as? JsonPrimitive)?.intOrNull
                }.getOrNull()
                if (code == 401 || code == 403) {
                    if (session === active) clearSession()
                    throw CardHistoryException(CardHistoryFailure.LOGIN_REQUIRED)
                }
                return text
            }
        } catch (known: CardHistoryException) { throw known }
        catch (_: IOException) { throw CardHistoryException(CardHistoryFailure.NETWORK) }
    }

    companion object {
        internal fun query(month: YearMonth?, current: Int, size: Int): String {
            require(current > 0 && size in 1..100)
            val fields = linkedMapOf("size" to "$size", "current" to "$current", "synAccessSource" to "h5")
            if (month != null) { fields["timeFrom"] = month.atDay(1).toString(); fields["timeTo"] = month.atEndOfMonth().toString() }
            return fields.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        }
    }
}
