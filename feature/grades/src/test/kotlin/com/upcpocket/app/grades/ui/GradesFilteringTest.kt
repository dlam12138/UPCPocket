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
