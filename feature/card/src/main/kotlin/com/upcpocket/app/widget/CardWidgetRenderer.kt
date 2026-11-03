package com.upcpocket.app.widget

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.feature.card.R
import com.upcpocket.app.platform.AccountMask

/**
 * 校园卡余额的 RemoteViews 渲染器。
 *
 * 这是 `:feature:card` 对桌面组件适配层的全部输出：布局、文案、余额/过渡余额字段
 * 与空状态都在本模块；宿主只传入“打开应用”和“刷新”两个 PendingIntent 工厂。
 */
class CardWidgetRenderer(private val context: Context) {

    fun views(
        snapshot: DirectCardSnapshot?,
        refreshing: Boolean,
        failed: Boolean,
        openApp: () -> PendingIntent,
        refresh: () -> PendingIntent,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_card_balance)
        views.setOnClickPendingIntent(R.id.widget_card_root, openApp())

        if (snapshot == null) {
            views.setTextViewText(
                R.id.widget_card_account,
                context.getString(R.string.widget_card_account_none),
            )
            views.setTextViewText(
                R.id.widget_card_balance,
                context.getString(DesignSystemR.string.widget_empty_value),
            )
            views.setViewVisibility(R.id.widget_card_transition, View.GONE)
            WidgetRenderSupport.setMeta(
                views = views,
                viewId = R.id.widget_card_updated,
                text = context.getString(DesignSystemR.string.widget_empty_hint) + "\n" +
                    context.getString(DesignSystemR.string.widget_no_record),
                colorRes = DesignSystemR.color.widget_accent,
            )
            views.setOnClickPendingIntent(R.id.widget_card_refresh, openApp())
            views.setContentDescription(
                R.id.widget_card_refresh,
                context.getString(R.string.widget_open_card),
            )
            views.setFloat(R.id.widget_card_refresh, "setAlpha", 1f)
            return views
        }

        views.setTextViewText(
            R.id.widget_card_account,
            context.getString(
                R.string.widget_card_account,
                AccountMask.mask(snapshot.studentId),
            ),
        )
        views.setTextViewText(
            R.id.widget_card_balance,
            context.getString(R.string.widget_balance_value, snapshot.balanceText),
        )
        // 2×2 只突出主余额；过渡余额仍可在应用里查看。
        views.setViewVisibility(R.id.widget_card_transition, View.GONE)
        views.setTextViewText(
            R.id.widget_card_transition,
            context.getString(R.string.widget_transition, snapshot.transitionText),
        )
        WidgetRenderSupport.setMeta(
            views = views,
            viewId = R.id.widget_card_updated,
            text = WidgetRenderSupport.metaText(context, snapshot.fetchedAt, refreshing, failed),
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
}
