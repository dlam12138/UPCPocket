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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.focus.FOCUS_STATS_ROUTE
import com.upcpocket.app.plugin.ui.PluginPageHost
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.power.POWER_TANGDAOWAN_ROUTE
import com.upcpocket.app.ui.about.AboutScreen
import com.upcpocket.app.ui.campus.CampusScreen
import com.upcpocket.app.ui.focus.FocusScreen
import com.upcpocket.app.ui.home.HomeScreen
import com.upcpocket.app.ui.settings.AppearanceScreen
import com.upcpocket.app.widget.WidgetLaunchTarget

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 未选中态用线性版本（M3 惯例：outlined ↔ filled 区分选中），悬浮胶囊 dock 用 */
    val outlinedIcon: ImageVector,
) {
    HOME(AppRoutes.HOME, "首页", Icons.Filled.Home, Icons.Outlined.Home),
    FOCUS(AppRoutes.FOCUS, "专注", Icons.Filled.Timer, Icons.Outlined.Timer),
    MORE(AppRoutes.MORE, "更多", Icons.Filled.Apps, Icons.Outlined.Apps),
}

@Composable
fun AppNav(
    launchTarget: WidgetLaunchTarget? = null,
    onLaunchConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Destination.entries.any { it.route == currentRoute }
    val pluginUi = AppGraph.ui

    val pageHost = remember(navController) {
        object : PluginPageHost {
            override fun open(route: PluginRouteId) {
                navController.navigate(route.value)
            }

            override fun back() {
                navController.popBackStack()
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
            WidgetLaunchTarget.POWER_TANGDAOWAN -> navController.navigate(POWER_TANGDAOWAN_ROUTE.value) {
                launchSingleTop = true
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
                    startDestination = Destination.HOME.route,
                ) {
                    composable(Destination.HOME.route) {
                        HomeScreen(
                            serviceEntries = pluginUi.entriesFor(UiSurface.HOME_SERVICES),
                            cardSections = pluginUi.sectionsFor(UiSurface.HOME_CARD),
                            onOpenRoute = pageHost::open,
                        )
                    }
                    composable(Destination.FOCUS.route) {
                        FocusScreen(
                            service = AppGraph.focusService,
                            onOpenStats = { pageHost.open(FOCUS_STATS_ROUTE) },
                        )
                    }
                    composable(Destination.MORE.route) {
                        CampusScreen(
                            learningEntries = pluginUi.entriesFor(UiSurface.MORE_LEARNING),
                            schoolSections = pluginUi.sectionsFor(UiSurface.MORE_SCHOOL),
                            onOpenRoute = pageHost::open,
                            onOpenAppearance = { navController.navigate(AppRoutes.APPEARANCE) },
                            onOpenAbout = { navController.navigate(AppRoutes.ABOUT) },
                        )
                    }
                    pluginUi.pages.forEach { page ->
                        composable(page.routeId.value) {
                            page.content(pageHost)
                        }
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
                                Destination.entries.forEach { destination ->
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
