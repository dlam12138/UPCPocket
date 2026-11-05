package com.upcpocket.app.timetable

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimetableProtocolTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/$name")!!.readText()

    private fun term(): TimetableTerm =
        TimetableProtocol.parseIndex(fixture("timetable_index.json"))
            .find("2026-2027", "1")!!

    private fun failCode(code: TimetableErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (failure: TimetableParseException) {
            assertEquals(code, failure.code)
        }
    }

    @Test
    fun indexTermsAndCurrentWeek() {
        val index = TimetableProtocol.parseIndex(fixture("timetable_index.json"))
        assertEquals(listOf("1", "2"), index.terms.map { it.term })
        assertEquals(8, index.currentWeek)
        assertEquals(6, index.currentWeekday)
        assertEquals(LocalDate.parse("2026-10-25"), index.currentWeekdays!!.first())
        assertEquals(19, index.find("2026-2027", "1")!!.totalWeeks)
    }

    @Test
    fun weekSundayFirstAndWeekdayFromExplicitDate() {
        val week = TimetableProtocol.parseWeek(fixture("timetable_week_08.json"), term(), 8)
        assertEquals(
            listOf(
                "2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28",
                "2026-10-29", "2026-10-30", "2026-10-31",
            ),
            week.dates.map(LocalDate::toString),
        )
        val sunday = week.meetings.first { it.courseName == "示例课程日" }
        assertEquals(7, sunday.weekday)
        assertEquals(10, sunday.startPeriod)
        assertEquals(LocalDate.parse("2026-10-25"), sunday.date)
    }

    @Test
    fun nonContiguousPeriodsAreSplitAndSourceIsRetained() {
        val week = TimetableProtocol.parseWeek(fixture("timetable_week_08.json"), term(), 8)
        val split = week.meetings.filter { it.courseName == "示例课程C" }
        assertEquals(2, split.size)
        assertEquals(listOf(listOf(1), listOf(3)), split.map { it.periods })
        assertEquals(setOf("lessArr_split_noncontiguous"), split.map { it.sectionsSource }.toSet())
        assertTrue(split.all { it.sourcePeriods == listOf(1, 3) })
    }

    @Test
    fun sameNameDifferentIdentityIsNotMerged() {
        val week = TimetableProtocol.parseWeek(fixture("timetable_week_08.json"), term(), 8)
        val same = week.meetings.filter { it.courseName == "示例课程A" }
        assertEquals(2, same.size)
        assertEquals(setOf("SYN-A", "SYN-A2"), same.mapNotNull { it.courseId }.toSet())
        assertEquals(setOf("教师甲", "教师丁"), same.mapNotNull { it.teacher }.toSet())
    }

    @Test
    fun emptyWeekIsValidOnlyWhenComplete() {
        val week = TimetableProtocol.parseWeek(
            fixture("timetable_week_09_empty.json"),
            term(),
            9,
        )
        assertTrue(week.complete)
        assertTrue(week.isEmpty)
        assertEquals(LocalDate.parse("2026-11-01"), week.dates.first())
    }

    @Test
    fun requestedWeekMustMatchDates() {
        failCode(TimetableErrorCode.SCHEMA_CHANGED) {
            TimetableProtocol.parseWeek(fixture("timetable_week_08.json"), term(), 9)
        }
    }

    @Test
    fun courseWeekListMustContainRequestedWeek() {
        failCode(TimetableErrorCode.SCHEMA_CHANGED) {
            TimetableProtocol.parseWeek(
                mutateDayEntry(day = 1, cell = "c_6") { entry ->
                    entry["week"] = JsonPrimitive("9")
                },
                term(),
                8,
            )
        }
    }

    @Test
    fun missingCourseNameFails() {
        failCode(TimetableErrorCode.SCHEMA_CHANGED) {
            TimetableProtocol.parseWeek(
                mutateDayEntry(day = 1, cell = "c_6") { it.remove("course_name") },
                term(),
                8,
            )
        }
    }

    @Test
    fun missingWeekFails() {
        failCode(TimetableErrorCode.SCHEMA_CHANGED) {
            TimetableProtocol.parseWeek(
                mutateDayEntry(day = 1, cell = "c_6") { it.remove("week") },
                term(),
                8,
            )
        }
    }

    @Test
    fun missingLessArrFailsWithoutFallback() {
        failCode(TimetableErrorCode.SCHEMA_CHANGED) {
            TimetableProtocol.parseWeek(
                mutateDayEntry(day = 1, cell = "c_6") { it.remove("lessArr") },
                term(),
                8,
            )
        }
    }

    @Test
    fun duplicatePeriodsFail() {
        failCode(TimetableErrorCode.SCHEMA_CHANGED) {
            TimetableProtocol.parseWeek(
                mutateDayEntry(day = 1, cell = "c_6") { entry ->
                    entry["lessArr"] = JsonArray(listOf(JsonPrimitive(6), JsonPrimitive(6)))
                    entry["totalLength"] = JsonPrimitive(2)
                },
                term(),
                8,
            )
        }
    }

    @Test
    fun totalLengthOrderCellAndWeekdayContradictionsFail() {
        listOf<Pair<TimetableErrorCode, () -> String>>(
            TimetableErrorCode.SCHEMA_CHANGED to {
                mutateDayEntry(day = 1, cell = "c_6") { it["totalLength"] = JsonPrimitive(1) }
            },
            TimetableErrorCode.SCHEMA_CHANGED to {
                mutateDayEntry(day = 1, cell = "c_6") { it["order"] = JsonPrimitive(7) }
            },
            TimetableErrorCode.SCHEMA_CHANGED to {
                mutateDayEntry(day = 1, cell = "c_6") { it["weekday"] = JsonPrimitive("2") }
            },
            TimetableErrorCode.SCHEMA_CHANGED to {
                mutateCellKey(day = 1, from = "c_6", to = "c_7")
            },
        ).forEach { (code, mutator) ->
            failCode(code) { TimetableProtocol.parseWeek(mutator(), term(), 8) }
        }
    }

    @Test
    fun nonConsecutiveDatesAndNonSevenDaysFail() {
        failCode(TimetableErrorCode.SCHEMA_CHANGED) {
            TimetableProtocol.parseWeek(
                mutateRoot { root ->
                    val d = root["d"]!!.jsonObject.toMutableMap()
                    val days = d["weekdays"]!!.jsonArray.toMutableList()
                    days[6] = JsonPrimitive("2026-11-02")
                    d["weekdays"] = JsonArray(days)
                    root["d"] = JsonObject(d)
                },
                term(),
                8,
            )
        }
        failCode(TimetableErrorCode.SCHEMA_CHANGED) {
            TimetableProtocol.parseWeek(
                mutateRoot { root ->
                    val d = root["d"]!!.jsonObject.toMutableMap()
                    d["classes"] = JsonArray(d["classes"]!!.jsonArray.take(6))
                    root["d"] = JsonObject(d)
                },
                term(),
                8,
            )
        }
    }

    @Test
    fun missingTeacherAndLocationAreShownAsMissingNotInvented() {
        val raw = mutateDayEntry(day = 1, cell = "c_6") { entry ->
            entry.remove("teacher")
            entry.remove("location")
        }
        val week = TimetableProtocol.parseWeek(raw, term(), 8)
        val meeting = week.meetings.first { it.courseName == "示例课程A" && it.teacher == null }
        assertNull(meeting.teacher)
        assertNull(meeting.location)
        assertNull(meeting.credit)
        assertNull(meeting.examType)
    }

    @Test
    fun weekTypeIsPreservedWithoutInference() {
        val raw = mutateDayEntry(day = 1, cell = "c_6") { it["week_type"] = JsonPrimitive("7") }
        val week = TimetableProtocol.parseWeek(raw, term(), 8)
        assertEquals("7", week.meetings.first { it.courseName == "示例课程A" }.weekType)
    }

    @Test
    fun authAndUpstreamBusinessErrorsAreDistinct() {
        failCode(TimetableErrorCode.AUTH_REQUIRED) {
            TimetableProtocol.parseIndex("""{"e":10013,"m":"expired","d":{}}""")
        }
        failCode(TimetableErrorCode.UPSTREAM_ERROR) {
            TimetableProtocol.parseIndex("""{"e":403,"m":"forbidden","d":{}}""")
        }
    }

    @Test
    fun crossMonthAndYearUseExplicitTermStartNotGuessedCalendar() {
        val index = TimetableProtocol.parseIndex(fixture("timetable_index.json"))
        val spring = index.find("2026-2027", "2")!!
        val week1 = emptyWeekPayload("2027-02-21")
        val parsed = TimetableProtocol.parseWeek(week1, spring, 1)
        assertEquals(LocalDate.parse("2027-02-21"), parsed.dates.first())
        assertEquals(LocalDate.parse("2027-02-27"), parsed.dates.last())

        val autumn = index.find("2026-2027", "1")!!
        val week19 = emptyWeekPayload("2027-01-10")
        val parsed19 = TimetableProtocol.parseWeek(week19, autumn, 19)
        assertEquals(LocalDate.parse("2027-01-10"), parsed19.dates.first())
        assertEquals(LocalDate.parse("2027-01-16"), parsed19.dates.last())
    }

    @Test
    fun overlappingCoursesAreBothPreserved() {
        val raw = mutateRoot { root ->
            val d = root["d"]!!.jsonObject.toMutableMap()
            val classes = d["classes"]!!.jsonArray.toMutableList()
            val monday = classes[1].jsonObject.toMutableMap()
            val existing = monday["c_6"]!!.jsonArray.toMutableList()
            existing += JsonObject(
                mapOf(
                    "course_id" to JsonPrimitive("SYN-OVERLAP"),
                    "course_name" to JsonPrimitive("重叠课程"),
                    "teacher" to JsonPrimitive("教师乙"),
                    "location" to JsonPrimitive("教学楼B-202"),
                    "weekday" to JsonPrimitive("1"),
                    "week" to JsonPrimitive("8"),
                    "week_type" to JsonPrimitive("0"),
                    "lessArr" to JsonArray(listOf(JsonPrimitive(6), JsonPrimitive(7))),
                    "order" to JsonPrimitive(6),
                    "totalLength" to JsonPrimitive(2),
                ),
            )
            monday["c_6"] = JsonArray(existing)
            classes[1] = JsonObject(monday)
            d["classes"] = JsonArray(classes)
            root["d"] = JsonObject(d)
        }
        val week = TimetableProtocol.parseWeek(raw, term(), 8)
        val overlap = week.meetings.filter { it.weekday == 1 && it.startPeriod == 6 }
        assertEquals(2, overlap.size)
        assertEquals(setOf("示例课程A", "重叠课程"), overlap.map { it.courseName }.toSet())
    }

    private fun emptyWeekPayload(firstDate: String): String {
        val first = LocalDate.parse(firstDate)
        val days = (0..6).map { JsonPrimitive(first.plusDays(it.toLong()).toString()) }
        return JsonObject(
            mapOf(
                "e" to JsonPrimitive(0),
                "d" to JsonObject(
                    mapOf(
                        "classes" to JsonArray((0..6).map { JsonObject(emptyMap()) }),
                        "weekdays" to JsonArray(days),
                    ),
                ),
            ),
        ).toString()
    }

    private fun mutateRoot(block: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(fixture("timetable_week_08.json")).jsonObject
            .toMutableMap()
        block(root)
        return JsonObject(root).toString()
    }

    private fun mutateDayEntry(
        day: Int,
        cell: String,
        mutate: (MutableMap<String, JsonElement>) -> Unit,
    ): String = mutateRoot { root ->
        val d = root["d"]!!.jsonObject.toMutableMap()
        val classes = d["classes"]!!.jsonArray.toMutableList()
        val dayObject = classes[day].jsonObject.toMutableMap()
        val entries = dayObject[cell]!!.jsonArray.toMutableList()
        val entry = entries[0].jsonObject.toMutableMap()
        mutate(entry)
        entries[0] = JsonObject(entry)
        dayObject[cell] = JsonArray(entries)
        classes[day] = JsonObject(dayObject)
        d["classes"] = JsonArray(classes)
        root["d"] = JsonObject(d)
    }

    private fun mutateCellKey(day: Int, from: String, to: String): String =
        mutateRoot { root ->
            val d = root["d"]!!.jsonObject.toMutableMap()
            val classes = d["classes"]!!.jsonArray.toMutableList()
            val dayObject = classes[day].jsonObject.toMutableMap()
            val value = dayObject.remove(from)!!
            dayObject[to] = value
            classes[day] = JsonObject(dayObject)
            d["classes"] = JsonArray(classes)
            root["d"] = JsonObject(d)
        }
}
