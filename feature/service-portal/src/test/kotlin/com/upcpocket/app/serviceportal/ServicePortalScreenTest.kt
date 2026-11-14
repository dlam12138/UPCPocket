package com.upcpocket.app.serviceportal

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.upcauth.ServicePortalAuthenticator
import com.upcpocket.app.upcauth.UpcServicePortalAuthResult
import com.upcpocket.app.upcauth.UpcServicePortalAuthSource
import com.upcpocket.app.upcauth.UpcServicePortalCookie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ServicePortalScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun cancelServiceScope() {
        serviceScope.cancel()
    }

    @Test
    fun syntheticOfflineFlowCoversCatalogGuideTodosAndApplications() {
        val service = ServicePortalService(SyntheticAccount(), SyntheticAuthenticator(), SyntheticApi(), serviceScope)
        rule.setContent {
            MaterialTheme {
                ServicePortalScreen(service, onBack = {}, onOpenAccountSettings = {})
            }
        }

        rule.waitUntil(timeoutMillis = 5_000) {
            !service.state.value.busy && service.state.value.results?.items?.isNotEmpty() == true
        }
        rule.onNodeWithText("合成办事事项").assertIsDisplayed().performClick()
        rule.onNodeWithText("合成指南").assertIsDisplayed()
        rule.onNodeWithText("合成办理说明").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("材料信息未提供。").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("返回事项").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("合成办事事项").assertIsDisplayed()

        rule.onNodeWithText("待处理").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            !service.state.value.busy && service.state.value.todos != null
        }
        rule.onNodeWithText("合成待处理").assertIsDisplayed()

        rule.onNodeWithText("在办").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            !service.state.value.busy && service.state.value.applications != null
        }
        rule.onNodeWithText("合成在办").assertIsDisplayed()
        rule.onNodeWithText("进度 0% · 1 个节点").assertIsDisplayed()
    }

    @Test
    fun outOfRangeSearchPageOffersAOneTapReturnToFirstPage() {
        var requestedPage = 0
        rule.setContent {
            MaterialTheme {
                ServiceSearchPageRecovery(onFirstPage = { requestedPage = 1 })
            }
        }

        rule.onNodeWithText("此页暂无事项").assertIsDisplayed()
        rule.onNodeWithText("回到第一页").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, requestedPage) }
    }

    private class SyntheticAccount : SchoolAccountReferenceReader {
        private val generation = AccountGeneration(1)
        private val reference = SchoolAccountReference(SchoolAccountId("fixture88"), generation)

        override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, generation))
        override val accountReference = MutableStateFlow<SchoolAccountReference?>(reference)
    }

    private class SyntheticAuthenticator : ServicePortalAuthenticator {
        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcServicePortalAuthResult = UpcServicePortalAuthResult.Success(
            binding = SchoolAccountReference(expectedAccountId, AccountGeneration(expectedCredentialGeneration)),
            cookies = listOf(
                UpcServicePortalCookie(
                    name = "syntheticSession",
                    value = "syntheticValue",
                    domain = UpcServicePortalCookie.HOST,
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    expiresAtEpochSeconds = null,
                    hostOnly = true,
                ),
            ),
            source = UpcServicePortalAuthSource.PASSWORD,
        )

        override fun clearSession() = Unit
    }

    private class SyntheticApi : ServicePortalDataSource {
        private val item = ServicePortalItem(
            rowKey = 0,
            detailId = 1,
            name = "合成办事事项",
            department = "合成部门",
            access = ServicePortalAccess.GUIDE_ONLY,
            externalHost = null,
            idAmbiguous = false,
        )

        override suspend fun catalog(session: ServicePortalSession?): ServicePortalCatalog = ServicePortalCatalog(
            items = listOf(item),
            folderCount = 0,
            source = if (session == null) ServicePortalScope.PUBLIC else ServicePortalScope.ACCOUNT,
        )

        override suspend fun guide(session: ServicePortalSession?, item: ServicePortalItem): ServicePortalGuide = ServicePortalGuide(
            title = "合成指南",
            department = "合成部门",
            description = "合成办理说明",
            descriptionTruncated = false,
            materials = emptyList(),
            materialsState = ServicePortalMaterialsState.MISSING,
            materialsTruncated = false,
            handlingPlace = null,
            handlingTimes = null,
            chargeType = null,
            charge = null,
            formCount = null,
            startNodes = emptyList(),
            access = item.access,
            externalHost = null,
            externalUrl = null,
            selectionMayBeAmbiguous = false,
            scope = if (session == null) ServicePortalScope.PUBLIC else ServicePortalScope.ACCOUNT,
        )

        override suspend fun todos(
            session: ServicePortalSession,
            page: Int,
            pageSize: Int,
        ): ServicePortalDataPage<ServicePortalTodo> = ServicePortalDataPage(
            total = 1,
            page = page,
            pageSize = pageSize,
            items = listOf(ServicePortalTodo("todo-$page", "合成待处理", "处理中", "材料核验", "2026-11-14", false)),
            hasMore = false,
            completeForPage = true,
            completeForScope = page == 1,
            pageInRange = true,
            scope = ServicePortalScope.ACCOUNT,
        )

        override suspend fun applications(
            session: ServicePortalSession,
            page: Int,
            pageSize: Int,
        ): ServicePortalDataPage<ServicePortalApplication> = ServicePortalDataPage(
            total = 1,
            page = page,
            pageSize = pageSize,
            items = listOf(
                ServicePortalApplication(
                    rowKey = "application-$page",
                    title = "合成在办",
                    status = "办理中",
                    statusCode = 1,
                    createdAt = "2026-11-14",
                    thirdParty = false,
                    progress = ServicePortalProgress(percent = 0, nodeCount = 1),
                ),
            ),
            hasMore = false,
            completeForPage = true,
            completeForScope = page == 1,
            pageInRange = true,
            scope = ServicePortalScope.ACCOUNT,
        )
    }
}
