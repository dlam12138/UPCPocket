package com.upcpocket.app.data.library

/**
 * 会话范围「已发出预约请求」防重闩。
 *
 * 同一会话内，同一 [LibraryReservationRequest] 只能登记一次：结果未知/超时/核对
 * 失败后关闭重进、切模式或重建 ViewModel 都不得再次 POST。只按会话代次失效
 * （换号/退出/重新登录会清会话）；跨进程持久防重不在本轮范围，边界如实标注。
 *
 * 纯内存、线程安全、与网络无关，便于 Repository 与可控 fake transport 共用同一
 * 套语义做单测，不依赖 Android。
 */
class ReservationAttemptLatch {
    private val lock = Any()
    private var generation: Long = -1L
    private val attempted = mutableSetOf<LibraryReservationRequest>()

    /** 会话代次变化时清空；返回 true=首次登记，false=同一会话内重复。 */
    fun registerOrReject(request: LibraryReservationRequest, sessionGeneration: Long): Boolean =
        synchronized(lock) {
            if (generation != sessionGeneration) {
                generation = sessionGeneration
                attempted.clear()
            }
            if (request in attempted) {
                false
            } else {
                attempted.add(request)
                true
            }
        }

    fun hasAttempted(request: LibraryReservationRequest, sessionGeneration: Long): Boolean =
        synchronized(lock) {
            generation == sessionGeneration && request in attempted
        }
}
