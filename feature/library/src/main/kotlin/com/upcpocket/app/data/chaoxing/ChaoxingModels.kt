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

data class ChaoxingUser(
    /** 学校返回的学号/登录名；只用于与当前统一账号比较，不在 UI 展示。 */
    val sno: String,
)

data class ChaoxingSession(
    /** WebView 登录后的 Chaoxing Cookie；只保存在内存，不写偏好/账号库。 */
    val cookieHeader: String,
    val user: ChaoxingUser,
) {
    override fun toString(): String = "ChaoxingSession(cookieHeader=<redacted>, user=<redacted>)"
}

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

/**
 * 学校座位图画布上的一块座位。
 *
 * 真实 `getdrawseat` 返回的坐标/尺寸是浮点数（例如 `xleft:166.0`、
 * `width:75.0`），旋转角可能是 630 这类超出 0–359 的值。这里保留画布单位，
 * 不用像素/密度单位；缩放只发生在 UI 层。
 */
data class ChaoxingSeatPlace(
    val seatNum: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** 已归一化到 [0, 360)。 */
    val rotate: Float,
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

class ChaoxingException(
    message: String,
    val code: String = "chaoxing_protocol",
) : Exception(message)

class ChaoxingSessionExpiredException : Exception("chaoxing session expired")
