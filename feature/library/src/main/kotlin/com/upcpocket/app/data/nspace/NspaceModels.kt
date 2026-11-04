package com.upcpocket.app.data.nspace

/** nspace 馆区/空间类型/房间的只读模型；所有字段来自学校接口，不猜测缺省值。 */
data class NspaceVenue(
    val id: String,
    val name: String,
)

data class NspaceRoomType(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

data class NspaceRoom(
    val id: String,
    val name: String,
    val floor: String,
    val minUser: Int?,
    val maxUser: Int?,
    val area: String?,
    val status: String?,
    val seatOpen: String?,
    val openTime: String?,
    val closeTime: String?,
    val timeSlice: String?,
    val isSign: Boolean?,
)

data class NspaceRoomPage(
    val rooms: List<NspaceRoom>,
    val currentPage: Int,
    val totalPage: Int,
    val totalCount: Int,
)

data class NspaceUser(
    val id: String,
    val username: String,
    val name: String,
)

/** 本人预约记录；不解析门禁密码等敏感字段。 */
data class NspaceReservation(
    val id: String,
    val roomName: String,
    val onDate: String,
    val begin: Int,
    val end: Int,
    val status: Int,
    val statusDescription: String?,
    val master: Boolean,
)

data class NspaceReservationPage(
    val reservations: List<NspaceReservation>,
    val currentPage: Int,
    val totalPage: Int,
    val totalCount: Int,
)

data class NspaceInterval(val start: Int, val end: Int)

/** 房间详情中的预约规则/时段；时间单位为当天绝对分钟。 */
data class NspaceRoomDetail(
    val roomId: String,
    val name: String,
    val openTime: Int,
    val closeTime: Int,
    val timeSlice: Int,
    val disableTimes: List<NspaceInterval>,
    val minTime: Int,
    val maxTime: Int,
    val minUser: Int,
    val maxUser: Int,
    val seatOpen: String,
    val annexRequired: Boolean,
)

data class NspaceUseType(
    val id: String,
    val name: String,
)

data class NspaceFormOption(
    val name: String,
    val value: String,
)

data class NspaceFormField(
    val field: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val value: String,
    val options: List<NspaceFormOption>,
)

/** 公开前端 saveReservation 的固定字段；字段值来自用户在当前表单中的选择。 */
data class NspaceBookingRequest(
    val begin: Int,
    val end: Int,
    val onDate: String,
    val roomId: String,
    val useType: String,
    val participants: String,
    val filePath: String,
    val source: String,
    val seatNo: String,
    val teamId: Int,
    val theme: String?,
    val phone: String?,
    val outline: String?,
    val extraField: Map<String, String>,
)

data class NspaceSession(
    val token: String,
    val user: NspaceUser,
)

class NspaceException(
    message: String,
    val code: String = "nspace_protocol",
) : Exception(message)

class NspaceSessionExpiredException : Exception("nspace session expired")
