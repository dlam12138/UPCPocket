package com.upcpocket.app.grades

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesProtocolTest {

    private val term = GradesTerm(year = "2025", term = "1")

    @Test
    fun indexAcceptsOnlyCompleteUniqueTermsAndKeepsCurrentInsideTheList() {
        val index = GradesProtocol.parseIndex(
            """
            {"e":0,"d":{
              "terms":[{"year":"2025","term":"1"},{"year":"2024","term":"2"}],
              "params":{"year":"2025","xq":"1"}
            }}
            """.trimIndent(),
        )
        assertEquals(listOf("2025|1", "2024|2"), index.terms.map { it.key })
        assertEquals(index.terms.first(), index.currentTerm)

        assertSchema { GradesProtocol.parseIndex("""{"e":0,"d":{"terms":[]}}""") }
        assertSchema {
            GradesProtocol.parseIndex(
                """{"e":0,"d":{"terms":[{"year":"2025","term":"1"},null]}}""",
            )
        }
        assertSchema {
            GradesProtocol.parseIndex("""{"e":0,"d":{"terms":[{"year":"2025"}]}}""")
        }
        assertSchema {
            GradesProtocol.parseIndex(
                """{"e":0,"d":{"terms":[
                    {"year":"2025","term":"1"},{"year":"2025","term":"1"}
                ]}}""",
            )
        }
        assertSchema {
            GradesProtocol.parseIndex(
                """{"e":0,"d":{"terms":[{"year":"2025","term":"1"}],
                    "params":{"year":"2023","xq":"2"}}}""",
            )
        }
        assertSchema {
            GradesProtocol.parseIndex(
                """{"e":0,"d":{"terms":[{"year":"2025","term":"1"}],"params":"bad"}}""",
            )
        }
    }

    @Test
    fun indexBusinessCodesAreNotSuccess() {
        assertProtocol(GradesRemoteFailure.AUTH_REQUIRED) {
            GradesProtocol.parseIndex("""{"e":10013}""")
        }
        assertProtocol(GradesRemoteFailure.UPSTREAM) {
            GradesProtocol.parseIndex("""{"e":10001,"d":{}}""")
        }
        assertProtocol(GradesRemoteFailure.SCHEMA) {
            GradesProtocol.parseIndex("""{"d":{"terms":[{"year":"2025","term":"1"}]}}""")
        }
    }

    @Test
    fun termDistinguishesEmptyResultMissingListAndBadRows() {
        assertTrue(
            GradesProtocol.parseTerm("""{"e":0,"d":{"list":[]}}""", term).isEmpty(),
        )
        assertSchema { GradesProtocol.parseTerm("""{"e":0,"d":{}}""", term) }
        assertSchema {
            GradesProtocol.parseTerm("""{"e":0,"d":{"list":[null]}}""", term)
        }
        assertSchema {
            GradesProtocol.parseTerm(
                """{"e":0,"d":{"list":[{"kccj":"90","kclx":"必修"}]}}""",
                term,
            )
        }
        assertSchema {
            GradesProtocol.parseTerm(
                """{"e":0,"d":{"list":[{"kcmc":"高等数学","kclx":"必修"}]}}""",
                term,
            )
        }
        assertSchema {
            GradesProtocol.parseTerm(
                """{"e":0,"d":{"list":[{"kcmc":"高等数学","kccj":true,"kclx":"必修"}]}}""",
                term,
            )
        }
        assertProtocol(GradesRemoteFailure.AUTH_REQUIRED) {
            GradesProtocol.parseTerm("""{"e":10013}""", term)
        }
    }

    @Test
    fun explicitNullAndBlankScoreOrNatureMeanUnknownNotBadRow() {
        val courses = GradesProtocol.parseTerm(
            """
            {"e":0,"d":{"list":[
              {"kcmc":"高等数学","kccj":null,"kclx":null,"xf":null},
              {"kcmc":"大学英语","kccj":"","kclx":"  ","xf":""},
              {"kcmc":"大学物理","kccj":95,"kclx":"必修","xf":"3.5","exame_type":"考试"}
            ]}}
            """.trimIndent(),
            term,
        )

        assertEquals(3, courses.size)
        assertNull(courses[0].score)
        assertNull(courses[0].courseType)
        assertNull(courses[0].credits)
        assertNull(courses[1].score)
        assertNull(courses[1].courseType)
        assertNull(courses[1].credits)
        assertEquals("95", courses[2].score)
        assertEquals(3.5, courses[2].credits!!, 0.0)
        assertEquals("必修", courses[2].courseType)
        assertEquals("考试", courses[2].examType)
    }

    @Test
    fun duplicateCourseNamesAreKeptAsSeparateRows() {
        val courses = GradesProtocol.parseTerm(
            """
            {"e":0,"d":{"list":[
              {"kcmc":"高等数学","kccj":"95","kclx":"必修","xf":"4"},
              {"kcmc":"高等数学","kccj":"80","kclx":"必修","xf":"4"}
            ]}}
            """.trimIndent(),
            term,
        )
        assertEquals(listOf("95", "80"), courses.map { it.score })
        assertTrue(courses.all { it.term == term })
    }

    private fun assertSchema(block: () -> Unit) {
        assertProtocol(GradesRemoteFailure.SCHEMA, block)
    }

    private fun assertProtocol(failure: GradesRemoteFailure, block: () -> Unit) {
        val exception = assertThrows(GradesProtocolException::class.java, block)
        assertEquals(failure, exception.failure)
    }
}
