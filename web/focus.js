import { transact } from "./db.js";

const DURATION_MS = 25 * 60_000;
const stateLabel = document.querySelector("#focus-state");
const timer = document.querySelector("#focus-time");
const error = document.querySelector("#focus-error");
const retry = document.querySelector("#retry-focus");
const historyList = document.querySelector("#focus-history");
const historyStatus = document.querySelector("#focus-history-status");
const controls = Array.from(document.querySelectorAll("[data-focus-action]"));
const dateFormat = new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" });
const summary = document.createElement("section");
summary.id = "focus-summary";
summary.className = "workspace-section";
summary.hidden = true;
summary.setAttribute("aria-labelledby", "focus-summary-title");
summary.innerHTML = `
  <div class="section-heading"><h2 id="focus-summary-title">专注统计</h2></div>
  <dl class="focus-stats">
    <div><dt>今日时长</dt><dd data-stat="today"></dd></div>
    <div><dt>今日番茄</dt><dd data-stat="pomodoros"></dd></div>
    <div><dt>本周时长</dt><dd data-stat="week"></dd></div>
  </dl>`;
historyList.closest("section").before(summary);

let activeSession = null;
let ready = false;
let pending = false;
let failed = false;
let nextRefresh;

function remaining(session, now) {
  if (!session) return DURATION_MS;
  if (session.phase === "paused") return session.remainingMs;
  return Math.max(0, Math.min(DURATION_MS, session.endsAt - now));
}

function sessionChange(current, action, expectedId, now) {
  const expired = current?.phase === "running" && current.endsAt <= now;
  if (expired || (action === "finish" && current && current.sessionId === expectedId)) {
    const endedAt = expired ? current.endsAt : now;
    const record = {
      sessionId: current.sessionId,
      startedAt: current.startedAt,
      endedAt,
      durationMs: DURATION_MS - remaining(current, now),
      completed: expired,
    };
    return { active: null, record };
  }
  if (action === "start" && !current && expectedId === null) {
    return {
      active: { sessionId: crypto.randomUUID(), startedAt: now, endsAt: now + DURATION_MS, phase: "running" },
    };
  }
  if (!current || current.sessionId !== expectedId) return { active: current };
  if (action === "pause" && current.phase === "running") {
    return {
      active: { ...current, phase: "paused", remainingMs: remaining(current, now), endsAt: null },
    };
  }
  if (action === "resume" && current.phase === "paused") {
    return {
      active: { ...current, phase: "running", endsAt: now + current.remainingMs, remainingMs: null },
    };
  }
  return { active: current };
}

function snapshot(action, expectedId) {
  // Read current state under the write lock, then settle history and clear the
  // active slot together. Another tab will see either both changes or neither.
  return transact(["focusState", "focusHistory"], "readwrite", (tx, setResult) => {
    const state = tx.objectStore("focusState");
    const history = tx.objectStore("focusHistory");
    const request = state.get("active");
    request.onsuccess = () => {
      const current = request.result ?? null;
      const { active, record } = sessionChange(current, action, expectedId, Date.now());
      if (record) {
        history.put(record);
        state.delete("active");
      } else if (active !== current) {
        state.put(active, "active");
      }
      const entries = history.getAll();
      entries.onsuccess = () => setResult({ active, entries: entries.result });
    };
  });
}

function renderTime(ms) {
  const seconds = Math.ceil(ms / 1000);
  const minutesText = String(Math.floor(seconds / 60)).padStart(2, "0");
  const secondsText = String(seconds % 60).padStart(2, "0");
  const value = `${minutesText}:${secondsText}`;
  if (timer.textContent !== value) timer.textContent = value;
}

function tick() {
  clearTimeout(nextRefresh);
  if (!ready) return;
  const ms = remaining(activeSession, Date.now());
  renderTime(ms);
  if (activeSession?.phase !== "running" || document.hidden || failed) return;
  if (ms === 0) {
    synchronize();
  } else {
    nextRefresh = setTimeout(tick, Math.min(1000, ms));
  }
}

function focusSummary(entries, now) {
  const time = new Date(now);
  const year = time.getFullYear();
  const month = time.getMonth();
  const day = time.getDate() - (time.getHours() < 4 ? 1 : 0);
  const today = new Date(year, month, day, 4);
  const tomorrow = new Date(year, month, day + 1, 4);
  const week = new Date(year, month, day - (today.getDay() + 6) % 7, 4);
  let todaySeconds = 0;
  let weekSeconds = 0;
  let pomodoros = 0;
  for (const entry of entries) {
    if (entry.startedAt < week || entry.startedAt >= tomorrow) continue;
    const seconds = Math.max(0, Math.floor(entry.durationMs / 1000));
    weekSeconds += seconds;
    if (entry.startedAt >= today) {
      todaySeconds += seconds;
      if (entry.completed) pomodoros++;
    }
  }
  return { todaySeconds, weekSeconds, pomodoros };
}

function statDuration(seconds) {
  if (seconds <= 0) return "—";
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds / 60) % 60;
  if (hours) return minutes ? `${hours} 小时 ${minutes} 分` : `${hours} 小时`;
  return minutes ? `${minutes} 分钟` : "不到 1 分钟";
}

function renderFocusSummary(entries) {
  const { todaySeconds, weekSeconds, pomodoros } = focusSummary(entries, Date.now());
  summary.querySelector('[data-stat="today"]').textContent = statDuration(todaySeconds);
  summary.querySelector('[data-stat="pomodoros"]').textContent = `${pomodoros} 个`;
  summary.querySelector('[data-stat="week"]').textContent = statDuration(weekSeconds);
  summary.hidden = false;
}

function renderHistory(entries) {
  historyList.replaceChildren(...entries
    .sort((left, right) => right.endedAt - left.endedAt)
    .map(entry => {
      const row = document.createElement("li");
      row.className = "history-item";
      const label = document.createElement("span");
      label.className = "record-title";
      const seconds = Math.floor(entry.durationMs / 1000);
      const minutes = Math.floor(seconds / 60);
      const rest = seconds % 60;
      const duration = minutes ? `${minutes} 分钟${rest ? ` ${rest} 秒` : ""}` : `${seconds} 秒`;
      label.textContent = `${entry.completed ? "已完成" : "提前结束"} · ${duration}`;
      const date = document.createElement("time");
      date.className = "record-date";
      date.dateTime = new Date(entry.endedAt).toISOString();
      date.textContent = dateFormat.format(entry.endedAt);
      row.append(label, date);
      return row;
    }));
  historyList.hidden = entries.length === 0;
  historyStatus.textContent = entries.length ? "" : "还没有专注记录";
}

function renderControls() {
  if (!ready) return;
  const phase = activeSession?.phase;
  const primary = phase === "running" ? "pause" : phase === "paused" ? "resume" : "start";
  const previousFocus = controls.find(button => button === document.activeElement);
  stateLabel.textContent = phase === "running" ? "专注中" : phase === "paused" ? "已暂停" : "准备开始";
  for (const button of controls) {
    const action = button.dataset.focusAction;
    button.disabled = false;
    button.hidden = action !== primary && (action !== "finish" || !activeSession);
  }
  if (previousFocus?.hidden) {
    controls.find(button => button.dataset.focusAction === primary).focus({ preventScroll: true });
  }
}

async function synchronize(action = "refresh") {
  if (pending || (action !== "refresh" && (!ready || failed))) return;
  pending = true;
  let result;
  try {
    result = await snapshot(action, activeSession?.sessionId ?? null);
  } catch {
    failed = true;
    error.textContent = action === "refresh" ? "计时读取失败，请重试" : "计时保存失败，请重试";
    if (!ready) stateLabel.textContent = "计时暂不可用";
    for (const button of controls) button.disabled = true;
    retry.hidden = false;
  } finally {
    pending = false;
  }
  if (result) {
    activeSession = result.active;
    ready = true;
    failed = false;
    error.textContent = "";
    retry.hidden = true;
    renderControls();
    renderHistory(result.entries);
    renderFocusSummary(result.entries);
  }
  tick();
}

export function refreshFocus() {
  return synchronize();
}

for (const button of controls) {
  button.addEventListener("click", () => synchronize(button.dataset.focusAction));
}
retry.addEventListener("click", refreshFocus);
