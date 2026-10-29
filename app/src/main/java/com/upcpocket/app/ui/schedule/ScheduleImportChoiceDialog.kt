package com.upcpocket.app.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * “导入”入口：先选同步方式，再进入具体流程。
 *
 * 把“登录并同步学校课表”放在第一位，是因为用户不该先装脚本、先到电脑下载 CSV；
 * CSV/剪贴板继续保留为备用路径，并如实说明 UPC 实际 CSV 表头尚未验证。
 */
@Composable
fun ScheduleImportChoiceDialog(
    onDismiss: () -> Unit,
    onSchoolSync: () -> Unit,
    onJwxtSync: () -> Unit,
    onCsv: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 / 同步课表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "推荐：在 App 内登录教务系统，自己打开「学期理论课表」，再点「读取当前页」。" +
                        "App 只读当前显示的表格，读取后先预览，确认才写入。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onJwxtSync, modifier = Modifier.fillMaxWidth()) {
                    Text("登录教务系统并读取当前课表")
                }
                Text(
                    "另一个入口：校园 App 的课表页（app.upc.edu.cn）。两个入口写入同一个学期，" +
                        "重复同步只替换学校来源的课程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSchoolSync, modifier = Modifier.fillMaxWidth()) {
                    Text("从校园 App 课表页同步")
                }
                Text(
                    "备用：CSV 文件 / 剪贴板（也支持学校课表页的导出脚本）。" +
                        "UPC 实际导出的表头尚未用真实账号核对，格式不符会明确报错。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCsv, modifier = Modifier.fillMaxWidth()) {
                    Text("从 CSV / 剪贴板导入")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
