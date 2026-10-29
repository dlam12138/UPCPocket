package com.upcpocket.app.data.library

import java.io.IOException

/**
 * 图书馆座位预约的数据模型。
 *
 * 字段名尽量对应学校页面实际返回（`/rest/v2/...`），但这里只保留展示和提交
 * 真正需要的部分。没有把页面里的内部字段整包抄进来，避免把无关个人数据带进内存。
 */
data class LibraryUser(
    val id: String,
    val username: String,
    val name: String,
)

data class LibraryFilters(
    val dates: List<String>,
    val buildings: List<LibraryBuilding>,
) {
    init {
        require(dates.isNotEmpty()) { "学校未返回可预约日期" }
        require(buildings.isNotEmpty()) { "学校未返回馆区" }
    }
}

data class LibraryBuilding(val id: String, val name: String)

data class LibraryFloor(val floorNum: Int, val floorName: String)

data class LibraryRoom(
    val roomId: String,
    val name: String,
    val floor: Int,
    val floorName: String? = null,
)

data class LibrarySeat(
    val id: String,
    val name: String,
    val row: Int,
    val col: Int,
    val type: String,
    val enabled: Boolean,
    val status: String,
    val localOnly: Boolean,
) {
    val isSeat: Boolean get() = type.equals("seat", ignoreCase = true)
    val selectable: Boolean get() = isSeat && enabled && !localOnly && status in setOf("FREE", "YESSELECT")
}

data class LibrarySeatLayout(
    val rows: Int,
    val cols: Int,
    val seats: List<LibrarySeat>,
)

data class LibraryTimeOption(val id: String, val value: String)

data class LibraryReservation(
    val id: String,
    val date: String,
    val begin: String,
    val end: String,
    val location: String,
    val status: String,
) {
    val statusLabel: String
        get() = when (status) {
            "RESERVE" -> "已预约"
            "CHECK_IN" -> "履约中"
            "AWAY" -> "暂离"
            "COMPLETE" -> "已履约"
            "INCOMPLETE" -> "早退"
            "MISS" -> "失约"
            "CANCEL" -> "已取消"
            "STOP" -> "结束使用"
            else -> status.ifBlank { "未知状态" }
        }
    val cancellable: Boolean get() = status == "RESERVE"
}

/** 学校返回的 token 或业务错误；调用方必须把失败显示出来，不能当成空数据。 */
open class LibrarySeatException(message: String, cause: Throwable? = null) : IOException(message, cause)

class LibrarySessionExpiredException(message: String = "登录已失效，请重新通过学校统一认证登录") :
    LibrarySeatException(message)

class LibraryCaptchaRequiredException :
    LibrarySeatException("学校当前启用了图形验证码，暂不能在原生界面提交；请先在官方页面完成预约")

object LibrarySeatStatus {
    fun label(status: String): String = when (status) {
        "FREE" -> "可用"
        "AWAY" -> "暂离"
        "IN_USE" -> "使用中"
        "BOOKED" -> "已预约"
        "FULL" -> "已满"
        "NOSELECT" -> "不可选"
        "YESSELECT" -> "已选"
        else -> status.ifBlank { "未知" }
    }
}

/**
 * 预约成功与否的核对口径。
 *
 * 只用“日期 + 时间”匹配是不够的：同一时段可能本来就有别的座位预约，
 * 旧列表也会被当成“新预约已出现”。这里要求座位身份也一致，并要求调用方
 * 用同步前的 id 集合排除旧记录。
 */
object LibraryReservationMatch {
    fun matches(
        reservation: LibraryReservation,
        date: String,
        begin: String,
        end: String,
        seatName: String,
    ): Boolean =
        reservation.date.trim() == date.trim() &&
            minutes(reservation.begin) == minutes(begin) &&
            minutes(reservation.end) == minutes(end) &&
            seatMatches(reservation.location, seatName)

    /** 座位标识可能是“3F-A01”而本地是“A01”，做包含匹配；空名一律不匹配。 */
    fun seatMatches(location: String, seatName: String): Boolean {
        val a = location.trim().lowercase()
        val b = seatName.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /** "8:00" / "08:00" / "08:00:00" → 分钟数；解析失败返回 null（不等于匹配）。 */
    fun minutes(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}
