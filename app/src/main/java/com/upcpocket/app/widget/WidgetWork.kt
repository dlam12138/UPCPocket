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
 * 同一类型使用唯一任务名 + KEEP：连续点击合并成一次网络请求，不会并发打学校接口。
 */
object WidgetWork {

    const val KEY_KIND = "widget_kind"

    fun enqueue(context: Context, kind: WidgetKind): Boolean = runCatching {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_KIND, kind.key)
                    .build(),
            )
            // 不加 NetworkType 约束：断网时立即失败并给出短提示，而不是让按钮无限等待网络恢复。
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            kind.uniqueWorkName,
            ExistingWorkPolicy.KEEP,
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
