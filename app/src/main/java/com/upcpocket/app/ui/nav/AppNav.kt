package com.upcpocket.app.ui.nav

import android.net.Uri
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.ui.about.AboutScreen
import com.upcpocket.app.ui.campus.CampusScreen
import com.upcpocket.app.ui.campus.CampusServicesScreen
import com.upcpocket.app.ui.classroom.FreeClassroomScreen
import com.upcpocket.app.ui.ecard.CampusCardLoginScreen
import com.upcpocket.app.ui.ecard.CampusCardScreen
import com.upcpocket.app.ui.library.LibrarySeatLoginScreen
import com.upcpocket.app.ui.library.LibrarySeatScreen
import com.upcpocket.app.ui.power.TangdaowanElectricityScreen
import com.upcpocket.app.ui.focus.FocusScreen
import com.upcpocket.app.ui.focus.FocusStatsScreen
import com.upcpocket.app.ui.schedule.BackgroundCropScreen
import com.upcpocket.app.ui.schedule.CourseOverviewScreen
import com.upcpocket.app.ui.schedule.DisplaySettingsScreen
import com.upcpocket.app.ui.schedule.ScheduleScreen
import com.upcpocket.app.ui.schedule.WallpaperLayers
import com.upcpocket.app.ui.schedule.rememberScheduleBackground
import com.upcpocket.app.ui.settings.AppearanceScreen
import com.upcpocket.app.ui.today.TodayScreen

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 未选中态用线性版本（M3 惯例：outlined ↔ filled 区分选中），悬浮胶囊 dock 用 */
    val outlinedIcon: ImageVector,
) {
    TODAY(AppRoutes.TODAY, "今天", Icons.Filled.Today, Icons.Outlined.Today),
    SCHEDULE(AppRoutes.SCHEDULE, "课表", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    FOCUS(AppRoutes.FOCUS, "专注", Icons.Filled.Timer, Icons.Outlined.Timer),
    CAMPUS(AppRoutes.CAMPUS, "更多", Icons.Filled.Apps, Icons.Outlined.Apps),
}

/** 非 tab 的全屏页面：这些路由下不显示底部导航栏 */
private const val ROUTE_OVERVIEW = "course_overview"
private const val ROUTE_DISPLAY = "display_settings"
private const val ROUTE_BG_CROP = "background_crop"
private const val ROUTE_APPEARANCE = "appearance"

/** "重新裁剪"时用这个占位值代替相册 URI */
private const val CROP_SOURCE_SAVED = "saved"

@Composable
fun AppNav(initialRoute: String? = null, routeToken: Int = 0) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Destination.entries.any { it.route == currentRoute }

    /**
     * 切到某个 tab。
     *
     * ⚠️ **所有"进 tab"的路径都必须走这里**，包括桌面小组件带进来的路由。
     *
     * 曾经小组件是用裸 `navController.navigate("schedule")` 把课表压栈的，
     * 绕过了下面这套 saveState/restoreState 记账；结果"今天"那一格保存的回退栈里
     * 夹着一个课表条目，之后每次点「今天」都会被 `restoreState` 原样恢复成课表。
     */
    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // 桌面小组件点进来时带的路由（点组件 → 课表页）。
    //
    // 刻意**不把它当成 NavHost 的 startDestination**：那样返回栈里就只剩目标页一个，
    // 按返回键会直接退出 App。这里是先落到今天页（startDestination），再把目标页压上去 ——
    // 和用户从底栏点进去的手感一致，返回键回到今天页。
    //
    // routeToken 每收到一次组件点击就 +1：同一个路由连点两次也得能再跳一次
    // （只拿路由字符串当 key 的话值没变、effect 不重跑，看起来就是"点组件没反应"）。
    LaunchedEffect(initialRoute, routeToken) {
        if (initialRoute == null || initialRoute == Destination.TODAY.route) return@LaunchedEffect
        if (Destination.entries.any { it.route == initialRoute }) {
            // tab 路由：必须和点底栏走同一条路径，否则会污染 tab 的 save/restore 状态（见 switchTab）
            switchTab(initialRoute)
        } else {
            navController.navigate(initialRoute)
        }
    }

    val context = LocalContext.current
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())

    // 壁纸只认「课表」这一页；切到别的 tab 就不画
    val wallpaperActive = currentRoute == Destination.SCHEDULE.route &&
        prefs.backgroundPath != null
    val wallpaper = rememberScheduleBackground(
        context = context,
        path = prefs.backgroundPath.takeIf { wallpaperActive },
        version = prefs.backgroundVersion,
    )
    val transparentBar = wallpaperActive && wallpaper != null && prefs.wallpaperNavBar
    // dock 胶囊的透明度：壁纸模式尊重"透明导航栏"偏好（更透），其余页面 0.5 ——
    // 半透明 + 细描边 + 无阴影，是所有页面同一套配方。
    val dockAlpha = if (transparentBar) 0.35f else 0.5f

    Box(
        Modifier
            .fillMaxSize()
            // 悬浮胶囊四周露出的 window 底色默认纯白，和动态取色后的页面 surface
            // （带主题色调）对不上——尤其 dock 脚下那条，看着像"另一块白色"。
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ①～③ 壁纸图层：铺满整屏，包括顶部栏和底部导航栏的下面
        if (wallpaperActive && wallpaper != null) {
            WallpaperLayers(image = wallpaper, prefs = prefs)
        }

        // ④ 页面内容。外壳透明；**不挂 Scaffold.bottomBar** —— 那会把内容区
        // 扣掉 dock 高度，滚动到底就能看出底部"被占用了一块"。
        // dock 改为下面的覆盖层：内容全高铺满，滚动时从胶囊底下穿过，才是真悬浮。
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                NavHost(
                    navController = navController,
                    startDestination = Destination.TODAY.route,
                ) {
                    composable(Destination.TODAY.route) {
                        TodayScreen(
                            onOpenSchedule = { switchTab(Destination.SCHEDULE.route) },
                            onOpenFocus = { switchTab(Destination.FOCUS.route) },
                            onOpenFocusStats = { navController.navigate(AppRoutes.FOCUS_STATS) },
                            onOpenMore = { switchTab(Destination.CAMPUS.route) },
                            // 「演示数据」开关在课程表设置页里；横幅点进去直达那一页
                            onOpenDemoSettings = { navController.navigate(ROUTE_DISPLAY) },
                        )
                    }
                    composable(Destination.SCHEDULE.route) {
                        ScheduleScreen(
                            onOpenOverview = { semesterId ->
                                navController.navigate("$ROUTE_OVERVIEW/$semesterId")
                            },
                            onOpenDisplaySettings = { navController.navigate(ROUTE_DISPLAY) },
                        )
                    }
                    composable(Destination.FOCUS.route) {
                        FocusScreen(onOpenStats = { navController.navigate(AppRoutes.FOCUS_STATS) })
                    }
                    composable(Destination.CAMPUS.route) {
                        CampusScreen(
                            onOpenCampusServices = { navController.navigate(AppRoutes.CAMPUS_SERVICES) },
                            onOpenAbout = { navController.navigate(AppRoutes.ABOUT) },
                            onOpenAppearance = { navController.navigate(ROUTE_APPEARANCE) },
                            onOpenDisplaySettings = { navController.navigate(ROUTE_DISPLAY) },
                            onOpenFocusStats = { navController.navigate(AppRoutes.FOCUS_STATS) },
                        )
                    }
                    composable(AppRoutes.CAMPUS_SERVICES) {
                        CampusServicesScreen(
                            onBack = { navController.popBackStack() },
                            onOpenClassrooms = { navController.navigate(AppRoutes.FREE_CLASSROOMS) },
                            onOpenLibrary = { navController.navigate(AppRoutes.LIBRARY_SEAT) },
                            onOpenCampusCard = { navController.navigate(AppRoutes.CAMPUS_CARD) },
                            onOpenTangdaowanPower = { navController.navigate(AppRoutes.POWER_TANGDAOWAN) },
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
                    composable(AppRoutes.CAMPUS_CARD) {
                        CampusCardScreen(
                            onBack = { navController.popBackStack() },
                            onLogin = { navController.navigate(AppRoutes.CAMPUS_CARD_LOGIN) },
                        )
                    }
                    composable(AppRoutes.CAMPUS_CARD_LOGIN) {
                        CampusCardLoginScreen(
                            onBack = { navController.popBackStack() },
                            onLoggedIn = { navController.popBackStack() },
                        )
                    }
                    composable(AppRoutes.POWER_TANGDAOWAN) {
                        TangdaowanElectricityScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_APPEARANCE) {
                        AppearanceScreen(onBack = { navController.popBackStack() })
                    }
                    composable(AppRoutes.ABOUT) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = "$ROUTE_OVERVIEW/{semesterId}",
                        arguments = listOf(navArgument("semesterId") { type = NavType.IntType }),
                    ) { entry ->
                        val semesterId = entry.arguments?.getInt("semesterId") ?: return@composable
                        CourseOverviewScreen(
                            semesterId = semesterId,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(ROUTE_DISPLAY) {
                        DisplaySettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenCrop = { uri ->
                                navController.navigate("$ROUTE_BG_CROP/${Uri.encode(uri.toString())}")
                            },
                            onRecrop = {
                                navController.navigate("$ROUTE_BG_CROP/$CROP_SOURCE_SAVED")
                            },
                            onOpenOverview = { semesterId ->
                                navController.navigate("$ROUTE_OVERVIEW/$semesterId")
                            },
                        )
                    }
                    composable(
                        route = "$ROUTE_BG_CROP/{source}",
                        arguments = listOf(navArgument("source") { type = NavType.StringType }),
                    ) { entry ->
                        val raw = entry.arguments?.getString("source") ?: return@composable
                        BackgroundCropScreen(
                            sourceUri = if (raw == CROP_SOURCE_SAVED) {
                                null
                            } else {
                                Uri.parse(Uri.decode(raw))
                            },
                            useSavedSource = raw == CROP_SOURCE_SAVED,
                            // 裁剪完回设置页，能立刻看到缩略图和三个滑块的效果
                            onSaved = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(AppRoutes.FOCUS_STATS) {
                        FocusStatsScreen(onBack = { navController.popBackStack() })
                    }
                }

                // ⑤ 悬浮胶囊 dock —— 覆盖层（对齐内容 Box 的底部）：
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
