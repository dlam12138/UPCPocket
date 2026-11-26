import {
  isInsideCampus, mapDistance, planCampusRoute, resolveCampusPlace, routeProgress, searchCampusPlaces,
} from "./campus-map-routing.js";

const MAP_DATA = new URL("./data/campus_map.json", import.meta.url);
const DEGREES_TO_RADIANS = Math.PI / 180;
const locationErrors = {
  1: "定位权限未开启，可手动选择起点预览",
  2: "暂时无法获取位置，可手动选择起点预览",
  3: "定位超时，请在室外重试",
};
const schemes = {
  light: {
    background: "#edf1e6", area: "#e0ebd7", building: "#b0bdaa", field: "#c0d8ae",
    water: "#adcedb", road: "#fffef8", path: "#c0cabc", ink: "#263c33", selection: "#235f44",
    walk: "#b04913", bike: "#2353a5", accuracy: "#225ac627", location: "#135ac0", pinBorder: "#fffef8",
  },
  dark: {
    background: "#23342c", area: "#304634", building: "#64766a", field: "#436046",
    water: "#365d69", road: "#91a394", path: "#566d60", ink: "#f0f8f3", selection: "#b1e4c4",
    walk: "#ffbe90", bike: "#95bfff", accuracy: "#8baeff3d", location: "#8baeff", pinBorder: "#102822",
  },
};

let controller;
let loadTask;
let isVisible = false;

async function readMap() {
  const response = await fetch(MAP_DATA, { credentials: "omit" });
  if (!response.ok) throw new Error("Campus map data unavailable");
  const data = await response.json();
  if (!Array.isArray(data.campuses) || data.campuses.length === 0) throw new Error("Missing campuses");
  return data;
}

function mapMarkup() {
  return `
    <div class="campus-map-toolbar">
      <label>校区<select data-campus-map-campus aria-label="校园地图校区"></select></label>
      <label>找地点<input type="search" data-campus-map-search placeholder="搜楼栋、校门或场馆" autocomplete="off"></label>
    </div>
    <ul class="campus-map-results" data-campus-map-results aria-label="校园地图搜索结果" hidden></ul>
    <div class="campus-map-panel">
      <canvas class="campus-map-canvas" aria-label="校园地图，起终点可从下方表单选择" role="img"></canvas>
      <span class="campus-map-north" aria-hidden="true">↑ 北</span>
      <div class="campus-map-zoom" aria-label="地图缩放">
        <button type="button" data-zoom="in" aria-label="放大地图">+</button>
        <button type="button" data-zoom="out" aria-label="缩小地图">−</button>
        <button type="button" data-zoom="reset" aria-label="重置地图">重置</button>
      </div>
    </div>
    <p class="campus-map-credit">© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap contributors · ODbL 1.0</a> · <a data-campus-map-data download="campus_map.json">地图数据</a></p>
    <p class="campus-map-coverage" data-campus-map-coverage>古镇口西区可查看地点，主要校内路网尚未收录。</p>
    <div class="campus-map-selection" data-campus-map-selection hidden>
      <p data-campus-map-selected-name></p>
      <div>
        <button class="text-button" type="button" data-campus-map-set-start>设为起点</button>
        <button class="text-button" type="button" data-campus-map-set-end>设为终点</button>
      </div>
    </div>
    <section class="detail-card campus-map-route" aria-labelledby="campus-map-route-title">
      <h2 id="campus-map-route-title">校内路线</h2>
      <form class="campus-map-route-form">
        <div class="campus-map-route-fields">
          <label>起点<select data-campus-map-start aria-label="路线起点"></select></label>
          <label>终点<select data-campus-map-end aria-label="路线终点"></select></label>
          <label>方式<select data-campus-map-mode aria-label="出行方式">
            <option value="walk">步行</option><option value="bike">骑行</option>
          </select></label>
        </div>
        <div class="campus-map-actions">
          <button class="button" type="submit">预览路线</button>
          <button class="text-button" type="button" data-campus-map-live>开始导航</button>
          <button class="text-button" type="button" data-campus-map-follow hidden>回到当前位置</button>
        </div>
      </form>
      <p data-campus-map-route-status role="status"></p>
      <p data-campus-map-position></p>
      <p data-campus-map-guidance aria-live="polite" aria-atomic="true"></p>
    </section>
  `;
}

function readableMetres(metres) {
  return metres >= 1000 ? (metres / 1000).toFixed(1) + " 公里" : Math.round(metres) + " 米";
}

function summarizeRoute(route, mode) {
  if (route.error) return route.error;
  if (route.metres < 2) return "起点已在目的地附近";
  const minutes = Math.max(1, Math.round(route.seconds / 60));
  const walking = route.metres - route.bikeMetres;
  let summary = `${readableMetres(route.metres)} · 约 ${minutes} 分钟`;
  if (mode === "bike" && walking > 2) {
    summary += ` · 骑行 ${readableMetres(route.bikeMetres)}，步行 ${readableMetres(walking)}`;
  }
  if (route.entrance) summary += " · 至" + route.entrance;
  if (route.approximate) summary += " · 路线到达目的地附近的道路";
  return summary + "。以现场通行为准。";
}

function campusCenter(bounds) {
  return [(bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2];
}

function createMapController(root, data) {
  root.innerHTML = mapMarkup();
  const element = name => root.querySelector("[data-campus-map-" + name + "]");
  const campusSelect = element("campus");
  const search = element("search");
  const searchResults = element("results");
  const start = element("start");
  const end = element("end");
  const mode = element("mode");
  const routeStatus = element("route-status");
  const guidance = element("guidance");
  const locationText = element("position");
  const searchSelection = element("selection");
  const liveButton = element("live");
  const followButton = element("follow");
  const canvas = root.querySelector("canvas");
  const context = canvas.getContext("2d");
  const theme = matchMedia("(prefers-color-scheme: dark)");
  element("data").href = MAP_DATA.href;

  for (const entry of data.campuses) campusSelect.add(new Option(entry.name, entry.id));
  let campus;
  let selected;
  let route;
  let view;
  let zoom = 1;
  let drag;
  let transform;
  let frame;
  let watch;
  let locationSession = 0;
  let lastPosition;
  let following = false;
  let lastProgress = 0;
  let offRoute = 0;
  let lastCalculation = 0;

  function paint() {
    frame = undefined;
    if (!context || !campus) return;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (!width || !height) return;
    const ratio = Math.min(devicePixelRatio || 1, 2);
    if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
    }
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    const colors = theme.matches ? schemes.dark : schemes.light;
    const lonScale = Math.cos(view[1] * DEGREES_TO_RADIANS);
    const [west, south, east, north] = campus.bounds;
    const scale = zoom * Math.min((width - 30) / (east - west) / lonScale, (height - 30) / (north - south));
    const xy = point => [width / 2 + (point[0] - view[0]) * scale * lonScale, height / 2 - (point[1] - view[1]) * scale];
    transform = { scale, lonScale, xy };

    context.fillStyle = colors.background;
    context.fillRect(0, 0, width, height);
    context.lineCap = "round";
    context.lineJoin = "round";

    function line(points) {
      context.beginPath();
      context.moveTo(...xy(points[0]));
      for (let i = 1; i < points.length; i++) context.lineTo(...xy(points[i]));
    }
    for (const kind of ["area", "water", "field", "building", "road", "path"]) {
      for (const feature of campus.geometry) {
        if (feature.kind !== kind || feature.points.length < 2) continue;
        line(feature.points);
        if (kind === "road" || kind === "path") {
          context.lineWidth = kind === "road" ? 2.6 : 1.0;
          context.strokeStyle = colors[kind];
          context.stroke();
        } else {
          context.fillStyle = colors[kind];
          context.fill();
        }
      }
    }
    if (route) {
      let previousMode;
      for (const segment of route.segments) {
        if (previousMode !== segment.travel) {
          if (previousMode) context.stroke();
          context.beginPath();
          context.moveTo(...xy(segment.a));
          context.lineWidth = 4;
          context.strokeStyle = colors[segment.travel];
          context.setLineDash(segment.travel === "walk" ? [6, 4] : []);
          previousMode = segment.travel;
        }
        context.lineTo(...xy(segment.b));
      }
      if (previousMode) context.stroke();
      context.setLineDash([]);
    }
    function dot(point, radius, fill, border = false) {
      context.beginPath();
      context.arc(...xy(point), radius, 0, Math.PI * 2);
      context.fillStyle = fill;
      context.fill();
      if (border) {
        context.strokeStyle = colors.pinBorder;
        context.lineWidth = 2;
        context.stroke();
      }
    }
    for (const place of campus.places) {
      if (!place.point || (place.kind === "entrance" && zoom < 2)) continue;
      dot(place.point, 2, colors.ink);
    }
    if (route) {
      dot(route.start, 6, colors.bike, true);
      dot(route.end, 7, colors.walk, true);
    }
    if (selected?.point) {
      dot(selected.point, 7, colors.selection, true);
      const [x, y] = xy(selected.point);
      context.font = "bold 13px -apple-system, sans-serif";
      context.textAlign = "center";
      context.lineWidth = 4;
      context.strokeStyle = colors.pinBorder;
      context.strokeText(selected.name, x, y - 12);
      context.fillStyle = colors.ink;
      context.fillText(selected.name, x, y - 12);
    }
    if (lastPosition) {
      dot(lastPosition.point, Math.min(130, lastPosition.accuracy * scale / 111195), colors.accuracy);
      dot(lastPosition.point, 7, colors.location, true);
    }
  }

  function redraw() {
    if (frame === undefined) frame = requestAnimationFrame(paint);
  }

  function updateSearch() {
    searchResults.replaceChildren();
    const term = search.value;
    const results = searchCampusPlaces(campus, term);
    searchResults.hidden = !term.trim();
    for (const place of results.slice(0, 14)) {
      const item = document.createElement("li");
      const button = document.createElement("button");
      button.className = "text-button";
      button.type = "button";
      button.textContent = place.name + (place.region ? " · " + place.region : "");
      button.onclick = () => selectPlace(place);
      item.append(button);
      searchResults.append(item);
    }
    if (term.trim() && !results.length) {
      const item = document.createElement("li");
      item.textContent = "当前校区未找到地点";
      searchResults.append(item);
    }
  }

  function selectPlace(place, center = true) {
    selected = place;
    searchSelection.hidden = !place;
    element("selected-name").textContent = place ? place.name : "";
    if (center && place?.point) {
      view = place.point;
      zoom = Math.max(zoom, 2);
      setFollowing(false);
    }
    redraw();
  }

  function chooseCampus(id) {
    const next = data.campuses.find(candidate => candidate.id === id) ?? data.campuses[0];
    if (campus === next) return;
    stopLocation();
    campus = next;
    campusSelect.value = campus.id;
    view = campusCenter(next.bounds);
    zoom = 1;
    route = undefined;
    routeStatus.textContent = "";
    element("coverage").hidden = campus.id !== "gzk";
    const places = campus.places.filter(place => place.point)
      .sort((a, b) => a.name.localeCompare(b.name, "zh-CN"));
    for (const [control, label] of [[start, "选择起点"], [end, "选择终点"]]) {
      control.replaceChildren(new Option(label, ""));
      for (const place of places) control.add(new Option(place.name + (place.region ? " · " + place.region : ""), place.id));
    }
    search.value = "";
    selectPlace(undefined, false);
    updateSearch();
    redraw();
  }

  function routeTo(origin, destination) {
    const planned = planCampusRoute(campus, origin, destination, mode.value);
    route = planned.error ? undefined : planned;
    routeStatus.textContent = summarizeRoute(planned, mode.value);
    lastProgress = 0;
    lastCalculation = Date.now();
    offRoute = 0;
    redraw();
    return route;
  }

  function setFollowing(value) {
    following = value;
    followButton.hidden = watch === undefined || value;
  }

  function stopLocation() {
    locationSession++;
    if (watch !== undefined) navigator.geolocation.clearWatch(watch);
    watch = undefined;
    lastPosition = undefined;
    setFollowing(false);
    liveButton.textContent = "开始导航";
    locationText.textContent = "";
    guidance.textContent = "";
    redraw();
  }

  function destinationPlace() { return campus.places.find(place => place.id === end.value); }

  function updatePosition({ coords }, destination) {
    const point = [coords.longitude, coords.latitude];
    if (!point.every(Number.isFinite) || !Number.isFinite(coords.accuracy)) return;
    locationText.textContent = "定位精度约 ±" + Math.round(coords.accuracy) + " 米";
    if (coords.accuracy > 65) {
      guidance.textContent = "定位精度不足，请移到开阔处";
      return;
    }
    if (!isInsideCampus(campus, point)) {
      guidance.textContent = "当前位置在所选校区地图范围外";
      return;
    }
    lastPosition = { point, accuracy: coords.accuracy };
    if (following) {
      view = point;
      zoom = Math.max(3, zoom);
    }
    redraw();
    const calculate = () => routeTo({ point }, destination);
    if (!route && Date.now() - lastCalculation > 3500) calculate();
    if (!route) {
      guidance.textContent = "等待当前位置接近可用道路";
      return;
    }
    const progress = routeProgress(route, point, lastProgress);
    if (progress.gap > Math.max(24, coords.accuracy)) {
      guidance.textContent = "已离开路线，正在核对当前位置";
      if (++offRoute >= 2 && Date.now() - lastCalculation > 3500) calculate();
      return;
    }
    offRoute = 0;
    lastProgress = progress.progress;
    if (progress.remaining <= 12 && mapDistance(point, route.end) <= Math.max(15, coords.accuracy)) {
      const arrival = route.entrance ? "已到达" + route.entrance : "已到达目的地附近";
      stopLocation();
      guidance.textContent = arrival;
      return;
    }
    const nearby = progress.cue && progress.cue.distance <= 40;
    const instruction = progress.cue
      ? (nearby ? "前方" : `约 ${readableMetres(progress.cue.distance)}后`) + progress.cue.instruction
      : "沿当前道路继续";
    const next = instruction + " · 剩余 " + readableMetres(progress.remaining);
    if (guidance.textContent !== next) guidance.textContent = next;
  }

  function startLocation() {
    if (watch !== undefined) {
      stopLocation();
      routeStatus.textContent = "已停止导航";
      return;
    }
    const destination = destinationPlace();
    if (!destination) {
      routeStatus.textContent = "请选择终点";
      end.focus();
      return;
    }
    if (!window.isSecureContext || !navigator.geolocation) {
      guidance.textContent = "此网页地址或浏览器不支持定位，可手动预览路线";
      return;
    }
    const session = ++locationSession;
    route = undefined;
    lastCalculation = 0;
    routeStatus.textContent = "";
    guidance.textContent = "正在获取当前位置…";
    // A user gesture starts the foreground watch. Ignore callbacks from a stopped watch.
    try {
      watch = navigator.geolocation.watchPosition(
        position => { if (session === locationSession && !document.hidden) updatePosition(position, destination); },
        error => {
          if (session !== locationSession) return;
          stopLocation();
          guidance.textContent = locationErrors[error.code] ?? "定位失败，请重试";
        },
        { enableHighAccuracy: true, maximumAge: 3000, timeout: 12000 },
      );
      liveButton.textContent = "结束导航";
      setFollowing(true);
      redraw();
    } catch {
      stopLocation();
      guidance.textContent = "无法开启定位，可手动选择起点预览";
    }
  }

  function clearRoute() {
    stopLocation();
    route = undefined;
    routeStatus.textContent = "";
    redraw();
  }

  campusSelect.addEventListener("change", () => chooseCampus(campusSelect.value));
  search.addEventListener("input", updateSearch);
  for (const chooser of [start, end, mode]) chooser.addEventListener("change", clearRoute);
  for (const [name, chooser] of [["set-start", start], ["set-end", end]]) {
    element(name).addEventListener("click", () => {
      if (!selected) return;
      chooser.value = selected.id;
      clearRoute();
      chooser.focus();
    });
  }
  root.querySelector("form").addEventListener("submit", event => {
    event.preventDefault();
    const origin = campus.places.find(place => place.id === start.value);
    const destination = destinationPlace();
    if (!origin || !destination) {
      routeStatus.textContent = "请选择起点和终点";
      (origin ? end : start).focus();
      return;
    }
    stopLocation();
    if (origin === destination) {
      routeStatus.textContent = "起点和终点相同";
      route = undefined;
      redraw();
      return;
    }
    if (routeTo({ point: origin.point, place: origin }, destination)) {
      const points = [route.start, route.end, ...route.segments.map(segment => segment.b)];
      const lngs = points.map(point => point[0]);
      const lats = points.map(point => point[1]);
      const bounds = [Math.min(...lngs), Math.min(...lats), Math.max(...lngs), Math.max(...lats)];
      const worldWidth = campus.bounds[2] - campus.bounds[0];
      const worldHeight = campus.bounds[3] - campus.bounds[1];
      view = campusCenter(bounds);
      zoom = Math.max(1, Math.min(4, worldWidth / (bounds[2] - bounds[0]) * 0.8,
        worldHeight / (bounds[3] - bounds[1]) * 0.8));
      selectPlace(destination, false);
    }
  });
  liveButton.addEventListener("click", startLocation);
  followButton.addEventListener("click", () => {
    if (!lastPosition) return;
    view = lastPosition.point;
    setFollowing(true);
    redraw();
  });

  for (const button of root.querySelectorAll("[data-zoom]")) {
    button.addEventListener("click", () => {
      if (button.dataset.zoom === "reset") {
        view = campusCenter(campus.bounds);
        zoom = 1;
      } else {
        zoom = Math.max(1, Math.min(9, zoom * (button.dataset.zoom === "in" ? 1.4 : 1 / 1.4)));
      }
      setFollowing(false);
      redraw();
    });
  }
  canvas.addEventListener("pointerdown", event => {
    if (drag || !event.isPrimary || event.button !== 0) return;
    drag = { id: event.pointerId, x: event.clientX, y: event.clientY, origin: view, moved: false };
    canvas.setPointerCapture(event.pointerId);
  });
  canvas.addEventListener("pointermove", event => {
    if (event.pointerId !== drag?.id || !transform) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    if (!drag.moved && Math.hypot(dx, dy) < 5) return;
    drag.moved = true;
    setFollowing(false);
    const [west, south, east, north] = campus.bounds;
    view = [
      Math.max(west, Math.min(east, drag.origin[0] - dx / (transform.scale * transform.lonScale))),
      Math.max(south, Math.min(north, drag.origin[1] + dy / transform.scale)),
    ];
    redraw();
  });
  canvas.addEventListener("pointerup", event => {
    if (event.pointerId !== drag?.id) return;
    if (!drag.moved && transform) {
      const box = canvas.getBoundingClientRect();
      const x = event.clientX - box.left;
      const y = event.clientY - box.top;
      let nearest;
      let distance = 15;
      for (const place of campus.places) {
        if (!place.point || (place.kind === "entrance" && zoom < 2)) continue;
        const [px, py] = transform.xy(place.point);
        const gap = Math.hypot(px - x, py - y);
        if (gap < distance) {
          nearest = place;
          distance = gap;
        }
      }
      if (nearest) selectPlace(nearest, false);
    }
    drag = undefined;
  });
  canvas.addEventListener("lostpointercapture", () => { drag = undefined; });
  if (typeof ResizeObserver === "function") new ResizeObserver(redraw).observe(canvas);
  else addEventListener("resize", redraw);
  theme.addEventListener("change", redraw);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden && watch !== undefined) stopLocation();
  });
  addEventListener("pagehide", stopLocation);

  return {
    show({ campus: campusId, place }) {
      const match = place ? resolveCampusPlace(data.campuses, place, campusId) : null;
      chooseCampus(match?.campus.id ?? (campusId && campusId !== "all" ? campusId : campus?.id));
      if (match?.place) {
        selectPlace(match.place);
        end.value = match.place.id;
        clearRoute();
      } else if (place) {
        search.value = place;
        updateSearch();
      }
      redraw();
    },
    hide: stopLocation,
  };
}

export async function showCampusMap(options = {}) {
  isVisible = true;
  const root = document.querySelector("#campus-map-app");
  if (!root) return;
  if (!controller) {
    root.textContent = "正在载入离线地图…";
    loadTask ??= readMap();
    try {
      const data = await loadTask;
      if (!isVisible) return;
      controller ??= createMapController(root, data);
    } catch {
      loadTask = undefined;
      if (!isVisible) return;
      const retry = document.createElement("button");
      retry.type = "button";
      retry.className = "text-button";
      retry.textContent = "重新加载";
      retry.onclick = () => showCampusMap(options);
      root.replaceChildren("暂时无法载入地图，请联网重试。", retry);
      return;
    }
  }
  if (isVisible) controller.show(options);
}

export function hideCampusMap() {
  isVisible = false;
  controller?.hide();
}
