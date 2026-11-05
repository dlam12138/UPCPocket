package com.upcpocket.app.widget

import android.app.PendingIntent
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.feature.card.R

/**
 * 校园卡余额的 RemoteViews 渲染器。
 *
 * 目标与缓存都必须来自当前统一账号绑定。没有成功快照但已绑定时，刷新按钮仍可
 * 触发后台查询；清理中/无绑定时只显示无身份的空状态，绝不保留旧学号或旧金额。
 *
 * 2×2 只保留“校园卡 / 余额 / 更新时间”三层。脱敏学号不再常驻组件，账号目标与
 * 真实绑定状态仍完整保留在应用页面和账号设置。
 */
class CardWidgetRenderer(private val context: Context) {

    fun views(
        status: CardBindingStatus,
        snapshot: DirectCardSnapshot?,
        refreshing: Boolean,
        failed: Boolean,
        openApp: () -> PendingIntent,
        refresh: () -> PendingIntent,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_card_balance)
        views.setOnClickPendingIntent(R.id.widget_card_root, openApp())
        val binding = status.binding
        val ownedSnapshot = snapshot?.takeIf {
            status.availability == CardBindingAvailability.READY &&
                binding != null &&
                it.sameOwnerAs(binding)
        }

        val primary = context.getColor(DesignSystemR.color.widget_primary)
        val secondary = context.getColor(DesignSystemR.color.widget_secondary)
        views.setTextColor(R.id.widget_card_balance, primary)

        if (binding == null || ownedSnapshot == null) {
            val (headline, stateHint) = when (status.availability) {
                CardBindingAvailability.READY ->
                    context.getString(DesignSystemR.string.widget_empty_value) to
                        context.getString(R.string.widget_card_bound_empty)
                CardBindingAvailability.LOADING ->
                    context.getString(R.string.widget_card_loading) to
                        context.getString(R.string.widget_card_loading_hint)
                CardBindingAvailability.CLEANING ->
                    context.getString(R.string.widget_card_cleaning) to
                        context.getString(R.string.widget_card_cleaning_hint)
                CardBindingAvailability.NEEDS_RECONFIGURATION ->
                    context.getString(R.string.widget_card_reconfigure) to
                        context.getString(R.string.widget_card_reconfigure_hint)
                CardBindingAvailability.STUDENT_ID_UNAVAILABLE ->
                    context.getString(R.string.widget_card_no_student_id) to
                        context.getString(R.string.widget_card_no_student_hint)
                CardBindingAvailability.NO_ACCOUNT ->
                    context.getString(R.string.widget_card_no_account) to
                        context.getString(R.string.widget_card_no_account_hint)
            }
            views.setTextViewText(R.id.widget_card_balance, headline)
            views.setTextViewTextSize(
                R.id.widget_card_balance,
                TypedValue.COMPLEX_UNIT_SP,
                HEADLINE_TEXT_SIZE_SP,
            )
            views.setViewVisibility(R.id.widget_card_unit, View.GONE)
            val hint = when {
                refreshing -> context.getString(DesignSystemR.string.widget_refreshing)
                failed -> context.getString(DesignSystemR.string.widget_refresh_failed)
                else -> stateHint
            }
            WidgetRenderSupport.setMeta(
                views = views,
                viewId = R.id.widget_card_updated,
                text = hint,
                colorRes = when {
                    refreshing -> DesignSystemR.color.widget_accent
                    failed -> DesignSystemR.color.widget_error
                    else -> DesignSystemR.color.widget_tertiary
                },
            )

            val canRefresh = binding != null &&
                status.availability == CardBindingAvailability.READY
            if (canRefresh) {
                views.setOnClickPendingIntent(R.id.widget_card_refresh, refresh())
                views.setContentDescription(
                    R.id.widget_card_refresh,
                    context.getString(R.string.widget_refresh_card),
                )
            } else {
                // 无绑定/清理中不启用网络刷新，点击只打开应用处理账号状态。
                views.setOnClickPendingIntent(R.id.widget_card_refresh, openApp())
                views.setContentDescription(
                    R.id.widget_card_refresh,
                    context.getString(R.string.widget_open_card),
                )
            }
            views.setFloat(R.id.widget_card_refresh, "setAlpha", if (refreshing) 0.5f else 1f)
            return views
        }

        val value = ownedSnapshot.balanceText
        val fontScale = context.resources.configuration.fontScale
        val numberSize = numberTextSize(value, fontScale)
        views.setTextViewText(R.id.widget_card_balance, value)
        views.setTextViewTextSize(
            R.id.widget_card_balance,
            TypedValue.COMPLEX_UNIT_SP,
            numberSize,
        )
        views.setTextViewTextSize(
            R.id.widget_card_unit,
            TypedValue.COMPLEX_UNIT_SP,
            unitTextSize(numberSize),
        )
        views.setTextColor(R.id.widget_card_unit, secondary)
        views.setViewVisibility(R.id.widget_card_unit, View.VISIBLE)

        val meta = WidgetRenderSupport.metaText(
            context = context,
            fetchedAt = ownedSnapshot.fetchedAt,
            refreshing = refreshing,
            failed = failed,
        )
        val historical = ownedSnapshot.credentialGeneration != binding.credentialGeneration.value
        WidgetRenderSupport.setMeta(
            views = views,
            viewId = R.id.widget_card_updated,
            text = if (historical && !refreshing && !failed) {
                context.getString(R.string.widget_card_history, meta)
            } else {
                meta
            },
            colorRes = WidgetRenderSupport.metaColor(refreshing, failed),
        )

        views.setOnClickPendingIntent(R.id.widget_card_refresh, refresh())
        views.setContentDescription(
            R.id.widget_card_refresh,
            context.getString(R.string.widget_refresh_card),
        )
        views.setFloat(R.id.widget_card_refresh, "setAlpha", if (refreshing) 0.5f else 1f)
        return views
    }

    /**
     * 大数字按字符数分档，并在 1.3× 以上字体再缩一档；不是无限 autoSize。
     * 6 位以内保持 36sp，长金额逐档收缩，仍保证单行；单位同步缩小。
     */
    internal fun numberTextSize(value: String, fontScale: Float = 1f): Float {
        val base = when {
            value.length <= 6 -> 36f
            value.length <= 8 -> 30f
            value.length <= 10 -> 25f
            else -> 19f
        }
        return if (fontScale < 1.3f) {
            base
        } else {
            when {
                value.length <= 6 -> base * 0.65f
                value.length <= 8 -> base * 0.55f
                else -> base * 0.5f
            }
        }
    }

    internal fun unitTextSize(numberSize: Float): Float =
        (numberSize * 0.42f).coerceIn(11f, 15f)

    private companion object {
        const val HEADLINE_TEXT_SIZE_SP = 20f
    }
}
