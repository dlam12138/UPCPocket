package com.upcpocket.app.core

import android.content.Context
import com.upcpocket.app.card.CardBalanceService
import com.upcpocket.app.card.CardCapabilities
import com.upcpocket.app.card.CardPlugin
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.power.PowerReadingRepository
import com.upcpocket.app.data.power.PowerSnapshotStore
import com.upcpocket.app.data.power.TangdaowanElectricityService
import com.upcpocket.app.data.prefs.FocusPrefsStore
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.data.repo.CardInputRepository
import com.upcpocket.app.data.repo.FocusRepository
import com.upcpocket.app.data.repo.PowerInputRepository
import com.upcpocket.app.focus.PomodoroEngine
import com.upcpocket.app.focus.PomodoroNotifications
import com.upcpocket.app.plugin.runtime.ActivePlugins
import com.upcpocket.app.plugin.runtime.PluginRuntime
import com.upcpocket.app.plugin.runtime.PluginStartResult
import com.upcpocket.app.widget.WidgetUpdates
import java.io.File
import kotlinx.serialization.json.Json

/**
 * 极简手动依赖容器。
 *
 * 轻量版只保留本机番茄钟与四个校园查询能力：
 *  - 番茄钟：数据库、偏好、前台计时；
 *  - 空闲教室：公开查询；
 *  - 图书馆座位：学校官方 CAS 登录后的内存会话；
 *  - 校园卡余额：输入本人学号直接读取学校余额接口，不需要平台登录；
 *  - 唐岛湾宿舍剩余电量：无需登录的只读页面查询。
 *
 * 课表（教务导入、旧学校同步、CSV 导入、展示编辑、桌面组件）已整体删除，
 * 因此这里没有 ScheduleRepository，也没有首启种子数据 —— 应用启动时
 * 不会写数据库，也不会发起任何网络请求。
 */
object AppGraph {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }

    val uiPrefsStore: UiPrefsStore by lazy { UiPrefsStore(appContext) }

    val db: AppDatabase by lazy { AppDatabase.get(appContext) }

    val focusRepository: FocusRepository by lazy { FocusRepository(db) }

    /**
     * 图书馆座位：token 默认只在这个单例的内存里；开启“记住登录”时另有
     * [SchoolSessionStore] 的 Keystore 加密副本，退出/关闭开关时清理。
     */
    val librarySeatRepository: LibrarySeatRepository by lazy { LibrarySeatRepository() }

    /** 唐岛湾宿舍剩余电量：无需登录的只读查询。 */
    val tangdaowanElectricityService: TangdaowanElectricityService by lazy {
        TangdaowanElectricityService()
    }

    /**
     * 校园卡余额插件。
     *
     * 插件自己持有协议、快照与 Repository；宿主只提供学号持久化和组件刷新回调。
     * 快照文件仍是既有的 `card-balance-v1.json`。
     */
    private val cardPlugin: CardPlugin by lazy {
        CardPlugin(
            context = appContext,
            inputStore = cardInputRepository,
            onSnapshotChanged = { WidgetUpdates.requestCard(appContext) },
        )
    }

    private val pluginRuntime: PluginRuntime by lazy {
        PluginRuntime().also { runtime ->
            runtime.register(cardPlugin.descriptor) { cardPlugin }
        }
    }

    /**
     * 显式装配结果。启动时校验插件描述符、能力声明与依赖；失败则在开发/发行环境
     * 都明确抛错，不静默留下半注册状态。
     */
    val activePlugins: ActivePlugins by lazy {
        when (val result = pluginRuntime.start()) {
            is PluginStartResult.Active -> result.plugins
            is PluginStartResult.Invalid -> error(
                "插件装配失败：" + result.diagnostics.joinToString { "${it.code}:${it.pluginId}" },
            )
        }
    }

    /** 页面只通过插件能力取业务入口，不再直接持有余额 Repository。 */
    val cardService: CardBalanceService
        get() = activePlugins.capability(CardCapabilities.balance)
            ?: error("余额插件未激活")

    /** 启动装配；由 Application 在设置 Context 后调用。 */
    fun startPlugins() {
        activePlugins
    }

    /**
     * 唐岛湾剩余电量的唯一进程内实例。
     *
     * 成功分支才写 `power-reading-v1.json`；页面和组件共享同一份成功快照与目标。
     */
    val powerReadingRepository: PowerReadingRepository by lazy {
        PowerReadingRepository(
            PowerSnapshotStore(File(appContext.noBackupFilesDir, "power-reading-v1.json")),
        ) { building, room ->
            tangdaowanElectricityService.query(building, room)
        }
    }

    /**
     * 电费输入（楼号＋房间号）与校园卡学号的持久层。
     * 供当前页面和将来的桌面组件复用；只读写本机配置，不查网络。
     */
    val powerInputRepository: PowerInputRepository by lazy {
        PowerInputRepository(uiPrefsStore)
    }

    val cardInputRepository: CardInputRepository by lazy {
        CardInputRepository(uiPrefsStore)
    }

    /** “记住登录”的加密持久副本（目前只有图书馆座位 token）。 */
    val schoolSessionStore: SchoolSessionStore by lazy { SchoolSessionStore(appContext) }

    val focusPrefsStore: FocusPrefsStore by lazy { FocusPrefsStore(appContext, json) }

    val pomodoroNotifications: PomodoroNotifications by lazy { PomodoroNotifications(appContext) }

    val pomodoroEngine: PomodoroEngine by lazy {
        PomodoroEngine(
            context = appContext,
            prefs = focusPrefsStore,
            repo = focusRepository,
            notifier = pomodoroNotifications,
        )
    }
}
