package com.upcpocket.app.data.library

import kotlinx.coroutines.flow.StateFlow

/**
 * 「按时段找 / 最终提交」编排所需的最小只读/写入口。
 *
 * 目的：让 ViewModel 的查询代次隔离、迟到响应、未知结果防重、隐藏响应
 * 编排可以用可控 fake 单测，而不必真的联网或迁移整个 Repository 架构。
 * 生产实现 [LibrarySeatRepositoryOps] 只是把调用转发给现有 Repository，
 * 不改变签名/凭据/会话门闩。
 */
interface LibrarySeatOps {
    /** 当前会话 token；会话切换后旧响应据此失效（不记录值本身）。 */
    fun sessionToken(): String?

    /** 供 UI 观察会话的只读流（会话失效/登出仍走原 Repository）。 */
    val session: StateFlow<LibrarySeatSession?>

    suspend fun timeSearchSeats(
        key: LibraryTimeSearchKey,
        page: Int,
        batch: Int,
        guard: () -> Boolean = { true },
    ): LibraryTimeSearchPage

    suspend fun startTimes(seatId: String, date: String): List<LibraryTimeOption>

    suspend fun endTimes(seatId: String, date: String, startId: String): List<LibraryTimeOption>

    /** 返回是否真正发出过 POST；NOT_SENT 不得冒充已发送。 */
    suspend fun reserve(
        date: String,
        seatId: String,
        startId: String,
        endId: String,
    ): LibraryReservationAttempt

    suspend fun myReservations(): List<LibraryReservation>
}

/** 生产适配器：把新编排接口转发给现有 Repository，保留其签名/门闩/防重闩。 */
class LibrarySeatRepositoryOps(
    private val repo: LibrarySeatRepository,
) : LibrarySeatOps {
    override fun sessionToken(): String? = repo.session.value?.token

    override val session: StateFlow<LibrarySeatSession?> get() = repo.session

    override suspend fun timeSearchSeats(
        key: LibraryTimeSearchKey,
        page: Int,
        batch: Int,
        guard: () -> Boolean,
    ): LibraryTimeSearchPage = repo.timeSearchSeats(key, page, batch, guard)

    override suspend fun startTimes(seatId: String, date: String): List<LibraryTimeOption> =
        repo.startTimes(seatId, date)

    override suspend fun endTimes(
        seatId: String,
        date: String,
        startId: String,
    ): List<LibraryTimeOption> = repo.endTimes(seatId, date, startId)

    override suspend fun reserve(
        date: String,
        seatId: String,
        startId: String,
        endId: String,
    ): LibraryReservationAttempt = repo.reserve(date, seatId, startId, endId)

    override suspend fun myReservations(): List<LibraryReservation> = repo.myReservations()
}
