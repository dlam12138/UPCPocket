// ==UserScript==
// @name         UPC 课表导出（UPC Pocket 导入用）
// @namespace    https://github.com/upc-pocket
// @version      0.1.0
// @description  在已正常登录的教务课表页，按学期逐周读取本人可见课表并导出 CSV；不上传、不保存账号。
// @match        https://app.upc.edu.cn/site/weekschedule/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

/*
 * 适配范围（必须如实说明）：
 *  - 端点与字段来自学校课表页公开前端代码（2026-10-27 核验）：
 *      GET  /timetable/wap/default/get-index
 *      POST /timetable/wap/default/get-datatmp  (year, term, week, type=2)
 *    `d.classes` 为按天排列的 c_1..c_14 课程块，课程块含 course_name/teacher/
 *    location/lessArr。
 *  - 脚本必须在本人的正常登录页面运行，使用浏览器现有会话；不读取密码，
 *    不向第三方发送数据。
 *  - 尚未在真实登录账号上完成端到端验证；若页面结构/字段变化，脚本会报错并停止，
 *    不会静默生成半份课表。
 *  - 逐周读取选定学期的所有周；任何一周失败都停止，不把“当前周”冒充整学期。
 */
(function () {
  'use strict';

  const API_INDEX = '/timetable/wap/default/get-index';
  const API_WEEK = '/timetable/wap/default/get-datatmp';
  const MAX_WEEK_ATTEMPTS = 30;

  const ui = document.createElement('div');
  ui.style.cssText = [
    'position:fixed', 'right:16px', 'bottom:88px', 'z-index:2147483647',
    'display:flex', 'flex-direction:column', 'gap:6px', 'align-items:flex-end',
    'font:13px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif',
  ].join(';');
  const status = document.createElement('div');
  status.style.cssText = 'max-width:280px;padding:6px 10px;border-radius:8px;background:#fff;' +
    'box-shadow:0 2px 10px rgba(0,0,0,.18);color:#333;display:none;white-space:pre-wrap';
  const button = document.createElement('button');
  button.textContent = '导出课表 CSV';
  button.style.cssText = 'border:0;border-radius:20px;padding:10px 16px;background:#1a73e8;color:#fff;' +
    'font-size:14px;box-shadow:0 3px 12px rgba(26,115,232,.35);cursor:pointer';
  ui.appendChild(status);
  ui.appendChild(button);
  document.body.appendChild(ui);

  function show(text, isError) {
    status.style.display = text ? 'block' : 'none';
    status.textContent = text || '';
    status.style.color = isError ? '#b3261e' : '#333';
  }

  async function jsonFetch(url, options) {
    const response = await fetch(url, Object.assign({
      credentials: 'include',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
    }, options || {}));
    if (!response.ok) throw new Error('HTTP ' + response.status + ' ' + url);
    return response.json();
  }

  function assertOk(payload, what) {
    if (!payload || payload.e !== 0) {
      throw new Error(what + '失败：' + ((payload && (payload.m || payload.e)) || '响应格式异常'));
    }
    return payload.d || {};
  }

  function chooseTerm(indexData) {
    const list = (indexData.termInfo || []).filter(function (t) {
      return t && t.year != null && t.term != null && Number(t.countweek) > 0;
    });
    if (!list.length) throw new Error('教务未返回可用学期');
    const params = indexData.params || {};
    let candidates = list.filter(function (t) {
      return String(t.year) === String(params.year) && String(t.term) === String(params.term);
    });
    if (!candidates.length) candidates = list;
    if (candidates.length === 1) return candidates[0];
    const labels = candidates.map(function (t, i) {
      const termName = ({ 1: '秋季', 2: '春季', 3: '夏季' })[t.term] || ('第' + t.term + '学期');
      return (i + 1) + '. ' + t.year + ' ' + termName + '（' + t.countweek + ' 周）';
    });
    const picked = window.prompt('请选择要导出的学期（整学期，不是当前周）：\n' + labels.join('\n'), '1');
    const idx = Number(picked) - 1;
    if (!Number.isInteger(idx) || idx < 0 || idx >= candidates.length) {
      throw new Error('已取消或选择无效');
    }
    return candidates[idx];
  }

  function parsePeriods(course) {
    const raw = course.course || course;
    if (Array.isArray(raw.lessArr) && raw.lessArr.length) {
      return raw.lessArr.map(Number).filter(function (n) { return n >= 1 && n <= 14; });
    }
    const text = String(raw.course_time || raw.time || '');
    const found = [];
    const re = /(\d{1,2})\s*(?:[-~～至]\s*(\d{1,2}))?/g;
    let m;
    while ((m = re.exec(text)) !== null) {
      const a = Number(m[1]);
      const b = m[2] ? Number(m[2]) : a;
      if (a >= 1 && b <= 14 && a <= b) {
        for (let p = a; p <= b; p++) found.push(p);
      }
    }
    return found;
  }

  function consecutiveGroups(periods) {
    const sorted = Array.from(new Set(periods)).sort(function (a, b) { return a - b; });
    const groups = [];
    sorted.forEach(function (p) {
      const last = groups[groups.length - 1];
      if (last && p === last.end + 1) last.end = p;
      else groups.push({ start: p, end: p });
    });
    return groups;
  }

  async function readWeek(year, term, week) {
    const body = new URLSearchParams({
      year: String(year), term: String(term), week: String(week), type: '2',
    });
    const payload = await jsonFetch(API_WEEK, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'X-Requested-With': 'XMLHttpRequest',
      },
      body: body.toString(),
    });
    return assertOk(payload, '读取第 ' + week + ' 周');
  }

  function collectWeek(data, week, rowsByKey, missing) {
    const days = Array.isArray(data.classes) ? data.classes : [];
    days.forEach(function (day, dayIndex) {
      if (!day || typeof day !== 'object') return;
      Object.keys(day).forEach(function (slotKey) {
        const list = day[slotKey];
        if (!Array.isArray(list)) return;
        list.forEach(function (course) {
          if (!course || typeof course !== 'object') return;
          const c = course.course || course;
          const name = String(c.course_name || c.name || '').trim();
          if (!name) return;
          const periods = parsePeriods(course);
          if (!periods.length) {
            missing.push('第 ' + week + ' 周 星期' + (dayIndex + 1) + '《' + name + '》缺少节次');
            return;
          }
          const teacher = String(c.teacher || '').trim();
          const room = String(c.location || c.room || '').trim();
          consecutiveGroups(periods).forEach(function (group) {
            const key = [name, dayIndex + 1, group.start, group.end, teacher, room].join('|');
            if (!rowsByKey[key]) {
              rowsByKey[key] = {
                name: name, weekday: dayIndex + 1, start: group.start, end: group.end,
                teacher: teacher, room: room, weeks: new Set(),
              };
            }
            rowsByKey[key].weeks.add(week);
          });
        });
      });
    });
  }

  function csvEscape(value) {
    const s = String(value == null ? '' : value);
    return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  function weeksText(set) {
    return Array.from(set).sort(function (a, b) { return a - b; }).join(',');
  }

  async function exportCsv() {
    button.disabled = true;
    try {
      show('读取学期信息…');
      const indexData = assertOk(await jsonFetch(API_INDEX), '读取学期');
      const term = chooseTerm(indexData);
      const totalWeeks = Number(term.countweek) || 0;
      if (!totalWeeks) throw new Error('学期周数为 0');
      if (totalWeeks > MAX_WEEK_ATTEMPTS) {
        throw new Error('学期周数超过脚本上限 ' + MAX_WEEK_ATTEMPTS + '，已停止；请先更新脚本');
      }
      const count = totalWeeks;
      const rowsByKey = {};
      const missing = [];
      for (let week = 1; week <= count; week++) {
        show('读取第 ' + week + ' / ' + count + ' 周…');
        const data = await readWeek(term.year, term.term, week);
        collectWeek(data, week, rowsByKey, missing);
      }
      if (missing.length) {
        throw new Error('有 ' + missing.length + ' 个课程块缺少节次，已停止导出：\n' + missing.slice(0, 5).join('\n'));
      }
      const rows = Object.keys(rowsByKey).map(function (k) { return rowsByKey[k]; });
      if (!rows.length) throw new Error('选定学期没有读到课程；未生成空课表');
      rows.sort(function (a, b) {
        return a.weekday - b.weekday || a.start - b.start || a.name.localeCompare(b.name, 'zh');
      });
      const lines = ['课程名称,星期,开始节次,结束节次,周次,教师,教室'];
      rows.forEach(function (r) {
        lines.push([
          r.name, r.weekday, r.start, r.end, weeksText(r.weeks), r.teacher, r.room,
        ].map(csvEscape).join(','));
      });
      const csv = '\uFEFF' + lines.join('\r\n') + '\r\n';
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'UPC课表_' + term.year + '_' + term.term + '.csv';
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(function () { URL.revokeObjectURL(url); }, 5000);
      let copied = false;
      try {
        await navigator.clipboard.writeText(csv);
        copied = true;
      } catch (_) { /* 剪贴板权限不可用时仍保留下载文件 */ }
      show('整学期 ' + count + ' 周读取完成：' + rows.length + ' 个时段。\n已下载 CSV' +
        (copied ? '，并复制到剪贴板' : '') +
        '。\n到 UPC Pocket：课表 → 导入 → 选择文件，或“从剪贴板导入”。', false);
    } catch (e) {
      show(String(e && e.message ? e.message : e), true);
    } finally {
      button.disabled = false;
    }
  }

  button.addEventListener('click', exportCsv);
})();
