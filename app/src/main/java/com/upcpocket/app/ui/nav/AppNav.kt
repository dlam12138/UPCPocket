package com.upcpocket.app.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
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
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.power.POWER_TANGDAOWAN_ROUTE
import com.upcpocket.app.ui.about.AboutScreen
import com.upcpocket.app.ui.home.HomeScreen
import com.upcpocket.app.ui.official.OfficialWebScreen
import com.upcpocket.app.ui.settings.AppearanceScreen
import com.upcpocket.app.ui.settings.SettingsScreen
import com.upcpocket.app.ui.settings.WidgetSettingsScreen
import com.upcpocket.app.widget.WidgetLaunchTarget

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val outlinedIcon: ImageVector,
    val order: Int,
)

private val hostHome = Destination(
    route = AppRoutes.HOME,
    label = "首页",
    icon = Icons.Filled.Home,
    outlinedIcon = Icons.Outlined.Home,
    order = 0,
)

/**
 * 宿主只固定首页；课表与专注 tab 来自已激活插件。
 *
 * M5 的三个顶级目的地是「首页 / 课表 / 专注」。设置不属于导航层级，统一从首页
 * 右上角进入，账号、主题、组件与关于不再散落在“更多”和插件设置区。
 */
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

    val pluginDestinations = pluginUi.bottomNav.map { tab ->
        Destination(
            route = tab.route.value,
            label = tab.label,
            icon = tab.icon.filledIcon(),
            outlinedIcon = tab.icon.outlinedIcon(),
            order = tab.order,
        )
    }.sortedWith(compareBy({ it.order }, { it.route }))
    // 首页由宿主固定在最左，即使未来某个插件贡献了更小的 order 也不能抢占宿主 tab。
    val destinations = listOf(hostHome) + pluginDestinations
    val showBottomBar = destinations.any { it.route == currentRoute }

    val pageHost = remember(navController) {
        object : PluginPageHost {
            override fun open(route: PluginRouteId) {
                navController.navigate(route.value) { launchSingleTop = true }
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

    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openSettings() {
        navController.navigate(AppRoutes.SETTINGS) { launchSingleTop = true }
    }

    // 桌面组件的固定入口：校园卡回首页（余额功能区所在），电费直接进唐岛湾原生查询页。
    // 只在消费一次后清空，避免重组时反复导航；不接受任意路由或 Intent 转发。
    LaunchedEffect(launchTarget) {
        when (launchTarget) {
            WidgetLaunchTarget.CARD_BALANCE -> {
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp,
                    ) {
                        destinations.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { switchTab(destination.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) {
                                            destination.icon
                                        } else {
                                            destination.outlinedIcon
                                        },
                                        contentDescription = destination.label,
                                    )
                                },
                                label = {
                                    Text(
                                        destination.label,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier.testTag("tab_${destination.route}"),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoutes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(AppRoutes.HOME) {
                HomeScreen(
                    serviceEntries = pluginUi.entriesFor(UiSurface.HOME_SERVICES),
                    cardSections = pluginUi.sectionsFor(UiSurface.HOME_CARD),
                    sectionHost = sectionHost,
                    onOpenRoute = pageHost::open,
                    onOpenSettings = ::openSettings,
                    onOpenOfficialWeb = { navController.navigate(AppRoutes.OFFICIAL_WEB) },
                )
            }
            pluginUi.pages.forEach { page ->
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
    }
}
