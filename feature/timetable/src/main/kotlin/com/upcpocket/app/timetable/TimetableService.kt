package com.upcpocket.app.timetable

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.upcauth.TimetableAuthenticator
import com.upcpocket.app.upcauth.UpcAuthFailure
import com.upcpocket.app.upcauth.UpcAuthResult
import com.upcpocket.app.upcauth.UpcTimetableAuthEndpoints
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 课表只读业务服务。
 *
 * - 所有会话、索引和周查询捕获 (accountId, credentialGeneration, businessEpoch)；
 *   切换账号、清理、选周变化后迟到结果不再发布/落盘。
 * - 缺会话或明确 10013 才认证；一次操作最多恢复一次，不循环提交密码。
 * - 首入只查当前需要的周，切换周按需查询；无自动整学期遍历或轮询。
 * - 缓存只保存完整成功周；协议矛盾不覆盖上一次成功缓存。
 */
class TimetableService(
    private val account: SchoolAccountReferenceReader,
    private val authenticator: TimetableAuthenticator,
    private val api: TimetableApi,
    private val sessionStore: TimetableSessionStore,
    private val cacheStore: TimetableCacheStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val todayProvider: () -> LocalDate = { LocalDate.now(TIMETABLE_ZONE) },
    /** 测试用提交屏障：仅在短提交锁内、最后复核之后调用。 */
    private val commitBarrier: suspend (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val colorStore: TimetableColorStore = TimetableColorStore.inMemory(),
) {
    private val _state = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = _state.asStateFlow()

    private val operationMutex = Mutex()
    private val authMutex = Mutex()
    private val commitMutex = Mutex()
    private val businessEpoch = AtomicLong(0)
    private val selectionEpoch = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adopting = AtomicLong(0)
    private var currentBinding: SchoolAccountReference? = null
    private var lastIndex: TimetableIndex? = null
    private var interactiveContext: InteractiveContext? = null
    private var explicitSelection = false
    private var accountEpochValue = 0L

    init {
        // 先取一次同步快照，避免页面在首次收集 Flow 之前打开时被迟到的
        // “账号已恢复”回调清空刚查到的学期/周。
        currentBinding = account.accountReference.value?.takeIf { reference ->
            val state = account.state.value
            state.status == SchoolAccountStatus.SAVED_UNVERIFIED &&
                !state.blocksPersonalSessions &&
                state.generation == reference.credentialGeneration
        }
        scope.launch {
            account.state.collect { state ->
                commitMutex.withLock { applyAccountStateLocked(state) }
            }
        }
        scope.launch {
            account.accountReference.collect {
                commitMutex.withLock {
                    applyAccountStateLocked(account.state.value)
                }
            }
        }
    }

    private data class InteractiveContext(
        val binding: SchoolAccountReference,
        val businessEpoch: Long,
    )

    private class ServiceFailure(
        val connection: TimetableConnection,
        val displayMessage: String,
        val interactionUrl: String? = null,
    ) : Exception(null, null, false, false)

    // ── public operations ─────────────────────────────────────────────────

    /**
     * 页面首次打开：先用缓存呈现已成功查询范围，再在线刷新索引确定学校当前学期/周。
     *
     * 旧缓存里的 currentWeek/currentTerm 只用于离线呈现，不能当作学校现状；
     * 在线 index 失败时保留缓存并明确标为历史。
     */
    suspend fun open() {
        val binding = bindingOrNull() ?: return publishAccountState()
        operationMutex.withLock {
            val business = businessEpoch.get()
            if (!isBindingCurrent(binding)) {
                publishAccountState()
                return
            }
            val cached = loadCache(binding.accountId.value)
            commitBarrier("open-cache")
            val applied = commitMutex.withLock {
                if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                    return@withLock false
                }
                lastIndex = cached.index
                explicitSelection = false
                applySelectionLocked(cached)
                true
            }
            if (applied) {
                loadIndexLocked(binding, selectionEpoch.get())
            }
        }
    }

    /** 主动刷新：索引也必须重取，才能更新 currentTerm/currentWeek。 */
    suspend fun refresh() {
        val binding = bindingOrNull() ?: return publishAccountState()
        operationMutex.withLock {
            if (!isBindingCurrent(binding)) {
                publishAccountState()
                return
            }
            loadIndexLocked(binding, selectionEpoch.get())
        }
    }

    /**
     * 按真实上海日期重算今日卡片；只读当前教学周的本地缓存，不遍历整学期、不发起网络。
     * 跨日后如果当前周尚未缓存，今日状态回到“尚未同步”，不会继续展示昨天的课。
     */
    suspend fun refreshToday() {
        val binding = bindingOrNull() ?: return
        val today = todayProvider()
        val index = lastIndex
        val currentTerm = index?.current()
        val currentWeek = index?.currentWeek
        val cached = withContext(ioDispatcher) { cacheStore.load(binding.accountId.value) }
        val week = if (currentTerm != null && currentWeek != null) {
            cached.week(currentTerm, currentWeek)
        } else {
            null
        }
        commitMutex.withLock {
            if (!isBindingCurrent(binding)) return@withLock
            if (week != null && week.dates.contains(today)) {
                publishTodayLocked(week, today)
            } else {
                _state.update {
                    val sameDay = it.today == today
                    it.copy(
                        today = today,
                        todayMeetings = if (sameDay) it.todayMeetings else emptyList(),
                        todayLoaded = sameDay && it.todayLoaded,
                        todayFetchedAtEpochMillis = if (sameDay) {
                            it.todayFetchedAtEpochMillis
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    /**
     * 供桌面组件冷启动读取：只读账号归属的本地缓存，不登录、不发网络、不依赖 Activity。
     * 账号切换/清理中、未绑定、当前周未缓存都必须返回真实状态，不能伪装成“今天无课”。
     */
    fun readWidgetSnapshot(): TimetableWidgetSnapshot {
        val accountState = account.state.value
        if (accountState.blocksPersonalSessions) {
            return TimetableWidgetSnapshot(TimetableWidgetStatus.CLEANUP, null)
        }
        if (accountState.status == SchoolAccountStatus.LOADING) {
            return TimetableWidgetSnapshot(TimetableWidgetStatus.ACCOUNT_LOADING, null)
        }
        if (!accountState.isCredentialSaved) {
            return TimetableWidgetSnapshot(TimetableWidgetStatus.NO_ACCOUNT, null)
        }
        val binding = bindingOrNull()
            ?: return TimetableWidgetSnapshot(TimetableWidgetStatus.NO_ACCOUNT, null)
        val today = todayProvider()
        val cache = cacheStore.load(binding.accountId.value)
        val index = cache.index
            ?: return TimetableWidgetSnapshot(TimetableWidgetStatus.NEEDS_SYNC, today)
        val term = index.current()
            ?: index.terms.firstOrNull { candidate ->
                val end = candidate.startDay.plusWeeks(candidate.totalWeeks.toLong()).minusDays(1)
                today in candidate.startDay..end
            }
            ?: return TimetableWidgetSnapshot(TimetableWidgetStatus.OUTSIDE_TERM, today)
        val end = term.startDay.plusWeeks(term.totalWeeks.toLong()).minusDays(1)
        if (today !in term.startDay..end) {
            return TimetableWidgetSnapshot(TimetableWidgetStatus.OUTSIDE_TERM, today)
        }
        val currentWeek = index.currentWeek
            ?: return TimetableWidgetSnapshot(TimetableWidgetStatus.NEEDS_SYNC, today)
        val week = cache.week(term, currentWeek)
            ?: return TimetableWidgetSnapshot(TimetableWidgetStatus.NEEDS_SYNC, today)
        if (!week.dates.contains(today)) {
            return TimetableWidgetSnapshot(TimetableWidgetStatus.NEEDS_SYNC, today)
        }
        val slots = runCatching {
            colorStore.assign(
                accountId = binding.accountId.value,
                termKey = "${week.term.year}|${week.term.term}",
                identities = week.meetings.map(::courseColorKey).distinct(),
            )
        }.getOrDefault(emptyMap())
        val courses = week.meetings
            .filter { it.date == today }
            .sortedWith(meetingComparator())
            .map { meeting ->
                val range = UpcTimetableSchedule.range(meeting)
                TimetableWidgetCourse(
                    name = meeting.courseName,
                    location = meeting.location,
                    timeLabel = range?.label,
                    startMinute = range?.startMinute,
                    endMinute = range?.endMinute,
                    periodLabel = if (meeting.startPeriod == meeting.endPeriod) {
                        "第${meeting.startPeriod}节"
                    } else {
                        "第${meeting.startPeriod}–${meeting.endPeriod}节"
                    },
                    colorSlot = slots[courseColorKey(meeting)],
                )
            }
        return TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = today,
            courses = courses,
            fetchedAtEpochMillis = week.fetchedAtEpochMillis,
        )
    }

    private fun publishTodayLocked(week: TimetableWeek, today: LocalDate) {
        if (!week.dates.contains(today)) return
        _state.update {
            it.copy(
                today = today,
                todayMeetings = week.meetings
                    .filter { meeting -> meeting.date == today }
                    .sortedWith(meetingComparator()),
                todayLoaded = true,
                todayFetchedAtEpochMillis = week.fetchedAtEpochMillis,
            )
        }
    }

    suspend fun selectTerm(term: TimetableTerm) {
        val binding = bindingOrNull() ?: return publishAccountState()
        selectionEpoch.incrementAndGet()
        operationMutex.withLock {
            if (!isBindingCurrent(binding)) {
                publishAccountState()
                return
            }
            val week = defaultWeekLocked(term)
            val cached = loadCache(binding.accountId.value)
            val token = selectionEpoch.get()
            var committed = false
            commitMutex.withLock {
                if (!isBindingCurrent(binding) || selectionEpoch.get() != token) {
                    return@withLock
                }
                committed = true
                explicitSelection = true
                lastIndex = cached.index ?: lastIndex
                _state.update {
                    it.copy(
                        selectedYear = term.year,
                        selectedTerm = term.term,
                        selectedWeek = week,
                        weekDates = emptyList(),
                        meetings = emptyList(),
                        weekState = TimetableWeekState.NOT_QUERIED,
                        weekFetchedAtEpochMillis = null,
                        message = null,
                    )
                }
                val cachedWeek = cached.week(term, week)
                if (cachedWeek != null) {
                    publishCachedWeekLocked(cachedWeek)
                }
            }
            if (committed && cached.week(term, week) == null) {
                loadWeekLocked(binding, term, week, token, businessEpoch.get())
            }
        }
    }

    suspend fun selectWeek(week: Int) {
        val binding = bindingOrNull() ?: return publishAccountState()
        val term = selectedTermLocked() ?: return
        if (week !in 1..term.totalWeeks) return
        selectionEpoch.incrementAndGet()
        operationMutex.withLock {
            if (!isBindingCurrent(binding)) {
                publishAccountState()
                return
            }
            val cachedWeek = loadCache(binding.accountId.value).week(term, week)
            val token = selectionEpoch.get()
            var committed = false
            commitMutex.withLock {
                if (!isBindingCurrent(binding) || selectionEpoch.get() != token) {
                    return@withLock
                }
                committed = true
                explicitSelection = true
                _state.update {
                    it.copy(
                        selectedWeek = week,
                        weekDates = emptyList(),
                        meetings = emptyList(),
                        weekState = TimetableWeekState.NOT_QUERIED,
                        weekFetchedAtEpochMillis = null,
                        message = null,
                    )
                }
                if (cachedWeek != null) {
                    publishCachedWeekLocked(cachedWeek)
                }
            }
            if (committed && cachedWeek == null) {
                loadWeekLocked(binding, term, week, token, businessEpoch.get())
            }
        }
    }

    // ── interactive login ─────────────────────────────────────────────────

    suspend fun beginInteractiveLogin(): String? {
        val binding = bindingOrNull() ?: run {
            publishAccountState()
            return null
        }
        return commitMutex.withLock {
            if (!isBindingCurrent(binding)) {
                applyAccountStateLocked(account.state.value)
                return@withLock null
            }
            interactiveContext = InteractiveContext(binding, businessEpoch.get())
            _state.update {
                it.copy(
                    connection = TimetableConnection.INTERACTION_REQUIRED,
                    interactionUrl = UpcTimetableAuthEndpoints.LOGIN_URL,
                    message = "请在学校登录页完成正常登录；本应用不会读取你输入的密码。",
                    isBusy = false,
                    canRetry = true,
                )
            }
            UpcTimetableAuthEndpoints.LOGIN_URL
        }
    }

    suspend fun cancelInteractiveLogin() {
        commitMutex.withLock {
            interactiveContext = null
            _state.update { it.copy(interactionUrl = null) }
        }
    }

    /**
     * WebView 页面完成/跳转时调用。只有 Cookies 非空且索引验证成功才保存；
     * 账号/代次/业务代次已变化的结果直接丢弃。
     */
    suspend fun adoptInteractiveCookies(cookieHeader: String): Boolean {
        val context = interactiveContext ?: return false
        if (!isBindingCurrent(context.binding) || businessEpoch.get() != context.businessEpoch) {
            interactiveContext = null
            publishAccountState()
            return false
        }
        val cookies = parseCookieHeader(cookieHeader)
        if (cookies.isEmpty()) return false
        if (!adopting.compareAndSet(0, 1)) return false
        return try {
            operationMutex.withLock {
                val index = try {
                    api.getIndex(cookies)
                } catch (failure: TimetableApiException) {
                    commitMutex.withLock {
                        if (isBindingCurrent(context.binding) &&
                            businessEpoch.get() == context.businessEpoch
                        ) {
                            val mapped = mapApiFailure(failure)
                            _state.update {
                                it.copy(
                                    connection = mapped.connection,
                                    message = "尚未检测到有效的课表会话；如已登录请回到课表页重试。",
                                    isBusy = false,
                                )
                            }
                        }
                    }
                    return false
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return false
                }
                commitMutex.withLock {
                    if (!isBindingCurrent(context.binding) ||
                        businessEpoch.get() != context.businessEpoch
                    ) {
                        interactiveContext = null
                        applyAccountStateLocked(account.state.value)
                        return@withLock false
                    }
                    val session = TimetableSession(
                        accountId = context.binding.accountId.value,
                        credentialGeneration = context.binding.credentialGeneration.value,
                        businessGeneration = context.businessEpoch,
                        savedAtEpochMillis = clock(),
                        cookies = cookies,
                    )
                    commitBarrier("adopt-session")
                    if (!withContext(ioDispatcher) { sessionStore.save(session) }) {
                        _state.update {
                            it.copy(
                                connection = TimetableConnection.PROTOCOL_ERROR,
                                message = "课表会话已建立，但本机加密保存失败；请稍后重试。",
                                isBusy = false,
                            )
                        }
                        return@withLock false
                    }
                    interactiveContext = null
                    lastIndex = index
                    applyIndexSelectionLocked(index, preserveSelection = explicitSelection)
                    commitBarrier("adopt-cache")
                    withContext(ioDispatcher) {
                        cacheStore.save(
                            context.binding.accountId.value,
                            index,
                            null,
                            clock(),
                        )
                    }
                    _state.update {
                        it.copy(
                            connection = TimetableConnection.CONNECTED,
                            interactionUrl = null,
                            message = null,
                            canRetry = false,
                        )
                    }
                    true
                }
            }
        } finally {
            adopting.set(0)
        }
    }

    // ── invalidation ──────────────────────────────────────────────────────

    /**
     * 账号失效清理；不读取账号状态，避免与账号锁形成环。
     *
     * SESSIONS_ONLY 保留同账号历史周；PERSONAL_DATA 连课表缓存一起删除。
     */
    suspend fun invalidateForAccount(scope: AccountDataScope): Boolean =
        commitMutex.withLock {
            businessEpoch.incrementAndGet()
            selectionEpoch.incrementAndGet()
            accountEpochValue += 1
            explicitSelection = false
            interactiveContext = null
            lastIndex = null
            currentBinding = null
            _state.update {
                it.copy(
                    accountEpoch = accountEpochValue,
                    terms = emptyList(),
                    selectedYear = null,
                    selectedTerm = null,
                    selectedWeek = null,
                    weekDates = emptyList(),
                    meetings = emptyList(),
                    courseColorSlots = emptyMap(),
                    weekState = TimetableWeekState.NOT_QUERIED,
                    weekFetchedAtEpochMillis = null,
                    today = null,
                    todayMeetings = emptyList(),
                    todayLoaded = false,
                    todayFetchedAtEpochMillis = null,
                    connection = TimetableConnection.IDLE,
                    message = null,
                    interactionUrl = null,
                    isBusy = false,
                    canRetry = false,
                )
            }
            val sessionCleared = withContext(ioDispatcher) { sessionStore.clear() }
            val cacheCleared = if (scope == AccountDataScope.PERSONAL_DATA) {
                withContext(ioDispatcher) { cacheStore.clear() }
            } else {
                true
            }
            val colorsCleared = if (scope == AccountDataScope.PERSONAL_DATA) {
                withContext(ioDispatcher) { colorStore.clear() }
            } else {
                true
            }
            sessionCleared && cacheCleared && colorsCleared
        }

    // ── index / week ──────────────────────────────────────────────────────

    private suspend fun loadCache(accountId: String): TimetableCache =
        withContext(ioDispatcher) { cacheStore.load(accountId) }

    private suspend fun loadIndexLocked(binding: SchoolAccountReference, token: Long) {
        val business = businessEpoch.get()
        val started = commitMutex.withLock {
            if (!isBindingCurrent(binding) || businessEpoch.get() != business ||
                selectionEpoch.get() != token
            ) {
                false
            } else {
                _state.update {
                    it.copy(
                        isBusy = true,
                        connection = TimetableConnection.CONNECTING,
                        weekState = TimetableWeekState.LOADING,
                        message = null,
                        interactionUrl = null,
                    )
                }
                true
            }
        }
        if (!started) return
        try {
            val index = withSession(binding) { cookies -> api.getIndex(cookies) }
            val next = commitMutex.withLock {
                if (!isBindingCurrent(binding) || businessEpoch.get() != business ||
                    selectionEpoch.get() != token
                ) {
                    return@withLock null
                }
                lastIndex = index
                applyIndexSelectionLocked(index, preserveSelection = explicitSelection)
                commitBarrier("index-cache")
                withContext(ioDispatcher) {
                    cacheStore.save(binding.accountId.value, index, null, clock())
                }
                val term = selectedTermLocked() ?: throw ServiceFailure(
                    TimetableConnection.PROTOCOL_ERROR,
                    "学校未返回可用学期。",
                )
                val week = _state.value.selectedWeek ?: defaultWeekLocked(term)
                term to week
            } ?: return
            loadWeekLocked(binding, next.first, next.second, selectionEpoch.get(), businessEpoch.get())
        } catch (failure: ServiceFailure) {
            commitMutex.withLock {
                if (isBindingCurrent(binding) && businessEpoch.get() == business &&
                    selectionEpoch.get() == token
                ) {
                    publishFailure(failure)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            commitMutex.withLock {
                if (isBindingCurrent(binding) && businessEpoch.get() == business &&
                    selectionEpoch.get() == token
                ) {
                    publishFailure(
                        ServiceFailure(
                            TimetableConnection.PROTOCOL_ERROR,
                            "课表查询返回了无法确认的结果；已保留上次成功缓存。",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun loadWeekLocked(
        binding: SchoolAccountReference,
        term: TimetableTerm,
        week: Int,
        token: Long,
        business: Long,
    ) {
        val started = commitMutex.withLock {
            if (!isBindingCurrent(binding) || businessEpoch.get() != business ||
                selectionEpoch.get() != token ||
                _state.value.selectedWeek != week ||
                _state.value.selectedYear != term.year ||
                _state.value.selectedTerm != term.term
            ) {
                false
            } else {
                _state.update {
                    it.copy(
                        isBusy = true,
                        connection = TimetableConnection.CONNECTING,
                        weekState = TimetableWeekState.LOADING,
                        message = null,
                        interactionUrl = null,
                    )
                }
                true
            }
        }
        if (!started) return
        try {
            val result = withSession(binding) { cookies -> api.getWeek(cookies, term, week) }
            commitMutex.withLock {
                if (!isBindingCurrent(binding) || businessEpoch.get() != business ||
                    selectionEpoch.get() != token ||
                    _state.value.selectedWeek != week ||
                    _state.value.selectedYear != term.year ||
                    _state.value.selectedTerm != term.term
                ) {
                    return@withLock
                }
                val stamped = result.copy(fetchedAtEpochMillis = clock())
                commitBarrier("week-cache")
                withContext(ioDispatcher) {
                    cacheStore.save(binding.accountId.value, lastIndex, stamped, clock())
                }
                commitBarrier("week-publish")
                publishWeekLocked(stamped)
            }
        } catch (failure: ServiceFailure) {
            commitMutex.withLock {
                if (isBindingCurrent(binding) && businessEpoch.get() == business &&
                    selectionEpoch.get() == token
                ) {
                    publishFailure(failure)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            commitMutex.withLock {
                if (isBindingCurrent(binding) && businessEpoch.get() == business &&
                    selectionEpoch.get() == token
                ) {
                    publishFailure(
                        ServiceFailure(
                            TimetableConnection.PROTOCOL_ERROR,
                            "课表查询返回了无法确认的结果；已保留上次成功缓存。",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun <T> withSession(
        binding: SchoolAccountReference,
        operation: suspend (List<TimetableCookie>) -> T,
    ): T {
        var authAttempted = false
        var retriedAfterAuth = false
        while (true) {
            val session = withContext(ioDispatcher) {
                sessionStore.load(
                    binding.accountId.value,
                    binding.credentialGeneration.value,
                )
            }
            if (session != null) {
                if (!isBindingCurrent(binding)) {
                    throw ServiceFailure(
                        TimetableConnection.NOT_CONFIGURED,
                        "学校账号已变化，请重新打开课表。",
                    )
                }
                try {
                    return operation(session.cookies)
                } catch (failure: TimetableApiException) {
                    if (failure.code == TimetableErrorCode.AUTH_REQUIRED) {
                        if (retriedAfterAuth) {
                            throw ServiceFailure(
                                TimetableConnection.SESSION_EXPIRED,
                                "课表会话已失效，自动恢复一次仍未成功。请稍后主动刷新。",
                            )
                        }
                        // 旧会话明确失效：清掉后最多按当前凭据认证一次。
                        withContext(ioDispatcher) { sessionStore.clear() }
                    } else {
                        throw mapApiFailure(failure)
                    }
                }
            } else if (retriedAfterAuth) {
                throw ServiceFailure(
                    TimetableConnection.SESSION_EXPIRED,
                    "课表会话已失效，自动恢复一次仍未成功。请稍后主动刷新。",
                )
            }
            if (authAttempted) {
                throw ServiceFailure(
                    TimetableConnection.SESSION_EXPIRED,
                    "课表会话已失效，自动恢复一次仍未成功。请稍后主动刷新。",
                )
            }
            authenticate(binding)
            authAttempted = true
            retriedAfterAuth = true
        }
    }

    private suspend fun authenticate(binding: SchoolAccountReference) {
        authMutex.withLock {
            if (!isBindingCurrent(binding)) {
                throw ServiceFailure(
                    TimetableConnection.NOT_CONFIGURED,
                    "学校账号已变化，请重新打开课表。",
                )
            }
            val existing = withContext(ioDispatcher) {
                sessionStore.load(
                    binding.accountId.value,
                    binding.credentialGeneration.value,
                )
            }
            if (existing != null) return
            val business = businessEpoch.get()
            val result = authenticator.authenticate(
                expectedAccountId = binding.accountId,
                expectedCredentialGeneration = binding.credentialGeneration.value,
            )
            when (result) {
                is UpcAuthResult.Failure -> throw when (result.reason) {
                    UpcAuthFailure.INVALID_CREDENTIALS -> ServiceFailure(
                        TimetableConnection.AUTH_FAILED,
                        "学校认证未通过。请核对“首页 → 设置 → 学校账号”中的账号密码；本轮不会自动重试。",
                    )
                    UpcAuthFailure.INTERACTION_REQUIRED -> ServiceFailure(
                        TimetableConnection.INTERACTION_REQUIRED,
                        "学校要求验证码或二次验证。请打开学校登录页完成正常登录。",
                        result.interactionUrl ?: UpcTimetableAuthEndpoints.LOGIN_URL,
                    )
                    UpcAuthFailure.NETWORK_UNAVAILABLE, UpcAuthFailure.TLS_ERROR ->
                        ServiceFailure(
                            TimetableConnection.NETWORK_UNAVAILABLE,
                            "网络不可用，无法完成学校认证。",
                        )
                    UpcAuthFailure.ACCOUNT_UNAVAILABLE -> ServiceFailure(
                        TimetableConnection.NOT_CONFIGURED,
                        "当前学校账号不可用，请先在“首页 → 设置 → 学校账号”重新保存。",
                    )
                    UpcAuthFailure.ACCOUNT_CHANGED -> ServiceFailure(
                        TimetableConnection.NOT_CONFIGURED,
                        "学校账号已变化，本次登录结果已丢弃。",
                    )
                    UpcAuthFailure.ACCESS_DENIED, UpcAuthFailure.UPSTREAM_ERROR,
                    UpcAuthFailure.PROTOCOL_ERROR,
                    -> ServiceFailure(
                        TimetableConnection.PROTOCOL_ERROR,
                        "学校认证返回了无法确认的结果；未保存会话，请稍后重试。",
                    )
                }
                is UpcAuthResult.Success -> {
                    commitMutex.withLock {
                        if (!isBindingCurrent(binding) || businessEpoch.get() != business) {
                            throw ServiceFailure(
                                TimetableConnection.NOT_CONFIGURED,
                                "登录期间学校账号已变化，本次结果已丢弃。",
                            )
                        }
                        val cookies = result.cookies.map {
                            TimetableCookie(
                                name = it.name,
                                value = it.value,
                                domain = it.domain,
                                path = it.path,
                                secure = it.secure,
                                httpOnly = it.httpOnly,
                                expiresAtEpochSeconds = it.expiresAtEpochSeconds,
                            )
                        }
                        val session = TimetableSession(
                            accountId = binding.accountId.value,
                            credentialGeneration = binding.credentialGeneration.value,
                            businessGeneration = business,
                            savedAtEpochMillis = clock(),
                            cookies = cookies,
                        )
                        commitBarrier("auth-session")
                        if (!withContext(ioDispatcher) { sessionStore.save(session) }) {
                            throw ServiceFailure(
                                TimetableConnection.PROTOCOL_ERROR,
                                "课表会话已建立，但本机加密保存失败；请稍后重试。",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun mapApiFailure(failure: TimetableApiException): ServiceFailure = when {
        failure.code == TimetableErrorCode.AUTH_REQUIRED -> ServiceFailure(
            TimetableConnection.SESSION_EXPIRED,
            "课表会话已失效，自动恢复一次仍未成功。请稍后主动刷新。",
        )
        failure.network -> ServiceFailure(
            TimetableConnection.NETWORK_UNAVAILABLE,
            "网络不可用，无法连接学校课表。",
        )
        failure.code == TimetableErrorCode.SCHEMA_CHANGED ||
            failure.code == TimetableErrorCode.RESPONSE_TOO_LARGE -> ServiceFailure(
            TimetableConnection.PROTOCOL_ERROR,
            "课表接口返回了无法确认的数据；已保留上次成功缓存，未覆盖。",
        )
        else -> ServiceFailure(
            TimetableConnection.PROTOCOL_ERROR,
            "学校课表接口暂时不可用；已保留上次成功缓存。",
        )
    }

    // ── state helpers ─────────────────────────────────────────────────────

    private fun publishWeekLocked(week: TimetableWeek) {
        val today = todayProvider()
        val colorSlots = colorSlotsFor(week)
        val todayInWeek = week.dates.contains(today)
        val todayMeetings = if (todayInWeek) {
            week.meetings.filter { it.date == today }.sortedWith(meetingComparator())
        } else {
            emptyList()
        }
        _state.update {
            val sameDay = it.today == today
            it.copy(
                terms = termsOf(lastIndex).ifEmpty { it.terms },
                selectedYear = week.term.year,
                selectedTerm = week.term.term,
                selectedWeek = week.week,
                weekDates = week.dates,
                meetings = week.meetings.sortedWith(meetingComparator()),
                courseColorSlots = colorSlots,
                weekState = if (week.isEmpty) {
                    TimetableWeekState.EMPTY
                } else {
                    TimetableWeekState.READY
                },
                weekFetchedAtEpochMillis = week.fetchedAtEpochMillis,
                today = today,
                todayMeetings = if (todayInWeek) {
                    todayMeetings
                } else if (sameDay) {
                    it.todayMeetings
                } else {
                    emptyList()
                },
                todayLoaded = if (todayInWeek) true else sameDay && it.todayLoaded,
                todayFetchedAtEpochMillis = if (todayInWeek) {
                    week.fetchedAtEpochMillis
                } else if (sameDay) {
                    it.todayFetchedAtEpochMillis
                } else {
                    null
                },
                connection = TimetableConnection.CONNECTED,
                message = null,
                interactionUrl = null,
                isBusy = false,
                canRetry = false,
                accountBlocked = false,
            )
        }
    }

    private fun publishCachedWeekLocked(week: TimetableWeek) {
        val today = todayProvider()
        val colorSlots = colorSlotsFor(week)
        val todayInWeek = week.dates.contains(today)
        val todayMeetings = if (todayInWeek) {
            week.meetings.filter { it.date == today }.sortedWith(meetingComparator())
        } else {
            emptyList()
        }
        _state.update {
            val sameDay = it.today == today
            it.copy(
                selectedYear = week.term.year,
                selectedTerm = week.term.term,
                selectedWeek = week.week,
                weekDates = week.dates,
                meetings = week.meetings.sortedWith(meetingComparator()),
                courseColorSlots = colorSlots,
                weekState = if (week.isEmpty) {
                    TimetableWeekState.EMPTY
                } else {
                    TimetableWeekState.CACHED
                },
                weekFetchedAtEpochMillis = week.fetchedAtEpochMillis,
                today = today,
                todayMeetings = if (todayInWeek) {
                    todayMeetings
                } else if (sameDay) {
                    it.todayMeetings
                } else {
                    emptyList()
                },
                todayLoaded = if (todayInWeek) true else sameDay && it.todayLoaded,
                todayFetchedAtEpochMillis = if (todayInWeek) {
                    week.fetchedAtEpochMillis
                } else if (sameDay) {
                    it.todayFetchedAtEpochMillis
                } else {
                    null
                },
                message = null,
                interactionUrl = null,
                isBusy = false,
                canRetry = true,
                accountBlocked = false,
            )
        }
    }

    private fun colorSlotsFor(week: TimetableWeek): Map<String, Int> {
        val accountId = currentBinding?.accountId?.value ?: return emptyMap()
        val identities = week.meetings.map(::courseColorKey).distinct()
        if (identities.isEmpty()) return emptyMap()
        return runCatching {
            colorStore.assign(
                accountId = accountId,
                termKey = "${week.term.year}|${week.term.term}",
                identities = identities,
            )
        }.getOrDefault(emptyMap())
    }

    private fun publishFailure(failure: ServiceFailure) {
        val current = _state.value
        val hasData = current.weekFetchedAtEpochMillis != null
        _state.update {
            it.copy(
                connection = failure.connection,
                message = failure.displayMessage,
                interactionUrl = failure.interactionUrl,
                isBusy = false,
                canRetry = true,
                weekState = when {
                    hasData && current.meetings.isEmpty() -> TimetableWeekState.EMPTY
                    hasData -> TimetableWeekState.CACHED
                    else -> TimetableWeekState.ERROR
                },
            )
        }
    }

    private fun applyIndexSelectionLocked(index: TimetableIndex, preserveSelection: Boolean) {
        val current = _state.value
        val existing = if (preserveSelection) selectedTermLocked() else null
        val selected = existing?.takeIf { old ->
            index.terms.any { it.year == old.year && it.term == old.term }
        } ?: index.current() ?: index.terms.firstOrNull()
        val preferred = if (preserveSelection && existing != null) {
            current.selectedWeek
        } else if (selected != null &&
            index.currentYear == selected.year && index.currentTerm == selected.term
        ) {
            index.currentWeek
        } else {
            null
        }
        val week = selected?.let {
            (preferred ?: defaultWeekLocked(it)).coerceIn(1, it.totalWeeks)
        }
        val changed = current.selectedYear != selected?.year ||
            current.selectedTerm != selected?.term ||
            current.selectedWeek != week
        _state.update {
            it.copy(
                terms = index.terms,
                selectedYear = selected?.year,
                selectedTerm = selected?.term,
                selectedWeek = week,
                weekDates = if (changed) emptyList() else it.weekDates,
                meetings = if (changed) emptyList() else it.meetings,
                weekState = if (changed) TimetableWeekState.NOT_QUERIED else it.weekState,
                weekFetchedAtEpochMillis = if (changed) null else it.weekFetchedAtEpochMillis,
                // 今日卡片按真实上海日期独立保留：查看其他教学周不能把它清空或
                // 伪装成“今天无课”；换号/清理才由账号失效路径清空。
            )
        }
    }

    private fun applySelectionLocked(cached: TimetableCache) {
        val terms = termsOf(cached.index)
        val current = cached.index?.current()
        val term = current ?: terms.firstOrNull() ?: cached.weeks.firstOrNull()?.term
        if (term == null) {
            _state.update {
                it.copy(
                    terms = terms,
                    connection = TimetableConnection.IDLE,
                    weekState = TimetableWeekState.NOT_QUERIED,
                    message = null,
                )
            }
            return
        }
        val week = defaultWeekLocked(term)
        _state.update {
            it.copy(
                terms = terms,
                selectedYear = term.year,
                selectedTerm = term.term,
                selectedWeek = week,
            )
        }
        cached.week(term, week)?.let(::publishCachedWeekLocked)
    }

    private fun defaultWeekLocked(term: TimetableTerm): Int {
        val index = lastIndex
        // 只使用学校响应里显式给出的 currentWeek；没有时不按开学日推算。
        // 非当前学期从第 1 周开始，用户可再明确选周。
        if (index?.currentYear == term.year && index.currentTerm == term.term &&
            index.currentWeek != null && index.currentWeek in 1..term.totalWeeks
        ) {
            return index.currentWeek
        }
        return 1
    }

    private fun selectedTermLocked(): TimetableTerm? {
        val state = _state.value
        val year = state.selectedYear ?: return null
        val term = state.selectedTerm ?: return null
        return lastIndex?.find(year, term)
            ?: state.terms.firstOrNull { it.year == year && it.term == term }
    }

    private fun termsOf(index: TimetableIndex?): List<TimetableTerm> =
        index?.terms.orEmpty()

    private fun bindingOrNull(): SchoolAccountReference? =
        account.accountReference.value?.takeIf { isBindingCurrent(it) }

    private fun isBindingCurrent(binding: SchoolAccountReference): Boolean {
        val state = account.state.value
        val reference = account.accountReference.value
        return state.status == SchoolAccountStatus.SAVED_UNVERIFIED &&
            !state.blocksPersonalSessions &&
            state.generation == binding.credentialGeneration &&
            reference?.accountId == binding.accountId &&
            reference.credentialGeneration == binding.credentialGeneration
    }

    private fun applyAccountStateLocked(state: SchoolAccountState) {
        val reference = account.accountReference.value
            ?.takeIf {
                state.status == SchoolAccountStatus.SAVED_UNVERIFIED &&
                    !state.blocksPersonalSessions &&
                    state.generation == it.credentialGeneration
            }
        val label = when {
            state.status == SchoolAccountStatus.LOADING -> "正在恢复学校账号"
            state.cleanupRequired || state.blocksPersonalSessions -> "学校账号正在清理或切换"
            state.status == SchoolAccountStatus.NOT_CONFIGURED -> "未设置学校账号"
            state.status == SchoolAccountStatus.NEEDS_RECONFIGURATION -> "需要重新保存学校账号"
            else -> state.maskedAccount?.let { "$it · 已保存，尚未验证" }
                ?: "学校账号已保存，尚未验证"
        }
        val previous = currentBinding
        if (reference == null) {
            if (previous != null) {
                businessEpoch.incrementAndGet()
                selectionEpoch.incrementAndGet()
                accountEpochValue += 1
            }
            currentBinding = null
            lastIndex = null
            explicitSelection = false
            interactiveContext = null
            _state.update {
                it.copy(
                    accountEpoch = accountEpochValue,
                    accountStatusLabel = label,
                    maskedAccount = state.maskedAccount,
                    accountSavedUnverified = state.isCredentialSaved,
                    accountBlocked = state.blocksPersonalSessions ||
                        state.status == SchoolAccountStatus.LOADING,
                    connection = when {
                        state.status == SchoolAccountStatus.LOADING ->
                            TimetableConnection.LOADING
                        state.blocksPersonalSessions ->
                            TimetableConnection.BLOCKED_BY_CLEANUP
                        state.status == SchoolAccountStatus.NEEDS_RECONFIGURATION ->
                            TimetableConnection.NOT_CONFIGURED
                        else -> TimetableConnection.NOT_CONFIGURED
                    },
                    terms = emptyList(),
                    selectedYear = null,
                    selectedTerm = null,
                    selectedWeek = null,
                    weekDates = emptyList(),
                    meetings = emptyList(),
                    weekState = TimetableWeekState.NOT_QUERIED,
                    weekFetchedAtEpochMillis = null,
                    today = null,
                    todayMeetings = emptyList(),
                    todayLoaded = false,
                    todayFetchedAtEpochMillis = null,
                    message = when {
                        state.status == SchoolAccountStatus.LOADING ->
                            "正在恢复本机账号；完成前不会查询个人课表。"
                        state.blocksPersonalSessions ->
                            "学校账号正在清理或切换；完成前不会登录或显示旧课表。"
                        state.status == SchoolAccountStatus.NEEDS_RECONFIGURATION ->
                            "学校账号密文不可用，请重新保存后再使用课表。"
                        else -> "请先在“首页 → 设置 → 学校账号”保存学校账号。"
                    },
                    interactionUrl = null,
                    isBusy = false,
                    canRetry = false,
                )
            }
            return
        }
        if (previous?.accountId != reference.accountId ||
            previous?.credentialGeneration != reference.credentialGeneration
        ) {
            businessEpoch.incrementAndGet()
            selectionEpoch.incrementAndGet()
            accountEpochValue += 1
            explicitSelection = false
            lastIndex = null
            interactiveContext = null
            _state.update {
                it.copy(
                    accountEpoch = accountEpochValue,
                    terms = emptyList(),
                    selectedYear = null,
                    selectedTerm = null,
                    selectedWeek = null,
                    weekDates = emptyList(),
                    meetings = emptyList(),
                    courseColorSlots = emptyMap(),
                    weekState = TimetableWeekState.NOT_QUERIED,
                    weekFetchedAtEpochMillis = null,
                    today = null,
                    todayMeetings = emptyList(),
                    todayLoaded = false,
                    todayFetchedAtEpochMillis = null,
                    interactionUrl = null,
                    isBusy = false,
                    message = null,
                )
            }
        }
        currentBinding = reference
        _state.update {
            it.copy(
                accountStatusLabel = label,
                maskedAccount = state.maskedAccount,
                accountSavedUnverified = state.isCredentialSaved,
                accountBlocked = false,
                connection = if (it.weekState == TimetableWeekState.NOT_QUERIED) {
                    TimetableConnection.IDLE
                } else {
                    it.connection
                },
            )
        }
    }

    private suspend fun publishAccountState() {
        commitMutex.withLock { applyAccountStateLocked(account.state.value) }
    }

    private fun parseCookieHeader(header: String): List<TimetableCookie> =
        header.split(';')
            .mapNotNull { part ->
                val trimmed = part.trim()
                val index = trimmed.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val name = trimmed.substring(0, index).trim()
                val value = trimmed.substring(index + 1).trim()
                runCatching {
                    TimetableCookie(
                        name = name,
                        value = value,
                        domain = "app.upc.edu.cn",
                        path = "/",
                        secure = true,
                        httpOnly = true,
                    )
                }.getOrNull()
            }
            .distinctBy { it.name }
            .take(TimetableStorage.MAX_COOKIES)

    internal fun meetingComparator(): Comparator<TimetableMeeting> =
        compareBy<TimetableMeeting>(
            { it.date },
            { it.startPeriod },
            { it.endPeriod },
            { it.courseName },
            { it.location.orEmpty() },
            { it.teacher.orEmpty() },
            { it.instanceId },
        )
}
