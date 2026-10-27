package com.upcpocket.app.ui.schedule

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upcpocket.app.domain.CsvScheduleImport
import com.upcpocket.app.domain.Period
import com.upcpocket.app.domain.Semester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** SAF 授权仅用于读取用户主动选择的文件，不请求存储权限或保留 URI 授权。 */
@Composable
fun CsvImportDialog(semester: Semester, periods: List<Period>, vm: ScheduleViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<CsvScheduleImport.Preview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun parseBytes(bytes: ByteArray) {
        busy = true; preview = null; error = null
        scope.launch {
            try {
                preview = withContext(Dispatchers.IO) {
                    CsvScheduleImport.parse(bytes, semester.lastWeek ?: 0, periods.map { it.indexNo }.toSet())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = if (e is IllegalArgumentException || e is IllegalStateException)
                    e.message ?: "CSV 格式无效" else "文件无法读取，请确认是 UTF-8 或 GB18030 CSV"
            } finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true; preview = null; error = null
                val bytes = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= CsvScheduleImport.MAX_BYTES) { "文件超过 2 MiB" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("无法读取文件")
                    }
                }.getOrElse {
                    busy = false
                    error = it.message ?: "文件无法读取，请确认是 UTF-8 或 GB18030 CSV"
                    return@launch
                }
                busy = false
                parseBytes(bytes)
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("CSV 导入当前学期") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目标：${semester.displayName}")
                Text("通用格式：课程名称、星期、开始节次、结束节次、周次；教师、教室可选。周次如 1-16(单) 或 1,3,5；节次可写“1-2,3-4”多个时段，也可用单列“节次”或“上课时间”。当前作息支持 1–13 节。UPC 实际导出表头尚需核对，不识别的格式会报错。")
                Text("追加导入，不清空现有课程；完全相同的记录跳过。不同内容可能撞课，请在预览中核对。单条记录表示一个重复时段；同一行的多个时段会拆成多条。")
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        picker.launch(
                            arrayOf(
                                "text/*", "application/csv", "application/vnd.ms-excel",
                                "application/octet-stream",
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("选择 CSV 文件") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)
                            ?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (clip.isNullOrBlank()) {
                            error = "剪贴板没有文本；请先在浏览器导出工具中复制 CSV"
                        } else {
                            parseBytes(clip.toByteArray(Charsets.UTF_8))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从剪贴板导入") }

                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                preview?.let { data ->
                    Text("${data.encoding} · ${data.rows.size} 条有效时段 · ${data.errors.size} 个错误")
                    data.errors.take(30).forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (data.errors.size > 30) Text("仅显示前 30 个错误；请修正文件后重新选择。")
                    data.rows.take(50).forEach { row ->
                        Text("${row.name} · 星期${row.weekday} 第${row.start}–${row.end}节 · ${row.weeks.joinToString(",")}周\n${row.teacher} ${row.room}")
                    }
                    if (data.rows.size > 50) Text("仅展示前 50 条，共 ${data.rows.size} 条将参与导入；确认前请核对源文件。")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && preview?.let { it.errors.isEmpty() && it.rows.isNotEmpty() } == true,
                onClick = {
                    busy = true
                    vm.importCsv(semester.id, preview!!.rows) { failure ->
                        busy = false
                        if (failure == null) onDismiss() else error = failure
                    }
                }) { Text("确认导入") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
