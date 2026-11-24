package com.upcpocket.app.grades

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.upcauth.GradesAuthenticator
import com.upcpocket.app.upcauth.UpcAuthFailure
import com.upcpocket.app.upcauth.UpcAuthResult
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 成绩只读业务服务。
 *
 * 边界：
 *  - 账号、凭据代次与业务 epoch 在每次发布/落盘前的同一提交锁内复核；
 *  - 网络请求不持有提交锁，切换账号或清理后迟到结果不发布、不落盘；
 *  - 每次同步最多做一次正常 CAS 认证；验证码/二次验证直接停止，不重试；
 *  - 按索引顺序逐学期查询；普通学期失败保留该学期上一份完整结果并记录范围，
 *    认证失效立即停止后续查询；
 *  - 只把逐学期完整结果合并后写入本机加密快照，不保存 Cookie、密码或原始响应。
 *
 * 学校响应没有独立的学生标识或全校汇总，本服务不宣称完成学校侧身份比对；归属
 * 只依靠当前账号的内部 reference 与独立 CAS 会话。
 */
class GradesService internal constructor(
    private val account: SchoolAccountReferenceReader,
    private val authenticator: GradesAuthenticator,
    private val files: GradesFiles,
    private val remote: GradesRemote,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 测试用提交屏障：仅在短提交锁内、最终复核之后调用。 */
    private val commitBarrier: suspend (String) -> Unit = {},
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor(
        account: SchoolAccountReferenceReader,
        authenticator: GradesAuthenticator,
        noBackupDir: File,
    ) : this(
        account = account,
        authenticator = authenticator,
        files = GradesFiles(noBackupDir),
        remote = GradesApi(),
    )

    private val _state = MutableStateFlow(GradesUiState())
    val state: StateFlow<GradesUiState> = _state.asStateFlow()

    private val commitMutex = Mutex()
    private val operationMutex = Mutex()
    private val businessEpoch = AtomicLong(0)
    private var currentBinding: SchoolAccountReference? = null

    init {
        val initial = bindingOrNull()
        currentBinding = initial
        _state.value = if (initial != null) {
            GradesUiState(owner = initial, connection = GradesConnection.IDLE)
        } else {
            unavailableState(account.state.value)
        }
        scope.launch {
            combine(account.state, account.accountReference) { state, reference ->
                state to reference
            }.collect {
                commitMutex.withLock { applyAccountStateLocked() }
            }
        }
    }

    // ── public operations ─────────────────────────────────────────────────

    /**
     * 读取本机完整快照，不访问学校。
     *
     * 同一 accountId 在改密后仍可读取旧快照；账号切换时旧账号快照不会发布。
     */
    suspend fun restore() {
        val binding = bindingOrNull() ?: run {
            publishAccountState()
            return
        }
        if (!operationMutex.tryLock()) return
        try {
            commitMutex.withLock {
                if (!isBindingCurrent(binding)) return@withLock
                val current = _state.value
                if (current.owner == binding && current.termResults.isNotEmpty()) {
                    // 已有本进程内更新结果时不要被较旧的缓存覆盖。restore 持有
                    // operationMutex，此时不存在仍在运行的 sync；若仍是 CONNECTING，
                    // 它只能是取消清理失败后残留的状态，必须在这里解除。
                    if (current.connection == GradesConnection.CONNECTING) {
                        _state.value = current.copy(
                            connection = if (current.lastSyncedAtEpochMillis != null) {
                                GradesConnection.READY
                            } else {
                                GradesConnection.IDLE
                            },
                        )
                    }
                    return@withLock
                }
                // 读取期间持有提交锁：首次账号引用到达时，observer 不能在读完缓存
                // 与发布之间把同一账号的状态清空；账号切换时则会在锁外等待后重新
                // 清理，不会发布旧快照。
                val loaded = withContext(ioDispatcher) { files.loadResult() }
                if (!isBindingCurrent(binding)) return@withLock
                currentBinding = binding
                commitBarrier("restore")
                if (!isBindingCurrent(binding)) return@withLock
                // 提交锁内只有本协程能发布状态；沿用进入锁时的 current，
                // 不再重复声明同名局部变量。
                val carry = current.takeIf { it.owner == binding }
                val carryFailure = carry?.lastFailure
                    ?.takeIf { it != GradesFailure.STORAGE_ERROR }
                val carryUnsaved = carry?.unsavedChanges == true
                _state.value = when (loaded) {
                    GradesLoadResult.Missing ->
                        if (carry != null && carryFailure != null) {
                            // 本进程内全部学期失败且从未落盘时，重新进入页面不能把
                            // 失败范围和原因清成“尚未同步”。
                            carry.copy(
                                owner = binding,
                                connection = GradesConnection.PARTIAL,
                                lastFailure = carryFailure,
                                unsavedChanges = carryUnsaved,
                            )
                        } else {
                            GradesUiState(
                                owner = binding,
                                connection = GradesConnection.IDLE,
                                unsavedChanges = carryUnsaved,
                            )
                        }
                    GradesLoadResult.Unreadable ->
                        GradesUiState(
                            owner = binding,
                            connection = GradesConnection.PROTOCOL_ERROR,
                            lastFailure = GradesFailure.STORAGE_ERROR,
                            unsavedChanges = carryUnsaved,
                        )
                    is GradesLoadResult.Loaded -> {
                        val cached = loaded.snapshot.takeIf {
                            it.accountId == binding.accountId.value
                        }
                        if (cached != null) {
                            stateFromSnapshot(binding, cached)
                        } else {
                            // 合法快照属于其他账号：不能显示，也不能当成损坏。
                            GradesUiState(
                                owner = binding,
                                connection = GradesConnection.IDLE,
                            )
                        }
                    }
                }
            }
        } finally {
            operationMutex.unlock()
        }
    }

    /**
     * 用户主动同步：索引一次，随后按索引顺序逐学期查询。
     *
     * 返回时 [state] 已发布最终结果；并发调用会直接忽略，避免重复提交密码。
     */
    suspend fun sync() {
        val binding = bindingOrNull() ?: run {
            publishAccountState()
            return
        }
        if (!operationMutex.tryLock()) return
        var business = 0L
        var prior = PriorData(emptyList(), emptyMap(), null)
        var previousState: GradesUiState? = null
        try {
            val started = commitMutex.withLock {
                if (!isBindingCurrent(binding)) return@withLock false
                // 与 restore 一样在提交锁内读本机快照，避免首次账号引用 observer
                // 在读取和发布 CONNECTING 之间插入并递增 epoch。
                val loaded = withContext(ioDispatcher) { files.loadResult() }
                if (!isBindingCurrent(binding)) return@withLock false
                currentBinding = binding
                business = businessEpoch.get()
                val snapshot = (loaded as? GradesLoadResult.Loaded)?.snapshot
                prior = priorDataLocked(binding, snapshot)
                previousState = _state.value
                commitBarrier("sync-start")
                if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                    return@withLock false
                }
                val carry = previousState?.takeIf { it.owner == binding }
                _state.value = GradesUiState(
                    owner = binding,
                    connection = GradesConnection.CONNECTING,
                    terms = prior.terms,
                    termResults = prior.resultsInOrder(),
                    lastSyncedAtEpochMillis = prior.lastSyncedAtEpochMillis,
                    // 重试期间仍保留上次查询的失败范围、原因和未落盘提示；
                    // 结果字段由下面的本次同步重新覆盖。
                    lastSyncRetainedTerms = carry?.lastSyncRetainedTerms.orEmpty(),
                    lastSyncFailedTerms = carry?.lastSyncFailedTerms.orEmpty(),
                    lastFailure = carry?.lastFailure,
                    unsavedChanges = carry?.unsavedChanges == true,
                )
                true
            }
            if (!started) return

            val auth = authenticator.authenticate(
                binding.accountId,
                binding.credentialGeneration.value,
            )
            if (!isCurrent(binding, business)) return
            when (auth) {
                is UpcAuthResult.Failure -> {
                    publishFailure(
                        binding = binding,
                        business = business,
                        connection = auth.reason.toConnection(),
                        failure = auth.reason.toGradesFailure(),
                        prior = prior,
                    )
                    return
                }
                is UpcAuthResult.Success -> Unit
            }
            val cookies = (auth as UpcAuthResult.Success).cookies
            if (cookies.isEmpty()) {
                publishFailure(
                    binding = binding,
                    business = business,
                    connection = GradesConnection.AUTH_FAILED,
                    failure = GradesFailure.AUTH_EXPIRED,
                    prior = prior,
                )
                return
            }

            val index = try {
                remote.getIndex(cookies)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GradesRemoteException) {
                publishRemoteFailure(binding, business, failure.failure, prior)
                return
            } catch (_: Exception) {
                publishRemoteFailure(
                    binding,
                    business,
                    GradesRemoteFailure.PROTOCOL,
                    prior,
                )
                return
            }
            if (!isCurrent(binding, business)) return

            val indexedPrior = prior.forTerms(index.terms)
            val appliedIndex = commitMutex.withLock {
                if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                    return@withLock false
                }
                currentBinding = binding
                commitBarrier("sync-index")
                if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                    return@withLock false
                }
                val carry = _state.value
                _state.value = GradesUiState(
                    owner = binding,
                    connection = GradesConnection.CONNECTING,
                    terms = index.terms,
                    termResults = indexedPrior.resultsInOrder(),
                    lastSyncedAtEpochMillis = prior.lastSyncedAtEpochMillis,
                    lastSyncRetainedTerms = carry.lastSyncRetainedTerms,
                    lastSyncFailedTerms = carry.lastSyncFailedTerms,
                    lastFailure = carry.lastFailure,
                    unsavedChanges = carry.unsavedChanges,
                )
                true
            }
            if (!appliedIndex) return

            val results = LinkedHashMap<String, GradesTermResult>(indexedPrior.results)
            val retained = mutableListOf<GradesTerm>()
            val failed = mutableListOf<GradesTerm>()
            var updated = 0
            var ordinaryFailure: GradesFailure? = null
            var mixedOrdinaryFailures = false
            var terminalFailure: GradesFailure? = null
            var terminalConnection: GradesConnection? = null

            fun recordOrdinaryFailure(term: GradesTerm, failure: GradesFailure) {
                if (ordinaryFailure == null) {
                    ordinaryFailure = failure
                } else if (ordinaryFailure != failure) {
                    mixedOrdinaryFailures = true
                }
                if (indexedPrior.results.containsKey(term.key)) {
                    retained += term
                } else {
                    failed += term
                }
            }

            for ((position, term) in index.terms.withIndex()) {
                if (!isCurrent(binding, business)) return
                val courses = try {
                    remote.getTerm(cookies, term)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: GradesRemoteException) {
                    if (failure.failure == GradesRemoteFailure.AUTH_REQUIRED) {
                        terminalFailure = GradesFailure.AUTH_EXPIRED
                        terminalConnection = GradesConnection.AUTH_FAILED
                        markRemainingTerms(
                            index.terms,
                            position,
                            indexedPrior.results,
                            retained,
                            failed,
                        )
                        break
                    }
                    recordOrdinaryFailure(term, failure.failure.toGradesFailure())
                    continue
                } catch (_: Exception) {
                    recordOrdinaryFailure(term, GradesFailure.PROTOCOL_ERROR)
                    continue
                }
                results[term.key] = GradesTermResult(
                    term = term,
                    courses = courses,
                    fetchedAtEpochMillis = clock(),
                )
                updated++
            }

            if (!isCurrent(binding, business)) return
            val now = clock()
            val orderedResults = index.terms.mapNotNull { results[it.key] }
            commitMutex.withLock {
                if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                    return@withLock
                }
                currentBinding = binding
                commitBarrier("sync-final")
                if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                    return@withLock
                }
                val hasTermFailure = retained.isNotEmpty() || failed.isNotEmpty()
                val uniformFailure = ordinaryFailure
                // 只记录查询本身的失败；保存失败由 unsavedChanges/STORAGE_ERROR
                // 表达，不能写进快照冒充一次已落盘的查询结果。
                val queryFailure = terminalFailure ?: when {
                    hasTermFailure && !mixedOrdinaryFailures && uniformFailure != null ->
                        uniformFailure
                    hasTermFailure -> GradesFailure.PARTIAL_FAILURE
                    else -> null
                }
                // 索引成功但本轮全部学期失败时，只要仍有可用结果（通常是沿用的
                // 旧结果），也要把本轮失败范围写入快照；否则重启会退回旧快照的
                // READY。没有可用结果时不落盘。
                val shouldSave = updated > 0 || orderedResults.isNotEmpty()
                val saveOk = if (shouldSave) {
                    val snapshot = GradesSnapshot(
                        accountId = binding.accountId.value,
                        credentialGeneration = binding.credentialGeneration.value,
                        // 仅有沿用结果时保留原来的成功时间与逐项 fetchedAt，
                        // 不把一次全失败尝试伪装成新的同步成功时间。
                        savedAtEpochMillis = if (updated > 0) {
                            now
                        } else {
                            prior.lastSyncedAtEpochMillis ?: 0L
                        },
                        terms = index.terms,
                        currentTerm = index.currentTerm,
                        results = orderedResults,
                        lastSyncRetainedTerms = retained.toList(),
                        lastSyncFailedTerms = failed.toList(),
                        lastFailure = queryFailure,
                    )
                    try {
                        withContext(ioDispatcher) { files.save(snapshot) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    true
                }
                val failure = queryFailure ?: if (!saveOk) {
                    GradesFailure.STORAGE_ERROR
                } else {
                    null
                }
                val connection = terminalConnection ?: when {
                    hasTermFailure -> GradesConnection.PARTIAL
                    // 结果只在内存里而未能落盘时，不能显示为普通可重复读取的 READY。
                    !saveOk -> GradesConnection.PROTOCOL_ERROR
                    else -> GradesConnection.READY
                }
                val unsavedChanges = if (shouldSave) {
                    // 本轮覆盖了快照（含只写入失败范围的沿用结果），成功即清除
                    // 之前的未落盘提示；失败则如实保留。
                    !saveOk
                } else {
                    previousState?.takeIf { it.owner == binding }?.unsavedChanges == true
                }
                _state.value = GradesUiState(
                    owner = binding,
                    connection = connection,
                    terms = index.terms,
                    termResults = orderedResults,
                    lastSyncedAtEpochMillis = if (updated > 0) {
                        now
                    } else {
                        prior.lastSyncedAtEpochMillis
                    },
                    lastSyncRetainedTerms = retained.toList(),
                    lastSyncFailedTerms = failed.toList(),
                    lastFailure = failure,
                    unsavedChanges = unsavedChanges,
                )
            }
        } catch (cancelled: CancellationException) {
            // 页面离开会取消 rememberCoroutineScope；必须清掉本次 CONNECTING，
            // 否则再次进入时 restore 会因 CONNECTING 直接返回，同步按钮一直不可用。
            rollbackCancelledSync(binding, business, previousState)
            throw cancelled
        } catch (_: Exception) {
            // 任何未预期异常都回到固定失败语义，不让页面停在“正在同步”。
            commitMutex.withLock {
                val current = _state.value
                if (isBindingCurrent(binding) &&
                    businessEpoch.get() == business &&
                    current.owner == binding &&
                    current.connection == GradesConnection.CONNECTING
                ) {
                    currentBinding = binding
                    _state.value = current.copy(
                        connection = GradesConnection.PROTOCOL_ERROR,
                        lastFailure = GradesFailure.PROTOCOL_ERROR,
                    )
                }
            }
        } finally {
            operationMutex.unlock()
        }
    }

    /**
     * 取消同步时回滚本次 CONNECTING。
     *
     * 只允许恢复到同一账号/凭据代次/业务 epoch 且仍由本次同步持有的状态；账号已经
     * 切换、清理已开始或新同步已发布时绝不覆盖。清理本身不可取消，且在释放
     * [operationMutex] 前完成，避免新的 restore 看到残留的 CONNECTING。
     */
    private suspend fun rollbackCancelledSync(
        binding: SchoolAccountReference,
        business: Long,
        previous: GradesUiState?,
    ) {
        withContext(NonCancellable) {
            commitMutex.withLock {
                if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                    return@withLock
                }
                val current = _state.value
                if (current.owner != binding ||
                    current.connection != GradesConnection.CONNECTING
                ) {
                    return@withLock
                }
                val restored = previous
                    ?.takeIf {
                        it.owner == binding &&
                            it.connection != GradesConnection.CONNECTING
                    }
                _state.value = restored ?: current.copy(connection = GradesConnection.IDLE)
            }
        }
    }

    /**
     * 账号失效：立即作废本业务会话与发布资格。
     *
     * `SESSIONS_ONLY` 保留同账号历史快照；`PERSONAL_DATA` 同时删除加密快照。
     * 迟到的同步结果会因业务 epoch 变化而无法发布或写回。
     */
    suspend fun invalidateForAccount(scope: AccountDataScope): Boolean =
        commitMutex.withLock {
            businessEpoch.incrementAndGet()
            currentBinding = null
            _state.value = GradesUiState()
            withContext(ioDispatcher) {
                if (scope == AccountDataScope.PERSONAL_DATA) files.clear() else true
            }
        }

    // ── account state ─────────────────────────────────────────────────────

    private fun unavailableState(state: SchoolAccountState): GradesUiState =
        GradesUiState(
            owner = null,
            connection = when {
                state.status == SchoolAccountStatus.LOADING -> GradesConnection.LOADING
                state.blocksPersonalSessions -> GradesConnection.BLOCKED_BY_CLEANUP
                else -> GradesConnection.NOT_CONFIGURED
            },
        )

    private fun applyAccountStateLocked() {
        val state = account.state.value
        val reference = account.accountReference.value?.takeIf {
            state.status == SchoolAccountStatus.SAVED_UNVERIFIED &&
                !state.blocksPersonalSessions &&
                state.generation == it.credentialGeneration
        }
        val previous = currentBinding
        if (reference == null) {
            if (previous != null || _state.value.owner != null ||
                _state.value.termResults.isNotEmpty()
            ) {
                businessEpoch.incrementAndGet()
            }
            currentBinding = null
            _state.value = unavailableState(state)
            return
        }
        if (previous != reference) {
            if (_state.value.owner == reference) {
                // restore/sync 可能先于首次账号状态收集发布结果；不能再用旧的
                // currentBinding 把同一账号刚恢复的数据清空。
                currentBinding = reference
                return
            }
            businessEpoch.incrementAndGet()
            currentBinding = reference
            _state.value = GradesUiState(
                owner = reference,
                connection = GradesConnection.IDLE,
            )
            return
        }
        currentBinding = reference
        if (_state.value.owner != reference) {
            _state.value = _state.value.copy(owner = reference)
        }
    }

    private suspend fun publishAccountState() {
        commitMutex.withLock { applyAccountStateLocked() }
    }

    private fun bindingOrNull(): SchoolAccountReference? =
        account.accountReference.value?.takeIf { isBindingCurrent(it) }

    private fun isBindingCurrent(binding: SchoolAccountReference): Boolean {
        val state = account.state.value
        val reference = account.accountReference.value
        return state.status == SchoolAccountStatus.SAVED_UNVERIFIED &&
            !state.blocksPersonalSessions &&
            state.generation == binding.credentialGeneration &&
            reference == binding
    }

    private fun isCurrent(binding: SchoolAccountReference, business: Long): Boolean =
        isBindingCurrent(binding) && businessEpoch.get() == business

    // ── prior/cache ───────────────────────────────────────────────────────

    private data class PriorData(
        val terms: List<GradesTerm>,
        val results: Map<String, GradesTermResult>,
        val lastSyncedAtEpochMillis: Long?,
    ) {
        fun forTerms(terms: List<GradesTerm>): PriorData = PriorData(
            terms = terms,
            results = results.filterKeys { key -> terms.any { it.key == key } },
            lastSyncedAtEpochMillis = lastSyncedAtEpochMillis,
        )

        fun resultsInOrder(): List<GradesTermResult> =
            terms.mapNotNull { results[it.key] }
    }

    private fun priorDataLocked(
        binding: SchoolAccountReference,
        snapshot: GradesSnapshot?,
    ): PriorData {
        val current = _state.value
        val currentMatches = current.owner == binding
        val cached = snapshot?.takeIf { it.accountId == binding.accountId.value }
        val terms = when {
            currentMatches && current.terms.isNotEmpty() -> current.terms
            cached != null -> cached.terms
            else -> emptyList()
        }
        val currentResults = if (currentMatches) {
            current.termResults.associateBy { it.term.key }
        } else {
            emptyMap()
        }
        val results = if (currentResults.isNotEmpty()) {
            currentResults
        } else {
            cached?.results?.associateBy { it.term.key }.orEmpty()
        }
        val lastSynced = when {
            currentMatches && current.lastSyncedAtEpochMillis != null ->
                current.lastSyncedAtEpochMillis
            cached != null -> cached.savedAtEpochMillis.takeIf { it > 0 }
            else -> null
        }
        return PriorData(terms, results, lastSynced)
    }

    private fun stateFromSnapshot(
        binding: SchoolAccountReference,
        snapshot: GradesSnapshot,
    ): GradesUiState {
        val byKey = snapshot.results.associateBy { it.term.key }
        return GradesUiState(
            owner = binding,
            // 完整成功快照才是 READY；带失败范围的快照恢复后仍表达为 PARTIAL，
            // 逐项结果的 fetchedAt 原样保留，不把沿用结果改写成刚同步。
            connection = if (snapshot.lastFailure != null) {
                GradesConnection.PARTIAL
            } else {
                GradesConnection.READY
            },
            terms = snapshot.terms,
            termResults = snapshot.terms.mapNotNull { byKey[it.key] },
            lastSyncedAtEpochMillis = snapshot.savedAtEpochMillis.takeIf { it > 0 },
            lastSyncRetainedTerms = snapshot.lastSyncRetainedTerms,
            lastSyncFailedTerms = snapshot.lastSyncFailedTerms,
            lastFailure = snapshot.lastFailure,
        )
    }

    // ── failures ──────────────────────────────────────────────────────────

    private suspend fun publishRemoteFailure(
        binding: SchoolAccountReference,
        business: Long,
        failure: GradesRemoteFailure,
        prior: PriorData,
    ) {
        publishFailure(
            binding = binding,
            business = business,
            connection = failure.toConnection(),
            failure = failure.toGradesFailure(),
            prior = prior,
        )
    }

    private suspend fun publishFailure(
        binding: SchoolAccountReference,
        business: Long,
        connection: GradesConnection,
        failure: GradesFailure,
        prior: PriorData,
    ) {
        commitMutex.withLock {
            if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                return@withLock
            }
            currentBinding = binding
            val current = _state.value
            val terms = current.terms.ifEmpty { prior.terms }
            val results = current.termResults.ifEmpty { prior.resultsInOrder() }
            val byKey = results.associateBy { it.term.key }
            val retained = terms.filter { byKey.containsKey(it.key) }
            val failed = terms.filterNot { byKey.containsKey(it.key) }
            _state.value = GradesUiState(
                owner = binding,
                connection = connection,
                terms = terms,
                termResults = terms.mapNotNull { byKey[it.key] },
                lastSyncedAtEpochMillis = current.lastSyncedAtEpochMillis
                    ?: prior.lastSyncedAtEpochMillis,
                lastSyncRetainedTerms = retained,
                lastSyncFailedTerms = failed,
                lastFailure = failure,
                unsavedChanges = current.owner == binding && current.unsavedChanges,
            )
        }
    }

    private fun markRemainingTerms(
        terms: List<GradesTerm>,
        start: Int,
        priorResults: Map<String, GradesTermResult>,
        retained: MutableList<GradesTerm>,
        failed: MutableList<GradesTerm>,
    ) {
        for (index in start until terms.size) {
            val term = terms[index]
            if (priorResults.containsKey(term.key)) retained += term else failed += term
        }
    }

    private fun UpcAuthFailure.toGradesFailure(): GradesFailure = when (this) {
        UpcAuthFailure.INVALID_CREDENTIALS -> GradesFailure.AUTH_INVALID
        UpcAuthFailure.INTERACTION_REQUIRED -> GradesFailure.AUTH_INTERACTION
        UpcAuthFailure.NETWORK_UNAVAILABLE -> GradesFailure.NETWORK_UNAVAILABLE
        UpcAuthFailure.TLS_ERROR -> GradesFailure.TLS_ERROR
        UpcAuthFailure.UPSTREAM_ERROR -> GradesFailure.UPSTREAM_ERROR
        UpcAuthFailure.PROTOCOL_ERROR -> GradesFailure.PROTOCOL_ERROR
        UpcAuthFailure.ACCOUNT_UNAVAILABLE,
        UpcAuthFailure.ACCOUNT_CHANGED,
        -> GradesFailure.ACCOUNT_REQUIRED
        UpcAuthFailure.ACCESS_DENIED -> GradesFailure.ACCESS_DENIED
    }

    private fun UpcAuthFailure.toConnection(): GradesConnection = when (this) {
        UpcAuthFailure.INTERACTION_REQUIRED -> GradesConnection.INTERACTION_REQUIRED
        UpcAuthFailure.NETWORK_UNAVAILABLE,
        UpcAuthFailure.TLS_ERROR,
        -> GradesConnection.NETWORK_UNAVAILABLE
        UpcAuthFailure.UPSTREAM_ERROR -> GradesConnection.PROTOCOL_ERROR
        UpcAuthFailure.PROTOCOL_ERROR -> GradesConnection.PROTOCOL_ERROR
        UpcAuthFailure.INVALID_CREDENTIALS,
        UpcAuthFailure.ACCOUNT_UNAVAILABLE,
        UpcAuthFailure.ACCOUNT_CHANGED,
        UpcAuthFailure.ACCESS_DENIED,
        -> GradesConnection.AUTH_FAILED
    }

    private fun GradesRemoteFailure.toGradesFailure(): GradesFailure = when (this) {
        GradesRemoteFailure.AUTH_REQUIRED -> GradesFailure.AUTH_EXPIRED
        GradesRemoteFailure.NETWORK -> GradesFailure.NETWORK_UNAVAILABLE
        GradesRemoteFailure.TIMEOUT -> GradesFailure.TIMEOUT
        GradesRemoteFailure.TLS -> GradesFailure.TLS_ERROR
        GradesRemoteFailure.TOO_LARGE,
        GradesRemoteFailure.SCHEMA,
        GradesRemoteFailure.PROTOCOL,
        -> GradesFailure.PROTOCOL_ERROR
        GradesRemoteFailure.HTTP,
        GradesRemoteFailure.UPSTREAM,
        -> GradesFailure.UPSTREAM_ERROR
    }

    private fun GradesRemoteFailure.toConnection(): GradesConnection = when (this) {
        GradesRemoteFailure.AUTH_REQUIRED -> GradesConnection.AUTH_FAILED
        GradesRemoteFailure.NETWORK,
        GradesRemoteFailure.TIMEOUT,
        GradesRemoteFailure.TLS,
        -> GradesConnection.NETWORK_UNAVAILABLE
        GradesRemoteFailure.TOO_LARGE,
        GradesRemoteFailure.HTTP,
        GradesRemoteFailure.UPSTREAM,
        GradesRemoteFailure.SCHEMA,
        GradesRemoteFailure.PROTOCOL,
        -> GradesConnection.PROTOCOL_ERROR
    }
}
