package com.upcpocket.app.ui.power

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.data.power.AllPowerCampusPreferenceProvider
import com.upcpocket.app.data.power.PowerCampus
import com.upcpocket.app.data.power.PowerCampusEntries
import com.upcpocket.app.data.power.PowerCampusPreference
import com.upcpocket.app.data.power.PowerCampusPreferenceProvider
import com.upcpocket.app.power.PowerService
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.GlassCard

/**
 * 「电费」页。
 *
 * 常用校区为“全部校区”时保留原来的两个入口：唐岛湾走 App 内原生查询，古镇口仍打开
 * 学校自己的页面（域名证书已过期，不绕过 TLS）。常用校区为单一校区时直接展示该校区
 * 内容，并可在页面内临时切换；古镇口内容不会挂载原生表单，也不会加载唐岛湾楼号。
 *
 * 两个校区不是一个系统，也不把古镇口说成已原生接入。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ElectricityScreen(
    service: PowerService,
    onBack: () -> Unit,
    onOpenTangdaowan: () -> Unit,
    preferredCampus: PowerCampusPreferenceProvider = AllPowerCampusPreferenceProvider,
) {
    val context = LocalContext.current
    val pageScope = rememberCoroutineScope()
    val campusPreferenceFlow = remember(preferredCampus) { preferredCampus.preferredCampus() }
    val campusPreference by campusPreferenceFlow
        .collectAsStateWithLifecycle(initialValue = PowerCampusPreference.Unread)

    var campusOverrideName by rememberSaveable { mutableStateOf<String?>(null) }
    val campusOverride = campusOverrideName?.let { name ->
        PowerCampus.entries.firstOrNull { it.name == name }
    }
    val lockedCampus = (campusPreference as? PowerCampusPreference.Selected)?.campus
    val effectiveCampus = campusOverride ?: lockedCampus

    fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
                title = { Text("电费") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                campusPreference is PowerCampusPreference.Unread -> CampusPreferenceLoading()

                effectiveCampus == null -> {
                    TangdaowanEntryCard(onOpenTangdaowan)
                    GuzhenkouCard(
                        onOpenSchoolPage = { openInBrowser(PowerCampusEntries.GUZHENKOU_URL) },
                    )
                }

                effectiveCampus == PowerCampus.TANGDAOWAN -> {
                    CampusSwitchRow(effectiveCampus) { campusOverrideName = it.name }
                    TangdaowanElectricityContent(service, persistenceScope = pageScope)
                }

                else -> {
                    CampusSwitchRow(effectiveCampus) { campusOverrideName = it.name }
                    GuzhenkouCard(
                        onOpenSchoolPage = { openInBrowser(PowerCampusEntries.GUZHENKOU_URL) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TangdaowanEntryCard(onOpenTangdaowan: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("唐岛湾校区", style = MaterialTheme.typography.titleMedium)
            Text(
                "楼号与房间号 · 无需登录",
                style = MaterialTheme.typography.bodySmall,
            )
            com.upcpocket.app.ui.components.CampusButton(
                onClick = onOpenTangdaowan,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("查剩余电量") }
        }
    }
}

@Composable
private fun GuzhenkouCard(onOpenSchoolPage: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("古镇口校区", style = MaterialTheme.typography.titleMedium)
            Text(
                "使用学校页面查询",
                style = MaterialTheme.typography.bodySmall,
            )
            com.upcpocket.app.ui.components.CampusOutlinedButton(
                onClick = onOpenSchoolPage,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("打开学校页面") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampusSwitchRow(
    selected: PowerCampus,
    onSelect: (PowerCampus) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PowerCampus.entries.forEach { campus ->
            CampusFilterChip(
                selected = selected == campus,
                onClick = { if (selected != campus) onSelect(campus) },
                label = { Text(campus.label) },
            )
        }
    }
}

@Composable
private fun CampusPreferenceLoading() {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                "正在读取常用校区…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
