const campusNames = {
  tangdaowan: "唐岛湾校区",
  guzhenkou: "古镇口校区",
};
const presets = [
  { label: "上午", first: 1, last: 5 },
  { label: "下午", first: 6, last: 9 },
  { label: "晚上", first: 10, last: 13 },
];
const allBuildings = "全部";
const campusKey = "upc-pocket-classroom-campus";
const resultsKey = "upc-pocket-classroom-results-v1";
const updatedTime = new Intl.DateTimeFormat("zh-CN", {
  month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit",
  hourCycle: "h23", timeZone: "Asia/Shanghai",
});
const campusDate = new Intl.DateTimeFormat("en-CA", {
  year: "numeric", month: "2-digit", day: "2-digit", timeZone: "Asia/Shanghai",
});

function today() {
  const parts = Object.fromEntries(campusDate.formatToParts(Date.now()).map(part => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function range(first, last) {
  return Array.from({ length: last - first + 1 }, (_, offset) => first + offset);
}

function validResult(data, query) {
  if (!data || JSON.stringify(data.query?.periods) !== JSON.stringify(query.periods)) return false;
  if (data.query.campus !== query.campus || data.query.date !== query.date || data.query.building !== query.building) {
    return false;
  }
  return Number.isFinite(Date.parse(data.fetchedAt)) && Array.isArray(data.buildings) &&
    data.buildings.every(building => typeof building === "string" && building.length > 0) &&
    Array.isArray(data.rooms) && data.rooms.every(room => room && typeof room.code === "string" &&
      typeof room.name === "string" && typeof room.building === "string" &&
      typeof room.available === "boolean" && (room.capacity === null || Number.isSafeInteger(room.capacity)));
}

function storedCampus() {
  try {
    const campus = localStorage.getItem(campusKey);
    if (Object.hasOwn(campusNames, campus)) return campus;
  } catch {
    // Browsers can disable storage and still make an online query.
  }
  return "tangdaowan";
}

function readCache() {
  try {
    const entries = JSON.parse(localStorage.getItem(resultsKey) ?? "[]");
    if (Array.isArray(entries)) {
      return new Map(entries.slice(0, 6).filter(entry => Array.isArray(entry) && entry.length === 2));
    }
  } catch {
    // Only successful live responses are used when the cache is unavailable.
  }
  return new Map();
}

function makeElement(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

function setOptions(select, choices, selected) {
  select.replaceChildren(...choices.map(choice => new Option(choice, choice)));
  select.value = selected;
}

function renderGroups(target, data, wing, onlyAvailable) {
  target.replaceChildren();
  const groups = new Map();
  const pattern = wing === "all" ? null : new RegExp(`^卓越\\s*${wing}\\s*\\d`);
  for (const room of data.rooms) {
    if (pattern && (room.building !== "卓越教学楼" || !pattern.test(room.name.trim()))) continue;
    if (!groups.has(room.building)) groups.set(room.building, []);
    groups.get(room.building).push(room);
  }
  for (const [building, rooms] of groups) {
    const freeCount = rooms.filter(room => room.available).length;
    const shown = onlyAvailable ? rooms.filter(room => room.available) : rooms;
    if (!shown.length) continue;
    const group = makeElement("section", "classroom-building");
    const title = makeElement("div", "classroom-building-heading");
    title.append(makeElement("h2", "", building), makeElement("span", "", `空闲 ${freeCount}/${rooms.length}`));
    const cards = makeElement("ul", "classroom-rooms");
    for (const room of shown) {
      const card = makeElement("li", `classroom-room${room.available ? " classroom-room-free" : ""}`);
      const name = makeElement("strong", "", room.name);
      const status = makeElement("span", "classroom-room-state", room.available ? "空闲" : "不可用");
      card.append(name, status);
      if (room.capacity) card.append(makeElement("span", "classroom-capacity", `${room.capacity} 座`));
      cards.append(card);
    }
    group.append(title, cards);
    target.append(group);
  }
  if (!target.childElementCount) {
    const message = data.rooms.length ? "当前筛选下没有匹配的教室。" : "学校未返回符合条件的教室。";
    target.append(makeElement("p", "classroom-empty", message));
  }
}

export function initializeClassrooms() {
  const root = document.querySelector("#classroom-app");
  if (!root) return;
  root.innerHTML = `
    <form class="classroom-filters detail-card">
      <div class="classroom-fields">
        <label>校区 <select name="campus">
          <option value="tangdaowan">唐岛湾校区</option>
          <option value="guzhenkou">古镇口校区</option>
        </select></label>
        <label>查询日期 <input type="date" name="date" required></label>
      </div>
      <fieldset class="classroom-periods">
        <legend>节次</legend>
        <div class="classroom-presets">
          ${presets.map(({label, first, last}, index) => `<button type="button" data-preset="${index}" aria-pressed="false">
            ${label} <span>${first}–${last} 节</span></button>`).join("")}
        </div>
      </fieldset>
      <details class="classroom-details">
        <summary>教学楼与自选节次</summary>
        <label>教学楼 <select name="building"><option>全部</option></select></label>
        <fieldset class="classroom-periods">
          <legend>自选节次</legend>
          <div class="classroom-custom">
            ${range(1, 13).map(period => `<label><input type="checkbox" value="${period}"><span>${period}</span></label>`).join("")}
          </div>
        </fieldset>
      </details>
      <div class="classroom-controls">
        <button type="submit" class="button">查询教室</button>
        <label class="classroom-only"><input type="checkbox" name="onlyFree"> 仅看空闲</label>
      </div>
    </form>
    <div class="classroom-results-header">
      <p class="classroom-status" role="status"></p>
      <label class="classroom-wing" hidden>卓越分区 <select name="wing">
        <option value="all">全部</option><option value="A">卓越 A</option><option value="B">卓越 B</option>
      </select></label>
    </div>
    <div class="classroom-results"></div>
  `;

  const form = root.querySelector("form");
  const { campus, date, building, onlyFree } = form.elements;
  const presetButtons = [...form.querySelectorAll("[data-preset]")];
  const periodBoxes = [...form.querySelectorAll(".classroom-custom input")];
  const wing = root.querySelector("select[name=wing]");
  const groups = root.querySelector(".classroom-results");
  const status = root.querySelector(".classroom-status");
  const submit = form.querySelector("[type=submit]");
  const cache = readCache();
  const choicesByCampus = new Map();
  let activeRequest;
  let displayedResult;
  let pending;
  let stale;
  let custom = false;

  campus.value = storedCampus();
  date.value = today();

  function assignPeriods(periods) {
    for (const box of periodBoxes) box.checked = periods.includes(Number(box.value));
  }

  function selectedPeriods() {
    return periodBoxes.filter(box => box.checked).map(box => Number(box.value));
  }

  function updatePresets() {
    const periods = selectedPeriods();
    for (const [index, button] of presetButtons.entries()) {
      const { first, last } = presets[index];
      const matches = range(first, last).filter(period => periods.includes(period)).length;
      button.setAttribute("aria-pressed", custom || !matches ? "false" : matches === last - first + 1 ? "true" : "mixed");
    }
  }

  function resetDisplayed(message) {
    activeRequest?.abort();
    activeRequest = undefined;
    pending = false;
    stale = false;
    displayedResult = null;
    submit.disabled = false;
    groups.replaceChildren();
    wing.parentElement.hidden = true;
    status.textContent = message;
  }

  function renderResult() {
    if (!displayedResult) return;
    const { query, buildings, rooms, fetchedAt } = displayedResult;
    const updated = updatedTime.format(new Date(fetchedAt));
    choicesByCampus.set(query.campus, buildings);
    setOptions(building, [...new Set([allBuildings, ...buildings, query.building])], query.building);
    const free = rooms.filter(room => room.available).length;
    const summary = `${campusNames[query.campus]} · 空闲 ${free}/${rooms.length} 间`;
    status.textContent = pending ? `正在更新 · 上次查询 ${updated}` :
      stale ? `更新失败 · 显示 ${updated} 的缓存` : `${summary} · ${updated} 更新`;
    wing.parentElement.hidden = query.campus !== "guzhenkou" || !rooms.some(room => room.building === "卓越教学楼");
    if (wing.parentElement.hidden) wing.value = "all";
    renderGroups(groups, displayedResult, wing.value, onlyFree.checked);
  }

  async function search() {
    const query = { campus: campus.value, date: date.value, building: building.value, periods: selectedPeriods() };
    const key = JSON.stringify(query);
    resetDisplayed("正在查询学校教室…");
    if (!query.date || !query.periods.length) {
      status.textContent = !query.date ? "请选择查询日期。" : "请至少选择一节。";
      return;
    }
    const request = new AbortController();
    activeRequest = request;
    const timeout = setTimeout(() => request.abort(), 18_000);
    pending = true;
    submit.disabled = true;
    const cached = cache.get(key);
    if (validResult(cached, query)) {
      displayedResult = cached;
      renderResult();
    }
    const url = new URL("./api/classrooms", location.href);
    url.search = new URLSearchParams({ campus: query.campus, date: query.date, building: query.building });
    for (const period of query.periods) url.searchParams.append("period", String(period));

    try {
      const response = await fetch(url, {
        credentials: "omit", redirect: "error", cache: "no-store",
        signal: request.signal,
      });
      if (!response.ok || !response.headers.get("content-type")?.includes("application/json")) throw new Error();
      const data = await response.json();
      if (!validResult(data, query)) throw new Error();
      if (activeRequest !== request) return;
      displayedResult = data;
      cache.delete(key);
      cache.set(key, data);
      while (cache.size > 6) cache.delete(cache.keys().next().value);
      try {
        localStorage.setItem(resultsKey, JSON.stringify([...cache]));
      } catch {
        // Keep the current in-memory result when the browser cannot store it.
      }
    } catch {
      if (activeRequest !== request) return;
      stale = true;
      if (!displayedResult) status.textContent = "暂时无法取得学校教室数据，请重试。";
    } finally {
      clearTimeout(timeout);
      if (activeRequest === request) {
        pending = false;
        submit.disabled = false;
        activeRequest = undefined;
        renderResult();
      }
    }
  }

  function queryChanged(runAutomatically) {
    if (runAutomatically) search();
    else resetDisplayed("筛选已更改，点击「查询教室」查看结果。");
  }

  form.addEventListener("submit", event => {
    event.preventDefault();
    search();
  });
  campus.addEventListener("change", () => {
    setOptions(building, choicesByCampus.get(campus.value) ?? [allBuildings], allBuildings);
    wing.value = "all";
    try { localStorage.setItem(campusKey, campus.value); } catch { /* Optional preference. */ }
    queryChanged(!custom);
  });
  date.addEventListener("change", () => queryChanged(!custom));
  building.addEventListener("change", () => queryChanged(false));
  onlyFree.addEventListener("change", renderResult);
  wing.addEventListener("change", renderResult);
  for (const box of periodBoxes) {
    box.addEventListener("change", () => {
      custom = true;
      updatePresets();
      queryChanged(false);
    });
  }
  for (const [index, button] of presetButtons.entries()) {
    button.addEventListener("click", () => {
      const { first, last } = presets[index];
      const periodGroup = range(first, last);
      const next = new Set(custom ? [] : selectedPeriods());
      const remove = periodGroup.every(period => next.has(period));
      for (const period of periodGroup) {
        if (remove) next.delete(period);
        else next.add(period);
      }
      custom = false;
      assignPeriods([...next]);
      updatePresets();
      search();
    });
  }
  assignPeriods(range(presets[0].first, presets[0].last));
  updatePresets();
  search();
}
