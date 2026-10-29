package com.upcpocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 深浅色：跟随系统 / 始终浅色 / 始终深色 */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/**
 * 全应用配色预设。
 *
 * [DYNAMIC] = 跟随系统壁纸的动态取色（最接近原生 Android 的默认观感）；
 * 其余每一项都是**一个种子色**，由 Material 3 算法（HCT）算出整套浅/深配色。
 */
enum class ThemePreset(val seedArgb: Int?) {
    DYNAMIC(null),
    PURPLE(0xFF6750A4.toInt()),
    BLUE(0xFF1A73E8.toInt()),
    TEAL(0xFF00838F.toInt()),
    GREEN(0xFF2E7D32.toInt()),
    AMBER(0xFFE8710A.toInt()),
    PINK(0xFFD81B60.toInt()),
    GRAPHITE(0xFF546E7A.toInt()),
}

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
    /**
     * 记住学校登录：把图书馆座位业务签发的 token 用 Keystore 加密保存。
     * 默认关闭。关闭或“退出学校登录”会清理持久副本。校园卡余额不需要登录。
     */
    val rememberSchoolLogin: Boolean = false,
    /**
     * 唐岛湾电费上次选的楼号与房间号。
     *
     * 用户已明确授权记住房间号，方便下次进入和将来的桌面组件直接回填；
     * 两者以同一次 DataStore 编辑保存，提供明确的“清除”动作。
     * 只保存在应用私有空间，不随输入自动发起网络查询。
     */
    val powerBuilding: String = "",
    val powerRoom: String = "",
    /**
     * 校园卡余额功能区上次输入的本人学号。
     *
     * 与余额快照分开保存：即使从未查询成功，重启后也能恢复输入；
     * 不查询时只保存这个字段。清除动作会同时删除它。
     */
    val cardStudentId: String = "",
)

private val Context.uiPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "ui_prefs")

class UiPrefsStore internal constructor(
    private val store: DataStore<Preferences>,
) {

    constructor(context: Context) : this(context.applicationContext.uiPrefsStore)

    val flow: Flow<UiPrefs> = store.data.map { p ->
        UiPrefs(
            themePreset = p[KEY_THEME_PRESET]?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() }
                ?: ThemePreset.DYNAMIC,
            darkMode = p[KEY_DARK_MODE]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
                ?: DarkMode.SYSTEM,
            rememberSchoolLogin = p[KEY_REMEMBER_SCHOOL_LOGIN] ?: false,
            powerBuilding = p[KEY_POWER_BUILDING].orEmpty(),
            powerRoom = p[KEY_POWER_ROOM].orEmpty(),
            cardStudentId = p[KEY_CARD_STUDENT_ID].orEmpty(),
        )
    }

    /** “记住学校登录”开关。清理动作由调用方负责（token/密文/Cookie）。 */
    suspend fun setRememberSchoolLogin(v: Boolean) =
        store.edit { it[KEY_REMEMBER_SCHOOL_LOGIN] = v }

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

    /** 校园卡学号与余额快照分离，只保存输入本身。 */
    suspend fun setCardStudentId(v: String) = store.edit { p ->
        if (v.isBlank()) p.remove(KEY_CARD_STUDENT_ID) else p[KEY_CARD_STUDENT_ID] = v
    }

    suspend fun clearCardStudentId() = store.edit { p -> p.remove(KEY_CARD_STUDENT_ID) }

    suspend fun setThemePreset(v: ThemePreset) = store.edit { p -> p[KEY_THEME_PRESET] = v.name }

    suspend fun setDarkMode(v: DarkMode) = store.edit { p -> p[KEY_DARK_MODE] = v.name }

    private companion object {
        val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")
        val KEY_DARK_MODE = stringPreferencesKey("dark_mode")

        /** 记住学校登录（只有图书馆 token 加密保存） */
        val KEY_REMEMBER_SCHOOL_LOGIN = booleanPreferencesKey("remember_school_login")

        /** 唐岛湾电费上次选的楼号与房间号（用户已授权记住） */
        val KEY_POWER_BUILDING = stringPreferencesKey("power_building")
        val KEY_POWER_ROOM = stringPreferencesKey("power_room")

        /** 校园卡余额功能区上次输入的学号（与余额快照无关） */
        val KEY_CARD_STUDENT_ID = stringPreferencesKey("card_student_id")
    }
}
