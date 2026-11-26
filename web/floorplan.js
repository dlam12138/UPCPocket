const sourceUrl = "https://jwc.upc.edu.cn/2026/0830/c23036a498711/page.htm";
let sheets;
let sheetsRequest;
let view;
let revision = 0;

async function readSheets() {
  const groups = await Promise.all(["a", "b"].map(async building => {
    const response = await fetch(new URL(`data/zhuoyue-floor-plans/${building}.json`, import.meta.url));
    if (!response.ok) throw new Error("Floor plan unavailable");
    return (await response.json()).map(sheet => ({ ...sheet, building }));
  }));
  return groups.flat();
}

function buildingName(sheet) {
  return `${sheet.building.toUpperCase()} 区`;
}

function roomNumbers(text) {
  return text.normalize("NFKC").toUpperCase().match(/[AB]\s*\d{3}(?!\d)/g)
    ?.map(number => number.replace(/\s/g, "")) ?? [];
}

function drawPlan(canvas, sheet, selected) {
  const density = 2;
  canvas.width = sheet.width * density;
  canvas.height = sheet.height * density;
  canvas.setAttribute("aria-label", `${buildingName(sheet)}${sheet.level}平面图${selected ? `，标记 ${selected.label}` : ""}`);
  const context = canvas.getContext("2d");
  if (!context) return;
  const theme = getComputedStyle(canvas);
  const color = name => theme.getPropertyValue(`--${name}`).trim();
  context.scale(density, density);

  function drawPolygon(points, fill, border, width) {
    context.beginPath();
    points.forEach(([x, y], index) => index ? context.lineTo(x, y) : context.moveTo(x, y));
    context.closePath();
    context.fillStyle = color(fill);
    context.fill();
    context.strokeStyle = color(border);
    context.lineWidth = width;
    context.stroke();
  }

  for (const area of sheet.areas) {
    drawPolygon(area.points, area.kind === "floor" ? "hero" : "muted", "control-line", 1.25);
  }
  for (const room of sheet.rooms) {
    const isSelected = room === selected;
    drawPolygon(room.points, isSelected ? "dock-active" : "surface", isSelected ? "green" : "control-line", isSelected ? 3 : 1);
    const xs = room.points.map(point => point[0]);
    const ys = room.points.map(point => point[1]);
    const width = Math.max(...xs) - Math.min(...xs);
    context.fillStyle = color("ink");
    context.font = `${isSelected ? "bold " : ""}12px -apple-system, sans-serif`;
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.fillText(room.label, Math.min(...xs) + width / 2, (Math.min(...ys) + Math.max(...ys)) / 2, Math.max(width - 4, 10));
  }
}

function createView(mount) {
  mount.innerHTML = `
    <div class="detail-card workspace-card floorplan-panel">
      <form class="floorplan-search">
        <div class="field"><label for="floorplan-room">查房号</label>
          <input id="floorplan-room" type="search" maxlength="35" placeholder="例如 A233" autocomplete="off"></div>
        <button class="button" type="submit">查找</button>
      </form>
      <p class="floorplan-status" role="status"></p>
      <div class="floorplan-matches"></div>
      <div class="floorplan-controls">
        <label for="floorplan-building">楼区 <select id="floorplan-building"><option value="a">A 区</option><option value="b">B 区</option></select></label>
        <label for="floorplan-level">楼层 <select id="floorplan-level"></select></label>
      </div>
      <div class="floorplan-viewport" tabindex="0" aria-label="可滚动的楼层图">
        <canvas role="img"></canvas>
      </div>
      <div class="floorplan-zoom" aria-label="调整平面图缩放">
        <button class="text-button" type="button" data-zoom="out">缩小</button>
        <button class="text-button" type="button" data-zoom="in">放大</button>
        <button class="text-button" type="button" data-zoom="reset">复位</button>
      </div>
      <a class="text-link" href="${sourceUrl}" target="_blank" rel="noopener noreferrer">教务处原图</a>
    </div>`;
  const query = mount.querySelector("#floorplan-room");
  const building = mount.querySelector("#floorplan-building");
  const level = mount.querySelector("#floorplan-level");
  const status = mount.querySelector(".floorplan-status");
  const matches = mount.querySelector(".floorplan-matches");
  const viewport = mount.querySelector(".floorplan-viewport");
  const canvas = mount.querySelector("canvas");
  let sheet;
  let selected;
  let zoom = 1;

  function setZoom(value) {
    zoom = Math.min(4, Math.max(1, value));
    canvas.style.width = `${zoom * 100}%`;
    mount.querySelector('[data-zoom="out"]').disabled = zoom === 1;
    mount.querySelector('[data-zoom="in"]').disabled = zoom === 4;
  }

  function show(sheetToShow, room) {
    sheet = sheetToShow;
    selected = room;
    building.value = sheet.building;
    level.replaceChildren(...sheets.filter(item => item.building === sheet.building)
      .map(item => new Option(item.level, item.id)));
    level.value = sheet.id;
    status.textContent = room
      ? `${buildingName(sheet)} · ${sheet.level} · ${room.label}`
      : `${buildingName(sheet)} · ${sheet.level} · 来源第 ${sheet.sourcePage} 页`;
    matches.replaceChildren();
    drawPlan(canvas, sheet, room);
    setZoom(room ? 2.5 : 1);
    if (room) {
      const center = room.points.reduce(([x, y], [nextX, nextY]) => [x + nextX, y + nextY], [0, 0])
        .map(value => value / room.points.length);
      viewport.scrollTo({
        left: center[0] / sheet.width * canvas.clientWidth - viewport.clientWidth / 2,
        top: center[1] / sheet.height * canvas.clientHeight - viewport.clientHeight / 2,
      });
    } else viewport.scrollTo(0, 0);
  }

  function find(value) {
    query.value = value;
    const [number] = roomNumbers(value);
    matches.replaceChildren();
    const results = number ? sheets.flatMap(item => item.rooms
      .filter(room => roomNumbers(room.label).includes(number))
      .map(room => ({ sheet: item, room }))) : [];
    if (results.length === 1) {
      show(results[0].sheet, results[0].room);
      return;
    }
    if (selected) show(sheet);
    status.textContent = results.length ? `找到 ${results.length} 处标注，请选择位置。` : "没有找到该房号。可尝试 A233 或 B328。";
    for (const [index, result] of results.entries()) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "text-button";
      button.textContent = `${buildingName(result.sheet)} ${result.sheet.level} · 位置 ${index + 1}`;
      button.addEventListener("click", () => show(result.sheet, result.room));
      matches.append(button);
    }
  }

  mount.querySelector("form").addEventListener("submit", event => {
    event.preventDefault();
    find(query.value);
  });
  building.addEventListener("change", () => show(sheets.find(item => item.building === building.value)));
  level.addEventListener("change", () => show(sheets.find(item => item.id === level.value)));
  mount.querySelector(".floorplan-zoom").addEventListener("click", event => {
    const action = event.target.closest("[data-zoom]")?.dataset.zoom;
    if (action) setZoom(action === "reset" ? 1 : zoom + (action === "in" ? .5 : -.5));
  });
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => drawPlan(canvas, sheet, selected));
  show(sheets[0]);
  return { find };
}

export async function showFloorplan(room) {
  const mount = document.getElementById("campus-floorplan-app");
  const current = ++revision;
  if (!view) {
    mount.textContent = "正在加载楼层图…";
    sheetsRequest ??= readSheets().catch(error => {
      sheetsRequest = undefined;
      throw error;
    });
    try {
      sheets = await sheetsRequest;
      if (current !== revision || mount.closest(".page").hidden) return;
      view = createView(mount);
    } catch {
      if (current !== revision) return;
      mount.innerHTML = `<p role="status">楼层图加载失败。</p>
        <button class="text-button" type="button">重试</button>
        <a class="text-link" href="${sourceUrl}" target="_blank" rel="noopener noreferrer">教务处原图</a>`;
      mount.querySelector("button").addEventListener("click", () => showFloorplan(room));
      return;
    }
  }
  if (room) view.find(room.slice(0, 35));
}
