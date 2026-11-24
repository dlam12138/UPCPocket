package com.upcpocket.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.upcpocket.app.data.prefs.HomeAnniversaryMode
import com.upcpocket.app.data.prefs.HomeAnniversarySettings
import com.upcpocket.app.ui.components.CampusButton
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.CampusIconButton
import com.upcpocket.app.ui.components.CampusOutlinedButton
import com.upcpocket.app.ui.components.CampusSwitch
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.CampusTopAppBar
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassDatePicker
import com.upcpocket.app.ui.components.GlassDatePickerDialog
import com.upcpocket.app.ui.components.GlassOutlinedTextField
import com.upcpocket.app.ui.home.homeAnniversaryPickerDate
import com.upcpocket.app.ui.home.homeAnniversaryPickerMillis
import com.upcpocket.app.ui.theme.appScaffoldContainerColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val NO_EPOCH_DAY = Long.MIN_VALUE
private val ANNIVERSARY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

private fun HomeAnniversaryMode.label(): String = when (this) {
    HomeAnniversaryMode.COUNT_UP -> "正计时"
    HomeAnniversaryMode.COUNT_DOWN -> "倒计时"
}

private fun HomeAnniversaryMode.description(): String = when (this) {
    HomeAnniversaryMode.COUNT_UP -> "起始日前显示还有几天开始，起始日为第 1 天。"
    HomeAnniversaryMode.COUNT_DOWN -> "目标日前显示还有几天，目标日显示就是今天。"
}

private fun localDateOrNull(epochDay: Long): LocalDate? =
    runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull()

/**
 * 「设置 → 应用 → 首页纪念日」。
 *
 * [settings] 为 null 表示 DataStore 尚未读完：不展示或提交默认值。表单只在首次
 * 读到真实偏好时初始化，之后保留用户草稿；保存成功才返回，失败留在本页并保留
 * 全部输入。日期按 UTC 日历日期与 DatePicker 互转，首页再按上海日期计算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAnniversaryScreen(
    settings: HomeAnniversarySettings?,
    onSave: suspend (HomeAnniversarySettings) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var initialized by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var dateEpochDay by rememberSaveable { mutableLongStateOf(NO_EPOCH_DAY) }
    var modeName by rememberSaveable {
        mutableStateOf(HomeAnniversaryMode.COUNT_DOWN.name)
    }
    var visible by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings) {
        if (!initialized && settings != null) {
            title = settings.title
            dateEpochDay = settings.date?.toEpochDay() ?: NO_EPOCH_DAY
            modeName = settings.mode.name
            visible = settings.visible
            initialized = true
        }
    }

    val loaded = initialized && settings != null
    val selectedDate = remember(dateEpochDay) {
        dateEpochDay.takeIf { it != NO_EPOCH_DAY }?.let(::localDateOrNull)
    }
    val mode = HomeAnniversaryMode.fromStoredValue(modeName)
    val canSave = loaded &&
        !saving &&
        title.trim().isNotEmpty() &&
        selectedDate != null

    fun submit() {
        if (!loaded || saving) return
        val trimmedTitle = title.trim()
        val date = selectedDate ?: return
        if (trimmedTitle.isEmpty()) return
        saving = true
        errorText = null
        scope.launch {
            try {
                onSave(
                    HomeAnniversarySettings(
                        title = trimmedTitle,
                        date = date,
                        mode = mode,
                        visible = visible,
                    ),
                )
                onBack()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saving = false
                errorText = "保存失败，请重试。"
            }
        }
    }

    Scaffold(
        containerColor = appScaffoldContainerColor(),
        topBar = {
            CampusTopAppBar(
                title = { Text("首页纪念日") },
                navigationIcon = {
                    CampusIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!loaded) {
                Text(
                    text = "正在读取纪念日设置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = "按上海日期计算。设置保存在本机。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    GlassOutlinedTextField(
                        value = title,
                        onValueChange = { if (!saving) title = it },
                        label = { Text("标题") },
                        placeholder = { Text("如：入学纪念日") },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("anniversary_title"),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "日期",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CampusOutlinedButton(
                            onClick = { showDatePicker = true },
                            enabled = !saving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("anniversary_date"),
                        ) {
                            Text(
                                text = selectedDate
                                    ?.format(ANNIVERSARY_DATE_FORMATTER)
                                    ?: "请选择日期",
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "计时方式",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeAnniversaryMode.entries.forEach { option ->
                                CampusFilterChip(
                                    selected = mode == option,
                                    onClick = { if (!saving) modeName = option.name },
                                    enabled = !saving,
                                    label = { Text(option.label()) },
                                    modifier = Modifier.testTag(
                                        "anniversary_mode_${option.name.lowercase()}",
                                    ),
                                )
                            }
                        }
                        Text(
                            text = mode.description(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = visible,
                                enabled = !saving,
                                role = Role.Switch,
                                onValueChange = { visible = it },
                            )
                            .testTag("anniversary_visible"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "显示在首页",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "关闭后保留标题、日期和计时方式。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        CampusSwitch(
                            checked = visible,
                            onCheckedChange = null,
                            enabled = !saving,
                        )
                    }
                }
            }

            errorText?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            CampusButton(
                onClick = ::submit,
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("anniversary_save"),
            ) {
                Text(if (saving) "保存中" else "保存")
            }

            if (title.trim().isEmpty() || selectedDate == null) {
                Text(
                    text = "填写标题并选择日期后可保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = homeAnniversaryPickerMillis(selectedDate),
        )
        GlassDatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                CampusTextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            dateEpochDay = homeAnniversaryPickerDate(millis).toEpochDay()
                        }
                        showDatePicker = false
                    },
                    modifier = Modifier.testTag("anniversary_date_confirm"),
                ) { Text("确定") }
            },
            dismissButton = {
                CampusTextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            GlassDatePicker(state = pickerState)
        }
    }
}
