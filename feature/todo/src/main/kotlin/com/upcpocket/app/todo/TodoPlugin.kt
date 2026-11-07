package com.upcpocket.app.todo

import android.app.PendingIntent
import android.content.Context
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginAppLifecycle
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginBottomNav
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.todo.TodoHomeSection
import com.upcpocket.app.ui.todo.TodoScreen

/** 待办插件公开能力的稳定键。 */
object TodoCapabilities {
    val store = capabilityKey<TodoStore>("todo.store")
}

val TODO_PLUGIN_ID: PluginId = PluginId.parse("todo")

val TODO_ROUTE: PluginRouteId = PluginRouteId.parse("todo.list")

/**
 * 本机待办插件：Room、AlarmManager 调度、通知和 UI 都归本模块。
 *
 * 插件装配不打开数据库；只有激活后才安装进程内桥、建通知渠道并从数据库重建提醒。
 */
class TodoPlugin(
    context: Context,
    openAppIntent: (String) -> PendingIntent,
) : Plugin, PluginUiContributor, PluginAppLifecycle {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = TODO_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(TodoCapabilities.store.id),
    )

    val service: TodoService = TodoService(context, openAppIntent)

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(TodoCapabilities.store, service))

    override fun onAppCreated() {
        TodoRuntime.install(service)
        service.ensureNotificationChannel()
    }

    override suspend fun onAppStart() {
        service.reconcileAll()
    }

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(TODO_ROUTE, TODO_PLUGIN_ID) {
                TodoScreen(store = service)
            },
        )
        registry.addSection(
            PluginSection(
                entryId = UiEntryId.parse("todo.home"),
                pluginId = TODO_PLUGIN_ID,
                surface = UiSurface.HOME_TODO,
                order = 1,
            ) { host ->
                TodoHomeSection(store = service, host = host)
            },
        )
        registry.addBottomNav(
            PluginBottomNav(
                entryId = UiEntryId.parse("todo.tab"),
                pluginId = TODO_PLUGIN_ID,
                order = 7,
                label = "待办",
                icon = UiIcon.TODO,
                route = TODO_ROUTE,
            ),
        )
    }
}
