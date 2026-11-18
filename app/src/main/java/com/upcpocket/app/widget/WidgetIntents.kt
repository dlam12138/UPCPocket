package com.upcpocket.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.upcpocket.app.MainActivity
import com.upcpocket.app.todo.TodoLaunchContract
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 组件打开应用时只允许这两个固定目标，不接受 URL、路由字符串或任意 Intent。 */
enum class WidgetLaunchTarget(internal val activityRequestBase: Int) {
    CARD_BALANCE(100_000),
    POWER_TANGDAOWAN(200_000),
    TIMETABLE(300_000),
    TODO(700_000),
    ;

    companion object {
        fun fromIntent(intent: Intent?): WidgetLaunchTarget? {
            if (intent == null) return null
            if (intent.action != WidgetIntents.ACTION_OPEN_FROM_WIDGET) return null
            // 组件入口不需要 data/URI；带 URL 的 Intent 直接拒绝，避免被当成转发目标。
            if (intent.data != null) return null
            val raw = intent.getStringExtra(WidgetIntents.EXTRA_WIDGET_TARGET) ?: return null
            return entries.firstOrNull { it.name == raw }
        }
    }
}

/**
 * 组件所有 PendingIntent 的唯一构造点。
 *
 * 安全约束：
 *  - 全部显式组件（MainActivity / 各自的 AppWidgetProvider / 私有待办 receiver）；
 *  - 仅待办 checkbox response 使用 FLAG_MUTABLE，以接收系统填入的 checked 值；
 *  - 校园服务刷新广播只带 appWidgetId，不携带业务标识或 URL；
 *  - 其他导航和刷新 PendingIntent 使用 FLAG_IMMUTABLE。
 */
object WidgetIntents {

    const val ACTION_OPEN_FROM_WIDGET = "com.upcpocket.app.action.OPEN_FROM_WIDGET"
    const val EXTRA_WIDGET_TARGET = "com.upcpocket.app.extra.WIDGET_TARGET"

    private const val REFRESH_REQUEST_BASE_CARD = 400_000
    private const val REFRESH_REQUEST_BASE_POWER = 500_000
    private const val REFRESH_REQUEST_BASE_TIMETABLE = 600_000
    private const val ACTION_TODO_COMPLETE = "com.upcpocket.app.action.COMPLETE_TODO_WIDGET_TASK"

    internal fun activityRequestCode(target: WidgetLaunchTarget, widgetId: Int): Int =
        target.activityRequestBase + widgetId * 10 + 1

    internal fun refreshRequestCode(kind: WidgetKind, widgetId: Int): Int =
        when (kind) {
            WidgetKind.CARD -> REFRESH_REQUEST_BASE_CARD + widgetId
            WidgetKind.POWER -> REFRESH_REQUEST_BASE_POWER + widgetId
            WidgetKind.TIMETABLE -> REFRESH_REQUEST_BASE_TIMETABLE + widgetId
            WidgetKind.TODO -> error("Todo widgets do not use refresh PendingIntents")
        }

    internal fun activityIntent(
        context: Context,
        target: WidgetLaunchTarget,
        widgetId: Int,
    ): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_FROM_WIDGET
        putExtra(EXTRA_WIDGET_TARGET, target.name)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }

    internal fun refreshIntent(
        context: Context,
        kind: WidgetKind,
        widgetId: Int,
        variant: WidgetProviderVariant = WidgetProviderVariant.standard(kind),
    ): Intent = Intent(context, variant.providerClass).apply {
        check(kind != WidgetKind.TODO) { "Todo widgets do not use refresh PendingIntents" }
        require(variant.kind == kind)
        action = kind.refreshAction
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    }

    fun openApp(
        context: Context,
        target: WidgetLaunchTarget,
        widgetId: Int,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        activityRequestCode(target, widgetId),
        activityIntent(context, target, widgetId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun refresh(
        context: Context,
        kind: WidgetKind,
        widgetId: Int,
        variant: WidgetProviderVariant = WidgetProviderVariant.standard(kind),
    ): PendingIntent {
        require(kind != WidgetKind.TODO) { "Todo widgets do not use refresh PendingIntents" }
        return PendingIntent.getBroadcast(
            context,
            refreshRequestCode(kind, widgetId),
            refreshIntent(context, kind, widgetId, variant),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun openTodoTask(context: Context, widgetId: Int, taskId: String): PendingIntent {
        require(widgetId > 0)
        require(isFullUuid(taskId))
        val intent = Intent(context, MainActivity::class.java).apply {
            action = TodoLaunchContract.ACTION_OPEN
            data = todoWidgetUri("open", widgetId.toString(), taskId)
            putExtra(TodoLaunchContract.EXTRA_TASK_ID, taskId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun createTodoTask(context: Context, widgetId: Int): PendingIntent {
        require(widgetId > 0)
        val requestId = UUID.nameUUIDFromBytes(
            "upcpocket.todo.widget.create:$widgetId".toByteArray(StandardCharsets.UTF_8),
        ).toString()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = TodoLaunchContract.ACTION_CREATE
            data = todoWidgetUri("create", widgetId.toString(), requestId)
            putExtra(TodoLaunchContract.EXTRA_CREATE_REQUEST_ID, requestId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Only this explicit action is mutable so RemoteViews can fill in EXTRA_CHECKED. */
    fun completeTodoTask(
        context: Context,
        widgetId: Int,
        taskId: String,
        revision: Int,
    ): PendingIntent {
        val intent = todoCompletionIntent(context, widgetId, taskId, revision)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE,
        )
    }

    internal fun todoCompletionIntent(
        context: Context,
        widgetId: Int,
        taskId: String,
        revision: Int,
    ): Intent {
        require(widgetId > 0)
        require(isFullUuid(taskId))
        require(revision > 0)
        return Intent(context, TodoWidgetCompleteReceiver::class.java).apply {
            action = ACTION_TODO_COMPLETE
            data = todoWidgetUri(
                "complete",
                widgetId.toString(),
                taskId,
                revision.toString(),
                "true",
            )
            `package` = context.packageName
        }
    }

    internal fun isTodoCompletionAction(action: String?): Boolean = action == ACTION_TODO_COMPLETE

    /** Shared actions are accepted only for a currently installed provider of this business kind. */
    fun isOwnedBy(context: Context, kind: WidgetKind, widgetId: Int): Boolean {
        if (widgetId <= 0) return false
        val manager = AppWidgetManager.getInstance(context)
        val provider = manager.getAppWidgetInfo(widgetId)?.provider ?: return false
        val variant = WidgetProviderVariant.fromProvider(provider) ?: return false
        return variant.kind == kind && isOwnedBy(context, variant, widgetId)
    }

    /** Provider entry points must reject ids belonging to their sibling appearance variant. */
    fun isOwnedBy(context: Context, variant: WidgetProviderVariant, widgetId: Int): Boolean {
        if (widgetId <= 0) return false
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, variant.providerClass)
        return manager.getAppWidgetIds(provider).contains(widgetId) &&
            manager.getAppWidgetInfo(widgetId)?.provider == provider
    }

    internal fun isFullUuid(value: String): Boolean = FULL_UUID.matches(value)

    private fun todoWidgetUri(vararg segments: String): Uri = Uri.Builder()
        .scheme("upcpocket")
        .authority("todo-widget")
        .apply { segments.forEach { segment -> appendPath(segment) } }
        .build()

    private val FULL_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
