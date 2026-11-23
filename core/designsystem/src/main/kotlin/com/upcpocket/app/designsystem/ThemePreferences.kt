package com.upcpocket.app.designsystem

/** 深浅色：跟随系统 / 始终浅色 / 始终深色。 */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/**
 * 全应用配色预设。
 *
 * [DYNAMIC] = 跟随系统壁纸的动态取色；[CUSTOM] = 用户在取色卡中自选的种子色；
 * 其余每一项都是固定种子色，由 Material 3 算法（HCT）算出整套浅/深配色。
 * 枚举名会写入 DataStore，既有名称不可改名。
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
    CUSTOM(null),
}

/** 取色卡默认种子色（与蓝预设一致），在用户尚未自定义色值时兜底。 */
const val DEFAULT_CUSTOM_THEME_SEED_ARGB: Int = 0xFF1A73E8.toInt()

/** 自定义背景图默认可见度（与主题底色混合后的图片不透明度）。 */
const val DEFAULT_CUSTOM_BACKGROUND_OPACITY: Float = 0.55f

const val MIN_CUSTOM_BACKGROUND_OPACITY: Float = 0.15f
const val MAX_CUSTOM_BACKGROUND_OPACITY: Float = 0.90f

/** 确保写入与计算用的种子色始终不透明。 */
fun normalizeOpaqueSeedArgb(argb: Int): Int = argb or (0xFF shl 24)

/** 限制自定义背景不透明度在保证可读性的范围内，NaN/无穷大回退默认值。 */
fun sanitizeCustomBackgroundOpacity(value: Float?): Float {
    if (value == null || !value.isFinite()) return DEFAULT_CUSTOM_BACKGROUND_OPACITY
    return value.coerceIn(MIN_CUSTOM_BACKGROUND_OPACITY, MAX_CUSTOM_BACKGROUND_OPACITY)
}
