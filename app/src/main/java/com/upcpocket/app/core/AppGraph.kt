package com.upcpocket.app.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.upcpocket.app.BuildConfig
import com.upcpocket.app.MainActivity
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountInvalidationFailure
import com.upcpocket.app.account.api.AccountInvalidationRequest
import com.upcpocket.app.account.api.AccountInvalidationResult
import com.upcpocket.app.account.api.SchoolAccountInvalidator
import com.upcpocket.app.card.CardBalanceService
import com.upcpocket.app.card.CardCapabilities
import com.upcpocket.app.card.CardPlugin
import com.upcpocket.app.classroom.ClassroomPlugin
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.data.repo.CardInputRepository
import com.upcpocket.app.data.repo.PowerInputRepository
import com.upcpocket.app.focus.FocusPlugin
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
import com.upcpocket.app.timetable.TimetableFiles
import com.upcpocket.app.timetable.TimetablePlugin
import com.upcpocket.app.timetable.TimetableService
import com.upcpocket.app.upcauth.UpcChaoxingAuthFailure
import com.upcpocket.app.upcauth.UpcChaoxingAuthResult
import com.upcpocket.app.upcauth.UpcChaoxingAuthenticator
import com.upcpocket.app.upcauth.UpcLibraryAuthFailure
import com.upcpocket.app.upcauth.UpcLibraryAuthResult
import com.upcpocket.app.upcauth.UpcLibraryAuthenticator
import com.upcpocket.app.upcauth.UpcTimetableAuthenticator
import com.upcpocket.app.widget.WidgetKind
import com.upcpocket.app.widget.WidgetStateStore
import com.upcpocket.app.widget.WidgetUpdates
import com.upcpocket.app.widget.WidgetWork

/**
 * 宿主手动装配容器。
 *
 * 只保留插件注册、宿主偏好/输入适配器、组件回调与稳定能力解析；业务协议、页面、
 * Room/DataStore 与计时引擎分别归各自 feature 插件。校园能力仍是：
 *  - 专注：数据库、偏好、前台计时；
 *  - 空闲教室：公开查询；
 *  - 图书馆座位：学校官方 CAS 登录后的内存会话；
 *  - 校园卡余额：按当前统一账号受控学号直接读取学校余额接口，不需要平台登录；
 *  - 课表：正常 CAS 建立的独立只读业务会话与账号归属周缓存；
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

    val uiPrefsStore: UiPrefsStore by lazy { UiPrefsStore(appContext) }

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
            invalidators = listOf(
                SchoolAccountInvalidator { request -> invalidateSchoolAccountData(request) },
            ),
        )
    }

    private suspend fun invalidateSchoolAccountData(
        request: AccountInvalidationRequest,
    ): AccountInvalidationResult {
        var failure: AccountInvalidationFailure? = null
        val library = activePlugins.capability(LibraryCapabilities.seat)
        if (library != null) {
            if (!library.signOutForAccountChange()) {
                failure = AccountInvalidationFailure.COOKIE_CLEAR_TIMEOUT
            }
        } else if (!SchoolWebCookies.clearAllSchoolCookiesAndAwait()) {
            // 图书馆插件被移除时，旧 WebView Cookie 仍可能留在系统中；这里仍按
            // 学校域名清理并等待，避免换号后自动带回旧身份。
            failure = AccountInvalidationFailure.COOKIE_CLEAR_TIMEOUT
        }
        val chaoxing = activePlugins.capability(LibraryCapabilities.chaoxing)
        if (chaoxing != null && !chaoxing.signOutForAccountChange()) {
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
            if (runCatching { cardInputRepository.clear() }.isFailure) {
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

    /**
     * 校园卡余额插件。
     *
     * 插件自己持有协议、快照与 Repository；宿主只提供受控账号身份、旧学号草稿清理
     * 和组件刷新回调。余额快照使用带账号归属的 v2，并保留对旧 v1 的同号迁移。
     */
    private val cardPlugin: CardPlugin by lazy {
        CardPlugin(
            context = appContext,
            account = account,
            inputStore = cardInputRepository,
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
        )
    }

    private val classroomPlugin: ClassroomPlugin by lazy { ClassroomPlugin() }

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

    /**
     * 课表认证适配：只有这里读取统一账号凭据；课表插件只拿到业务 Cookie。
     * 构造不联网，真正认证由用户进入课表或主动刷新触发。
     */
    private val timetableAuthenticator: UpcTimetableAuthenticator by lazy {
        UpcTimetableAuthenticator(account)
    }

    private val timetablePlugin: TimetablePlugin by lazy {
        TimetablePlugin(
            context = appContext,
            account = account,
            authenticator = timetableAuthenticator,
        )
    }

    private val pluginRuntime: PluginRuntime by lazy {
        PluginRuntime().also { runtime ->
            runtime.register(cardPlugin.descriptor) { cardPlugin }
            runtime.register(powerPlugin.descriptor) { powerPlugin }
            runtime.register(classroomPlugin.descriptor) { classroomPlugin }
            runtime.register(libraryPlugin.descriptor) { libraryPlugin }
            runtime.register(focusPlugin.descriptor) { focusPlugin }
            runtime.register(timetablePlugin.descriptor) { timetablePlugin }
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

    /**
     * 电费输入（楼号＋房间号）与旧版校园卡学号草稿的持久层。
     *
     * 旧学号草稿只用于账号设置提示/迁移清理，不再是余额查询来源；余额查询只读
     * 当前统一账号的受控学号。
     */
    val powerInputRepository: PowerInputRepository by lazy {
        PowerInputRepository(uiPrefsStore)
    }

    val cardInputRepository: CardInputRepository by lazy {
        CardInputRepository(uiPrefsStore)
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
