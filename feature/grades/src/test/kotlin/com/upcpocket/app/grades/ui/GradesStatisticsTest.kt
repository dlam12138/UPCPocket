package com.upcpocket.app.grades.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesStatisticsTest {

    private fun course(
        id: String,
        rawGrade: String = "90",
        score: GradesScore = GradesScore.Percent(90.0),
        credits: Double? = 2.0,
        nature: GradesCourseNature? = GradesCourseNature("必修", isRequired = true),
        retakeAmbiguous: Boolean = false,
        termId: String = "term-a",
    ) = GradesCourse(
        id = id,
        termId = termId,
        name = "合成课程$id",
        rawGrade = rawGrade,
        score = score,
        credits = credits,
        nature = nature,
        retakeAmbiguous = retakeAmbiguous,
    )

    @Test
    fun percentageGpaUsesMinusFiveAndZeroBelowSixty() {
        assertEquals(4.2, GradesScore.Percent(92.0).gradePoint()!!, 1e-9)
        assertEquals(5.0, GradesScore.Percent(100.0).gradePoint()!!, 1e-9)
        assertEquals(1.0, GradesScore.Percent(60.0).gradePoint()!!, 1e-9)
        assertEquals(0.0, GradesScore.Percent(59.9).gradePoint()!!, 1e-9)
        assertEquals(0.0, GradesScore.Percent(0.0).gradePoint()!!, 1e-9)

        assertTrue(GradesScore.Percent(60.0).isPassing()!!)
        assertFalse(GradesScore.Percent(59.9).isPassing()!!)
    }

    @Test
    fun invalidPercentageIsNotInterpreted() {
        assertNull(GradesScore.Percent(-0.1).percentageEquivalent())
        assertNull(GradesScore.Percent(100.1).percentageEquivalent())
        assertNull(GradesScore.Percent(Double.NaN).percentageEquivalent())
        assertNull(GradesScore.Percent(Double.POSITIVE_INFINITY).percentageEquivalent())
        assertNull(GradesScore.Missing.percentageEquivalent())
        assertNull(GradesScore.Unrecognized.percentageEquivalent())
    }

    @Test
    fun levelsAndPassFailUseCampusConversions() {
        assertConversion(GradesScore.Level(GradesLevel.EXCELLENT), 95.0, 4.5)
        assertConversion(GradesScore.Level(GradesLevel.GOOD), 85.0, 3.5)
        assertConversion(GradesScore.Level(GradesLevel.MEDIUM), 75.0, 2.5)
        assertConversion(GradesScore.Level(GradesLevel.PASS), 65.0, 1.5)
        assertConversion(GradesScore.Level(GradesLevel.FAIL), 40.0, 0.0)
        assertConversion(GradesScore.PassFail(passed = true), 80.0, 3.0)
        assertConversion(GradesScore.PassFail(passed = false), 40.0, 0.0)
    }

    private fun assertConversion(score: GradesScore, percentage: Double, gpa: Double) {
        assertEquals(percentage, score.percentageEquivalent()!!, 1e-9)
        assertEquals(gpa, score.gradePoint()!!, 1e-9)
        assertEquals(percentage >= 60.0, score.isPassing()!!)
    }

    @Test
    fun creditWeightedGpaAndAverageUseIncludedCourses() {
        val courses = listOf(
            course("a", score = GradesScore.Percent(92.0), credits = 3.0),
            course("b", rawGrade = "良好", score = GradesScore.Level(GradesLevel.GOOD), credits = 2.0),
            course("c", score = GradesScore.Percent(55.0), credits = 1.0,
                nature = GradesCourseNature("选修", isRequired = false)),
        )

        val stats = computeGradesStatistics(courses)

        assertEquals(3, stats.included.size)
        assertEquals(0, stats.pendingCount)
        assertEquals(19.6 / 6.0, stats.totalGpa!!, 1e-9)
        assertEquals(83.5, stats.weightedAverage!!, 1e-9)
        assertEquals(3.92, stats.requiredGpa!!, 1e-9)
        assertEquals(89.2, stats.requiredAverage!!, 1e-9)
        assertEquals(5.0, stats.earnedCredits, 1e-9)
        assertEquals(6.0, stats.attemptedCredits, 1e-9)
        assertEquals(2, stats.passedCount)
        assertEquals(2.0 / 3.0, stats.passRate!!, 1e-9)
        assertFalse(stats.requiredPending)
        assertEquals("3.27", formatGpa(stats.totalGpa!!))
        assertEquals("83.5", formatAverage(stats.weightedAverage!!))
    }

    @Test
    fun pendingReasonsAreExplicitAndExcludedFromStatistics() {
        val missingScore = course("missing", rawGrade = "", score = GradesScore.Missing, credits = 3.0)
        val unrecognized = course("unrecognized", rawGrade = "待定", score = GradesScore.Unrecognized, credits = 3.0)
        val noCredits = course("no-credits", score = GradesScore.Percent(80.0), credits = null)
        val zeroCredits = course("zero-credits", score = GradesScore.Percent(80.0), credits = 0.0)
        val ambiguous = course(
            "ambiguous",
            score = GradesScore.Percent(85.0),
            credits = 3.0,
            retakeAmbiguous = true,
        )
        val invalidPercent = course("invalid", score = GradesScore.Percent(120.0), credits = 3.0)
        val valid = course("valid", score = GradesScore.Percent(80.0), credits = 3.0)

        assertEquals(
            GradesPendingReason.MISSING_SCORE,
            pendingReason(missingScore),
        )
        assertEquals(
            GradesPendingReason.UNRECOGNIZED_SCORE,
            pendingReason(unrecognized),
        )
        assertEquals(
            GradesPendingReason.MISSING_CREDITS,
            pendingReason(noCredits),
        )
        assertEquals(
            GradesPendingReason.MISSING_CREDITS,
            pendingReason(zeroCredits),
        )
        assertEquals(
            GradesPendingReason.AMBIGUOUS_RETAKE,
            pendingReason(ambiguous),
        )
        assertEquals(
            GradesPendingReason.UNRECOGNIZED_SCORE,
            pendingReason(invalidPercent),
        )
        assertNull(pendingReason(valid))

        val stats = computeGradesStatistics(
            listOf(missingScore, unrecognized, noCredits, zeroCredits, ambiguous, invalidPercent, valid),
        )
        assertEquals(1, stats.included.size)
        assertEquals(6, stats.pendingCount)
        assertEquals(3.0, stats.totalGpa!!, 1e-9)
        assertEquals(3.0, stats.attemptedCredits, 1e-9)
        assertEquals(3.0, stats.earnedCredits, 1e-9)
        assertEquals(1.0, stats.passRate!!, 1e-9)
    }

    @Test
    fun unknownNatureIsIncludedButRequiredStatisticsStayPending() {
        val unknown = course(
            "unknown",
            score = GradesScore.Percent(88.0),
            credits = 2.0,
            nature = null,
        )
        val unspecifiedRequired = course(
            "unspecified",
            score = GradesScore.Percent(90.0),
            credits = 2.0,
            nature = GradesCourseNature("必修", isRequired = null),
        )

        assertEquals(UNKNOWN_NATURE_LABEL, courseNatureLabel(unknown))
        assertEquals(GradesPendingReason.MISSING_CREDITS, pendingReason(unknown.copy(credits = null)))
        assertNull(pendingReason(unknown))

        val stats = computeGradesStatistics(listOf(unknown, unspecifiedRequired))
        assertEquals(2, stats.included.size)
        assertTrue(stats.requiredPending)
        assertNull(stats.requiredGpa)
        assertNull(stats.requiredAverage)
        assertEquals(3.9, stats.totalGpa!!, 1e-9)
        assertEquals(4.0, stats.earnedCredits, 1e-9)

        val groups = natureGroups(stats.included)
        assertEquals(listOf("必修", UNKNOWN_NATURE_LABEL), groups.map { it.label }.sorted())
        assertEquals(2.0, groups.first { it.label == UNKNOWN_NATURE_LABEL }.credits, 1e-9)
    }

    @Test
    fun failedCoursesEarnNoCreditsButCountInAttemptedCredits() {
        val pass = course("pass", score = GradesScore.PassFail(passed = true), credits = 2.0)
        val fail = course("fail", score = GradesScore.Percent(50.0), credits = 2.0)
        val pending = course("pending", score = GradesScore.Percent(90.0), credits = 4.0,
            retakeAmbiguous = true)

        val stats = computeGradesStatistics(listOf(pass, fail, pending))

        assertEquals(2, stats.included.size)
        assertEquals(1, stats.pendingCount)
        assertEquals(1, stats.passedCount)
        assertEquals(0.5, stats.passRate!!, 1e-9)
        assertEquals(2.0, stats.earnedCredits, 1e-9)
        assertEquals(4.0, stats.attemptedCredits, 1e-9)
        assertEquals(1.5, stats.totalGpa!!, 1e-9)
        assertEquals(65.0, stats.weightedAverage!!, 1e-9)
    }

    @Test
    fun emptyCoursesHaveNeutralStatistics() {
        val stats = computeGradesStatistics(emptyList())

        assertTrue(stats.included.isEmpty())
        assertTrue(stats.pending.isEmpty())
        assertNull(stats.totalGpa)
        assertNull(stats.weightedAverage)
        assertNull(stats.requiredGpa)
        assertNull(stats.requiredAverage)
        assertEquals(0.0, stats.earnedCredits, 1e-9)
        assertEquals(0.0, stats.attemptedCredits, 1e-9)
        assertEquals(0, stats.passedCount)
        assertNull(stats.passRate)
        assertFalse(stats.requiredPending)
        assertEquals("0", formatCredits(stats.earnedCredits))
    }

    @Test
    fun scoreBandsUseExplicitPercentagesOrConversions() {
        assertEquals(GradesScoreBand.SCORE_90_100, GradesScore.Percent(90.0).band())
        assertEquals(GradesScoreBand.SCORE_90_100, GradesScore.Percent(100.0).band())
        assertEquals(GradesScoreBand.SCORE_80_89, GradesScore.Percent(89.9).band())
        assertEquals(GradesScoreBand.SCORE_70_79, GradesScore.Percent(70.0).band())
        assertEquals(GradesScoreBand.SCORE_60_69, GradesScore.Percent(60.0).band())
        assertEquals(GradesScoreBand.SCORE_BELOW_60, GradesScore.Percent(59.9).band())

        assertEquals(GradesScoreBand.SCORE_90_100, GradesScore.Level(GradesLevel.EXCELLENT).band())
        assertEquals(GradesScoreBand.SCORE_80_89, GradesScore.Level(GradesLevel.GOOD).band())
        assertEquals(GradesScoreBand.SCORE_70_79, GradesScore.Level(GradesLevel.MEDIUM).band())
        assertEquals(GradesScoreBand.SCORE_60_69, GradesScore.Level(GradesLevel.PASS).band())
        assertEquals(GradesScoreBand.SCORE_BELOW_60, GradesScore.Level(GradesLevel.FAIL).band())
        assertEquals(GradesScoreBand.SCORE_80_89, GradesScore.PassFail(passed = true).band())
        assertEquals(GradesScoreBand.SCORE_BELOW_60, GradesScore.PassFail(passed = false).band())

        assertNull(GradesScore.Missing.band())
        assertNull(GradesScore.Unrecognized.band())

        val courses = listOf(
            course("a", score = GradesScore.Percent(92.0), credits = 3.0),
            course("b", score = GradesScore.Level(GradesLevel.GOOD), credits = 2.0),
            course("c", score = GradesScore.Percent(55.0), credits = 1.0),
        )
        val summaries = bandSummaries(courses)
        assertEquals(GradesScoreBand.entries, summaries.map { it.band })
        assertEquals(1, summaries.first { it.band == GradesScoreBand.SCORE_90_100 }.courseCount)
        assertEquals(1, summaries.first { it.band == GradesScoreBand.SCORE_80_89 }.courseCount)
        assertEquals(0, summaries.first { it.band == GradesScoreBand.SCORE_70_79 }.courseCount)
        assertEquals(0, summaries.first { it.band == GradesScoreBand.SCORE_60_69 }.courseCount)
        assertEquals(1, summaries.first { it.band == GradesScoreBand.SCORE_BELOW_60 }.courseCount)
    }

    @Test
    fun formattingIsStableAndOmitsMeaninglessZeros() {
        assertEquals("3.13", formatGpa(3.125))
        assertEquals("3.00", formatGpa(3.0))
        assertEquals("81.9", formatAverage(81.875))
        assertEquals("10", formatCredits(10.0))
        assertEquals("1.5", formatCredits(1.5))
        assertEquals("1.25", formatCredits(1.25))
        assertEquals("0", formatCredits(0.0))
        assertEquals("—", formatCredits(Double.NaN))
        assertEquals("83.3%", formatPercent(5.0 / 6.0))
        assertEquals("100%", formatPercent(1.0))
        assertEquals("0%", formatPercent(0.0))
        assertEquals("—", formatPercent(Double.NaN))
    }
}
