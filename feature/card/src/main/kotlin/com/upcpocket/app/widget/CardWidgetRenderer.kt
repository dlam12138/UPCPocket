package com.upcpocket.app.widget

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.feature.card.R
import com.upcpocket.app.platform.AccountMask

/**
 * 校园卡余额的 RemoteViews 渲染器。
 *
 * 目标与缓存都必须来自当前统一账号绑定。没有成功快照但已绑定时，刷新按钮仍可
 * 触发后台查询；清理中/无绑定时只显示无身份的空状态，绝不保留旧学号或旧金额。
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

        if (binding == null || ownedSnapshot == null) {
            views.setTextViewText(
                R.id.widget_card_account,
                if (status.availability == CardBindingAvailability.READY && binding != null) {
                    // 已绑定但还没查成功时也展示脱敏绑定，用户能确认目标是当前账号。
                    context.getString(R.string.widget_card_account, AccountMask.mask(binding.studentId))
                } else {
                    context.getString(accountLabel(status.availability))
                },
            )
            views.setTextViewText(
                R.id.widget_card_balance,
                context.getString(DesignSystemR.string.widget_empty_value),
            )
            views.setViewVisibility(R.id.widget_card_transition, View.GONE)
            val canRefresh = binding != null && status.availability == CardBindingAvailability.READY
            WidgetRenderSupport.setMeta(
                views = views,
                viewId = R.id.widget_card_updated,
                text = when (status.availability) {
                    CardBindingAvailability.READY ->
                        context.getString(R.string.widget_card_bound_empty)
                    CardBindingAvailability.LOADING ->
                        context.getString(R.string.widget_card_loading)
                    CardBindingAvailability.CLEANING ->
                        context.getString(R.string.widget_card_cleaning)
                    CardBindingAvailability.NEEDS_RECONFIGURATION ->
                        context.getString(R.string.widget_card_reconfigure)
                    CardBindingAvailability.STUDENT_ID_UNAVAILABLE ->
                        context.getString(R.string.widget_card_no_student_id)
                    CardBindingAvailability.NO_ACCOUNT ->
                        context.getString(R.string.widget_card_no_account)
                },
                colorRes = if (failed) DesignSystemR.color.widget_error else DesignSystemR.color.widget_accent,
            )
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
            views.setFloat(R.id.widget_card_refresh, "setAlpha", if (refreshing) 0.55f else 1f)
            return views
        }

        views.setTextViewText(
            R.id.widget_card_account,
            context.getString(R.string.widget_card_account, AccountMask.mask(ownedSnapshot.studentId)),
        )
        views.setTextViewText(
            R.id.widget_card_balance,
            context.getString(R.string.widget_balance_value, ownedSnapshot.balanceText),
        )
        // 2×2 只突出主余额；过渡余额仍可在应用里查看。
        views.setViewVisibility(R.id.widget_card_transition, View.GONE)
        views.setTextViewText(
            R.id.widget_card_transition,
            context.getString(R.string.widget_transition, ownedSnapshot.transitionText),
        )
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
        views.setFloat(R.id.widget_card_refresh, "setAlpha", if (refreshing) 0.55f else 1f)
        return views
    }

    private fun accountLabel(availability: CardBindingAvailability): Int = when (availability) {
        CardBindingAvailability.READY -> R.string.widget_card_account_bound
        CardBindingAvailability.LOADING -> R.string.widget_card_account_loading
        CardBindingAvailability.CLEANING -> R.string.widget_card_account_cleaning
        CardBindingAvailability.NEEDS_RECONFIGURATION -> R.string.widget_card_account_reconfigure
        CardBindingAvailability.STUDENT_ID_UNAVAILABLE -> R.string.widget_card_account_no_student
        CardBindingAvailability.NO_ACCOUNT -> R.string.widget_card_account_none
    }
}
