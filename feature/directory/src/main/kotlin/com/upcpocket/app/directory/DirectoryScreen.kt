package com.upcpocket.app.directory

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.CampusOutlinedButton
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.CampusTopAppBar
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassIconButton
import com.upcpocket.app.ui.components.GlassOutlinedTextField

private const val HOME = "home"
private const val COLLEGES = "colleges"
private const val UNIT_PREFIX = "unit/"
private const val OFFICE_PREFIX = "office/"

@Composable
internal fun DirectoryScreen(
    data: DirectoryData,
    initialCampus: DirectoryCampus?,
    onBack: () -> Unit,
    onOpenMap: ((DirectoryMapTarget) -> Unit)?,
) {
    var pages by rememberSaveable { mutableStateOf(arrayListOf(HOME)) }
    var campusName by rememberSaveable { mutableStateOf(initialCampus?.name) }
    var query by rememberSaveable { mutableStateOf("") }
    val campus = DirectoryCampus.entries.firstOrNull { it.name == campusName }
    val page = pages.last()
    val stateHolder = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val transition = updateTransition(page, label = "Directory page")

    fun openPage(fromPage: String, route: String) {
        if (pages.last() != fromPage || route in pages) return
        focus.clearFocus()
        pages = ArrayList(pages + route)
    }

    fun goBack() {
        if (pages.size == 1) {
            onBack()
        } else {
            stateHolder.removeState(pages.last())
            pages = ArrayList(pages.dropLast(1))
        }
    }

    BackHandler(enabled = pages.size > 1, onBack = ::goBack)

    DirectoryScaffold(
        onBack = ::goBack,
        title = {
            transition.AnimatedContent(transitionSpec = { directoryFade() using null }) { route ->
                val destination = remember(data, route) { data.destination(route) }
                DirectoryTitle(
                    destination.title,
                    if (route == page) Modifier else Modifier.clearAndSetSemantics {},
                )
            }
        },
    ) { modifier ->
        transition.AnimatedContent(
            modifier = modifier,
            transitionSpec = { directoryFade() using null },
        ) { route ->
            val destination = remember(data, route) { data.destination(route) }
            val office = destination.office
            val unit = destination.unit
            val contentModifier = if (route == page) Modifier.fillMaxSize() else DirectoryExitingPage
            stateHolder.SaveableStateProvider(route) {
                when {
                    route == HOME -> DirectoryHome(
                        data = data,
                        query = query,
                        onQueryChange = { query = it },
                        campus = campus,
                        onCampusChange = { campusName = it?.name },
                        onColleges = { openPage(route, COLLEGES) },
                        onUnit = { openPage(route, UNIT_PREFIX + it) },
                        onOffice = { openPage(route, OFFICE_PREFIX + it) },
                        modifier = contentModifier.imePadding(),
                    )

                    route == COLLEGES -> DirectoryUnits(
                        units = remember(data, campus) { data.units(campus, colleges = true) },
                        onUnit = { openPage(route, UNIT_PREFIX + it) },
                        modifier = contentModifier,
                    )

                    office != null -> DirectoryOfficeDetails(office, unit?.name, onOpenMap, contentModifier)

                    unit != null -> DirectoryUnitDetails(
                        unit = unit,
                        offices = remember(data, unit.id, campus) { data.offices(unit.id, campus) },
                        onOffice = { openPage(route, OFFICE_PREFIX + it) },
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

private fun directoryFade() =
    fadeIn(tween(durationMillis = 150, delayMillis = 35)) togetherWith fadeOut(tween(durationMillis = 100))

private val DirectoryExitingPage = Modifier.fillMaxSize().clearAndSetSemantics {}.pointerInput(Unit) {
    // AnimatedContent still draws the outgoing page briefly. Its controls should no longer receive taps.
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

private data class DirectoryDestination(val title: String, val unit: DirectoryUnit?, val office: DirectoryOffice?)

private fun DirectoryData.destination(route: String): DirectoryDestination {
    val office = if (route.startsWith(OFFICE_PREFIX)) {
        offices.firstOrNull { it.id == route.removePrefix(OFFICE_PREFIX) }
    } else {
        null
    }
    val unitId = office?.unitId ?: route.removePrefix(UNIT_PREFIX)
    val unit = if (office != null || route.startsWith(UNIT_PREFIX)) {
        units.firstOrNull { it.id == unitId }
    } else {
        null
    }
    val title = when {
        office != null && office.name != unit?.name -> office.name
        unit != null -> unit.name
        route == COLLEGES -> "学院"
        else -> "校园通讯录"
    }
    return DirectoryDestination(title, unit, office)
}

@Composable
internal fun DirectoryLoadingScreen(onBack: () -> Unit, onRetry: (() -> Unit)? = null) {
    DirectoryScaffold(onBack, title = { DirectoryTitle("校园通讯录") }) { modifier ->
        Box(modifier, contentAlignment = Alignment.Center) {
            if (onRetry == null) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("通讯录加载失败")
                    CampusTextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun DirectoryScaffold(
    onBack: () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CampusTopAppBar(
                title = title,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        content(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
    }
}

@Composable
private fun DirectoryTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectoryHome(
    data: DirectoryData,
    query: String,
    onQueryChange: (String) -> Unit,
    campus: DirectoryCampus?,
    onCampusChange: (DirectoryCampus?) -> Unit,
    onColleges: () -> Unit,
    onUnit: (String) -> Unit,
    onOffice: (String) -> Unit,
    modifier: Modifier,
) {
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val browsing = query.isBlank()
    val units = remember(data, campus) { data.units(campus, colleges = false) }
    val results = remember(data, query, campus) {
        if (browsing) emptyList() else data.search(query, campus)
    }

    fun updateQuery(value: String) {
        onQueryChange(value)
        listState.requestScrollToItem(0)
    }

    Column(modifier) {
        GlassOutlinedTextField(
            value = query,
            onValueChange = ::updateQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            label = { Text("搜索单位、事项、楼栋或电话") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    GlassIconButton(onClick = { updateQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清除搜索")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val filters = remember { listOf(null) + DirectoryCampus.entries }
            filters.forEach { filter ->
                CampusFilterChip(
                    selected = campus == filter,
                    onClick = {
                        onCampusChange(filter)
                        listState.requestScrollToItem(0)
                    },
                    label = { Text(filter?.label ?: "全部") },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = DirectoryListPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (browsing) {
                item(COLLEGES) { DirectoryRow("学院", onClick = onColleges) }
                items(units, key = { UNIT_PREFIX + it.id }) { unit ->
                    DirectoryRow(unit.name, onClick = { onUnit(unit.id) })
                }
            } else if (results.isEmpty()) {
                item("empty") {
                    Text("没有找到相关结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(results, key = { result ->
                    result.office?.let { OFFICE_PREFIX + it.id } ?: (UNIT_PREFIX + result.unit.id)
                }) { result ->
                    val office = result.office
                    if (office == null) {
                        DirectoryRow(result.unit.name, onClick = { onUnit(result.unit.id) })
                    } else {
                        val parentName = result.unit.name.takeUnless { it == office.name }.orEmpty()
                        val subtitle = listOf(parentName, officeLocation(office))
                            .filter(String::isNotBlank).joinToString(" · ")
                        DirectoryRow(office.name, subtitle, onClick = { onOffice(office.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryUnits(units: List<DirectoryUnit>, onUnit: (String) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = DirectoryListPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(units, key = { it.id }) { unit ->
            DirectoryRow(unit.name, onClick = { onUnit(unit.id) })
        }
    }
}

@Composable
private fun DirectoryUnitDetails(
    unit: DirectoryUnit,
    offices: List<DirectoryOffice>,
    onOffice: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, contentPadding = DirectoryListPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (unit.introduction.isNotBlank()) {
            item("introduction") {
                DirectoryInfoCard {
                    DirectoryHeading("单位介绍")
                    Text(unit.introduction, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (offices.isNotEmpty()) {
            item("windows") {
                DirectoryHeading("办事窗口", modifier = Modifier.padding(top = 4.dp))
            }
        }
        items(offices, key = { OFFICE_PREFIX + it.id }) { office ->
            DirectoryRow(office.name, officeLocation(office), onClick = { onOffice(office.id) })
        }
    }
}

@Composable
private fun DirectoryOfficeDetails(
    office: DirectoryOffice,
    unitName: String?,
    onOpenMap: ((DirectoryMapTarget) -> Unit)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val sources = remember(office.sources) { office.sources.mapNotNull(::officialSource).distinct() }
    val location = officeLocation(office)
    val mapTarget = office.mapTarget

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(DirectoryListPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (unitName != null && unitName != office.name) {
            Text(
                unitName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (office.duties.isNotBlank() || office.consultations.isNotEmpty()) {
            DirectoryInfoCard {
                if (office.duties.isNotBlank()) {
                    DirectoryHeading("职责")
                    Text(office.duties, style = MaterialTheme.typography.bodyMedium)
                }
                if (office.consultations.isNotEmpty()) {
                    DirectoryHeading("可咨询事务")
                    office.consultations.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        if (office.phones.isNotEmpty() || office.email.isNotBlank()) {
            DirectoryInfoCard {
                if (office.phones.isNotEmpty()) {
                    DirectoryHeading("联系电话")
                    office.phones.forEach { phone ->
                        val label = listOf(phone.label, phone.number).filter(String::isNotBlank).joinToString("  ")
                        val dialable = dialablePhone(phone.number)
                        if (dialable == null) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            CampusOutlinedButton(onClick = { dialPhone(context, phone.number, dialable) }) {
                                Text(label)
                            }
                        }
                    }
                }
                if (office.email.isNotBlank()) {
                    DirectoryHeading("电子邮箱")
                    Text(office.email, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (location.isNotBlank() || (mapTarget != null && onOpenMap != null)) {
            DirectoryInfoCard {
                DirectoryHeading(if (office.address.isBlank() && mapTarget == null) "校区" else "位置")
                if (location.isNotBlank()) Text(location, style = MaterialTheme.typography.bodyMedium)
                if (mapTarget != null && onOpenMap != null) {
                    CampusOutlinedButton(onClick = { onOpenMap(mapTarget) }) { Text("在校园地图查看") }
                }
            }
        }
        if (sources.isNotEmpty()) {
            DirectoryInfoCard {
                DirectoryHeading("资料来源")
                sources.forEachIndexed { index, source ->
                    CampusTextButton(onClick = { openSource(context, source) }) {
                        Text(if (sources.size == 1) "查看官网来源" else "查看官网来源 ${index + 1}")
                    }
                }
                if (office.checkedOn.isNotBlank()) {
                    Text("核对日期 ${office.checkedOn}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DirectoryHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun DirectoryInfoCard(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun DirectoryRow(title: String, subtitle: String = "", onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val DirectoryListPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp)

private fun officeLocation(office: DirectoryOffice): String {
    val campus = office.campus?.label.orEmpty()
    val region = when {
        office.region.isBlank() -> campus
        campus.isBlank() || office.region.contains(campus) -> office.region
        else -> "$campus${office.region}"
    }
    return when {
        office.address.isBlank() -> region
        region.isBlank() || office.address.contains(region) -> office.address
        else -> "$region · ${office.address}"
    }
}

private val DialableNumber = Regex("\\+?[0-9]{4,15}")

private fun dialablePhone(number: String): String? = number.filterNot { it == '-' || it.isWhitespace() }
    .takeIf(DialableNumber::matches)

private fun dialPhone(context: Context, displayNumber: String, number: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    } catch (_: ActivityNotFoundException) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val message = if (clipboard == null) {
            "没有找到可用的拨号器"
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("联系电话", displayNumber))
            "已复制电话号码"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private fun officialSource(value: String): Uri? = Uri.parse(value).takeIf { source ->
    val host = source.host
    source.scheme == "https" && source.userInfo == null &&
        (host == "upc.edu.cn" || host?.endsWith(".upc.edu.cn") == true)
}

private fun openSource(context: Context, source: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, source).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
