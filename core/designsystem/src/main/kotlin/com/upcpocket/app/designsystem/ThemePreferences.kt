package com.upcpocket.app.designsystem

/** 深浅色：跟随系统 / 始终浅色 / 始终深色。 */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/**
 * 全应用配色预设。
 *
 * [DYNAMIC] = 跟随系统壁纸的动态取色；其余每一项都是一个种子色，由 Material 3
 * 算法（HCT）算出整套浅/深配色。枚举名会写入 DataStore，改名需要迁移。
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
