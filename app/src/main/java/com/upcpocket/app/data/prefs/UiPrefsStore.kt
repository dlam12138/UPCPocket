package com.upcpocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.designsystem.WidgetAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全应用偏好。
 *
 * 轻量版删掉了课表相关的格子样式、壁纸、演示模式与教学周参数；
 * 这些旧键即使还留在用户的 DataStore 文件里也不会被读取或写回。
 */
data class UiPrefs(
    /** 全应用配色预设（默认跟随系统壁纸动态取色） */
    val themePreset: ThemePreset = ThemePreset.DYNAMIC,
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
     * 旧版校园卡余额功能区学号草稿。
     *
     * M2 起仅用于账号设置提示/迁移清理，不再作为余额查询来源；账号绑定后删除。
     */
    val cardStudentId: String = "",
) {
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
        UiPrefs(
            themePreset = p[KEY_THEME_PRESET]?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() }
                ?: ThemePreset.DYNAMIC,
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

    suspend fun setDarkMode(v: DarkMode) = store.edit { p -> p[KEY_DARK_MODE] = v.name }

    suspend fun setWidgetAppearance(v: WidgetAppearance) {
        store.edit { p -> p[KEY_WIDGET_APPEARANCE] = v.name }
        widgetAppearanceSnapshot = v
    }

    private companion object {
        val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")
        val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        val KEY_WIDGET_APPEARANCE = stringPreferencesKey("widget_appearance")

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
    }
}
