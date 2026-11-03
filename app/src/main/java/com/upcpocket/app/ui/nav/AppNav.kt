package com.upcpocket.app.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.upcpocket.app.account.SchoolAccountScreen
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.plugin.ui.PluginPageHost
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.power.POWER_TANGDAOWAN_ROUTE
import com.upcpocket.app.ui.about.AboutScreen
import com.upcpocket.app.ui.campus.CampusScreen
import com.upcpocket.app.ui.home.HomeScreen
import com.upcpocket.app.ui.settings.AppearanceScreen
import com.upcpocket.app.widget.WidgetLaunchTarget

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 未选中态用线性版本（M3 惯例：outlined ↔ filled 区分选中），悬浮胶囊 dock 用 */
    val outlinedIcon: ImageVector,
    val order: Int,
)

private fun filledIcon(icon: UiIcon): ImageVector = when (icon) {
    UiIcon.ELECTRICITY -> Icons.Filled.Bolt
    UiIcon.CLASSROOM -> Icons.Filled.MeetingRoom
    UiIcon.LIBRARY -> Icons.AutoMirrored.Filled.MenuBook
    UiIcon.INSIGHTS -> Icons.Filled.Insights
    UiIcon.TIMER -> Icons.Filled.Timer
    UiIcon.TIMETABLE -> Icons.AutoMirrored.Filled.MenuBook
}

private fun outlinedIcon(icon: UiIcon): ImageVector = when (icon) {
    UiIcon.ELECTRICITY -> Icons.Outlined.Bolt
    UiIcon.CLASSROOM -> Icons.Outlined.MeetingRoom
    UiIcon.LIBRARY -> Icons.AutoMirrored.Outlined.MenuBook
    UiIcon.INSIGHTS -> Icons.Outlined.Insights
    UiIcon.TIMER -> Icons.Outlined.Timer
    UiIcon.TIMETABLE -> Icons.AutoMirrored.Outlined.MenuBook
}

private val hostDestinations = listOf(
    Destination(AppRoutes.HOME, "首页", Icons.Filled.Home, Icons.Outlined.Home, order = 0),
    Destination(AppRoutes.MORE, "更多", Icons.Filled.Apps, Icons.Outlined.Apps, order = 100),
)

@Composable
fun AppNav(
    launchTarget: WidgetLaunchTarget? = null,
    onLaunchConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val pluginUi = AppGraph.ui
    val accountState by AppGraph.account.state.collectAsStateWithLifecycle()
    val legacyCardStudentId by AppGraph.cardInputRepository.studentId
        .collectAsStateWithLifecycle(initialValue = "")
    val destinations = (hostDestinations + pluginUi.bottomNav.map { tab ->
        Destination(
            route = tab.route.value,
            label = tab.label,
            icon = filledIcon(tab.icon),
            outlinedIcon = outlinedIcon(tab.icon),
            order = tab.order,
        )
    }).sortedWith(compareBy({ it.order }, { it.route }))
    val showBottomBar = destinations.any { it.route == currentRoute }

    val pageHost = remember(navController) {
        object : PluginPageHost {
            override fun open(route: PluginRouteId) {
                navController.navigate(route.value)
            }

            override fun back() {
                navController.popBackStack()
            }

            override fun openSchoolAccountSettings() {
                navController.navigate(AppRoutes.ACCOUNT) { launchSingleTop = true }
            }
        }
    }

    val sectionHost = remember(navController) {
        object : PluginSectionHost {
            override fun openSchoolAccountSettings() {
                navController.navigate(AppRoutes.ACCOUNT) { launchSingleTop = true }
            }
        }
    }

    /**
     * 切到某个 tab。
     *
     * 所有"进 tab"的路径都走这里：saveState/restoreState 让每个 tab 各自保留滚动位置，
     * 也避免把子页压进 tab 的回退栈。
     */
    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // 桌面组件的固定入口：校园卡回首页（余额功能区所在），电费直接进唐岛湾原生查询页。
    // 只在消费一次后清空，避免重组时反复导航；不接受任意路由或 Intent 转发。
    LaunchedEffect(launchTarget) {
        when (launchTarget) {
            WidgetLaunchTarget.CARD_BALANCE -> {
                // 不能用 switchTab 的 restoreState：若 HOME 栈顶还压着电费子页，
                // 恢复状态会把电费页又带回来，看起来像“点校园卡没反应”。
                navController.navigate(AppRoutes.HOME) {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                    launchSingleTop = true
                }
            }
            WidgetLaunchTarget.POWER_TANGDAOWAN -> {
                val powerPage = pluginUi.page(POWER_TANGDAOWAN_ROUTE)
                if (powerPage != null) {
                    navController.navigate(powerPage.routeId.value) { launchSingleTop = true }
                } else {
                    // 电量插件未激活时，旧组件仍可能带着这个入口；安全回到首页，
                    // 不导航到未注册路由，也不强行启动插件。
                    navController.navigate(AppRoutes.HOME) { launchSingleTop = true }
                }
            }
            null -> return@LaunchedEffect
        }
        onLaunchConsumed()
    }

    // dock 胶囊的透明度：半透明 + 细描边 + 无阴影，所有页面同一套配方。
    val dockAlpha = 0.5f

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                NavHost(
                    navController = navController,
                    startDestination = AppRoutes.HOME,
                ) {
                    composable(AppRoutes.HOME) {
                        HomeScreen(
                            serviceEntries = pluginUi.entriesFor(UiSurface.HOME_SERVICES),
                            cardSections = pluginUi.sectionsFor(UiSurface.HOME_CARD),
                            sectionHost = sectionHost,
                            onOpenRoute = pageHost::open,
                        )
                    }
                    composable(AppRoutes.MORE) {
                        CampusScreen(
                            learningEntries = pluginUi.entriesFor(UiSurface.MORE_LEARNING),
                            schoolSections = pluginUi.sectionsFor(UiSurface.MORE_SCHOOL),
                            sectionHost = sectionHost,
                            accountState = accountState,
                            onOpenRoute = pageHost::open,
                            onOpenAccount = { navController.navigate(AppRoutes.ACCOUNT) },
                            onOpenAppearance = { navController.navigate(AppRoutes.APPEARANCE) },
                            onOpenAbout = { navController.navigate(AppRoutes.ABOUT) },
                        )
                    }
                    pluginUi.pages.forEach { page ->
                        composable(page.routeId.value) {
                            page.content(pageHost)
                        }
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
                    composable(AppRoutes.ABOUT) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                }

                // 悬浮胶囊 dock —— 覆盖层（对齐内容 Box 的底部）：
                // 所有页面同一配方：半透明面板 + 玻璃边 + 窄软落地影。
                if (showBottomBar) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = dockAlpha),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            border = BorderStroke(
                                0.5.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.25f),
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    ),
                                ),
                            ),
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .fillMaxWidth()
                                .height(56.dp)
                                .shadow(
                                    elevation = 6.dp,
                                    shape = RoundedCornerShape(28.dp),
                                    clip = false,
                                ),
                        ) {
                            Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                destinations.forEach { destination ->
                                    val selected = currentRoute == destination.route
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable { switchTab(destination.route) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (selected) {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                            .copy(alpha = 0.85f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                if (selected) {
                                                    destination.icon
                                                } else {
                                                    destination.outlinedIcon
                                                },
                                                contentDescription = destination.label,
                                                tint = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.size(23.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 悬浮 dock 的页面底部余量已移到 `core:designsystem` 的 [DOCK_CLEARANCE]；
 * 宿主只负责决定 dock 自身的呈现位置。
 */
