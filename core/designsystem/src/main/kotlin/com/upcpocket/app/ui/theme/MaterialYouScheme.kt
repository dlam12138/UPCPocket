package com.upcpocket.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.upcpocket.app.designsystem.DEFAULT_CUSTOM_THEME_SEED_ARGB
import com.upcpocket.app.designsystem.normalizeOpaqueSeedArgb

/**
 * 配色风格变体（对标 Android 14+ 的"主题色"风格）。
 * 默认的 TONAL_SPOT 就是系统动态取色用的那套算法，所以它跟原生观感一致。
 */
enum class SchemeVariant(val label: String) {
    TONAL_SPOT("标准"),
    VIBRANT("鲜艳"),
    EXPRESSIVE("表现力"),
    CONTENT("内容色"),
}

/**
 * 由**一个种子色**算出整套 Material 3 配色（浅色 / 深色两套）。
 *
 * 用的是 Google `material-color-utilities`（MIT，Kotlin 移植）：先用 HCT 把种子色摊成
 * 若干色调调色板，再按 M3 的规范取各角色色值 —— 和 Android 系统的动态取色同一套算法，
 * 所以自己给种子色也能得到"原生感"的配色，而不是随手挑的色。
 */
object MaterialYouScheme {

    /** 默认对比度（系统默认档） */
    const val DEFAULT_CONTRAST = 0.0

    private const val CACHE_LIMIT = 32

    private data class SchemeCacheKey(
        val seedArgb: Int,
        val dark: Boolean,
        val variant: SchemeVariant,
        val contrastLevel: Double,
    )

    private data class SwatchCacheKey(
        val seedArgb: Int,
        val dark: Boolean,
    )

    private val cacheLock = Any()
    private val schemeCache = object : LinkedHashMap<SchemeCacheKey, ColorScheme>(
        CACHE_LIMIT,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SchemeCacheKey, ColorScheme>?,
        ): Boolean = size > CACHE_LIMIT
    }
    private val swatchCache = object : LinkedHashMap<SwatchCacheKey, List<Color>>(
        CACHE_LIMIT,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SwatchCacheKey, List<Color>>?,
        ): Boolean = size > CACHE_LIMIT
    }

    /**
     * 仅计算外观设置预览所需的三个代表色（primary / primaryContainer / tertiary），
     * 避免进入外观页时在主线程同步生成多套完整 36 角色 M3 配色表。
     */
    fun previewSwatches(
        seedArgb: Int,
        dark: Boolean,
    ): List<Color> {
        val key = SwatchCacheKey(seedArgb, dark)
        synchronized(cacheLock) {
            swatchCache[key]?.let { return it }
            schemeCache[SchemeCacheKey(seedArgb, dark, SchemeVariant.TONAL_SPOT, DEFAULT_CONTRAST)]
                ?.let { cachedScheme ->
                    val swatches = listOf(
                        cachedScheme.primary,
                        cachedScheme.primaryContainer,
                        cachedScheme.tertiary,
                    )
                    swatchCache[key] = swatches
                    return swatches
                }
        }
        val scheme = dynamicScheme(seedArgb, dark, SchemeVariant.TONAL_SPOT, DEFAULT_CONTRAST)
        val colors = MaterialDynamicColors()
        val swatches = listOf(
            Color(colors.primary().getArgb(scheme)),
            Color(colors.primaryContainer().getArgb(scheme)),
            Color(colors.tertiary().getArgb(scheme)),
        )
        synchronized(cacheLock) {
            swatchCache[key] = swatches
        }
        return swatches
    }

    fun fromSeed(
        seedArgb: Int,
        dark: Boolean,
        variant: SchemeVariant = SchemeVariant.TONAL_SPOT,
        contrastLevel: Double = DEFAULT_CONTRAST,
    ): ColorScheme {
        val key = SchemeCacheKey(seedArgb, dark, variant, contrastLevel)
        synchronized(cacheLock) {
            schemeCache[key]?.let { return it }
        }
        val scheme = dynamicScheme(seedArgb, dark, variant, contrastLevel)
        val colors = MaterialDynamicColors()
        fun role(color: DynamicColor): Color = Color(color.getArgb(scheme))
        val computed = ColorScheme(
            primary = role(colors.primary()),
            onPrimary = role(colors.onPrimary()),
            primaryContainer = role(colors.primaryContainer()),
            onPrimaryContainer = role(colors.onPrimaryContainer()),
            inversePrimary = role(colors.inversePrimary()),

            secondary = role(colors.secondary()),
            onSecondary = role(colors.onSecondary()),
            secondaryContainer = role(colors.secondaryContainer()),
            onSecondaryContainer = role(colors.onSecondaryContainer()),

            tertiary = role(colors.tertiary()),
            onTertiary = role(colors.onTertiary()),
            tertiaryContainer = role(colors.tertiaryContainer()),
            onTertiaryContainer = role(colors.onTertiaryContainer()),

            background = role(colors.background()),
            onBackground = role(colors.onBackground()),

            surface = role(colors.surface()),
            onSurface = role(colors.onSurface()),
            surfaceVariant = role(colors.surfaceVariant()),
            onSurfaceVariant = role(colors.onSurfaceVariant()),
            surfaceTint = role(colors.surfaceTint()),
            inverseSurface = role(colors.inverseSurface()),
            inverseOnSurface = role(colors.inverseOnSurface()),
            surfaceBright = role(colors.surfaceBright()),
            surfaceDim = role(colors.surfaceDim()),
            surfaceContainer = role(colors.surfaceContainer()),
            surfaceContainerHigh = role(colors.surfaceContainerHigh()),
            surfaceContainerHighest = role(colors.surfaceContainerHighest()),
            surfaceContainerLow = role(colors.surfaceContainerLow()),
            surfaceContainerLowest = role(colors.surfaceContainerLowest()),

            error = role(colors.error()),
            onError = role(colors.onError()),
            errorContainer = role(colors.errorContainer()),
            onErrorContainer = role(colors.onErrorContainer()),

            outline = role(colors.outline()),
            outlineVariant = role(colors.outlineVariant()),
            scrim = role(colors.scrim()),
            // 注：primaryFixed / secondaryFixed / tertiaryFixed 那一组角色
            // 在当前的 Compose Material3 版本里还不是构造参数，本 App 也没用到，故不映射。
        )
        synchronized(cacheLock) {
            schemeCache[key] = computed
            if (variant == SchemeVariant.TONAL_SPOT && contrastLevel == DEFAULT_CONTRAST) {
                swatchCache[SwatchCacheKey(seedArgb, dark)] =
                    listOf(computed.primary, computed.primaryContainer, computed.tertiary)
            }
        }
        return computed
    }

    /**
     * 从一组采样像素中提取适合作为 Material You 主题种子的推荐色列表。
     *
     * 使用 `material-color-utilities` 的 QuantizerCelebi + Score 过滤过灰或相近色，
     * 仅在后台线程对小尺寸缩略图调用。
     */
    fun extractSeedColors(
        pixels: IntArray,
        maxColors: Int = 4,
    ): List<Int> {
        if (pixels.isEmpty() || maxColors <= 0) return emptyList()
        val quantized = QuantizerCelebi.quantize(pixels, 128)
        if (quantized.isEmpty()) return emptyList()
        return Score.score(
            colorsToPopulation = quantized,
            desired = maxColors,
            fallbackColorArgb = DEFAULT_CUSTOM_THEME_SEED_ARGB,
            filter = true,
        ).map(::normalizeOpaqueSeedArgb).distinct().take(maxColors)
    }

    internal fun dynamicScheme(
        seedArgb: Int,
        dark: Boolean,
        variant: SchemeVariant,
        contrastLevel: Double,
    ): DynamicScheme {
        val hct = Hct.fromInt(seedArgb)
        return when (variant) {
            SchemeVariant.TONAL_SPOT -> SchemeTonalSpot(hct, dark, contrastLevel)
            SchemeVariant.VIBRANT -> SchemeVibrant(hct, dark, contrastLevel)
            SchemeVariant.EXPRESSIVE -> SchemeExpressive(hct, dark, contrastLevel)
            SchemeVariant.CONTENT -> SchemeContent(hct, dark, contrastLevel)
        }
    }
}
