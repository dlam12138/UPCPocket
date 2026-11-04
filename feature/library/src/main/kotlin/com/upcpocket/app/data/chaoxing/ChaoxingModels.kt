package com.upcpocket.app.data.chaoxing

/**
 * 两个 Chaoxing 座位应用。它们共享大部分前端/接口形状，但 room/list 的
 * `seatId` 参数和 room/info 的提交方式有实际差异，必须保留。
 */
enum class ChaoxingSeatKind(
    val api: String,
    val campusLabel: String,
    val officialUrl: String,
) {
    SEAT(
        api = "seat",
        campusLabel = "古镇口东区",
        officialUrl = "https://office.chaoxing.com/front/third/apps/seat/index" +
            "?fidEnc=e32f8fbde51f9dba&mappId=4197453",
    ),
    SEATENGINE(
        api = "seatengine",
        campusLabel = "求是",
        officialUrl = "https://office.chaoxing.com/front/third/apps/seatengine/index" +
            "?fidEnc=e32f8fbde51f9dba&mappId=20853788&seatId=4258",
    ),
}

/** reservepc 的两个独立租户；共享列表协议，但 reserveId/mappId/页面不同。 */
enum class ChaoxingTenant(
    val reserveId: String,
    val mappId: String,
    val campusLabel: String,
    val officialUrl: String,
) {
    GUZHENKOU(
        reserveId = "13492",
        mappId = "10570653",
        campusLabel = "古镇口东区",
        officialUrl = "https://reserve.chaoxing.com/front/web/apps/reservepc/index" +
            "?reserveId=13492&resvAppId=13492&fidEnc=e32f8fbde51f9dba&mappId=10570653",
    ),
    QIUSHI(
        reserveId = "31841",
        mappId = "20875315",
        campusLabel = "求是",
        officialUrl = "https://reserve.chaoxing.com/front/web/apps/reservepc/index" +
            "?reserveId=31841&resvAppId=31841&fidEnc=e32f8fbde51f9dba&mappId=20875315",
    ),
}

data class ChaoxingUser(
    /** 学校返回的学号/登录名；只用于与当前统一账号比较，不在 UI 展示。 */
    val sno: String,
)

data class ChaoxingSession(
    /** WebView 登录后的 Chaoxing Cookie；只保存在内存，不写偏好/账号库。 */
    val cookieHeader: String,
    val user: ChaoxingUser,
)

data class ChaoxingSeatRoom(
    val id: Int,
    val name: String,
    val floor: String,
    val capacity: Int,
    val usedCount: Int?,
    val startMinute: Int,
    val endMinute: Int,
)

data class ChaoxingSeatRoomPage(
    val rooms: List<ChaoxingSeatRoom>,
    val totalRow: Int,
    val totalPage: Int,
)

data class ChaoxingSeatPlace(
    val seatNum: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val rotate: Int,
)

data class ChaoxingSeatRoomDetail(
    val roomId: Int,
    val name: String,
    val capacity: Int,
    val timeUnit: Int,
    val startMinute: Int,
    val endMinute: Int,
    val places: List<ChaoxingSeatPlace>,
)

data class ChaoxingTimeInterval(
    val startMinute: Int,
    val endMinute: Int,
)

data class ChaoxingReserveInterval(
    /** ISO weekday: 1=Monday ... 7=Sunday. */
    val weekday: Int,
    val start: String,
    val end: String,
)

data class ChaoxingReserveRoom(
    val id: Int,
    val name: String,
    val minUser: Int?,
    val maxUser: Int?,
    val reserveNum: Int?,
    val details: String,
    val intervals: List<ChaoxingReserveInterval>,
)

data class ChaoxingReserveRoomPage(
    val rooms: List<ChaoxingReserveRoom>,
)

class ChaoxingException(
    message: String,
    val code: String = "chaoxing_protocol",
) : Exception(message)

class ChaoxingSessionExpiredException : Exception("chaoxing session expired")
