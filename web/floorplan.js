const sourceUrl = "https://jwc.upc.edu.cn/2026/0830/c23036a498711/page.htm";
const MAX_SHARED_BYTES = 2 * 1024 * 1024;
const MAX_SHARED_CONTOURS = 12_000;
const MAX_SHARED_POINTS = 100_000;
const MAX_CANVAS_SIDE = 4096;
const MAX_CANVAS_PIXELS = 8_000_000;
let sheets;
let sharedLinework;
let sheetsRequest;
let view;
let revision = 0;
const sharedPathCache = new WeakMap();

async function readSheets() {
  const groups = await Promise.all(["a", "b"].map(async building => {
    const response = await fetch(new URL(`data/zhuoyue-floor-plans/${building}.json`, import.meta.url));
    if (!response.ok) throw new Error("Floor plan unavailable");
    return (await response.json()).map(sheet => ({ ...sheet, building }));
  }));
  const response = await fetch(new URL("data/zhuoyue-floor-plans/page-5.json", import.meta.url));
  if (!response.ok) throw new Error("Shared floor plan unavailable");
  const raw = await response.text();
  if (new TextEncoder().encode(raw).byteLength > MAX_SHARED_BYTES) {
    throw new Error("Shared floor plan is too large");
  }
  const linework = JSON.parse(raw);
  const allSheets = groups.flat();
  validateSharedLinework(linework, allSheets);
  return { sheets: allSheets, sharedLinework: linework };
}

function buildingName(sheet) {
  return `${sheet.building.toUpperCase()} 栋`;
}

function displayLevel(sheet) {
  return sheet.buildingLevel || `图注${sheet.level}`;
}

function floorTitle(sheet) {
  return `${buildingName(sheet)}${displayLevel(sheet)}`;
}

function sourceSummary(renderedSheets, sourcePage) {
  const captions = [...new Set(renderedSheets.map(item => item.level))]
    .map(level => `图注「${level}」`)
    .join(" / ");
  return `来源第 ${sourcePage} 页 · ${captions}`;
}

function viewportKey(sheet) {
  return sharedLinework.sheetIds.includes(sheet.id)
    ? `page-${sharedLinework.sourcePage}:${sharedLinework.width}x${sharedLinework.height}`
    : `sheet:${sheet.id}:${sheet.width}x${sheet.height}`;
}

function sheetsForCanvas(sheet) {
  if (!sharedLinework.sheetIds.includes(sheet.id)) return [sheet];
  return sharedLinework.sheetIds.map(id => sheets.find(item => item.id === id));
}

function sharedLineworkFor(sheet) {
  return sharedLinework.sheetIds.includes(sheet.id) ? sharedLinework : null;
}

function validateSharedLinework(linework, allSheets) {
  const sheetIds = ["a-3", "b-3"];
  if (linework.sourcePage !== 5 || JSON.stringify(linework.sheetIds) !== JSON.stringify(sheetIds)) {
    throw new Error("Shared floor plan source is invalid");
  }
  if (!Number.isInteger(linework.width) || !Number.isInteger(linework.height) ||
      linework.width < 1 || linework.width > 20_000 ||
      linework.height < 1 || linework.height > 20_000) {
    throw new Error("Shared floor plan size is invalid");
  }
  const members = sheetIds.map(id => allSheets.find(item => item.id === id));
  if (members.some(item => !item || item.sourcePage !== 5 ||
      item.width !== linework.width || item.height !== linework.height ||
      typeof item.buildingLevel !== "string" || !item.buildingLevel.trim())) {
    throw new Error("Shared floor plan sheets do not match");
  }
  if (!Array.isArray(linework.contours) || linework.contours.length < 1 ||
      linework.contours.length > MAX_SHARED_CONTOURS) {
    throw new Error("Shared floor plan contours are invalid");
  }
  let totalPoints = 0;
  for (const contour of linework.contours) {
    if (!Array.isArray(contour) || contour.length < 3 || contour.length > 4096) {
      throw new Error("Shared floor plan contour is invalid");
    }
    totalPoints += contour.length;
    if (totalPoints > MAX_SHARED_POINTS) throw new Error("Shared floor plan has too many points");
    for (const point of contour) {
      if (!Array.isArray(point) || point.length !== 2 ||
          !Number.isInteger(point[0]) || !Number.isInteger(point[1]) ||
          point[0] < 0 || point[0] > linework.width ||
          point[1] < 0 || point[1] > linework.height) {
        throw new Error("Shared floor plan point is out of bounds");
      }
    }
  }
}

function sharedPath(linework) {
  let path = sharedPathCache.get(linework);
  if (path) return path;
  path = new Path2D();
  for (const contour of linework.contours) {
    contour.forEach(([x, y], index) => index ? path.lineTo(x, y) : path.moveTo(x, y));
    path.closePath();
  }
  sharedPathCache.set(linework, path);
  return path;
}

function canvasDensity(sheet) {
  return Math.min(
    2,
    MAX_CANVAS_SIDE / sheet.width,
    MAX_CANVAS_SIDE / sheet.height,
    Math.sqrt(MAX_CANVAS_PIXELS / (sheet.width * sheet.height)),
  );
}

function roomNumbers(text) {
  return text.normalize("NFKC").toUpperCase().match(/[AB]\s*\d{3}(?!\d)/g)
    ?.map(number => number.replace(/\s/g, "")) ?? [];
}

function drawPlan(canvas, sheet, renderedSheets, linework, selected) {
  const density = canvasDensity(sheet);
  canvas.width = Math.max(1, Math.round(sheet.width * density));
  canvas.height = Math.max(1, Math.round(sheet.height * density));
  const title = renderedSheets.map(floorTitle).join(" / ");
  const source = sourceSummary(renderedSheets, linework?.sourcePage ?? sheet.sourcePage);
  canvas.setAttribute(
    "aria-label",
    `${title}平面图，${source}${selected ? `，标记 ${selected.label}` : ""}`,
  );
  const context = canvas.getContext("2d");
  if (!context) return;
  const theme = getComputedStyle(canvas);
  const color = name => theme.getPropertyValue(`--${name}`).trim();
  context.setTransform(
    canvas.width / sheet.width,
    0,
    0,
    canvas.height / sheet.height,
    0,
    0,
  );

  function drawPolygon(points, fill, border, width, outline = true) {
    context.beginPath();
    points.forEach(([x, y], index) => index ? context.lineTo(x, y) : context.moveTo(x, y));
    context.closePath();
    context.fillStyle = color(fill);
    context.fill();
    if (outline) {
      context.strokeStyle = color(border);
      context.lineWidth = width;
      context.stroke();
    }
  }

  if (!linework) {
    for (const area of sheet.areas) {
      drawPolygon(area.points, area.kind === "floor" ? "hero" : "muted", "control-line", 1.25);
    }
    for (const room of sheet.rooms) {
      const isSelected = room === selected;
      drawPolygon(room.points, isSelected ? "dock-active" : "surface", isSelected ? "green" : "control-line", isSelected ? 3 : 1);
    }
  } else {
    for (const item of renderedSheets) {
      for (const area of item.areas) {
        if (area.kind === "floor") drawPolygon(area.points, "hero", "control-line", 1, false);
      }
    }
    for (const item of renderedSheets) {
      for (const area of item.areas) {
        if (area.kind === "stairs" || area.kind === "elevator") {
          drawPolygon(area.points, "muted", "control-line", 1, false);
        }
      }
    }
    for (const item of renderedSheets) {
      for (const room of item.rooms) {
        const isSelected = item === sheet && room === selected;
        drawPolygon(room.points, isSelected ? "dock-active" : "surface", "control-line", 1, false);
      }
    }
    context.globalAlpha = 0.78;
    context.fillStyle = color("ink");
    context.fill(sharedPath(linework), "evenodd");
    context.globalAlpha = 1;
  }
  const labelFontSize = linework ? 25 : 12;
  for (const item of renderedSheets) {
    for (const room of item.rooms) {
      const isSelected = item === sheet && room === selected;
      const xs = room.points.map(point => point[0]);
      const ys = room.points.map(point => point[1]);
      const width = Math.max(...xs) - Math.min(...xs);
      context.fillStyle = color(isSelected && linework ? "green" : "ink");
      context.font = `${isSelected ? "bold " : ""}${labelFontSize}px -apple-system, sans-serif`;
      context.textAlign = "center";
      context.textBaseline = "middle";
      context.fillText(room.label, Math.min(...xs) + width / 2, (Math.min(...ys) + Math.max(...ys)) / 2, Math.max(width - 4, 10));
    }
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

  function sheetForBuilding(targetBuilding) {
    if (sharedLinework.sheetIds.includes(sheet.id)) {
      const paired = sheets.find(item => item.building === targetBuilding &&
        sharedLinework.sheetIds.includes(item.id));
      if (paired) return paired;
    }
    return sheets.find(item => item.building === targetBuilding);
  }

  function setZoom(value) {
    zoom = Math.min(4, Math.max(1, value));
    canvas.style.width = `${zoom * 100}%`;
    mount.querySelector('[data-zoom="out"]').disabled = zoom === 1;
    mount.querySelector('[data-zoom="in"]').disabled = zoom === 4;
  }

  function show(sheetToShow, room) {
    const preservedViewport = sheet && viewportKey(sheet) === viewportKey(sheetToShow)
      ? { zoom, left: viewport.scrollLeft, top: viewport.scrollTop }
      : null;
    sheet = sheetToShow;
    selected = room;
    building.value = sheet.building;
    const renderedSheets = sheetsForCanvas(sheet);
    level.replaceChildren(...sheets.filter(item => item.building === sheet.building)
      .map(item => new Option(displayLevel(item), item.id)));
    level.value = sheet.id;
    const title = renderedSheets.map(floorTitle).join(" / ");
    const source = sourceSummary(renderedSheets, sharedLineworkFor(sheet)?.sourcePage ?? sheet.sourcePage);
    status.textContent = room
      ? `${title} · ${room.label} · ${source}`
      : `${title} · ${source}`;
    matches.replaceChildren();
    drawPlan(canvas, sheet, renderedSheets, sharedLineworkFor(sheet), room);
    setZoom(room ? 2.5 : preservedViewport?.zoom ?? 1);
    if (room) {
      const center = room.points.reduce(([x, y], [nextX, nextY]) => [x + nextX, y + nextY], [0, 0])
        .map(value => value / room.points.length);
      viewport.scrollTo({
        left: center[0] / sheet.width * canvas.clientWidth - viewport.clientWidth / 2,
        top: center[1] / sheet.height * canvas.clientHeight - viewport.clientHeight / 2,
      });
    } else if (preservedViewport) {
      viewport.scrollTo(preservedViewport.left, preservedViewport.top);
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
      button.textContent = `${floorTitle(result.sheet)} · 位置 ${index + 1} · ` +
        sourceSummary([result.sheet], result.sheet.sourcePage);
      button.addEventListener("click", () => show(result.sheet, result.room));
      matches.append(button);
    }
  }

  mount.querySelector("form").addEventListener("submit", event => {
    event.preventDefault();
    find(query.value);
  });
  building.addEventListener("change", () => show(sheetForBuilding(building.value)));
  level.addEventListener("change", () => show(sheets.find(item => item.id === level.value)));
  mount.querySelector(".floorplan-zoom").addEventListener("click", event => {
    const action = event.target.closest("[data-zoom]")?.dataset.zoom;
    if (action) setZoom(action === "reset" ? 1 : zoom + (action === "in" ? .5 : -.5));
  });
  document.documentElement.addEventListener("pocket-theme-change", () => {
    drawPlan(canvas, sheet, sheetsForCanvas(sheet), sharedLineworkFor(sheet), selected);
  });
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
      const catalog = await sheetsRequest;
      sheets = catalog.sheets;
      sharedLinework = catalog.sharedLinework;
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
