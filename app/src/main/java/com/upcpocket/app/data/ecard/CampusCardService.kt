package com.upcpocket.app.data.ecard

import com.upcpocket.app.data.prefs.SchoolSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

data class CampusCardBalance(
    val account: String,
    val cards: List<CampusCardProtocol.Card>,
    val basis: CampusCardProtocol.Basis,
) {
    /** 多张卡时给一个总数，单张卡直接用卡余额；口径来自服务端配置，不自己相加。 */
    val totalCents: Long get() = cards.sumOf { it.cents }
}

sealed interface CampusCardOutcome {
    data class Success(val balance: CampusCardBalance) : CampusCardOutcome
    data object NeedsLogin : CampusCardOutcome

    /** 网络或学校服务暂时不可用：**不是**会话失效，不能清令牌、不能提示“重新登录”。 */
    data class Unavailable(val message: String) : CampusCardOutcome
}

/** 接管令牌的结果：必须区分“令牌无效”和“这次连不上”。 */
sealed interface CampusCardAdoptResult {
    data class Accepted(val balance: CampusCardBalance) : CampusCardAdoptResult
    data object Rejected : CampusCardAdoptResult
    data class Unavailable(val message: String) : CampusCardAdoptResult
}

/**
 * 校园卡余额业务层。
 *
 * 职责边界：
 *  - 令牌从哪里来：用户在 App 内打开学校官方登录页（[CampusCardProtocol.LOGIN_PATH]），
 *    登录成功后学校平台把 `synjones-auth` 令牌带回 `/plat/`；这里只接管**App 自己**
 *    合法拿到的这一个令牌；
 *  - 重启复用：开启“记住登录”时用 [SchoolSessionStore] 的 Keystore 密文；
 *    恢复的令牌先按“待验证”处理，第一次查询确认有效后才显示已登录；
 *  - 换号隔离：令牌验证出账号后，如果与上次记录不同，先丢弃旧缓存，不能串号；
 *  - 过期：服务端 401/403 时清掉该业务令牌并回到“需要登录”，**不自动重登**；
 *  - 网络失败：保留令牌与上次成功的值，只报“暂时读不到”——把断网说成“登录失效”
 *    会逼用户白重登一次（真机复现过）。
 */
class CampusCardService(
    private val store: SchoolSessionStore,
    private val client: CampusCardClient = CampusCardClient(),
) {
    private val _session = MutableStateFlow<CampusCardProtocol.Result?>(null)

    private var token: String? = null

    /** 当前内存中的令牌（只读快照，供界面判断“是否有会话”，不用于展示）。 */
    fun hasSession(): Boolean = !token.isNullOrBlank()

    /** 最近一次成功读取的结果（恢复会话时已经验证过，避免立刻重复请求）。 */
    fun snapshot(): CampusCardBalance? = _session.value?.toBalance()

    /**
     * 接管登录回调里的令牌，并立刻做一次只读校验。
     *
     * 只有校验成功才落盘；校验失败时**不覆盖**已有令牌（旧的可能仍有效）。
     */
    suspend fun adoptToken(rawToken: String, remember: Boolean): CampusCardAdoptResult =
        withContext(Dispatchers.IO) {
            val value = rawToken.trim()
            if (value.isEmpty()) return@withContext CampusCardAdoptResult.Rejected
            val previous = store.loadCampusCard()
            val fetched = try {
                client.fetch(value)
            } catch (_: CampusCardSessionExpiredException) {
                return@withContext CampusCardAdoptResult.Rejected
            } catch (e: Exception) {
                return@withContext CampusCardAdoptResult.Unavailable(
                    e.message ?: "这次没能连上学校校园卡服务，请检查网络后重试",
                )
            }
            val account = fetched.cards.firstOrNull()?.account.orEmpty()
            // 换号：旧账号的令牌与缓存都不再适用。
            if (previous != null && account.isNotEmpty() && previous.account.isNotEmpty() &&
                previous.account != account
            ) {
                store.clearCampusCard()
            }
            token = value
            _session.value = fetched
            if (remember) store.saveCampusCard(value, account)
            CampusCardAdoptResult.Accepted(fetched.toBalance())
        }

    /**
     * 恢复上次保存的令牌并验证。
     *
     * @return true 表示恢复了仍有效的会话。网络失败同样返回 false（这次没法确认），
     *         但**不删密文** —— 让下次有网时还能再试，界面据 [lastFailure] 说明原因。
     */
    suspend fun restore(accountHint: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!token.isNullOrBlank()) return@withContext true
        val saved = store.loadCampusCard() ?: run {
            lastFailure = null
            return@withContext false
        }
        val fetched = try {
            client.fetch(saved.token)
        } catch (_: CampusCardSessionExpiredException) {
            // 明确被服务端拒绝：清掉密文，让用户重登。
            store.clearCampusCard()
            lastFailure = null
            return@withContext false
        } catch (e: Exception) {
            lastFailure = e.message ?: "这次没能连上学校校园卡服务"
            return@withContext false
        }
        val account = fetched.cards.firstOrNull()?.account.orEmpty()
        if (accountHint != null && account.isNotEmpty() && accountHint != account) {
            store.clearCampusCard()
            lastFailure = null
            return@withContext false
        }
        token = saved.token
        _session.value = fetched
        lastFailure = null
        // 服务端可能返回新的账号标识，刷新一次密文里的账号。
        if (account.isNotEmpty() && account != saved.account) store.saveCampusCard(saved.token, account)
        true
    }

    /** 上次恢复失败的原因；`null` 表示没有失败或失败已排除。 */
    var lastFailure: String? = null
        private set

    suspend fun refresh(): CampusCardOutcome = withContext(Dispatchers.IO) {
        val value = token ?: return@withContext CampusCardOutcome.NeedsLogin
        try {
            val fetched = client.fetch(value)
            _session.value = fetched
            val account = fetched.cards.firstOrNull()?.account.orEmpty()
            if (account.isNotEmpty()) store.saveCampusCard(value, account)
            CampusCardOutcome.Success(fetched.toBalance())
        } catch (_: CampusCardSessionExpiredException) {
            signOut()
            CampusCardOutcome.NeedsLogin
        } catch (e: Exception) {
            // 注意：这里**不清令牌**。断网/学校维护不等于会话失效。
            CampusCardOutcome.Unavailable(e.message ?: "这次没能连上学校校园卡服务，请稍后重试")
        }
    }

    fun rememberedAccount(): String = store.loadCampusCard()?.account.orEmpty()

    /** 只退出校园卡这一项，不动图书馆 token 与课表 Cookie。 */
    fun signOut() {
        token = null
        _session.value = null
        lastFailure = null
        store.clearCampusCard()
    }

    private fun CampusCardProtocol.Result.toBalance() = CampusCardBalance(
        account = cards.firstOrNull()?.account.orEmpty(),
        cards = cards,
        basis = basis,
    )
}
