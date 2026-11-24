package com.upcpocket.app.ui.todo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.upcpocket.app.todo.PersonalSchedule
import com.upcpocket.app.todo.PersonalScheduleStore
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.PersonalScheduleTime
import com.upcpocket.app.ui.components.CampusButton
import com.upcpocket.app.ui.components.CampusOutlinedButton
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.GlassModalBottomSheet as ModalBottomSheet
import java.io.Serializable
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/**
 * 课表日期入口与日程待办新增入口共用的编辑请求。
 *
 * [Add.date] 是调用方所选日期，[Add.zone] 是该日期所在的时区；待办截止按这个日期
 * 预填。个人日程的录入与显示仍固定使用上海时区。
 */
sealed interface AgendaEditorRequest : Serializable {

    data class Add(val date: LocalDate, val zone: ZoneId) : AgendaEditorRequest

    data class EditSchedule(val id: String) : AgendaEditorRequest
}

/**
 * 个人日程与「待办／日程」新建流程的宿主。
 *
 * `Add` 先让用户选择待办或日程；待办继续复用 [TodoEditorSheet] 的连续添加行为，
 * 日程打开上海时间表单。`EditSchedule` 从本机存储重新读取目标，读取失败或目标已
 * 删除都明确提示，不把空结果当成可编辑日程。
 */
@Composable
fun AgendaEditorHost(
    todoStore: TodoStore,
    personalScheduleStore: PersonalScheduleStore,
    request: AgendaEditorRequest?,
    onDismiss: () -> Unit,
) {
    when (val active = request) {
        null -> Unit

        is AgendaEditorRequest.Add -> {
            var type by rememberSaveable(active) { mutableStateOf<AgendaEntryType?>(null) }
            when (type) {
                null -> AgendaTypeSheet(
                    onTodo = { type = AgendaEntryType.TODO },
                    onSchedule = { type = AgendaEntryType.SCHEDULE },
                    onDismiss = onDismiss,
                )

                AgendaEntryType.TODO -> TodoEditorSheet(
                    store = todoStore,
                    initialTask = null,
                    onDismiss = onDismiss,
                    initialDueAt = defaultTodoDueAt(active.date, active.zone),
                )

                AgendaEntryType.SCHEDULE -> PersonalScheduleEditorSheet(
                    store = personalScheduleStore,
                    initial = null,
                    initialDate = active.date,
                    onDismiss = onDismiss,
                )
            }
        }

        is AgendaEditorRequest.EditSchedule -> key(active.id) {
            ScheduleEditorLoader(
                store = personalScheduleStore,
                id = active.id,
                onDismiss = onDismiss,
            )
        }
    }
}

private enum class AgendaEntryType {
    TODO,
    SCHEDULE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaTypeSheet(
    onTodo: () -> Unit,
    onSchedule: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("新建", style = MaterialTheme.typography.titleLarge)
            Text(
                "选择本机条目类型。日程按上海时间记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CampusButton(
                onClick = onTodo,
                modifier = Modifier.fillMaxWidth().testTag("agenda_type_todo"),
            ) {
                Text("待办")
            }
            CampusOutlinedButton(
                onClick = onSchedule,
                modifier = Modifier.fillMaxWidth().testTag("agenda_type_schedule"),
            ) {
                Text("日程")
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                CampusTextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("agenda_type_cancel"),
                ) { Text("取消") }
            }
        }
    }
}

private sealed interface ScheduleEditorLoad {
    data object Loading : ScheduleEditorLoad
    data object Failed : ScheduleEditorLoad
    data object Missing : ScheduleEditorLoad
    data class Ready(val schedule: PersonalSchedule) : ScheduleEditorLoad
}

@Composable
private fun ScheduleEditorLoader(
    store: PersonalScheduleStore,
    id: String,
    onDismiss: () -> Unit,
) {
    var readGeneration by rememberSaveable { mutableIntStateOf(0) }
    val load by produceState<ScheduleEditorLoad>(
        initialValue = ScheduleEditorLoad.Loading,
        store,
        id,
        readGeneration,
    ) {
        value = ScheduleEditorLoad.Loading
        value = try {
            store.byId(id)?.let { ScheduleEditorLoad.Ready(it) } ?: ScheduleEditorLoad.Missing
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ScheduleEditorLoad.Failed
        }
    }

    when (val state = load) {
        ScheduleEditorLoad.Loading -> AgendaNoticeSheet(
            message = "正在读取日程",
            onDismiss = onDismiss,
        )

        ScheduleEditorLoad.Missing -> AgendaNoticeSheet(
            message = "日程已不存在。",
            onDismiss = onDismiss,
        )

        ScheduleEditorLoad.Failed -> AgendaNoticeSheet(
            message = "日程读取失败。",
            onDismiss = onDismiss,
            onRetry = { readGeneration++ },
        )

        is ScheduleEditorLoad.Ready -> PersonalScheduleEditorSheet(
            store = store,
            initial = state.schedule,
            initialDate = PersonalScheduleTime.shanghaiDate(state.schedule.startAt),
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaNoticeSheet(
    message: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onRetry?.let { retry ->
                    CampusTextButton(
                        onClick = retry,
                        modifier = Modifier.testTag("agenda_notice_retry"),
                    ) { Text("重试") }
                }
                Spacer(Modifier.weight(1f))
                CampusTextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("agenda_notice_close"),
                ) { Text("关闭") }
            }
        }
    }
}

private fun defaultTodoDueAt(date: LocalDate, zone: ZoneId): Long =
    date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
