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
    /** 学校 stats2 的真实统计；缺失时 UI 不能猜成 0。 */
    val totalSeats: Int? = null,
    val freeSeats: Int? = null,
    val reserved: Int? = null,
    val inUse: Int? = null,
    val away: Int? = null,
    val disabled: Int? = null,
    /** 学校 stats2 的 maxHour；只在服务端提供时展示，不据此臆造限制。 */
    val maxHour: Int? = null,
) {
    val floorLabel: String
        get() = floorName?.takeIf { it.isNotBlank() } ?: if (floor > 0) "楼层 $floor" else "楼层未提供"

    val remainingLabel: String
        get() = when {
            freeSeats != null && totalSeats != null -> "余 $freeSeats / 共 $totalSeats 座"
            freeSeats != null -> "余 $freeSeats 座"
            totalSeats != null -> "共 $totalSeats 座"
            else -> "座位统计待查询"
        }

    val maxDurationLabel: String?
        get() = maxHour?.takeIf { it > 0 }?.let { "学校单次最长 $it 小时" }

    val occupancy: Float?
        get() {
            val total = totalSeats ?: return null
            val free = freeSeats ?: return null
            if (total <= 0) return null
            return (free.toFloat() / total).coerceIn(0f, 1f)
        }
}

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

/**
 * 一次「按时段找」查询的完整身份。
 *
 * 分页、迟到响应与最终确认都要拿它和账号/会话代次一起复核：换馆区、房间、
 * 日期、时段或模式后的旧结果不能混入新条件。beginMinute/endMinute 是
 * HH:mm 转成的当天分钟数（学校 searchSeats 路径参数单位，实测 840=14:00）。
 */
data class LibraryTimeSearchKey(
    val buildingId: String,
    val roomId: String,
    val date: String,
    val beginMinute: Int,
    val endMinute: Int,
    val beginText: String,
    val endText: String,
)

/**
 * 本机个人隐藏座位（唐岛湾 libseat）。
 *
 * 不是学校封禁/账号数据：只落本机，退出或换号不删除，可逐个/全部恢复。
 * 命名空间 = system + buildingId + roomId + seatId；nameSnapshot 只是座号展示
 * 快照，展示时以当前官方布局名称优先。
 */
data class LibraryHiddenSeat(
    val system: String,
    val buildingId: String,
    val roomId: String,
    val seatId: String,
    val nameSnapshot: String,
) {
    companion object {
        const val SYSTEM_LIBSEAT = "libseat"
    }
}

/**
 * 按时段找的一页真实结果。
 *
 * seats 是学校 `data.seats`（key=row*1000+col）还原出的座位；page/hasMore
 * 来自学校 `data.page`/`data.hasMore`，不能由「本页 N 条」臆断。
 */
data class LibraryTimeSearchPage(
    val seats: List<LibrarySeat>,
    val page: Int,
    val hasMore: Boolean,
)

/**
 * 一次按时段查询累积出的、去重后的候选状态。
 *
 * status 只由服务端查询推进；部分分页失败时保留同条件已读候选并标记
 * partialFailure，hasMore=true 时仍保留加载更多，不伪造 0 或「共 N 个」。
 */
data class LibraryTimeSearchResult(
    val key: LibraryTimeSearchKey,
    val seats: List<LibrarySeat> = emptyList(),
    val loadedPages: Int = 0,
    val nextPage: Int = 1,
    val hasMore: Boolean = false,
    val complete: Boolean = false,
    val partialFailure: Boolean = false,
    val pageLimitReached: Boolean = false,
    val failed: Boolean = false,
    val failureMessage: String? = null,
    val querying: Boolean = false,
)

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

class LibrarySessionExpiredException(message: String = "登录已失效，请重新登录") :
    LibrarySeatException(message)

class LibraryCaptchaRequiredException :
    LibrarySeatException("需要图形验证码，请在学校页面预约。")

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
