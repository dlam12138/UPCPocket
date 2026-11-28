package com.upcpocket.app.grades.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class GradesScreenContentTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val terms = listOf(
        GradesTerm(id = "t1", label = "合成学期甲"),
        GradesTerm(id = "t2", label = "合成学期乙"),
        GradesTerm(id = "t3", label = "合成学期丙", isSynced = false),
        GradesTerm(id = "t4", label = "合成学期丁", syncFailed = true),
    )

    private val courses = listOf(
        course("c1", "t1", "合成课程甲", "92", GradesScore.Percent(92.0), 3.0,
            GradesCourseNature("必修", isRequired = true)),
        course("c2", "t1", "合成课程乙", "良好", GradesScore.Level(GradesLevel.GOOD), 2.0,
            GradesCourseNature("必修", isRequired = true)),
        course("c3", "t1", "合成课程丙", "55", GradesScore.Percent(55.0), 1.5,
            GradesCourseNature("选修", isRequired = false)),
        course("c4", "t2", "合成课程丁", "88", GradesScore.Percent(88.0), 2.5,
            GradesCourseNature("必修", isRequired = true)),
        course("c5", "t2", "合成课程戊", "77", GradesScore.Percent(77.0), 2.0, null),
        course("c6", "t2", "合成课程己", "优秀", GradesScore.Level(GradesLevel.EXCELLENT), 3.0,
            GradesCourseNature("必修", isRequired = true), retakeAmbiguous = true),
        course("c8", "t4", "合成课程庚", "通过", GradesScore.PassFail(passed = true), 1.0,
            GradesCourseNature("必修", isRequired = true)),
    )

    private fun course(
        id: String,
        termId: String,
        name: String,
        rawGrade: String,
        score: GradesScore,
        credits: Double?,
        nature: GradesCourseNature?,
        retakeAmbiguous: Boolean = false,
    ) = GradesCourse(
        id = id,
        termId = termId,
        name = name,
        rawGrade = rawGrade,
        score = score,
        credits = credits,
        nature = nature,
        retakeAmbiguous = retakeAmbiguous,
    )

    private fun readyState(
        account: GradesAccountState = GradesAccountState.READY,
        sync: GradesSyncState = GradesSyncState.Ready(),
        termList: List<GradesTerm> = terms,
        courseList: List<GradesCourse> = courses,
        unsavedChanges: Boolean = false,
    ) = GradesUiState(
        account = account,
        sync = sync,
        terms = termList,
        courses = courseList,
        unsavedChanges = unsavedChanges,
    )

    private fun mixedTermState(
        sync: GradesSyncState = GradesSyncState.Failed(GradesSyncFailure.SERVER),
    ) = readyState(
        sync = sync,
        termList = listOf(
            GradesTerm("t1", "合成学期甲"),
            GradesTerm("t2", "合成学期乙", syncFailed = true),
            GradesTerm("t3", "合成已同步空学期"),
            GradesTerm("t4", "合成首次失败学期", isSynced = false, syncFailed = true),
            GradesTerm("t5", "合成未同步学期", isSynced = false),
            GradesTerm("t6", "合成第二个空学期"),
        ),
        courseList = courses.map { course ->
            if (course.id == "c8") course.copy(termId = "t2") else course
        },
    )

    private fun render(
        state: GradesUiState = readyState(),
        onSync: () -> Unit = {},
        onOpenAccount: () -> Unit = {},
        onOpenOfficialPage: () -> Unit = {},
        fontScale: Float? = null,
    ) {
        rule.setContent {
            MaterialTheme {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides if (fontScale == null) {
                        baseDensity
                    } else {
                        Density(density = baseDensity.density, fontScale = fontScale)
                    },
                ) {
                    GradesScreenContent(
                        state = state,
                        onBack = {},
                        onSync = onSync,
                        onOpenAccount = onOpenAccount,
                        onOpenOfficialPage = onOpenOfficialPage,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun scrollOverviewTo(tag: String) {
        rule.onNodeWithTag("grades_overview_list")
            .performScrollToNode(hasTestTag(tag))
    }

    private fun scrollDetailTo(tag: String) {
        rule.onNodeWithTag("grades_detail_list")
            .performScrollToNode(hasTestTag(tag))
    }

    private fun selectTab(label: String) {
        val tab = rule.onNode(hasText(label) and hasClickAction())
        tab.performSemanticsAction(SemanticsActions.OnClick)
        tab.assertIsSelected()
    }

    private fun emptyStateText(text: String) = rule.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag("grades_empty_state")),
        useUnmergedTree = true,
    )

    private fun detailEmptyText(text: String) = rule.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag("grades_detail_empty")),
        useUnmergedTree = true,
    )

    private fun boundsOf(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun overviewShowsMetricsPendingAndSourceScope() {
        render()

        rule.onNodeWithText("成绩与学分").assertIsDisplayed()
        rule.onNodeWithText("同步未完成").assertIsDisplayed()

        rule.onNodeWithTag("grades_metric_总 GPA").assertIsDisplayed()
        rule.onNodeWithText("3.13").assertIsDisplayed()
        rule.onNodeWithText("均分 81.9").assertIsDisplayed()

        rule.onNodeWithTag("grades_metric_已获学分").assertIsDisplayed()
        rule.onNodeWithText("10.5").assertIsDisplayed()
        rule.onNodeWithText("修读 12").assertIsDisplayed()

        rule.onNodeWithTag("grades_metric_必修 GPA").assertIsDisplayed()
        rule.onNodeWithText("待核").assertIsDisplayed()
        rule.onNodeWithText("性质待核").assertIsDisplayed()

        rule.onNodeWithTag("grades_metric_通过率").assertIsDisplayed()
        rule.onNodeWithText("83.3%").assertIsDisplayed()
        rule.onNodeWithText("5/6 门").assertIsDisplayed()

        rule.onNodeWithTag("grades_metric_学分加权均分").assertDoesNotExist()
        rule.onNodeWithTag("grades_metric_修读学分").assertDoesNotExist()

        scrollOverviewTo("grades_pending_note")
        rule.onNodeWithText("待核 1 门").assertIsDisplayed()

        scrollOverviewTo("grades_stats_note")
        rule.onNodeWithText(
            "统计基于本机已同步的本科成绩记录，不代表学校学籍结果。",
        ).assertIsDisplayed()
    }

    @Test
    fun metricGridUsesTwoColumnsOnNarrowScreens() {
        render()

        val total = boundsOf("grades_metric_总 GPA")
        val earned = boundsOf("grades_metric_已获学分")
        val required = boundsOf("grades_metric_必修 GPA")
        val passRate = boundsOf("grades_metric_通过率")

        assertEquals(total.top.toDouble(), earned.top.toDouble(), 0.5)
        assertEquals(required.top.toDouble(), passRate.top.toDouble(), 0.5)
        assertTrue("必修 GPA 应换到第二行", required.top > total.top)
        assertTrue("已获学分应在总 GPA 右侧", earned.left > total.left)
        assertTrue("通过率应在必修 GPA 右侧", passRate.left > required.left)
    }

    @Test
    @Config(sdk = [31], qualifiers = "w393dp-h800dp")
    fun metricGridUsesSingleRowOnCommonPhoneWidth() {
        render()

        val total = boundsOf("grades_metric_总 GPA")
        val earned = boundsOf("grades_metric_已获学分")
        val required = boundsOf("grades_metric_必修 GPA")
        val passRate = boundsOf("grades_metric_通过率")

        assertEquals(total.top.toDouble(), earned.top.toDouble(), 0.5)
        assertEquals(total.top.toDouble(), required.top.toDouble(), 0.5)
        assertEquals(total.top.toDouble(), passRate.top.toDouble(), 0.5)
        assertTrue(earned.left > total.left)
        assertTrue(required.left > earned.left)
        assertTrue(passRate.left > required.left)
    }

    @Test
    @Config(sdk = [31], qualifiers = "w393dp-h800dp")
    fun metricGridFallsBackToTwoColumnsWithLargerFontScale() {
        render(fontScale = 1.5f)

        val total = boundsOf("grades_metric_总 GPA")
        val earned = boundsOf("grades_metric_已获学分")
        val required = boundsOf("grades_metric_必修 GPA")
        val passRate = boundsOf("grades_metric_通过率")

        assertEquals(total.top.toDouble(), earned.top.toDouble(), 0.5)
        assertEquals(required.top.toDouble(), passRate.top.toDouble(), 0.5)
        assertTrue("必修 GPA 应换到第二行", required.top > total.top)
    }

    @Test
    fun termRowOpensMatchingScopeAndClearShowsAllRecords() {
        render()

        scrollOverviewTo("grades_term_row_t1")
        rule.onNodeWithTag("grades_term_row_t1").performClick()

        rule.onNodeWithText("筛选：合成学期甲").assertIsDisplayed()
        rule.onNodeWithTag("grades_metric_总 GPA").assertIsDisplayed()
        rule.onNodeWithText("3.02").assertIsDisplayed()
        rule.onNodeWithText("均分 81.3").assertIsDisplayed()
        rule.onNodeWithText("共 3 门").assertIsDisplayed()
        rule.onNodeWithText("仅显示已计入统计的记录。").assertIsDisplayed()

        rule.onNodeWithText("清除筛选").performClick()
        rule.onNodeWithText("全部成绩记录").assertIsDisplayed()
        rule.onNodeWithText("共 7 门").assertIsDisplayed()
        rule.onNodeWithText("待核 1 门").assertIsDisplayed()

        scrollDetailTo("grades_course_c6")
        rule.onNodeWithText("优秀").assertIsDisplayed()
        rule.onNodeWithTag("grades_course_status_c6")
            .assertTextEquals("未计入统计 · 同名重修待核")

        scrollDetailTo("grades_course_c2")
        rule.onNodeWithText("良好").assertIsDisplayed()
        rule.onNodeWithTag("grades_course_status_c2")
            .assertTextEquals("已计入统计")
    }

    @Test
    fun natureRowDetailMatchesChartScopeAndCanIncludePending() {
        render()

        scrollOverviewTo("grades_nature_row_必修")
        rule.onNodeWithTag("grades_nature_row_必修").performClick()

        rule.onNodeWithText("筛选：必修").assertIsDisplayed()
        rule.onNodeWithText("共 4 门").assertIsDisplayed()
        rule.onNodeWithText("仅显示已计入统计的记录。").assertIsDisplayed()

        scrollDetailTo("grades_course_c2")
        rule.onNodeWithText("良好").assertIsDisplayed()
        rule.onNodeWithTag("grades_course_status_c2").assertTextEquals("已计入统计")
        rule.onNodeWithTag("grades_course_c6").assertDoesNotExist()

        rule.onNodeWithTag("grades_detail_list").performScrollToIndex(0)
        rule.onNodeWithText("含待核记录 1 门").assertIsDisplayed().performClick()

        rule.onNodeWithText("共 5 门").assertIsDisplayed()
        scrollDetailTo("grades_course_c6")
        rule.onNodeWithText("优秀").assertIsDisplayed()
        rule.onNodeWithTag("grades_course_status_c6")
            .assertTextEquals("未计入统计 · 同名重修待核")
    }

    @Test
    fun bandRowOpensDetailForExplicitPercentageBand() {
        render()

        scrollOverviewTo("grades_band_row_SCORE_80_89")
        rule.onNodeWithTag("grades_band_row_SCORE_80_89").performClick()

        rule.onNodeWithText("筛选：分数 80–89").assertIsDisplayed()
        rule.onNodeWithText("共 3 门").assertIsDisplayed()

        scrollDetailTo("grades_course_c8")
        rule.onNodeWithTag("grades_course_status_c8").assertTextEquals("已计入统计")

        rule.onNodeWithTag("grades_course_c1").assertDoesNotExist()
    }

    @Test
    fun authFailureKeepsRecordsAndOpensOfficialPage() {
        var openedOfficialPage = 0
        render(
            state = readyState(sync = GradesSyncState.Failed(GradesSyncFailure.AUTH)),
            onOpenOfficialPage = { openedOfficialPage++ },
        )

        rule.onNodeWithTag("grades_sync_detail").assertTextEquals(
            "学校登录已失效 · 1 个学期同步失败，已保留已同步记录；1 个学期尚未同步",
        )
        rule.onNodeWithText("3/4 个学期已同步 · 7 门课程 · 1 个学期同步失败")
            .assertIsDisplayed()
        rule.onNodeWithTag("grades_sync_action").assertIsEnabled()

        rule.onNodeWithText("打开学校成绩网页").assertIsDisplayed().performClick()
        assertEquals(1, openedOfficialPage)

        rule.onNodeWithTag("grades_metric_总 GPA").assertIsDisplayed()
        rule.onNodeWithText("3.13").assertIsDisplayed()

        scrollOverviewTo("grades_term_row_t4")
        rule.onNodeWithText(
            "上次同步失败，数字来自已同步记录。",
        ).assertIsDisplayed()
    }

    @Test
    fun interactionFailureOffersOfficialEntry() {
        var openedOfficialPage = 0
        render(
            state = readyState(sync = GradesSyncState.Failed(GradesSyncFailure.INTERACTION)),
            onOpenOfficialPage = { openedOfficialPage++ },
        )

        rule.onNodeWithTag("grades_sync_detail").assertTextEquals(
            "学校要求交互验证 · 1 个学期同步失败，已保留已同步记录；1 个学期尚未同步",
        )
        rule.onNodeWithTag("grades_official_action").assertIsDisplayed().performClick()
        assertEquals(1, openedOfficialPage)
    }

    @Test
    fun storageFailureWithoutRecordsCanRetry() {
        var syncs = 0
        render(
            state = GradesUiState(sync = GradesSyncState.Failed(GradesSyncFailure.STORAGE)),
            onSync = { syncs++ },
        )

        rule.onNodeWithText("本机成绩读取或保存失败。未获取到成绩，请重新同步。")
            .assertIsDisplayed()
        rule.onNodeWithTag("grades_official_action").assertDoesNotExist()
        rule.onNodeWithText("重试").performClick()
        assertEquals(1, syncs)
    }

    @Test
    fun partialFailureAndSaveFailureShowBothHints() {
        val state = mutableStateOf(
            readyState(
                sync = GradesSyncState.Failed(GradesSyncFailure.SERVER),
                unsavedChanges = true,
            ),
        )
        rule.setContent {
            MaterialTheme {
                GradesScreenContent(state = state.value, onBack = {}, onSync = {})
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("3/4 个学期已同步 · 7 门课程 · 1 个学期同步失败")
            .assertIsDisplayed()
        rule.onNodeWithTag("grades_sync_detail").assertTextEquals(
            "教务系统暂时不可用 · 1 个学期同步失败，已保留已同步记录；" +
                "1 个学期尚未同步；" +
                "本次更新未保存，重启后会恢复到上次保存的成绩",
        )

        // 部分覆盖有结果但课程全部为空：未保存提示仍须可见，不能显示成完成后的空结果。
        rule.runOnIdle {
            state.value = readyState(
                sync = GradesSyncState.Failed(GradesSyncFailure.SERVER),
                courseList = emptyList(),
                unsavedChanges = true,
            )
        }
        rule.onNodeWithTag("grades_sync_detail").assertTextEquals(
            "教务系统暂时不可用 · 请稍后重试；1 个学期同步失败且无记录；" +
                "1 个学期尚未同步；本次更新未保存，重启后会恢复到上次保存的成绩",
        )
        emptyStateText(
            "教务系统暂时不可用。未获取到成绩，请稍后重试。 " +
                "1 个学期同步失败且无记录；1 个学期尚未同步；2 个学期已同步但无记录。",
        ).assertIsDisplayed()
        emptyStateText("暂无成绩记录").assertDoesNotExist()

        // 同步完成但结果未写入本机时，也不能表现为已保存。
        rule.runOnIdle { state.value = readyState(unsavedChanges = true) }
        rule.onNodeWithText(
            "1 个学期尚未同步；本次更新未保存，重启后会恢复到上次保存的成绩",
        )
            .assertIsDisplayed()
    }

    @Test
    fun emptyStatesDistinguishLoadingNotSyncedFailureAndCompletedEmpty() {
        val state = mutableStateOf(GradesUiState(sync = GradesSyncState.Loading))
        rule.setContent {
            MaterialTheme {
                GradesScreenContent(state = state.value, onBack = {}, onSync = {})
            }
        }

        rule.onNodeWithText("正在读取成绩").assertIsDisplayed()

        rule.runOnIdle { state.value = GradesUiState(sync = GradesSyncState.NotSynced) }
        rule.onNodeWithText("尚未同步成绩").assertIsDisplayed()
        rule.onNodeWithText("未同步").assertIsDisplayed()

        rule.runOnIdle {
            state.value = GradesUiState(sync = GradesSyncState.Failed(GradesSyncFailure.NETWORK))
        }
        rule.onNodeWithText("网络不可用。未获取到成绩，请稍后重试。").assertIsDisplayed()
        emptyStateText("同步失败").assertIsDisplayed()

        rule.runOnIdle { state.value = GradesUiState(sync = GradesSyncState.Ready()) }
        rule.onNodeWithText("状态未知").assertIsDisplayed()
        rule.onNodeWithText("学期状态未知").assertIsDisplayed()
        rule.onNodeWithText("教务没有返回学期列表或成绩记录。").assertIsDisplayed()

        rule.runOnIdle {
            state.value = GradesUiState(
                sync = GradesSyncState.Ready(),
                terms = listOf(GradesTerm("b1", "合成已同步空学期")),
            )
        }
        emptyStateText("暂无成绩记录").assertIsDisplayed()
        emptyStateText("教务系统没有返回本科成绩记录。").assertIsDisplayed()

        rule.runOnIdle {
            state.value = GradesUiState(
                sync = GradesSyncState.Ready(),
                terms = listOf(GradesTerm("u1", "合成未同步学期", isSynced = false)),
            )
        }
        emptyStateText("学期成绩尚未同步").assertIsDisplayed()

        rule.runOnIdle {
            state.value = GradesUiState(
                sync = GradesSyncState.Ready(),
                terms = listOf(
                    GradesTerm("f1", "合成失败学期", isSynced = false, syncFailed = true),
                ),
            )
        }
        emptyStateText("学期成绩同步失败").assertIsDisplayed()
        emptyStateText("1 个学期同步失败且无记录；0 个学期尚未同步；0 个学期已同步但无记录。")
            .assertIsDisplayed()

        rule.runOnIdle {
            state.value = GradesUiState(
                sync = GradesSyncState.Failed(GradesSyncFailure.SERVER),
                terms = listOf(GradesTerm("all-failed", "合成全部失败学期", syncFailed = true)),
            )
        }
        emptyStateText("学期成绩同步失败").assertIsDisplayed()
        emptyStateText("教务系统暂时不可用。未获取到成绩，请稍后重试。 1 个学期同步失败且无记录。")
            .assertIsDisplayed()
    }

    @Test
    fun accountLockStatesAreDistinctAndBlockSync() {
        var openedAccount = 0
        val account = mutableStateOf(GradesAccountState.ACCOUNT_REQUIRED)
        rule.setContent {
            MaterialTheme {
                GradesScreenContent(
                    state = GradesUiState(
                        account = account.value,
                        sync = GradesSyncState.NotSynced,
                    ),
                    onBack = {},
                    onSync = {},
                    onOpenAccount = { openedAccount++ },
                )
            }
        }

        rule.onNodeWithText("保存学校账号后可同步教务成绩。").assertIsDisplayed()
        rule.onNodeWithTag("grades_sync_action").assertIsNotEnabled()
        rule.onNodeWithTag("grades_account_action").performClick()
        assertEquals(1, openedAccount)

        rule.runOnIdle { account.value = GradesAccountState.RESTORING }
        rule.onNodeWithText("恢复完成后可同步教务成绩。").assertIsDisplayed()
        rule.onNodeWithTag("grades_account_action").assertDoesNotExist()
        rule.onNodeWithTag("grades_sync_action").assertIsNotEnabled()

        rule.runOnIdle { account.value = GradesAccountState.CLEANING }
        rule.onNodeWithText("清理完成后可重新设置账号并同步。").assertIsDisplayed()
        rule.onNodeWithTag("grades_account_action").assertDoesNotExist()
        rule.onNodeWithTag("grades_sync_action").assertIsNotEnabled()

        rule.runOnIdle { account.value = GradesAccountState.READY }
        rule.onNodeWithTag("grades_sync_action").assertIsEnabled()
    }

    @Test
    fun accountRequiredStillShowsHistoryButCannotSync() {
        render(state = readyState(account = GradesAccountState.ACCOUNT_REQUIRED))

        rule.onNodeWithText("已有 7 门历史记录仍可查看", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("grades_sync_action").assertIsNotEnabled()

        rule.onNodeWithTag("grades_metric_总 GPA").assertIsDisplayed()
        rule.onNodeWithText("3.13").assertIsDisplayed()
    }

    @Test
    fun unsyncedAndEmptyTermStatesAreDistinct() {
        val state = readyState(
            termList = terms + GradesTerm("t5", "合成学期空") +
                GradesTerm("t6", "合成首次失败学期", isSynced = false, syncFailed = true),
        )
        render(state = state)

        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()

        rule.onNodeWithTag("grades_term_chip_t3").performScrollTo().performClick()
        rule.onNodeWithText("该学期尚未同步。").assertIsDisplayed()
        rule.onNodeWithText("该学期尚未同步").assertIsDisplayed()

        rule.onNodeWithTag("grades_term_chip_t4").performScrollTo().performClick()
        rule.onNodeWithText(
            "该学期上次同步失败，数字来自已同步记录。",
        ).assertIsDisplayed()

        rule.onNodeWithTag("grades_term_chip_t5").performScrollTo().performClick()
        rule.onNodeWithText("该学期暂无成绩记录").assertIsDisplayed()
        rule.onNodeWithText("可切换其他学期或查看全部学期。").assertIsDisplayed()
    }

    @Test
    fun noRecordTermsAreHiddenByDefaultAndExpansionKeepsFullStatistics() {
        var syncs = 0
        render(state = mixedTermState(), onSync = { syncs++ })

        rule.onNodeWithTag("grades_term_chip_t1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("grades_term_chip_t2").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("grades_term_chip_t3").assertDoesNotExist()
        rule.onNodeWithTag("grades_term_chip_t4").assertDoesNotExist()
        rule.onNodeWithTag("grades_term_chip_t5").assertDoesNotExist()
        rule.onNodeWithTag("grades_term_chip_t6").assertDoesNotExist()
        scrollOverviewTo("grades_term_row_t1")
        scrollOverviewTo("grades_term_row_t2")
        rule.onNodeWithTag("grades_term_row_t3").assertDoesNotExist()
        rule.onNodeWithTag("grades_term_row_t4").assertDoesNotExist()
        rule.onNodeWithTag("grades_term_row_t5").assertDoesNotExist()
        rule.onNodeWithTag("grades_term_row_t6").assertDoesNotExist()

        rule.onNodeWithText("4/6 个学期已同步 · 7 门课程 · 2 个学期同步失败")
            .assertIsDisplayed()
        rule.onNodeWithText("3.13").assertIsDisplayed()
        rule.onNodeWithTag("grades_toggle_empty_terms")
            .assertIsDisplayed()
            .performClick()

        rule.onNodeWithText("隐藏无记录学期").assertIsDisplayed()
        listOf("t1", "t2", "t3", "t4", "t5", "t6").forEach { id ->
            rule.onNodeWithTag("grades_term_chip_$id").performScrollTo().assertIsDisplayed()
        }
        listOf("t1", "t2", "t3", "t4", "t5", "t6").forEach { id ->
            scrollOverviewTo("grades_term_row_$id")
        }
        rule.onNodeWithText("4/6 个学期已同步 · 7 门课程 · 2 个学期同步失败")
            .assertIsDisplayed()
        rule.onNodeWithText("3.13").assertIsDisplayed()
        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()
        rule.onNodeWithTag("grades_term_chip_t3").assertDoesNotExist()
        rule.onNodeWithText("4/6 个学期已同步 · 7 门课程 · 2 个学期同步失败")
            .assertIsDisplayed()
        assertEquals(0, syncs)
    }

    @Test
    fun readySyncCallsOutFailedAndUnsyncedEmptyTermsAlongsideUnsavedHint() {
        render(
            state = mixedTermState(sync = GradesSyncState.Ready()).copy(unsavedChanges = true),
        )

        rule.onNodeWithText("同步未完成").assertIsDisplayed()
        rule.onNodeWithText(
            "1 个学期同步失败且无记录；1 个学期尚未同步；" +
                "本次更新未保存，重启后会恢复到上次保存的成绩",
        ).assertIsDisplayed()
        rule.onNodeWithText("6 个学期 · 7 门课程").assertIsDisplayed()
        rule.onNodeWithText("3.13").assertIsDisplayed()
    }

    @Test
    fun collapsingSelectedEmptyTermReturnsToAllAndDoesNotRestoreItWhenExpandedAgain() {
        var syncs = 0
        render(state = mixedTermState(), onSync = { syncs++ })

        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()
        scrollOverviewTo("grades_term_row_t3")
        rule.onNodeWithTag("grades_term_row_t3").performClick()
        rule.onNodeWithText("筛选：合成已同步空学期").assertIsDisplayed()
        rule.onNodeWithText("该学期暂无成绩记录").assertIsDisplayed()
        rule.onNodeWithText("仅显示已计入统计的记录。").assertIsDisplayed()

        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()
        rule.onNodeWithTag("grades_metric_总 GPA").assertIsDisplayed()
        rule.onNodeWithText("3.13").assertIsDisplayed()
        rule.onNodeWithText("全部成绩记录").assertIsDisplayed()
        rule.onNodeWithText("共 7 门").assertIsDisplayed()
        rule.onNodeWithText("待核 1 门").assertIsDisplayed()
        scrollDetailTo("grades_course_c6")
        rule.onNodeWithTag("grades_course_c6").assertIsDisplayed()
        rule.onNodeWithTag("grades_course_status_c6")
            .assertTextEquals("未计入统计 · 同名重修待核")

        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()
        rule.onNodeWithTag("grades_detail_list").performScrollToIndex(0)
        rule.onNodeWithText("全部成绩记录").assertIsDisplayed()
        rule.onNodeWithText("共 7 门").assertIsDisplayed()
        rule.onNodeWithText("筛选：合成已同步空学期").assertDoesNotExist()
        scrollDetailTo("grades_course_c6")
        rule.onNodeWithTag("grades_course_status_c6")
            .assertTextEquals("未计入统计 · 同名重修待核")
        assertEquals(0, syncs)
    }

    @Test
    fun missingSelectedTermIsKeptDuringLoadingThenClearedAfterRefreshRemovesIt() {
        val state = mutableStateOf(mixedTermState())
        rule.setContent {
            MaterialTheme {
                GradesScreenContent(state = state.value, onBack = {}, onSync = {})
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()
        selectTab("课程明细")
        rule.onNodeWithTag("grades_detail_list").assertIsDisplayed()
        rule.onNodeWithTag("grades_term_chip_t3").performScrollTo().performClick()
        rule.onNodeWithTag("grades_term_chip_t3").assertIsSelected()
        rule.onNodeWithText("筛选：合成已同步空学期").assertIsDisplayed()

        rule.runOnIdle {
            state.value = GradesUiState(sync = GradesSyncState.Loading)
        }
        rule.runOnIdle { state.value = mixedTermState() }
        rule.onNodeWithText("筛选：合成已同步空学期").assertIsDisplayed()
        rule.onNodeWithText("该学期暂无成绩记录").assertIsDisplayed()

        rule.runOnIdle {
            state.value = state.value.copy(terms = state.value.terms.filterNot { it.id == "t3" })
        }
        rule.waitForIdle()
        rule.onNodeWithText("全部成绩记录").assertIsDisplayed()
        rule.onNodeWithText("共 7 门").assertIsDisplayed()
        rule.onNodeWithText("3.13").assertIsDisplayed()
    }

    @Test
    fun emptyTermExpansionAndSelectionSurviveStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                GradesScreenContent(
                    state = mixedTermState(),
                    onBack = {},
                    onSync = {},
                )
            }
        }

        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()
        selectTab("课程明细")
        rule.onNodeWithTag("grades_detail_list").assertIsDisplayed()
        rule.onNodeWithTag("grades_term_chip_t3").performScrollTo().performClick()
        rule.onNodeWithTag("grades_term_chip_t3").assertIsSelected()
        rule.onNodeWithText("筛选：合成已同步空学期").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNodeWithText("隐藏无记录学期").assertIsDisplayed()
        rule.onNodeWithText("筛选：合成已同步空学期").assertIsDisplayed()
        rule.onNodeWithText("该学期暂无成绩记录").assertIsDisplayed()
    }

    @Test
    fun wholePageEmptyStateExplainsMixedTermStatusesAndCanExpandTheirList() {
        var syncs = 0
        render(
            state = readyState(
                termList = listOf(
                    GradesTerm("b", "合成已同步空学期"),
                    GradesTerm("c", "合成首次失败学期", isSynced = false, syncFailed = true),
                    GradesTerm("d", "合成未同步学期", isSynced = false),
                ),
                courseList = emptyList(),
            ),
            onSync = { syncs++ },
        )

        rule.onNodeWithText("学期成绩同步未完成").assertIsDisplayed()
        rule.onNodeWithText(
            "1 个学期同步失败且无记录；1 个学期尚未同步；1 个学期已同步但无记录。",
        ).assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed()
        rule.onNodeWithTag("grades_term_chip_b").assertDoesNotExist()
        rule.onNodeWithTag("grades_toggle_empty_terms")
            .assertIsDisplayed()
            .performClick()
        rule.onNodeWithTag("grades_empty_term_b").assertIsDisplayed()
        rule.onNodeWithTag("grades_empty_term_c").assertIsDisplayed()
        rule.onNodeWithTag("grades_empty_term_d").assertIsDisplayed()
        rule.onNodeWithText("已同步，无成绩记录").assertIsDisplayed()
        rule.onNodeWithText("同步失败").assertIsDisplayed()
        rule.onNodeWithText("尚未同步").assertIsDisplayed()
        rule.onNodeWithText("隐藏无记录学期").assertIsDisplayed()
        assertEquals(0, syncs)
    }

    @Test
    fun failedWholePageEmptyStateCanExpandTermsAndKeepsFailureAndUnsavedMessages() {
        val state = readyState(
            sync = GradesSyncState.Failed(GradesSyncFailure.NETWORK),
            termList = listOf(
                GradesTerm("b", "合成已同步空学期"),
                GradesTerm("c", "合成首次失败学期", isSynced = false, syncFailed = true),
                GradesTerm("d", "合成未同步学期", isSynced = false),
            ),
            courseList = emptyList(),
            unsavedChanges = true,
        )
        render(state = state)

        rule.onNodeWithTag("grades_sync_summary").assertTextEquals("未获取到成绩")
        rule.onNodeWithTag("grades_sync_detail").assertTextEquals(
            "网络不可用 · 请稍后重试；1 个学期同步失败且无记录；" +
                "1 个学期尚未同步；本次更新未保存，重启后会恢复到上次保存的成绩",
        )
        emptyStateText("学期成绩同步未完成").assertIsDisplayed()
        emptyStateText(
            "网络不可用。未获取到成绩，请稍后重试。 " +
                "1 个学期同步失败且无记录；1 个学期尚未同步；1 个学期已同步但无记录。",
        ).assertIsDisplayed()
        emptyStateText("重试").assertIsDisplayed()
        rule.onNodeWithTag("grades_official_action").assertDoesNotExist()
        rule.onNodeWithTag("grades_empty_term_b").assertDoesNotExist()
        rule.onNodeWithText("显示无记录学期（3）").assertIsDisplayed().performClick()

        rule.onNodeWithTag("grades_empty_term_b").assertIsDisplayed()
        rule.onNodeWithTag("grades_empty_term_c").assertIsDisplayed()
        rule.onNodeWithTag("grades_empty_term_d").assertIsDisplayed()
        emptyStateText("重试").assertIsDisplayed()
        rule.onNodeWithTag("grades_sync_detail").assertTextEquals(
            "网络不可用 · 请稍后重试；1 个学期同步失败且无记录；" +
                "1 个学期尚未同步；本次更新未保存，重启后会恢复到上次保存的成绩",
        )
    }

    @Test
    fun failedSyncDetailIncludesNoRecordFailureAndUnsyncedCountsWithRetainedCourses() {
        render(
            state = mixedTermState().copy(unsavedChanges = true),
        )

        rule.onNodeWithTag("grades_sync_summary").assertTextEquals(
            "4/6 个学期已同步 · 7 门课程 · 2 个学期同步失败",
        )
        rule.onNodeWithTag("grades_sync_detail").assertTextEquals(
            "教务系统暂时不可用 · 2 个学期同步失败，已保留已同步记录；" +
                "1 个学期同步失败且无记录；1 个学期尚未同步；" +
                "本次更新未保存，重启后会恢复到上次保存的成绩",
        )
    }

    @Test
    fun emptyTermsShowCorrectOverviewAndDetailStates() {
        var syncs = 0
        val state = readyState(
            termList = terms + listOf(
                GradesTerm("t5", "合成已同步空学期"),
                GradesTerm("t6", "合成首次失败学期", isSynced = false, syncFailed = true),
            ),
        )
        render(state = state, onSync = { syncs++ })

        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()
        rule.onNodeWithTag("grades_term_chip_t3").performScrollTo().performClick()
        rule.onNodeWithText("该学期尚未同步").assertIsDisplayed()
        rule.onNodeWithText("该学期尚未同步。").assertIsDisplayed()

        rule.onNodeWithTag("grades_term_chip_t5").performScrollTo().performClick()
        rule.onNodeWithText("该学期暂无成绩记录").assertIsDisplayed()
        rule.onNodeWithText("可切换其他学期或查看全部学期。").assertIsDisplayed()

        rule.onNodeWithTag("grades_term_chip_t6").performScrollTo().performClick()
        rule.onNodeWithText("该学期同步失败，暂无记录。").assertIsDisplayed()

        selectTab("课程明细")
        rule.onNodeWithTag("grades_detail_list").assertIsDisplayed()
        rule.onNodeWithTag("grades_term_chip_t5").performScrollTo().performClick()
        scrollDetailTo("grades_detail_empty")
        rule.onNodeWithText("该学期暂无成绩记录").assertIsDisplayed()
        rule.onNodeWithText("查看全部学期").assertIsDisplayed()

        rule.onNodeWithTag("grades_term_chip_t6").performScrollTo().performClick()
        scrollDetailTo("grades_detail_empty")
        rule.onNodeWithText("该学期同步失败").assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed()

        rule.onNodeWithTag("grades_term_chip_t3").performScrollTo().performClick()
        scrollDetailTo("grades_detail_empty")
        rule.onNodeWithText("该学期尚未同步").assertIsDisplayed()
        detailEmptyText("教务同步").assertIsDisplayed()
        assertEquals(0, syncs)
    }

    @Test
    fun overviewTermEntryKeepsEmptyTermStatesAheadOfStatisticsScopeEmptyState() {
        var syncs = 0
        render(
            state = readyState(
                termList = terms + listOf(
                    GradesTerm("t5", "合成已同步空学期"),
                    GradesTerm("t6", "合成首次失败学期", isSynced = false, syncFailed = true),
                ),
            ),
            onSync = { syncs++ },
        )
        rule.onNodeWithTag("grades_toggle_empty_terms").performClick()

        scrollOverviewTo("grades_term_row_t5")
        rule.onNodeWithTag("grades_term_row_t5").performClick()
        rule.onNodeWithText("仅显示已计入统计的记录。").assertIsDisplayed()
        detailEmptyText("该学期暂无成绩记录").assertIsDisplayed()
        detailEmptyText("查看全部学期").assertIsDisplayed()
        rule.onNodeWithText("统计中没有可计入的记录").assertDoesNotExist()

        rule.onNodeWithText("清除筛选").performClick()
        selectTab("成绩总览")
        rule.onNodeWithTag("grades_overview_list").assertIsDisplayed()
        scrollOverviewTo("grades_term_row_t6")
        rule.onNodeWithTag("grades_term_row_t6").performClick()
        rule.onNodeWithText("仅显示已计入统计的记录。").assertIsDisplayed()
        detailEmptyText("该学期同步失败").assertIsDisplayed()
        detailEmptyText("重试").assertIsDisplayed()
        rule.onNodeWithText("统计中没有可计入的记录").assertDoesNotExist()

        rule.onNodeWithText("清除筛选").performClick()
        selectTab("成绩总览")
        rule.onNodeWithTag("grades_overview_list").assertIsDisplayed()
        scrollOverviewTo("grades_term_row_t3")
        rule.onNodeWithTag("grades_term_row_t3").performClick()
        rule.onNodeWithText("仅显示已计入统计的记录。").assertIsDisplayed()
        detailEmptyText("该学期尚未同步").assertIsDisplayed()
        detailEmptyText("教务同步").assertIsDisplayed()
        rule.onNodeWithText("统计中没有可计入的记录").assertDoesNotExist()
        assertEquals(0, syncs)
    }

    @Test
    fun pendingOnlyCourseKeepsItsTermVisibleByDefault() {
        render(
            state = readyState(
                termList = listOf(GradesTerm("pending", "合成待核学期")),
                courseList = listOf(
                    course(
                        "pending",
                        "pending",
                        "合成待核课程",
                        "",
                        GradesScore.Missing,
                        null,
                        null,
                    ),
                ),
            ),
        )

        rule.onNodeWithTag("grades_term_chip_pending").assertIsDisplayed()
        rule.onNodeWithTag("grades_toggle_empty_terms").assertDoesNotExist()
        scrollOverviewTo("grades_term_row_pending")
        rule.onNodeWithTag("grades_term_row_pending").assertIsDisplayed()
    }

    @Test
    fun selectedNatureFilterAndDetailTabSurviveStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                GradesScreenContent(
                    state = readyState(),
                    onBack = {},
                    onSync = {},
                )
            }
        }

        scrollOverviewTo("grades_nature_row_必修")
        rule.onNodeWithTag("grades_nature_row_必修").performClick()
        rule.onNodeWithText("筛选：必修").assertIsDisplayed()
        rule.onNodeWithText("共 4 门").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNodeWithText("筛选：必修").assertIsDisplayed()
        rule.onNodeWithText("共 4 门").assertIsDisplayed()
        rule.onNodeWithText("仅显示已计入统计的记录。").assertIsDisplayed()

        rule.onNodeWithText("清除筛选").performClick()
        rule.onNodeWithText("全部成绩记录").assertIsDisplayed()
        rule.onNodeWithText("共 7 门").assertIsDisplayed()
    }
}
