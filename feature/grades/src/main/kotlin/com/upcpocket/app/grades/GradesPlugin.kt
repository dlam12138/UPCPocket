package com.upcpocket.app.grades

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.upcauth.GradesAuthenticator

object GradesCapabilities {
    val read = capabilityKey<GradesService>("grades.read")
}

class GradesPlugin(
    context: Context,
    private val account: SchoolAccountReferenceReader,
    authenticator: GradesAuthenticator,
    private val openOfficialPage: (Context) -> Unit,
) : Plugin, PluginUiContributor {
    private val service = GradesService(account, authenticator, context.noBackupFilesDir)

    override val descriptor = PluginDescriptor(
        id = PluginId.parse("grades"),
        version = "1.0.0",
        capabilities = setOf(GradesCapabilities.read.id),
    )

    override val pluginId get() = descriptor.id
    override fun capabilities() = listOf(PluginCapability(GradesCapabilities.read, service))

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(PluginPage(ROUTE, pluginId) { host ->
            val context = LocalContext.current
            GradesScreen(
                account = account,
                service = service,
                onBack = host::back,
                onOpenAccount = host::openSchoolAccountSettings,
                onOpenOfficialPage = { openOfficialPage(context) },
            )
        })
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse(ROUTE.value),
                pluginId = pluginId,
                surface = UiSurface.SERVICE_DIRECTORY,
                order = 35,
                title = "成绩与学分",
                subtitle = "查看课程成绩、学分与绩点",
                icon = UiIcon.INSIGHTS,
                route = ROUTE,
                category = UiCategory.STUDY,
                keywords = "成绩查询 教务 学分 绩点 GPA 均分",
            ),
        )
    }

    companion object {
        val ROUTE = PluginRouteId.parse("grades.home")
    }
}
