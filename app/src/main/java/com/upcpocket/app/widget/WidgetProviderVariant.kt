package com.upcpocket.app.widget

import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import com.upcpocket.app.designsystem.WidgetAppearance

/** Launcher entry points are presentation variants of one shared business widget kind. */
enum class WidgetProviderVariant(
    val kind: WidgetKind,
    val providerClass: Class<out AppWidgetProvider>,
    val fixedAppearance: WidgetAppearance? = null,
) {
    CARD(WidgetKind.CARD, CardBalanceWidgetProvider::class.java),
    CARD_GLASS(WidgetKind.CARD, CardBalanceGlassWidgetProvider::class.java, WidgetAppearance.FROSTED_GLASS),
    POWER(WidgetKind.POWER, PowerWidgetProvider::class.java),
    POWER_GLASS(WidgetKind.POWER, PowerGlassWidgetProvider::class.java, WidgetAppearance.FROSTED_GLASS),
    TIMETABLE(WidgetKind.TIMETABLE, TimetableWidgetProvider::class.java),
    TIMETABLE_GLASS(WidgetKind.TIMETABLE, TimetableGlassWidgetProvider::class.java, WidgetAppearance.FROSTED_GLASS),
    TIMETABLE_WEEK(WidgetKind.TIMETABLE, TimetableWeekWidgetProvider::class.java),
    TIMETABLE_TODAY_TOMORROW(
        WidgetKind.TIMETABLE,
        TimetableTodayTomorrowWidgetProvider::class.java,
    ),
    TODO(WidgetKind.TODO, TodoWidgetProvider::class.java),
    TODO_GLASS(WidgetKind.TODO, TodoGlassWidgetProvider::class.java, WidgetAppearance.FROSTED_GLASS),
    ;

    fun resolveAppearance(globalAppearance: WidgetAppearance): WidgetAppearance =
        fixedAppearance ?: globalAppearance

    companion object {
        fun forKind(kind: WidgetKind): List<WidgetProviderVariant> =
            entries.filter { it.kind == kind }

        fun standard(kind: WidgetKind): WidgetProviderVariant =
            entries.first { it.kind == kind && it.fixedAppearance == null }

        fun fromProvider(provider: ComponentName?): WidgetProviderVariant? =
            provider?.let { component -> entries.firstOrNull { it.providerClass.name == component.className } }
    }
}

/**
 * 只有原“今日课表”两版有上下课倒计时语义，需要课时边界与分钟重绘；
 * 今明版与本周版只随上海午夜、同步和尺寸/设置变化重绘。保留为显式判断，
 * 避免以后新增 TIMETABLE 变量时被“非周版即今日版”的过滤误纳入。
 */
internal val WidgetProviderVariant.isTimetableTodayVariant: Boolean
    get() = this == WidgetProviderVariant.TIMETABLE ||
        this == WidgetProviderVariant.TIMETABLE_GLASS

/** The app-widget manager is authoritative for both provider identity and current ownership. */
internal fun widgetIds(context: Context, kind: WidgetKind): IntArray {
    val manager = android.appwidget.AppWidgetManager.getInstance(context.applicationContext)
    return WidgetProviderVariant.forKind(kind).flatMap { variant ->
        manager.getAppWidgetIds(ComponentName(context, variant.providerClass)).asIterable()
    }.distinct().toIntArray()
}

internal fun hasWidgetInstances(context: Context, kind: WidgetKind): Boolean =
    widgetIds(context, kind).isNotEmpty()
