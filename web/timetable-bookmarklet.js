// @ts-check

export function makeTimetableBookmarklet() {
  const source = exportSchoolTimetable.toString().split("\n")
    .map(line => line.trim())
    .filter(line => line && !line.startsWith("//") && !line.startsWith("/**"))
    .join("\n");
  return `javascript:${encodeURIComponent(`void (${source})();`)}`;
}

// This function runs as a bookmarklet. Keep all its dependencies inside it.
async function exportSchoolTimetable() {
  const origin = "https://app.upc.edu.cn";
  if (location.origin !== origin) {
    alert("请先在 Safari 登录并打开学校课表页面，再点这个书签。");
    return;
  }

  const panelId = "upcpocket-semester-export";
  const existing = document.getElementById(panelId);
  if (existing) {
    existing.focus();
    return;
  }

  const panel = document.createElement("section");
  panel.id = panelId;
  panel.tabIndex = -1;
  panel.setAttribute("aria-label", "UPC Pocket 课表导出");
  panel.style.cssText = "position:fixed;z-index:2147483647;inset:12px 12px auto;margin:0 auto;max-width:32rem;max-height:75vh;overflow:auto;padding:16px;background:#fff;color:#203d30;border:1px solid #93a49b;border-radius:14px;box-shadow:0 6px 30px #0005;font:16px/1.55 -apple-system,sans-serif;text-align:left";
  const title = document.createElement("strong");
  title.textContent = "UPC Pocket 课表导出";
  const status = document.createElement("p");
  status.setAttribute("role", "status");
  const preview = document.createElement("pre");
  preview.style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.5 -apple-system,sans-serif;background:#edf4ed;border-radius:8px;padding:10px";
  preview.hidden = true;
  const actions = document.createElement("div");
  actions.style.cssText = "display:flex;flex-wrap:wrap;align-items:center;gap:8px";

  /** @param {string} label */
  function button(label) {
    const control = document.createElement("button");
    control.type = "button";
    control.textContent = label;
    control.style.cssText = "min-height:44px;padding:6px 12px;border:1px solid #648475;border-radius:8px;background:#f1f7f2;color:#203d30;font:inherit";
    return control;
  }

  const start = button("导出整学期");
  start.disabled = true;
  const close = button("关闭");
  const requests = new AbortController();
  let downloadUrl = "";
  close.onclick = () => {
    requests.abort();
    if (downloadUrl) URL.revokeObjectURL(downloadUrl);
    panel.remove();
  };
  actions.append(start, close);
  panel.append(title, status, preview, actions);
  document.body.append(panel);
  panel.focus();

  const loginRequired = new Error("login required");

  /** @param {unknown} value @returns {value is Record<string, unknown>} */
  function isRecord(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
  }

  /** @param {unknown} value */
  function sourceText(value) {
    return typeof value === "string" || typeof value === "number" ? String(value).trim() : "";
  }

  /** @param {unknown} value */
  function dateValue(value) {
    const match = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(sourceText(value));
    if (!match) return NaN;
    const iso = `${match[1]}-${match[2].padStart(2, "0")}-${match[3].padStart(2, "0")}`;
    const date = Date.parse(`${iso}T00:00:00Z`);
    return Number.isFinite(date) && new Date(date).toISOString().slice(0, 10) === iso ? date : NaN;
  }

  /** @param {string} path @param {URLSearchParams} [form] */
  async function request(path, form) {
    /** @type {Record<string, string>} */
    const headers = { Accept: "application/json, text/plain, */*", "X-Requested-With": "XMLHttpRequest" };
    if (form) headers["Content-Type"] = "application/x-www-form-urlencoded";
    const response = await fetch(origin + path, {
      method: form ? "POST" : "GET",
      body: form,
      credentials: "same-origin",
      mode: "same-origin",
      redirect: "manual",
      cache: "no-store",
      signal: requests.signal,
      headers,
    });
    if (response.type === "opaqueredirect" || response.status === 401 || response.status === 403) throw loginRequired;
    if (!response.ok) throw new Error("request failed");
    const bytes = await response.arrayBuffer();
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    const prefix = text.slice(0, 4096).trimStart();
    const html = prefix.startsWith("<") && /<(?:!doctype\s+html|html|head|body|form|div)\b/i.test(prefix);
    const login = /\bcas\.upc\.edu\.cn\b|\/cas\/login\b|\bcas\s+login\b|统一身份认证|\bid\s*=\s*["']?loginform\b|<input\b[^>]*\btype\s*=\s*["']?password\b/i;
    if (html && login.test(prefix)) throw loginRequired;
    /** @type {unknown} */
    const payload = JSON.parse(text);
    if (isRecord(payload) && (payload.e === 10013 || payload.e === "10013")) throw loginRequired;
    if (!isRecord(payload) || (payload.e !== 0 && payload.e !== "0") || !isRecord(payload.d)) {
      throw new Error("invalid response");
    }
    return payload.d;
  }

  /** @param {unknown} value @returns {value is Record<string, unknown>} */
  function isCourseDay(value) {
    if (!isRecord(value)) return false;
    return Object.entries(value).every(([key, entries]) => {
      if (!key.startsWith("c_")) return true;
      const slot = /^c_(\d{1,2})$/.exec(key);
      if (!slot) return false;
      if (entries === null) return true;
      if (!Array.isArray(entries)) return false;
      const period = Number(slot[1]);
      return entries.length === 0 || (period >= 1 && period <= 14 && entries.every(isRecord));
    });
  }

  /** @param {{year:string, term:string, startDay:string, totalWeeks:number}} term @param {number} week */
  async function readWeek(term, week) {
    const data = await request("/timetable/wap/default/get-datatmp", new URLSearchParams({
      year: term.year, term: term.term, week: String(week), type: "2",
    }));
    const dates = data.weekdays;
    const classes = data.classes;
    const day = 86_400_000;
    const first = dateValue(term.startDay) + (week - 1) * 7 * day;
    if (!Array.isArray(dates) || dates.length !== 7 || dates.some((date, i) => dateValue(date) !== first + i * day) ||
        !Array.isArray(classes) || classes.length !== 7 || !classes.every(isCourseDay)) {
      throw new Error("invalid week");
    }
    // The older API also uses unpadded month/day strings and null empty cells.
    return { week, dates: dates.map((_, i) => new Date(first + i * day).toISOString().slice(0, 10)), classes };
  }

  try {
    status.textContent = "正在读取学校课表…";
    const index = await request("/timetable/wap/default/get-index");
    const params = isRecord(index.params) ? index.params : {};
    const year = sourceText(params.year);
    const termCode = sourceText(params.term);
    const terms = Array.isArray(index.termInfo) ? index.termInfo : [];
    const currentTerm = terms.filter(isRecord).find(candidate =>
      sourceText(candidate.year) === year && sourceText(candidate.term) === termCode);
    const startDay = sourceText(currentTerm?.startday);
    const totalWeeks = Number(sourceText(currentTerm?.countweek));
    if (!year || !termCode || !Number.isFinite(dateValue(startDay)) ||
        !Number.isInteger(totalWeeks) || totalWeeks < 1 || totalWeeks > 30) {
      throw new Error("invalid term");
    }
    const term = { year, term: termCode, startDay, totalWeeks };
    const indexWeek = Number(sourceText(params.week));
    const probeWeek = Number.isInteger(indexWeek) && indexWeek >= 1 && indexWeek <= totalWeeks ? indexWeek : 1;
    const firstWeek = await readWeek(term, probeWeek);
    if (requests.signal.aborted) return;

    const courses = firstWeek.classes.flatMap(day => Object.entries(day).flatMap(([key, entries]) =>
      /^c_\d+$/.test(key) && Array.isArray(entries) ? entries.filter(isRecord) : []));
    const sample = courses.slice(0, 5).map(course =>
      [course.course_name, course.teacher, course.location].map(sourceText).filter(Boolean).join(" / "));
    preview.textContent = sample.length ? sample.join("\n") : `第 ${probeWeek} 周暂无课程。`;
    preview.hidden = false;
    status.textContent = `确认预览后，可导出本学期全部 ${totalWeeks} 周。`;
    start.disabled = false;

    start.onclick = async () => {
      start.disabled = true;
      const weeks = [];
      const failedWeeks = [];
      let expired = false;
      for (let week = 1; week <= totalWeeks; week++) {
        if (requests.signal.aborted) return;
        status.textContent = `正在获取第 ${week} / ${totalWeeks} 周…`;
        try {
          weeks.push(week === probeWeek ? firstWeek : await readWeek(term, week));
        } catch (error) {
          if (requests.signal.aborted) return;
          failedWeeks.push(week);
          if (error === loginRequired) {
            expired = true;
            break;
          }
        }
        if (week < totalWeeks) await new Promise(resolve => setTimeout(resolve, 350));
      }
      if (requests.signal.aborted) return;
      if (failedWeeks.length) {
        status.textContent = expired ? "学校登录已失效，请重新登录后再运行书签。" :
          `课程获取不完整，失败周次：${failedWeeks.join("、")}。请重试。`;
        start.disabled = expired;
        return;
      }

      const json = JSON.stringify({ version: 3, encoding: "UTF-8", term, weeks, errors: [] });
      if (json.includes("\uFFFD")) {
        status.textContent = "课表文字读取异常，请刷新学校课表后重新运行书签。";
        return;
      }
      const blob = new Blob([json], { type: "application/json;charset=utf-8" });
      if (blob.size > 16 * 1024 * 1024 || await blob.text() !== json) {
        status.textContent = "课表文件无法完整导出，请刷新学校课表后重试。";
        return;
      }
      if (requests.signal.aborted) return;

      const copy = button("复制课表 JSON");
      copy.onclick = async () => {
        try {
          await navigator.clipboard.writeText(json);
          status.textContent = "课表已复制。返回 UPC Pocket，选择「粘贴导入」。";
        } catch {
          status.textContent = "Safari 未能复制课表，请选择「下载 JSON」。";
        }
      };

      const download = document.createElement("a");
      downloadUrl = URL.createObjectURL(blob);
      download.href = downloadUrl;
      download.download = `UPCPocket-${year.replace(/[^0-9-]/g, "")}-${termCode.replace(/\D/g, "")}-UTF8.json`;
      download.textContent = "下载 JSON";
      download.style.cssText = "display:inline-flex;align-items:center;min-height:44px;padding:0 8px;color:#215138;text-decoration:underline";
      start.replaceWith(copy, download);
      status.textContent = `已读取 ${totalWeeks} / ${totalWeeks} 周。复制课表后可回到 UPC Pocket 导入。`;
    };
  } catch (error) {
    if (requests.signal.aborted) return;
    status.textContent = error === loginRequired ? "请先登录学校课表，再重新运行书签。" :
      "暂时无法读取学校课表，请确认学校课表已打开并能正常查看，再重试。";
  }
}
