package com.upcpocket.app.core

import android.content.Context
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.prefs.FocusPrefsStore
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.data.repo.FocusRepository
import com.upcpocket.app.data.repo.ScheduleRepository
import com.upcpocket.app.focus.PomodoroEngine
import com.upcpocket.app.focus.PomodoroNotifications
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 极简手动依赖容器。
 *
 * M1 只保留**本地**依赖：数据库、偏好、课表仓库、番茄钟；
 * 0.1.2 增加两个独立网络能力：公开空闲教室查询、图书馆座位（官方 CAS 登录后的内存会话）。
 * 上游项目里的 CAS 客户端、教务/一卡通客户端、令牌存储、房间表、更新检查已删除；
 * 图书馆 token 不落盘，退出/进程结束即清除。
 */
object AppGraph {

    private lateinit var appContext: Context

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        // 首次启动种一份本地作息与学期；幂等，已有数据就什么都不做。
        // 放在 IO 线程，避免拖慢首帧。
        appScope.launch {
            runCatching { scheduleRepository.ensureSeeded() }
        }
    }

    /** 给需要在非 Composable 环境里拿 Context 的地方用（比如清理背景图文件） */
    fun appContextOrNull(): Context? = if (::appContext.isInitialized) appContext else null

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

    val scheduleRepository: ScheduleRepository by lazy {
        ScheduleRepository(db, uiPrefsStore)
    }

    val focusRepository: FocusRepository by lazy { FocusRepository(db) }

    /**
     * 图书馆座位：token 默认只在这个单例的内存里；开启“记住登录”时另有
     * [SchoolSessionStore] 的 Keystore 加密副本，退出/关闭开关时清理。
     */
    val librarySeatRepository: LibrarySeatRepository by lazy { LibrarySeatRepository() }

    /** “记住登录”的加密持久副本（仅图书馆 token；课表依赖 WebView Cookie）。 */
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
