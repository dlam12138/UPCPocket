package com.upcpocket.app.ui.official

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.upcpocket.app.data.wechat.WeChatDirect
import com.upcpocket.app.ui.components.CampusAlertDialog
import com.upcpocket.app.ui.components.CampusTextButton

/**
 * 公众号交接的固定展示参数，只含学校公开的公众号名称、菜单路径与固定文案。
 *
 * 不保存深链、小程序参数、会话值或任何学校接口地址。
 */
data class WeChatReservationHandoff(
    val title: String,
    val accountName: String,
    val menuPath: String,
    val instruction: String,
    val infoLabel: String,
)

/**
 * 体育馆与游泳馆预约的官方微信交接。
 *
 * 只保存学校公开的公众号名称、菜单路径和公开规则/介绍地址，不包含公众号深链、
 * 小程序参数、微信 SDK 配置或任何学校接口。实时余位、场次核对、提交与结果都在
 * 微信内完成；本模块不读取场次，也不把“微信已打开”当成预约成功。
 */
object GymReservationHandoff {
    const val OFFICIAL_ACCOUNT_NAME = "中国石油大学体育场馆"
    const val RULES_URL = "https://sports.upc.edu.cn/2023/0606/c7678a405213/page.htm"
    const val POOL_INTRO_URL = "https://news.upc.edu.cn/info/1432/114958.htm"

    val gym = WeChatReservationHandoff(
        title = "体育馆预约",
        accountName = OFFICIAL_ACCOUNT_NAME,
        menuPath = "场地预定 → 场地预定",
        instruction = "进入后可选择项目和日期。",
        infoLabel = "查看预约规则",
    )

    val pool = WeChatReservationHandoff(
        title = "游泳馆预约",
        accountName = OFFICIAL_ACCOUNT_NAME,
        menuPath = "场地预定 → 游泳馆预定",
        instruction = "进入后按微信页面提示选择。",
        infoLabel = "查看游泳馆介绍",
    )

    /**
     * 仅在用户主动点击「复制公众号名并打开微信」后调用。
     *
     * 剪贴板只写入学校公开的公众号名称，不含账号、预约或会话内容。
     */
    fun copyAccountName(
        context: Context,
        accountName: String = OFFICIAL_ACCOUNT_NAME,
    ): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("公众号名称", accountName))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * 固定 [WeChatDirect.PACKAGE_NAME] 的系统启动意图；不解析任意 URI、不转发外部 Intent。
     *
     * Manifest 已通过 `<queries><package android:name="com.tencent.mm" /></queries>`
     * 声明查询范围，这里不再扩大包可见性。
     */
    fun weChatLaunchIntent(packageManager: PackageManager): Intent? {
        return try {
            packageManager.getLaunchIntentForPackage(WeChatDirect.PACKAGE_NAME)?.takeIf { intent ->
                intent.component?.packageName == WeChatDirect.PACKAGE_NAME
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /** 打开已安装的微信；失败只返回固定结果，由交接 UI 提示用户手动处理。 */
    fun openWeChat(context: Context): WeChatHandoffResult {
        val intent = weChatLaunchIntent(context.packageManager) ?: return WeChatHandoffResult.NOT_INSTALLED
        return try {
            context.startActivity(intent)
            WeChatHandoffResult.OPENED
        } catch (_: ActivityNotFoundException) {
            WeChatHandoffResult.FAILED
        } catch (_: SecurityException) {
            WeChatHandoffResult.FAILED
        }
    }
}

enum class WeChatHandoffResult {
    OPENED,
    NOT_INSTALLED,
    FAILED,
}

private data class HandoffFeedback(val message: String, val isError: Boolean)

/**
 * 同一份交接说明供「服务」分类目录、校园官方网页和搜索结果共用。
 *
 * 标题、公众号、菜单路径与公开页面按钮均由条目携带；对话框保持可操作：
 * 微信缺失或启动失败时留在原处，仍可打开学校公开的预约规则或游泳馆介绍。
 */
@Composable
fun WeChatReservationHandoffDialog(
    link: OfficialLink,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val handoff = link.weChatHandoff ?: return
    var feedback by remember(link) { mutableStateOf<HandoffFeedback?>(null) }

    CampusAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(handoff.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "官方公众号",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = handoff.accountName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "菜单路径：${handoff.menuPath}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "在微信搜索『${handoff.accountName}』，${handoff.instruction}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                feedback?.let { state ->
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                CampusTextButton(onClick = { OfficialWebCatalog.open(context, link) }) {
                    Text(handoff.infoLabel)
                }
            }
        },
        confirmButton = {
            CampusTextButton(
                onClick = {
                    val copied = GymReservationHandoff.copyAccountName(context, handoff.accountName)
                    feedback = when (GymReservationHandoff.openWeChat(context)) {
                        WeChatHandoffResult.OPENED -> HandoffFeedback(
                            message = if (copied) {
                                "已复制公众号名并打开微信。搜索「${handoff.accountName}」，依次点开「${handoff.menuPath}」。"
                            } else {
                                "已打开微信，但未能复制公众号名，请手动搜索「${handoff.accountName}」。"
                            },
                            isError = false,
                        )
                        WeChatHandoffResult.NOT_INSTALLED -> HandoffFeedback(
                            message = if (copied) {
                                "已复制公众号名，但未找到可用的微信。安装或启用微信后重试，仍可${handoff.infoLabel}。"
                            } else {
                                "未找到可用的微信。安装或启用微信后重试，仍可${handoff.infoLabel}。"
                            },
                            isError = true,
                        )
                        WeChatHandoffResult.FAILED -> HandoffFeedback(
                            message = if (copied) {
                                "已复制公众号名，但未能打开微信。请手动打开微信搜索该公众号。"
                            } else {
                                "未能复制公众号名，也未能打开微信。请手动打开微信搜索「${handoff.accountName}」。"
                            },
                            isError = true,
                        )
                    }
                },
            ) {
                Text("复制公众号名并打开微信")
            }
        },
        dismissButton = {
            CampusTextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
