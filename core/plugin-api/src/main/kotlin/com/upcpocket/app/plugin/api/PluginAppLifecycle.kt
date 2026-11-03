package com.upcpocket.app.plugin.api

/**
 * 可选的应用级生命周期贡献。
 *
 * 插件只有在成功激活后才会收到回调；宿主不再按具体业务类型直接调用某个
 * feature 的初始化方法。回调里的异常由宿主按插件隔离，不得影响其他插件。
 *
 *  - [onAppCreated]：Application 启动、插件已发布后调用（只做轻量初始化）；
 *  - [onAppStart]：可挂起，用于恢复上一进程状态；宿主在后台作用域调用。
 */
interface PluginAppLifecycle {
    fun onAppCreated() = Unit

    suspend fun onAppStart() = Unit
}
