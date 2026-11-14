package com.upcpocket.app.shiguang

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.*
import com.upcpocket.app.upcauth.*
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ShiguangScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun detailVerificationActionOpensLoginAndKeepsTheSelectedDetail() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val auth = ChallengeAuthenticator()
        val service = ShiguangService(Account(), auth, Api(), ownerScope, auth)
        val item = ShiguangActivity("synthetic-detail", ShiguangKind.ACTIVITY, "合成详情")
        service.detail(item)
        val challengedState = service.state.value
        assertEquals(ShiguangFailure.INTERACTION_REQUIRED, challengedState.error)
        assertEquals(item, challengedState.selected)

        rule.setContent {
            MaterialTheme {
                DetailContent(
                    state = challengedState,
                    scroll = rememberLazyListState(),
                    supportsInteractiveLogin = service.supportsInteractiveLogin,
                    onInteractiveLogin = service::openInteractiveLogin,
                    onOpenAccountSettings = {},
                )
            }
        }
        rule.onNodeWithText("前往验证").assertIsDisplayed().assertIsEnabled().performClick()

        val attempt = service.state.value.interactiveAttempt
        assertNotNull(attempt)
        assertEquals(item, service.state.value.selected)
        service.cancelInteractiveLogin(attempt!!.id)
        ownerScope.cancel()
    }

    @Test fun detailVerificationActionIsDisabledDuringBusyState() {
        rule.setContent {
            MaterialTheme {
                ShiguangInteractiveLoginAction(
                    state = ShiguangState(busy = true, interactionRequired = true),
                    onClick = {},
                )
            }
        }
        rule.onNodeWithText("前往验证").assertIsNotEnabled()
    }

    private class Account : SchoolAccountReferenceReader {
        override val state = MutableStateFlow(
            SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)),
        )
        override val accountReference = MutableStateFlow<SchoolAccountReference?>(
            SchoolAccountReference(SchoolAccountId("synthetic-detail"), AccountGeneration(1)),
        )
    }

    private class ChallengeAuthenticator : ShiguangAuthenticator, ShiguangInteractiveAuthenticator {
        override val entryUrl = UpcShiguangWebLoginPolicy.entryUrl

        override suspend fun authenticate(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long) =
            UpcShiguangAuthResult.Failure(UpcShiguangAuthFailure.INTERACTION_REQUIRED)

        override fun inspectWebNavigation(url: String, method: String, isMainFrame: Boolean, kind: ShiguangWebRequestKind, operation: DiagnosticOperation?) =
            if (url == entryUrl && method == "GET" && isMainFrame && kind == ShiguangWebRequestKind.NAVIGATION) {
                UpcShiguangWebNavigation.Allow
            } else {
                UpcShiguangWebNavigation.Block
            }

        override suspend fun completeWebLoginResult(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
            callbackUrl: String,
            operation: DiagnosticOperation?,
        ) = UpcShiguangAuthResult.Success(emptyList(), identityVerified = true)
    }

    private class Api : ShiguangDataSource {
        override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean) =
            ShiguangPage(page, 1, emptyList())

        override suspend fun detail(session: ShiguangSession, item: ShiguangActivity) =
            ShiguangDetail(item.title, null, emptyMap())

        override suspend fun summary(session: ShiguangSession) = ShiguangSummary(emptyList())
    }
}
