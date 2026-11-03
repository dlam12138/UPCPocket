package com.upcpocket.app.focus

import android.app.PendingIntent
import android.content.Context
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.prefs.FocusPrefsStore
import com.upcpocket.app.data.repo.FocusRepository
import kotlinx.serialization.json.Json

/**
 * 专注插件对页面、通知和前台服务暴露的唯一业务入口。
 *
 * 字段全部惰性创建：插件装配阶段只构造这个对象，不打开数据库、不读 DataStore。
 * 第一次进入专注页或恢复上一轮计时时才创建 Repository/Engine；数据库文件名、
 * 版本、实体全限定名与 schema 均保持迁移前不变。
 */
class FocusService(
    context: Context,
    openAppIntent: () -> PendingIntent,
) {
    private val appContext = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    val notifications: PomodoroNotifications by lazy {
        PomodoroNotifications(appContext, openAppIntent)
    }

    val prefs: FocusPrefsStore by lazy { FocusPrefsStore(appContext, json) }

    private val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val repository: FocusRepository by lazy { FocusRepository(database) }

    val engine: PomodoroEngine by lazy {
        PomodoroEngine(
            context = appContext,
            prefs = prefs,
            repo = repository,
            notifier = notifications,
        )
    }

    fun ensureChannels() = notifications.ensureChannels()

    suspend fun restore() = engine.restore()
}

/**
 * 前台服务与插件实例之间的进程内桥。
 *
 * Application 先完成插件装配，再由系统创建/恢复 PomodoroService；服务只在这里
 * 查询当前已激活的实例。插件未激活时不落盘、不联网，直接停止服务，避免绕过
 * 插件的停用状态继续执行计时。
 */
object FocusRuntime {
    @Volatile
    private var active: FocusService? = null

    fun install(service: FocusService) {
        active = service
    }

    fun serviceOrNull(): FocusService? = active

    /** 仅用于单元测试隔离进程内桥，不参与生产装配。 */
    internal fun clearForTest() {
        active = null
    }
}
