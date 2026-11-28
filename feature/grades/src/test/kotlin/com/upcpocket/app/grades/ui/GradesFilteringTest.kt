package com.upcpocket.app.grades.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesFilteringTest {

    private val requirement = GradesCourseNature("必修", isRequired = true)
    private val elective = GradesCourseNature("选修", isRequired = false)

    private val courses = listOf(
        course("a1", "term-a", 92.0, 3.0, requirement),
        course("a2", "term-a", 55.0, 1.5, elective),
        course("a3", "term-a", 85.0, 2.0, requirement, retakeAmbiguous = true),
        course("b1", "term-b", 88.0, 2.5, null),
        course("b2", "term-b", 77.0, null, requirement),
        course("b3", "term-b", 65.0, 1.0, elective),
    )

    private fun course(
        id: String,
        termId: String,
        score: Double,
        credits: Double?,
        nature: GradesCourseNature?,
        retakeAmbiguous: Boolean = false,
    ) = GradesCourse(
        id = id,
        termId = termId,
        name = "合成课程$id",
        rawGrade = score.toString(),
        score = GradesScore.Percent(score),
        credits = credits,
        nature = nature,
        retakeAmbiguous = retakeAmbiguous,
    )

    private fun ids(filtered: List<GradesCourse>) = filtered.map { it.id }

    @Test
    fun termVisibilityClassifiesEmptyTermsAndKeepsOriginalOrder() {
        val terms = listOf(
            GradesTerm("a-pending", "待核课程学期", isSynced = false),
            GradesTerm("b", "已同步空学期"),
            GradesTerm("a-failed", "有旧记录的失败学期", syncFailed = true),
            GradesTerm("c", "首次同步失败学期", isSynced = false, syncFailed = true),
            GradesTerm("d", "尚未同步学期", isSynced = false),
            GradesTerm("a-last", "末尾有课程学期"),
        )
        val termCourses = listOf(
            GradesCourse(
                id = "pending",
                termId = "a-pending",
                name = "待核课程",
                rawGrade = "",
                score = GradesScore.Missing,
                credits = null,
                nature = null,
            ),
            course("old", "a-failed", 88.0, 2.0, requirement),
            course("last", "a-last", 91.0, 1.0, elective),
        )

        val collapsed = deriveGradesTermVisibility(
            terms = terms,
            courses = termCourses,
            showTermsWithoutRecords = false,
        )
        assertEquals(listOf("a-pending", "a-failed", "a-last"),
            collapsed.visibleTerms.map { it.id })
        assertEquals(3, collapsed.withoutRecordsCount)
        assertEquals(1, collapsed.syncedEmptyCount)
        assertEquals(1, collapsed.failedWithoutRecordsCount)
        assertEquals(1, collapsed.unsyncedWithoutRecordsCount)

        val expanded = deriveGradesTermVisibility(
            terms = terms,
            courses = termCourses,
            showTermsWithoutRecords = true,
        )
        assertEquals(terms.map { it.id }, expanded.visibleTerms.map { it.id })
        assertEquals(3, expanded.withoutRecordsCount)
        assertEquals(1, expanded.syncedEmptyCount)
        assertEquals(1, expanded.failedWithoutRecordsCount)
        assertEquals(1, expanded.unsyncedWithoutRecordsCount)
    }

    @Test
    fun termVisibilityCountsEmptyAndFailedWithoutRecordsSeparatelyFromCourseTerms() {
        val terms = listOf(
            GradesTerm("with-records", "有课程", syncFailed = true),
            GradesTerm("synced-empty", "已同步空学期"),
            GradesTerm("failed-empty", "曾为空后刷新失败", syncFailed = true),
            GradesTerm("unsynced-empty", "未同步空学期", isSynced = false),
        )
        val visibility = deriveGradesTermVisibility(
            terms = terms,
            courses = listOf(course("record", "with-records", 90.0, 1.0, requirement)),
            showTermsWithoutRecords = false,
        )

        assertEquals(listOf("with-records"), visibility.visibleTerms.map { it.id })
        assertEquals(3, visibility.withoutRecordsCount)
        assertEquals(1, visibility.syncedEmptyCount)
        assertEquals(1, visibility.failedWithoutRecordsCount)
        assertEquals(1, visibility.unsyncedWithoutRecordsCount)
    }

    @Test
    fun termVisibilityHasZeroWithoutRecordCountWhenEveryTermHasCourses() {
        val terms = listOf(GradesTerm("a", "学期甲"), GradesTerm("b", "学期乙"))
        val visibility = deriveGradesTermVisibility(
            terms = terms,
            courses = listOf(
                course("a-course", "a", 90.0, 1.0, requirement),
                course("b-course", "b", 80.0, 2.0, elective),
            ),
            showTermsWithoutRecords = false,
        )

        assertEquals(terms.map { it.id }, visibility.visibleTerms.map { it.id })
        assertEquals(0, visibility.withoutRecordsCount)
        assertEquals(0, visibility.syncedEmptyCount)
        assertEquals(0, visibility.failedWithoutRecordsCount)
        assertEquals(0, visibility.unsyncedWithoutRecordsCount)
    }

    @Test
    fun termVisibilityHasZeroWithoutRecordCountForNoTerms() {
        val visibility = deriveGradesTermVisibility(
            terms = emptyList(),
            courses = courses,
            showTermsWithoutRecords = true,
        )

        assertTrue(visibility.visibleTerms.isEmpty())
        assertEquals(0, visibility.withoutRecordsCount)
    }

    @Test
    fun termFilterKeepsAllRecordsOfThatTerm() {
        assertEquals(
            listOf("a1", "a2", "a3"),
            ids(filterGradesCourses(courses, "term-a", null, null)),
        )
        assertEquals(
            listOf("b1", "b2", "b3"),
            ids(filterGradesCourses(courses, "term-b", null, null)),
        )
        assertEquals(
            listOf("a1", "a2", "a3", "b1", "b2", "b3"),
            ids(filterGradesCourses(courses, null, null, null)),
        )
    }

    @Test
    fun natureFilterUsesDisplayedNatureLabel() {
        assertEquals(
            listOf("a1", "a3", "b2"),
            ids(filterGradesCourses(courses, null, "必修", null)),
        )
        assertEquals(
            listOf("a2", "b3"),
            ids(filterGradesCourses(courses, null, "选修", null)),
        )
        assertEquals(
            listOf("b1"),
            ids(filterGradesCourses(courses, null, UNKNOWN_NATURE_LABEL, null)),
        )
    }

    @Test
    fun bandFilterUsesExplicitPercentages() {
        assertEquals(
            listOf("a1"),
            ids(filterGradesCourses(courses, null, null, GradesScoreBand.SCORE_90_100)),
        )
        assertEquals(
            listOf("a3", "b1"),
            ids(filterGradesCourses(courses, null, null, GradesScoreBand.SCORE_80_89)),
        )
        assertEquals(
            listOf("b2"),
            ids(filterGradesCourses(courses, null, null, GradesScoreBand.SCORE_70_79)),
        )
        assertEquals(
            listOf("b3"),
            ids(filterGradesCourses(courses, null, null, GradesScoreBand.SCORE_60_69)),
        )
        assertEquals(
            listOf("a2"),
            ids(filterGradesCourses(courses, null, null, GradesScoreBand.SCORE_BELOW_60)),
        )
    }

    @Test
    fun statisticsScopeExcludesPendingAndPendingCountUsesSameFilter() {
        val included = filterGradesCourses(
            courses = courses,
            termId = null,
            natureLabel = "必修",
            band = null,
            includePending = false,
        )
        assertEquals(listOf("a1"), ids(included))
        assertEquals(2, pendingCountFor(courses, null, "必修", null))

        val includedBand = filterGradesCourses(
            courses = courses,
            termId = null,
            natureLabel = null,
            band = GradesScoreBand.SCORE_80_89,
            includePending = false,
        )
        assertEquals(listOf("b1"), ids(includedBand))
        assertEquals(1, pendingCountFor(courses, null, null, GradesScoreBand.SCORE_80_89))

        val includedTerm = filterGradesCourses(
            courses = courses,
            termId = "term-a",
            natureLabel = null,
            band = null,
            includePending = false,
        )
        assertEquals(listOf("a1", "a2"), ids(includedTerm))
        assertEquals(1, pendingCountFor(courses, "term-a", null, null))
    }

    @Test
    fun statisticsScopeRecordCountMatchesChartDenominator() {
        val stats = computeGradesStatistics(courses)
        val included = filterGradesCourses(
            courses = courses,
            termId = null,
            natureLabel = null,
            band = null,
            includePending = false,
        )

        assertEquals(stats.included.map { it.id }, ids(included))
        assertEquals(stats.pendingCount, pendingCountFor(courses, null, null, null))

        val groups = natureGroups(stats.included)
        groups.forEach { group ->
            val groupDetail = filterGradesCourses(
                courses = courses,
                termId = null,
                natureLabel = group.label,
                band = null,
                includePending = false,
            )
            assertEquals(group.courses.size, groupDetail.size)
        }

        bandSummaries(stats.included).forEach { summary ->
            val bandDetail = filterGradesCourses(
                courses = courses,
                termId = null,
                natureLabel = null,
                band = summary.band,
                includePending = false,
            )
            assertEquals(summary.courseCount, bandDetail.size)
        }
    }

    @Test
    fun combinedFiltersIntersect() {
        assertEquals(
            listOf("a1"),
            ids(filterGradesCourses(courses, "term-a", "必修", GradesScoreBand.SCORE_90_100)),
        )
        assertTrue(
            filterGradesCourses(courses, "term-a", "选修", GradesScoreBand.SCORE_90_100).isEmpty(),
        )
    }
}
