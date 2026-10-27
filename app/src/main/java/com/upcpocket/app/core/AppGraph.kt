package com.upcpocket.app.core

import android.content.Context
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.prefs.FocusPrefsStore
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
 * M1 只保留**本地**依赖：数据库、偏好、课表仓库、番茄钟。
 * 上游项目里的 CAS 客户端、教务/一卡通客户端、令牌存储、房间表、更新检查
 * 已随郑州大学专属链路一起移除 —— 这个容器里现在拿不到任何能发校园请求的对象，
 * 这是"不再误连旧服务"最硬的保证（不是靠开关关掉的，是代码不存在）。
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
