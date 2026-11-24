package com.upcpocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.upcpocket.app.designsystem.DEFAULT_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.DEFAULT_CUSTOM_THEME_SEED_ARGB
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.designsystem.normalizeOpaqueSeedArgb
import com.upcpocket.app.designsystem.sanitizeCustomBackgroundOpacity
import com.upcpocket.app.timetable.TimetableModuleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全应用偏好。
 *
 * 轻量版删掉了课表相关的格子样式、演示模式与教学周参数；
 * 这些旧键即使还留在用户的 DataStore 文件里也不会被读取或写回。
 */
data class UiPrefs(
    /** 全应用配色预设（默认跟随系统壁纸动态取色） */
    val themePreset: ThemePreset = ThemePreset.DYNAMIC,
    /** 取色卡模式保存的自定义主题种子色（ARGB，始终不透明）。 */
    val customThemeSeedArgb: Int = DEFAULT_CUSTOM_THEME_SEED_ARGB,
    /**
     * 自定义应用背景版本号；0 表示未启用背景图，正值表示已启用并在更换图片时递增，
     * 用于通知界面重新加载应用私有目录中的背景文件。
     */
    val customBackgroundVersion: Long = 0L,
    /** 自定义背景图可见度（0.15..0.90），越高图片越清晰、遮罩越轻。 */
    val customBackgroundOpacity: Float = DEFAULT_CUSTOM_BACKGROUND_OPACITY,
    /** 深浅色（默认跟随系统） */
    val darkMode: DarkMode = DarkMode.SYSTEM,
    /** Launcher widget background; independent from the app's Compose style. */
    val widgetAppearance: WidgetAppearance = WidgetAppearance.SOLID,
    /**
     * 记住学校登录：把图书馆座位业务签发的 token 用 Keystore 加密保存。
     * 默认关闭。这只是图书馆 token 偏好，不是统一学校账号密码的保存同意；
     * 关闭或“退出学校登录”会清理持久副本。校园卡余额不需要登录。
     */
    val rememberSchoolLogin: Boolean = false,
    /** 本机诊断事件开关；默认开启，只写私有目录，由用户主动导出。 */
    val diagnosticsEnabled: Boolean = true,
    /**
     * 唐岛湾电费上次选的楼号与房间号。
     *
     * 用户已明确授权记住房间号，方便下次进入和将来的桌面组件直接回填；
     * 两者以同一次 DataStore 编辑保存，提供明确的“清除”动作。
     * 只保存在应用私有空间，不随输入自动发起网络查询。
     */
    val powerBuilding: String = "",
    val powerRoom: String = "",
    /** 首页置顶的常用服务 ID 集合；默认置顶高频四项，用户可在「服务」页调整。 */
    val pinnedServiceIds: Set<String> = DEFAULT_PINNED_SERVICES,
    /**
     * 常用校区：设置里选择的默认展示校区。
     *
     * 只改变各功能页的默认范围；页内临时切换不改写这里。缺键或未知值按
     * [PreferredCampus.ALL] 处理，保持旧安装原有的多校区/上次校区行为。
     */
    val preferredCampus: PreferredCampus = PreferredCampus.ALL,
    /**
     * 旧版校园卡余额功能区学号草稿。
     *
     * M2 起仅用于账号设置提示/迁移清理，不再作为余额查询来源；账号绑定后删除。
     */
    val cardStudentId: String = "",
    /** 应用内界面风格；独立于桌面组件，旧安装和未知取值保持 Material 3。旧候选的 `ui_style` 键不读取也不清除。 */
    val uiStyle: UiStyle = UiStyle.MATERIAL3,
    /**
     * 日程待办显示在课表页还是专注页。
     *
     * 只影响一级页内的槽位位置，不改变待办数据、提醒或路由；缺键与未知值
     * 回退 [TodoHostPlacement.TIMETABLE]。
     */
    val todoHostPlacement: TodoHostPlacement = TodoHostPlacement.TIMETABLE,
    /** 首页模块上下排序偏好；缺键或未知值回退 [HomeModuleId.DEFAULT_ORDER]。 */
    val homeModuleOrder: List<HomeModuleId> = HomeModuleId.DEFAULT_ORDER,
    /** 课表页模块上下排序偏好；缺键或未知值回退 [TimetableModuleId.DEFAULT_ORDER]。 */
    val timetableModuleOrder: List<TimetableModuleId> = TimetableModuleId.DEFAULT_ORDER,
    /**
     * 是否允许长按拖动首页/课表卡片；缺键默认关闭。
     *
     * 关闭只锁定交互，不影响 [homeModuleOrder] / [timetableModuleOrder] 的渲染与保留。
     */
    val moduleReorderEnabled: Boolean = false,
) {
    val hasCustomBackground: Boolean
        get() = customBackgroundVersion > 0L

    companion object {
        val DEFAULT_PINNED_SERVICES = setOf(
            "classroom.free",
            "library.home",
            "power.electricity",
            "shiguang.home",
        )
    }
}

private val Context.uiPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "ui_prefs")

class UiPrefsStore internal constructor(
    private val store: DataStore<Preferences>,
) {

    @Volatile
    private var widgetAppearanceSnapshot: WidgetAppearance = WidgetAppearance.SOLID

    /** Synchronous in-memory value for RemoteViews rendering; never reads DataStore on a receiver. */
    fun widgetAppearanceNow(): WidgetAppearance = widgetAppearanceSnapshot

    constructor(context: Context) : this(context.applicationContext.uiPrefsStore)

    val flow: Flow<UiPrefs> = store.data.map { p ->
        val appearance = WidgetAppearance.fromStoredValue(p[KEY_WIDGET_APPEARANCE])
        widgetAppearanceSnapshot = appearance
        val storedBackgroundVersion = (p[KEY_CUSTOM_BACKGROUND_VERSION] ?: 0L).coerceAtLeast(0L)
        UiPrefs(
            themePreset = p[KEY_THEME_PRESET]?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() }
                ?: ThemePreset.DYNAMIC,
            customThemeSeedArgb = p[KEY_CUSTOM_THEME_SEED]?.let(::normalizeOpaqueSeedArgb)
                ?: DEFAULT_CUSTOM_THEME_SEED_ARGB,
            customBackgroundVersion = storedBackgroundVersion,
            customBackgroundOpacity = sanitizeCustomBackgroundOpacity(p[KEY_CUSTOM_BACKGROUND_OPACITY]),
            darkMode = p[KEY_DARK_MODE]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
                ?: DarkMode.SYSTEM,
            widgetAppearance = appearance,
            rememberSchoolLogin = p[KEY_REMEMBER_SCHOOL_LOGIN] ?: false,
            diagnosticsEnabled = p[KEY_DIAGNOSTICS_ENABLED] ?: true,
            powerBuilding = p[KEY_POWER_BUILDING].orEmpty(),
            powerRoom = p[KEY_POWER_ROOM].orEmpty(),
            // An absent key is an older/unconfigured installation; a present empty set is
            // an intentional choice to pin nothing.
            pinnedServiceIds = if (p.contains(KEY_PINNED_SERVICES)) {
                p[KEY_PINNED_SERVICES].orEmpty()
            } else {
                UiPrefs.DEFAULT_PINNED_SERVICES
            },
            cardStudentId = p[KEY_CARD_STUDENT_ID].orEmpty(),
            preferredCampus = PreferredCampus.fromStoredValue(p[KEY_PREFERRED_CAMPUS]),
            uiStyle = p[KEY_UI_STYLE]?.let { runCatching { UiStyle.valueOf(it) }.getOrNull() }
                ?: UiStyle.MATERIAL3,
            todoHostPlacement = TodoHostPlacement.fromStoredValue(p[KEY_TODO_HOST_PLACEMENT]),
            homeModuleOrder = HomeModuleId.fromStoredValue(p[KEY_HOME_MODULE_ORDER]),
            timetableModuleOrder = TimetableModuleId.fromStoredValue(p[KEY_TIMETABLE_MODULE_ORDER]),
            moduleReorderEnabled = p[KEY_MODULE_REORDER_ENABLED] ?: false,
        )
    }

    suspend fun togglePinnedService(entryId: String) = store.edit { p ->
        val current = if (p.contains(KEY_PINNED_SERVICES)) {
            p[KEY_PINNED_SERVICES].orEmpty()
        } else {
            UiPrefs.DEFAULT_PINNED_SERVICES
        }
        p[KEY_PINNED_SERVICES] = if (entryId in current) current - entryId else current + entryId
    }

    /** “记住学校登录”开关。清理动作由调用方负责（token/密文/Cookie）。 */
    suspend fun setRememberSchoolLogin(v: Boolean) =
        store.edit { it[KEY_REMEMBER_SCHOOL_LOGIN] = v }

    suspend fun setDiagnosticsEnabled(v: Boolean) =
        store.edit { it[KEY_DIAGNOSTICS_ENABLED] = v }

    /**
     * 一次编辑同时保存楼号与房间号，避免两个字段在不同版本/进程里各写一半。
     * 空串表示删除对应键。
     */
    suspend fun savePowerInput(building: String, room: String) = store.edit { p ->
        if (building.isBlank()) p.remove(KEY_POWER_BUILDING) else p[KEY_POWER_BUILDING] = building
        if (room.isBlank()) p.remove(KEY_POWER_ROOM) else p[KEY_POWER_ROOM] = room
    }

    suspend fun clearPowerInput() = store.edit { p ->
        p.remove(KEY_POWER_BUILDING)
        p.remove(KEY_POWER_ROOM)
    }

    /** 旧版学号草稿；只服务迁移提示，不再作为余额查询来源。 */
    suspend fun setCardStudentId(v: String) = store.edit { p ->
        if (v.isBlank()) p.remove(KEY_CARD_STUDENT_ID) else p[KEY_CARD_STUDENT_ID] = v
    }

    suspend fun clearCardStudentId() = store.edit { p -> p.remove(KEY_CARD_STUDENT_ID) }

    suspend fun setThemePreset(v: ThemePreset) = store.edit { p -> p[KEY_THEME_PRESET] = v.name }

    /** 保存取色卡种子色并切换到自定义配色模式，仅修改本机外观偏好。 */
    suspend fun setCustomThemeSeed(argb: Int) = store.edit { p ->
        p[KEY_CUSTOM_THEME_SEED] = normalizeOpaqueSeedArgb(argb)
        p[KEY_THEME_PRESET] = ThemePreset.CUSTOM.name
    }

    /** 更新或清除自定义背景版本号；非正值清除键。 */
    suspend fun setCustomBackgroundVersion(version: Long) = store.edit { p ->
        if (version <= 0L) {
            p.remove(KEY_CUSTOM_BACKGROUND_VERSION)
        } else {
            p[KEY_CUSTOM_BACKGROUND_VERSION] = version
        }
    }

    /** 保存自定义背景可见度；自动限制在保证可读性的区间。 */
    suspend fun setCustomBackgroundOpacity(opacity: Float) = store.edit { p ->
        p[KEY_CUSTOM_BACKGROUND_OPACITY] = sanitizeCustomBackgroundOpacity(opacity)
    }

    suspend fun setDarkMode(v: DarkMode) = store.edit { p -> p[KEY_DARK_MODE] = v.name }

    /** 只保存本机默认展示校区；不触发任何校园请求。 */
    suspend fun setPreferredCampus(v: PreferredCampus) =
        store.edit { p -> p[KEY_PREFERRED_CAMPUS] = v.name }

    suspend fun setUiStyle(v: UiStyle) = store.edit { p -> p[KEY_UI_STYLE] = v.name }

    /** 只保存本机展示位置；不移动、改写或重新调度任何待办。 */
    suspend fun setTodoHostPlacement(v: TodoHostPlacement) =
        store.edit { p -> p[KEY_TODO_HOST_PLACEMENT] = v.name }

    /** 只保存拖动开关；关闭后保留既有 [UiPrefs.homeModuleOrder] / [UiPrefs.timetableModuleOrder]。 */
    suspend fun setModuleReorderEnabled(v: Boolean) =
        store.edit { p -> p[KEY_MODULE_REORDER_ENABLED] = v }

    /**
     * 只保存首页模块排序偏好；不触发校园请求或影响其他业务数据。
     *
     * 在同一个 edit 内复核开关，丢弃关闭开关后才到达的拖动回调。
     */
    suspend fun setHomeModuleOrder(order: List<HomeModuleId>) = store.edit { p ->
        if (p[KEY_MODULE_REORDER_ENABLED] != true) return@edit
        p[KEY_HOME_MODULE_ORDER] = HomeModuleId.toStoredValue(order)
    }

    /**
     * 只保存课表页模块排序偏好；不触发校园请求或影响待办/课表数据。
     *
     * 与首页相同，关闭开关后迟到的回调不写回。
     */
    suspend fun setTimetableModuleOrder(order: List<TimetableModuleId>) = store.edit { p ->
        if (p[KEY_MODULE_REORDER_ENABLED] != true) return@edit
        p[KEY_TIMETABLE_MODULE_ORDER] = TimetableModuleId.toStoredValue(order)
    }

    suspend fun setWidgetAppearance(v: WidgetAppearance) {
        store.edit { p -> p[KEY_WIDGET_APPEARANCE] = v.name }
        widgetAppearanceSnapshot = v
    }

    private companion object {
        val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")
        val KEY_CUSTOM_THEME_SEED = intPreferencesKey("custom_theme_seed_argb")
        val KEY_CUSTOM_BACKGROUND_VERSION = longPreferencesKey("custom_background_version")
        val KEY_CUSTOM_BACKGROUND_OPACITY = floatPreferencesKey("custom_background_opacity")
        val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        val KEY_WIDGET_APPEARANCE = stringPreferencesKey("widget_appearance")
        /**
         * 应用内界面风格。使用独立新键；旧候选的 `ui_style` 可能仍为有效枚举名，
         * 但不能据此自动开启新外观。
         */
        val KEY_UI_STYLE = stringPreferencesKey("app_ui_style")

        /** 记住学校登录（只有图书馆 token 加密保存） */
        val KEY_REMEMBER_SCHOOL_LOGIN = booleanPreferencesKey("remember_school_login")

        /** 本机诊断记录开关；默认开启。 */
        val KEY_DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")

        /** 唐岛湾电费上次选的楼号与房间号（用户已授权记住） */
        val KEY_POWER_BUILDING = stringPreferencesKey("power_building")
        val KEY_POWER_ROOM = stringPreferencesKey("power_room")

        /** 校园卡余额功能区上次输入的学号（与余额快照无关） */
        val KEY_CARD_STUDENT_ID = stringPreferencesKey("card_student_id")

        /** 首页置顶的服务入口 ID */
        val KEY_PINNED_SERVICES = stringSetPreferencesKey("pinned_service_ids")

        /** 常用校区（仅本机默认展示范围） */
        val KEY_PREFERRED_CAMPUS = stringPreferencesKey("preferred_campus")

        /** 日程待办一级页位置（课表页/专注页）；缺键回退课表页。 */
        val KEY_TODO_HOST_PLACEMENT = stringPreferencesKey("todo_host_placement")

        /** 首页模块排序（逗号分隔枚举名）；缺键回退默认顺序。 */
        val KEY_HOME_MODULE_ORDER = stringPreferencesKey("home_module_order")

        /** 课表页模块排序（逗号分隔枚举名）；缺键回退默认顺序。 */
        val KEY_TIMETABLE_MODULE_ORDER = stringPreferencesKey("timetable_module_order")

        /** 首页/课表长按拖动开关；缺键默认关闭。 */
        val KEY_MODULE_REORDER_ENABLED = booleanPreferencesKey("module_reorder_enabled")
    }
}
