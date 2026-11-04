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

data class NspaceSession(
    val token: String,
    val user: NspaceUser,
)

class NspaceException(
    message: String,
    val code: String = "nspace_protocol",
) : Exception(message)

class NspaceSessionExpiredException : Exception("nspace session expired")
