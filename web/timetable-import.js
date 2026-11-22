// @ts-check

const DAY = 86_400_000;
const MAX_FILE_BYTES = 16 * 1024 * 1024;
const MAX_WEEKS = 30;
const MAX_PERIOD = 14;

// Same published 1–12 period schedule as Android's UpcTimetableSchedule.
const OFFICIAL_PERIODS = [
  ["08:00", "08:45"], ["08:50", "09:35"], ["09:55", "10:40"],
  ["10:45", "11:30"], ["11:35", "12:20"], ["14:00", "14:45"],
  ["14:50", "15:35"], ["15:55", "16:40"], ["16:45", "17:30"],
  ["19:00", "19:45"], ["19:50", "20:35"], ["20:40", "21:25"],
];

/** @typedef {{year: string, term: string, startDay: number, totalWeeks: number}} Term */
/**
 * @typedef {Object} Course
 * @property {string} name
 * @property {string} location
 * @property {string} teacher
 * @property {string} date
 * @property {string} time
 * @property {number} startPeriod
 * @property {number} endPeriod
 * @property {number[]} sourcePeriods
 * @property {string} weekLabel
 * @property {Record<string, unknown>} raw
 */
/** @typedef {{number: number, dates: number[], courses: Course[]}} TeachingWeek */
/** @typedef {{key: string, term: Term, weeks: TeachingWeek[]}} Semester */

export class TimetableImportError extends Error {}

/** @param {number} period */
export function periodClock(period) {
  const range = OFFICIAL_PERIODS[period - 1];
  return range ? { start: range[0], end: range[1] } : null;
}

/** @param {unknown} value @returns {value is Record<string, unknown>} */
function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** @param {unknown} value */
function sourceText(value) {
  const text = typeof value === "string" || typeof value === "number" ? String(value).trim() : "";
  if (text.includes("\uFFFD")) throw new TimetableImportError("文件含损坏的文字，请重新导出原始 JSON");
  return text;
}

/** @param {unknown} value */
function dateValue(value) {
  const match = typeof value === "string" && /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(value);
  if (!match) return NaN;
  const iso = `${match[1]}-${match[2].padStart(2, "0")}-${match[3].padStart(2, "0")}`;
  const date = Date.parse(`${iso}T00:00:00Z`);
  return Number.isFinite(date) && dateId(date) === iso ? date : NaN;
}

/** @param {number} date */
function dateId(date) {
  return new Date(date).toISOString().slice(0, 10);
}

/** @param {unknown} value */
function periodValue(value) {
  if (typeof value === "number") return value;
  if (typeof value === "string" && /^\d+(?:\.0+)?$/.test(value.trim())) return Number(value);
  return NaN;
}

/** @param {string} label @param {number} week */
function includesWeek(label, week) {
  const tokens = label.replace(/\s*([-~—－])\s*/g, "$1").split(/[,，、;；\s]+/).filter(Boolean);
  let includes = false;
  for (const token of tokens) {
    const range = /^(\d{1,2})(?:[-~—－](\d{1,2}))?周?$/.exec(token);
    if (!range) return false;
    const start = Number(range[1]);
    const end = range[2] ? Number(range[2]) : start;
    if (end < start) return false;
    if (start <= week && week <= end) includes = true;
  }
  return includes;
}

/** @param {number[]} periods @returns {Array<{start: number, end: number}>} */
function consecutiveGroups(periods) {
  /** @type {Array<{start: number, end: number}>} */
  const groups = [];
  for (const period of [...new Set(periods)].sort((a, b) => a - b)) {
    const last = groups.at(-1);
    if (last && last.end + 1 === period) last.end = period;
    else groups.push({ start: period, end: period });
  }
  return groups;
}

/** @param {string} value */
function sourceClock(value) {
  const match = /^(\d{1,2})\s*[:：]\s*(\d{2})\s*[~～\-–—]\s*(\d{1,2})\s*[:：]\s*(\d{2})$/.exec(value);
  if (!match) return "";
  const [, startHour, startMinute, endHour, endMinute] = match;
  const start = Number(startHour) * 60 + Number(startMinute);
  const end = Number(endHour) * 60 + Number(endMinute);
  if (Number(startMinute) > 59 || Number(endMinute) > 59 || end > 1440 || start >= end) return "";
  return `${startHour.padStart(2, "0")}:${startMinute}–${endHour.padStart(2, "0")}:${endMinute}`;
}

/** @param {number} start @param {number} end */
function officialClock(start, end) {
  const first = periodClock(start);
  const last = periodClock(end);
  return first && last ? `${first.start}–${last.end}` : "";
}

/** @param {unknown} day @param {number} date @param {number} week @returns {Course[]} */
function parseDay(day, date, week) {
  if (!isRecord(day)) throw new TimetableImportError(`第 ${week} 周的课程表结构不正确`);
  /** @type {Course[]} */
  const courses = [];
  for (const [key, entries] of Object.entries(day)) {
    if (!key.startsWith("c_")) continue;
    const cell = /^c_(\d{1,2})$/.exec(key);
    if (!cell || (entries !== null && !Array.isArray(entries))) {
      throw new TimetableImportError(`第 ${week} 周的节次单元格格式不正确`);
    }
    if (entries === null || entries.length === 0) continue;
    if (Number(cell[1]) < 1 || Number(cell[1]) > MAX_PERIOD) {
      throw new TimetableImportError(`第 ${week} 周含超出范围的节次`);
    }
    for (const raw of entries) {
      if (!isRecord(raw) || typeof raw.course_name !== "string" || !Array.isArray(raw.lessArr)) {
        throw new TimetableImportError(`第 ${week} 周的课程缺少课程名或节次`);
      }
      const name = sourceText(raw.course_name);
      const sourcePeriods = Array.from(raw.lessArr, periodValue);
      if (!name || !sourcePeriods.length || sourcePeriods.some(p => !Number.isInteger(p) || p < 1 || p > MAX_PERIOD)) {
        throw new TimetableImportError(`第 ${week} 周的课程名或节次不合法`);
      }
      const weekLabel = sourceText(raw.week);
      if (!includesWeek(weekLabel, week)) {
        throw new TimetableImportError(`第 ${week} 周的课程周次与教学周不一致`);
      }
      const groups = consecutiveGroups(sourcePeriods);
      const clock = sourceClock(sourceText(raw.course_time));
      const course = {
        name, date: dateId(date), location: sourceText(raw.location), teacher: sourceText(raw.teacher),
        sourcePeriods, weekLabel, raw,
      };
      for (const { start, end } of groups) {
        // An overall start and end from the source do not describe each split group.
        const time = (groups.length === 1 && clock) || officialClock(start, end);
        courses.push({ ...course, time, startPeriod: start, endPeriod: end });
      }
    }
  }
  return courses.sort((a, b) => a.startPeriod - b.startPeriod || b.endPeriod - a.endPeriod);
}

/** @param {unknown} raw @returns {Semester} */
export function parseSemesterExport(raw) {
  if (!isRecord(raw) || raw.version !== 3 || raw.encoding !== "UTF-8") {
    throw new TimetableImportError("请选择 UPC Pocket V3 导出的 UTF-8 JSON");
  }
  if (!isRecord(raw.term) || typeof raw.term.year !== "string" || typeof raw.term.term !== "string") {
    throw new TimetableImportError("文件缺少学年或学期信息");
  }
  const { totalWeeks } = raw.term;
  const year = sourceText(raw.term.year);
  const termCode = sourceText(raw.term.term);
  const startDay = dateValue(raw.term.startDay);
  if (!year || !termCode || year.length > 40 || termCode.length > 20 || !Number.isFinite(startDay) ||
      typeof totalWeeks !== "number" || !Number.isInteger(totalWeeks) || totalWeeks < 1 || totalWeeks > MAX_WEEKS) {
    throw new TimetableImportError("学期信息不完整或无效");
  }
  if (!Array.isArray(raw.errors) || raw.errors.length || !Array.isArray(raw.weeks) || raw.weeks.length !== totalWeeks) {
    throw new TimetableImportError("文件含失败或缺失的教学周，请重新导出完整学期");
  }
  /** @type {Map<number, TeachingWeek>} */
  const weeks = new Map();
  for (const entry of raw.weeks) {
    if (!isRecord(entry) || typeof entry.week !== "number" || !Number.isInteger(entry.week) ||
        entry.week < 1 || entry.week > totalWeeks) {
      throw new TimetableImportError("文件含无效的教学周编号");
    }
    const number = entry.week;
    if (weeks.has(number)) throw new TimetableImportError(`文件包含重复的第 ${number} 周`);
    if (!Array.isArray(entry.dates) || entry.dates.length !== 7 ||
        !Array.isArray(entry.classes) || entry.classes.length !== 7) {
      throw new TimetableImportError(`第 ${number} 周缺少完整的七天课表`);
    }
    const dates = Array.from(entry.dates, dateValue);
    const first = startDay + (number - 1) * 7 * DAY;
    if (dates.some((date, index) => date !== first + index * DAY)) {
      throw new TimetableImportError(`第 ${number} 周的日期与教学周不一致`);
    }
    const courses = Array.from(entry.classes, (day, index) => parseDay(day, dates[index], number)).flat();
    weeks.set(number, { number, dates, courses });
  }
  return {
    key: JSON.stringify([year, termCode]),
    term: { year, term: termCode, startDay, totalWeeks },
    weeks: [...weeks.values()].sort((first, second) => first.number - second.number),
  };
}

/** @param {File} file */
export async function readSemesterFile(file) {
  if (file.size > MAX_FILE_BYTES) throw new TimetableImportError("课表文件不能超过 16 MB");
  let bytes;
  try {
    bytes = await file.arrayBuffer();
  } catch {
    throw new TimetableImportError("无法读取课表文件，请在「文件」中确认已下载后重试");
  }
  let text;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new TimetableImportError("文件不是有效的 UTF-8，请重新导出原始 JSON");
  }
  return parseSemesterText(text);
}

/** @param {string} text */
export function readSemesterText(text) {
  // Count potentially large pastes without allocating another UTF-8 copy.
  let size = text.length;
  if (size * 3 > MAX_FILE_BYTES) {
    for (let pos = 0; pos < text.length && size <= MAX_FILE_BYTES; pos++) {
      const code = text.charCodeAt(pos);
      if (code < 0x80) continue;
      size += code < 0x800 ? 1 : 2;
      if (code >= 0xd800 && code <= 0xdbff) {
        const next = text.charCodeAt(pos + 1);
        if (next >= 0xdc00 && next <= 0xdfff) pos++;
      }
    }
  }
  if (size > MAX_FILE_BYTES) throw new TimetableImportError("课表 JSON 不能超过 16 MB");
  return parseSemesterText(text.replace(/^\uFEFF/, ""));
}

/** @param {string} text */
function parseSemesterText(text) {
  if (!text.trim()) throw new TimetableImportError("课表 JSON 为空");
  if (text.includes("\uFFFD")) throw new TimetableImportError("课表含损坏的文字，请重新导出原始 JSON");
  /** @type {unknown} */
  let raw;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new TimetableImportError("课表 JSON 格式错误，请重新复制或选择原始导出文件");
  }
  return parseSemesterExport(raw);
}
