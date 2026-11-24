/*
 * 悬浮胶囊 dock 的视觉/覆盖层结构移植自 ZZUPocket（3beuml/ZZUPocket），
 * AppNav.kt at commit dda318e9b37d8b65991a965e72fd4dd40b630a20。
 * Copyright (c) 2026 3beuml, MIT License；完整许可见仓库根 LICENSE。
 * 本项目只保留首页、课表（或专注，含本机日程待办）、服务、专注四个一级入口，
 * 并补足 Pager、主题/无障碍与页面安全余量适配。
 */
package com.upcpocket.app.ui.nav

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.Backdrop
import com.upcpocket.app.account.SchoolAccountScreen
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.HomeModuleId
import com.upcpocket.app.data.prefs.TodoHostPlacement
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.focus.FOCUS_MAIN_ROUTE
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.LocalPluginPageActive
import com.upcpocket.app.plugin.ui.PluginBottomNav
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginPageHost
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.power.POWER_TANGDAOWAN_ROUTE
import com.upcpocket.app.timetable.LocalTimetableModuleLayout
import com.upcpocket.app.timetable.LocalTimetableTodoSlot
import com.upcpocket.app.timetable.TIMETABLE_ROUTE
import com.upcpocket.app.timetable.TimetableModuleId
import com.upcpocket.app.timetable.TimetableModuleLayout
import com.upcpocket.app.todo.TODO_ROUTE
import com.upcpocket.app.ui.todo.LocalTodoListBack
import com.upcpocket.app.ui.todo.TodoScheduleSection
import com.upcpocket.app.ui.about.AboutScreen
import com.upcpocket.app.ui.components.glassBorder
import com.upcpocket.app.ui.components.glassHighlight
import com.upcpocket.app.ui.components.glassThemeBackdrop
import com.upcpocket.app.ui.components.isGlassSheetVisible
import com.upcpocket.app.ui.focus.LocalFocusTodoSlot
import com.upcpocket.app.ui.home.HomeScreen
import com.upcpocket.app.ui.learning.LearningPlatformsScreen
import com.upcpocket.app.ui.nav.kyant.LiquidBottomTab
import com.upcpocket.app.ui.nav.kyant.LiquidBottomTabs
import com.upcpocket.app.ui.official.OfficialWebScreen
import com.upcpocket.app.ui.services.ServicesScreen
import com.upcpocket.app.ui.settings.AppearanceScreen
import com.upcpocket.app.ui.settings.SettingsScreen
import com.upcpocket.app.ui.settings.WidgetSettingsScreen
import com.upcpocket.app.ui.theme.LocalUiStyle
import com.upcpocket.app.ui.theme.appScaffoldContainerColor
import com.upcpocket.app.widget.WidgetLaunchTarget
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val outlinedIcon: ImageVector,
    val order: Int,
    val page: PluginPage? = null,
)

internal const val MAIN_ROUTE = "main"

private const val MAIN_LAYER_ENTER_MS = 200
private const val MAIN_LAYER_EXIT_MS = 140
private const val SECONDARY_ENTER_MS = 220
private const val SECONDARY_EXIT_MS = 140
private const val SECONDARY_POP_ENTER_MS = 200
private const val SECONDARY_POP_EXIT_MS = 180

/**
 * 上游 ZZUPocket AppNav 的悬浮 dock 为滚动内容预留的底座余量（56 + 上下 8 + 16）。
 * 这里再叠加系统 navigationBars inset，由宿主统一注入一级页。
 */
internal val DOCK_CLEARANCE = 88.dp

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
 * 宿主固定首页，课表/专注 tab 来自已激活插件；待办存在时按本机偏好合入
 * 课表页顶部或专注页计时控制与标签之间，默认课表。
 *
 * 四个一级页由同一个 [HorizontalPager] 承载，tab 入口不再压入 NavController；设置、
 * 账号、WebView、完整待办列表和其他插件二级页仍走 NavHost。这样整页拖动、胶囊连续
 * 动画和通知/组件深链只共享一个页面索引事实源。
 */
@Composable
fun AppNav(
    launchTarget: WidgetLaunchTarget? = null,
    onLaunchConsumed: () -> Unit = {},
    openTodo: Boolean = false,
    onTodoConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntryState = navController.currentBackStackEntryAsState()
    val onMainState = remember(backStackEntryState) {
        derivedStateOf {
            val route = backStackEntryState.value?.destination?.route
            route == null || route == MAIN_ROUTE
        }
    }
    val pluginUi = AppGraph.ui
    val todoStore = AppGraph.todoStoreOrNull
    val storedPrefs: UiPrefs? by AppGraph.uiPrefsStore.flow
        .collectAsStateWithLifecycle(initialValue = null)
    val prefs = storedPrefs ?: UiPrefs()
    val preferredCampus = storedPrefs?.preferredCampus

    // 日程待办按本机偏好合入课表页或专注页。只有待办页面、待办能力和所选宿主
    // 同时可用时才合页；所选宿主缺失时保留原独立 tab，不改写用户保存的偏好。
    val pagerRoutes = remember(pluginUi) {
        pluginUi.bottomNav.map { it.route.value }.toSet()
    }
    val todoHost = resolveTodoHost(
        pagerRoutes = pagerRoutes,
        timetablePageRegistered = pluginUi.page(TIMETABLE_ROUTE) != null,
        focusPageRegistered = pluginUi.page(FOCUS_MAIN_ROUTE) != null,
        todoPageRegistered = pluginUi.page(TODO_ROUTE) != null,
        todoStoreAvailable = todoStore != null,
        preferredHost = storedPrefs?.todoHostPlacement,
    )
    // 只剩一个宿主且偏好尚未读到时，待办是独立 tab 还是该宿主内的二级页还未
    // 确定。这里先不挂载 NavHost、Pager 和导航 effects，等偏好到达后一次性按
    // 最终层级装配；否则会先注册临时导航图、消费通知/组件入口，再在位置确定
    // 后重建并丢失深链或 Pager 选择。两个宿主都可用时无需等待，偏好未读到时
    // 设置卡片仍显示但选项禁用，槽位暂不渲染。
    if (todoHost.awaitingPreference) {
        Box(Modifier.fillMaxSize().background(appScaffoldContainerColor()))
        return
    }
    // 首页由宿主固定在最左，服务广场排在课表之后；插件 tab 按各自 order 排序合并。
    val destinations = remember(pluginUi, todoHost.embedTodo) {
        val pluginDestinations = visiblePluginTabs(pluginUi.bottomNav, todoHost.embedTodo)
            .map { tab ->
                Destination(
                    route = tab.route.value,
                    label = tab.label,
                    icon = tab.icon.filledIcon(),
                    outlinedIcon = tab.icon.outlinedIcon(),
                    order = tab.order,
                    page = pluginUi.page(tab.route),
                )
            }.sortedWith(compareBy({ it.order }, { it.route }))
        listOf(hostHome) +
            (pluginDestinations + hostServices).sortedWith(compareBy({ it.order }, { it.route }))
    }
    val allServiceEntries = remember(pluginUi) {
        pluginUi.entriesIn(UiSurface.HOME_SERVICES, UiSurface.SERVICE_DIRECTORY)
    }
    val homeTimetableSections = remember(pluginUi) {
        pluginUi.sectionsFor(UiSurface.HOME_TIMETABLE)
    }
    val homeCardSections = remember(pluginUi) {
        pluginUi.sectionsFor(UiSurface.HOME_CARD)
    }
    val homeTodoSections = remember(pluginUi) {
        pluginUi.sectionsFor(UiSurface.HOME_TODO)
    }
    val schoolSections = remember(pluginUi) {
        pluginUi.sectionsFor(UiSurface.MORE_SCHOOL)
    }
    val destinationRoutes = remember(destinations) { destinations.map { it.route } }
    val tabRoutes = remember(destinationRoutes) { destinationRoutes.toSet() }
    // 偏好尚未读到时 host 为 null：先不渲染槽位，避免默认位置闪一下后再跳页。
    val todoScheduleSlot: (@Composable () -> Unit)? = remember(
        todoHost.host,
        todoStore,
        navController,
    ) {
        val store = todoStore
        if (todoHost.host != null && store != null) {
            val slot: @Composable () -> Unit = {
                TodoScheduleSection(
                    store = store,
                    onOpenAll = {
                        navController.navigate(TODO_ROUTE.value) { launchSingleTop = true }
                    },
                )
            }
            slot
        } else {
            null
        }
    }

    // 合页后待办完整列表是二级页；通知/组件深链不能按 tab 下标定位。仅当待办仍是
    // 独立 tab（所选宿主缺失）时才预选该页，避免跳到不存在的路由。
    val initialRoute = when {
        openTodo || launchTarget == WidgetLaunchTarget.TODO ->
            TODO_ROUTE.value.takeIf { it in tabRoutes } ?: AppRoutes.HOME
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
    val imeInsets = WindowInsets.ime
    val imeVisibleState = remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }

    /**
     * 四个一级页只由一个 PagerState 表示；不把 tab 压进 NavController。
     *
     * 点击/组件/返回都走这里。二级页存在时先回 main，再平滑切页；重复调用会
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

    /**
     * 通知、桌面待办组件和＋入口共用：稳定解析待办完整页。
     *
     * 合页时 todo.list 是二级页，直接压入 NavController；课表插件缺失、待办仍是
     * 独立 tab 时才按路由切 Pager。永远不会为了待办通知触发课表登录。
     */
    fun openTodoListPage() {
        val page = pluginUi.page(TODO_ROUTE)
        if (page == null) {
            switchTab(AppRoutes.HOME)
            return
        }
        if (page.routeId.value in tabRoutes) {
            switchTab(page.routeId.value)
        } else {
            navController.navigate(page.routeId.value) { launchSingleTop = true }
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

    val onOpenRoute: (PluginRouteId) -> Unit = remember(pageHost) {
        { route -> pageHost.open(route) }
    }
    val onOpenSettings = remember(navController) {
        { navController.navigate(AppRoutes.SETTINGS) { launchSingleTop = true } }
    }
    val onOpenOfficialWeb = remember(navController) {
        { navController.navigate(AppRoutes.OFFICIAL_WEB) { launchSingleTop = true } }
    }
    val onOpenLearningPlatforms = remember(navController) {
        { navController.navigate(AppRoutes.LEARNING_PLATFORMS) { launchSingleTop = true } }
    }
    val onOpenServices = remember(destinationRoutes, navController, pagerState) {
        { switchTab(AppRoutes.SERVICES) }
    }
    val onHomeModuleOrderChange: (List<HomeModuleId>) -> Unit = remember(scope) {
        { newOrder ->
            scope.launch { AppGraph.uiPrefsStore.setHomeModuleOrder(newOrder) }
        }
    }
    val onTimetableModuleOrderChange: (List<TimetableModuleId>) -> Unit = remember(scope) {
        { newOrder ->
            scope.launch { AppGraph.uiPrefsStore.setTimetableModuleOrder(newOrder) }
        }
    }
    val onTogglePin: (String) -> Unit = remember(scope) {
        { entryId ->
            scope.launch { AppGraph.uiPrefsStore.togglePinnedService(entryId) }
        }
    }

    // 通知点击进入待办完整页；具体条目由 TodoService.pendingOpenTask 交给页面。
    // 位置未定的首读时序不会走到这里（上层已延迟挂载），因此可直接按最终稳定
    // 路由消费，不会提前导航到将被移除的页面。
    LaunchedEffect(openTodo) {
        if (!openTodo) return@LaunchedEffect
        openTodoListPage()
        onTodoConsumed()
    }

    // 桌面组件的固定入口：校园卡回首页，电费直接进唐岛湾原生查询页，课表切到合页，
    // 待办打开完整列表。只在消费一次后清空，避免重组时反复导航；不接受任意路由或
    // Intent 转发。
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
                if (TIMETABLE_ROUTE.value in tabRoutes) {
                    switchTab(TIMETABLE_ROUTE.value)
                } else {
                    switchTab(AppRoutes.HOME)
                }
            }
            WidgetLaunchTarget.TODO -> {
                openTodoListPage()
            }
            null -> return@LaunchedEffect
        }
        onLaunchConsumed()
    }

    val homePageIndex = pageIndexForRoute(destinationRoutes, AppRoutes.HOME).coerceAtLeast(0)
    // 使用 derivedStateOf 隔离高频滚动偏移与路由状态，避免 Pager 切页或二级页间跳转触发整棵 AppNav 重组。
    val backToHomeEnabled by remember(onMainState, pagerState, homePageIndex) {
        derivedStateOf {
            onMainState.value && (
                pagerState.currentPage != homePageIndex ||
                    pagerState.currentPageOffsetFraction != 0f
                )
        }
    }
    BackHandler(enabled = backToHomeEnabled) {
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
    // 光学底栏只在 API 33+ 启用；31–32 保留现有 Material 3 底栏。
    val opticalDock = glass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val dockBackdrop = if (opticalDock) glassThemeBackdrop() else null
    val rootContainerColor = appScaffoldContainerColor()
    val dockAnimation: AnimationSpec<Float> = remember(opticalDock) {
        if (opticalDock) {
            spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMedium)
        } else {
            tween(durationMillis = 320, easing = FastOutSlowInEasing)
        }
    }
    val selectTab: (Int) -> Unit = remember(destinations, dockAnimation, navController, pagerState) {
        { index ->
            destinations.getOrNull(index)?.let { switchTab(it.route, dockAnimation) }
        }
    }

    // 首帧只组合当前可见的一级页，首帧绘制后再逐帧预热其余一级页；
    // 预热完成后一级页与底栏常驻于 MainNavHostContainer，二级页返回不再重建。
    var warmedBeyondPages by remember(destinations.size) { mutableIntStateOf(0) }
    LaunchedEffect(destinations.size) {
        val target = destinations.lastIndex.coerceAtLeast(0)
        if (target <= 0) return@LaunchedEffect
        withFrameNanos { }
        for (count in 1..target) {
            snapshotFlow { onMainState.value }.first { it }
            withFrameNanos { }
            warmedBeyondPages = count
        }
    }

    MainNavHostContainer(
        navController = navController,
        onMainState = onMainState,
        imeVisibleState = imeVisibleState,
        rootContainerColor = rootContainerColor,
        mainContent = { mainActiveState, mainImeVisibleState ->
            val pagerScrollEnabled by remember(mainActiveState, mainImeVisibleState) {
                derivedStateOf { mainActiveState.value && !mainImeVisibleState.value }
            }
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 全部一级页保留状态；活动页由 LocalPluginPageActive 控制自动查询/
                    // 权限/通知消费，预加载不会触发学校登录或刷新。
                    beyondViewportPageCount = warmedBeyondPages,
                    userScrollEnabled = pagerScrollEnabled,
                    overscrollEffect = null,
                    key = { destinations[it].route },
                ) { page ->
                    val active by remember(mainActiveState, pagerState, page) {
                        derivedStateOf {
                            mainActiveState.value &&
                                pagerState.currentPage == page &&
                                !pagerState.isScrollInProgress
                        }
                    }
                    val pageModifier = if (active) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxSize().clearAndSetSemantics { }
                    }
                    CompositionLocalProvider(LocalPluginPageActive provides active) {
                        Box(pageModifier) {
                            val destination = destinations.getOrNull(page)
                            when (destination?.route) {
                                AppRoutes.HOME -> HomeScreen(
                                    serviceEntries = allServiceEntries,
                                    timetableSections = homeTimetableSections,
                                    cardSections = homeCardSections,
                                    todoSections = homeTodoSections,
                                    sectionHost = sectionHost,
                                    onOpenRoute = onOpenRoute,
                                    onOpenSettings = onOpenSettings,
                                    onOpenOfficialWeb = onOpenOfficialWeb,
                                    pinnedServiceIds = prefs.pinnedServiceIds,
                                    onOpenServices = onOpenServices,
                                    onOpenLearningPlatforms = onOpenLearningPlatforms,
                                    moduleOrder = prefs.homeModuleOrder,
                                    reorderEnabled = prefs.moduleReorderEnabled,
                                    onModuleOrderChange = onHomeModuleOrderChange,
                                )
                                AppRoutes.SERVICES -> ServicesScreen(
                                    entries = allServiceEntries,
                                    pinnedServiceIds = prefs.pinnedServiceIds,
                                    preferredCampus = preferredCampus,
                                    onTogglePin = onTogglePin,
                                    onOpenRoute = onOpenRoute,
                                    onOpenOfficialWeb = onOpenOfficialWeb,
                                    onOpenLearningPlatforms = onOpenLearningPlatforms,
                                )
                                else -> {
                                    val pageContent = destination?.page
                                    val slot = todoScheduleSlot
                                    when {
                                        destination == null || pageContent == null -> Unit
                                        // 课表页按本机排序偏好组合日程待办槽位与周课表主体。
                                        destination.route == TIMETABLE_ROUTE.value -> {
                                            val timetableLayout = remember(
                                                prefs.timetableModuleOrder,
                                                prefs.moduleReorderEnabled,
                                                onTimetableModuleOrderChange,
                                            ) {
                                                TimetableModuleLayout(
                                                    order = prefs.timetableModuleOrder,
                                                    reorderEnabled = prefs.moduleReorderEnabled,
                                                    onOrderChange = onTimetableModuleOrderChange,
                                                )
                                            }
                                            val activeSlot = if (
                                                slot != null &&
                                                todoHost.host == TodoHostPlacement.TIMETABLE
                                            ) {
                                                slot
                                            } else {
                                                null
                                            }
                                            CompositionLocalProvider(
                                                LocalTimetableTodoSlot provides activeSlot,
                                                LocalTimetableModuleLayout provides timetableLayout,
                                            ) {
                                                pageContent.content(pageHost)
                                            }
                                        }
                                        // 专注页在计时控制与标签之间插入同一份日程。
                                        slot != null &&
                                            destination.route == FOCUS_MAIN_ROUTE.value &&
                                            todoHost.host == TodoHostPlacement.FOCUS -> {
                                            CompositionLocalProvider(
                                                LocalFocusTodoSlot provides slot,
                                            ) {
                                                pageContent.content(pageHost)
                                            }
                                        }
                                        else -> pageContent.content(pageHost)
                                    }
                                }
                            }
                        }
                    }
                }

                // 底栏随一级页常驻在 NavHost 下方：切去二级页时不销毁 LiquidBottomTabs /
                // LayerBackdrop / RuntimeShader，一级页内弹输入法或玻璃弹层时仅取消放置以释放命中区。
                val showDock = !mainImeVisibleState.value &&
                    !(mainActiveState.value && isGlassSheetVisible())
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (showDock) Modifier else Modifier.clearAndSetSemantics { })
                        .layout { measurable, constraints ->
                            if (!showDock) {
                                layout(0, 0) {}
                            } else {
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    placeable.placeRelative(0, 0)
                                }
                            }
                        },
                ) {
                    if (dockBackdrop != null) {
                        KyantDock(
                            destinations = destinations,
                            pagerState = pagerState,
                            backdrop = dockBackdrop,
                            dockDark = dockDark,
                            onSelect = selectTab,
                        )
                    } else {
                        FloatingDock(
                            destinations = destinations,
                            pagerState = pagerState,
                            dockDark = dockDark,
                            onSelect = selectTab,
                        )
                    }
                }
            }
        },
        secondaryRoutes = {
            pluginUi.pages
                .filterNot { it.routeId.value in tabRoutes }
                .forEach { page ->
                    composable(page.routeId.value) {
                        if (page.routeId == TODO_ROUTE) {
                            // 合页后的完整待办列表是二级页，顶栏提供明确返回；
                            // 独立 tab 场景不会进入此分支。
                            val onBack = remember(navController) {
                                {
                                    navController.popBackStack()
                                    Unit
                                }
                            }
                            CompositionLocalProvider(
                                LocalTodoListBack provides onBack,
                            ) {
                                page.content(pageHost)
                            }
                        } else {
                            page.content(pageHost)
                        }
                    }
                }
            composable(AppRoutes.SETTINGS) {
                val accountState by AppGraph.account.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    accountState = accountState,
                    schoolSections = schoolSections,
                    sectionHost = sectionHost,
                    preferredCampus = preferredCampus,
                    onPreferredCampusChange = remember(scope) {
                        { campus ->
                            scope.launch { AppGraph.uiPrefsStore.setPreferredCampus(campus) }
                        }
                    },
                    todoHostPlacement = storedPrefs?.todoHostPlacement,
                    showTodoHostPlacement = todoHost.canChooseHost,
                    onTodoHostPlacementChange = remember(scope) {
                        { placement ->
                            scope.launch {
                                AppGraph.uiPrefsStore.setTodoHostPlacement(placement)
                            }
                        }
                    },
                    moduleReorderEnabled = storedPrefs?.moduleReorderEnabled,
                    onModuleReorderEnabledChange = remember(scope) {
                        { enabled ->
                            scope.launch { AppGraph.uiPrefsStore.setModuleReorderEnabled(enabled) }
                        }
                    },
                    onBack = remember(navController) { { navController.popBackStack() } },
                    onOpenAccount = remember(navController) {
                        { navController.navigate(AppRoutes.ACCOUNT) { launchSingleTop = true } }
                    },
                    onOpenAppearance = remember(navController) {
                        { navController.navigate(AppRoutes.APPEARANCE) { launchSingleTop = true } }
                    },
                    onOpenWidgets = remember(navController) {
                        { navController.navigate(AppRoutes.WIDGETS) { launchSingleTop = true } }
                    },
                    onOpenOfficialWeb = onOpenOfficialWeb,
                    onOpenAbout = remember(navController) {
                        { navController.navigate(AppRoutes.ABOUT) { launchSingleTop = true } }
                    },
                )
            }
            composable(AppRoutes.ACCOUNT) {
                val legacyCardStudentId by AppGraph.cardInputRepository.studentId
                    .collectAsStateWithLifecycle(initialValue = "")
                SchoolAccountScreen(
                    accounts = AppGraph.account,
                    onBack = remember(navController) { { navController.popBackStack() } },
                    legacyStudentIdHint = legacyCardStudentId.takeIf { it.isNotBlank() },
                )
            }
            composable(AppRoutes.APPEARANCE) {
                AppearanceScreen(
                    onBack = remember(navController) { { navController.popBackStack() } },
                )
            }
            composable(AppRoutes.WIDGETS) {
                WidgetSettingsScreen(
                    onBack = remember(navController) { { navController.popBackStack() } },
                )
            }
            composable(AppRoutes.OFFICIAL_WEB) {
                OfficialWebScreen(
                    onBack = remember(navController) { { navController.popBackStack() } },
                )
            }
            composable(AppRoutes.LEARNING_PLATFORMS) {
                LearningPlatformsScreen(
                    onBack = remember(navController) { { navController.popBackStack() } },
                )
            }
            composable(AppRoutes.ABOUT) {
                AboutScreen(
                    onBack = remember(navController) { { navController.popBackStack() } },
                )
            }
        },
    )
}

/**
 * 一级页常驻层与二级页 [NavHost] 的分层容器。
 *
 * 一级页及悬浮底栏始终挂载在 [NavHost] 下方，`MAIN_ROUTE` 仅作为空占位路由：
 * 1. 进入二级页时一级页通过 GPU 透明过渡隐去并在完全透明后跳过绘制，不再销毁四页状态，
 *    返回一级页时零重建、零滚动位置丢失；
 * 2. [LocalDockClearance] 仅向一级页注入且不随进入二级页归零，避免切换瞬间触发四页整页重排；
 * 3. 二级页期间阻断底层指针事件并清除底层无障碍语义，透明自定义背景/玻璃页也不会透出底层内容。
 */
@Composable
internal fun MainNavHostContainer(
    navController: NavHostController,
    onMainState: State<Boolean>,
    imeVisibleState: State<Boolean>,
    rootContainerColor: Color,
    mainContent: @Composable (onMainState: State<Boolean>, mainImeVisibleState: State<Boolean>) -> Unit,
    secondaryRoutes: NavGraphBuilder.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(rootContainerColor),
    ) {
        PersistentMainLayer(
            onMainState = onMainState,
            imeVisibleState = imeVisibleState,
            content = mainContent,
        )

        NavHost(
            navController = navController,
            startDestination = MAIN_ROUTE,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = SECONDARY_ENTER_MS,
                        easing = FastOutSlowInEasing,
                    ),
                ) + slideInHorizontally(
                    animationSpec = tween(
                        durationMillis = SECONDARY_ENTER_MS,
                        easing = FastOutSlowInEasing,
                    ),
                ) { fullWidth -> (fullWidth * 0.08f).toInt() }
            },
            exitTransition = {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = SECONDARY_EXIT_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            },
            popEnterTransition = {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = SECONDARY_POP_ENTER_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            },
            popExitTransition = {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = SECONDARY_POP_EXIT_MS,
                        easing = FastOutSlowInEasing,
                    ),
                ) + slideOutHorizontally(
                    animationSpec = tween(
                        durationMillis = SECONDARY_POP_EXIT_MS,
                        easing = FastOutSlowInEasing,
                    ),
                ) { fullWidth -> (fullWidth * 0.08f).toInt() }
            },
        ) {
            composable(
                route = MAIN_ROUTE,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                // 空占位：真实一级页常驻于 PersistentMainLayer。
            }
            secondaryRoutes()
        }
    }
}

@Composable
private fun PersistentMainLayer(
    onMainState: State<Boolean>,
    imeVisibleState: State<Boolean>,
    content: @Composable (onMainState: State<Boolean>, mainImeVisibleState: State<Boolean>) -> Unit,
) {
    val onMain by onMainState
    val mainImeVisibleState = remember(onMainState, imeVisibleState) {
        derivedStateOf { onMainState.value && imeVisibleState.value }
    }
    val mainImeVisible by mainImeVisibleState
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val mainDockClearance = if (!mainImeVisible) DOCK_CLEARANCE + navigationBottom else 0.dp
    val mainAlphaState = animateFloatAsState(
        targetValue = if (onMain) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (onMain) MAIN_LAYER_ENTER_MS else MAIN_LAYER_EXIT_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "main_layer_alpha",
    )
    val hiddenLayerGuard = if (onMain) {
        Modifier
    } else {
        Modifier
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    }

    CompositionLocalProvider(LocalDockClearance provides mainDockClearance) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(hiddenLayerGuard)
                .graphicsLayer { alpha = mainAlphaState.value }
                .drawWithContent {
                    if (mainAlphaState.value > 0.001f) {
                        drawContent()
                    }
                },
        ) {
            content(onMainState, mainImeVisibleState)
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
 * 日程待办在一级页中的最终归属。
 *
 * [embedTodo] 为 true 时待办独立 tab 从 Pager 移除，[host] 是应渲染
 * `TodoScheduleSection` 的一级页。两个宿主都可用时一级入口始终为四项；所选
 * 宿主缺失时保留已注册的待办 tab，不自动改挂另一页，也不覆盖保存的偏好。
 * [awaitingPreference] 只在一级/二级归属会随尚未读到的偏好改变时置位；
 * 调用方应推迟挂载导航图与消费待办深链，等偏好到达后再按最终层级装配。
 */
internal data class TodoHostResolution(
    val embedTodo: Boolean,
    val host: TodoHostPlacement?,
    val canChooseHost: Boolean,
    val awaitingPreference: Boolean,
)

/**
 * 以最终 UI 快照、实际注册的 Pager 入口和待办能力解析待办宿主。
 *
 * 不按模块名或硬编码插件开关决定；`pagerRoutes` 必须来自最终可见的 bottomNav，
 * 这样未注册为一级入口的页面不会被误当成合页宿主。
 */
internal fun resolveTodoHost(
    pagerRoutes: Set<String>,
    timetablePageRegistered: Boolean,
    focusPageRegistered: Boolean,
    todoPageRegistered: Boolean,
    todoStoreAvailable: Boolean,
    preferredHost: TodoHostPlacement?,
): TodoHostResolution {
    val todoReady = todoPageRegistered && todoStoreAvailable
    val timetableHostAvailable =
        timetablePageRegistered && TIMETABLE_ROUTE.value in pagerRoutes
    val focusHostAvailable = focusPageRegistered && FOCUS_MAIN_ROUTE.value in pagerRoutes
    val bothHostsAvailable = timetableHostAvailable && focusHostAvailable
    val availableHost = when {
        timetableHostAvailable -> TodoHostPlacement.TIMETABLE
        focusHostAvailable -> TodoHostPlacement.FOCUS
        else -> null
    }
    // 待办页面或本机存储缺失时没有槽位，也没有位置可选项。
    // 两个宿主都在时一级入口始终四项，位置只决定槽位所在页；只剩一个宿主时
    // 只有偏好正好指向它才合页，所选宿主缺失则保留独立 tab 且不改写偏好。
    val embedTodo = todoReady && availableHost != null &&
        (bothHostsAvailable || preferredHost == availableHost)
    // 两个宿主且偏好未读到时先不渲染槽位（避免闪错页），但无需等待。
    val selectedHost = if (embedTodo) preferredHost else null
    val canChoose = todoReady && bothHostsAvailable
    // 只剩一个宿主且偏好未读到时，待办是独立 tab 还是二级页还未定，
    // 调用方需等偏好到达后再装配导航图。
    val awaiting = todoReady && availableHost != null &&
        !bothHostsAvailable && preferredHost == null
    return TodoHostResolution(
        embedTodo = embedTodo,
        host = selectedHost,
        canChooseHost = canChoose,
        awaitingPreference = awaiting,
    )
}

/**
 * 合页时从最终插件 tab 快照中移除待办；完整待办列表改由稳定的二级路由承载。
 * 单独保留该过滤规则，便于用真实装配的 bottomNav 做回归，而不是手写路由表。
 */
internal fun visiblePluginTabs(
    tabs: List<PluginBottomNav>,
    todoEmbedded: Boolean,
): List<PluginBottomNav> = tabs.filterNot {
    todoEmbedded && it.route.value == TODO_ROUTE.value
}

/**
 * 五项悬浮胶囊 dock（基于 3beuml/ZZUPocket MIT AppNav 的视觉/覆盖结构）。
 *
 * 触摸区域限于胶囊的矩形边界，页面手势可从周围的透明留白起手。胶囊内的点击
 * 由 Tab 处理，选中泡和图标过渡随 PagerState 的连续位置更新。
 */
@Composable
private fun FloatingDock(
    destinations: List<Destination>,
    pagerState: PagerState,
    dockDark: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (destinations.isEmpty()) return
    val selectedIndex by remember(pagerState) { derivedStateOf { pagerState.targetPage } }
    // API 31–32 不使用玻璃底栏，保持现有 Material 3 底栏。
    val glass = LocalUiStyle.current == UiStyle.LIQUID_GLASS &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
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
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) { detectTapGestures { } }
            .shadow(
                elevation = dockElevation,
                shape = dockShape,
                clip = false,
            )
            .testTag("floating_dock"),
    ) {
        val materialBubble = if (!glass) {
            Modifier.drawBehind {
                val count = destinations.size
                if (count <= 0) return@drawBehind
                val slotWidth = size.width / count
                val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, lastIndex.toFloat())
                drawCircle(
                    color = selectedBubble,
                    radius = 20.dp.toPx(),
                    center = Offset(slotWidth * (position + 0.5f), size.height / 2f),
                )
            }
        } else Modifier

        Box(Modifier.fillMaxSize().glassHighlight(dockShape).then(materialBubble)) {
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

@Composable
private fun DockTabIcon(
    destination: Destination,
    pagerState: PagerState,
    index: Int,
    lastIndex: Int,
    unselectedTint: Color,
    selectedTint: Color,
) {
    // 将 Pager 偏移读取推迟到绘制阶段（graphicsLayer），滑动或切 tab 期间不触发图标重组。
    Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = destination.outlinedIcon,
            contentDescription = null,
            tint = unselectedTint,
            modifier = Modifier
                .size(23.dp)
                .graphicsLayer {
                    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        .coerceIn(0f, lastIndex.toFloat())
                    val selectedAmount = (1f - abs(position - index)).coerceIn(0f, 1f)
                    alpha = 1f - selectedAmount
                },
        )
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = selectedTint,
            modifier = Modifier
                .size(23.dp)
                .graphicsLayer {
                    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        .coerceIn(0f, lastIndex.toFloat())
                    val selectedAmount = (1f - abs(position - index)).coerceIn(0f, 1f)
                    alpha = selectedAmount
                },
        )
    }
}
