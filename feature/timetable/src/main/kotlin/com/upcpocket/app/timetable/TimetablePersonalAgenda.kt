package com.upcpocket.app.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassIconButton
import com.upcpocket.app.ui.components.GlassModalBottomSheet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 宿主注入课表和首页的最小个人日程视图。
 *
 * 课表模块不依赖待办插件，也不直接打开数据库；窗口订阅、打开新建和按 ID 打开编辑
 * 都由宿主适配。所有窗口与时点均按 `Asia/Shanghai` 计算，显示也使用上海时间。
 */
data class TimetableAgendaEntry(
    val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val location: String? = null,
)

/** 半开时间窗口 `[fromInclusive, untilExclusive)`，毫秒时点。 */
data class TimetableAgendaWindow(
    val fromInclusive: Long,
    val untilExclusive: Long,
)

/** 本机日程读取状态；加载、失败与真正空列表分别表达。 */
sealed interface TimetableAgendaState {
    data object Loading : TimetableAgendaState

    data object Failed : TimetableAgendaState

    data class Ready(val entries: List<TimetableAgendaEntry>) : TimetableAgendaState
}

interface TimetablePersonalAgenda {

    /** 订阅与 [window] 相交的本机日程；只读本机，不触发学校请求。 */
    @Composable
    fun windowState(window: TimetableAgendaWindow): TimetableAgendaState

    /** 打开所选上海日期的新建流程（可选待办或日程）。 */
    fun openCreate(date: LocalDate)

    /** 按 ID 打开已有个人日程的编辑表单。 */
    fun openEdit(id: String)
}

val LocalTimetablePersonalAgenda = compositionLocalOf<TimetablePersonalAgenda?> { null }

/** 上海日期对应的半开日窗口；上海零点结束的日程只属于前一日。 */
fun timetableAgendaDayWindow(date: LocalDate): TimetableAgendaWindow {
    val start = date.atStartOfDay(TIMETABLE_ZONE)
    val end = date.plusDays(1).atStartOfDay(TIMETABLE_ZONE)
    return TimetableAgendaWindow(
        fromInclusive = start.toInstant().toEpochMilli(),
        untilExclusive = end.toInstant().toEpochMilli(),
    )
}

/** 覆盖 [dates] 全部上海日期的半开窗口；空列表返回 null。 */
fun timetableAgendaWeekWindow(dates: List<LocalDate>): TimetableAgendaWindow? {
    val first = dates.minOrNull() ?: return null
    val last = dates.maxOrNull() ?: return null
    return TimetableAgendaWindow(
        fromInclusive = first.atStartOfDay(TIMETABLE_ZONE).toInstant().toEpochMilli(),
        untilExclusive = last.plusDays(1).atStartOfDay(TIMETABLE_ZONE).toInstant().toEpochMilli(),
    )
}

/** 日程是否与 [date] 的上海日窗口相交；零点结束不会算到次日。 */
fun timetableAgendaCoversDate(entry: TimetableAgendaEntry, date: LocalDate): Boolean {
    if (entry.endAt <= entry.startAt) return false
    val window = timetableAgendaDayWindow(date)
    return entry.startAt < window.untilExclusive && entry.endAt > window.fromInclusive
}

/**
 * 个人日程在某一上海日期内的片段。
 *
 * [startMinute] / [endMinute] 是相对该日 00:00 的分钟数，范围 0–1440；[endMinute]
 * 为 1440 表示上海零点结束，仅出现在前一日。跨日日程在相交的每一天各出现一次。
 */
data class TimetableAgendaSegment(
    val entry: TimetableAgendaEntry,
    val startMinute: Int,
    val endMinute: Int,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
)

fun projectTimetableAgendaDay(
    entries: List<TimetableAgendaEntry>,
    date: LocalDate,
): List<TimetableAgendaSegment> {
    val window = timetableAgendaDayWindow(date)
    return entries.mapNotNull { entry ->
        if (entry.endAt <= entry.startAt) return@mapNotNull null
        if (!timetableAgendaCoversDate(entry, date)) return@mapNotNull null
        val startMinute = minutesFrom(window.fromInclusive, entry.startAt).coerceIn(0, 1440)
        val endMinute = minutesFrom(window.fromInclusive, entry.endAt).coerceIn(0, 1440)
        if (endMinute <= startMinute) return@mapNotNull null
        TimetableAgendaSegment(
            entry = entry,
            startMinute = startMinute,
            endMinute = endMinute,
            continuesBefore = entry.startAt < window.fromInclusive,
            continuesAfter = entry.endAt > window.untilExclusive,
        )
    }.sortedWith(
        compareBy<TimetableAgendaSegment>(
            { it.startMinute },
            { it.endMinute },
            { it.entry.title },
            { it.entry.id },
        ),
    )
}

/** 片段在选中日期内的显示时段，例如 `14:00–16:00`、`23:00–24:00（跨日）`。 */
fun timetableAgendaSegmentTimeLabel(segment: TimetableAgendaSegment): String {
    val range = "${agendaMinuteLabel(segment.startMinute)}–${agendaMinuteLabel(segment.endMinute)}"
    return when {
        segment.continuesBefore && segment.continuesAfter -> "全天（跨日）"
        segment.continuesBefore -> "$range（接前日）"
        segment.continuesAfter -> "$range（跨日）"
        else -> range
    }
}

/** 设备时区与上海不同时，查看时段的视图需要注明「上海时间」。 */
fun timetableAgendaNeedsShanghaiNote(
    deviceZone: ZoneId = ZoneId.systemDefault(),
): Boolean = deviceZone != TIMETABLE_ZONE

private fun minutesFrom(fromInclusive: Long, instant: Long): Int =
    ((instant - fromInclusive) / 60_000L).toInt()

private fun agendaMinuteLabel(minute: Int): String {
    val safe = minute.coerceIn(0, 1440)
    return String.format(Locale.CHINA, "%02d:%02d", safe / 60, safe % 60)
}

private val AGENDA_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

internal fun agendaDateLabel(date: LocalDate): String =
    date.format(AGENDA_DATE_FORMATTER)

@Composable
internal fun rememberAgendaNowMillis(): Long {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    return nowMillis
}

internal fun agendaMinuteOfDay(nowMillis: Long): Int {
    val time = Instant.ofEpochMilli(nowMillis).atZone(TIMETABLE_ZONE).toLocalTime()
    return time.hour * 60 + time.minute
}

/**
 * 只有展示日期就是上海实际今天时才返回当前分钟。
 *
 * 其它日期返回 null，避免昨天或明天同一时段被误标「进行中」或「已结束」；
 * 日期与分钟来自同一 [nowMillis]，零点切换时不会错位。
 */
internal fun agendaMinuteForDate(nowMillis: Long, date: LocalDate): Int? {
    val actualDate = Instant.ofEpochMilli(nowMillis).atZone(TIMETABLE_ZONE).toLocalDate()
    return if (date == actualDate) agendaMinuteOfDay(nowMillis) else null
}

/**
 * 课表或首页的个人日程列表正文。
 *
 * 只负责展示已投影的片段；加载、失败与真正空列表使用不同文案，且都不伪装成成功
 * 的空结果。点击行按 ID 打开宿主编辑表单。
 */
@Composable
internal fun TimetableAgendaList(
    state: TimetableAgendaState,
    date: LocalDate,
    nowMinute: Int?,
    onEdit: (String) -> Unit,
    emptyText: String,
    testTagPrefix: String,
) {
    when (state) {
        TimetableAgendaState.Loading -> Text(
            "正在读取本机日程…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp).testTag("${testTagPrefix}_loading"),
        )

        TimetableAgendaState.Failed -> Text(
            "本机日程读取失败。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 4.dp).testTag("${testTagPrefix}_failed"),
        )

        is TimetableAgendaState.Ready -> {
            val segments = projectTimetableAgendaDay(state.entries, date)
            if (segments.isEmpty()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp).testTag("${testTagPrefix}_empty"),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    segments.forEach { segment ->
                        key(segment.entry.id) {
                            TimetableAgendaRow(
                                segment = segment,
                                nowMinute = nowMinute,
                                showSourceTag = false,
                                onClick = { onEdit(segment.entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TimetableAgendaRow(
    segment: TimetableAgendaSegment,
    nowMinute: Int?,
    showSourceTag: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val inProgress = nowMinute != null &&
        nowMinute >= segment.startMinute && nowMinute < segment.endMinute
    val ended = nowMinute != null && segment.endMinute <= nowMinute
    val location = segment.entry.location?.trim()?.takeIf(String::isNotEmpty)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag("timetable_agenda_entry_${segment.entry.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(32.dp)
                .background(scheme.tertiary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    segment.entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showSourceTag) {
                    AgendaTag("日程", scheme.tertiary)
                }
                when {
                    inProgress -> AgendaTag("进行中", scheme.primary)
                    ended && !segment.continuesAfter -> AgendaTag("已结束", scheme.outline)
                }
            }
            Text(
                listOfNotNull(
                    timetableAgendaSegmentTimeLabel(segment),
                    location,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgendaTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        modifier = Modifier.padding(start = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 课表页的个人日程卡；日期由页面保存，任意日期都能新建或打开本机日程。 */
@Composable
internal fun TimetablePersonalAgendaCard(
    state: TimetableAgendaState,
    selectedDate: LocalDate,
    nowMinute: Int?,
    showShanghaiTime: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onCreate: (LocalDate) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth().testTag("timetable_personal_agenda")) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("个人日程", style = MaterialTheme.typography.titleSmall)
                if (showShanghaiTime) {
                    Text(
                        "上海时间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .testTag("timetable_agenda_timezone"),
                    )
                }
                Spacer(Modifier.weight(1f))
                CampusTextButton(
                    onClick = { onCreate(selectedDate) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("timetable_agenda_add"),
                ) { Text("新增") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    onClick = { onSelectDate(selectedDate.minusDays(1)) },
                    modifier = Modifier.testTag("timetable_agenda_previous_day"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "前一天",
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        agendaDateLabel(selectedDate),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (selectedDate != LocalDate.now(TIMETABLE_ZONE)) {
                        CampusTextButton(
                            onClick = { onSelectDate(LocalDate.now(TIMETABLE_ZONE)) },
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .testTag("timetable_agenda_today"),
                        ) { Text("回到今天") }
                    }
                }
                GlassIconButton(
                    onClick = { onSelectDate(selectedDate.plusDays(1)) },
                    modifier = Modifier.testTag("timetable_agenda_next_day"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "后一天",
                    )
                }
            }
            TimetableAgendaList(
                state = state,
                date = selectedDate,
                nowMinute = nowMinute,
                onEdit = onEdit,
                emptyText = "这一天没有个人日程。",
                testTagPrefix = "timetable_agenda",
            )
        }
    }
}

/**
 * 首页只有本机日程能力时使用的「今日安排」卡。
 *
 * 不读取学校账号、不查询课表；课表插件缺失或未同步时仍可选择日期入口新建和编辑。
 * 固定显示上海实际今天，跨日片段按半开窗口裁剪。
 */
@Composable
fun TimetablePersonalAgendaTodayCard(
    agenda: TimetablePersonalAgenda,
    modifier: Modifier = Modifier,
) {
    val nowMillis = rememberAgendaNowMillis()
    val today = Instant.ofEpochMilli(nowMillis).atZone(TIMETABLE_ZONE).toLocalDate()
    val state = agenda.windowState(timetableAgendaDayWindow(today))
    val showShanghaiTime = timetableAgendaNeedsShanghaiNote()
    GlassCard(modifier.fillMaxWidth().testTag("timetable_agenda_today_card")) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今日安排", style = MaterialTheme.typography.titleSmall)
                Text(
                    agendaDateLabel(today),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
                if (showShanghaiTime) {
                    Text(
                        "上海时间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .testTag("timetable_agenda_timezone"),
                    )
                }
                Spacer(Modifier.weight(1f))
                CampusTextButton(
                    onClick = { agenda.openCreate(today) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("timetable_agenda_add"),
                ) { Text("新增") }
            }
            TimetableAgendaList(
                state = state,
                date = today,
                nowMinute = agendaMinuteOfDay(nowMillis),
                onEdit = agenda::openEdit,
                emptyText = "今天没有个人日程。",
                testTagPrefix = "timetable_today_agenda",
            )
        }
    }
}

/** 周网格日期入口打开的当日日程列表；重叠和跨日片段按时间列出。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableAgendaDaySheet(
    state: TimetableAgendaState,
    date: LocalDate,
    nowMinute: Int?,
    showShanghaiTime: Boolean,
    onEdit: (String) -> Unit,
    onCreate: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // 小屏或当天多条相交日程时，面板整体可滚动到最后一行与关闭按钮。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // ModalBottomSheet 已提供屏幕高度上限；内容超过时在面板内滚动。
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .testTag("timetable_agenda_day_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agendaDateLabel(date), style = MaterialTheme.typography.titleLarge)
                if (showShanghaiTime) {
                    Text(
                        "上海时间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .testTag("timetable_agenda_timezone"),
                    )
                }
                Spacer(Modifier.weight(1f))
                CampusTextButton(
                    onClick = { onCreate(date) },
                    modifier = Modifier.testTag("timetable_agenda_add"),
                ) { Text("新增") }
            }
            TimetableAgendaList(
                state = state,
                date = date,
                nowMinute = nowMinute,
                onEdit = onEdit,
                emptyText = "这一天没有个人日程。",
                testTagPrefix = "timetable_sheet_agenda",
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                CampusTextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("timetable_agenda_sheet_close"),
                ) { Text("关闭") }
            }
        }
    }
}
