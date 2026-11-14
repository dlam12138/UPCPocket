package com.upcpocket.app.shiguang

import java.math.BigDecimal

enum class ShiguangKind { ACTIVITY, SOCIAL_PRACTICE }
data class ShiguangActivity(
    val id: String,
    val kind: ShiguangKind,
    val title: String,
    val fields: Map<String, String> = emptyMap(),
)
data class ShiguangPage(val page: Int, val totalPages: Int?, val items: List<ShiguangActivity>) {
    val nextPage: Int? get() = totalPages?.takeIf { page < it }?.let { page + 1 }
}
data class ShiguangDetail(val title: String, val description: String?, val fields: Map<String, String>)
data class ShiguangRank(val position: Int, val total: Int)
data class ShiguangModule(val id: Int, val label: String, val hours: BigDecimal?, val credits: BigDecimal?, val rank: ShiguangRank?)
data class ShiguangSummary(val modules: List<ShiguangModule>) {
    val totalHours: BigDecimal? get() = if (modules.size == 5 && modules.all { it.hours != null })
        modules.fold(BigDecimal.ZERO) { total, module -> total + module.hours!! } else null
}
enum class ShiguangFailure(val label: String) {
    CLEANUP_PENDING("验证页面未关闭，请重试"),
    ACCOUNT_UNAVAILABLE("请先保存学校账号"), ACCOUNT_CHANGED("账号已变化，请重新刷新"),
    IDENTITY_UNVERIFIED("身份未核实，暂不能查看我的"), IDENTITY_MISMATCH("登录身份不符，请检查账号"),
    INTERACTION_REQUIRED("请在学校页面继续登录"), INVALID_CREDENTIALS("账号或密码有误"),
    NETWORK("网络连接失败"), TLS("安全连接失败"), ACCESS_DENIED("学校暂不允许访问"),
    AUTH_REQUIRED("登录已失效，请刷新"), PROTOCOL("学校页面发生变化，暂无法读取"), UPSTREAM("学校服务暂不可用"),
}
class ShiguangException(val reason: ShiguangFailure) : Exception(reason.name)

enum class ShiguangTab { ACTIVITIES, MINE }
data class ShiguangState(
    /** Monotonic in-memory UI reset marker; never contains an account identifier. */
    val accountEpoch: Long = 0,
    val accountAvailable: Boolean = false,
    val tab: ShiguangTab = ShiguangTab.ACTIVITIES,
    val keyword: String = "",
    val activities: ShiguangPage? = null,
    val participations: ShiguangPage? = null,
    val summary: ShiguangSummary? = null,
    val selected: ShiguangActivity? = null,
    val detail: ShiguangDetail? = null,
    val busy: Boolean = false,
    val error: ShiguangFailure? = null,
    val identityVerified: Boolean = false,
    val interactionRequired: Boolean = false,
    val interactiveAttempt: ShiguangInteractiveAttempt? = null,
)
