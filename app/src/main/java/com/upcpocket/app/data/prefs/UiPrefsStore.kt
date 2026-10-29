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
     * 唐岛湾电费查询上次选的楼号。
     *
     * 只记楼号这一个下拉项，**不记房间号** —— 房间号是可推断的居住信息，
     * 同一台设备换人后更不该自动代入；用户每次自己填。
     */
    val powerBuilding: String = "",
)

private val Context.uiPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "ui_prefs")

class UiPrefsStore(context: Context) {

    private val store = context.applicationContext.uiPrefsStore

    val flow: Flow<UiPrefs> = store.data.map { p ->
        UiPrefs(
            themePreset = p[KEY_THEME_PRESET]?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() }
                ?: ThemePreset.DYNAMIC,
            darkMode = p[KEY_DARK_MODE]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
                ?: DarkMode.SYSTEM,
            rememberSchoolLogin = p[KEY_REMEMBER_SCHOOL_LOGIN] ?: false,
            powerBuilding = p[KEY_POWER_BUILDING].orEmpty(),
        )
    }

    /** “记住学校登录”开关。清理动作由调用方负责（token/密文/Cookie）。 */
    suspend fun setRememberSchoolLogin(v: Boolean) =
        store.edit { it[KEY_REMEMBER_SCHOOL_LOGIN] = v }

    /** 记住唐岛湾电费上次选的楼号（只记楼号，不记房间号）。 */
    suspend fun setPowerBuilding(v: String) = store.edit {
        if (v.isBlank()) {
            it.remove(KEY_POWER_BUILDING)
        } else {
            it[KEY_POWER_BUILDING] = v
        }
    }

    suspend fun setThemePreset(v: ThemePreset) = store.edit { p -> p[KEY_THEME_PRESET] = v.name }

    suspend fun setDarkMode(v: DarkMode) = store.edit { p -> p[KEY_DARK_MODE] = v.name }

    private companion object {
        val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")
        val KEY_DARK_MODE = stringPreferencesKey("dark_mode")

        /** 记住学校登录（只有图书馆 token 加密保存） */
        val KEY_REMEMBER_SCHOOL_LOGIN = booleanPreferencesKey("remember_school_login")

        /** 唐岛湾电费上次选的楼号（不含房间号） */
        val KEY_POWER_BUILDING = stringPreferencesKey("power_building")
    }
}
