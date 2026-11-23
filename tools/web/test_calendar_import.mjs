import assert from "node:assert/strict";
import { test } from "node:test";

const CALENDAR = [
  "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//UPC Pocket test//CN",
  "BEGIN:VTIMEZONE", "TZID:Asia/Shanghai", "BEGIN:STANDARD",
  "DTSTART:19920101T000000", "TZOFFSETFROM:+0800", "TZOFFSETTO:+0800",
  "END:STANDARD", "END:VTIMEZONE",
  "BEGIN:VEVENT", "UID:synthetic-weekly-1", "DTSTAMP:20260901T000000Z",
  "DTSTART:20260909T080000", "DTEND:20260909T094000",
  "RRULE:FREQ=WEEKLY;COUNT=3",
  "SUMMARY:合成高等数学", "LOCATION:合成楼 101",
  "END:VEVENT", "END:VCALENDAR",
].join("\r\n");

const EMPTY_CALENDAR = [
  "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//UPC Pocket test//CN",
  "END:VCALENDAR",
].join("\r\n");

test("parses a floating weekly .ics without loading the parser at startup", async () => {
  const { courseWeek, parseCourseCalendar } = await import("../../web/timetable.js");

  // The parser is loaded on demand, so a preview before parsing cannot render.
  assert.throws(() => courseWeek({}, Date.UTC(2026, 8, 6)), /日历解析器尚未加载/);

  const data = await parseCourseCalendar(CALENDAR);
  const firstWeek = courseWeek(data, Date.UTC(2026, 8, 6));
  assert.deepEqual(firstWeek.map(course => ({
    name: course.name,
    location: course.location,
    date: course.date,
    time: course.time,
  })), [{ name: "合成高等数学", location: "合成楼 101", date: "2026-09-09", time: "08:00–09:40" }]);

  const secondWeek = courseWeek(data, Date.UTC(2026, 8, 13));
  assert.deepEqual(secondWeek.map(course => course.date), ["2026-09-16"]);
});

test("rejects a calendar without events", async () => {
  const { parseCourseCalendar } = await import("../../web/timetable.js");
  await assert.rejects(parseCourseCalendar(EMPTY_CALENDAR), /日历没有课程或内容过多/);
});
