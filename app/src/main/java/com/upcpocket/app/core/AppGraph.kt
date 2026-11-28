package com.upcpocket.app.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.upcpocket.app.BuildConfig
import com.upcpocket.app.MainActivity
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.campusbackend.BackendOriginPolicy
import com.upcpocket.app.campusbackend.BackendErrorCode
import com.upcpocket.app.campusbackend.BackendFailure
import com.upcpocket.app.campusbackend.BackendResult
import com.upcpocket.app.campusbackend.CampusBackendClient
import com.upcpocket.app.campusbackend.CampusDeviceKind
import com.upcpocket.app.data.chaoxing.ChaoxingWebCookies
import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountInvalidationFailure
import com.upcpocket.app.account.api.AccountInvalidationRequest
import com.upcpocket.app.account.api.AccountInvalidationResult
import com.upcpocket.app.account.api.SchoolAccountInvalidator
import com.upcpocket.app.campusmap.CampusMapCapabilities
import com.upcpocket.app.campusmap.CampusMapPlugin
import com.upcpocket.app.campusmap.CampusId
import com.upcpocket.app.card.CardBalanceService
import com.upcpocket.app.data.cardrecharge.CardRechargeService
import com.upcpocket.app.card.CardCapabilities
import com.upcpocket.app.card.CardPlugin
import com.upcpocket.app.card.CardBackendBalance
import com.upcpocket.app.card.CardBackendDataSource
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.card.CardBackendTransaction
import com.upcpocket.app.card.CardBackendTransactionPage
import com.upcpocket.app.card.CardBackendTransactionsSummary
import com.upcpocket.app.classroom.ClassroomPlugin
import com.upcpocket.app.data.classroom.DataStoreFreeClassroomCampusStore
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.data.repo.PowerInputRepository
import com.upcpocket.app.diagnostics.DiagnosticStore
import com.upcpocket.app.directory.DirectoryCampus
import com.upcpocket.app.directory.DirectoryCampusMap
import com.upcpocket.app.directory.DirectoryPlugin
import com.upcpocket.app.floorplan.FloorPlanPlugin
import com.upcpocket.app.platform.diagnostics.Diagnostics
import com.upcpocket.app.focus.FocusPlugin
import com.upcpocket.app.grades.GradesCapabilities
import com.upcpocket.app.grades.GradesFiles
import com.upcpocket.app.grades.GradesPlugin
import com.upcpocket.app.grades.GradesBackendSource
import com.upcpocket.app.grades.GradesBackendFailure
import com.upcpocket.app.grades.GradesBackendFailureCode
import com.upcpocket.app.grades.GradesBackendTerm
import com.upcpocket.app.grades.GradesBackendTerms
import com.upcpocket.app.grades.GradesBackendCourse
import com.upcpocket.app.grades.GradesBackendCourses
import com.upcpocket.app.data.library.LibraryUser
import com.upcpocket.app.library.ChaoxingAccountAuthResult
import com.upcpocket.app.library.ChaoxingAccountAuthenticator
import com.upcpocket.app.library.ChaoxingAuthFailure
import com.upcpocket.app.library.LibraryAccountAuthFailure
import com.upcpocket.app.library.LibraryAccountAuthResult
import com.upcpocket.app.library.LibraryAccountAuthenticator
import com.upcpocket.app.library.LibraryCapabilities
import com.upcpocket.app.library.LibraryPlugin
import com.upcpocket.app.library.SchoolWebCookies
import com.upcpocket.app.plugin.api.PluginAppLifecycle
import com.upcpocket.app.plugin.runtime.ActivePlugins
import com.upcpocket.app.plugin.runtime.PluginDiagnostic
import com.upcpocket.app.plugin.runtime.PluginRuntime
import com.upcpocket.app.plugin.runtime.PluginStartResult
import com.upcpocket.app.plugin.ui.PluginUiSnapshot
import com.upcpocket.app.power.PowerCapabilities
import com.upcpocket.app.power.PowerPlugin
import com.upcpocket.app.power.PowerService
import com.upcpocket.app.timetable.TimetableCapabilities
import com.upcpocket.app.timetable.TimetableCampusMap
import com.upcpocket.app.timetable.TimetableFiles
import com.upcpocket.app.timetable.TimetablePlugin
import com.upcpocket.app.timetable.TimetableService
import com.upcpocket.app.timetable.TimetableBackendSource
import com.upcpocket.app.timetable.TimetableBackendFailure
import com.upcpocket.app.timetable.TimetableBackendFailureCode
import com.upcpocket.app.timetable.TimetableBackendIndex
import com.upcpocket.app.timetable.TimetableBackendTerm
import com.upcpocket.app.timetable.TimetableBackendWeek
import com.upcpocket.app.timetable.TimetableBackendMeeting
import com.upcpocket.app.todo.PersonalScheduleStore
import com.upcpocket.app.todo.TodoCapabilities
import com.upcpocket.app.todo.TodoLaunchContract
import com.upcpocket.app.todo.TodoPlugin
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.update.UpdateController
import com.upcpocket.app.update.UpdateControllerFactory
import com.upcpocket.app.ui.official.OfficialWebCatalog
import com.upcpocket.app.upcauth.UpcChaoxingAuthFailure
import com.upcpocket.app.upcauth.UpcChaoxingAuthResult
import com.upcpocket.app.upcauth.UpcChaoxingAuthenticator
import com.upcpocket.app.upcauth.UpcLibraryAuthFailure
import com.upcpocket.app.upcauth.UpcLibraryAuthResult
import com.upcpocket.app.upcauth.UpcLibraryAuthenticator
import com.upcpocket.app.upcauth.UpcShiguangAuthenticator
import com.upcpocket.app.shiguang.ShiguangPlugin
import com.upcpocket.app.shiguang.ShiguangCapabilities
import com.upcpocket.app.upcauth.UpcServicePortalAuthenticator
import com.upcpocket.app.serviceportal.ServicePortalCapabilities
import com.upcpocket.app.serviceportal.ServicePortalPlugin
import com.upcpocket.app.widget.WidgetKind
import com.upcpocket.app.widget.WidgetAutoRefresh
import com.upcpocket.app.widget.WidgetStateStore
import com.upcpocket.app.widget.WidgetUpdates
import com.upcpocket.app.widget.WidgetWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.LocalDate
import java.time.YearMonth

/**
 * 宿主手动装配容器。
 *
 * 只保留插件注册、宿主偏好/输入适配器、组件回调与稳定能力解析；业务协议、页面、
 * Room/DataStore 与计时引擎分别归各自 feature 插件。校园能力仍是：
 *  - 专注：数据库、偏好、前台计时；
 *  - 空闲教室：公开查询；
 *  - 图书馆座位：学校官方 CAS 登录后的内存会话；
 *  - 校园卡：由校方托管会话经后端读取余额与只读流水；
 *  - 课表和成绩：由校方托管会话经后端读取，只在本机保留受控账号缓存；
 *  - 唐岛湾宿舍剩余电量：无需登录的只读页面查询。
 *
 * 旧课表编辑/导入/旧学校同步与桌面组件已整体删除；M3 新增的是只读课表插件，
 * 不读写 Room 五张旧表。应用启动只恢复账号并让插件做轻量状态恢复，不遍历整学期、
 * 不写种子数据、不发起周期性网络请求。
 */
object AppGraph {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 应用内更新只由宿主拥有：网络、清单验签、下载和系统安装都不经过校园插件。
     * 构造本身不联网；前台每天最多检查一次元数据，手动检查/下载由关于页触发。
     */
    val updates: UpdateController by lazy {
        UpdateControllerFactory.create(appContext, updateScope)
    }

    fun onAppForeground() {
        updates.onForeground()
    }

    val uiPrefsStore: UiPrefsStore by lazy { UiPrefsStore(appContext) }

    val customBackgroundStore: com.upcpocket.app.data.prefs.CustomBackgroundStore by lazy {
        com.upcpocket.app.data.prefs.CustomBackgroundStore(appContext)
    }

    /**
     * 本机诊断存储：只在私有 noBackup 目录滚动保存固定码事件，由用户在“关于”
     * 主动导出。它不参与业务事务，任何失败都必须被吞掉。
     */
    val diagnostics: DiagnosticStore by lazy { DiagnosticStore(appContext) }

    /** Construction is local only; a blank Release origin disables all campus requests. */
    val campusBackendOrigin: String?
        get() = BuildConfig.CAMPUS_BACKEND_ORIGIN.takeIf { it.isNotBlank() }

    val campusBackendClient: CampusBackendClient by lazy {
        CampusBackendClient.create(
            origin = campusBackendOrigin,
            policy = if (BuildConfig.DEBUG) BackendOriginPolicy.Debug
            else BackendOriginPolicy.Release(approvedHttpsOrigins = emptySet()),
        )
    }

    fun installDiagnostics() {
        Diagnostics.install(diagnostics)
    }

    /**
     * 统一学校账号是共享核心能力，不属于某个业务插件。
     *
     * 账号层只持有密文与代次；换号/删号通过下面的失效器清理图书馆会话、
     * WebView 学校 Cookie 与校园卡个人缓存。恢复在 Application 启动时执行，
     * 先完成半途清理，再恢复业务插件。
     */
    val account: SchoolAccountRepository by lazy {
        SchoolAccountRepository(
            context = appContext,
            backendClient = campusBackendClient,
            backendOrigin = campusBackendOrigin,
            invalidators = listOf(
                SchoolAccountInvalidator { request -> invalidateSchoolAccountData(request) },
            ),
            onSessionClosed = {
                runCatching { WidgetWork.cancel(appContext, WidgetKind.CARD) }
                runCatching { WidgetAutoRefresh.cancel(appContext, WidgetKind.CARD) }
                // Fence account state is already closed when this callback runs. Publish
                // placeholder RemoteViews synchronously before cache invalidation continues.
                runCatching {
                    WidgetUpdates.updateKindNow(
                        appContext,
                        WidgetKind.CARD,
                        scheduleTicker = false,
                    )
                }
                runCatching {
                    WidgetUpdates.updateKindNow(
                        appContext,
                        WidgetKind.TIMETABLE,
                        scheduleTicker = false,
                    )
                }
                runCatching { WidgetAutoRefresh.reconcile(appContext) }
            },
            deviceKindProvider = {
                if (appContext.resources.configuration.smallestScreenWidthDp >= 600) {
                    CampusDeviceKind.TABLET
                } else {
                    CampusDeviceKind.PHONE
                }
            },
        )
    }

    fun hasUsableManagedSession(): Boolean = account.hasUsableManagedSession()

    fun installationIdForWidgetCadence(): String? = account.installationId()

    /** Feature-scoped read sources keep Pocket bearer tokens inside the host adapter. */
    private val timetableBackendSource: TimetableBackendSource by lazy {
        object : TimetableBackendSource {
            override suspend fun index(): TimetableBackendIndex = campusRead(
                unavailable = { TimetableBackendFailure(TimetableBackendFailureCode.SERVICE_NOT_READY) },
                stale = { TimetableBackendFailure(TimetableBackendFailureCode.NETWORK_UNAVAILABLE) },
                mapFailure = { it.toTimetableFailure() },
                request = campusBackendClient::getTimetableIndex,
            ).let { response ->
                try {
                    TimetableBackendIndex(
                        terms = response.terms.map {
                            TimetableBackendTerm(
                                year = it.year,
                                term = it.term,
                                label = it.label,
                                startDay = LocalDate.parse(it.startDay),
                                totalWeeks = it.totalWeeks,
                            )
                        },
                        currentYear = response.currentYear,
                        currentTerm = response.currentTerm,
                        currentWeek = response.currentWeek,
                        weekDates = response.weekDates?.map(LocalDate::parse),
                        compatibilityNote = response.compatibilityNote,
                    )
                } catch (_: Exception) {
                    throw TimetableBackendFailure(TimetableBackendFailureCode.UPSTREAM_PROTOCOL_ERROR)
                }
            }

            override suspend fun week(year: String, term: String, week: Int): TimetableBackendWeek =
                campusRead(
                    unavailable = { TimetableBackendFailure(TimetableBackendFailureCode.SERVICE_NOT_READY) },
                    stale = { TimetableBackendFailure(TimetableBackendFailureCode.NETWORK_UNAVAILABLE) },
                    mapFailure = { it.toTimetableFailure() },
                    request = { token -> campusBackendClient.getTimetableWeek(token, year, term, week) },
                ).let { response ->
                    try {
                        TimetableBackendWeek(
                            year = response.year,
                            term = response.term,
                            week = response.week,
                            weekDates = response.weekDates.map(LocalDate::parse),
                            meetings = response.meetings.map {
                                TimetableBackendMeeting(
                                    courseName = it.courseName,
                                    date = LocalDate.parse(it.date),
                                    dayOfWeek = it.dayOfWeek,
                                    startPeriod = it.startPeriod,
                                    endPeriod = it.endPeriod,
                                    rawPeriod = it.rawPeriod,
                                    teacher = it.teacher,
                                    location = it.location,
                                    weeksLabel = it.weeksLabel,
                                    courseCode = it.courseCode,
                                    classGroup = it.classGroup,
                                )
                            },
                            complete = response.complete,
                            fetchedAtEpochMillis = response.fetchedAtEpochMillis,
                        )
                    } catch (_: Exception) {
                        throw TimetableBackendFailure(TimetableBackendFailureCode.UPSTREAM_PROTOCOL_ERROR)
                    }
                }
        }
    }

    private val gradesBackendSource: GradesBackendSource by lazy {
        object : GradesBackendSource {
            override suspend fun terms(): GradesBackendTerms = campusRead(
                unavailable = { GradesBackendFailure(GradesBackendFailureCode.SERVICE_NOT_READY) },
                stale = { GradesBackendFailure(GradesBackendFailureCode.NETWORK_UNAVAILABLE) },
                mapFailure = { it.toGradesFailure() },
                request = campusBackendClient::getGradesTerms,
            ).let { response ->
                GradesBackendTerms(
                    terms = response.terms.map { GradesBackendTerm(it.year, it.term) },
                    currentTerm = response.currentTerm?.let { GradesBackendTerm(it.year, it.term) },
                    complete = true,
                )
            }

            override suspend fun courses(year: String, term: String): GradesBackendCourses = campusRead(
                unavailable = { GradesBackendFailure(GradesBackendFailureCode.SERVICE_NOT_READY) },
                stale = { GradesBackendFailure(GradesBackendFailureCode.NETWORK_UNAVAILABLE) },
                mapFailure = { it.toGradesFailure() },
                request = { token -> campusBackendClient.getGradesCourses(token, year, term) },
            ).let { response ->
                GradesBackendCourses(
                    courses = response.courses.map {
                        GradesBackendCourse(
                            courseName = it.courseName,
                            score = it.scoreText,
                            credits = it.creditsText?.toDoubleOrNull(),
                            courseType = it.courseType,
                            examType = it.examType,
                        )
                    },
                    complete = response.complete,
                    fetchedAtEpochMillis = response.fetchedAtEpochMillis,
                )
            }
        }
    }

    private val cardBackendSource: CardBackendDataSource by lazy {
        object : CardBackendDataSource {
            override suspend fun balance(): CardBackendBalance = campusRead(
                unavailable = { CardBackendException(CardBackendFailureCode.BACKEND_NOT_READY) },
                stale = { CardBackendException(CardBackendFailureCode.NETWORK_UNAVAILABLE) },
                mapFailure = { it.toCardBackendException() },
                request = campusBackendClient::getCardBalance,
            ).let {
                CardBackendBalance(it.balanceCents, it.transitionCents, it.fetchedAtEpochMillis)
            }

            override suspend fun transactions(
                month: YearMonth?,
                page: Int,
                size: Int,
            ): CardBackendTransactionPage = campusRead(
                unavailable = { CardBackendException(CardBackendFailureCode.BACKEND_NOT_READY) },
                stale = { CardBackendException(CardBackendFailureCode.NETWORK_UNAVAILABLE) },
                mapFailure = { it.toCardBackendException() },
                request = { token ->
                    campusBackendClient.getCardTransactions(token, month?.toString(), page, size)
                },
            ).let { response ->
                if (response.total !in 0..Int.MAX_VALUE.toLong() || response.pages !in 0..Int.MAX_VALUE) {
                    throw CardBackendException(CardBackendFailureCode.UPSTREAM_PROTOCOL_ERROR)
                }
                CardBackendTransactionPage(
                    page = response.page,
                    size = response.size,
                    total = response.total.toInt(),
                    pages = response.pages,
                    rows = response.rows.map(::toCardTransaction),
                )
            }

            override suspend fun transactionsSummary(
                month: YearMonth?,
            ): CardBackendTransactionsSummary = campusRead(
                unavailable = { CardBackendException(CardBackendFailureCode.BACKEND_NOT_READY) },
                stale = { CardBackendException(CardBackendFailureCode.NETWORK_UNAVAILABLE) },
                mapFailure = { it.toCardBackendException() },
                request = { token ->
                    campusBackendClient.getCardTransactionsSummary(token, month?.toString())
                },
            ).let { CardBackendTransactionsSummary(it.incomeFen, it.expenseFen) }
        }
    }

    private fun toCardTransaction(value: com.upcpocket.app.campusbackend.CampusCardTransactionResponse) =
        CardBackendTransaction(value.rowKey, value.title, value.time, value.amountFen)

    private suspend fun <T> campusRead(
        unavailable: () -> Throwable,
        stale: () -> Throwable,
        mapFailure: (BackendFailure) -> Throwable,
        request: suspend (String) -> BackendResult<T>,
    ): T {
        val lease = account.captureSessionLease() ?: throw unavailable()
        return when (val result = request(lease.accessToken)) {
            is BackendResult.Success -> {
                if (!account.isSessionLeaseCurrent(lease)) throw stale()
                result.value
            }
            BackendResult.NoContent -> throw mapFailure(
                BackendFailure(code = BackendErrorCode.UPSTREAM_PROTOCOL_ERROR),
            )
            is BackendResult.Failure -> {
                if (result.error.code in setOf(
                        BackendErrorCode.TOKEN_INVALID,
                        BackendErrorCode.UNAUTHENTICATED,
                        BackendErrorCode.DISABLED,
                    )
                ) {
                    account.invalidateSessionIfCurrent(lease)
                }
                throw mapFailure(result.error)
            }
        }
    }

    private fun BackendFailure.toCardBackendException() = CardBackendException(
        code = when (code) {
            BackendErrorCode.NOT_CONFIGURED, BackendErrorCode.INVALID_ORIGIN -> CardBackendFailureCode.BACKEND_NOT_READY
            BackendErrorCode.TOKEN_INVALID -> CardBackendFailureCode.TOKEN_INVALID
            BackendErrorCode.UNAUTHENTICATED -> CardBackendFailureCode.UNAUTHENTICATED
            BackendErrorCode.DISABLED -> CardBackendFailureCode.DISABLED
            BackendErrorCode.SIGN_IN_FAILED -> CardBackendFailureCode.SIGN_IN_FAILED
            BackendErrorCode.INTERACTIVE_CHALLENGE -> CardBackendFailureCode.INTERACTIVE_CHALLENGE
            BackendErrorCode.CREDENTIAL_UPDATE_REQUIRED -> CardBackendFailureCode.CREDENTIAL_UPDATE_REQUIRED
            BackendErrorCode.VERIFICATION_BUSY -> CardBackendFailureCode.VERIFICATION_BUSY
            BackendErrorCode.MODULE_DISABLED -> CardBackendFailureCode.MODULE_DISABLED
            BackendErrorCode.CIRCUIT_OPEN -> CardBackendFailureCode.CIRCUIT_OPEN
            BackendErrorCode.UPSTREAM_UNAVAILABLE -> CardBackendFailureCode.UPSTREAM_UNAVAILABLE
            BackendErrorCode.DEPENDENCY_UNAVAILABLE -> CardBackendFailureCode.DEPENDENCY_UNAVAILABLE
            BackendErrorCode.UPSTREAM_TIMEOUT, BackendErrorCode.NETWORK_TIMEOUT -> CardBackendFailureCode.UPSTREAM_TIMEOUT
            BackendErrorCode.VALIDATION_FAILED -> CardBackendFailureCode.VALIDATION_FAILED
            BackendErrorCode.IDENTITY_UNSUPPORTED -> CardBackendFailureCode.IDENTITY_UNSUPPORTED
            BackendErrorCode.UPSTREAM_PROTOCOL_ERROR -> CardBackendFailureCode.UPSTREAM_PROTOCOL_ERROR
            BackendErrorCode.RATE_LIMITED -> CardBackendFailureCode.RATE_LIMITED
            BackendErrorCode.NETWORK_UNAVAILABLE -> CardBackendFailureCode.NETWORK_UNAVAILABLE
            BackendErrorCode.TLS_ERROR, BackendErrorCode.REDIRECT_REJECTED -> CardBackendFailureCode.TLS_ERROR
            else -> CardBackendFailureCode.UNKNOWN
        },
        retryAfterMillis = retryAfterMillis,
    )

    private fun BackendFailure.toTimetableFailure() = TimetableBackendFailure(
        code = when (code) {
            BackendErrorCode.NOT_CONFIGURED, BackendErrorCode.INVALID_ORIGIN -> TimetableBackendFailureCode.SERVICE_NOT_READY
            BackendErrorCode.TOKEN_INVALID -> TimetableBackendFailureCode.TOKEN_INVALID
            BackendErrorCode.UNAUTHENTICATED -> TimetableBackendFailureCode.UNAUTHENTICATED
            BackendErrorCode.DISABLED -> TimetableBackendFailureCode.DISABLED
            BackendErrorCode.INTERACTIVE_CHALLENGE -> TimetableBackendFailureCode.INTERACTIVE_CHALLENGE
            BackendErrorCode.CREDENTIAL_UPDATE_REQUIRED -> TimetableBackendFailureCode.CREDENTIAL_UPDATE_REQUIRED
            BackendErrorCode.VERIFICATION_BUSY -> TimetableBackendFailureCode.VERIFICATION_BUSY
            BackendErrorCode.MODULE_DISABLED -> TimetableBackendFailureCode.MODULE_DISABLED
            BackendErrorCode.CIRCUIT_OPEN -> TimetableBackendFailureCode.CIRCUIT_OPEN
            BackendErrorCode.UPSTREAM_UNAVAILABLE -> TimetableBackendFailureCode.UPSTREAM_UNAVAILABLE
            BackendErrorCode.UPSTREAM_TIMEOUT, BackendErrorCode.NETWORK_TIMEOUT -> TimetableBackendFailureCode.UPSTREAM_TIMEOUT
            BackendErrorCode.DEPENDENCY_UNAVAILABLE -> TimetableBackendFailureCode.DEPENDENCY_UNAVAILABLE
            BackendErrorCode.VALIDATION_FAILED -> TimetableBackendFailureCode.VALIDATION_FAILED
            BackendErrorCode.IDENTITY_UNSUPPORTED -> TimetableBackendFailureCode.IDENTITY_UNSUPPORTED
            BackendErrorCode.UPSTREAM_PROTOCOL_ERROR -> TimetableBackendFailureCode.UPSTREAM_PROTOCOL_ERROR
            BackendErrorCode.RATE_LIMITED -> TimetableBackendFailureCode.RATE_LIMITED
            BackendErrorCode.NETWORK_UNAVAILABLE, BackendErrorCode.TLS_ERROR, BackendErrorCode.REDIRECT_REJECTED ->
                TimetableBackendFailureCode.NETWORK_UNAVAILABLE
            else -> TimetableBackendFailureCode.UPSTREAM_UNAVAILABLE
        },
        retryAfterSeconds = retryAfterMillis?.let { (it + 999L) / 1_000L },
    )

    private fun BackendFailure.toGradesFailure() = GradesBackendFailure(
        code = when (code) {
            BackendErrorCode.NOT_CONFIGURED, BackendErrorCode.INVALID_ORIGIN -> GradesBackendFailureCode.SERVICE_NOT_READY
            BackendErrorCode.TOKEN_INVALID -> GradesBackendFailureCode.TOKEN_INVALID
            BackendErrorCode.UNAUTHENTICATED -> GradesBackendFailureCode.UNAUTHENTICATED
            BackendErrorCode.DISABLED -> GradesBackendFailureCode.DISABLED
            BackendErrorCode.INTERACTIVE_CHALLENGE -> GradesBackendFailureCode.INTERACTIVE_CHALLENGE
            BackendErrorCode.CREDENTIAL_UPDATE_REQUIRED -> GradesBackendFailureCode.CREDENTIAL_UPDATE_REQUIRED
            BackendErrorCode.VERIFICATION_BUSY -> GradesBackendFailureCode.VERIFICATION_BUSY
            BackendErrorCode.MODULE_DISABLED -> GradesBackendFailureCode.MODULE_DISABLED
            BackendErrorCode.CIRCUIT_OPEN -> GradesBackendFailureCode.CIRCUIT_OPEN
            BackendErrorCode.UPSTREAM_UNAVAILABLE -> GradesBackendFailureCode.UPSTREAM_UNAVAILABLE
            BackendErrorCode.UPSTREAM_TIMEOUT, BackendErrorCode.NETWORK_TIMEOUT -> GradesBackendFailureCode.UPSTREAM_TIMEOUT
            BackendErrorCode.DEPENDENCY_UNAVAILABLE -> GradesBackendFailureCode.DEPENDENCY_UNAVAILABLE
            BackendErrorCode.VALIDATION_FAILED -> GradesBackendFailureCode.VALIDATION_FAILED
            BackendErrorCode.IDENTITY_UNSUPPORTED -> GradesBackendFailureCode.IDENTITY_UNSUPPORTED
            BackendErrorCode.UPSTREAM_PROTOCOL_ERROR -> GradesBackendFailureCode.UPSTREAM_PROTOCOL_ERROR
            BackendErrorCode.RATE_LIMITED -> GradesBackendFailureCode.RATE_LIMITED
            BackendErrorCode.NETWORK_UNAVAILABLE, BackendErrorCode.TLS_ERROR, BackendErrorCode.REDIRECT_REJECTED ->
                GradesBackendFailureCode.NETWORK_UNAVAILABLE
            else -> GradesBackendFailureCode.UPSTREAM_UNAVAILABLE
        },
        retryAfterSeconds = retryAfterMillis?.let { (it + 999L) / 1_000L },
    )

    private suspend fun invalidateSchoolAccountData(
        request: AccountInvalidationRequest,
    ): AccountInvalidationResult {
        // 诊断事件不含 PII，但账号前后操作不应串联；先递增清理代次，
        // 旧队列在写入前被丢弃。清理失败不能阻塞退出。
        runCatching { diagnostics.clearForAccountChange() }
        var failure: AccountInvalidationFailure? = null
        val recharge = activePlugins.capability(CardCapabilities.recharge)
        val rechargeCleared = if (recharge != null) recharge.invalidateForAccountChange(request.scope)
            else request.scope != AccountDataScope.PERSONAL_DATA || CardRechargeService.clearStoredData(appContext.noBackupFilesDir)
        if (!rechargeCleared) failure = AccountInvalidationFailure.PERSONAL_DATA_CLEAR_FAILED
        val shiguangBrowserStopped = activePlugins.capability(ShiguangCapabilities.read)?.invalidateForAccountChange() ?: true
        val servicePortalStopped = activePlugins.capability(ServicePortalCapabilities.read)?.invalidateForAccountChange() ?: true
        val cardBrowserStopped = activePlugins.capability(CardCapabilities.history)
            ?.invalidateForAccountChange() ?: true
        if (!cardBrowserStopped || !shiguangBrowserStopped || !servicePortalStopped) failure = AccountInvalidationFailure.COOKIE_CLEAR_TIMEOUT
        val library = activePlugins.capability(LibraryCapabilities.seat)
        if (!cardBrowserStopped || !shiguangBrowserStopped || !servicePortalStopped) {
            // A browser that could not be stopped must not outlive cookie removal. Keep the
            // account blocked; the invalidation retry will clear shared school cookies later.
        } else if (library != null) {
            if (!library.signOutForAccountChange()) {
                failure = AccountInvalidationFailure.COOKIE_CLEAR_TIMEOUT
            }
        } else if (!SchoolWebCookies.clearAllSchoolCookiesAndAwait()) {
            // 图书馆插件被移除时，旧 WebView Cookie 仍可能留在系统中；这里仍按
            // 学校域名清理并等待，避免换号后自动带回旧身份。
            failure = AccountInvalidationFailure.COOKIE_CLEAR_TIMEOUT
        }
        if (library == null) {
            if (!runCatching { SchoolSessionStore(appContext).clearAll(); true }.getOrDefault(false)) {
                failure = AccountInvalidationFailure.SESSION_CLEAR_FAILED
            }
        }
        val chaoxing = activePlugins.capability(LibraryCapabilities.chaoxing)
        val chaoxingCleared = if (chaoxing != null) {
            chaoxing.signOutForAccountChange()
        } else {
            runCatching { ChaoxingWebCookies.clearAllAndAwait() }.getOrDefault(false)
        }
        if (!chaoxingCleared) {
            failure = AccountInvalidationFailure.COOKIE_CLEAR_TIMEOUT
        }
        val timetable = activePlugins.capability(TimetableCapabilities.week)
        val timetableCleared = if (timetable != null) {
            timetable.invalidateForAccount(request.scope)
        } else if (request.scope == AccountDataScope.PERSONAL_DATA) {
            // 插件被隔离时也必须清掉可恢复的旧个人数据；不能因 UI 不可见而遗留。
            TimetableFiles.clearAll(appContext)
        } else {
            TimetableFiles.clearSession(appContext)
        }
        if (!timetableCleared) {
            failure = AccountInvalidationFailure.PERSONAL_DATA_CLEAR_FAILED
        }
        val grades = activePlugins.capability(GradesCapabilities.read)
        val gradesCleared = grades?.invalidateForAccount(request.scope)
            ?: (request.scope != AccountDataScope.PERSONAL_DATA ||
                GradesFiles.clearAll(appContext.noBackupFilesDir))
        if (!gradesCleared) {
            failure = AccountInvalidationFailure.PERSONAL_DATA_CLEAR_FAILED
        }
        if (request.scope == AccountDataScope.PERSONAL_DATA) {
            // 先取消旧组件的排队任务并清掉旧刷新状态，再删除个人缓存。
            WidgetWork.cancel(appContext, WidgetKind.CARD)
            WidgetStateStore(appContext).clear(WidgetKind.CARD)
            val card = cardServiceOrNull
            if (card != null) {
                if (runCatching { card.clearForAccountChange() }.isFailure) {
                    failure = AccountInvalidationFailure.PERSONAL_DATA_CLEAR_FAILED
                }
            } else {
                // 插件未激活时仍要清掉已安装组件的旧缓存；否则重新启用后会看到旧号数据。
                if (runCatching {
                        DirectCardBalanceStore(appContext.noBackupFilesDir).clearAll()
                    }.isFailure
                ) {
                    failure = AccountInvalidationFailure.PERSONAL_DATA_CLEAR_FAILED
                }
            }
            if (runCatching { uiPrefsStore.clearCardStudentId() }.isFailure) {
                failure = AccountInvalidationFailure.PERSONAL_DATA_CLEAR_FAILED
            }
            WidgetWork.cancel(appContext, WidgetKind.TIMETABLE)
            WidgetStateStore(appContext).clear(WidgetKind.TIMETABLE)
            WidgetUpdates.requestCard(appContext)
            WidgetUpdates.requestTimetable(appContext)
        }
        return failure?.let(AccountInvalidationResult::failure)
            ?: AccountInvalidationResult.Success
    }

    private val cardPlugin: CardPlugin by lazy {
        CardPlugin(
            context = appContext,
            account = account,
            backend = cardBackendSource,
            onOpenOfficialPortal = {
                OfficialWebCatalog.open(appContext, OfficialWebCatalog.digitalUpc)
            },
            onSnapshotChanged = { WidgetUpdates.requestCard(appContext) },
        )
    }

    /**
     * 唐岛湾电量插件。
     *
     * 插件自己持有协议、快照与 Repository；宿主只提供输入持久化和组件刷新回调。
     * 快照文件仍是既有的 `power-reading-v1.json`。
     */
    private val powerPlugin: PowerPlugin by lazy {
        PowerPlugin(
            context = appContext,
            inputStore = powerInputRepository,
            onSnapshotChanged = { WidgetUpdates.requestPower(appContext) },
            preferredCampus = uiPrefsStore.powerCampusPreference(),
        )
    }

    private val classroomPlugin: ClassroomPlugin by lazy {
        ClassroomPlugin(
            campusStore = DataStoreFreeClassroomCampusStore(appContext),
            preferredCampus = uiPrefsStore.classroomPreferredCampus(),
        )
    }

    /**
     * 本机待办与提醒。通知点击只通过固定 Action + 本地 UUID 打开对应条目。
     */
    private val todoPlugin: TodoPlugin by lazy {
        TodoPlugin(
            context = appContext,
            onTasksChanged = { WidgetUpdates.requestTodo(appContext) },
            openAppIntent = { taskId -> todoOpenIntent(taskId) },
        )
    }

    internal fun todoOpenIntent(taskId: String): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = TodoLaunchContract.ACTION_OPEN
            data = Uri.Builder()
                .scheme("upcpocket")
                .authority("todo")
                .appendPath("open")
                .appendPath(taskId)
                .build()
            putExtra(TodoLaunchContract.EXTRA_TASK_ID, taskId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 图书馆认证适配：只有这里通过 core 的受控凭据完成 libseat 正常 CAS。
     * 插件只拿到核验后的 seat token 与本人标识，不依赖 core:account、不读密码。
     */
    private val libraryAuthenticator: LibraryAccountAuthenticator by lazy {
        val core = UpcLibraryAuthenticator(account)
        LibraryAccountAuthenticator { accountId, generation ->
            when (val result = core.authenticate(accountId, generation)) {
                is UpcLibraryAuthResult.Success -> LibraryAccountAuthResult.Success(
                    seatToken = result.seatToken,
                    user = LibraryUser(
                        id = result.user.id,
                        username = result.user.username,
                        name = result.user.name,
                    ),
                )
                is UpcLibraryAuthResult.Failure -> LibraryAccountAuthResult.Failure(
                    reason = result.reason.toFeatureFailure(),
                    interactionUrl = result.interactionUrl,
                )
            }
        }
    }

    /**
     * 超星认证适配：只在这里通过 core 的受控凭据和公共 CAS 引擎取得 office Cookie。
     * 插件只拿到内存 Cookie 头，不依赖 core:account、不读密码。
     */
    private val chaoxingAuthenticator: ChaoxingAccountAuthenticator by lazy {
        val core = UpcChaoxingAuthenticator(account)
        ChaoxingAccountAuthenticator { accountId, generation ->
            when (val result = core.authenticate(accountId, generation)) {
                is UpcChaoxingAuthResult.Success ->
                    ChaoxingAccountAuthResult.Success(result.cookieHeader)
                is UpcChaoxingAuthResult.Failure ->
                    ChaoxingAccountAuthResult.Failure(
                        reason = result.reason.toFeatureFailure(),
                        interactionUrl = result.interactionUrl,
                    )
            }
        }
    }

    /**
     * 图书馆座位插件：内存会话归插件，持久副本用原有 Keystore 加密存储；
     * 宿主只提供“记住登录”偏好、受控认证适配和账号引用。
     */
    private val libraryPlugin: LibraryPlugin by lazy {
        LibraryPlugin(
            context = appContext,
            accountSettings = HostLibraryAccountSettings(uiPrefsStore),
            account = account,
            identityVerifier = account,
            authenticator = libraryAuthenticator,
            chaoxingAuthenticator = chaoxingAuthenticator,
            preferredCampus = uiPrefsStore.libraryCampusPreference(),
        )
    }

    private fun UpcLibraryAuthFailure.toFeatureFailure(): LibraryAccountAuthFailure = when (this) {
        UpcLibraryAuthFailure.INVALID_CREDENTIALS ->
            LibraryAccountAuthFailure.INVALID_CREDENTIALS
        UpcLibraryAuthFailure.INTERACTION_REQUIRED ->
            LibraryAccountAuthFailure.INTERACTION_REQUIRED
        UpcLibraryAuthFailure.NETWORK_UNAVAILABLE ->
            LibraryAccountAuthFailure.NETWORK_UNAVAILABLE
        UpcLibraryAuthFailure.TLS_ERROR -> LibraryAccountAuthFailure.TLS_ERROR
        UpcLibraryAuthFailure.UPSTREAM_ERROR -> LibraryAccountAuthFailure.UPSTREAM_ERROR
        UpcLibraryAuthFailure.PROTOCOL_ERROR -> LibraryAccountAuthFailure.PROTOCOL_ERROR
        UpcLibraryAuthFailure.ACCOUNT_UNAVAILABLE ->
            LibraryAccountAuthFailure.ACCOUNT_UNAVAILABLE
        UpcLibraryAuthFailure.ACCOUNT_CHANGED -> LibraryAccountAuthFailure.ACCOUNT_CHANGED
        UpcLibraryAuthFailure.ACCESS_DENIED -> LibraryAccountAuthFailure.ACCESS_DENIED
        UpcLibraryAuthFailure.IDENTITY_MISMATCH -> LibraryAccountAuthFailure.IDENTITY_MISMATCH
    }

    private fun UpcChaoxingAuthFailure.toFeatureFailure(): ChaoxingAuthFailure = when (this) {
        UpcChaoxingAuthFailure.INVALID_CREDENTIALS -> ChaoxingAuthFailure.INVALID_CREDENTIALS
        UpcChaoxingAuthFailure.INTERACTION_REQUIRED -> ChaoxingAuthFailure.INTERACTION_REQUIRED
        UpcChaoxingAuthFailure.NETWORK_UNAVAILABLE -> ChaoxingAuthFailure.NETWORK_UNAVAILABLE
        UpcChaoxingAuthFailure.TLS_ERROR -> ChaoxingAuthFailure.TLS_ERROR
        UpcChaoxingAuthFailure.UPSTREAM_ERROR -> ChaoxingAuthFailure.UPSTREAM_ERROR
        UpcChaoxingAuthFailure.PROTOCOL_ERROR -> ChaoxingAuthFailure.PROTOCOL_ERROR
        UpcChaoxingAuthFailure.ACCOUNT_UNAVAILABLE -> ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE
        UpcChaoxingAuthFailure.ACCOUNT_CHANGED -> ChaoxingAuthFailure.ACCOUNT_CHANGED
        UpcChaoxingAuthFailure.ACCESS_DENIED -> ChaoxingAuthFailure.ACCESS_DENIED
    }

    /**
     * 专注插件：数据库、偏好快照、计时引擎与前台服务都在 `feature:focus`。
     * 宿主只提供“打开应用”的 PendingIntent，避免业务模块反向引用 MainActivity。
     */
    private val focusPlugin: FocusPlugin by lazy {
        FocusPlugin(
            context = appContext,
            openAppIntent = { openAppPendingIntent() },
        )
    }

    private val timetablePlugin: TimetablePlugin by lazy {
        TimetablePlugin(
            context = appContext,
            account = account,
            backend = timetableBackendSource,
            resolveCampusMap = {
                activePlugins.capability(CampusMapCapabilities.PLACE_REQUESTS)?.let { requests ->
                    TimetableCampusMap { location ->
                        requests.offer(location)
                        CampusMapPlugin.ROUTE
                    }
                }
            },
        )
    }

    private val shiguangPlugin: ShiguangPlugin by lazy {
        val authenticator = UpcShiguangAuthenticator(account)
        ShiguangPlugin(account = account, authenticator = authenticator, interactiveAuthenticator = authenticator)
    }

    private val gradesPlugin: GradesPlugin by lazy {
        GradesPlugin(appContext, account, gradesBackendSource) { context ->
            OfficialWebCatalog.open(context, OfficialWebCatalog.academicScores)
        }
    }

    private val servicePortalPlugin: ServicePortalPlugin by lazy {
        ServicePortalPlugin(account = account, authenticator = UpcServicePortalAuthenticator(account))
    }

    private val campusMapPlugin: CampusMapPlugin by lazy {
        CampusMapPlugin(initialCampus = { uiPrefsStore.initialCampusMapCampus() })
    }

    private val directoryPlugin: DirectoryPlugin by lazy {
        DirectoryPlugin(
            context = appContext,
            initialCampus = { uiPrefsStore.initialDirectoryCampus() },
            resolveCampusMap = {
                activePlugins.capability(CampusMapCapabilities.PLACE_REQUESTS)?.let { requests ->
                    DirectoryCampusMap { target ->
                        val campus = when (target.campus) {
                            DirectoryCampus.TANGDAOWAN -> CampusId.TDW
                            DirectoryCampus.GUZHENKOU -> CampusId.GZK
                        }
                        requests.offer(campus, target.placeId)
                        CampusMapPlugin.ROUTE
                    }
                }
            },
        )
    }

    private val floorPlanPlugin: FloorPlanPlugin by lazy {
        FloorPlanPlugin(appContext)
    }

    private val pluginRuntime: PluginRuntime by lazy {
        PluginRuntime().also { runtime ->
            runtime.register(cardPlugin.descriptor) { cardPlugin }
            runtime.register(powerPlugin.descriptor) { powerPlugin }
            runtime.register(classroomPlugin.descriptor) { classroomPlugin }
            runtime.register(libraryPlugin.descriptor) { libraryPlugin }
            runtime.register(focusPlugin.descriptor) { focusPlugin }
            runtime.register(todoPlugin.descriptor) { todoPlugin }
            runtime.register(timetablePlugin.descriptor) { timetablePlugin }
            runtime.register(gradesPlugin.descriptor) { gradesPlugin }
            runtime.register(shiguangPlugin.descriptor) { shiguangPlugin }
            runtime.register(servicePortalPlugin.descriptor) { servicePortalPlugin }
            runtime.register(campusMapPlugin.descriptor) { campusMapPlugin }
            runtime.register(directoryPlugin.descriptor) { directoryPlugin }
            runtime.register(floorPlanPlugin.descriptor) { floorPlanPlugin }
        }
    }

    /**
     * 插件启动结果。失败插件及其传递依赖不会进入 [ActivePlugins]；独立插件仍会启动。
     * 开发构建的 fail-fast 与发行构建的降级由 [PluginStartupPolicy] 决定。
     */
    private val started: PluginStartResult.Started by lazy {
        pluginRuntime.start()
    }

    /**
     * UI 解析可能把贡献失败的插件（及其传递必需依赖）从活动集合中裁剪；
     * 宿主、生命周期和系统组件能力都以这里的最终集合为准。
     */
    private val uiResolution: HostUiResolution by lazy {
        HostUiAssembly.resolve(started.plugins)
    }

    val activePlugins: ActivePlugins get() = uiResolution.activePlugins

    val pluginDiagnostics: List<PluginDiagnostic> get() = started.diagnostics

    /**
     * 已激活插件贡献的页面、首页入口、底部 tab 和设置区。
     *
     * 每个插件的贡献在独立 fork 中校验后原子合并；失败插件整份 UI 被丢弃，
     * 不残留半套入口，也不会阻塞其他插件。
     */
    private val uiAssembly: HostUiAssemblyResult get() = uiResolution.assembly

    val ui: PluginUiSnapshot get() = uiAssembly.snapshot

    internal val uiDiagnostics: List<HostUiDiagnostic> get() = uiAssembly.diagnostics

    /**
     * 组件的可选能力。插件未激活时返回 null，Provider/Worker 必须走“不可用”
     * 或空状态路径，不能直接调用能力 getter 抛错。
     */
    val cardServiceOrNull: CardBalanceService?
        get() = activePlugins.capability(CardCapabilities.balance)

    /**
     * 账号状态变化后只做余额缓存归属迁移与组件重绘，不联网。
     * 无绑定/清理中时服务自身会拒绝读取旧目标。
     */
    suspend fun prepareCardForAccount() {
        cardServiceOrNull?.prepare()
    }

    fun requestCardWidgetUpdate() {
        WidgetUpdates.requestCard(appContext)
    }

    fun requestTimetableWidgetUpdate() {
        WidgetUpdates.requestTimetable(appContext)
    }

    val powerServiceOrNull: PowerService?
        get() = activePlugins.capability(PowerCapabilities.tangdaowan)

    /** 课表组件只读本地缓存；插件未激活时返回 null，组件显示未同步而不是伪造空课。 */
    val timetableServiceOrNull: TimetableService?
        get() = activePlugins.capability(TimetableCapabilities.week)

    /** 通知点击/待办页共享的本地数据源；插件未激活时不打开任何任务。 */
    val todoStoreOrNull: TodoStore?
        get() = activePlugins.capability(TodoCapabilities.store)

    /** 个人日程仅由待办插件提供；缺失时课表与首页不显示本机日程入口。 */
    val personalScheduleStoreOrNull: PersonalScheduleStore?
        get() = activePlugins.capability(TodoCapabilities.personalSchedule)

    /**
     * 启动装配；由 Application 在设置 Context 后调用。
     *
     * 开发/测试构建遇到任何插件或 UI 失败直接抛错；发行构建记录脱敏诊断后，
     * 继续使用健康插件。这样可选 feature 的缺依赖/API 不兼容/工厂失败不会让
     * 整个 App 无法启动。
     */
    fun startPlugins() {
        val hasFailures = pluginDiagnostics.isNotEmpty() || uiDiagnostics.isNotEmpty()
        val summary = PluginStartupPolicy.summary(pluginDiagnostics, uiDiagnostics)
        if (hasFailures) {
            Log.w(TAG, "插件降级：$summary")
            if (PluginStartupPolicy.shouldFailFast(BuildConfig.DEBUG, hasFailures = true)) {
                error(PluginStartupPolicy.failFastMessage(summary))
            }
        }
    }

    /** 通知已激活插件做轻量 Application 初始化；失败只影响该插件。 */
    fun notifyPluginsCreated() {
        activePlugins.ordered.forEach { plugin ->
            val lifecycle = plugin as? PluginAppLifecycle ?: return@forEach
            runCatching { lifecycle.onAppCreated() }.onFailure {
                Log.w(TAG, "插件初始化降级：plugin:${plugin.descriptor.id}:LIFECYCLE_CREATED_FAILED")
            }
        }
    }

    /** 恢复已激活插件的进程状态；未激活的插件不会收到回调。 */
    suspend fun restorePlugins() {
        activePlugins.ordered.forEach { plugin ->
            val lifecycle = plugin as? PluginAppLifecycle ?: return@forEach
            runCatching { lifecycle.onAppStart() }.onFailure {
                Log.w(TAG, "插件恢复降级：plugin:${plugin.descriptor.id}:LIFECYCLE_START_FAILED")
            }
        }
    }

    /** 电费输入由宿主偏好拥有；校园卡旧学号只在升级/解绑清理时直接删除。 */
    val powerInputRepository: PowerInputRepository by lazy {
        PowerInputRepository(uiPrefsStore)
    }

    /**
     * 通知点击打开应用。请求码仍为 0，flags 与迁移前一致；MainActivity 的解析逻辑不变。
     */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val TAG = "PluginRuntime"
}
