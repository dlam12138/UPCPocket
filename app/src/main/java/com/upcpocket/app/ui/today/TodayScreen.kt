package com.upcpocket.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.domain.FocusStats
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.nav.DOCK_CLEARANCE
import com.upcpocket.app.ui.schedule.DemoBanner

/**
 * 「今天」页 —— 首页。
 *
 * 结构：**演示横幅（仅在演示模式）** → 今天的时间轴（上课 / 自己加的日程 / 已完成的专注）
 * → 今日专注卡 → 学期概览。
 *
 * M1 删掉了上游的电费与校园卡卡片：那两块数据只能来自校园接口，
 * 本地原型既没有数据源，也不该摆一张永远"未登录"的空卡。
 *
 * 时间轴只做"我这一天长什么样"，不做成就/打卡那套通用番茄钟的东西，
 * 也不画课间空档、不提示"够不够一个番茄"—— 那些是替用户做安排，属于噪音。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenSchedule: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenFocusStats: () -> Unit,
    onOpenMore: () -> Unit,
    onOpenDemoSettings: () -> Unit,
    vm: TodayViewModel = viewModel(),
    timelineVm: TodayTimelineViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val timeline by timelineVm.state.collectAsStateWithLifecycle()

    // 从课表/专注页切回来时重算：时间轴是会变的（刚加完日程、刚记完一笔专注）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onResume()
        timelineVm.reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今天") },
                actions = {
                    IconButton(onClick = onOpenMore) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "更多",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                // 悬浮 dock 是覆盖层：内容从胶囊底下穿过，这里垫出尾部余量
                .padding(bottom = DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 演示模式横幅：今天页是打开 App 的第一屏，虚构数据必须在这里就说明白
            if (state.demoMode) {
                DemoBanner(onOpenSettings = onOpenDemoSettings)
            }

            // ① 今天的时间轴：上课 / 已完成的专注（课间空档不画）
            TodayTimelineCard(state = timeline, onOpenSchedule = onOpenSchedule)

            // ② 今天的日程：自己在课表里加的（没有就不显示这张卡）
            TodayCustomScheduleCard(
                entries = timeline.customEntries,
                onOpenSchedule = onOpenSchedule,
            )

            // ③ 专注与统计：本地能力，和课表互不依赖
            FocusSummaryCard(
                focusSeconds = state.todayFocusSeconds,
                focusCount = state.todayFocusCount,
                streakDays = state.streakDays,
                onOpenFocus = onOpenFocus,
                onOpenStats = onOpenFocusStats,
            )

            // ④ 学期概览（周次 / 总周数 / 开始日期），纯本地信息
            SemesterSummaryCard(state = state, onOpenSchedule = onOpenSchedule)

            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 今日专注卡。
 *
 * 这里刻意只显示"今天专注了多久、几个番茄、连续几天"——
 * 更细的热力图与标签分布在学习统计页。今天页要给的是"今天做得怎么样"的结论，
 * 不是一堆需要解读的图表。
 */
@Composable
private fun FocusSummaryCard(
    focusSeconds: Int,
    focusCount: Int,
    streakDays: Int,
    onOpenFocus: () -> Unit,
    onOpenStats: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("专注", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "学习统计",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOpenStats),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (focusSeconds > 0) FocusStats.humanDuration(focusSeconds) else "0 分钟",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "今天 · $focusCount 个番茄",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                if (streakDays > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.EmojiEvents,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "连续 $streakDays 天",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            Button(onClick = onOpenFocus, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("去专注")
            }
            Text(
                text = "番茄钟、专注记录与统计都在本机，不需要联网，也不需要账号。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 学期概览：只讲本地事实（第几周、共几周、开学日），不做任何"校历已同步"的暗示 */
@Composable
private fun SemesterSummaryCard(state: TodayUiState, onOpenSchedule: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSchedule)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AccessTime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("课表", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            Text(text = state.semesterLine, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = state.courseSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
