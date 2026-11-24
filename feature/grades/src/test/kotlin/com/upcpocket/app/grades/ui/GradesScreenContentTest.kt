package com.upcpocket.app.grades.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun boundsOf(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun overviewShowsMetricsPendingAndSourceScope() {
        render()

        rule.onNodeWithText("成绩与学分").assertIsDisplayed()
        rule.onNodeWithText("已同步").assertIsDisplayed()

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

        rule.onNodeWithText("学校登录已失效 · 1 个学期同步失败，已保留已同步记录")
            .assertIsDisplayed()
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

        rule.onNodeWithText("学校要求交互验证 · 1 个学期同步失败，已保留已同步记录")
            .assertIsDisplayed()
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
        rule.onNodeWithText(
            "教务系统暂时不可用 · 1 个学期同步失败，已保留已同步记录；" +
                "本次更新未保存，重启后会恢复到上次保存的成绩",
        ).assertIsDisplayed()

        // 部分覆盖有结果但课程全部为空：未保存提示仍须可见，不能显示成完成后的空结果。
        rule.runOnIdle {
            state.value = readyState(
                sync = GradesSyncState.Failed(GradesSyncFailure.SERVER),
                courseList = emptyList(),
                unsavedChanges = true,
            )
        }
        rule.onNodeWithText(
            "教务系统暂时不可用 · 请稍后重试；本次更新未保存，重启后会恢复到上次保存的成绩",
        ).assertIsDisplayed()
        rule.onNodeWithText("教务系统暂时不可用。未获取到成绩，请稍后重试。")
            .assertIsDisplayed()
        rule.onNodeWithText("暂无成绩记录").assertDoesNotExist()

        // 同步完成但结果未写入本机时，也不能表现为已保存。
        rule.runOnIdle { state.value = readyState(unsavedChanges = true) }
        rule.onNodeWithText("本次更新未保存，重启后会恢复到上次保存的成绩")
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

        rule.runOnIdle { state.value = GradesUiState(sync = GradesSyncState.Ready()) }
        rule.onNodeWithText("教务系统没有返回本科成绩记录。").assertIsDisplayed()

        rule.runOnIdle {
            state.value = GradesUiState(
                sync = GradesSyncState.Ready(),
                terms = listOf(GradesTerm("u1", "合成未同步学期", isSynced = false)),
            )
        }
        rule.onNodeWithText("学期成绩尚未同步").assertIsDisplayed()

        rule.runOnIdle {
            state.value = GradesUiState(
                sync = GradesSyncState.Ready(),
                terms = listOf(GradesTerm("f1", "合成失败学期", syncFailed = true)),
            )
        }
        rule.onNodeWithText("学期成绩同步失败").assertIsDisplayed()
        rule.onNodeWithText("没有可显示的历史记录，请重试。").assertIsDisplayed()
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
            termList = terms + GradesTerm("t5", "合成学期空"),
        )
        render(state = state)

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
