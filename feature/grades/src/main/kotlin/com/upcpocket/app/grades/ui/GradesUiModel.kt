package com.upcpocket.app.grades.ui

import androidx.compose.runtime.Immutable

/**
 * 成绩页的纯展示状态。
 *
 * 由整合层把已同步的教务数据映射进来；UI 不发起请求，也不读取账号或学校会话。
 */
@Immutable
data class GradesUiState(
    val account: GradesAccountState = GradesAccountState.READY,
    val sync: GradesSyncState = GradesSyncState.Loading,
    val terms: List<GradesTerm> = emptyList(),
    val courses: List<GradesCourse> = emptyList(),
    /**
     * 本次同步（含部分学期结果）未能保存到本机；重启后会恢复到上次保存的成绩。
     * 与学期级 [GradesTerm.syncFailed] 独立，可同时为 true。
     */
    val unsavedChanges: Boolean = false,
)

/**
 * 学校账号的可用状态。
 *
 * “未保存账号”“恢复中”“清理中”都会阻止同步，并与已保存可同步明确区分；
 * 其中清理中是账号数据清理流程，不能在此期间进入账号设置。
 */
enum class GradesAccountState {
    /** 已保存账号，可以同步。 */
    READY,

    /** 本机没有保存学校账号。 */
    ACCOUNT_REQUIRED,

    /** 正在恢复已保存账号。 */
    RESTORING,

    /** 正在清理学校账号数据。 */
    CLEANING,
}

/** 读取/同步状态；“尚未同步”、加载中与失败必须彼此可辨。 */
sealed interface GradesSyncState {
    /** 正在读取本机已保存的成绩。 */
    data object Loading : GradesSyncState

    /** 本机没有同步过的成绩。 */
    data object NotSynced : GradesSyncState

    /** 正在从教务同步；已有记录可继续显示。 */
    data object Syncing : GradesSyncState

    /** 已同步；[syncedAtMillis] 为空表示来源没有提供时间。 */
    data class Ready(val syncedAtMillis: Long? = null) : GradesSyncState

    /** 同步失败；已有记录仍可查看，由页面按是否还有数据分别表达。 */
    data class Failed(val reason: GradesSyncFailure) : GradesSyncState
}

/** 固定的失败原因，只映射为短文案，不携带异常消息、URL 或响应内容。 */
enum class GradesSyncFailure {
    NETWORK,
    AUTH,

    /** 本机成绩读取或保存失败；可以重新同步。 */
    STORAGE,

    /** 学校要求交互验证；与 [AUTH] 一样提供官方网页入口。 */
    INTERACTION,
    SERVER,
    UNSUPPORTED,
    UNKNOWN,
}

/**
 * 教务学期；[label] 直接使用教务展示文案。
 *
 * [isSynced] 表示该学期是否已有成功同步的记录，[syncFailed] 表示最近一次该学期
 * 同步失败。整体失败但已有记录时继续显示已同步数字；部分学期可能来自本次成功、
 * 部分来自更早同步，因此页面按学期状态说明，不统称“历史”。
 */
@Immutable
data class GradesTerm(
    val id: String,
    val label: String,
    val isSynced: Boolean = true,
    val syncFailed: Boolean = false,
)

/**
 * 成绩页学期列表的派生状态。
 *
 * 无记录学期按完整课程行集合分类：成功同步的空学期、失败且无记录学期、
 * 尚未同步学期分别计数。带有课程行的学期始终可见，即使课程仍待核或同步失败。
 */
internal data class GradesTermVisibility(
    val visibleTerms: List<GradesTerm>,
    val withoutRecordsCount: Int,
    val syncedEmptyCount: Int,
    val failedWithoutRecordsCount: Int,
    val unsyncedWithoutRecordsCount: Int,
)

/** 按教务原顺序派生成绩页学期列表与无记录状态数量。 */
internal fun deriveGradesTermVisibility(
    terms: List<GradesTerm>,
    courses: List<GradesCourse>,
    showTermsWithoutRecords: Boolean,
): GradesTermVisibility {
    val termIdsWithCourses = courses.mapTo(mutableSetOf()) { it.termId }
    val withoutRecords = terms.filter { it.id !in termIdsWithCourses }
    val visibleTerms = if (showTermsWithoutRecords) {
        terms
    } else {
        terms.filter { it.id in termIdsWithCourses }
    }

    return GradesTermVisibility(
        visibleTerms = visibleTerms,
        withoutRecordsCount = withoutRecords.size,
        syncedEmptyCount = withoutRecords.count { it.isSynced && !it.syncFailed },
        failedWithoutRecordsCount = withoutRecords.count { it.syncFailed },
        unsyncedWithoutRecordsCount = withoutRecords.count { !it.isSynced && !it.syncFailed },
    )
}

/** 课程性质来自教务字段；[isRequired] 为空表示来源没有明确必修标记。 */
@Immutable
data class GradesCourseNature(
    val label: String,
    val isRequired: Boolean? = null,
)

/** 原始成绩的明确解释；无法识别时仍保留 [GradesCourse.rawGrade] 原文。 */
sealed interface GradesScore {
    /** 百分制原分。 */
    data class Percent(val score: Double) : GradesScore

    /** 五级等级。 */
    data class Level(val level: GradesLevel) : GradesScore

    /** 二级制“通过/不通过”。 */
    data class PassFail(val passed: Boolean) : GradesScore

    /** 成绩缺失。 */
    data object Missing : GradesScore

    /** 目前无法按教务规则解释的成绩文本。 */
    data object Unrecognized : GradesScore
}

/** 五级等级，换算值见统计规则。 */
enum class GradesLevel(val label: String) {
    EXCELLENT("优秀"),
    GOOD("良好"),
    MEDIUM("中等"),
    PASS("及格"),
    FAIL("不及格"),
}

/**
 * 一门已同步的本科成绩记录。
 *
 * 后端没有独立的学校审核字段：明细成功返回且原始分制可识别时，页面显示“已出分”。
 * [rawGrade] 始终按教务原文显示；统计只使用成绩、学分、课程性质都足以核实且
 * [retakeAmbiguous] 为 false 的记录，无法核实的记录显示“待核”及原因。
 */
@Immutable
data class GradesCourse(
    val id: String,
    val termId: String,
    val name: String,
    val rawGrade: String,
    val score: GradesScore,
    val credits: Double?,
    val nature: GradesCourseNature?,
    val retakeAmbiguous: Boolean = false,
)
