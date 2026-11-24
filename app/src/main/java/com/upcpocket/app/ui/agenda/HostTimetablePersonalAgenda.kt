package com.upcpocket.app.ui.agenda

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.timetable.TimetableAgendaEntry
import com.upcpocket.app.timetable.TimetableAgendaState
import com.upcpocket.app.timetable.TimetableAgendaWindow
import com.upcpocket.app.timetable.TimetablePersonalAgenda
import com.upcpocket.app.todo.PersonalSchedule
import com.upcpocket.app.todo.PersonalScheduleStore
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 把待办插件的本机日程存储适配成课表模块的最小只读视图。
 *
 * 只做窗口订阅与新建/编辑转发；课表模块不感知待办数据库、Room 或学校账号。
 * 读取中、读取失败与真正空列表分别保留，不把失败折叠成空结果。
 */
@Composable
fun rememberTimetablePersonalAgenda(
    store: PersonalScheduleStore?,
    openCreate: (LocalDate) -> Unit,
    openEdit: (String) -> Unit,
): TimetablePersonalAgenda? {
    if (store == null) return null
    val latestCreate by rememberUpdatedState(openCreate)
    val latestEdit by rememberUpdatedState(openEdit)
    return remember(store) {
        object : TimetablePersonalAgenda {
            @Composable
            override fun windowState(window: TimetableAgendaWindow): TimetableAgendaState {
                val stateFlow: Flow<TimetableAgendaState> = remember(store, window) {
                    store.observeWindow(window.fromInclusive, window.untilExclusive)
                        .map<List<PersonalSchedule>, TimetableAgendaState> { schedules ->
                            TimetableAgendaState.Ready(
                                schedules.map(PersonalSchedule::toAgendaEntry),
                            )
                        }
                        .onStart { emit(TimetableAgendaState.Loading) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            emit(TimetableAgendaState.Failed)
                        }
                        .distinctUntilChanged()
                }
                val state by stateFlow.collectAsStateWithLifecycle(
                    initialValue = TimetableAgendaState.Loading,
                )
                return state
            }

            override fun openCreate(date: LocalDate) = latestCreate(date)

            override fun openEdit(id: String) = latestEdit(id)
        }
    }
}

private fun PersonalSchedule.toAgendaEntry(): TimetableAgendaEntry = TimetableAgendaEntry(
    id = id,
    title = title,
    startAt = startAt,
    endAt = endAt,
    location = location,
)
