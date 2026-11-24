package com.upcpocket.app.grades.ui

import java.util.Locale

/** 成绩页使用的固定分段；标签明确表示百分制或明确换算分。 */
internal enum class GradesScoreBand(val label: String) {
    SCORE_90_100("90–100"),
    SCORE_80_89("80–89"),
    SCORE_70_79("70–79"),
    SCORE_60_69("60–69"),
    SCORE_BELOW_60("低于 60"),
}

/** 不能计入统计的原因；明细行按固定短文案展示。 */
internal enum class GradesPendingReason(val label: String) {
    AMBIGUOUS_RETAKE("同名重修待核"),
    MISSING_SCORE("成绩缺失"),
    UNRECOGNIZED_SCORE("成绩无法识别"),
    MISSING_CREDITS("学分不可用"),
}

internal const val UNKNOWN_NATURE_LABEL = "性质未知"

/**
 * 百分制等效分。
 *
 * 依据教务处成绩计算规则：优秀/良好/中等/及格/不及格分别为 95/85/75/65/40，
 * 二级制通过/不通过分别为 80/40。百分制只接受 0–100 的有限数。
 */
internal fun GradesScore.percentageEquivalent(): Double? = when (this) {
    is GradesScore.Percent -> score.takeIf { it.isFinite() && it in 0.0..100.0 }
    is GradesScore.Level -> when (level) {
        GradesLevel.EXCELLENT -> 95.0
        GradesLevel.GOOD -> 85.0
        GradesLevel.MEDIUM -> 75.0
        GradesLevel.PASS -> 65.0
        GradesLevel.FAIL -> 40.0
    }
    is GradesScore.PassFail -> if (passed) 80.0 else 40.0
    GradesScore.Missing, GradesScore.Unrecognized -> null
}

/** 单课绩点：百分制等效分 >= 60 为 s/10−5，否则为 0。 */
internal fun GradesScore.gradePoint(): Double? {
    val percentage = percentageEquivalent() ?: return null
    return if (percentage >= 60.0) percentage / 10.0 - 5.0 else 0.0
}

/** 百分制 >= 60 为通过；等级与二级制按上面的明确换算判断。 */
internal fun GradesScore.isPassing(): Boolean? =
    percentageEquivalent()?.let { it >= 60.0 }

/** 按百分制或明确换算分归入固定分段。 */
internal fun GradesScore.band(): GradesScoreBand? {
    val percentage = percentageEquivalent() ?: return null
    return when {
        percentage >= 90.0 -> GradesScoreBand.SCORE_90_100
        percentage >= 80.0 -> GradesScoreBand.SCORE_80_89
        percentage >= 70.0 -> GradesScoreBand.SCORE_70_79
        percentage >= 60.0 -> GradesScoreBand.SCORE_60_69
        else -> GradesScoreBand.SCORE_BELOW_60
    }
}

/** 不能计入统计的具体原因；null 表示该记录可以核实。 */
internal fun pendingReason(course: GradesCourse): GradesPendingReason? {
    val credits = course.credits
    return when {
        course.retakeAmbiguous -> GradesPendingReason.AMBIGUOUS_RETAKE
        course.score is GradesScore.Missing -> GradesPendingReason.MISSING_SCORE
        course.score.percentageEquivalent() == null -> GradesPendingReason.UNRECOGNIZED_SCORE
        credits == null || !credits.isFinite() || credits <= 0.0 ->
            GradesPendingReason.MISSING_CREDITS
        else -> null
    }
}

/** 明细成功返回且原始分制可识别、可计入统计的记录视为“已出分”。 */
internal fun GradesCourse.isIncludedInStatistics(): Boolean = pendingReason(this) == null

internal fun courseNatureLabel(course: GradesCourse): String =
    course.nature?.label?.takeIf { it.isNotBlank() } ?: UNKNOWN_NATURE_LABEL

internal fun termLabel(terms: List<GradesTerm>, termId: String): String =
    terms.firstOrNull { it.id == termId }?.label?.takeIf { it.isNotBlank() }
        ?: termId.takeIf { it.isNotBlank() }
        ?: "学期未知"

/**
 * 按学期、课程性质和成绩分段筛选明细。
 *
 * [includePending] 为 false 时只保留可计入统计的记录，用于从总览图表或学期行
 * 进入明细时保持与图表相同的分母；为 true 时保留待核记录，便于查看原始成绩。
 */
internal fun filterGradesCourses(
    courses: List<GradesCourse>,
    termId: String?,
    natureLabel: String?,
    band: GradesScoreBand?,
    includePending: Boolean = true,
): List<GradesCourse> = courses.filter { course ->
    (includePending || course.isIncludedInStatistics()) &&
        (termId == null || course.termId == termId) &&
        (natureLabel == null || courseNatureLabel(course) == natureLabel) &&
        (band == null || course.score.band() == band)
}

/** 指定筛选范围内、因无法核实而未计入统计的记录数。 */
internal fun pendingCountFor(
    courses: List<GradesCourse>,
    termId: String?,
    natureLabel: String?,
    band: GradesScoreBand?,
): Int = courses.count { course ->
    !course.isIncludedInStatistics() &&
        (termId == null || course.termId == termId) &&
        (natureLabel == null || courseNatureLabel(course) == natureLabel) &&
        (band == null || course.score.band() == band)
}

internal data class GradesStatistics(
    val included: List<GradesCourse>,
    val pending: List<GradesCourse>,
    val totalGpa: Double?,
    val weightedAverage: Double?,
    val requiredGpa: Double?,
    val requiredAverage: Double?,
    val earnedCredits: Double,
    val attemptedCredits: Double,
    val passedCount: Int,
    val passRate: Double?,
    val requiredPending: Boolean,
) {
    val pendingCount: Int get() = pending.size
    val hasPending: Boolean get() = pending.isNotEmpty()
}

/**
 * 计算一组课程的可核实统计。
 *
 * 只纳入成绩可解释、学分大于 0 且没有同名重修歧义的记录；平均分与 GPA
 * 均按学分加权，已获学分只累计通过课程。课程性质未知不会被猜成必修，
 * 只会让必修统计显示待核。
 */
internal fun computeGradesStatistics(courses: List<GradesCourse>): GradesStatistics {
    val included = courses.filter { pendingReason(it) == null }
    val pending = courses.filter { pendingReason(it) != null }

    val totalGpa = weightedMetric(included) { it.score.gradePoint() }
    val weightedAverage = weightedMetric(included) { it.score.percentageEquivalent() }
    val required = included.filter { it.nature?.isRequired == true }
    val requiredGpa = weightedMetric(required) { it.score.gradePoint() }
    val requiredAverage = weightedMetric(required) { it.score.percentageEquivalent() }

    val passed = included.filter { it.score.isPassing() == true }
    val earnedCredits = passed.sumOf { it.creditsValue() }
    val attemptedCredits = included.sumOf { it.creditsValue() }
    val passRate = if (included.isEmpty()) null else passed.size.toDouble() / included.size

    val requiredPending = courses.any { it.nature?.isRequired == null } ||
        pending.any { it.nature?.isRequired != false }

    return GradesStatistics(
        included = included,
        pending = pending,
        totalGpa = totalGpa,
        weightedAverage = weightedAverage,
        requiredGpa = requiredGpa,
        requiredAverage = requiredAverage,
        earnedCredits = earnedCredits,
        attemptedCredits = attemptedCredits,
        passedCount = passed.size,
        passRate = passRate,
        requiredPending = requiredPending,
    )
}

internal data class GradesNatureGroup(
    val label: String,
    val courses: List<GradesCourse>,
    val credits: Double,
)

/** 课程性质学分分布；调用方只传已计入统计的课程。 */
internal fun natureGroups(courses: List<GradesCourse>): List<GradesNatureGroup> =
    courses.groupBy(::courseNatureLabel)
        .map { (label, group) ->
            GradesNatureGroup(
                label = label,
                courses = group,
                credits = group.sumOf { it.creditsValue() },
            )
        }
        .sortedWith(
            compareByDescending<GradesNatureGroup> { it.credits }
                .thenBy { it.label },
        )

internal data class GradesBandSummary(
    val band: GradesScoreBand,
    val courseCount: Int,
    val credits: Double,
)

/** 成绩分段；调用方只传已计入统计且有可解释成绩的课程。 */
internal fun bandSummaries(courses: List<GradesCourse>): List<GradesBandSummary> =
    GradesScoreBand.entries.map { band ->
        val group = courses.filter { it.score.band() == band }
        GradesBandSummary(
            band = band,
            courseCount = group.size,
            credits = group.sumOf { it.creditsValue() },
        )
    }

internal fun Double.isUsableCredit(): Boolean = isFinite() && this > 0.0

internal fun GradesCourse.creditsValue(): Double =
    credits?.takeIf { it.isUsableCredit() } ?: 0.0

/** GPA 保留两位小数，使用固定小数点，避免随系统区域变化。 */
internal fun formatGpa(value: Double): String = formatFixed(value, digits = 2)

/** 学分加权均分保留一位小数。 */
internal fun formatAverage(value: Double): String = formatFixed(value, digits = 1)

/** 学分去掉无意义的尾零，保留最多两位小数。 */
internal fun formatCredits(value: Double): String {
    if (!value.isFinite()) return "—"
    return String.format(Locale.US, "%.2f", value)
        .trimEnd('0')
        .trimEnd('.')
}

/** 比例（0–1）显示为百分比，最多一位小数。 */
internal fun formatPercent(fraction: Double): String {
    if (!fraction.isFinite()) return "—"
    return String.format(Locale.US, "%.1f", fraction * 100.0)
        .removeSuffix(".0") + "%"
}

private fun formatFixed(value: Double, digits: Int): String {
    if (!value.isFinite()) return "—"
    return String.format(Locale.US, "%.${digits}f", value)
}

private fun weightedMetric(
    courses: List<GradesCourse>,
    value: (GradesCourse) -> Double?,
): Double? {
    var weightedSum = 0.0
    var totalCredits = 0.0
    courses.forEach { course ->
        val credits = course.credits ?: return@forEach
        if (!credits.isUsableCredit()) return@forEach
        val metric = value(course) ?: return@forEach
        if (!metric.isFinite()) return@forEach
        weightedSum += metric * credits
        totalCredits += credits
    }
    return if (totalCredits > 0.0) weightedSum / totalCredits else null
}
