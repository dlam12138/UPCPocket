package com.upcpocket.app.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.upcpocket.app.MainActivity
import com.upcpocket.app.card.CardBalanceService
import com.upcpocket.app.card.CardCapabilities
import com.upcpocket.app.card.CardPlugin
import com.upcpocket.app.classroom.ClassroomPlugin
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.data.repo.CardInputRepository
import com.upcpocket.app.data.repo.PowerInputRepository
import com.upcpocket.app.focus.FocusCapabilities
import com.upcpocket.app.focus.FocusPlugin
import com.upcpocket.app.focus.FocusService
import com.upcpocket.app.library.LibraryPlugin
import com.upcpocket.app.plugin.runtime.ActivePlugins
import com.upcpocket.app.plugin.runtime.PluginRuntime
import com.upcpocket.app.plugin.runtime.PluginStartResult
import com.upcpocket.app.plugin.ui.PluginUiSnapshot
import com.upcpocket.app.power.PowerCapabilities
import com.upcpocket.app.power.PowerPlugin
import com.upcpocket.app.power.PowerService
import com.upcpocket.app.widget.WidgetUpdates

/**
 * 宿主手动装配容器。
 *
 * 只保留插件注册、宿主偏好/输入适配器、组件回调与稳定能力解析；业务协议、页面、
 * Room/DataStore 与计时引擎分别归各自 feature 插件。校园能力仍是：
 *  - 专注：数据库、偏好、前台计时；
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

    val uiPrefsStore: UiPrefsStore by lazy { UiPrefsStore(appContext) }

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
     * 图书馆座位插件：内存会话归插件，持久副本用原有 Keystore 加密存储；
     * 宿主只提供“记住登录”偏好的读取。
     */
    private val libraryPlugin: LibraryPlugin by lazy {
        LibraryPlugin(
            context = appContext,
            accountSettings = HostLibraryAccountSettings(uiPrefsStore),
        )
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

    private val pluginRuntime: PluginRuntime by lazy {
        PluginRuntime().also { runtime ->
            runtime.register(cardPlugin.descriptor) { cardPlugin }
            runtime.register(powerPlugin.descriptor) { powerPlugin }
            runtime.register(classroomPlugin.descriptor) { classroomPlugin }
            runtime.register(libraryPlugin.descriptor) { libraryPlugin }
            runtime.register(focusPlugin.descriptor) { focusPlugin }
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

    /**
     * 已激活插件贡献的页面、首页入口和设置区。
     *
     * 只询问真正启动成功的插件；任何重复 ID/路由或“入口指向别的插件页面”都会在
     * 构建快照时失败，不会静默覆盖。宿主只读取这份不可变快照。
     */
    val ui: PluginUiSnapshot by lazy { HostUiAssembly.build(activePlugins) }

    /** 页面只通过插件能力取业务入口，不再直接持有余额 Repository。 */
    val cardService: CardBalanceService
        get() = activePlugins.capability(CardCapabilities.balance)
            ?: error("余额插件未激活")

    /** 电量页面与组件共用同一个插件服务。 */
    val powerService: PowerService
        get() = activePlugins.capability(PowerCapabilities.tangdaowan)
            ?: error("电量插件未激活")

    /** 专注 tab、统计页、通知恢复与前台服务共用同一个插件服务。 */
    val focusService: FocusService
        get() = activePlugins.capability(FocusCapabilities.timer)
            ?: error("专注插件未激活")

    /** 启动装配；由 Application 在设置 Context 后调用。 */
    fun startPlugins() {
        activePlugins
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
}
