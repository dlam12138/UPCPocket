package com.upcpocket.app.grades.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.EmptyStateCard
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.HorizontalMetricBar
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.components.StatusPill

/**
 * 四项摘要单排所需的内容宽度。
 *
 * 393dp 屏幕去掉两侧 20dp 后约 353dp，可容纳四个紧凑卡片；360dp 等窄屏
 * 仍使用两列。字体放大时也退回两列，避免数值与辅助标签被截断。
 */
private val SingleRowMinWidth = 344.dp
private const val SINGLE_ROW_MAX_FONT_SCALE = 1.0f

/**
 * 成绩总览：待核提示、课程性质/学期/成绩分段。
 *
 * 四项摘要由 [GradesMetricGrid] 在总览/明细切换上方展示，始终使用当前学期范围；
 * 图表与统计只使用可计入统计的记录，待核记录单独提示，不混入分母。
 * 点课程性质、学期行或成绩分段会进入明细，并采用与图表相同的记录范围。
 */
@Composable
internal fun GradesOverview(
    allCourses: List<GradesCourse>,
    courses: List<GradesCourse>,
    terms: List<GradesTerm>,
    selectedTermId: String?,
    selectedTerm: GradesTerm?,
    stats: GradesStatistics,
    onTermClick: (String) -> Unit,
    onNatureClick: (String) -> Unit,
    onBandClick: (GradesScoreBand) -> Unit,
    onShowAllTerms: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedTerm != null && courses.isEmpty()) {
        GradesTermEmptyOverview(
            term = selectedTerm,
            onSync = onSync,
            onShowAllTerms = onShowAllTerms,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("grades_overview_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 4.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (stats.hasPending) {
            item(key = "pending") {
                PendingNote(stats.pendingCount)
            }
        }
        item(key = "nature") {
            OverviewSection("课程性质学分分布") {
                NatureDistributionCard(stats = stats, onNatureClick = onNatureClick)
            }
        }
        if (terms.isNotEmpty()) {
            item(key = "terms") {
                OverviewSection("学期") {
                    TermListCard(
                        terms = terms,
                        allCourses = allCourses,
                        selectedTermId = selectedTermId,
                        onTermClick = onTermClick,
                    )
                }
            }
        }
        item(key = "bands") {
            OverviewSection("成绩分段") {
                BandDistributionCard(stats = stats, onBandClick = onBandClick)
                Text(
                    text = "分段按百分制原分或教务处明确换算分统计。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("grades_band_note"),
                )
            }
        }
        item(key = "source_note") {
            Text(
                text = "统计基于本机已同步的本科成绩记录，不代表学校学籍结果。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("grades_stats_note"),
            )
        }
    }
}

@Composable
private fun OverviewSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        content()
    }
}

@Composable
private fun GradesTermEmptyOverview(
    term: GradesTerm,
    onSync: () -> Unit,
    onShowAllTerms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title: String
    val subtitle: String
    val actionLabel: String
    val action: () -> Unit
    when {
        term.syncFailed -> {
            title = "该学期同步失败"
            subtitle = "没有可显示的历史记录，请重试。"
            actionLabel = "重试"
            action = onSync
        }
        !term.isSynced -> {
            title = "该学期尚未同步"
            subtitle = "教务同步后显示该学期成绩与学分统计。"
            actionLabel = "教务同步"
            action = onSync
        }
        else -> {
            title = "该学期暂无成绩记录"
            subtitle = "可切换其他学期或查看全部学期。"
            actionLabel = "查看全部学期"
            action = onShowAllTerms
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("grades_overview_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
    ) {
        item {
            EmptyStateCard(
                title = title,
                subtitle = subtitle,
                actionLabel = actionLabel,
                onAction = action,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("grades_term_empty"),
            )
        }
    }
}

private data class GradesMetric(
    val label: String,
    val value: String,
    val subtitle: String,
    val highlight: Boolean,
)

@Composable
internal fun GradesMetricGrid(
    stats: GradesStatistics,
    modifier: Modifier = Modifier,
) {
    val hasPending = stats.hasPending
    val averageText = stats.weightedAverage?.let(::formatAverage)
        ?: if (hasPending) "待核" else "—"
    val tiles = listOf(
        GradesMetric(
            label = "总 GPA",
            value = gpaText(stats.totalGpa, hasPending),
            subtitle = "均分 $averageText",
            highlight = true,
        ),
        GradesMetric(
            label = "已获学分",
            value = formatCredits(stats.earnedCredits),
            subtitle = "修读 ${formatCredits(stats.attemptedCredits)}",
            highlight = false,
        ),
        GradesMetric(
            label = "必修 GPA",
            value = when {
                stats.requiredPending -> "待核"
                else -> stats.requiredGpa?.let(::formatGpa) ?: "—"
            },
            subtitle = when {
                stats.requiredPending -> "性质待核"
                stats.requiredGpa == null -> "暂无必修"
                else -> "学分加权"
            },
            highlight = true,
        ),
        GradesMetric(
            label = "通过率",
            value = stats.passRate?.let(::formatPercent)
                ?: if (hasPending) "待核" else "—",
            subtitle = if (stats.included.isEmpty()) {
                "暂无出分"
            } else {
                "${stats.passedCount}/${stats.included.size} 门"
            },
            highlight = false,
        ),
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = if (maxWidth >= SingleRowMinWidth &&
            fontScale <= SINGLE_ROW_MAX_FONT_SCALE
        ) {
            4
        } else {
            2
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEach { rowTiles ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTiles.forEach { tile ->
                        GradesMetricCard(
                            tile = tile,
                            compact = columns == 4,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowTiles.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GradesMetricCard(
    tile: GradesMetric,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .heightIn(min = 84.dp)
            .testTag("grades_metric_${tile.label}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = tile.value,
                style = if (compact) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.SemiBold,
                color = if (tile.highlight) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun gpaText(value: Double?, hasPending: Boolean): String =
    value?.let(::formatGpa) ?: if (hasPending) "待核" else "—"

@Composable
private fun PendingNote(pendingCount: Int) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("grades_pending_note"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusPill(
                text = "待核 $pendingCount 门",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "未计入统计，原始成绩与原因见课程明细。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NatureDistributionCard(
    stats: GradesStatistics,
    onNatureClick: (String) -> Unit,
) {
    val groups = natureGroups(stats.included)
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            if (groups.isEmpty()) {
                Text(
                    text = "暂无可计入统计的课程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                groups.forEachIndexed { index, group ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    val share = if (stats.attemptedCredits > 0.0) {
                        group.credits / stats.attemptedCredits
                    } else {
                        0.0
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "查看${group.label}课程明细",
                            ) { onNatureClick(group.label) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("grades_nature_row_${group.label}"),
                    ) {
                        HorizontalMetricBar(
                            label = group.label,
                            valueText = "${formatCredits(group.credits)} 学分",
                            fraction = share.toFloat(),
                            subtitle = "${group.courses.size} 门 · 占 ${formatPercent(share)}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TermListCard(
    terms: List<GradesTerm>,
    allCourses: List<GradesCourse>,
    selectedTermId: String?,
    onTermClick: (String) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            terms.forEachIndexed { index, term ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                val termCourses = allCourses.filter { it.termId == term.id }
                TermRow(
                    term = term,
                    termCourses = termCourses,
                    selected = term.id == selectedTermId,
                    onClick = { onTermClick(term.id) },
                )
            }
        }
    }
}

@Composable
private fun TermRow(
    term: GradesTerm,
    termCourses: List<GradesCourse>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val stats = computeGradesStatistics(termCourses)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "查看${term.label}成绩明细",
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("grades_term_row_${term.id}"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = term.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (term.syncFailed) {
                StatusPill(
                    text = "同步失败",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else if (!term.isSynced) {
                StatusPill(
                    text = "未同步",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = termStatsLine(term, termCourses, stats),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (termCourses.isNotEmpty()) {
            Text(
                text = termCreditsLine(stats),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (term.syncFailed && termCourses.isNotEmpty()) {
            Text(
                text = "上次同步失败，数字来自已同步记录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun termStatsLine(
    term: GradesTerm,
    termCourses: List<GradesCourse>,
    stats: GradesStatistics,
): String = when {
    termCourses.isEmpty() && term.syncFailed -> "同步失败，暂无记录"
    !term.isSynced -> "尚未同步，暂无统计"
    termCourses.isEmpty() -> "暂无成绩记录"
    stats.included.isEmpty() -> "统计待核 · ${stats.pendingCount} 门待核"
    else -> "总 GPA ${stats.totalGpa?.let(::formatGpa) ?: "待核"} · " +
        "均分 ${stats.weightedAverage?.let(::formatAverage) ?: "待核"}"
}

private fun termCreditsLine(stats: GradesStatistics): String {
    val credits = "已获 ${formatCredits(stats.earnedCredits)} / " +
        "修读 ${formatCredits(stats.attemptedCredits)} 学分"
    return if (stats.hasPending) {
        "$credits · ${stats.pendingCount} 门待核"
    } else {
        credits
    }
}

@Composable
private fun BandDistributionCard(
    stats: GradesStatistics,
    onBandClick: (GradesScoreBand) -> Unit,
) {
    val bands = bandSummaries(stats.included)
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            if (stats.included.isEmpty()) {
                Text(
                    text = "暂无已出分记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                bands.forEachIndexed { index, band ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    val share = if (stats.attemptedCredits > 0.0) {
                        band.credits / stats.attemptedCredits
                    } else {
                        0.0
                    }
                    val enabled = band.courseCount > 0
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = enabled,
                                role = Role.Button,
                                onClickLabel = "查看${band.band.label}分段课程",
                            ) { onBandClick(band.band) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("grades_band_row_${band.band.name}"),
                    ) {
                        HorizontalMetricBar(
                            label = band.band.label,
                            valueText = "${band.courseCount} 门",
                            fraction = share.toFloat(),
                            subtitle = if (enabled) {
                                "${formatCredits(band.credits)} 学分 · 占 ${formatPercent(share)}"
                            } else {
                                "暂无课程"
                            },
                            barColor = if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
