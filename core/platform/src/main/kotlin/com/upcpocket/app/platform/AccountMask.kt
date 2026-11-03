package com.upcpocket.app.platform

/** 账号的展示用摘要：只保留首尾字符，中间打码。 */
object AccountMask {
    /**
     * 目的是让用户能核对“现在登录的是不是我”，而不是把学工号完整写进界面或日志。
     */
    fun mask(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.length <= 2) return value.first() + "•"
        if (value.length <= 6) return value.first() + "••" + value.last()
        return value.take(2) + "••••" + value.takeLast(2)
    }
}
