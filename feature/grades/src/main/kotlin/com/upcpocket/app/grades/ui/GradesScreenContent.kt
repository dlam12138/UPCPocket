package com.upcpocket.app.grades.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.CampusIconButton
import com.upcpocket.app.ui.components.CampusSegmentedTabs
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.CampusTopAppBar
import com.upcpocket.app.ui.components.EmptyStateCard
import com.upcpocket.app.ui.components.RelativeTime
import com.upcpocket.app.ui.components.StatusPill
import com.upcpocket.app.ui.theme.appScaffoldContainerColor

private const val NO_FILTER = ""

/** 本次同步结果未能写入本机时的固定提示；与学期级失败提示并列展示。 */
private const val UNSAVED_CHANGES_MESSAGE = "本次更新未保存，重启后会恢复到上次保存的成绩"

private data class GradesEmptySpec(
    val title: String,
    val subtitle: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * 成绩与学分页的纯展示入口。
 *
 * 页面只消费 [GradesUiState] 并发出返回、同步、账号设置与学校成绩网页事件；
 * 四项摘要固定在总览/明细切换上方并按当前学期范围更新；学期、课程性质、
 * 成绩分段、总览/明细切换与“统计口径”属于本页状态，使用 rememberSaveable 保存。
 *
 * 账号未保存、账号恢复/清理中与已保存可同步彼此独立展示；账号不可用时始终
 * 禁用同步，但已有成绩记录仍可查看。同步失败时按学期状态说明已同步记录，
 * 不把部分同步结果统称历史；AUTH 与 INTERACTION 失败提供学校成绩网页入口。
 * [GradesUiState.unsavedChanges] 与学期失败可同时出现，二者共用同步详情行：
 * 前者提示本次更新重启后会丢失，后者说明仍保留哪些记录，互不覆盖。
 */
@Composable
fun GradesScreenContent(
    state: GradesUiState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onOpenOfficialPage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedTermId by rememberSaveable { mutableStateOf(NO_FILTER) }
    var selectedNature by rememberSaveable { mutableStateOf(NO_FILTER) }
    var selectedBandName by rememberSaveable { mutableStateOf(NO_FILTER) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var includedOnly by rememberSaveable { mutableStateOf(false) }
    var showTermsWithoutRecords by rememberSaveable { mutableStateOf(false) }

    val termVisibility = deriveGradesTermVisibility(
        terms = state.terms,
        courses = state.courses,
        showTermsWithoutRecords = showTermsWithoutRecords,
    )
    val visibleTerms = termVisibility.visibleTerms
    val effectiveTermId = selectedTermId.takeIf { id ->
        id.isNotEmpty() && visibleTerms.any { it.id == id }
    }
    val selectedTerm = effectiveTermId?.let { id -> visibleTerms.firstOrNull { it.id == id } }
    val natureFilter = selectedNature.takeIf { it.isNotEmpty() }
    val bandFilter = selectedBandName.takeIf { it.isNotEmpty() }
        ?.let { name -> GradesScoreBand.entries.firstOrNull { it.name == name } }

    val overviewCourses = if (effectiveTermId == null) {
        state.courses
    } else {
        state.courses.filter { it.termId == effectiveTermId }
    }
    val stats = computeGradesStatistics(overviewCourses)
    val detailCourses = filterGradesCourses(
        courses = state.courses,
        termId = effectiveTermId,
        natureLabel = natureFilter,
        band = bandFilter,
        includePending = !includedOnly,
    )
    val pendingCount = pendingCountFor(
        courses = state.courses,
        termId = effectiveTermId,
        natureLabel = natureFilter,
        band = bandFilter,
    )
    val hasData = state.courses.isNotEmpty()
    val canSync = state.account == GradesAccountState.READY &&
        state.sync !is GradesSyncState.Loading &&
        state.sync !is GradesSyncState.Syncing

    val selectTerm: (String?) -> Unit = { termId ->
        selectedTermId = termId ?: NO_FILTER
        includedOnly = false
    }
    val toggleTermsWithoutRecords: () -> Unit = {
        if (showTermsWithoutRecords) {
            val collapsedTerms = deriveGradesTermVisibility(
                terms = state.terms,
                courses = state.courses,
                showTermsWithoutRecords = false,
            ).visibleTerms
            if (selectedTermId.isNotEmpty() && collapsedTerms.none { it.id == selectedTermId }) {
                selectTerm(null)
            }
            showTermsWithoutRecords = false
        } else {
            showTermsWithoutRecords = true
        }
    }
    LaunchedEffect(selectedTermId, visibleTerms, state.sync) {
        if (state.sync !is GradesSyncState.Loading &&
            selectedTermId.isNotEmpty() &&
            visibleTerms.none { it.id == selectedTermId }
        ) {
            selectTerm(null)
        }
    }
    val openTerm: (String) -> Unit = { termId ->
        selectedTermId = termId
        selectedNature = NO_FILTER
        selectedBandName = NO_FILTER
        includedOnly = true
        showDetails = true
    }
    val openNature: (String) -> Unit = { label ->
        selectedNature = label
        selectedBandName = NO_FILTER
        includedOnly = true
        showDetails = true
    }
    val openBand: (GradesScoreBand) -> Unit = { band ->
        selectedBandName = band.name
        selectedNature = NO_FILTER
        includedOnly = true
        showDetails = true
    }
    val clearFilters: () -> Unit = {
        selectedTermId = NO_FILTER
        selectedNature = NO_FILTER
        selectedBandName = NO_FILTER
        includedOnly = false
    }

    Scaffold(
        modifier = modifier,
        containerColor = appScaffoldContainerColor(),
        topBar = {
            CampusTopAppBar(
                title = { Text("成绩与学分") },
                navigationIcon = {
                    CampusIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    val syncing = state.sync is GradesSyncState.Syncing
                    CampusTextButton(
                        onClick = onSync,
                        enabled = canSync,
                        modifier = Modifier.testTag("grades_sync_action"),
                    ) {
                        Text(if (syncing) "同步中…" else "教务同步")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            GradesStatusHeader(
                state = state,
                termVisibility = termVisibility,
                onOpenAccount = onOpenAccount,
                onOpenOfficialPage = onOpenOfficialPage,
            )
            if (hasData && state.terms.isNotEmpty()) {
                GradesTermSelector(
                    terms = visibleTerms,
                    selectedTermId = effectiveTermId,
                    onSelect = selectTerm,
                    withoutRecordsCount = termVisibility.withoutRecordsCount,
                    showTermsWithoutRecords = showTermsWithoutRecords,
                    onToggleTermsWithoutRecords = toggleTermsWithoutRecords,
                )
                selectedTerm?.let { term ->
                    val status = selectedTermStatus(term, overviewCourses.isNotEmpty())
                    if (status != null) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (term.syncFailed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .padding(horizontal = 20.dp, vertical = 2.dp)
                                .testTag("grades_term_status"),
                        )
                    }
                }
            }
            if (overviewCourses.isNotEmpty()) {
                GradesMetricGrid(
                    stats = stats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 6.dp),
                )
            }
            if (hasData) {
                CampusSegmentedTabs(
                    tabs = listOf("成绩总览", "课程明细"),
                    selectedIndex = if (showDetails) 1 else 0,
                    onSelect = { index -> showDetails = index == 1 },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (showDetails) {
                    GradesCourseDetail(
                        courses = detailCourses,
                        allCourses = state.courses,
                        terms = state.terms,
                        selectedTerm = selectedTerm,
                        selectedNature = natureFilter,
                        selectedBand = bandFilter,
                        includedOnly = includedOnly,
                        pendingCount = pendingCount,
                        onClearFilters = clearFilters,
                        onShowAllRecords = { includedOnly = false },
                        onShowAllTerms = { selectTerm(null) },
                        onSync = onSync,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    GradesOverview(
                        allCourses = state.courses,
                        courses = overviewCourses,
                        terms = visibleTerms,
                        selectedTermId = effectiveTermId,
                        selectedTerm = selectedTerm,
                        stats = stats,
                        onTermClick = openTerm,
                        onNatureClick = openNature,
                        onBandClick = openBand,
                        onShowAllTerms = { selectTerm(null) },
                        onSync = onSync,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                GradesSyncEmptyState(
                    state = state,
                    termVisibility = termVisibility,
                    onSync = onSync,
                    onOpenAccount = onOpenAccount,
                    onOpenOfficialPage = onOpenOfficialPage,
                    showTermsWithoutRecords = showTermsWithoutRecords,
                    onToggleTermsWithoutRecords = toggleTermsWithoutRecords,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun selectedTermStatus(term: GradesTerm, hasCourses: Boolean): String? = when {
    term.syncFailed && hasCourses -> "该学期上次同步失败，数字来自已同步记录。"
    term.syncFailed -> "该学期同步失败，暂无记录。"
    !term.isSynced -> "该学期尚未同步。"
    else -> null
}

@Composable
private fun GradesStatusHeader(
    state: GradesUiState,
    termVisibility: GradesTermVisibility,
    onOpenAccount: () -> Unit,
    onOpenOfficialPage: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val summaryStyle = MaterialTheme.typography.labelSmall

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.account != GradesAccountState.READY) {
            val (label, containerColor, contentColor) = when (state.account) {
                GradesAccountState.ACCOUNT_REQUIRED ->
                    Triple("未设置账号", scheme.surfaceVariant, scheme.onSurfaceVariant)
                GradesAccountState.RESTORING ->
                    Triple("账号恢复中", scheme.tertiaryContainer, scheme.onTertiaryContainer)
                GradesAccountState.CLEANING ->
                    Triple("账号清理中", scheme.errorContainer, scheme.onErrorContainer)
                GradesAccountState.READY ->
                    Triple("已保存账号", scheme.secondaryContainer, scheme.onSecondaryContainer)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    text = label,
                    containerColor = containerColor,
                    contentColor = contentColor,
                )
                if (state.account == GradesAccountState.RESTORING ||
                    state.account == GradesAccountState.CLEANING
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = accountSummary(state),
                    style = summaryStyle,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("grades_account_summary"),
                )
            }
            if (state.account == GradesAccountState.ACCOUNT_REQUIRED) {
                CampusTextButton(
                    onClick = onOpenAccount,
                    modifier = Modifier.testTag("grades_account_action"),
                ) {
                    Text("设置学校账号")
                }
            }
            return@Column
        }

        val failed = state.sync is GradesSyncState.Failed
        val (label, containerColorForSync, contentColor) = when (state.sync) {
            GradesSyncState.Loading ->
                Triple("读取中", scheme.tertiaryContainer, scheme.onTertiaryContainer)
            GradesSyncState.NotSynced ->
                Triple("未同步", scheme.surfaceVariant, scheme.onSurfaceVariant)
            GradesSyncState.Syncing ->
                Triple("同步中", scheme.tertiaryContainer, scheme.onTertiaryContainer)
            is GradesSyncState.Ready -> when {
                state.terms.isEmpty() ->
                    Triple("状态未知", scheme.surfaceVariant, scheme.onSurfaceVariant)
                state.terms.any { it.syncFailed || !it.isSynced } ->
                    Triple("同步未完成", scheme.tertiaryContainer, scheme.onTertiaryContainer)
                else ->
                    Triple("已同步", scheme.secondaryContainer, scheme.onSecondaryContainer)
            }
            is GradesSyncState.Failed ->
                Triple("同步失败", scheme.errorContainer, scheme.onErrorContainer)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(
                text = label,
                containerColor = containerColorForSync,
                contentColor = contentColor,
            )
            if (state.sync is GradesSyncState.Loading || state.sync is GradesSyncState.Syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = syncSummary(state),
                style = summaryStyle,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("grades_sync_summary"),
            )
        }
        syncDetail(state, termVisibility)?.let { detail ->
            Text(
                text = detail,
                style = summaryStyle,
                color = if (failed || state.unsavedChanges) {
                    scheme.error
                } else {
                    scheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("grades_sync_detail"),
            )
        }
        if (state.sync is GradesSyncState.Failed &&
            state.sync.reason.opensOfficialPage()
        ) {
            CampusTextButton(
                onClick = onOpenOfficialPage,
                modifier = Modifier.testTag("grades_official_action"),
            ) {
                Text("打开学校成绩网页")
            }
        }
    }
}

@Composable
private fun GradesTermSelector(
    terms: List<GradesTerm>,
    selectedTermId: String?,
    onSelect: (String?) -> Unit,
    withoutRecordsCount: Int,
    showTermsWithoutRecords: Boolean,
    onToggleTermsWithoutRecords: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "学期",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (withoutRecordsCount > 0) {
                CampusTextButton(
                    onClick = onToggleTermsWithoutRecords,
                    modifier = Modifier.testTag("grades_toggle_empty_terms"),
                ) {
                    Text(
                        if (showTermsWithoutRecords) {
                            "隐藏无记录学期"
                        } else {
                            "显示无记录学期（$withoutRecordsCount）"
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CampusFilterChip(
                selected = selectedTermId == null,
                onClick = { onSelect(null) },
                label = { Text("全部学期", maxLines = 1) },
                modifier = Modifier.testTag("grades_term_all"),
            )
            terms.forEach { term ->
                CampusFilterChip(
                    selected = selectedTermId == term.id,
                    onClick = { onSelect(term.id) },
                    label = { Text(term.label, maxLines = 1) },
                    modifier = Modifier.testTag("grades_term_chip_${term.id}"),
                )
            }
        }
    }
}

@Composable
private fun GradesSyncEmptyState(
    state: GradesUiState,
    termVisibility: GradesTermVisibility,
    onSync: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenOfficialPage: () -> Unit,
    showTermsWithoutRecords: Boolean,
    onToggleTermsWithoutRecords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = emptySpec(
        state = state,
        onSync = onSync,
        onOpenAccount = onOpenAccount,
        onOpenOfficialPage = onOpenOfficialPage,
        termVisibility = termVisibility,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        EmptyStateCard(
            title = spec.title,
            subtitle = spec.subtitle,
            actionLabel = spec.actionLabel,
            onAction = spec.action,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("grades_empty_state"),
        )
        if (state.account == GradesAccountState.READY &&
            state.terms.isNotEmpty() &&
            (state.sync is GradesSyncState.Ready || state.sync is GradesSyncState.Failed) &&
            termVisibility.withoutRecordsCount > 0
        ) {
            CampusTextButton(
                onClick = onToggleTermsWithoutRecords,
                modifier = Modifier.testTag("grades_toggle_empty_terms"),
            ) {
                Text(
                    if (showTermsWithoutRecords) {
                        "隐藏无记录学期"
                    } else {
                        "显示无记录学期（${termVisibility.withoutRecordsCount}）"
                    },
                    maxLines = 1,
                )
            }
            if (showTermsWithoutRecords) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("grades_empty_term_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.terms.forEach { term ->
                        EmptyTermStatusRow(term)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTermStatusRow(term: GradesTerm) {
    val (label, containerColor, contentColor) = when {
        term.syncFailed -> Triple(
            "同步失败",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        !term.isSynced -> Triple(
            "尚未同步",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(
            "已同步，无成绩记录",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("grades_empty_term_${term.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = term.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        StatusPill(
            text = label,
            containerColor = containerColor,
            contentColor = contentColor,
        )
    }
}

private fun emptySpec(
    state: GradesUiState,
    onSync: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenOfficialPage: () -> Unit,
    termVisibility: GradesTermVisibility,
): GradesEmptySpec = when (state.account) {
    GradesAccountState.ACCOUNT_REQUIRED -> GradesEmptySpec(
        title = "未设置学校账号",
        subtitle = "保存学校账号后可同步教务成绩。",
        actionLabel = "设置学校账号",
        action = onOpenAccount,
    )
    GradesAccountState.RESTORING -> GradesEmptySpec(
        title = "正在恢复学校账号",
        subtitle = "恢复完成后可同步教务成绩。",
    )
    GradesAccountState.CLEANING -> GradesEmptySpec(
        title = "正在清理学校账号",
        subtitle = "清理完成后可重新设置账号并同步。",
    )
    GradesAccountState.READY -> when (val sync = state.sync) {
        GradesSyncState.Loading -> GradesEmptySpec(
            title = "正在读取成绩",
            subtitle = "请稍候。",
        )
        GradesSyncState.NotSynced -> GradesEmptySpec(
            title = "尚未同步成绩",
            subtitle = "同步教务后查看成绩与学分统计。",
            actionLabel = "教务同步",
            action = onSync,
        )
        GradesSyncState.Syncing -> GradesEmptySpec(
            title = "正在同步教务成绩",
            subtitle = "完成后会显示成绩与统计。",
        )
        is GradesSyncState.Ready -> when {
            state.terms.isEmpty() -> GradesEmptySpec(
                title = "学期状态未知",
                subtitle = "教务没有返回学期列表或成绩记录。",
                actionLabel = "重新同步",
                action = onSync,
            )
            termVisibility.syncedEmptyCount == state.terms.size -> GradesEmptySpec(
                title = "暂无成绩记录",
                subtitle = "教务系统没有返回本科成绩记录。",
                actionLabel = "重新同步",
                action = onSync,
            )
            termVisibility.unsyncedWithoutRecordsCount == state.terms.size -> GradesEmptySpec(
                title = "学期成绩尚未同步",
                subtitle = "学期列表来自教务，成绩尚未同步。",
                actionLabel = "教务同步",
                action = onSync,
            )
            else -> {
                val failed = termVisibility.failedWithoutRecordsCount
                val unsynced = termVisibility.unsyncedWithoutRecordsCount
                val syncedEmpty = termVisibility.syncedEmptyCount
                val counts = buildList {
                    add("$failed 个学期同步失败且无记录")
                    add("$unsynced 个学期尚未同步")
                    add("$syncedEmpty 个学期已同步但无记录")
                }.joinToString("；")
                GradesEmptySpec(
                    title = when {
                        failed == state.terms.size -> "学期成绩同步失败"
                        failed > 0 -> "学期成绩同步未完成"
                        else -> "学期成绩尚未同步"
                    },
                    subtitle = "$counts。",
                    actionLabel = if (failed > 0) "重试" else "教务同步",
                    action = onSync,
                )
            }
        }
        is GradesSyncState.Failed -> {
            val official = sync.reason.opensOfficialPage()
            val failedWithoutRecords = termVisibility.failedWithoutRecordsCount
            val title = when {
                state.terms.isEmpty() -> "同步失败"
                failedWithoutRecords == state.terms.size -> "学期成绩同步失败"
                failedWithoutRecords > 0 -> "学期成绩同步未完成"
                else -> "同步失败"
            }
            val failureMessage = "${sync.reason.displayLabel()}。未获取到成绩，" +
                "${failureHint(sync.reason)}。"
            val termStatusMessage = noRecordTermCounts(termVisibility)
            GradesEmptySpec(
                title = title,
                subtitle = if (termStatusMessage == null) {
                    failureMessage
                } else {
                    "$failureMessage $termStatusMessage。"
                },
                actionLabel = if (official) "打开学校成绩网页" else "重试",
                action = if (official) onOpenOfficialPage else onSync,
            )
        }
    }
}

private fun noRecordTermCounts(termVisibility: GradesTermVisibility): String? = buildList {
    if (termVisibility.failedWithoutRecordsCount > 0) {
        add("${termVisibility.failedWithoutRecordsCount} 个学期同步失败且无记录")
    }
    if (termVisibility.unsyncedWithoutRecordsCount > 0) {
        add("${termVisibility.unsyncedWithoutRecordsCount} 个学期尚未同步")
    }
    if (termVisibility.syncedEmptyCount > 0) {
        add("${termVisibility.syncedEmptyCount} 个学期已同步但无记录")
    }
}.takeIf { it.isNotEmpty() }?.joinToString("；")

private fun accountSummary(state: GradesUiState): String {
    val history = if (state.courses.isNotEmpty()) {
        "已有 ${state.courses.size} 门历史记录仍可查看"
    } else {
        "无法同步教务成绩"
    }
    return when (state.account) {
        GradesAccountState.ACCOUNT_REQUIRED -> "未设置学校账号 · $history"
        GradesAccountState.RESTORING -> "正在恢复学校账号 · $history"
        GradesAccountState.CLEANING -> "正在清理学校账号 · $history"
        GradesAccountState.READY -> syncSummary(state)
    }
}

private fun syncSummary(state: GradesUiState): String {
    val hasCourses = state.courses.isNotEmpty()
    return when (state.sync) {
        GradesSyncState.Loading ->
            if (hasCourses) "正在读取，当前显示已有记录" else "正在读取本机成绩"
        GradesSyncState.NotSynced ->
            if (hasCourses) "显示已有成绩记录" else "尚未同步教务成绩"
        GradesSyncState.Syncing ->
            if (hasCourses) "正在同步，当前显示已有记录" else "正在获取教务成绩"
        is GradesSyncState.Ready ->
            if (hasCourses) {
                "${state.terms.size} 个学期 · ${state.courses.size} 门课程"
            } else {
                "暂无成绩记录"
            }
        is GradesSyncState.Failed ->
            if (hasCourses) {
                failedSummaryWithRecords(state)
            } else {
                "未获取到成绩"
            }
    }
}

/**
 * 同步失败但已有记录时的摘要。
 *
 * 记录可能同时来自本次同步成功的学期和更早同步的学期，因此只按
 * [GradesTerm.isSynced] 说明已同步学期数（分母为学期索引总数），
 * 并按 [GradesTerm.syncFailed] 区分失败的学期，不笼统标成“历史”。
 */
private fun failedSummaryWithRecords(state: GradesUiState): String {
    val syncedTerms = state.terms.count { it.isSynced }
    if (state.terms.isEmpty() || syncedTerms == 0) {
        return "${state.courses.size} 门课程 · 学期状态未知"
    }
    val failedTerms = state.terms.count { it.syncFailed }
    return buildString {
        append("$syncedTerms/${state.terms.size} 个学期已同步")
        append(" · ${state.courses.size} 门课程")
        if (failedTerms > 0) {
            append(" · $failedTerms 个学期同步失败")
        }
    }
}

private fun syncDetail(
    state: GradesUiState,
    termVisibility: GradesTermVisibility,
): String? = when (val sync = state.sync) {
    is GradesSyncState.Ready -> {
        val details = buildList {
            sync.syncedAtMillis?.takeIf { it > 0L }
                ?.let { add("上次同步 ${RelativeTime.ago(it)}") }
            addAll(noRecordSyncDetails(termVisibility))
        }.joinToString("；").takeIf { it.isNotEmpty() }
        appendUnsavedHint(
            detail = details,
            unsavedChanges = state.unsavedChanges,
        )
    }
    is GradesSyncState.Failed -> {
        val retainedSummary = if (state.courses.isNotEmpty()) {
            val syncedTerms = state.terms.count { it.isSynced }
            val failedTerms = state.terms.count { it.syncFailed }
            "${sync.reason.displayLabel()} · " + when {
                syncedTerms == 0 -> "已保留本机已有成绩记录"
                failedTerms > 0 -> "$failedTerms 个学期同步失败，已保留已同步记录"
                else -> "已保留已同步记录"
            }
        } else {
            "${sync.reason.displayLabel()} · ${failureHint(sync.reason)}"
        }
        appendUnsavedHint(
            detail = (listOf(retainedSummary) + noRecordSyncDetails(termVisibility))
                .joinToString("；"),
            unsavedChanges = state.unsavedChanges,
        )
    }
    GradesSyncState.Syncing ->
        if (state.courses.isNotEmpty()) "完成后会更新当前记录" else null
    GradesSyncState.Loading -> null
    GradesSyncState.NotSynced ->
        if (state.courses.isNotEmpty()) null else "同步后可查看成绩与学分"
}

/** 同步详情中需要直接披露的、无课程记录的失败与未同步学期数量。 */
private fun noRecordSyncDetails(termVisibility: GradesTermVisibility): List<String> = buildList {
    if (termVisibility.failedWithoutRecordsCount > 0) {
        add("${termVisibility.failedWithoutRecordsCount} 个学期同步失败且无记录")
    }
    if (termVisibility.unsyncedWithoutRecordsCount > 0) {
        add("${termVisibility.unsyncedWithoutRecordsCount} 个学期尚未同步")
    }
}

/**
 * 本次结果未写入本机时，在现有同步详情后追加固定提示。
 *
 * 学期同步失败与保存失败可以同时发生：前者说明哪些记录仍被保留，后者说明
 * 本次结果在重启后会丢失。两者共用同步详情行展示，不新增入口；只有未保存
 * 提示时也单独显示，避免有结果但课程为空的部分覆盖被误认为完全成功。
 */
private fun appendUnsavedHint(detail: String?, unsavedChanges: Boolean): String? =
    if (!unsavedChanges) {
        detail
    } else {
        listOfNotNull(detail, UNSAVED_CHANGES_MESSAGE).joinToString("；")
    }

private fun GradesSyncFailure.displayLabel(): String = when (this) {
    GradesSyncFailure.NETWORK -> "网络不可用"
    GradesSyncFailure.AUTH -> "学校登录已失效"
    GradesSyncFailure.STORAGE -> "本机成绩读取或保存失败"
    GradesSyncFailure.INTERACTION -> "学校要求交互验证"
    GradesSyncFailure.SERVER -> "教务系统暂时不可用"
    GradesSyncFailure.UNSUPPORTED -> "教务返回内容无法识别"
    GradesSyncFailure.UNKNOWN -> "同步未完成"
}

/** AUTH 与 INTERACTION 都需要用户到学校网页完成验证。 */
private fun GradesSyncFailure.opensOfficialPage(): Boolean =
    this == GradesSyncFailure.AUTH || this == GradesSyncFailure.INTERACTION

private fun failureHint(reason: GradesSyncFailure): String = when (reason) {
    GradesSyncFailure.AUTH -> "请检查学校账号后重试"
    GradesSyncFailure.INTERACTION -> "请完成学校网页验证后重试"
    GradesSyncFailure.STORAGE -> "请重新同步"
    GradesSyncFailure.NETWORK,
    GradesSyncFailure.SERVER,
    GradesSyncFailure.UNSUPPORTED,
    GradesSyncFailure.UNKNOWN -> "请稍后重试"
}
