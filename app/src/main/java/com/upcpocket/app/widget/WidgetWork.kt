package com.upcpocket.app.widget

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * 一次性的组件刷新任务。
 *
 * 只有用户点击刷新按钮才会入队；AppWidgetProvider 的 onUpdate / onEnabled / resize /
 * 应用启动都只渲染本地缓存，不隐式联网，也没有周期任务（updatePeriodMillis=0）。
 *
 * 同一类型使用唯一任务名；广播层 tryBegin 已合并连续点击，不会并发打学校接口。
 * 这里用 REPLACE 保证换号后即使旧任务尚在取消/收尾，新绑定的一次刷新也不会被
 * KEEP 语义丢弃；唯一任务名、Provider/Worker FQCN 和组件实例均保持不变。
 */
object WidgetWork {

    const val KEY_KIND = "widget_kind"

    /**
     * 可选的绑定指纹，只用于发现“点击后账号已切换/改密”。不包含学号、密码、
     * Cookie 或 URL；旧版本已排队任务没有该字段，执行时仍读取当前绑定。
     */
    const val KEY_BINDING_TOKEN = "widget_binding_token"

    fun enqueue(
        context: Context,
        kind: WidgetKind,
        bindingToken: String? = null,
    ): Boolean = runCatching {
        val data = Data.Builder()
            .putString(KEY_KIND, kind.key)
            .apply {
                if (bindingToken != null) putString(KEY_BINDING_TOKEN, bindingToken)
            }
            .build()
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInputData(data)
            // 不加 NetworkType 约束：断网时立即失败并给出短提示，而不是让按钮无限等待网络恢复。
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            kind.uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        true
    }.getOrDefault(false)

    fun cancel(context: Context, kind: WidgetKind) {
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(kind.uniqueWorkName)
        }
    }
}
