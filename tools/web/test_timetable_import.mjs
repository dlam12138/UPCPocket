import assert from "node:assert/strict";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const START = Date.UTC(2026, 8, 6);
const DAY = 86_400_000;

export function makeSemesterFixture() {
  const term = {
    year: "2026-2027",
    term: "1",
    startDay: "2026-09-06",
    totalWeeks: 19,
  };
  const weeks = Array.from({ length: term.totalWeeks }, (_, index) => ({
    week: index + 1,
    dates: Array.from({ length: 7 }, (_, day) =>
      new Date(START + (index * 7 + day) * DAY).toISOString().slice(0, 10),
    ),
    classes: Array.from({ length: 7 }, () => ({})),
  }));

  function add(week, day, cell, fields) {
    const slots = weeks[week - 1].classes[day];
    (slots[`c_${cell}`] ??= []).push({
      course_id: `SYN-${week}-${day}-${cell}`,
      course_name: "合成课程",
      teacher: "示例教师",
      location: "示例教学楼 101",
      weekday: String(day),
      week: String(week),
      week_type: "0",
      lessArr: [cell],
      order: cell,
      totalLength: 1,
      ...fields,
    });
  }

  add(1, 0, 1, {
    course_name: "示例物理实验",
    teacher: "示例教师甲",
    location: "合成实验楼 A101",
    weekday: "1",
    week: "1，3~5",
    lessArr: [1, 2, 4],
    totalLength: 3,
    course_time: "08:00~11:30",
    course_type: "合成必修课",
  });
  add(1, 0, 2, {
    course_name: "示例物理实验",
    teacher: "示例教师乙",
    location: "合成实验楼 B202",
    lessArr: [2, 3],
    totalLength: 2,
  });
  add(1, 1, 6, {
    course_name: "示例工程制图",
    lessArr: [6, 7],
    totalLength: 2,
  });

  add(11, 0, 3, { course_name: "示例周日研讨" });
  add(11, 1, 1, {
    course_name: "示例高等数学",
    lessArr: [1, 2],
    totalLength: 2,
  });
  add(11, 1, 6, {
    course_name: "示例材料实验",
    lessArr: [6, 7, 9],
    totalLength: 3,
  });
  add(11, 2, 3, {
    course_name: "示例程序设计",
    lessArr: [3, 4],
    totalLength: 2,
  });
  add(11, 3, 1, {
    course_name: "示例工程制图",
    lessArr: [1, 2],
    totalLength: 2,
  });
  add(11, 4, 6, {
    course_name: "示例人工智能导论",
    lessArr: [6, 7],
    totalLength: 2,
  });
  add(11, 5, 3, {
    course_name: "示例学术英语",
    lessArr: [3, 4],
    totalLength: 2,
  });
  add(11, 5, 10, {
    course_name: "示例大学物理",
    lessArr: [10, 11],
    totalLength: 2,
  });
  add(11, 6, 8, {
    course_name: "示例校园实践",
    lessArr: [8, 9],
    totalLength: 2,
  });
  add(19, 6, 3, { course_name: "示例末周课程", week: "18-19" });

  return { version: 3, encoding: "UTF-8", term, weeks, errors: [] };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv[2] === "--fixture") {
    process.stdout.write(`${JSON.stringify(makeSemesterFixture(), null, 2)}\n`);
  } else {
    const {
      parseSemesterExport,
      readSemesterFile,
      readSemesterText,
      TimetableImportError,
    } = await import("../../web/timetable-import.js");

    test("reads a UTF-8 V3 file with all 19 weeks, including an empty week", async () => {
      const fixture = makeSemesterFixture();
      const file = new File([JSON.stringify(fixture)], "upc-timetable.json", {
        type: "application/json",
      });
      const semester = await readSemesterFile(file);
      assert.deepEqual(semester.term, { ...fixture.term, startDay: START });
      assert.deepEqual(
        semester.weeks.map((week) => week.number),
        fixture.weeks.map((week) => week.week),
      );
      assert.deepEqual(semester.weeks[1].courses, []);
      assert.deepEqual(
        semester.weeks[18].dates,
        fixture.weeks[18].dates.map((date) => Date.parse(date)),
      );
      assert.equal(semester.weeks[18].courses[0].name, "示例末周课程");
      assert.equal(
        semester.weeks[0].courses.find(
          (course) => course.name === "示例工程制图",
        ).date,
        "2026-09-07",
      );
    });

    test("imports pasted UTF-8 text with the same validation as files", async () => {
      const fixture = makeSemesterFixture();
      const json = JSON.stringify(fixture);
      const expected = await readSemesterFile(
        new File([json], "upc-timetable.json"),
      );
      assert.deepEqual(readSemesterText(json), expected);
      assert.deepEqual(readSemesterText(`\uFEFF${json}`), expected);
      assert.equal(expected.weeks[0].courses[0].name, "示例物理实验");

      assert.throws(() => readSemesterText("{"), TimetableImportError);
      fixture.weeks.pop();
      assert.throws(
        () => readSemesterText(JSON.stringify(fixture)),
        TimetableImportError,
      );
    });

    test("uses actual Sunday dates, splits nonconsecutive periods, and keeps same-name courses distinct", () => {
      const fixture = makeSemesterFixture();
      const { courses } = parseSemesterExport(fixture).weeks[0];
      const split = courses
        .filter((course) => course.teacher === "示例教师甲")
        .sort((left, right) => left.startPeriod - right.startPeriod);
      assert.deepEqual(
        split.map((course) => [course.startPeriod, course.endPeriod]),
        [
          [1, 2],
          [4, 4],
        ],
      );
      for (const course of split) {
        assert.equal(course.date, "2026-09-06");
        assert.equal(course.location, "合成实验楼 A101");
        assert.equal(course.weekLabel, "1，3~5");
        assert.deepEqual(course.sourcePeriods, [1, 2, 4]);
        assert.deepEqual(course.raw, fixture.weeks[0].classes[0].c_1[0]);
      }
      const overlap = courses.find((course) => course.teacher === "示例教师乙");
      assert.equal(overlap.name, split[0].name);
      assert.equal(overlap.location, "合成实验楼 B202");
      assert.deepEqual([overlap.startPeriod, overlap.endPeriod], [2, 3]);
    });

    test("rejects failed, missing, duplicate and misaligned school weeks", () => {
      const failed = makeSemesterFixture();
      failed.errors.push({ week: 3, message: "合成读取失败" });
      assert.throws(() => parseSemesterExport(failed));

      const missing = makeSemesterFixture();
      missing.weeks.splice(2, 1);
      assert.throws(() => parseSemesterExport(missing));

      const duplicate = makeSemesterFixture();
      duplicate.weeks[2] = duplicate.weeks[1];
      assert.throws(() => parseSemesterExport(duplicate));

      const misaligned = makeSemesterFixture();
      misaligned.weeks[2].dates[2] = misaligned.weeks[2].dates[3];
      assert.throws(() => parseSemesterExport(misaligned));
    });

    test("rejects courses outside their source week and invalid periods", () => {
      const excluded = makeSemesterFixture();
      excluded.weeks[0].classes[0].c_1[0].week = "2,4~6";
      assert.throws(() => parseSemesterExport(excluded));

      const badPeriod = makeSemesterFixture();
      badPeriod.weeks[0].classes[0].c_1[0].lessArr = [1, 15];
      assert.throws(() => parseSemesterExport(badPeriod));
    });

    test("rejects malformed UTF-8 bytes and malformed JSON", async () => {
      const fixture = makeSemesterFixture();
      const json = JSON.stringify(fixture);
      const courseName = "示例物理实验";
      const nameOffset = json.indexOf(courseName);
      const malformedBytes = new File(
        [
          json.slice(0, nameOffset),
          Uint8Array.of(0xc3, 0x28),
          json.slice(nameOffset + courseName.length),
        ],
        "damaged.json",
      );
      await assert.rejects(
        () => readSemesterFile(malformedBytes),
        /有效的 UTF-8/,
      );

      const malformedJson = new File(["{"], "damaged.json");
      await assert.rejects(() => readSemesterFile(malformedJson));
    });
  }
}
