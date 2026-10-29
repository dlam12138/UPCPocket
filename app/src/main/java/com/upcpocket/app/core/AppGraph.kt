package com.upcpocket.app.core

import android.content.Context
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.power.TangdaowanElectricityService
import com.upcpocket.app.data.prefs.FocusPrefsStore
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.data.repo.FocusRepository
import com.upcpocket.app.focus.PomodoroEngine
import com.upcpocket.app.focus.PomodoroNotifications
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

    /** 唐岛湾宿舍剩余电量：无需登录的只读查询，不保存房间号。 */
    val tangdaowanElectricityService: TangdaowanElectricityService by lazy {
        TangdaowanElectricityService()
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
