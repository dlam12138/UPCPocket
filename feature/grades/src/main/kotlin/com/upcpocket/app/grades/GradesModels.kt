package com.upcpocket.app.grades

import com.upcpocket.app.account.api.SchoolAccountReference
import kotlinx.serialization.Serializable

/**
 * 学校成绩索引中的一个学期。
 *
 * `year`/`term` 保留学校返回的原始文本，不按本机规则重写；只用两者组合作为
 * 索引内唯一键。学校未返回的学期不会被构造或查询。
 */
@Serializable
data class GradesTerm(
    val year: String,
    val term: String,
) {
    init {
        require(year.isValidGradesToken()) { "非法成绩学年" }
        require(term.isValidGradesToken()) { "非法成绩学期" }
    }

    val key: String
        get() = "$year|$term"
}

/**
 * 一条成绩记录。
 *
 * - [score] 是 `kccj` 原始非空文本；学校显式返回 null/空串表示未出分或未知，
 *   此时为 null，不用 0、"--" 或猜测值代替；
 * - [credits] 是 `xf` 的可空数值，缺失或无法解析时为 null，不用 0 代替；
 * - [courseType] 是 `kclx` 原文；学校显式返回 null/空串时同样为 null；
 * - [examType] 是 `exame_type` 原文，可为 null；
 * - [term] 表示这条记录由哪个学期的查询返回，重复课名按列表原样保留。
 */
@Serializable
data class GradesCourse(
    val term: GradesTerm,
    val courseName: String,
    val score: String? = null,
    val credits: Double? = null,
    val courseType: String? = null,
    val examType: String? = null,
)

/** 一个学期的完整查询结果；空列表表示学校明确返回 `d.list: []`，不是缺失。 */
@Serializable
data class GradesTermResult(
    val term: GradesTerm,
    val courses: List<GradesCourse>,
    val fetchedAtEpochMillis: Long,
)

internal data class GradesIndex(
    val terms: List<GradesTerm>,
    val currentTerm: GradesTerm?,
)

enum class GradesConnection {
    LOADING,
    NOT_CONFIGURED,
    BLOCKED_BY_CLEANUP,
    IDLE,
    CONNECTING,
    READY,
    PARTIAL,
    INTERACTION_REQUIRED,
    AUTH_FAILED,
    NETWORK_UNAVAILABLE,
    PROTOCOL_ERROR,
}

/** 固定失败语义；不携带上游正文、账号、Cookie、课程内容或异常原文。 */
@Serializable
enum class GradesFailure {
    ACCOUNT_REQUIRED,
    ACCOUNT_BLOCKED,
    AUTH_INTERACTION,
    AUTH_INVALID,
    AUTH_EXPIRED,
    ACCESS_DENIED,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    PARTIAL_FAILURE,
    STORAGE_ERROR,
}

/**
 * 成绩页状态。
 *
 * [owner] 是非明文、可直接比较的当前数据归属；账号切换或凭据代次变化时 UI
 * 必须立即丢弃旧所有者的页面内容。`termResults` 是当前已知的逐学期完整结果；
 * 最近一次同步中保留旧结果和完全没有可用结果的学期分别列入两个 `lastSync*`
 * 字段，不能用“同步成功”笼统覆盖部分失败。
 */
data class GradesUiState(
    val owner: SchoolAccountReference? = null,
    val connection: GradesConnection = GradesConnection.LOADING,
    val terms: List<GradesTerm> = emptyList(),
    val termResults: List<GradesTermResult> = emptyList(),
    val lastSyncedAtEpochMillis: Long? = null,
    val lastSyncRetainedTerms: List<GradesTerm> = emptyList(),
    val lastSyncFailedTerms: List<GradesTerm> = emptyList(),
    val lastFailure: GradesFailure? = null,
    /** 本进程内已有查询结果尚未写入本机快照；恢复或重试前不能当成已持久化。 */
    val unsavedChanges: Boolean = false,
)

private fun String.isValidGradesToken(): Boolean =
    isNotBlank() && length <= 64 && '|' !in this && none { it.isISOControl() }
