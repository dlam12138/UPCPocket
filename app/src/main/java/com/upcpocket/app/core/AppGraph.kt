package com.upcpocket.app.core

import android.content.Context
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
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
     * 校园卡余额的唯一进程内实例。
     *
     * 页面、桌面组件与 WorkManager 刷新都必须用它：同一把锁、同一份快照 Flow，
     * 避免两个 AtomicFile 同时写 `card-balance-v1.json`，也避免组件用旧目标覆盖页面刚查到的结果。
     * 文件格式仍是既有的 `card-balance-v1.json`，升级不会丢旧缓存。
     */
    val directCardBalanceRepository: DirectCardBalanceRepository by lazy {
        DirectCardBalanceRepository(
            DirectCardBalanceStore(File(appContext.noBackupFilesDir, "card-balance-v1.json")),
        )
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
