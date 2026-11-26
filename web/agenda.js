import { transact } from "./db.js?v=24";

const dateInShanghai = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
});
const datePattern = /^\d{4}-\d{2}-\d{2}$/;
const timePattern = /^(?:[01]\d|2[0-3]):[0-5]\d$/;

let view;
let selectedDate;
let lastToday;
let readVersion = 0;

function todayInShanghai() {
  const parts = Object.fromEntries(dateInShanghai.formatToParts().map(({ type, value }) => [type, value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function validDate(value) {
  if (!datePattern.test(value) || value.startsWith("0000-")) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().startsWith(`${value}T`);
}

function readDays(selected, today) {
  return transact("agenda", "readonly", (transaction, setResult) => {
    const dates = transaction.objectStore("agenda").index("date");
    const byDate = new Map();
    for (const date of new Set([selected, today])) {
      const request = dates.getAll(date);
      request.onsuccess = () => {
        request.result.sort((a, b) => a.start.localeCompare(b.start) || a.end.localeCompare(b.end) || a.id - b.id);
        byDate.set(date, request.result);
      };
    }
    setResult(byDate);
  });
}

function saveEntry(entry, fields) {
  return transact("agenda", "readwrite", (transaction, setResult) => {
    const store = transaction.objectStore("agenda");
    if (!entry) {
      store.add({ ...fields, revision: 1 });
      setResult(true);
      return;
    }
    const request = store.get(entry.id);
    request.onsuccess = () => {
      if (request.result?.revision !== entry.revision) {
        setResult(false);
        return;
      }
      if (fields) store.put({ ...fields, id: entry.id, revision: entry.revision + 1 });
      else store.delete(entry.id);
      setResult(true);
    };
  });
}

function entryTitle(entry) {
  const title = document.createElement("span");
  title.className = "record-title";
  title.textContent = entry.title;
  return title;
}

function entryTime(entry, full = false) {
  const time = document.createElement("time");
  time.className = full ? "record-date" : "agenda-hero-time";
  time.dateTime = `${entry.date}T${entry.start}:00+08:00`;
  time.textContent = `${entry.start}–${entry.end}`;
  return time;
}

function entryRow(entry) {
  const row = document.createElement("li");
  row.className = "agenda-row";
  const summary = document.createElement("span");
  summary.append(entryTitle(entry), entryTime(entry, true));
  if (entry.location) {
    const place = document.createElement("span");
    place.className = "record-date";
    place.textContent = entry.location;
    summary.append(place);
  }
  const edit = document.createElement("button");
  edit.className = "text-button";
  edit.type = "button";
  edit.textContent = "编辑";
  edit.setAttribute("aria-label", `编辑日程 ${entry.title}`);
  edit.addEventListener("click", () => view.editEntry(entry));
  row.append(summary, edit);
  return row;
}

function renderToday(entries, date) {
  const { home } = view;
  home.replaceChildren();
  if (!entries.length) return;
  const list = document.createElement("ul");
  list.className = "agenda-hero-list";
  list.setAttribute("aria-label", "今日个人日程");
  for (const entry of entries.slice(0, 3)) {
    const row = document.createElement("li");
    row.append(entryTime(entry), entryTitle(entry));
    list.append(row);
  }
  const link = document.createElement("a");
  link.className = "text-link agenda-hero-link";
  link.href = `#timetable?agenda=${date}`;
  link.textContent = entries.length > 3 ? `查看全部 ${entries.length} 条日程` : "查看个人日程";
  home.append(list, link);
}

export async function refreshAgenda() {
  if (!view) return;
  const today = todayInShanghai();
  if (!selectedDate || selectedDate === lastToday) selectedDate = today;
  lastToday = today;
  const date = selectedDate;
  view.date.value = date;
  const version = ++readVersion;
  try {
    const days = await readDays(date, today);
    if (version !== readVersion) return;
    const entries = days.get(date);
    view.list.replaceChildren(...entries.map(entryRow));
    view.list.hidden = !entries.length;
    view.status.textContent = entries.length ? "" : "当天没有个人日程";
    view.retry.hidden = true;
    view.add.disabled = false;
    renderToday(days.get(today), today);
  } catch {
    if (version !== readVersion) return;
    view.list.hidden = true;
    view.status.textContent = "个人日程读取失败";
    view.retry.hidden = false;
    view.add.disabled = true;
    view.home.textContent = "个人日程读取失败";
  }
}

export function selectAgendaDate(date) {
  if (!validDate(date) || selectedDate === date) return;
  selectedDate = date;
  if (view) {
    view.list.hidden = true;
    view.status.textContent = "正在读取个人日程…";
  }
  refreshAgenda();
}

export function initializeAgenda() {
  const section = document.querySelector("#campus-agenda");
  const home = document.querySelector("#today-agenda");
  if (!section || !home || view) return;

  section.innerHTML = `
    <section class="detail-card workspace-card agenda-card" aria-labelledby="agenda-title">
      <div class="section-heading agenda-heading">
        <h2 id="agenda-title">个人日程</h2>
        <button class="text-button" type="button" data-agenda-add disabled>新增日程</button>
      </div>
      <label class="field agenda-date-field">
        <span>查看日期（北京时间）</span>
        <input type="date" data-agenda-date required>
      </label>
      <div class="workspace-status agenda-status">
        <p role="status" data-agenda-status>正在读取个人日程…</p>
        <button class="text-button" type="button" data-agenda-retry hidden>重试</button>
      </div>
      <ul class="record-list" data-agenda-list hidden></ul>
    </section>
    <dialog class="settings-dialog agenda-dialog" aria-labelledby="agenda-edit-title">
      <form class="agenda-form">
        <div class="section-heading">
          <h2 id="agenda-edit-title">新增日程</h2>
          <button class="text-button" type="button" data-agenda-close>取消</button>
        </div>
        <label class="field">标题<input type="text" name="title" maxlength="120" required></label>
        <label class="field">日期（北京时间）<input type="date" name="date" required></label>
        <div class="agenda-time-fields">
          <label class="field">开始时间<input type="time" name="start" required></label>
          <label class="field">结束时间<input type="time" name="end" required></label>
        </div>
        <label class="field">地点（可选）<input type="text" name="location" maxlength="120"></label>
        <p class="operation-error" role="status"></p>
        <div class="todo-edit-actions">
          <button class="button" type="submit" data-agenda-save>保存</button>
          <button class="text-button todo-delete" type="button" data-agenda-delete hidden>删除日程</button>
          <button class="text-button todo-delete" type="button" data-agenda-confirm hidden>确认删除</button>
        </div>
      </form>
    </dialog>
  `;

  const date = section.querySelector("[data-agenda-date]");
  const add = section.querySelector("[data-agenda-add]");
  const retry = section.querySelector("[data-agenda-retry]");
  const dialog = section.querySelector("dialog");
  const form = dialog.querySelector("form");
  const formStatus = form.querySelector("[role=status]");
  const title = form.querySelector("[name=title]");
  const fields = Array.from(form.querySelectorAll("input, button"));
  const end = form.querySelector("[name=end]");
  const deleteButton = form.querySelector("[data-agenda-delete]");
  const confirm = form.querySelector("[data-agenda-confirm]");
  let entry;
  let saving = false;

  function editEntry(existing) {
    if (dialog.open || saving) return;
    entry = existing;
    form.reset();
    title.setCustomValidity("");
    end.setCustomValidity("");
    formStatus.textContent = "";
    form.querySelector("h2").textContent = entry ? "编辑日程" : "新增日程";
    deleteButton.hidden = !entry;
    confirm.hidden = true;
    form.elements.date.value = entry?.date ?? selectedDate ?? todayInShanghai();
    for (const name of ["title", "start", "end", "location"]) form.elements[name].value = entry?.[name] ?? "";
    dialog.showModal();
    title.focus();
  }

  view = {
    home, date, add, retry, editEntry,
    status: section.querySelector("[data-agenda-status]"),
    list: section.querySelector("[data-agenda-list]"),
  };

  async function mutate(record) {
    if (saving) return;
    saving = true;
    for (const field of fields) field.disabled = true;
    try {
      if (!await saveEntry(entry, record)) {
        formStatus.textContent = "这条日程已更改，请重新打开后再操作。";
        refreshAgenda();
        return;
      }
      if (dialog.open) {
        dialog.close();
        if (record) selectedDate = record.date;
      }
      await refreshAgenda();
    } catch {
      formStatus.textContent = record ? "保存失败，请重试。" : "删除失败，请重试。";
    } finally {
      saving = false;
      for (const field of fields) field.disabled = false;
    }
  }

  add.addEventListener("click", () => editEntry());
  retry.addEventListener("click", refreshAgenda);
  date.addEventListener("change", () => {
    if (validDate(date.value)) selectAgendaDate(date.value);
    else date.value = selectedDate;
  });
  form.addEventListener("input", () => {
    title.setCustomValidity("");
    end.setCustomValidity("");
  });
  form.addEventListener("submit", event => {
    event.preventDefault();
    if (saving) return;
    const values = Object.fromEntries(new FormData(form));
    values.title = values.title.trim();
    values.location = values.location.trim();
    if (!values.title) {
      title.setCustomValidity("请输入日程标题");
      title.reportValidity();
      return;
    }
    if (!validDate(values.date)) {
      formStatus.textContent = "请选择有效的日期。";
      return;
    }
    if (!timePattern.test(values.start) || !timePattern.test(values.end) || values.start >= values.end) {
      end.setCustomValidity("结束时间须晚于开始时间");
      end.reportValidity();
      return;
    }
    formStatus.textContent = "";
    mutate(values);
  });
  form.querySelector("[data-agenda-close]").addEventListener("click", () => dialog.close());
  deleteButton.addEventListener("click", () => {
    deleteButton.hidden = true;
    confirm.hidden = false;
    confirm.focus();
  });
  confirm.addEventListener("click", () => mutate(null));
  dialog.addEventListener("cancel", event => { if (saving) event.preventDefault(); });
  window.addEventListener("hashchange", () => {
    if (dialog.open && location.hash.split("?")[0] !== "#timetable") dialog.close();
  });
  refreshAgenda();
}
