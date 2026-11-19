/*
 * 悬浮胶囊 dock 的视觉/覆盖层结构移植自 ZZUPocket（3beuml/ZZUPocket），
 * AppNav.kt at commit dda318e9b37d8b65991a965e72fd4dd40b630a20。
 * Copyright (c) 2026 3beuml, MIT License；完整许可见仓库根 LICENSE。
 * 本项目只保留首页、课表、服务、待办、专注五个一级入口，并补足 Pager、主题/无障碍与页面安全余量适配。
 */
package com.upcpocket.app.ui.nav

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.upcpocket.app.account.SchoolAccountScreen
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.LocalPluginPageActive
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginPageHost
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.power.POWER_TANGDAOWAN_ROUTE
import com.upcpocket.app.timetable.TIMETABLE_ROUTE
import com.upcpocket.app.todo.TODO_ROUTE
import com.upcpocket.app.ui.about.AboutScreen
import com.upcpocket.app.ui.components.glassBorder
import com.upcpocket.app.ui.components.glassHighlight
import com.upcpocket.app.ui.components.isGlassSheetVisible
import com.upcpocket.app.ui.home.HomeScreen
import com.upcpocket.app.ui.nav.kyant.LiquidBottomTab
import com.upcpocket.app.ui.nav.kyant.LiquidBottomTabs
import com.upcpocket.app.ui.official.OfficialWebScreen
import com.upcpocket.app.ui.services.ServicesScreen
import com.upcpocket.app.ui.settings.AppearanceScreen
import com.upcpocket.app.ui.settings.SettingsScreen
import com.upcpocket.app.ui.settings.WidgetSettingsScreen
import com.upcpocket.app.ui.theme.LocalUiStyle
import com.upcpocket.app.widget.WidgetLaunchTarget
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val outlinedIcon: ImageVector,
    val order: Int,
    val page: PluginPage? = null,
)

private const val MAIN_ROUTE = "main"

/**
 * 上游 ZZUPocket AppNav 的悬浮 dock 为滚动内容预留的底座余量（56 + 上下 8 + 16）。
 * 这里再叠加系统 navigationBars inset，由宿主统一注入一级页。
 */
private val DOCK_CLEARANCE = 88.dp

private val hostHome = Destination(
    route = AppRoutes.HOME,
    label = "首页",
    icon = Icons.Filled.Home,
    outlinedIcon = Icons.Outlined.Home,
    order = 0,
)

private val hostServices = Destination(
    route = AppRoutes.SERVICES,
    label = "服务",
    icon = Icons.Filled.Apps,
    outlinedIcon = Icons.Outlined.Apps,
    order = 6,
)

/**
 * 宿主固定首页，课表/待办/专注 tab 来自已激活插件。
 *
 * 五个一级页由同一个 [HorizontalPager] 承载，tab 入口不再压入 NavController；设置、
 * 账号、WebView 和插件二级页仍走 NavHost。这样整页拖动、胶囊连续动画和通知/组件
 * 深链只共享一个页面索引事实源。
 */
@Composable
fun AppNav(
    launchTarget: WidgetLaunchTarget? = null,
    onLaunchConsumed: () -> Unit = {},
    openTodo: Boolean = false,
    onTodoConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onMain = currentRoute == MAIN_ROUTE
    val pluginUi = AppGraph.ui
    val accountState by AppGraph.account.state.collectAsStateWithLifecycle()
    val legacyCardStudentId by AppGraph.cardInputRepository.studentId
        .collectAsStateWithLifecycle(initialValue = "")
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())

    val pluginDestinations = pluginUi.bottomNav.map { tab ->
        Destination(
            route = tab.route.value,
            label = tab.label,
            icon = tab.icon.filledIcon(),
            outlinedIcon = tab.icon.outlinedIcon(),
            order = tab.order,
            page = pluginUi.page(tab.route),
        )
    }.sortedWith(compareBy({ it.order }, { it.route }))
    // 首页由宿主固定在最左，服务广场排在课表之后；插件 tab 按各自 order 排序合并。
    val destinations = listOf(hostHome) +
        (pluginDestinations + hostServices).sortedWith(compareBy({ it.order }, { it.route }))
    val allServiceEntries = remember(pluginUi) {
        pluginUi.entriesIn(UiSurface.HOME_SERVICES, UiSurface.SERVICE_DIRECTORY)
    }
    val destinationRoutes = remember(destinations) { destinations.map { it.route } }
    val tabRoutes = remember(destinationRoutes) { destinationRoutes.toSet() }

    val initialRoute = when {
        openTodo -> TODO_ROUTE.value
        launchTarget == WidgetLaunchTarget.TODO -> TODO_ROUTE.value
        launchTarget == WidgetLaunchTarget.TIMETABLE -> TIMETABLE_ROUTE.value
        else -> AppRoutes.HOME
    }
    val initialPage = pageIndexForRoute(destinationRoutes, initialRoute)
        .takeIf { it >= 0 }
        ?: pageIndexForRoute(destinationRoutes, AppRoutes.HOME).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { destinations.size },
    )
    val scope = rememberCoroutineScope()
    val tabSwitch = remember { arrayOfNulls<Job>(1) }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val dockVisible = onMain && !imeVisible
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dockClearance = if (dockVisible) DOCK_CLEARANCE + navigationBottom else 0.dp

    /**
     * 五个一级页只由一个 PagerState 表示；不把 tab 压进 NavController。
     *
     * 点击/组件/通知/返回都走这里。二级页存在时先回 main，再平滑切页；重复调用会
     * 取消上一次动画，不会叠加返回栈。
     */
    fun switchTab(
        route: String,
        animationSpec: AnimationSpec<Float> = tween(durationMillis = 320, easing = FastOutSlowInEasing),
    ) {
        val index = pageIndexForRoute(destinationRoutes, route)
        if (index < 0) return
        if (navController.currentDestination?.route != MAIN_ROUTE) {
            navController.popBackStack(MAIN_ROUTE, inclusive = false)
        }
        tabSwitch[0]?.cancel()
        tabSwitch[0] = scope.launch {
            pagerState.animateScrollToPage(
                page = index,
                animationSpec = animationSpec,
            )
        }
    }

    fun openPluginRoute(route: PluginRouteId) {
        if (route.value in tabRoutes) {
            switchTab(route.value)
        } else {
            navController.navigate(route.value) { launchSingleTop = true }
        }
    }

    val pageHost = remember(navController, pagerState, tabRoutes) {
        object : PluginPageHost {
            override fun open(route: PluginRouteId) = openPluginRoute(route)

            override fun back() {
                navController.popBackStack()
            }

            override fun openSchoolAccountSettings() {
                navController.navigate(AppRoutes.ACCOUNT) { launchSingleTop = true }
            }
        }
    }

    val sectionHost = remember(navController, pagerState, tabRoutes) {
        object : PluginSectionHost {
            override fun openSchoolAccountSettings() {
                navController.navigate(AppRoutes.ACCOUNT) { launchSingleTop = true }
            }

            override fun open(route: PluginRouteId) = openPluginRoute(route)
        }
    }

    fun openSettings() {
        navController.navigate(AppRoutes.SETTINGS) { launchSingleTop = true }
    }

    // 通知点击进入待办页；具体条目由 TodoService.pendingOpenTask 交给页面。
    LaunchedEffect(openTodo) {
        if (!openTodo) return@LaunchedEffect
        switchTab(TODO_ROUTE.value)
        onTodoConsumed()
    }

    // 桌面组件的固定入口：校园卡回首页，电费直接进唐岛湾原生查询页，课表切到课表页。
    // 只在消费一次后清空，避免重组时反复导航；不接受任意路由或 Intent 转发。
    LaunchedEffect(launchTarget) {
        when (launchTarget) {
            WidgetLaunchTarget.CARD_BALANCE -> {
                switchTab(AppRoutes.HOME)
            }
            WidgetLaunchTarget.POWER_TANGDAOWAN -> {
                val powerPage = pluginUi.page(POWER_TANGDAOWAN_ROUTE)
                if (powerPage != null) {
                    navController.navigate(powerPage.routeId.value) { launchSingleTop = true }
                } else {
                    // 电量插件未激活时，旧组件仍可能带着这个入口；安全回到首页，
                    // 不导航到未注册路由，也不强行启动插件。
                    switchTab(AppRoutes.HOME)
                }
            }
            WidgetLaunchTarget.TIMETABLE -> {
                switchTab(TIMETABLE_ROUTE.value)
            }
            WidgetLaunchTarget.TODO -> {
                if (pluginUi.page(TODO_ROUTE) != null) {
                    switchTab(TODO_ROUTE.value)
                } else {
                    switchTab(AppRoutes.HOME)
                }
            }
            null -> return@LaunchedEffect
        }
        onLaunchConsumed()
    }

    val homePageIndex = pageIndexForRoute(destinationRoutes, AppRoutes.HOME).coerceAtLeast(0)
    val awayFromHome by remember(pagerState, homePageIndex) {
        derivedStateOf {
            pagerState.currentPage != homePageIndex || pagerState.currentPageOffsetFraction != 0f
        }
    }
    BackHandler(enabled = onMain && awayFromHome) {
        tabSwitch[0]?.cancel()
        tabSwitch[0] = scope.launch {
            pagerState.animateScrollToPage(
                page = homePageIndex,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            )
        }
    }

    val dockDark = when (prefs.darkMode) {
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
        DarkMode.SYSTEM -> isSystemInDarkTheme()
    }
    val glass = LocalUiStyle.current == UiStyle.LIQUID_GLASS
    val drawDock = dockVisible && !isGlassSheetVisible()
    val dockBackdrop = if (drawDock && glass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLayerBackdrop()
    } else null
    val surface = MaterialTheme.colorScheme.surface

    CompositionLocalProvider(LocalDockClearance provides dockClearance) {
        Box(
            Modifier
                .fillMaxSize()
                .background(surface),
        ) {
            NavHost(
                navController = navController,
                startDestination = MAIN_ROUTE,
                modifier = Modifier.fillMaxSize().then(
                    if (dockBackdrop != null) Modifier.layerBackdrop(dockBackdrop).background(surface)
                    else Modifier,
                ),
            ) {
                composable(MAIN_ROUTE) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // 五页全部保留状态；活动页由 LocalPluginPageActive 控制自动查询/
                        // 权限/通知消费，预加载不会触发学校登录或刷新。
                        beyondViewportPageCount = destinations.lastIndex.coerceAtLeast(0),
                        userScrollEnabled = !imeVisible,
                        key = { destinations[it].route },
                    ) { page ->
                        val active = pagerState.currentPage == page &&
                            !pagerState.isScrollInProgress
                        val pageModifier = if (active) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.fillMaxSize().clearAndSetSemantics { }
                        }
                        CompositionLocalProvider(LocalPluginPageActive provides active) {
                            Box(pageModifier) {
                                when (destinations.getOrNull(page)?.route) {
                                    AppRoutes.HOME -> HomeScreen(
                                        serviceEntries = allServiceEntries,
                                        timetableSections = pluginUi.sectionsFor(UiSurface.HOME_TIMETABLE),
                                        cardSections = pluginUi.sectionsFor(UiSurface.HOME_CARD),
                                        todoSections = pluginUi.sectionsFor(UiSurface.HOME_TODO),
                                        sectionHost = sectionHost,
                                        onOpenRoute = pageHost::open,
                                        onOpenSettings = ::openSettings,
                                        onOpenOfficialWeb = {
                                            navController.navigate(AppRoutes.OFFICIAL_WEB)
                                        },
                                        pinnedServiceIds = prefs.pinnedServiceIds,
                                        onOpenServices = { switchTab(AppRoutes.SERVICES) },
                                    )
                                    AppRoutes.SERVICES -> ServicesScreen(
                                        entries = allServiceEntries,
                                        pinnedServiceIds = prefs.pinnedServiceIds,
                                        onTogglePin = { entryId ->
                                            scope.launch { AppGraph.uiPrefsStore.togglePinnedService(entryId) }
                                        },
                                        onOpenRoute = pageHost::open,
                                        onOpenOfficialWeb = {
                                            navController.navigate(AppRoutes.OFFICIAL_WEB)
                                        },
                                    )
                                    else -> destinations.getOrNull(page)?.page?.content(pageHost)
                                }
                            }
                        }
                    }
                }
                pluginUi.pages
                    .filterNot { it.routeId.value in tabRoutes }
                    .forEach { page ->
                        composable(page.routeId.value) {
                            page.content(pageHost)
                        }
                    }
                composable(AppRoutes.SETTINGS) {
                    SettingsScreen(
                        accountState = accountState,
                        schoolSections = pluginUi.sectionsFor(
                            UiSurface.MORE_SCHOOL,
                        ),
                        sectionHost = sectionHost,
                        onBack = { navController.popBackStack() },
                        onOpenAccount = { navController.navigate(AppRoutes.ACCOUNT) },
                        onOpenAppearance = { navController.navigate(AppRoutes.APPEARANCE) },
                        onOpenWidgets = { navController.navigate(AppRoutes.WIDGETS) },
                        onOpenOfficialWeb = { navController.navigate(AppRoutes.OFFICIAL_WEB) },
                        onOpenAbout = { navController.navigate(AppRoutes.ABOUT) },
                    )
                }
                composable(AppRoutes.ACCOUNT) {
                    SchoolAccountScreen(
                        accounts = AppGraph.account,
                        onBack = { navController.popBackStack() },
                        legacyStudentIdHint = legacyCardStudentId.takeIf { it.isNotBlank() },
                    )
                }
                composable(AppRoutes.APPEARANCE) {
                    AppearanceScreen(onBack = { navController.popBackStack() })
                }
                composable(AppRoutes.WIDGETS) {
                    WidgetSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(AppRoutes.OFFICIAL_WEB) {
                    OfficialWebScreen(onBack = { navController.popBackStack() })
                }
                composable(AppRoutes.ABOUT) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
            }

            if (drawDock) {
                val dockAnimation: AnimationSpec<Float> = remember(glass) {
                    if (glass) {
                        spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMedium)
                    } else {
                        tween(durationMillis = 320, easing = FastOutSlowInEasing)
                    }
                }
                val selectTab: (Int) -> Unit = { index ->
                    destinations.getOrNull(index)?.let { switchTab(it.route, dockAnimation) }
                }
                if (dockBackdrop != null) {
                    KyantDock(
                        destinations = destinations,
                        pagerState = pagerState,
                        backdrop = dockBackdrop,
                        dockDark = dockDark,
                        onSelect = selectTab,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                } else {
                    FloatingDock(
                        destinations = destinations,
                        pagerState = pagerState,
                        dockDark = dockDark,
                        selectedIndex = pagerState.targetPage,
                        onSelect = selectTab,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun KyantDock(
    destinations: List<Destination>,
    pagerState: PagerState,
    backdrop: Backdrop,
    dockDark: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetPage = remember(pagerState) { { pagerState.targetPage } }
    val selectTab: (Int) -> Unit = { index ->
        if (index != pagerState.targetPage) onSelect(index)
    }
    LiquidBottomTabs(
        selectedIndex = targetPage,
        onTabSelected = selectTab,
        backdrop = backdrop,
        tabsCount = destinations.size,
        darkTheme = dockDark,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("floating_dock"),
    ) {
        destinations.forEachIndexed { index, destination ->
            val selected = pagerState.targetPage == index
            LiquidBottomTab(
                selected = selected,
                onClick = { selectTab(index) },
                modifier = Modifier
                    .semantics { contentDescription = destination.label }
                    .testTag("tab_${destination.route}"),
            ) {
                Icon(
                    imageVector = if (selected) destination.icon else destination.outlinedIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
    }
}

internal fun pageIndexForRoute(routes: List<String>, route: String): Int = routes.indexOf(route)

/**
 * 五项悬浮胶囊 dock（基于 3beuml/ZZUPocket MIT AppNav 的视觉/覆盖结构）。
 *
 * 外层只吞 tap，不吃 move：胶囊四周留白不会穿透到下面的校园卡按钮，页面横向
 * Pager 仍可从胶囊外起手。选中泡随 PagerState 的连续位置滑动，图标颜色/填充
 * 按滑动进度交叉过渡；点击只切 Pager，不新增 NavController 栈。
 */
@Composable
private fun FloatingDock(
    destinations: List<Destination>,
    pagerState: PagerState,
    dockDark: Boolean,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (destinations.isEmpty()) return
    val glass = LocalUiStyle.current == UiStyle.LIQUID_GLASS
    val scheme = MaterialTheme.colorScheme
    val dockShape = RoundedCornerShape(28.dp)
    val dockColor = when {
        glass -> Color.White.copy(alpha = if (dockDark) 0.05f else 0.15f)
        dockDark -> scheme.surface
        else -> scheme.surface.copy(alpha = 0.5f)
    }
    val dockElevation = when {
        glass -> 0.dp
        dockDark -> 1.dp
        else -> 6.dp
    }
    val selectedBubble = when {
        glass -> scheme.primary.copy(alpha = 0.16f)
        dockDark -> scheme.primaryContainer
        else -> scheme.secondaryContainer.copy(alpha = 0.85f)
    }
    val selectedTint = if (dockDark && !glass) scheme.onPrimaryContainer else scheme.primary
    val unselectedTint = scheme.onSurfaceVariant
    val liquidIndicator = if (glass) {
        Modifier.liquidDockIndicator(pagerState, destinations.size, selectedBubble, glassBorder())
    } else Modifier
    val tabIndication = if (glass) null else LocalIndication.current
    val lastIndex = destinations.lastIndex

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                // 上游 issue #12 的修复：只消费胶囊带子上的 tap，不消费 move，
                // 因此留白不会点穿校园卡，页面拖动也不会被 dock 拦住。
                detectTapGestures { }
            },
    ) {
        Surface(
            shape = dockShape,
            color = dockColor,
            tonalElevation = dockElevation,
            shadowElevation = 0.dp,
            border = if (glass) glassBorder(1.2.dp) else BorderStroke(
                0.5.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.25f),
                        scheme.outlineVariant.copy(alpha = 0.6f),
                        scheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                ),
            ),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(56.dp)
                .shadow(
                    elevation = dockElevation,
                    shape = dockShape,
                    clip = false,
                )
                .testTag("floating_dock"),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().glassHighlight(dockShape)) {
                val slotWidth = maxWidth / destinations.size
                if (!glass) {
                    val bubbleSize = 40.dp
                    Box(
                        Modifier
                            .offset {
                                val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                                    .coerceIn(0f, lastIndex.toFloat())
                                val bubbleX = slotWidth * position + (slotWidth - bubbleSize) / 2
                                IntOffset(bubbleX.roundToPx(), 8.dp.roundToPx())
                            }
                            .size(bubbleSize)
                            .background(selectedBubble, CircleShape),
                    )
                }
                Row(
                    Modifier.fillMaxSize().then(liquidIndicator).selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    destinations.forEachIndexed { index, destination ->
                        val focusIndicator = if (glass) {
                            val focused = remember { mutableStateOf(false) }
                            Modifier.onFocusChanged { focused.value = it.isFocused }
                                .then(
                                    if (focused.value) Modifier.border(1.dp, scheme.primary, CircleShape)
                                    else Modifier,
                                )
                        } else Modifier
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(focusIndicator)
                                .selectable(
                                    selected = index == selectedIndex,
                                    role = Role.Tab,
                                    interactionSource = null,
                                    indication = tabIndication,
                                    onClick = { onSelect(index) },
                                )
                                .semantics {
                                    contentDescription = destination.label
                                }
                                .testTag("tab_${destination.route}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            DockTabIcon(destination, pagerState, index, lastIndex, unselectedTint, selectedTint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTabIcon(
    destination: Destination,
    pagerState: PagerState,
    index: Int,
    lastIndex: Int,
    unselectedTint: Color,
    selectedTint: Color,
) {
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, lastIndex.toFloat())
    val selectedAmount = (1f - abs(position - index)).coerceIn(0f, 1f)
    val tint = lerp(unselectedTint, selectedTint, selectedAmount)

    Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = destination.outlinedIcon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(23.dp).graphicsLayer { alpha = 1f - selectedAmount },
        )
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(23.dp).graphicsLayer { alpha = selectedAmount },
        )
    }
}
