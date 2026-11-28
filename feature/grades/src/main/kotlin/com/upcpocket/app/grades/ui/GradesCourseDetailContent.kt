package com.upcpocket.app.grades.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.EmptyStateCard
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.StatusPill

private data class DetailEmptySpec(
    val title: String,
    val subtitle: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * 课程明细：筛选条、统计口径提示与课程记录。
 *
 * 从总览图表/学期行进入时只列已计入统计的记录，和图表分母保持一致；
 * 可一键切换为含待核记录。原始成绩始终显示，待核原因固定为短文案。
 */
@Composable
internal fun GradesCourseDetail(
    courses: List<GradesCourse>,
    allCourses: List<GradesCourse>,
    terms: List<GradesTerm>,
    selectedTerm: GradesTerm?,
    selectedNature: String?,
    selectedBand: GradesScoreBand?,
    includedOnly: Boolean,
    pendingCount: Int,
    onClearFilters: () -> Unit,
    onShowAllRecords: () -> Unit,
    onShowAllTerms: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeFilters = selectedTerm != null ||
        selectedNature != null ||
        selectedBand != null
    val filterLabels = buildList {
        selectedTerm?.let { add(it.label) }
        selectedNature?.let { add(it) }
        selectedBand?.let { add("分数 ${it.label}") }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("grades_detail_list"),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 4.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "detail_header") {
            DetailFilterHeader(
                activeFilters = activeFilters,
                filterLabels = filterLabels,
                includedOnly = includedOnly,
                pendingCount = pendingCount,
                courseCount = courses.size,
                onClearFilters = onClearFilters,
                onShowAllRecords = onShowAllRecords,
            )
        }
        if (courses.isEmpty()) {
            item(key = "detail_empty") {
                val spec = detailEmptySpec(
                    selectedTerm = selectedTerm,
                    allCourses = allCourses,
                    activeFilters = activeFilters,
                    includedOnly = includedOnly,
                    pendingCount = pendingCount,
                    onClearFilters = onClearFilters,
                    onShowAllRecords = onShowAllRecords,
                    onShowAllTerms = onShowAllTerms,
                    onSync = onSync,
                )
                EmptyStateCard(
                    title = spec.title,
                    subtitle = spec.subtitle,
                    actionLabel = spec.actionLabel,
                    onAction = spec.action,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("grades_detail_empty"),
                )
            }
        } else {
            items(courses, key = { it.id }) { course ->
                CourseCard(
                    course = course,
                    terms = terms,
                    showTerm = selectedTerm == null,
                )
            }
            item(key = "detail_note") {
                Text(
                    text = "原始成绩始终按教务原文显示；待核记录不计入统计。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("grades_detail_note"),
                )
            }
        }
    }
}

@Composable
private fun DetailFilterHeader(
    activeFilters: Boolean,
    filterLabels: List<String>,
    includedOnly: Boolean,
    pendingCount: Int,
    courseCount: Int,
    onClearFilters: () -> Unit,
    onShowAllRecords: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (activeFilters) {
                    "筛选：${filterLabels.joinToString(" · ")}"
                } else {
                    "全部成绩记录"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("grades_filter_summary"),
            )
            Text(
                text = "共 $courseCount 门",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("grades_detail_count"),
            )
        }
        if (activeFilters) {
            CampusTextButton(
                onClick = onClearFilters,
                modifier = Modifier.testTag("grades_clear_filters"),
            ) {
                Text("清除筛选")
            }
        }
        if (includedOnly) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    text = "统计口径",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "仅显示已计入统计的记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            if (pendingCount > 0) {
                CampusTextButton(
                    onClick = onShowAllRecords,
                    modifier = Modifier.testTag("grades_show_pending"),
                ) {
                    Text("含待核记录 $pendingCount 门")
                }
            }
        } else if (pendingCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    text = "待核 $pendingCount 门",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "保留原始成绩，不计入统计。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun detailEmptySpec(
    selectedTerm: GradesTerm?,
    allCourses: List<GradesCourse>,
    activeFilters: Boolean,
    includedOnly: Boolean,
    pendingCount: Int,
    onClearFilters: () -> Unit,
    onShowAllRecords: () -> Unit,
    onShowAllTerms: () -> Unit,
    onSync: () -> Unit,
): DetailEmptySpec {
    val selectedTermHasCourses = selectedTerm?.let { term ->
        allCourses.any { it.termId == term.id }
    } == true

    if (selectedTerm != null && !selectedTermHasCourses) {
        return when {
            selectedTerm.syncFailed -> DetailEmptySpec(
                title = "该学期同步失败",
                subtitle = "没有可显示的历史记录，请重试。",
                actionLabel = "重试",
                action = onSync,
            )
            !selectedTerm.isSynced -> DetailEmptySpec(
                title = "该学期尚未同步",
                subtitle = "教务同步后显示该学期成绩。",
                actionLabel = "教务同步",
                action = onSync,
            )
            else -> DetailEmptySpec(
                title = "该学期暂无成绩记录",
                subtitle = "可切换其他学期或查看全部学期。",
                actionLabel = "查看全部学期",
                action = onShowAllTerms,
            )
        }
    }

    return when {
        includedOnly && pendingCount > 0 -> DetailEmptySpec(
            title = "统计中没有可计入的记录",
            subtitle = "本筛选下另有 $pendingCount 门待核记录。",
            actionLabel = "含待核记录",
            action = onShowAllRecords,
        )
        activeFilters -> DetailEmptySpec(
            title = "没有符合筛选的成绩",
            subtitle = "清除筛选后可查看全部记录。",
            actionLabel = "清除筛选",
            action = onClearFilters,
        )
        else -> DetailEmptySpec(
            title = "暂无成绩记录",
            subtitle = "教务没有返回本科成绩记录。",
        )
    }
}

@Composable
private fun CourseCard(
    course: GradesCourse,
    terms: List<GradesTerm>,
    showTerm: Boolean,
) {
    val reason = pendingReason(course)
    val included = reason == null
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("grades_course_${course.id}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = course.name.ifBlank { "课程名称缺失" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = courseMetaLine(course, terms, showTerm),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = course.rawGrade.ifBlank { "—" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (included) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                    )
                    Text(
                        text = "原始成绩",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    text = if (included) "已出分" else "待核",
                    containerColor = if (included) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (included) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                Text(
                    text = if (reason == null) {
                        "已计入统计"
                    } else {
                        "未计入统计 · ${reason.label}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (included) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.testTag("grades_course_status_${course.id}"),
                )
            }
        }
    }
}

private fun courseMetaLine(
    course: GradesCourse,
    terms: List<GradesTerm>,
    showTerm: Boolean,
): String {
    val credits = course.credits
        ?.takeIf { it.isUsableCredit() }
        ?.let { "学分 ${formatCredits(it)}" }
        ?: "学分不可用"
    val nature = "性质 ${courseNatureLabel(course)}"
    val term = if (showTerm) " · ${termLabel(terms, course.termId)}" else ""
    return "$credits · $nature$term"
}
