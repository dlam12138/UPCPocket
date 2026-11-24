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

    val effectiveTermId = selectedTermId.takeIf { id ->
        id.isNotEmpty() && state.terms.any { it.id == id }
    }
    val selectedTerm = effectiveTermId?.let { id -> state.terms.firstOrNull { it.id == id } }
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
                onOpenAccount = onOpenAccount,
                onOpenOfficialPage = onOpenOfficialPage,
            )
            if (hasData && state.terms.isNotEmpty()) {
                GradesTermSelector(
                    terms = state.terms,
                    selectedTermId = effectiveTermId,
                    onSelect = selectTerm,
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
                        onShowAllTerms = { selectedTermId = NO_FILTER },
                        onSync = onSync,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    GradesOverview(
                        allCourses = state.courses,
                        courses = overviewCourses,
                        terms = state.terms,
                        selectedTermId = effectiveTermId,
                        selectedTerm = selectedTerm,
                        stats = stats,
                        onTermClick = openTerm,
                        onNatureClick = openNature,
                        onBandClick = openBand,
                        onShowAllTerms = { selectedTermId = NO_FILTER },
                        onSync = onSync,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                GradesSyncEmptyState(
                    state = state,
                    onSync = onSync,
                    onOpenAccount = onOpenAccount,
                    onOpenOfficialPage = onOpenOfficialPage,
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
            is GradesSyncState.Ready ->
                Triple("已同步", scheme.secondaryContainer, scheme.onSecondaryContainer)
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
        syncDetail(state)?.let { detail ->
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "学期",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
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
    onSync: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenOfficialPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = emptySpec(state, onSync, onOpenAccount, onOpenOfficialPage)

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
    }
}

private fun emptySpec(
    state: GradesUiState,
    onSync: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenOfficialPage: () -> Unit,
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
            state.terms.isNotEmpty() && state.terms.none { it.isSynced } -> GradesEmptySpec(
                title = "学期成绩尚未同步",
                subtitle = "学期列表来自教务，成绩尚未同步。",
                actionLabel = "教务同步",
                action = onSync,
            )
            state.terms.any { it.syncFailed } -> GradesEmptySpec(
                title = "学期成绩同步失败",
                subtitle = "没有可显示的历史记录，请重试。",
                actionLabel = "重试",
                action = onSync,
            )
            else -> GradesEmptySpec(
                title = "暂无成绩记录",
                subtitle = "教务系统没有返回本科成绩记录。",
                actionLabel = "重新同步",
                action = onSync,
            )
        }
        is GradesSyncState.Failed -> {
            val official = sync.reason.opensOfficialPage()
            GradesEmptySpec(
                title = "同步失败",
                subtitle = "${sync.reason.displayLabel()}。未获取到成绩，" +
                    "${failureHint(sync.reason)}。",
                actionLabel = if (official) "打开学校成绩网页" else "重试",
                action = if (official) onOpenOfficialPage else onSync,
            )
        }
    }
}

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

private fun syncDetail(state: GradesUiState): String? = when (val sync = state.sync) {
    is GradesSyncState.Ready -> appendUnsavedHint(
        detail = sync.syncedAtMillis?.takeIf { it > 0L }
            ?.let { "上次同步 ${RelativeTime.ago(it)}" },
        unsavedChanges = state.unsavedChanges,
    )
    is GradesSyncState.Failed -> appendUnsavedHint(
        detail = if (state.courses.isNotEmpty()) {
            val syncedTerms = state.terms.count { it.isSynced }
            val failedTerms = state.terms.count { it.syncFailed }
            "${sync.reason.displayLabel()} · " + when {
                syncedTerms == 0 -> "已保留本机已有成绩记录"
                failedTerms > 0 -> "$failedTerms 个学期同步失败，已保留已同步记录"
                else -> "已保留已同步记录"
            }
        } else {
            "${sync.reason.displayLabel()} · ${failureHint(sync.reason)}"
        },
        unsavedChanges = state.unsavedChanges,
    )
    GradesSyncState.Syncing ->
        if (state.courses.isNotEmpty()) "完成后会更新当前记录" else null
    GradesSyncState.Loading -> null
    GradesSyncState.NotSynced ->
        if (state.courses.isNotEmpty()) null else "同步后可查看成绩与学分"
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
