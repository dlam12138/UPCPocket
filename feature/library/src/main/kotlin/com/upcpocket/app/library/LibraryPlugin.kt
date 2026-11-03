package com.upcpocket.app.library

import android.content.Context
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey

/** 图书馆插件公开能力的稳定键。 */
object LibraryCapabilities {
    val seat = capabilityKey<LibraryService>("library.seat")
}

/**
 * 图书馆座位插件。
 *
 * 登录仍走学校官方 WebView 页面；插件只持有内存会话与加密持久副本，
 * 构造时不登录、不联网、不发预约请求。
 */
class LibraryPlugin(
    context: Context,
    rememberLogin: suspend () -> Boolean,
) : Plugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = PluginId.parse("library"),
        version = "1.0.0",
        capabilities = setOf(LibraryCapabilities.seat.id),
    )

    val service: LibraryService = LibraryService(
        repository = LibrarySeatRepository(),
        sessionStore = SchoolSessionStore(context.applicationContext),
        rememberLogin = rememberLogin,
    )

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(LibraryCapabilities.seat, service))
}
