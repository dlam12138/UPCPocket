import { periodClock, readSemesterFile, readSemesterText, TimetableImportError } from "./timetable-import.js";
import { deleteTimetableSemester, listTimetableSemesters, saveTimetableSemester } from "./timetable-store.js";

const DAY = 86_400_000;
const SHANGHAI_OFFSET = 8 * 3_600_000;
const DAYS = ["日", "一", "二", "三", "四", "五", "六"];
const PERIOD_COUNT = 14;
const MAX_FILE_BYTES = 1_048_576;
// The Android timetable uses the same fills across both color schemes.
const COURSE_COLORS = [
  "#8dd3c7", "#ffffb3", "#bebada", "#fb8072", "#80b1d3",
  "#fdb462", "#b3de69", "#fccde5", "#e6ab02", "#bc80bd",
  "#ccebc5", "#ffed6f", "#c40063", "#d01010", "#9d7660",
  "#ff7f00", "#6a3d9a", "#386cb0", "#33a02c", "#1b9e77",
];
const LIGHT_TEXT_SLOTS = new Set([12, 13, 16, 17]);
// The iCalendar parser is ~260 KB and only needed for .ics previews, so it is
// loaded on demand instead of at startup.
let ICAL = null;
let campusTimezone = null;
let icalRequest = null;

class CalendarParserUnavailable extends Error {}

function loadIcal() {
  if (ICAL) return Promise.resolve(ICAL);
  icalRequest ??= import("./vendor/ical.js?v=20").then(module => {
    ICAL = module.default;
    campusTimezone = new ICAL.Component(ICAL.parse([
      "BEGIN:VTIMEZONE", "TZID:Asia/Shanghai", "BEGIN:STANDARD",
      "DTSTART:19920101T000000", "TZOFFSETFROM:+0800", "TZOFFSETTO:+0800",
      "END:STANDARD", "END:VTIMEZONE",
    ].join("\r\n")));
    return ICAL;
  }).catch(error => {
    icalRequest = null;
    const failure = new CalendarParserUnavailable("日历解析器尚未加载");
    failure.cause = error;
    throw failure;
  });
  return icalRequest;
}

function dateId(timestamp) {
  return new Date(timestamp).toISOString().slice(0, 10);
}

function campusToday() {
  return Math.floor((Date.now() + SHANGHAI_OFFSET) / DAY) * DAY;
}

function sunday(day) {
  return day - new Date(day).getUTCDay() * DAY;
}

function shortDate(day) {
  const date = new Date(day);
  return `${date.getUTCMonth() + 1}月${date.getUTCDate()}日`;
}

function calendarDate(day) {
  const civil = new Date(day);
  return `${civil.getUTCMonth() + 1}/${civil.getUTCDate()}`;
}

function timestamp(time) {
  // Floating course times mean Shanghai local time in Pocket.
  if (time.isDate || time.zone === ICAL.Timezone.localTimezone) {
    return time.toUnixTime() * 1000 - SHANGHAI_OFFSET;
  }
  return time.toUnixTime() * 1000;
}

function campusClock(instant) {
  return new Date(instant + SHANGHAI_OFFSET).toISOString().slice(11, 16);
}

function registerZones(zones) {
  if (!ICAL) throw new Error("日历解析器尚未加载");
  ICAL.TimezoneService.reset();
  for (const component of [campusTimezone, ...zones]) {
    ICAL.TimezoneService.register(new ICAL.Timezone({ component }));
  }
}

export async function parseCourseCalendar(text) {
  await loadIcal();
  const calendar = new ICAL.Component(ICAL.parse(text.trim()));
  if (calendar.name !== "vcalendar") throw new Error("请选择 .ics 日历文件");
  const zones = calendar.getAllSubcomponents("vtimezone");
  const components = calendar.getAllSubcomponents("vevent");
  if (!components.length || components.length > 2000 || zones.length > 50) {
    throw new Error("日历没有课程或内容过多");
  }
  registerZones(zones);
  const series = new Map();
  const changes = [];
  for (const component of components) {
    const event = new ICAL.Event(component, { exceptions: [] });
    for (const field of ["dtstart", "dtend", "recurrence-id", "rdate", "exdate"]) {
      for (const property of component.getAllProperties(field)) {
        const zone = property.getParameter("tzid");
        if (zone && !ICAL.TimezoneService.has(zone)) throw new Error("日历中有未定义的时区");
      }
    }
    const cancelled = component.getFirstPropertyValue("status") === "CANCELLED";
    if (!event.uid || (!event.startDate && !cancelled)) throw new Error("课程缺少标识或开始时间");
    for (const rule of component.getAllProperties("rrule")) {
      if (!["DAILY", "WEEKLY", "MONTHLY", "YEARLY"].includes(rule.getFirstValue().freq)) {
        throw new Error("日历包含不支持的重复频率");
      }
    }
    if (event.isRecurrenceException()) {
      if (event.modifiesFuture()) throw new Error("课表含连续课程变更，请导入已展开的日历");
      if (cancelled && !event.startDate) event.startDate = event.recurrenceId;
      changes.push(event);
    } else {
      if (series.has(event.uid)) throw new Error("日历包含重复的课程标识");
      series.set(event.uid, event);
    }
  }
  for (const change of changes) {
    if (!series.has(change.uid)) throw new Error("课程变更缺少原课程");
    series.get(change.uid).relateException(change);
  }
  return { series, changes, zones };
}

export function courseWeek(data, firstDay) {
  registerZones(data.zones);
  const start = firstDay - SHANGHAI_OFFSET;
  const end = start + 7 * DAY;
  const courses = new Map();
  let iterations = 0;

  function add(event, began, finished) {
    if (event.component.getFirstPropertyValue("status") === "CANCELLED") return;
    const begins = timestamp(began);
    const finishes = timestamp(finished);
    if (!Number.isFinite(begins) || !Number.isFinite(finishes) || finishes < begins) {
      throw new Error("课程时间无效");
    }
    if (begins < start || begins >= end) return;
    const name = event.summary?.trim() || "未命名课程";
    courses.set(`${event.uid}|${begins}`, {
      name,
      location: event.location?.trim() || "",
      date: dateId(begins + SHANGHAI_OFFSET),
      begins,
      finishes,
      time: began.isDate ? "全天" : finishes === begins
        ? campusClock(begins)
        : `${campusClock(begins)}–${campusClock(finishes)}`,
    });
  }

  for (const event of data.series.values()) {
    if (event.component.getFirstPropertyValue("status") === "CANCELLED") continue;
    if (!event.isRecurring()) {
      add(event, event.startDate, event.endDate);
      continue;
    }
    const iterator = event.iterator();
    for (let next = iterator.next(); next && timestamp(next) < end; next = iterator.next()) {
      if (++iterations > 30_000) throw new Error("日历的重复课程过多");
      if (timestamp(next) < start) continue;
      const occurrence = event.getOccurrenceDetails(next);
      add(occurrence.item, occurrence.startDate, occurrence.endDate);
    }
  }
  for (const event of data.changes) {
    const master = data.series.get(event.uid);
    if (master.component.getFirstPropertyValue("status") !== "CANCELLED") {
      add(event, event.startDate, event.endDate);
    }
  }
  return [...courses.values()].sort((first, second) => first.begins - second.begins || second.finishes - first.finishes);
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

function tone(name) {
  let hash = 0;
  for (const character of name) hash = (hash * 31 + character.codePointAt(0)) >>> 0;
  return hash % COURSE_COLORS.length;
}

function fieldLabel(value) {
  return typeof value === "string" || typeof value === "number" ? String(value).trim() : "";
}

function courseIdentity(course) {
  return fieldLabel(course.raw?.course_id) || course.name;
}

function periodLabel(course) {
  if (!course.startPeriod) return course.time;
  const { startPeriod, endPeriod } = course;
  return `第 ${startPeriod === endPeriod ? startPeriod : `${startPeriod}–${endPeriod}`} 节`;
}

function semesterLabel({ year, term }) {
  const season = new Map([["1", "秋季"], ["2", "春季"], ["3", "夏季"]]).get(term) ?? `第${term}学期`;
  return `${year} ${season}`;
}

function makeSemesterView(semester) {
  const days = new Map();
  const identities = new Set();
  const { weeks } = semester;
  for (const week of weeks) {
    for (const day of week.dates) days.set(day, { schoolWeek: week.number, courses: [] });
    for (const course of week.courses) {
      days.get(Date.parse(course.date)).courses.push(course);
      identities.add(courseIdentity(course));
    }
  }
  const colors = new Map([...identities].sort().slice(0, COURSE_COLORS.length).map((identity, index) => [identity, index]));
  return { ...semester, days, colors, firstWeek: sunday(weeks[0].dates[0]), lastWeek: sunday(weeks.at(-1).dates[6]) };
}

function datesOf(firstDay) {
  return DAYS.map((_, index) => firstDay + index * DAY);
}

function weekLabel(semester, firstDay) {
  const weeks = new Set(datesOf(firstDay).map(day => semester.days.get(day)?.schoolWeek).filter(Boolean));
  return `第 ${[...weeks].join("–")} 周`;
}

function overlappingCourses(courses) {
  const groups = [];
  const ordered = [...courses].sort((a, b) => a.startPeriod - b.startPeriod || b.endPeriod - a.endPeriod);
  for (const course of ordered) {
    const group = groups.at(-1);
    if (group && group.lastPeriod >= course.startPeriod) {
      group.lastPeriod = Math.max(group.lastPeriod, course.endPeriod);
      group.courses.push(course);
    } else {
      groups.push({ firstPeriod: course.startPeriod, lastPeriod: course.endPeriod, courses: [course] });
    }
  }
  return groups;
}

let refresh = () => {};
export function refreshTimetableDate() { refresh(); }

export function initializeTimetable() {
  const root = document.querySelector("#timetable-app");
  const pageActions = document.querySelector("#timetable-page-actions");
  if (!root || root.childElementCount) return;
  root.innerHTML = `
    <section class="timetable-source" aria-label="课表来源">
      <div class="timetable-empty detail-card workspace-card">
        <svg class="icon" aria-hidden="true"><use href="#i-calendar"/></svg>
        <div><h2>导入学期课表</h2><p>在「课表选项」设置 Safari 书签，或直接导入已下载的 JSON。</p></div>
      </div>
      <div class="timetable-source-actions">
        <button type="button" class="button timetable-paste-action">粘贴导入</button>
        <label class="button button-secondary timetable-file timetable-json-action"><span>导入 JSON</span>
          <input aria-label="导入 JSON 课表" type="file" accept=".json,application/json">
        </label>
        <details class="timetable-source-options">
          <summary class="text-button">课表选项</summary>
          <div class="timetable-source-menu">
            <button type="button" class="text-button timetable-setup-action">设置 Safari 书签</button>
            <a class="text-button" href="https://app.upc.edu.cn/site/weekschedule/index" target="_blank" rel="noopener noreferrer">
              前往学校课表 <svg class="icon" aria-hidden="true"><use href="#i-external"/></svg>
            </a>
            <label class="text-button timetable-file">预览 .ics 文件
              <input aria-label="预览 ICS 课表" type="file" accept=".ics,text/calendar">
            </label>
            <button type="button" data-week="today" class="text-button timetable-this-week" hidden>本周</button>
            <button type="button" class="text-button timetable-view-action" aria-pressed="false" hidden>按日查看</button>
            <button type="button" class="text-button timetable-clear" hidden>删除本机课表</button>
          </div>
        </details>
      </div>
      <p class="timetable-import-status" role="status"></p>
    </section>
    <section class="timetable-week" aria-labelledby="timetable-week-title" hidden>
      <div class="timetable-week-toolbar">
        <div class="timetable-term-control">
          <span class="timetable-term-label"></span>
          <span class="timetable-term-count"></span>
          <select class="timetable-term" aria-label="课表学期"></select>
        </div>
        <div class="timetable-week-steps">
          <button type="button" data-week="-1" class="text-button" aria-label="上一周"></button>
          <div class="timetable-week-jump">
            <h2 id="timetable-week-title"></h2>
            <p class="timetable-week-dates"></p>
            <select class="timetable-week-select" aria-label="跳转到教学周"></select>
          </div>
          <button type="button" data-week="1" class="text-button" aria-label="下一周"></button>
        </div>
      </div>
      <div class="timetable-week-scroll" role="region" aria-label="课表日期与课程" tabindex="0">
        <div class="timetable-board">
          <div class="timetable-days" aria-label="选择日期"></div>
          <div class="timetable-columns"></div>
          <p class="timetable-week-empty" hidden>本周无课程</p>
        </div>
      </div>
    </section>
    <dialog class="settings-dialog timetable-detail" aria-labelledby="timetable-detail-title">
      <h2 id="timetable-detail-title"></h2>
      <p class="timetable-detail-subtitle"></p>
      <p class="timetable-detail-weeks" hidden></p>
      <div class="timetable-detail-body"></div>
      <form method="dialog"><button class="text-button">关闭</button></form>
    </dialog>
    <dialog class="settings-dialog timetable-delete" aria-labelledby="timetable-delete-title">
      <h2 id="timetable-delete-title">删除本机课表？</h2>
      <p></p>
      <form method="dialog"><button class="text-button" value="cancel" autofocus>取消</button><button class="button" value="delete">删除</button></form>
    </dialog>
    <dialog class="settings-dialog timetable-transfer timetable-paste" aria-labelledby="timetable-paste-title">
      <h2 id="timetable-paste-title">粘贴课表 JSON</h2>
      <p id="timetable-paste-hint">请在输入框长按并选择「粘贴」。</p>
      <textarea aria-label="粘贴课表内容" aria-describedby="timetable-paste-hint" placeholder="在此粘贴" rows="2"
        autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>
      <form method="dialog"><button class="text-button">取消</button></form>
    </dialog>
    <dialog class="settings-dialog timetable-transfer timetable-setup" aria-labelledby="timetable-setup-title">
      <h2 id="timetable-setup-title">设置 Safari 书签</h2>
      <ol>
        <li>复制书签脚本。在 Safari 新建一个书签，再到书签列表长按编辑。把网址替换为脚本，取名「导出课表」。</li>
        <li>在 Safari 登录<a href="https://app.upc.edu.cn/site/weekschedule/index" target="_blank" rel="noopener noreferrer">学校课表</a>。在该页点书签「导出课表」，再依次点「导出整学期」和「复制课表 JSON」。</li>
        <li>回到 Pocket 点「粘贴导入」。系统询问时允许粘贴。</li>
      </ol>
      <textarea aria-label="书签脚本" rows="3" wrap="off" spellcheck="false" readonly hidden></textarea>
      <p class="timetable-copy-status" role="status"></p>
      <form method="dialog">
        <button type="button" class="button timetable-copy-script">复制书签脚本</button>
        <button class="text-button">完成</button>
      </form>
    </dialog>`;
  const [jsonInput, calendarInput] = root.querySelectorAll('input[type="file"]');
  const paste = root.querySelector(".timetable-paste-action");
  const fileAction = root.querySelector(".timetable-json-action");
  const pasteDialog = root.querySelector(".timetable-paste");
  const pasteArea = pasteDialog.querySelector("textarea");
  const setup = root.querySelector(".timetable-setup-action");
  const setupDialog = root.querySelector(".timetable-setup");
  const bookmarkletCode = setupDialog.querySelector("textarea");
  const copyScript = setupDialog.querySelector(".timetable-copy-script");
  const copyStatus = setupDialog.querySelector(".timetable-copy-status");
  const source = root.querySelector(".timetable-source");
  const sourceActions = source.querySelector(".timetable-source-actions");
  const empty = root.querySelector(".timetable-empty");
  const options = root.querySelector(".timetable-source-options");
  const menu = options.querySelector(".timetable-source-menu");
  const status = root.querySelector(".timetable-import-status");
  const clear = root.querySelector(".timetable-clear");
  const week = root.querySelector(".timetable-week");
  const heading = week.querySelector("#timetable-week-title");
  const dateRange = week.querySelector(".timetable-week-dates");
  const termLabel = week.querySelector(".timetable-term-label");
  const termCount = week.querySelector(".timetable-term-count");
  const terms = week.querySelector(".timetable-term");
  const weekNumbers = week.querySelector(".timetable-week-select");
  const previousWeek = week.querySelector('[data-week="-1"]');
  const followingWeek = week.querySelector('[data-week="1"]');
  const thisWeek = menu.querySelector(".timetable-this-week");
  const days = week.querySelector(".timetable-days");
  const columns = week.querySelector(".timetable-columns");
  const emptyWeek = week.querySelector(".timetable-week-empty");
  const board = week.querySelector(".timetable-board");
  const scroll = week.querySelector(".timetable-week-scroll");
  const viewAction = menu.querySelector(".timetable-view-action");
  const dialog = root.querySelector(".timetable-detail");
  const detailSubtitle = dialog.querySelector(".timetable-detail-subtitle");
  const detailWeeks = dialog.querySelector(".timetable-detail-weeks");
  const detailBody = dialog.querySelector(".timetable-detail-body");
  const deleteDialog = root.querySelector(".timetable-delete");
  const todaySummary = document.querySelector("#today-course-summary");
  const semesters = new Map();
  let semester;
  let calendar;
  let previewingCalendar = false;
  let daily = false;
  let busy = true;
  let restoreFailed = false;
  let deleteKey;
  let today = campusToday();
  let selected = today;
  let selectedWeek = sunday(today);
  let calendarWeek = [];

  function report(message, error = false) {
    status.textContent = message;
    status.classList.toggle("operation-error", error);
  }

  function setBusy(value) {
    busy = value;
    jsonInput.disabled = calendarInput.disabled = paste.disabled = clear.disabled = value;
  }

  function currentSemester() {
    return [...semesters.values()].find(item => item.days.has(today));
  }

  function renderSummary() {
    if (!todaySummary) return;
    const current = semester?.days.has(today) ? semester : currentSemester();
    let todays;
    if (previewingCalendar) {
      todays = courseWeek(calendar, sunday(today)).filter(course => course.date === dateId(today));
    } else if (current) {
      const seen = new Set();
      todays = [];
      for (const course of current.days.get(today).courses) {
        if (seen.has(course.raw)) continue;
        seen.add(course.raw);
        todays.push(course);
      }
    }
    const first = todays?.[0];
    if (first) {
      todaySummary.textContent = `${first.time || periodLabel(first)} ${first.name}${todays.length > 1 ? ` · 共 ${todays.length} 门` : ""}`;
    } else if (todays) {
      todaySummary.textContent = "本机课表今日无课";
    } else if (semesters.size) {
      todaySummary.textContent = "已导入的课表未覆盖今天";
    } else {
      todaySummary.textContent = restoreFailed ? "未能读取本机课表" : busy ? "正在读取本机课表…" : "还未导入学期课表";
    }
  }

  function showDetails(course) {
    const weekday = DAYS[new Date(`${course.date}T00:00:00Z`).getUTCDay()];
    const date = `${course.date} 周${weekday}`;
    const split = new Set(course.sourcePeriods).size > course.endPeriod - course.startPeriod + 1;
    const rows = [
      ["日期", date],
      ["节次", course.startPeriod ? periodLabel(course) : ""],
      ["原始节次", split ? course.sourcePeriods.join("、") : ""],
      ["周次", course.weekLabel],
      ["教师", course.startPeriod ? course.teacher || "未提供" : ""],
      ["地点", course.location || "未提供"],
      ["上课时间", course.time || "时间待确认"],
      ["课程类型", fieldLabel(course.raw?.course_type)],
      ["课程编号", fieldLabel(course.raw?.course_id)],
    ];
    const list = element("dl", "timetable-detail-fields");
    for (const [label, value] of rows) {
      if (value) list.append(element("dt", "", label), element("dd", "", value));
    }
    dialog.querySelector("h2").textContent = course.name;
    detailSubtitle.textContent = [date, periodLabel(course)].filter(Boolean).join(" · ");
    detailWeeks.hidden = !course.weekLabel;
    detailWeeks.textContent = course.weekLabel ? `${course.weekLabel} 周` : "";
    detailBody.replaceChildren(list);
    if (!dialog.open) dialog.showModal();
    else dialog.querySelector("form button").focus();
  }

  function courseButton(course, compact = false) {
    const identity = courseIdentity(course);
    const swatch = semester?.colors.get(identity) ?? tone(identity);
    const card = element("button", "timetable-course");
    card.style.setProperty("--course-paper", COURSE_COLORS[swatch]);
    card.style.setProperty("--course-text", LIGHT_TEXT_SLOTS.has(swatch) ? "#f7f7fa" : "#111318");
    card.type = "button";
    const name = element("strong", "timetable-course-name", course.name);
    const location = course.location || "地点未提供";
    if (compact) {
      card.append(name);
      if (course.endPeriod > course.startPeriod) {
        const time = element("span", "timetable-course-slot-time");
        const labels = course.time ? course.time.split("–") : ["时间待确认"];
        time.append(...labels.map(label => element("span", "", label)));
        card.append(time, element("span", "timetable-course-location", location));
      }
      card.setAttribute("aria-label", [course.name, periodLabel(course), course.time || "时间待确认", course.location].filter(Boolean).join("，"));
    } else {
      card.append(element("span", "timetable-course-time", [periodLabel(course), course.startPeriod ? course.time : ""].filter(Boolean).join(" · ")), name);
      if (course.location) card.append(element("span", "timetable-course-location", location));
    }
    card.addEventListener("click", () => showDetails(course));
    return card;
  }

  function overlapButton(group) {
    const { firstPeriod, lastPeriod, courses } = group;
    const card = element("button", "timetable-course timetable-overlap");
    card.type = "button";
    card.setAttribute("aria-label", `${periodLabel({ startPeriod: firstPeriod, endPeriod: lastPeriod })}，${courses.length} 门重叠课程`);
    card.append(element("strong", "", "重叠课程"), element("span", "timetable-overlap-count", `${courses.length} 门`));
    card.addEventListener("click", () => showOverlap(group));
    return card;
  }

  function showOverlap({ courses, firstPeriod, lastPeriod }) {
    dialog.querySelector("h2").textContent = `${periodLabel({ startPeriod: firstPeriod, endPeriod: lastPeriod })}重叠课程`;
    detailSubtitle.textContent = "";
    detailWeeks.hidden = true;
    detailBody.replaceChildren(...courses.map(course => courseButton(course)));
    dialog.showModal();
  }

  function renderDays(dates, events, showGrid) {
    days.replaceChildren();
    if (showGrid) days.append(element("span", "timetable-axis-title"));
    dates.forEach((date, index) => {
      const dateLabel = `周${DAYS[index]} ${shortDate(date)}`;
      const dayButton = element("button", "timetable-day-button");
      dayButton.type = "button";
      dayButton.dataset.day = date;
      dayButton.setAttribute("aria-label", events[index] ? dateLabel : `${dateLabel}，未导入`);
      if (daily) dayButton.setAttribute("aria-pressed", String(date === selected));
      if (date === today) dayButton.setAttribute("aria-current", "date");
      dayButton.append(element("span", "", `周${DAYS[index]}`), element("strong", "", calendarDate(date)));
      if (!events[index]) dayButton.append(element("span", "timetable-day-unavailable", "未导入"));
      dayButton.addEventListener("click", () => {
        daily = true;
        selected = date;
        renderCourses();
        days.querySelector(`[data-day="${date}"]`)?.focus({ preventScroll: true });
      });
      days.append(dayButton);
    });
  }

  function renderGrid(dates, events) {
    const axis = element("div", "timetable-period-axis");
    for (let period = 1; period <= PERIOD_COUNT; period++) {
      const row = element("div", "timetable-period");
      row.append(element("strong", "", String(period)));
      const clock = periodClock(period);
      if (clock) row.append(element("span", "", clock.start), element("span", "", clock.end));
      else row.append(element("span", "", "待确认"));
      axis.append(row);
    }
    columns.append(axis);
    dates.forEach((date, index) => {
      const day = element("div", "timetable-grid-day");
      day.dataset.day = date;
      day.setAttribute("role", "group");
      day.setAttribute("aria-label", `周${DAYS[index]} ${shortDate(date)}`);
      if (date === today) day.classList.add("is-today");
      if (!events[index]) day.classList.add("is-outside-term");
      for (const group of overlappingCourses(events[index]?.courses ?? [])) {
        const { firstPeriod, lastPeriod, courses } = group;
        const card = courses.length === 1 ? courseButton(courses[0], true) : overlapButton(group);
        card.style.gridRow = `${firstPeriod} / ${lastPeriod + 1}`;
        day.append(card);
      }
      columns.append(day);
    });
  }

  function renderAgenda(dates, events) {
    dates.forEach((date, index) => {
      if (daily && date !== selected) return;
      const column = element("section", "timetable-day-column");
      column.dataset.day = date;
      column.append(element("h3", "timetable-day-title", `周${DAYS[index]} · ${shortDate(date)}`));
      const item = events[index];
      const courses = item?.courses ?? [];
      if (!courses.length) column.append(element("p", "timetable-day-empty", item ? "无课程" : "没有导入这一天的课表"));
      for (const course of courses) column.append(courseButton(course));
      columns.append(column);
    });
  }

  function renderCourses() {
    const dates = datesOf(selectedWeek);
    const showGrid = !previewingCalendar && !daily;
    const events = previewingCalendar
      ? dates.map(day => ({ courses: calendarWeek.filter(course => course.date === dateId(day)) }))
      : dates.map(day => semester.days.get(day));
    week.classList.toggle("daily-mode", daily);
    board.classList.toggle("timetable-period-grid", showGrid);
    board.classList.toggle("timetable-agenda-grid", !showGrid && !daily);
    emptyWeek.hidden = !showGrid || events.some(day => day?.courses.length);
    emptyWeek.textContent = events.every(Boolean) ? "本周无课程" : "本周已导入的日期无课程";
    columns.hidden = !emptyWeek.hidden;
    viewAction.textContent = daily ? "查看整周" : "按日查看";
    viewAction.setAttribute("aria-pressed", String(daily));
    if (daily) pageActions.prepend(viewAction);
    else menu.insertBefore(viewAction, clear);
    renderDays(dates, events, showGrid);
    columns.replaceChildren();
    if (!showGrid) renderAgenda(dates, events);
    else if (emptyWeek.hidden) renderGrid(dates, events);
  }

  function renderWeek() {
    if (previewingCalendar) calendarWeek = courseWeek(calendar, selectedWeek);
    const range = `${calendarDate(selectedWeek)} – ${calendarDate(selectedWeek + 6 * DAY)}`;
    heading.textContent = previewingCalendar ? "日历预览" : weekLabel(semester, selectedWeek);
    dateRange.textContent = range;
    previousWeek.disabled = !previewingCalendar && selectedWeek <= semester.firstWeek;
    followingWeek.disabled = !previewingCalendar && selectedWeek >= semester.lastWeek;
    thisWeek.disabled = !previewingCalendar && !semester.days.has(today);
    weekNumbers.hidden = previewingCalendar;
    weekNumbers.value = String(selectedWeek);
    renderCourses();
  }

  function renderSource() {
    const hasData = previewingCalendar || Boolean(semester);
    week.hidden = clear.hidden = thisWeek.hidden = viewAction.hidden = !hasData;
    empty.hidden = hasData;
    source.classList.toggle("has-timetable", hasData);
    clear.textContent = previewingCalendar ? "关闭日历预览" : "删除本机课表";
    if (!hasData) {
      sourceActions.append(paste, fileAction, options);
      menu.insertBefore(viewAction, clear);
      columns.replaceChildren();
      days.replaceChildren();
      renderSummary();
      return;
    }
    pageActions.append(options);
    menu.prepend(paste, fileAction);
    termLabel.textContent = previewingCalendar ? ".ics 文件" : semesterLabel(semester.term);
    termCount.textContent = previewingCalendar ? "本机预览" : `共 ${semester.term.totalWeeks} 周`;
    const items = [...semesters.values()].map(item => new Option(semesterLabel(item.term), item.key));
    if (calendar) items.push(new Option(".ics 日历预览", "calendar"));
    terms.replaceChildren(...items);
    terms.value = previewingCalendar ? "calendar" : semester.key;
    terms.disabled = items.length === 1;
    weekNumbers.replaceChildren();
    if (!previewingCalendar) {
      for (let day = semester.firstWeek; day <= semester.lastWeek; day += 7 * DAY) {
        weekNumbers.add(new Option(weekLabel(semester, day), String(day)));
      }
    }
    renderWeek();
    renderSummary();
  }

  function jumpTo(firstDay) {
    selectedWeek = firstDay;
    const dates = datesOf(firstDay);
    selected = dates.includes(today) && (previewingCalendar || semester.days.has(today))
      ? today : dates.find(day => previewingCalendar || semester.days.has(day)) ?? firstDay;
    if (dialog.open) dialog.close();
    scroll.scrollLeft = 0;
  }

  function showSource(next) {
    previewingCalendar = next === "calendar";
    semester = semesters.get(next);
    today = campusToday();
    if (previewingCalendar || semester) {
      jumpTo(previewingCalendar || semester.days.has(today) ? sunday(today) : semester.firstWeek);
    }
    renderSource();
  }

  async function importSemester(load) {
    if (busy) return;
    setBusy(true);
    report("正在检查学期课表…");
    try {
      const parsed = await load();
      if (!parsed) {
        report("");
        return;
      }
      const view = makeSemesterView(parsed);
      await saveTimetableSemester(parsed);
      semesters.set(view.key, view);
      restoreFailed = false;
      options.open = false;
      showSource(view.key);
      const offline = navigator.serviceWorker?.controller ? "，可离线查看" : "";
      report(`已保存 ${view.term.totalWeeks} 周${offline}。`);
    } catch (error) {
      const message = error instanceof TimetableImportError ? error.message : "未能在本机保存课表";
      report(`${message}。${semester || previewingCalendar ? "已保留原课表。" : ""}`, true);
    } finally {
      setBusy(false);
      renderSummary();
    }
  }

  jsonInput.addEventListener("change", () => {
    const file = jsonInput.files[0];
    jsonInput.value = "";
    if (file) importSemester(() => readSemesterFile(file));
  });

  paste.addEventListener("click", () => importSemester(async () => {
    let text;
    try {
      text = await navigator.clipboard?.readText();
    } catch {
      // The user can paste into a native text field when Safari declines the API call.
    }
    if (text === undefined) {
      if (window.location.hash === "#timetable") pasteDialog.showModal();
      return;
    }
    if (!text.trim()) throw new TimetableImportError("剪贴板没有课表 JSON，请从学校课表复制后重试");
    return readSemesterText(text);
  }));

  function importPastedText(text) {
    pasteArea.value = "";
    pasteDialog.close();
    importSemester(() => readSemesterText(text));
  }

  pasteArea.addEventListener("paste", event => {
    const text = event.clipboardData?.getData("text/plain");
    if (!text) return;
    event.preventDefault();
    importPastedText(text);
  });
  pasteArea.addEventListener("input", event => {
    if (event.inputType === "insertFromPaste") importPastedText(pasteArea.value);
    else pasteArea.value = "";
  });

  setup.addEventListener("click", () => {
    options.open = false;
    copyStatus.textContent = "";
    bookmarkletCode.value = "";
    bookmarkletCode.hidden = true;
    setupDialog.showModal();
  });
  copyScript.addEventListener("click", async () => {
    let code;
    try {
      const module = await import("./timetable-bookmarklet.js?v=20");
      code = module.makeTimetableBookmarklet();
    } catch {
      copyStatus.textContent = "暂时无法准备书签脚本，请联网后重试。";
      return;
    }
    try {
      await navigator.clipboard.writeText(code);
      bookmarkletCode.hidden = true;
      bookmarkletCode.value = "";
      copyStatus.textContent = "已复制书签脚本。";
    } catch {
      if (!setupDialog.open) return;
      bookmarkletCode.value = code;
      bookmarkletCode.hidden = false;
      copyStatus.textContent = "可长按脚本，选择「全选」然后复制。";
      bookmarkletCode.focus();
      bookmarkletCode.select();
    }
  });

  calendarInput.addEventListener("change", async () => {
    const file = calendarInput.files[0];
    calendarInput.value = "";
    if (!file || busy) return;
    setBusy(true);
    report("正在读取日历文件…");
    try {
      if (file.size > MAX_FILE_BYTES) throw new Error("日历文件过大");
      const parsed = await parseCourseCalendar(await file.text());
      courseWeek(parsed, sunday(campusToday()));
      calendar = parsed;
      options.open = false;
      showSource("calendar");
      report("正在预览 .ics 文件，关闭或刷新页面后结束。");
    } catch (error) {
      if (error instanceof CalendarParserUnavailable) {
        report("预览 .ics 需要先联网加载一次日历解析器，请联网后重试。", true);
      } else {
        report(`无法读取该 .ics 文件。${semester || previewingCalendar ? "已保留原课表。" : ""}`, true);
      }
    } finally {
      setBusy(false);
      renderSummary();
    }
  });

  clear.addEventListener("click", () => {
    if (busy) return;
    if (previewingCalendar) {
      calendar = undefined;
      options.open = false;
      showSource(currentSemester()?.key ?? semesters.keys().next().value);
      report("");
      return;
    }
    deleteDialog.returnValue = "cancel";
    deleteKey = semester.key;
    deleteDialog.querySelector("p").textContent = `${semesterLabel(semester.term)}将从此浏览器移除。`;
    deleteDialog.showModal();
  });
  deleteDialog.addEventListener("close", async () => {
    const key = deleteKey;
    deleteKey = undefined;
    if (deleteDialog.returnValue !== "delete" || busy || !key) return;
    setBusy(true);
    try {
      await deleteTimetableSemester(key);
      semesters.delete(key);
      options.open = false;
      if (semester?.key === key) {
        showSource(currentSemester()?.key ?? semesters.keys().next().value ?? (calendar ? "calendar" : undefined));
      } else {
        renderSource();
      }
      report("已删除该学期的本机课表。");
    } catch {
      report("未能删除本机课表，请重试。", true);
    } finally {
      setBusy(false);
      renderSummary();
    }
  });

  terms.addEventListener("change", () => {
    showSource(terms.value);
    report(previewingCalendar ? "正在预览 .ics 文件，关闭或刷新页面后结束。" : "");
    if (!terms.disabled) terms.focus();
  });
  weekNumbers.addEventListener("change", () => {
    jumpTo(Number(weekNumbers.value));
    renderWeek();
  });
  viewAction.addEventListener("click", () => {
    daily = !daily;
    options.open = false;
    renderCourses();
    (daily ? viewAction : options.querySelector("summary")).focus({ preventScroll: true });
    scroll.scrollLeft = 0;
  });
  window.addEventListener("hashchange", () => {
    if (window.location.hash !== "#timetable") {
      if (dialog.open) dialog.close();
      if (deleteDialog.open) deleteDialog.close("cancel");
      if (pasteDialog.open) pasteDialog.close();
      if (setupDialog.open) setupDialog.close();
      options.open = false;
    }
  });
  for (const button of [previousWeek, thisWeek, followingWeek]) {
    button.addEventListener("click", () => {
      const priorDay = selected;
      const priorWeek = selectedWeek;
      const offset = button.dataset.week;
      if (offset === "today") {
        options.open = false;
        options.querySelector("summary").focus({ preventScroll: true });
      }
      today = campusToday();
      const nextWeek = offset === "today" ? sunday(today) : selectedWeek + Number(offset) * 7 * DAY;
      try {
        jumpTo(nextWeek);
        renderWeek();
        renderSummary();
      } catch {
        selected = priorDay;
        selectedWeek = priorWeek;
        report("该周暂时无法展开，请检查课表文件。", true);
      }
    });
  }

  dialog.addEventListener("click", event => {
    if (event.target !== dialog) return;
    const { left, right, top, bottom } = dialog.getBoundingClientRect();
    if (event.clientX < left || event.clientX > right || event.clientY < top || event.clientY > bottom) dialog.close();
  });

  refresh = () => {
    const next = campusToday();
    if (next === today) return;
    const stepWithToday = selected === today && (previewingCalendar || semester?.days.has(next));
    today = next;
    try {
      if (stepWithToday) jumpTo(sunday(today));
      if (previewingCalendar || semester) renderWeek();
      renderSummary();
    } catch {
      report("无法更新今日课表，请重新导入。", true);
    }
  };

  setBusy(true);
  report("正在读取本机课表…");
  renderSummary();
  listTimetableSemesters().then(items => {
    // Select from a complete set of records so a late restore cannot replace a new import.
    const views = items.map(makeSemesterView);
    for (const item of views) semesters.set(item.key, item);
    showSource(currentSemester()?.key ?? views[0]?.key);
    report("");
  }).catch(() => {
    restoreFailed = true;
    report("未能读取本机课表，请重新打开或选择文件。", true);
  }).finally(() => {
    setBusy(false);
    renderSummary();
  });
}
