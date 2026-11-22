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

/** The app-widget manager is authoritative for both provider identity and current ownership. */
internal fun widgetIds(context: Context, kind: WidgetKind): IntArray {
    val manager = android.appwidget.AppWidgetManager.getInstance(context.applicationContext)
    return WidgetProviderVariant.forKind(kind).flatMap { variant ->
        manager.getAppWidgetIds(ComponentName(context, variant.providerClass)).asIterable()
    }.distinct().toIntArray()
}

internal fun hasWidgetInstances(context: Context, kind: WidgetKind): Boolean =
    widgetIds(context, kind).isNotEmpty()
