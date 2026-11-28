package com.upcpocket.app.timetable

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBodyType
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticExceptionType
import com.upcpocket.app.platform.diagnostics.DiagnosticMediaType
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

private const val ACCOUNT_RESTORE_BUDGET_MS = 3_000L
private const val OFFICIAL_TIMETABLE_URL = "https://app.upc.edu.cn/site/weekschedule/index"

/**
 * 课表只读业务服务。
 *
 * - 所有索引和周查询捕获 (accountId, credentialGeneration, businessEpoch)；
 *   切换账号、清理、选周变化后迟到结果不再发布/落盘。
 * - 校园数据只通过宿主注入的 Pocket backend source 读取，不保存学校 Cookie 或密码。
 * - 首入只查当前需要的周，切换周按需查询；无自动整学期遍历或轮询。
 * - 缓存只保存完整成功周；协议矛盾不覆盖上一次成功缓存。
 */
class TimetableService(
    private val account: SchoolAccountReferenceReader,
    private val backend: TimetableBackendSource,
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
    private val _todayState = MutableStateFlow(TimetableTodayUiState())
    val todayState: StateFlow<TimetableTodayUiState> = _todayState.asStateFlow()

    private val operationMutex = Mutex()
    private val commitMutex = Mutex()
    private val colorMutex = Mutex()
    private val businessEpoch = AtomicLong(0)
    private val selectionEpoch = AtomicLong(0)
    private val todayReadGeneration = AtomicLong(0)
    private val weekReadGeneration = AtomicLong(0)
    private val todayTomorrowReadGeneration = AtomicLong(0)
    private val weekSnapshotRevision = AtomicLong(0)
    private val todayTomorrowSnapshotRevision = AtomicLong(0)

    /** 每次成功写入本地课表缓存后递增；读取期间发生写入时旧快照不得再发布。 */
    private val widgetCacheEpoch = AtomicLong(0)
    @Volatile
    private var latestWeekWidgetVersion: WeekWidgetVersion? = null
    @Volatile
    private var latestTodayTomorrowWidgetVersion: TodayTomorrowWidgetVersion? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentBinding: SchoolAccountReference? = null
    private var lastIndex: TimetableIndex? = null
    private var explicitSelection = false
    private var accountEpochValue = 0L

    init {
        // 先取一次同步快照，避免页面在首次收集 Flow 之前打开时被迟到的
        // “账号已恢复”回调清空刚查到的学期/周。
        currentBinding = account.accountReference.value?.takeIf { reference ->
            val state = account.state.value
            state.isManagedActive && !state.blocksPersonalSessions &&
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

    private data class TodayReadVersion(
        val businessEpoch: Long,
        val generation: Long,
        val date: LocalDate,
    )

    private data class WeekWidgetVersion(
        val binding: SchoolAccountReference,
        val businessEpoch: Long,
        val today: LocalDate,
        val weekStart: LocalDate,
        val revision: Long,
        val cacheEpoch: Long,
    )

    private data class TodayTomorrowWidgetVersion(
        val binding: SchoolAccountReference,
        val businessEpoch: Long,
        val today: LocalDate,
        val revision: Long,
        val cacheEpoch: Long,
    )

    private data class CalendarReadVersion(
        val binding: SchoolAccountReference,
        val businessEpoch: Long,
        val weekToken: Long?,
        val todayTomorrowToken: Long?,
        val today: LocalDate,
        val weekStart: LocalDate,
        val cacheEpoch: Long,
    )

    private class ServiceFailure(
        val connection: TimetableConnection,
        val displayMessage: String,
        val interactionUrl: String? = null,
        val detail: DiagnosticDetail? = null,
        val httpStatus: Int? = null,
        val responseBytes: Int? = null,
        val exceptionType: DiagnosticExceptionType? = null,
        val mediaType: DiagnosticMediaType? = null,
        val bodyType: DiagnosticBodyType? = null,
        val phase: DiagnosticPhase = DiagnosticPhase.INDEX,
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
        val diagnosticOperation = Diagnostics.beginOperation()
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
                loadIndexLocked(binding, selectionEpoch.get(), diagnosticOperation)
            }
        }
    }

    /** 主动刷新：索引也必须重取，才能更新 currentTerm/currentWeek。 */
    suspend fun refresh() {
        val binding = bindingOrNull() ?: return publishAccountState()
        val diagnosticOperation = Diagnostics.beginOperation()
        operationMutex.withLock {
            if (!isBindingCurrent(binding)) {
                publishAccountState()
                return
            }
            loadIndexLocked(binding, selectionEpoch.get(), diagnosticOperation)
        }
    }

    /**
     * 按上海实际日期从本机缓存恢复今日课程，不调用 open()、不查询学校接口，也不改变
     * 正在查看的学期/周。账号启动恢复有界等待；缓存磁盘读取在 IO dispatcher 完成。
     */
    suspend fun refreshToday() = restoreTodayFromCache()

    suspend fun restoreTodayFromCache() {
        if (account.state.value.status == SchoolAccountStatus.LOADING) {
            publishTodayAccountState()
            withTimeoutOrNull(ACCOUNT_RESTORE_BUDGET_MS) {
                account.state.first { it.status != SchoolAccountStatus.LOADING }
            }
            if (account.state.value.status == SchoolAccountStatus.LOADING) {
                publishTodayAccountState()
                return
            }
        }

        val binding = bindingOrNull()
        if (binding == null) {
            publishTodayAccountState()
            return
        }
        val readVersion = commitMutex.withLock {
            if (!isBindingCurrent(binding)) return@withLock null
            // A cold-start account can finish restoring just before its collector adopts the
            // new reference. Adopt it here before capturing businessEpoch so this cache-only
            // read is not mistaken for stale work on its first attempt.
            applyAccountStateLocked(account.state.value)
            if (!isBindingCurrent(binding)) return@withLock null
            val today = todayProvider()
            val version = TodayReadVersion(
                businessEpoch = businessEpoch.get(),
                generation = todayReadGeneration.incrementAndGet(),
                date = today,
            )
            _todayState.update { current ->
                // Keep an already loaded day visible while checking the same account's cache.
                if (current.todayLoaded && current.today == today &&
                    current.accountEpoch == accountEpochValue
                ) {
                    return@update current
                }
                TimetableTodayUiState(
                    accountEpoch = accountEpochValue,
                    accountManagedActive = true,
                    accountBlocked = false,
                    today = today,
                    todayState = TimetableTodayState.READING_CACHE,
                    todayMessage = null,
                )
            }
            version
        } ?: return

        val cached = withContext(ioDispatcher) {
            cacheStore.loadResult(binding.accountId.value)
        }
        commitBarrier("today-cache")
        val requested = resolveTodayCache(cached, readVersion.date)
        val colors = requested.week?.let { week ->
            colorMutex.withLock {
                if (!isBindingCurrent(binding) ||
                    businessEpoch.get() != readVersion.businessEpoch ||
                    todayReadGeneration.get() != readVersion.generation
                ) {
                    emptyMap()
                } else {
                    withContext(ioDispatcher) {
                        runCatching {
                            colorStore.assign(
                                accountId = binding.accountId.value,
                                termKey = "${week.term.year}|${week.term.term}",
                                identities = week.meetings.map(::courseColorKey).distinct(),
                            )
                        }.getOrDefault(emptyMap())
                    }
                }
            }
        }.orEmpty()
        commitMutex.withLock {
            if (!isBindingCurrent(binding) ||
                businessEpoch.get() != readVersion.businessEpoch ||
                todayReadGeneration.get() != readVersion.generation ||
                todayProvider() != readVersion.date
            ) {
                return@withLock
            }
            publishTodayLocked(
                resolution = requested,
                today = readVersion.date,
                colorSlots = colors,
            )
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
        if (!accountState.isManagedActive) {
            return TimetableWidgetSnapshot(TimetableWidgetStatus.NO_ACCOUNT, null)
        }
        val binding = bindingOrNull()
            ?: return TimetableWidgetSnapshot(TimetableWidgetStatus.NO_ACCOUNT, null)
        val today = todayProvider()
        val cache = cacheStore.load(binding.accountId.value)
        val week = when (val coverage = findCachedWeekCoveringDate(cache, today)) {
            is CachedWeekCoverage.Found -> coverage.week
            CachedWeekCoverage.Conflict ->
                return TimetableWidgetSnapshot(TimetableWidgetStatus.NEEDS_SYNC, today)
            CachedWeekCoverage.Missing -> {
                val index = cache.index
                    ?: return TimetableWidgetSnapshot(TimetableWidgetStatus.NEEDS_SYNC, today)
                val inTerm = index.terms.any { it.contains(today) }
                return TimetableWidgetSnapshot(
                    if (inTerm) {
                        TimetableWidgetStatus.NEEDS_SYNC
                    } else {
                        TimetableWidgetStatus.OUTSIDE_TERM
                    },
                    today,
                )
            }
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

    /**
     * 立即撤下本周内容的占位快照。只读账号状态，不读缓存、不联网，可在组件
     * Receiver 线程调用；账号仍可用时返回 [TimetableWeekWidgetStatus.READING]，
     * 由异步读取替换，而不是继续展示旧账号或旧日期的课程。
     */
    fun weekWidgetPlaceholderNow(): TimetableWeekWidgetSnapshot {
        val accountState = account.state.value
        val today = todayProvider()
        val weekStart = TimetableWeekWidgetSnapshot.weekStartOf(today)
        val status = when {
            accountState.blocksPersonalSessions -> TimetableWeekWidgetStatus.CLEANUP
            accountState.status == SchoolAccountStatus.LOADING ->
                TimetableWeekWidgetStatus.ACCOUNT_LOADING
            !accountState.isManagedActive -> TimetableWeekWidgetStatus.NO_ACCOUNT
            bindingOrNull() == null -> TimetableWeekWidgetStatus.NO_ACCOUNT
            else -> TimetableWeekWidgetStatus.READING
        }
        return TimetableWeekWidgetSnapshot(status, weekStart, today)
    }

    /**
     * 「今明课表」的立即占位：与周版共用账号/清理/恢复判断，但携带今天与次日，
     * 让 Receiver 在后台读取前就能撤下旧课程而不虚构无课。
     */
    fun todayTomorrowWidgetPlaceholderNow(): TimetableTodayTomorrowWidgetSnapshot {
        val accountState = account.state.value
        val today = todayProvider()
        val status = when {
            accountState.blocksPersonalSessions -> TimetableTodayTomorrowWidgetStatus.CLEANUP
            accountState.status == SchoolAccountStatus.LOADING ->
                TimetableTodayTomorrowWidgetStatus.ACCOUNT_LOADING
            !accountState.isManagedActive -> TimetableTodayTomorrowWidgetStatus.NO_ACCOUNT
            bindingOrNull() == null -> TimetableTodayTomorrowWidgetStatus.NO_ACCOUNT
            else -> TimetableTodayTomorrowWidgetStatus.READING
        }
        return TimetableTodayTomorrowWidgetSnapshot(status, today, today.plusDays(1))
    }

    /**
     * 读取上海时区实际今天所在周日至周六的缓存周。一次读取当前受控账号的
     * [TimetableCacheStore.loadResult]，逐日复用 [findCachedWeekCoveringDate]
     * 选择成功周，允许相邻缓存周拼接覆盖；只有七日全覆盖且无课才返回
     * [TimetableWeekWidgetStatus.EMPTY]。
     *
     * 读取完成后在提交锁内复核绑定、凭据代次、业务代次、读取代次和日期/周起点；
     * 任一变化都返回占位状态，旧读取不能把已撤下的内容画回去。
     */
    suspend fun readWeekWidgetSnapshot(): TimetableWeekWidgetSnapshot =
        readCalendarWidgetSnapshots(includeWeek = true, includeTodayTomorrow = false).week
            ?: weekWidgetPlaceholderNow()

    /** 今明版单独读取；与周版同时存在时由宿主调用 [readCalendarWidgetSnapshots] 共用一次缓存读取。 */
    suspend fun readTodayTomorrowWidgetSnapshot(): TimetableTodayTomorrowWidgetSnapshot =
        readCalendarWidgetSnapshots(includeWeek = false, includeTodayTomorrow = true).todayTomorrow
            ?: todayTomorrowWidgetPlaceholderNow()

    /**
     * 一次读取当前受控账号的缓存，按需产出周版和/或今明版。
     *
     * 周版保持原有七日与修订语义；今明版只用同一份读取结果分别投影今天和次日，
     * 周六的明天可以落在另一教学周，不要求与周版整体状态一致。读取完成后在同一
     * 提交锁内分别复核绑定、业务代次、读取代次、实际日期和缓存写入代次；未通过
     * 的一组退回占位，另一组不影响。
     */
    suspend fun readCalendarWidgetSnapshots(
        includeWeek: Boolean,
        includeTodayTomorrow: Boolean,
    ): TimetableCalendarWidgetSnapshots {
        require(includeWeek || includeTodayTomorrow) { "至少请求一种课表组件快照" }
        if (account.state.value.status == SchoolAccountStatus.LOADING) {
            withTimeoutOrNull(ACCOUNT_RESTORE_BUDGET_MS) {
                account.state.first { it.status != SchoolAccountStatus.LOADING }
            }
        }
        fun placeholders() = TimetableCalendarWidgetSnapshots(
            week = if (includeWeek) weekWidgetPlaceholderNow() else null,
            todayTomorrow = if (includeTodayTomorrow) todayTomorrowWidgetPlaceholderNow() else null,
        )

        val initial = placeholders()
        val weekNeedsRead = includeWeek &&
            initial.week?.status == TimetableWeekWidgetStatus.READING
        val todayTomorrowNeedsRead = includeTodayTomorrow &&
            initial.todayTomorrow?.status == TimetableTodayTomorrowWidgetStatus.READING
        if (!weekNeedsRead && !todayTomorrowNeedsRead) return initial
        val binding = bindingOrNull() ?: return placeholders()
        val version = commitMutex.withLock {
            if (!isBindingCurrent(binding)) return@withLock null
            CalendarReadVersion(
                binding = binding,
                businessEpoch = businessEpoch.get(),
                weekToken = if (weekNeedsRead) weekReadGeneration.incrementAndGet() else null,
                todayTomorrowToken = if (todayTomorrowNeedsRead) {
                    todayTomorrowReadGeneration.incrementAndGet()
                } else {
                    null
                },
                today = initial.week?.today ?: requireNotNull(initial.todayTomorrow).today,
                weekStart = initial.week?.weekStart ?: TimetableWeekWidgetSnapshot.weekStartOf(
                    requireNotNull(initial.todayTomorrow).today,
                ),
                cacheEpoch = widgetCacheEpoch.get(),
            )
        } ?: return placeholders()

        val result = withContext(ioDispatcher) {
            cacheStore.loadResult(version.binding.accountId.value)
        }
        commitBarrier(
            when {
                weekNeedsRead && todayTomorrowNeedsRead -> "calendar-widget-cache"
                weekNeedsRead -> "week-widget-cache"
                else -> "today-tomorrow-widget-cache"
            },
        )
        return commitMutex.withLock {
            val finalToday = todayProvider()
            val current = isBindingCurrent(version.binding) &&
                businessEpoch.get() == version.businessEpoch &&
                finalToday == version.today &&
                widgetCacheEpoch.get() == version.cacheEpoch
            val weekSnapshot = when {
                !includeWeek -> null
                !weekNeedsRead -> weekWidgetPlaceholderNow()
                !current ||
                    weekReadGeneration.get() != version.weekToken ||
                    TimetableWeekWidgetSnapshot.weekStartOf(finalToday) != version.weekStart ->
                    weekWidgetPlaceholderNow()
                else -> {
                    val snapshot = buildWeekWidgetSnapshot(result, version.today, version.weekStart)
                    val revision = weekSnapshotRevision.incrementAndGet()
                    snapshot.assignRevision(revision)
                    latestWeekWidgetVersion = WeekWidgetVersion(
                        binding = version.binding,
                        businessEpoch = version.businessEpoch,
                        today = version.today,
                        weekStart = version.weekStart,
                        revision = revision,
                        cacheEpoch = version.cacheEpoch,
                    )
                    snapshot
                }
            }
            val todayTomorrowSnapshot = when {
                !includeTodayTomorrow -> null
                !todayTomorrowNeedsRead -> todayTomorrowWidgetPlaceholderNow()
                !current || todayTomorrowReadGeneration.get() != version.todayTomorrowToken ->
                    todayTomorrowWidgetPlaceholderNow()
                else -> {
                    val snapshot = buildTodayTomorrowWidgetSnapshot(result, version.today)
                    val revision = todayTomorrowSnapshotRevision.incrementAndGet()
                    snapshot.assignRevision(revision)
                    latestTodayTomorrowWidgetVersion = TodayTomorrowWidgetVersion(
                        binding = version.binding,
                        businessEpoch = version.businessEpoch,
                        today = version.today,
                        revision = revision,
                        cacheEpoch = version.cacheEpoch,
                    )
                    snapshot
                }
            }
            TimetableCalendarWidgetSnapshots(weekSnapshot, todayTomorrowSnapshot)
        }
    }

    /** 发布前复核：只接受仍属于当前绑定、业务代次、今天与周起点的最后一次读取。 */
    fun isWeekWidgetSnapshotCurrent(snapshot: TimetableWeekWidgetSnapshot): Boolean {
        if (!snapshot.isReadResult) return false
        val latest = latestWeekWidgetVersion ?: return false
        if (latest.revision != snapshot.revision) return false
        val today = todayProvider()
        if (today != snapshot.today || TimetableWeekWidgetSnapshot.weekStartOf(today) != snapshot.weekStart) {
            return false
        }
        if (latest.today != snapshot.today || latest.weekStart != snapshot.weekStart) return false
        if (businessEpoch.get() != latest.businessEpoch) return false
        if (widgetCacheEpoch.get() != latest.cacheEpoch) return false
        return isBindingCurrent(latest.binding)
    }

    /**
     * 发布前复核今明版：读取后被成功写缓存、账号/代次变化或上海日期跨日，
     * 都拒绝旧快照。日期比较同时保证周六 → 周日只显示读取当时的两天。
     */
    fun isTodayTomorrowWidgetSnapshotCurrent(
        snapshot: TimetableTodayTomorrowWidgetSnapshot,
    ): Boolean {
        if (!snapshot.isReadResult) return false
        val latest = latestTodayTomorrowWidgetVersion ?: return false
        if (latest.revision != snapshot.revision) return false
        val today = todayProvider()
        if (today != snapshot.today || snapshot.tomorrow != today.plusDays(1)) return false
        if (latest.today != snapshot.today) return false
        if (businessEpoch.get() != latest.businessEpoch) return false
        if (widgetCacheEpoch.get() != latest.cacheEpoch) return false
        return isBindingCurrent(latest.binding)
    }

    private fun buildWeekWidgetSnapshot(
        result: TimetableCacheReadResult,
        today: LocalDate,
        weekStart: LocalDate,
    ): TimetableWeekWidgetSnapshot {
        fun snapshot(
            status: TimetableWeekWidgetStatus,
            days: List<TimetableWeekWidgetDay> = emptyList(),
            fetchedAt: Long? = null,
        ) = TimetableWeekWidgetSnapshot(status, weekStart, today, days, fetchedAt)

        return when (result) {
            TimetableCacheReadResult.Missing -> snapshot(TimetableWeekWidgetStatus.NEEDS_SYNC)
            TimetableCacheReadResult.Corrupted,
            TimetableCacheReadResult.Unreadable,
            TimetableCacheReadResult.WrongAccount,
            -> snapshot(TimetableWeekWidgetStatus.READ_ERROR)
            is TimetableCacheReadResult.Available -> {
                val cache = result.cache
                var coveredDays = 0
                var conflictDays = 0
                var outsideDays = 0
                var newestFetchedAt: Long? = null
                val days = (0L..6L).map { offset ->
                    val date = weekStart.plusDays(offset)
                    val projection = projectWidgetDay(cache, date)
                    when (val coverage = projection.coverage) {
                        is CachedWeekCoverage.Found -> {
                            coveredDays += 1
                            val fetched = coverage.week.fetchedAtEpochMillis
                            newestFetchedAt = newestFetchedAt
                                ?.let { maxOf(it, fetched) }
                                ?: fetched
                            val courses = coverage.week.meetings
                                .filter { it.date == date }
                                .sortedWith(meetingComparator())
                                .map { meeting ->
                                    TimetableWeekWidgetCourse(
                                        name = meeting.courseName,
                                        startPeriod = meeting.startPeriod,
                                        endPeriod = meeting.endPeriod,
                                    )
                                }
                            TimetableWeekWidgetDay(
                                date = date,
                                state = if (courses.isEmpty()) {
                                    TimetableWeekWidgetDayState.EMPTY
                                } else {
                                    TimetableWeekWidgetDayState.COURSES
                                },
                                courses = courses,
                            )
                        }
                        CachedWeekCoverage.Conflict -> {
                            conflictDays += 1
                            TimetableWeekWidgetDay(date, TimetableWeekWidgetDayState.CONFLICT)
                        }
                        CachedWeekCoverage.Missing -> {
                            if (cache.index != null && !projection.inTerm) {
                                outsideDays += 1
                                TimetableWeekWidgetDay(date, TimetableWeekWidgetDayState.OUTSIDE_TERM)
                            } else {
                                TimetableWeekWidgetDay(date, TimetableWeekWidgetDayState.UNSYNCED)
                            }
                        }
                    }
                }
                val status = when {
                    coveredDays == 7 && days.all { it.state == TimetableWeekWidgetDayState.EMPTY } ->
                        TimetableWeekWidgetStatus.EMPTY
                    coveredDays == 7 -> TimetableWeekWidgetStatus.OK
                    coveredDays > 0 -> TimetableWeekWidgetStatus.PARTIAL
                    conflictDays > 0 -> TimetableWeekWidgetStatus.CONFLICT
                    outsideDays == 7 -> TimetableWeekWidgetStatus.OUTSIDE_TERM
                    else -> TimetableWeekWidgetStatus.NEEDS_SYNC
                }
                snapshot(status, days, newestFetchedAt)
            }
        }
    }

    /**
     * 今明版按同一上海日期分别投影，互不借用周版的整体状态：
     * - 缓存完全缺失：两天都是未同步；
     * - 缓存损坏/不可读/账号不匹配：保留两天日期，各显示读取失败；
     * - 某天有成功周覆盖：按真实课程列出，空列表才是已同步无课；
     * - 某天无覆盖但索引存在且不在任何学期：学期外；
     * - 其余无覆盖：该日未同步；同一天多个不同的最新周：冲突。
     */
    private fun buildTodayTomorrowWidgetSnapshot(
        result: TimetableCacheReadResult,
        today: LocalDate,
    ): TimetableTodayTomorrowWidgetSnapshot {
        val tomorrow = today.plusDays(1)
        fun day(
            date: LocalDate,
            state: TimetableTodayTomorrowWidgetDayState,
            courses: List<TimetableTodayTomorrowWidgetCourse> = emptyList(),
        ) = TimetableTodayTomorrowWidgetDay(date, state, courses)

        fun snapshot(
            status: TimetableTodayTomorrowWidgetStatus,
            days: List<TimetableTodayTomorrowWidgetDay> = emptyList(),
        ) = TimetableTodayTomorrowWidgetSnapshot(status, today, tomorrow, days)

        return when (result) {
            TimetableCacheReadResult.Missing -> snapshot(
                TimetableTodayTomorrowWidgetStatus.OK,
                listOf(
                    day(today, TimetableTodayTomorrowWidgetDayState.UNSYNCED),
                    day(tomorrow, TimetableTodayTomorrowWidgetDayState.UNSYNCED),
                ),
            )
            TimetableCacheReadResult.Corrupted,
            TimetableCacheReadResult.Unreadable,
            TimetableCacheReadResult.WrongAccount,
            -> snapshot(
                TimetableTodayTomorrowWidgetStatus.READ_ERROR,
                listOf(
                    day(today, TimetableTodayTomorrowWidgetDayState.READ_ERROR),
                    day(tomorrow, TimetableTodayTomorrowWidgetDayState.READ_ERROR),
                ),
            )
            is TimetableCacheReadResult.Available -> {
                val cache = result.cache
                val days = listOf(today, tomorrow).map { date ->
                    val projection = projectWidgetDay(cache, date)
                    when (val coverage = projection.coverage) {
                        is CachedWeekCoverage.Found -> {
                            val courses = coverage.week.meetings
                                .filter { it.date == date }
                                .sortedWith(meetingComparator())
                                .map { meeting ->
                                    TimetableTodayTomorrowWidgetCourse(
                                        name = meeting.courseName,
                                        startPeriod = meeting.startPeriod,
                                        endPeriod = meeting.endPeriod,
                                    )
                                }
                            day(
                                date = date,
                                state = if (courses.isEmpty()) {
                                    TimetableTodayTomorrowWidgetDayState.EMPTY
                                } else {
                                    TimetableTodayTomorrowWidgetDayState.COURSES
                                },
                                courses = courses,
                            )
                        }
                        CachedWeekCoverage.Conflict ->
                            day(date, TimetableTodayTomorrowWidgetDayState.CONFLICT)
                        CachedWeekCoverage.Missing ->
                            if (cache.index != null && !projection.inTerm) {
                                day(date, TimetableTodayTomorrowWidgetDayState.OUTSIDE_TERM)
                            } else {
                                day(date, TimetableTodayTomorrowWidgetDayState.UNSYNCED)
                            }
                    }
                }
                snapshot(
                    status = TimetableTodayTomorrowWidgetStatus.OK,
                    days = days,
                )
            }
        }
    }

    private data class WidgetDayProjection(
        val coverage: CachedWeekCoverage,
        val inTerm: Boolean,
    )

    private fun projectWidgetDay(cache: TimetableCache, date: LocalDate): WidgetDayProjection =
        WidgetDayProjection(
            coverage = findCachedWeekCoveringDate(cache, date),
            inTerm = cache.index?.terms?.any { it.contains(date) } == true,
        )

    private data class TodayCacheResolution(
        val state: TimetableTodayState,
        val week: TimetableWeek? = null,
        val meetings: List<TimetableMeeting> = emptyList(),
        val message: String? = null,
    )

    private sealed interface CachedWeekCoverage {
        data object Missing : CachedWeekCoverage

        data class Found(val week: TimetableWeek) : CachedWeekCoverage

        data object Conflict : CachedWeekCoverage
    }

    private fun resolveTodayCache(
        result: TimetableCacheReadResult,
        today: LocalDate,
    ): TodayCacheResolution {
        return when (result) {
            TimetableCacheReadResult.Missing ->
                TodayCacheResolution(TimetableTodayState.NOT_SYNCED)
            TimetableCacheReadResult.Corrupted,
            TimetableCacheReadResult.Unreadable,
            -> TodayCacheResolution(
                state = TimetableTodayState.READ_ERROR,
                message = "本机课表读取失败，请前往课表页重试。",
            )
            TimetableCacheReadResult.WrongAccount -> TodayCacheResolution(
                state = TimetableTodayState.READ_ERROR,
                message = "本机课表缓存与当前账号不匹配。",
            )
            is TimetableCacheReadResult.Available -> {
                val cache = result.cache
                val week = when (val coverage = findCachedWeekCoveringDate(cache, today)) {
                    CachedWeekCoverage.Conflict -> return TodayCacheResolution(
                        state = TimetableTodayState.READ_ERROR,
                        message = "本机课表在今天存在冲突，请前往课表页刷新。",
                    )
                    CachedWeekCoverage.Missing -> {
                        val inTerm = cache.index?.terms.orEmpty().any { it.contains(today) }
                        return TodayCacheResolution(
                            if (inTerm || cache.index == null) {
                                TimetableTodayState.NOT_SYNCED
                            } else {
                                TimetableTodayState.OUTSIDE_TERM
                            },
                        )
                    }
                    is CachedWeekCoverage.Found -> coverage.week
                }
                val meetings = week.meetings
                    .filter { it.date == today }
                    .sortedWith(meetingComparator())
                TodayCacheResolution(
                    state = if (meetings.isEmpty()) {
                        TimetableTodayState.EMPTY
                    } else {
                        TimetableTodayState.READY
                    },
                    week = week,
                    meetings = meetings,
                )
            }
        }
    }

    private fun findCachedWeekCoveringDate(
        cache: TimetableCache,
        date: LocalDate,
    ): CachedWeekCoverage {
        val matchingWeeks = cache.weeks.filter { week ->
            date in week.dates
        }
        if (matchingWeeks.isEmpty()) return CachedWeekCoverage.Missing

        val weeksByTerm = matchingWeeks.groupBy { it.term.year to it.term.term }
        // week.dates is the coverage source. Term ranges and the cached current term only
        // disambiguate overlapping candidates; currentWeek is deliberately never consulted.
        val datedTerms = weeksByTerm.filterValues { weeks ->
            weeks.any { it.term.contains(date) }
        }.keys
        val indexedTerm = cache.index?.current()
            ?.takeIf { it.contains(date) }
            ?.let { it.year to it.term }
        val selectedTerm = when {
            datedTerms.size == 1 -> datedTerms.single()
            datedTerms.size > 1 -> indexedTerm?.takeIf { it in datedTerms }
                ?: return CachedWeekCoverage.Conflict
            indexedTerm != null && indexedTerm in weeksByTerm -> indexedTerm
            weeksByTerm.size == 1 -> weeksByTerm.keys.single()
            else -> return CachedWeekCoverage.Conflict
        }
        val selectedWeeks = weeksByTerm.getValue(selectedTerm)
        if (selectedWeeks.map { it.week }.distinct().size > 1) {
            return CachedWeekCoverage.Conflict
        }
        val fetchedAt = selectedWeeks.maxOf { it.fetchedAtEpochMillis }
        val newestWeeks = selectedWeeks.filter { it.fetchedAtEpochMillis == fetchedAt }.distinct()
        // Equal timestamps with different contents have no safe freshness ordering.
        if (newestWeeks.size > 1) return CachedWeekCoverage.Conflict
        return CachedWeekCoverage.Found(newestWeeks.single())
    }

    private fun TimetableTerm.contains(date: LocalDate): Boolean {
        val end = startDay.plusWeeks(totalWeeks.toLong()).minusDays(1)
        return date in startDay..end
    }

    private fun publishTodayLocked(
        resolution: TodayCacheResolution,
        today: LocalDate,
        colorSlots: Map<String, Int>,
    ) {
        _todayState.update {
            it.copy(
                today = today,
                todayMeetings = resolution.meetings,
                todayCourseColorSlots = colorSlots,
                todayState = resolution.state,
                todayMessage = resolution.message,
                todayLoaded = resolution.state == TimetableTodayState.READY ||
                    resolution.state == TimetableTodayState.EMPTY,
                todayFetchedAtEpochMillis = resolution.week?.fetchedAtEpochMillis,
            )
        }
    }

    suspend fun selectTerm(term: TimetableTerm) {
        val binding = bindingOrNull() ?: return publishAccountState()
        val diagnosticOperation = Diagnostics.beginOperation()
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
                loadWeekLocked(
                    binding = binding,
                    term = term,
                    week = week,
                    token = token,
                    business = businessEpoch.get(),
                    operationId = diagnosticOperation.id,
                    diagnosticOperation = diagnosticOperation,
                )
            }
        }
    }

    suspend fun selectWeek(week: Int) {
        val binding = bindingOrNull() ?: return publishAccountState()
        val term = selectedTermLocked() ?: return
        if (week !in 1..term.totalWeeks) return
        val diagnosticOperation = Diagnostics.beginOperation()
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
                loadWeekLocked(
                    binding = binding,
                    term = term,
                    week = week,
                    token = token,
                    business = businessEpoch.get(),
                    operationId = diagnosticOperation.id,
                    diagnosticOperation = diagnosticOperation,
                )
            }
        }
    }

    // ── official verification handoff ──────────────────────────────────────

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
            _state.update {
                it.copy(
                    connection = TimetableConnection.INTERACTION_REQUIRED,
                    interactionUrl = OFFICIAL_TIMETABLE_URL,
                    message = "请在学校官方页面完成验证，再返回校方托管账号设置。",
                    isBusy = false,
                    canRetry = true,
                )
            }
            OFFICIAL_TIMETABLE_URL
        }
    }

    suspend fun cancelInteractiveLogin() {
        commitMutex.withLock { _state.update { it.copy(interactionUrl = null) } }
    }

    /**
     * Official WebView cookies never become a business session. Verification must be
     * completed through the managed account flow; keeping this callback closed prevents
     * a local-cookie/direct-school fallback from returning after migration.
     */
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
            todayReadGeneration.incrementAndGet()
            invalidateCalendarWidgetSnapshotsLocked()
            accountEpochValue += 1
            explicitSelection = false
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
                    connection = TimetableConnection.IDLE,
                    message = null,
                    interactionUrl = null,
                    isBusy = false,
                    canRetry = false,
                )
            }
            _todayState.value = TimetableTodayUiState(
                accountEpoch = accountEpochValue,
                accountManagedActive = false,
                accountBlocked = true,
                today = todayProvider(),
                todayState = TimetableTodayState.ACCOUNT_BLOCKED,
                todayMessage = "学校账号正在切换，今日课程已隐藏。",
            )
            val sessionCleared = withContext(ioDispatcher) { sessionStore.clear() }
            val cacheCleared = if (scope == AccountDataScope.PERSONAL_DATA) {
                withContext(ioDispatcher) { cacheStore.clear() }
            } else {
                true
            }
            val colorsCleared = if (scope == AccountDataScope.PERSONAL_DATA) {
                colorMutex.withLock {
                    withContext(ioDispatcher) { colorStore.clear() }
                }
            } else {
                true
            }
            sessionCleared && cacheCleared && colorsCleared
        }

    // ── index / week ──────────────────────────────────────────────────────

    private suspend fun loadCache(accountId: String): TimetableCache =
        withContext(ioDispatcher) { cacheStore.load(accountId) }

    private suspend fun loadIndexLocked(
        binding: SchoolAccountReference,
        token: Long,
        diagnosticOperation: DiagnosticOperation,
    ) {
        val operationId = diagnosticOperation.id
        val business = businessEpoch.get()
        val startedAt = clock()
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
        recordQueryStart(
            DiagnosticPhase.INDEX,
            DiagnosticDetail.INDEX_REQUEST,
            operationId,
            diagnosticOperation,
        )
        try {
            val index = try {
                backend.index().toDomainIndex()
            } catch (failure: TimetableBackendFailure) {
                throw mapBackendFailure(failure, DiagnosticPhase.INDEX)
            }
            recordCompatibility(index.compatibility, operationId, diagnosticOperation)
            recordQueryComplete(
                phase = DiagnosticPhase.INDEX,
                detail = DiagnosticDetail.INDEX_OK,
                result = DiagnosticResult.OK,
                operationId = operationId,
                itemCount = index.terms.size,
                durationMs = elapsed(startedAt),
                diagnosticOperation = diagnosticOperation,
            )
            val next = commitMutex.withLock {
                if (!isBindingCurrent(binding) || businessEpoch.get() != business ||
                    selectionEpoch.get() != token
                ) {
                    recordQueryComplete(
                        phase = DiagnosticPhase.INDEX,
                        detail = DiagnosticDetail.STALE_DISCARDED,
                        result = DiagnosticResult.PARTIAL,
                        operationId = operationId,
                        reason = DiagnosticReason.STALE,
                        diagnosticOperation = diagnosticOperation,
                    )
                    return@withLock null
                }
                lastIndex = index
                applyIndexSelectionLocked(index, preserveSelection = explicitSelection)
                commitBarrier("index-cache")
                val cached = withContext(ioDispatcher) {
                    cacheStore.save(binding.accountId.value, index, null, clock())
                }
                if (cached) {
                    widgetCacheEpoch.incrementAndGet()
                    recordQueryComplete(
                        phase = DiagnosticPhase.CACHE,
                        detail = DiagnosticDetail.CACHE_SAVED,
                        result = DiagnosticResult.OK,
                        operationId = operationId,
                        itemCount = index.terms.size,
                        diagnosticOperation = diagnosticOperation,
                    )
                } else {
                    recordQueryFail(
                        phase = DiagnosticPhase.CACHE,
                        detail = DiagnosticDetail.CACHE_SAVE_FAILED,
                        operationId = operationId,
                        reason = DiagnosticReason.STORAGE,
                        diagnosticOperation = diagnosticOperation,
                    )
                }
                val term = selectedTermLocked() ?: throw ServiceFailure(
                    TimetableConnection.PROTOCOL_ERROR,
                    "学校未返回可用学期。",
                    detail = DiagnosticDetail.INDEX_PARSE_CURRENT,
                )
                val week = _state.value.selectedWeek ?: defaultWeekLocked(term)
                term to week
            } ?: return
            loadWeekLocked(
                binding = binding,
                term = next.first,
                week = next.second,
                token = selectionEpoch.get(),
                business = businessEpoch.get(),
                operationId = operationId,
                diagnosticOperation = diagnosticOperation,
            )
        } catch (failure: ServiceFailure) {
            commitMutex.withLock {
                if (isBindingCurrent(binding) && businessEpoch.get() == business &&
                    selectionEpoch.get() == token
                ) {
                    publishFailure(failure, operationId, diagnosticOperation)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            commitMutex.withLock {
                if (isBindingCurrent(binding) && businessEpoch.get() == business &&
                    selectionEpoch.get() == token
                ) {
                    publishFailure(
                        ServiceFailure(
                            TimetableConnection.PROTOCOL_ERROR,
                            unreadableDataMessage(currentHasCache()),
                            detail = DiagnosticDetail.RESPONSE_NON_JSON,
                            exceptionType = Diagnostics.classify(failure),
                        ),
                        operationId,
                        diagnosticOperation,
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
        operationId: String,
        diagnosticOperation: DiagnosticOperation,
    ) {
        val startedAt = clock()
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
        recordQueryStart(
            DiagnosticPhase.WEEK,
            DiagnosticDetail.WEEK_REQUEST,
            operationId,
            diagnosticOperation,
        )
        try {
            val result = try {
                backend.week(term.year, term.term, week).toDomainWeek(term, week)
            } catch (failure: TimetableBackendFailure) {
                throw mapBackendFailure(failure, DiagnosticPhase.WEEK)
            }
            if (!result.complete || result.fetchedAtEpochMillis <= 0L ||
                result.term.year != term.year || result.term.term != term.term ||
                result.week != week
            ) {
                throw ServiceFailure(
                    TimetableConnection.PROTOCOL_ERROR,
                    "校方返回的课表不完整或与所选周不匹配，已保留上次成功数据。",
                    detail = DiagnosticDetail.WEEK_PARSE_CLASSES,
                    phase = DiagnosticPhase.WEEK,
                )
            }
            recordCompatibility(result.compatibility, operationId, diagnosticOperation)
            recordQueryComplete(
                phase = DiagnosticPhase.WEEK,
                detail = if (result.meetings.isEmpty()) {
                    DiagnosticDetail.WEEK_EMPTY
                } else {
                    DiagnosticDetail.WEEK_OK
                },
                result = DiagnosticResult.OK,
                operationId = operationId,
                itemCount = result.meetings.size,
                durationMs = elapsed(startedAt),
                diagnosticOperation = diagnosticOperation,
            )
            commitMutex.withLock {
                if (!isBindingCurrent(binding) || businessEpoch.get() != business ||
                    selectionEpoch.get() != token ||
                    _state.value.selectedWeek != week ||
                    _state.value.selectedYear != term.year ||
                    _state.value.selectedTerm != term.term
                ) {
                    recordQueryComplete(
                        phase = DiagnosticPhase.WEEK,
                        detail = DiagnosticDetail.STALE_DISCARDED,
                        result = DiagnosticResult.PARTIAL,
                        operationId = operationId,
                        reason = DiagnosticReason.STALE,
                        diagnosticOperation = diagnosticOperation,
                    )
                    return@withLock
                }
                val stamped = result
                commitBarrier("week-cache")
                val cached = withContext(ioDispatcher) {
                    cacheStore.save(binding.accountId.value, lastIndex, stamped, clock())
                }
                if (cached) {
                    widgetCacheEpoch.incrementAndGet()
                    recordQueryComplete(
                        phase = DiagnosticPhase.CACHE,
                        detail = DiagnosticDetail.CACHE_SAVED,
                        result = DiagnosticResult.OK,
                        operationId = operationId,
                        itemCount = stamped.meetings.size,
                        diagnosticOperation = diagnosticOperation,
                    )
                } else {
                    recordQueryFail(
                        phase = DiagnosticPhase.CACHE,
                        detail = DiagnosticDetail.CACHE_SAVE_FAILED,
                        operationId = operationId,
                        reason = DiagnosticReason.STORAGE,
                        diagnosticOperation = diagnosticOperation,
                    )
                }
                commitBarrier("week-publish")
                publishWeekLocked(stamped)
            }
        } catch (failure: ServiceFailure) {
            commitMutex.withLock {
                if (isBindingCurrent(binding) && businessEpoch.get() == business &&
                    selectionEpoch.get() == token
                ) {
                    publishFailure(failure, operationId, diagnosticOperation)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            commitMutex.withLock {
                if (isBindingCurrent(binding) && businessEpoch.get() == business &&
                    selectionEpoch.get() == token
                ) {
                    publishFailure(
                        ServiceFailure(
                            TimetableConnection.PROTOCOL_ERROR,
                            unreadableDataMessage(currentHasCache()),
                            detail = DiagnosticDetail.RESPONSE_NON_JSON,
                            exceptionType = Diagnostics.classify(failure),
                            phase = DiagnosticPhase.WEEK,
                        ),
                        operationId,
                        diagnosticOperation,
                    )
                }
            }
        }
    }

    private fun mapBackendFailure(
        failure: TimetableBackendFailure,
        phase: DiagnosticPhase,
    ): ServiceFailure {
        val retryDelay = failure.retryAfterSeconds?.coerceIn(1, 86_400)
        return when (failure.code) {
            TimetableBackendFailureCode.SERVICE_NOT_READY -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                "校方服务未就绪，已保留本机成功数据；可稍后重试或打开学校网页。",
                interactionUrl = OFFICIAL_TIMETABLE_URL,
                phase = phase,
            )
            TimetableBackendFailureCode.TOKEN_INVALID,
            TimetableBackendFailureCode.UNAUTHENTICATED -> ServiceFailure(
                TimetableConnection.SESSION_EXPIRED,
                "校方托管会话已失效，请重新绑定托管账号。",
                detail = DiagnosticDetail.AUTH_REQUIRED,
                phase = phase,
            )
            TimetableBackendFailureCode.DISABLED -> ServiceFailure(
                TimetableConnection.SESSION_EXPIRED,
                "校方账号已停用，请联系学校服务管理员。",
                phase = phase,
            )
            TimetableBackendFailureCode.INTERACTIVE_CHALLENGE -> ServiceFailure(
                TimetableConnection.INTERACTION_REQUIRED,
                "学校要求网页验证。完成学校官方验证后，请返回重试。",
                interactionUrl = OFFICIAL_TIMETABLE_URL,
                detail = DiagnosticDetail.AUTH_INTERACTION,
                phase = phase,
            )
            TimetableBackendFailureCode.CREDENTIAL_UPDATE_REQUIRED -> ServiceFailure(
                TimetableConnection.AUTH_FAILED,
                "请在账号设置更新校方托管密码后重试。",
                phase = phase,
            )
            TimetableBackendFailureCode.VERIFICATION_BUSY -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                "校方正在处理验证，请稍后重试。",
                phase = phase,
            )
            TimetableBackendFailureCode.MODULE_DISABLED -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                "校方暂未开放课表模块，已保留此账号的上次成功数据。",
                phase = phase,
            )
            TimetableBackendFailureCode.CIRCUIT_OPEN -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                "校方课表服务正在暂停请求，已保留上次成功数据。",
                phase = phase,
            )
            TimetableBackendFailureCode.UPSTREAM_UNAVAILABLE -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                "学校课表暂时不可用，已保留此账号的上次成功数据；可稍后重试或打开学校网页。",
                interactionUrl = OFFICIAL_TIMETABLE_URL,
                phase = phase,
            )
            TimetableBackendFailureCode.DEPENDENCY_UNAVAILABLE -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                "校方课表服务依赖暂时不可用，已保留上次成功数据；可稍后重试或打开学校网页。",
                interactionUrl = OFFICIAL_TIMETABLE_URL,
                phase = phase,
            )
            TimetableBackendFailureCode.UPSTREAM_TIMEOUT -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                "学校课表响应超时，已保留上次成功数据；可稍后重试或打开学校网页。",
                interactionUrl = OFFICIAL_TIMETABLE_URL,
                phase = phase,
            )
            TimetableBackendFailureCode.VALIDATION_FAILED -> ServiceFailure(
                TimetableConnection.PROTOCOL_ERROR,
                "课表请求参数未通过校验。",
                phase = phase,
            )
            TimetableBackendFailureCode.IDENTITY_UNSUPPORTED -> ServiceFailure(
                TimetableConnection.AUTH_FAILED,
                "当前账号暂不支持校方托管课表服务。",
                phase = phase,
            )
            TimetableBackendFailureCode.UPSTREAM_PROTOCOL_ERROR -> ServiceFailure(
                TimetableConnection.PROTOCOL_ERROR,
                unreadableDataMessage(currentHasCache()),
                interactionUrl = OFFICIAL_TIMETABLE_URL,
                detail = DiagnosticDetail.RESPONSE_NON_JSON,
                phase = phase,
            )
            TimetableBackendFailureCode.RATE_LIMITED -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                retryDelay?.let { "请求过于频繁，请在 $it 秒后重试。" }
                    ?: "请求过于频繁，请稍后重试。",
                phase = phase,
            )
            TimetableBackendFailureCode.NETWORK_UNAVAILABLE -> ServiceFailure(
                TimetableConnection.NETWORK_UNAVAILABLE,
                "网络不可用，已保留上次成功数据。",
                phase = phase,
            )
        }
    }

    private fun unreadableDataMessage(hasCache: Boolean): String =
        if (hasCache) {
            "校方返回内容格式异常，已保留上次缓存。"
        } else {
            "校方返回内容格式异常，暂无可用的旧缓存。"
        }

    private fun currentHasCache(): Boolean {
        val current = _state.value
        return current.weekFetchedAtEpochMillis != null ||
            current.weekState == TimetableWeekState.CACHED ||
            current.weekState == TimetableWeekState.READY ||
            current.weekState == TimetableWeekState.EMPTY
    }

    // ── diagnostics ───────────────────────────────────────────────────────

    private fun recordQueryStart(
        phase: DiagnosticPhase,
        detail: DiagnosticDetail?,
        operationId: String,
        diagnosticOperation: DiagnosticOperation? = null,
    ) {
        Diagnostics.record(
            module = DiagnosticModule.TIMETABLE,
            action = DiagnosticAction.QUERY_START,
            result = DiagnosticResult.PENDING,
            phase = phase,
            detail = detail,
            operationId = operationId,
            operation = diagnosticOperation?.takeIf { it.id == operationId },
        )
    }

    private fun recordQueryComplete(
        phase: DiagnosticPhase,
        detail: DiagnosticDetail,
        result: DiagnosticResult,
        operationId: String,
        reason: DiagnosticReason? = null,
        itemCount: Int? = null,
        durationMs: Long? = null,
        diagnosticOperation: DiagnosticOperation? = null,
    ) {
        Diagnostics.record(
            module = DiagnosticModule.TIMETABLE,
            action = if (result == DiagnosticResult.OK) {
                DiagnosticAction.QUERY_COMPLETE
            } else {
                DiagnosticAction.QUERY_CHANGED
            },
            result = result,
            reason = reason,
            phase = phase,
            detail = detail,
            itemCount = itemCount,
            durationMs = durationMs,
            operationId = operationId,
            operation = diagnosticOperation?.takeIf { it.id == operationId },
        )
    }

    private fun recordQueryFail(
        phase: DiagnosticPhase,
        detail: DiagnosticDetail,
        operationId: String,
        reason: DiagnosticReason,
        httpStatus: Int? = null,
        responseBytes: Int? = null,
        exceptionType: DiagnosticExceptionType? = null,
        mediaType: DiagnosticMediaType? = null,
        bodyType: DiagnosticBodyType? = null,
        durationMs: Long? = null,
        diagnosticOperation: DiagnosticOperation? = null,
    ) {
        Diagnostics.record(
            module = DiagnosticModule.TIMETABLE,
            action = DiagnosticAction.QUERY_FAIL,
            result = DiagnosticResult.FAIL,
            reason = reason,
            phase = phase,
            detail = detail,
            httpStatus = httpStatus,
            responseBytes = responseBytes,
            exceptionType = exceptionType,
            mediaType = mediaType,
            bodyType = bodyType,
            durationMs = durationMs,
            operationId = operationId,
            operation = diagnosticOperation?.takeIf { it.id == operationId },
        )
    }

    private fun recordQueryException(
        phase: DiagnosticPhase,
        detail: DiagnosticDetail,
        operationId: String,
        failure: Throwable,
        durationMs: Long? = null,
        diagnosticOperation: DiagnosticOperation? = null,
    ) {
        Diagnostics.recordException(
            module = DiagnosticModule.TIMETABLE,
            action = DiagnosticAction.QUERY_FAIL,
            result = DiagnosticResult.FAIL,
            reason = DiagnosticReason.UNKNOWN,
            failure = failure,
            durationMs = durationMs,
            operationId = operationId,
            phase = phase,
            detail = detail,
            operation = diagnosticOperation?.takeIf { it.id == operationId },
        )
    }

    private fun recordCompatibility(
        notes: List<TimetableCompatibility>,
        operationId: String,
        diagnosticOperation: DiagnosticOperation? = null,
    ) {
        notes.forEach { note ->
            Diagnostics.record(
                module = DiagnosticModule.TIMETABLE,
                action = DiagnosticAction.QUERY_CHANGED,
                result = DiagnosticResult.OK,
                reason = DiagnosticReason.SCHEMA,
                phase = DiagnosticPhase.PARSE,
                detail = when (note) {
                    TimetableCompatibility.WEEKDAY_IGNORED ->
                        DiagnosticDetail.COMPAT_WEEKDAY_IGNORED
                    TimetableCompatibility.ORDER_IGNORED ->
                        DiagnosticDetail.COMPAT_ORDER_IGNORED
                    TimetableCompatibility.TOTAL_LENGTH_IGNORED ->
                        DiagnosticDetail.COMPAT_TOTAL_LENGTH_IGNORED
                    TimetableCompatibility.CELL_START_IGNORED ->
                        DiagnosticDetail.COMPAT_CELL_START_IGNORED
                    TimetableCompatibility.INTEGRAL_FLOAT_ACCEPTED ->
                        DiagnosticDetail.COMPAT_INTEGRAL_FLOAT
                    TimetableCompatibility.DUPLICATE_PERIODS_DEDUPED ->
                        DiagnosticDetail.COMPAT_DUPLICATE_PERIODS
                    TimetableCompatibility.INDEX_TERM_SKIPPED ->
                        DiagnosticDetail.COMPAT_INDEX_TERM_SKIPPED
                    TimetableCompatibility.INDEX_CURRENT_IGNORED ->
                        DiagnosticDetail.COMPAT_INDEX_CURRENT_IGNORED
                },
                operationId = operationId,
                operation = diagnosticOperation?.takeIf { it.id == operationId },
            )
        }
    }

    private fun elapsed(started: Long): Long = (clock() - started).coerceAtLeast(0)

    // ── state helpers ─────────────────────────────────────────────────────

    private fun publishWeekLocked(week: TimetableWeek) {
        val today = todayProvider()
        val colorSlots = colorSlotsFor(week)
        publishTodayFromWeekLocked(week, today, colorSlots)
        _state.update {
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
        publishTodayFromWeekLocked(week, today, colorSlots)
        _state.update {
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
                message = null,
                interactionUrl = null,
                isBusy = false,
                canRetry = true,
                accountBlocked = false,
            )
        }
    }

    private fun publishTodayFromWeekLocked(
        week: TimetableWeek,
        today: LocalDate,
        colorSlots: Map<String, Int>,
    ) {
        if (today !in week.dates) return
        // This helper runs under commitMutex. Advance before publishing so any in-flight
        // cache-only read cannot later replace a newer online result for the same date.
        todayReadGeneration.incrementAndGet()
        val meetings = week.meetings
            .filter { it.date == today }
            .sortedWith(meetingComparator())
        _todayState.update {
            it.copy(
                accountEpoch = accountEpochValue,
                accountManagedActive = true,
                accountBlocked = false,
                today = today,
                todayMeetings = meetings,
                todayCourseColorSlots = colorSlots,
                todayState = if (meetings.isEmpty()) {
                    TimetableTodayState.EMPTY
                } else {
                    TimetableTodayState.READY
                },
                todayMessage = null,
                todayLoaded = true,
                todayFetchedAtEpochMillis = week.fetchedAtEpochMillis,
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

    private fun failureReason(failure: ServiceFailure): DiagnosticReason = when {
        failure.connection == TimetableConnection.AUTH_FAILED ||
            failure.connection == TimetableConnection.SESSION_EXPIRED ||
            failure.connection == TimetableConnection.INTERACTION_REQUIRED ->
            DiagnosticReason.AUTH
        failure.exceptionType == DiagnosticExceptionType.TLS -> DiagnosticReason.TLS
        failure.exceptionType == DiagnosticExceptionType.TIMEOUT -> DiagnosticReason.NETWORK
        failure.connection == TimetableConnection.NETWORK_UNAVAILABLE ->
            DiagnosticReason.NETWORK
        failure.detail == DiagnosticDetail.RESPONSE_LOGIN_PAGE -> DiagnosticReason.AUTH
        failure.detail == DiagnosticDetail.RESPONSE_MIME_MISMATCH -> DiagnosticReason.MIME
        failure.detail == DiagnosticDetail.RESPONSE_BUSINESS -> DiagnosticReason.HTTP
        failure.detail == DiagnosticDetail.RESPONSE_NON_JSON ||
            failure.detail == DiagnosticDetail.RESPONSE_HTML ||
            failure.detail == DiagnosticDetail.RESPONSE_MALFORMED_JSON ||
            failure.detail == DiagnosticDetail.RESPONSE_EMPTY ||
            failure.detail == DiagnosticDetail.INDEX_PARSE_TERM_INFO ||
            failure.detail == DiagnosticDetail.INDEX_PARSE_TERM_DATE ||
            failure.detail == DiagnosticDetail.INDEX_PARSE_TERM_WEEKS ||
            failure.detail == DiagnosticDetail.INDEX_PARSE_CURRENT ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_CLASSES ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_DATES ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_DATE_ALIGN ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_CELL_KEY ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_LESS_ARR ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_NAME ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_WEEK_LABEL ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_WEEKDAY ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_ORDER ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_TOTAL_LENGTH ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_PERIOD_RANGE ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_ENTRY_TYPE ||
            failure.detail == DiagnosticDetail.WEEK_PARSE_DAY_TYPE ->
            DiagnosticReason.SCHEMA
        failure.httpStatus != null -> DiagnosticReason.HTTP
        failure.connection == TimetableConnection.PROTOCOL_ERROR -> DiagnosticReason.SCHEMA
        else -> DiagnosticReason.UNKNOWN
    }

    private fun publishFailure(
        failure: ServiceFailure,
        operationId: String,
        diagnosticOperation: DiagnosticOperation? = null,
    ) {
        val current = _state.value
        val hasData = current.weekFetchedAtEpochMillis != null
        recordQueryFail(
            phase = failure.phase,
            detail = failure.detail ?: DiagnosticDetail.PUBLISH_FAIL,
            operationId = operationId,
            reason = failureReason(failure),
            httpStatus = failure.httpStatus,
            responseBytes = failure.responseBytes,
            exceptionType = failure.exceptionType,
            mediaType = failure.mediaType,
            bodyType = failure.bodyType,
            diagnosticOperation = diagnosticOperation,
        )
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
        return state.isManagedActive &&
            !state.blocksPersonalSessions &&
            state.generation == binding.credentialGeneration &&
            reference?.accountId == binding.accountId &&
            reference.credentialGeneration == binding.credentialGeneration
    }

    private fun invalidateCalendarWidgetSnapshotsLocked() {
        weekReadGeneration.incrementAndGet()
        todayTomorrowReadGeneration.incrementAndGet()
        latestWeekWidgetVersion = null
        latestTodayTomorrowWidgetVersion = null
    }

    private fun applyAccountStateLocked(state: SchoolAccountState) {
        val reference = account.accountReference.value
            ?.takeIf {
                state.isManagedActive &&
                    !state.blocksPersonalSessions &&
                    state.generation == it.credentialGeneration
            }
        val label = when {
            state.status == SchoolAccountStatus.LOADING -> "正在恢复校方托管账号"
            state.cleanupRequired || state.blocksPersonalSessions -> "校方托管账号正在清理"
            !state.isManagedActive -> "未绑定校方托管账号"
            else -> "校方托管账号已连接"
        }
        val previous = currentBinding
        if (reference == null) {
            invalidateCalendarWidgetSnapshotsLocked()
            if (previous != null) {
                businessEpoch.incrementAndGet()
                selectionEpoch.incrementAndGet()
                todayReadGeneration.incrementAndGet()
                accountEpochValue += 1
            }
            currentBinding = null
            lastIndex = null
            explicitSelection = false
            _state.update {
                it.copy(
                    accountEpoch = accountEpochValue,
                    accountStatusLabel = label,
                    maskedAccount = state.maskedAccount,
                    accountManagedActive = state.isManagedActive,
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
                    message = when {
                        state.status == SchoolAccountStatus.LOADING ->
                            "正在恢复校方托管账号；完成前不会查询个人课表。"
                        state.blocksPersonalSessions ->
                            "学校账号正在清理或切换；完成前不会读取后端或显示旧课表。"
                        state.status == SchoolAccountStatus.NEEDS_RECONFIGURATION ->
                            "校方托管会话不可用，请在账号设置重新绑定。"
                        else -> "请先在“首页 → 设置 → 学校账号”绑定校方托管账号。"
                    },
                    interactionUrl = null,
                    isBusy = false,
                    canRetry = false,
                )
            }
            publishTodayUnavailableLocked(state)
            return
        }
        if (previous?.accountId != reference.accountId ||
            previous?.credentialGeneration != reference.credentialGeneration
        ) {
            businessEpoch.incrementAndGet()
            selectionEpoch.incrementAndGet()
            todayReadGeneration.incrementAndGet()
            invalidateCalendarWidgetSnapshotsLocked()
            accountEpochValue += 1
            explicitSelection = false
            lastIndex = null
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
                    interactionUrl = null,
                    isBusy = false,
                    message = null,
                )
            }
            _todayState.value = TimetableTodayUiState(
                accountEpoch = accountEpochValue,
                accountManagedActive = true,
                accountBlocked = false,
                today = todayProvider(),
                todayState = TimetableTodayState.NOT_SYNCED,
            )
        }
        currentBinding = reference
        _state.update {
            it.copy(
                accountStatusLabel = label,
                maskedAccount = state.maskedAccount,
                accountManagedActive = state.isManagedActive,
                accountBlocked = false,
                connection = if (it.weekState == TimetableWeekState.NOT_QUERIED) {
                    TimetableConnection.IDLE
                } else {
                    it.connection
                },
            )
        }
        _todayState.update {
            it.copy(
                accountEpoch = accountEpochValue,
                accountManagedActive = state.isManagedActive,
                accountBlocked = false,
            )
        }
    }

    private suspend fun publishAccountState() {
        commitMutex.withLock { applyAccountStateLocked(account.state.value) }
    }

    private suspend fun publishTodayAccountState() {
        commitMutex.withLock {
            if (bindingOrNull() != null) return@withLock
            publishTodayUnavailableLocked(account.state.value)
        }
    }

    private fun publishTodayUnavailableLocked(state: SchoolAccountState) {
        todayReadGeneration.incrementAndGet()
        val todayState = when {
            state.status == SchoolAccountStatus.LOADING ->
                TimetableTodayState.ACCOUNT_RESTORING
            state.blocksPersonalSessions -> TimetableTodayState.ACCOUNT_BLOCKED
            else -> TimetableTodayState.ACCOUNT_REQUIRED
        }
        val message = when {
            state.status == SchoolAccountStatus.LOADING ->
                "正在恢复校方托管账号。"
            state.cleanupRequired && state.errorCode in setOf(
                SchoolAccountErrorCode.CLEANUP_FAILED,
                SchoolAccountErrorCode.CLEANUP_REQUIRED,
            ) -> "账号清理未完成，今日课程已隐藏。"
            state.blocksPersonalSessions ->
                "学校账号正在清理或切换，今日课程暂不可用。"
            state.status == SchoolAccountStatus.NEEDS_RECONFIGURATION ->
                "校方托管会话不可用，请重新绑定。"
            else -> "请先绑定校方托管账号。"
        }
        _todayState.value = TimetableTodayUiState(
            accountEpoch = accountEpochValue,
            accountManagedActive = state.isManagedActive,
            accountBlocked = state.blocksPersonalSessions ||
                state.status == SchoolAccountStatus.LOADING,
            today = todayProvider(),
            todayState = todayState,
            todayMessage = message,
        )
    }

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
