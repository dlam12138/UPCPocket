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
import androidx.compose.runtime.getValue
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
import com.upcpocket.app.ui.about.AboutScreen
import com.upcpocket.app.ui.campus.CampusScreen
import com.upcpocket.app.ui.classroom.FreeClassroomScreen
import com.upcpocket.app.ui.focus.FocusScreen
import com.upcpocket.app.ui.focus.FocusStatsScreen
import com.upcpocket.app.ui.home.HomeScreen
import com.upcpocket.app.ui.library.LibrarySeatLoginScreen
import com.upcpocket.app.ui.library.LibrarySeatScreen
import com.upcpocket.app.ui.power.ElectricityScreen
import com.upcpocket.app.ui.power.TangdaowanElectricityScreen
import com.upcpocket.app.ui.settings.AppearanceScreen

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
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Destination.entries.any { it.route == currentRoute }

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
                            onOpenElectricity = { navController.navigate(AppRoutes.ELECTRICITY) },
                            onOpenClassrooms = { navController.navigate(AppRoutes.FREE_CLASSROOMS) },
                            onOpenLibrary = { navController.navigate(AppRoutes.LIBRARY_SEAT) },
                        )
                    }
                    composable(Destination.FOCUS.route) {
                        FocusScreen(onOpenStats = { navController.navigate(AppRoutes.FOCUS_STATS) })
                    }
                    composable(Destination.MORE.route) {
                        CampusScreen(
                            onOpenFocusStats = { navController.navigate(AppRoutes.FOCUS_STATS) },
                            onOpenAppearance = { navController.navigate(AppRoutes.APPEARANCE) },
                            onOpenAbout = { navController.navigate(AppRoutes.ABOUT) },
                        )
                    }
                    composable(AppRoutes.FREE_CLASSROOMS) {
                        FreeClassroomScreen(onBack = { navController.popBackStack() })
                    }
                    composable(AppRoutes.LIBRARY_SEAT) {
                        LibrarySeatScreen(
                            onBack = { navController.popBackStack() },
                            onLogin = { navController.navigate(AppRoutes.LIBRARY_LOGIN) },
                        )
                    }
                    composable(AppRoutes.LIBRARY_LOGIN) {
                        LibrarySeatLoginScreen(
                            onBack = { navController.popBackStack() },
                            onLoggedIn = { navController.popBackStack() },
                        )
                    }
                    composable(AppRoutes.ELECTRICITY) {
                        ElectricityScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTangdaowan = { navController.navigate(AppRoutes.POWER_TANGDAOWAN) },
                        )
                    }
                    composable(AppRoutes.POWER_TANGDAOWAN) {
                        TangdaowanElectricityScreen(onBack = { navController.popBackStack() })
                    }
                    composable(AppRoutes.APPEARANCE) {
                        AppearanceScreen(onBack = { navController.popBackStack() })
                    }
                    composable(AppRoutes.ABOUT) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                    composable(AppRoutes.FOCUS_STATS) {
                        FocusStatsScreen(onBack = { navController.popBackStack() })
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
 * 悬浮 dock 为「滚动内容」预留下的底部余量：胶囊 56 + 上下边距 16 = 72，再加 16 呼吸。
 * dock 是覆盖层后内容全高滚动（从胶囊底下穿过），各滚动容器把这个值挂到
 * contentPadding 上，**最后一条内容才能完整滚出胶囊、不再永远压在 dock 底下**。
 */
val DOCK_CLEARANCE = 88.dp
