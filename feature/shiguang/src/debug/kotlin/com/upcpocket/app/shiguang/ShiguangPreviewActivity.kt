package com.upcpocket.app.shiguang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.upcpocket.app.account.api.*
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.ui.theme.UpcPocketTheme
import com.upcpocket.app.upcauth.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Debug-only synthetic data: no account repository, HTTP client, credentials, or school request. */
class ShiguangPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val noAccount = intent.getBooleanExtra("noAccount", false)
        val verified = !intent.getBooleanExtra("unverified", false)
        val scenario = intent.getStringExtra("scenario").orEmpty()
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val date = DateTimeFormatter.ISO_LOCAL_DATE
        val ended = "${today.minusDays(1).format(date)} 14:00–16:00"
        val future = "${today.plusDays(3).format(date)} 09:00–11:00"
        val account = object : SchoolAccountReferenceReader {
            override val state = MutableStateFlow(SchoolAccountState(
                if (noAccount) SchoolAccountStatus.NOT_CONFIGURED else SchoolAccountStatus.SAVED_UNVERIFIED,
                AccountGeneration(if (noAccount) 0 else 1)))
            override val accountReference = MutableStateFlow<SchoolAccountReference?>(if (noAccount) null else
                SchoolAccountReference(SchoolAccountId("synthetic-preview"), AccountGeneration(1)))
        }
        val authenticator = object : ShiguangAuthenticator {
            override suspend fun authenticate(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long) =
                UpcShiguangAuthResult.Success(emptyList(), verified)
        }
        val api = object : ShiguangDataSource {
            override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage {
                if (scenario == "error") throw ShiguangException(ShiguangFailure.NETWORK)
                val records = listOf(
                    ShiguangActivity("preview-ended", ShiguangKind.ACTIVITY, "已结束的校园志愿服务活动", linkedMapOf(
                        "分类" to "志愿服务", "活动时间" to ended, "地点" to "合成场地 A", "学时" to "0", "学分" to "0")),
                    ShiguangActivity("preview-future", ShiguangKind.ACTIVITY,
                        "长标题预览：面向全校学生的校园文化交流与科学体验活动",
                        linkedMapOf("分类" to "校园文化", "活动时间" to future, "地点" to "合成场地 B", "学时" to "2", "学分" to "0.2")),
                    ShiguangActivity("preview-unknown", ShiguangKind.ACTIVITY, "活动时间未确定的合成活动",
                        linkedMapOf("报名截止时间" to "${today.plusDays(2).format(date)} 18:00", "地点" to "待通知")),
                    ShiguangActivity("preview-practice", ShiguangKind.SOCIAL_PRACTICE,
                        "社会实践：走进社区开展环境观察和志愿服务",
                        linkedMapOf("活动时间" to "${today.plusDays(5).format(date)} 08:30 至 12:00", "地点" to "合成社区", "学时" to "未知")),
                )
                return ShiguangPage(page, 3, records.map { item ->
                    if (mine) item.copy(title = "参与记录 · ${item.title}") else item
                })
            }
            override suspend fun detail(session: ShiguangSession, item: ShiguangActivity) = ShiguangDetail(item.title,
                "这是用于验证原生布局、长文字和返回位置的合成介绍。活动介绍随字体大小和窗口宽度调整，不访问网络。\n\n活动地点与时间只用于界面预览。",
                linkedMapOf("归属组织" to "合成组织", "活动院系" to "全校", "活动地点" to "合成场地", "活动时间" to future, "报名时间" to "合成时间段", "学时" to "2", "学分" to "0.2"))
            override suspend fun summary(session: ShiguangSession) = ShiguangSummary(
                listOf("思想成长", "创新创业", "文体发展", "社会实践与志愿服务", "工作履历与技能培训").mapIndexed { index, label ->
                    ShiguangModule(index + 1, label,
                        when (index) { 0 -> BigDecimal.ZERO; 4 -> null; else -> BigDecimal(12 + index * 6) },
                        if (index == 2) null else BigDecimal("0.${index + 1}"), null)
                })
        }
        val service = ShiguangService(account, authenticator, api, lifecycleScope)
        setContent {
            UpcPocketTheme(darkMode = if (intent.getBooleanExtra("dark", false)) DarkMode.DARK else DarkMode.LIGHT, preset = ThemePreset.BLUE) {
                ShiguangScreen(service, ::finish) { }
            }
        }
    }
}
