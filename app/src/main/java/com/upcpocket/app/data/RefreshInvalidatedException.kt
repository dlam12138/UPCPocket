package com.upcpocket.app.data

/**
 * 一次刷新在返回前被用户清除/替换了目标，结果不得再写回。
 *
 * 这是内部协作信号，不面向用户展示；页面和组件都应静默丢弃，而不是把上一次查询的
 * 结果重新塞回已经清除的缓存。
 */
class RefreshInvalidatedException : java.io.IOException("本次查询已失效")
