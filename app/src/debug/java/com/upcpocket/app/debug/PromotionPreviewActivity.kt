package com.upcpocket.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.timetable.PromotionTodaySection
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.reminder.TodoReminderAccess
import com.upcpocket.app.ui.home.HomeScreen
import com.upcpocket.app.ui.theme.UpcPocketTheme
import com.upcpocket.app.ui.todo.TodoHomeSection
import com.upcpocket.app.ui.todo.TodoScreen
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

/** Screenshot-only composition of real screens and memory fixtures. No AppGraph or disk access. */
class PromotionPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen").takeIf { it in setOf("home", "todo") } ?: "home"
        val dark = intent.getBooleanExtra("dark", false)
        val store = PromotionTodoStore()
        val host = object : PluginSectionHost {
            override fun openSchoolAccountSettings() = Unit
            override fun open(route: PluginRouteId) = Unit
        }
        val services = listOf(
            Triple("power", "电费", UiIcon.ELECTRICITY),
            Triple("classroom", "空闲教室", UiIcon.CLASSROOM),
            Triple("library", "图书馆", UiIcon.LIBRARY),
            Triple("shiguang", "石光", UiIcon.INSIGHTS),
            Triple("serviceportal", "e站通", UiIcon.TODO),
        ).mapIndexed { index, (id, title, icon) ->
            PluginEntry(UiEntryId.parse("$id.promotion"), PluginId.parse(id), UiSurface.HOME_SERVICES,
                index, title, "校园服务", icon, null)
        }
        val today = PluginSection(UiEntryId.parse("timetable.promotion"), PluginId.parse("timetable"),
            UiSurface.HOME_TIMETABLE, 0) { PromotionTodaySection() }
        val todo = PluginSection(UiEntryId.parse("todo.promotion"), PluginId.parse("todo"),
            UiSurface.HOME_TODO, 0) { TodoHomeSection(store, host) }
        setContent {
            UpcPocketTheme(darkMode = if (dark) DarkMode.DARK else DarkMode.LIGHT, preset = ThemePreset.BLUE) {
                if (screen == "todo") TodoScreen(store) else HomeScreen(
                    serviceEntries = services,
                    timetableSections = listOf(today),
                    cardSections = emptyList(),
                    todoSections = listOf(todo),
                    sectionHost = host,
                    onOpenRoute = {}, onOpenSettings = {}, onOpenOfficialWeb = {},
                )
            }
        }
    }
}

/** All changes disappear with this Activity; no alarms, notifications, accounts or Room. */
private class PromotionTodoStore : TodoStore {
    private val now = System.currentTimeMillis()
    override val tasks = MutableStateFlow(listOf(
        task("整理高等数学笔记", 1),
        task("完成程序设计实验", 2),
        task("准备英语课堂展示", 3),
        task("归还图书馆借书", 4, completed = true),
    ))
    override val pendingOpenTask = MutableStateFlow<String?>(null)
    private fun task(title: String, index: Int, completed: Boolean = false) = TodoTask(
        id = "promotion-$index", title = title, completed = completed,
        createdAt = now - index * 60_000, updatedAt = now,
        completedAt = if (completed) now else null, reminderAt = null,
        reminderFiredAt = null, revision = 1,
    )
    override fun reminderAccess() = TodoReminderAccess(true, true)
    override suspend fun add(title: String, reminderAt: Long?): TodoTask =
        task(title, tasks.value.size + 1).copy(id = UUID.randomUUID().toString(), reminderAt = reminderAt)
            .also { tasks.value = tasks.value + it }
    override suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean {
        if (tasks.value.none { it.id == id }) return false
        tasks.value = tasks.value.map { if (it.id == id) it.copy(title = title, reminderAt = reminderAt, revision = it.revision + 1) else it }
        return true
    }
    override suspend fun setCompleted(id: String, completed: Boolean): Boolean {
        val task = tasks.value.firstOrNull { it.id == id } ?: return false
        return setCompletedIfRevision(id, task.revision, completed) != null
    }
    override suspend fun setCompletedIfRevision(id: String, revision: Int, completed: Boolean): TodoTask? {
        val task = tasks.value.firstOrNull { it.id == id && it.revision == revision } ?: return null
        val updated = task.copy(completed = completed, completedAt = if (completed) System.currentTimeMillis() else null,
            updatedAt = System.currentTimeMillis(), revision = revision + 1)
        tasks.value = tasks.value.map { if (it.id == id) updated else it }
        return updated
    }
    override suspend fun delete(id: String): TodoTask? = tasks.value.firstOrNull { it.id == id }?.also {
        tasks.value = tasks.value.filterNot { it.id == id }
    }
    override suspend fun restore(task: TodoTask): Boolean {
        if (tasks.value.any { it.id == task.id }) return false
        tasks.value = tasks.value + task.copy(revision = task.revision + 1)
        return true
    }
    override fun openTask(taskId: String) { pendingOpenTask.value = taskId }
    override fun consumeOpenTask() { pendingOpenTask.value = null }
    override fun ensureNotificationChannel() = Unit
    override suspend fun reconcileAll() = Unit
}
