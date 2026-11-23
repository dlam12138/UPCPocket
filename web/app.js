import { refreshFocus } from "./focus.js";
import { refreshTodos } from "./todo.js";
import { initializeClassrooms } from "./classroom.js";
import { initializeCalendar } from "./calendar.js";
import { initializeServiceCatalog } from "./service-catalog.js";
import { initializeTimetable, refreshTimetableDate } from "./timetable.js";
import { initializeLibrary } from "./library.js?v=20";

const services = [
  { name: "空闲教室", source: "两校区 · 按楼栋和节次查询", icon: "room", color: "sage", route: "classroom", common: true },
  { name: "图书馆", source: "学校官方预约与记录", icon: "book", color: "honey", route: "library", common: true },
  { name: "学校校历", source: "查看教务处发布的校历", icon: "calendar", color: "blue", route: "calendar", common: true },
  { name: "我的课表", source: "学校课表与同步状态", icon: "calendar", color: "sage", route: "timetable", common: true },
  { name: "e站通", source: "公开事项目录与办事指南", icon: "layers", color: "lavender", route: "service-catalog" },
  { name: "石光", icon: "book", color: "honey" },
  { name: "校园卡", icon: "card", color: "sage" },
  { name: "宿舍电量", icon: "bolt", color: "blue" },
];

function renderServices() {
  for (const list of document.querySelectorAll("[data-service-list]")) {
    const cards = list.dataset.serviceList === "common"
      ? services.filter(service => service.common)
      : services;
    list.innerHTML = cards.map(service => {
      const contents = `
        <span class="tile-icon ${service.color}">
          <svg class="icon" aria-hidden="true"><use href="#i-${service.icon}"/></svg>
        </span>
        <span class="service-title">${service.name}</span>
        <span class="service-origin">${service.source ?? "网页版暂未接入"}</span>`;
      return service.route
        ? `<a class="service-card" href="#${service.route}">
            <svg class="icon service-arrow" aria-hidden="true"><use href="#i-arrow"/></svg>${contents}</a>`
        : `<div class="service-card service-card-unavailable">${contents}</div>`;
    }).join("");
  }
}

const campusDateOptions = { timeZone: "Asia/Shanghai", year: "numeric", month: "long", day: "numeric" };
const campusDate = new Intl.DateTimeFormat("zh-CN", campusDateOptions);
const campusWeekday = new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", weekday: "long" });

function updateDate() {
  const now = new Date();
  const parts = Object.fromEntries(campusDate.formatToParts(now).map(part => [part.type, part.value]));
  const month = parts.month.replace("月", "").padStart(2, "0");
  const day = parts.day.padStart(2, "0");
  for (const label of document.querySelectorAll(".today")) {
    label.dateTime = `${parts.year}-${month}-${day}`;
    label.textContent = `${campusDate.format(now)} · ${campusWeekday.format(now)}`;
  }
}

const settingsButton = document.querySelector("#open-settings");
const settings = document.querySelector("#settings");
const transparency = document.querySelector("#reduce-transparency");
const settingsStatus = document.querySelector("#settings-status");
const transparencyKey = "upc-pocket:reduce-transparency";

function applyTransparency() {
  if (transparency.checked) {
    document.documentElement.dataset.transparency = "reduced";
  } else {
    delete document.documentElement.dataset.transparency;
  }
}

try {
  transparency.checked = localStorage.getItem(transparencyKey) === "true";
  applyTransparency();
} catch {
  settingsStatus.textContent = "外观设置暂时无法读取";
}

settingsButton.addEventListener("click", () => settings.showModal());
transparency.addEventListener("change", () => {
  applyTransparency();
  try {
    localStorage.setItem(transparencyKey, String(transparency.checked));
    settingsStatus.textContent = "";
  } catch {
    settingsStatus.textContent = "外观设置仅在本次打开时生效";
  }
});

// 地址末尾的 #home、#timetable 等标记当前页面，浏览器的前进和后退也能使用。
const pages = Array.from(document.querySelectorAll(".page"));
const navigation = document.querySelectorAll(".dock a");
let stationEnabled = false;
let setLibraryActive = () => {};
const libraryRoutes = new Map([
  ["library", "tangdaowan"], ["library-east", "east"], ["library-qiushi", "qiushi"],
]);

function showPage(moveFocus) {
  const id = window.location.hash.slice(1);
  const libraryBranch = libraryRoutes.get(id);
  const active = pages.find(page => page.dataset.route === (libraryBranch ? "library" : id)) || pages[0];
  const dockRoute = active.dataset.parent ?? active.dataset.route;
  for (const page of pages) {
    page.hidden = page !== active;
  }
  for (const link of navigation) {
    if (link.hash === "#" + dockRoute) {
      link.setAttribute("aria-current", "page");
      const title = active.dataset.parent ? active.querySelector("h1").textContent : link.textContent;
      document.title = title.trim() + " · UPC Pocket";
    } else {
      link.removeAttribute("aria-current");
    }
  }
  settingsButton.hidden = active.dataset.route !== "home";
  if (settingsButton.hidden && settings.open) settings.close();
  if (active.dataset.route === "todo") refreshTodos();
  if (active.dataset.route === "focus") refreshFocus();
  if (active.dataset.route === "calendar") initializeCalendar();
  if (stationEnabled && active.dataset.route === "service-catalog") initializeServiceCatalog();
  setLibraryActive(libraryBranch ?? null);
  if (moveFocus) {
    active.querySelector("h1").focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }
}

function resumePage() {
  updateDate();
  refreshTimetableDate();
  refreshFocus();
  if (window.location.hash === "#todo") refreshTodos();
}

// 默认按 official（仅学校官方入口）呈现；只有服务端显式返回 station 才启用站内登录。
function applyOfficialMode() {
  document.querySelector("#classroom-app").hidden = true;
  document.querySelector("#service-catalog-app").hidden = true;
  for (const node of document.querySelectorAll(".library-campus-nav, .library-booking")) {
    if (node.dataset.officialPreviousHidden === undefined) {
      node.dataset.officialPreviousHidden = node.hidden ? "1" : "0";
    }
    node.hidden = true;
  }
  const libraryOfficial = document.querySelector("#library-official");
  if (libraryOfficial) {
    libraryOfficial.hidden = false;
    const note = libraryOfficial.querySelector("[data-library-official-note]");
    if (note) note.hidden = false;
    for (const link of libraryOfficial.querySelectorAll("[data-library-official]")) {
      link.hidden = false;
    }
  }
  for (const panel of document.querySelectorAll("[data-official-entry]")) panel.hidden = false;
  const source = { classroom: "官方入口", library: "官方入口", "service-catalog": "官方入口" };
  for (const card of document.querySelectorAll(".service-card[href^='#']")) {
    const route = card.getAttribute("href").slice(1);
    if (!source[route]) continue;
    const label = card.querySelector(".service-origin");
    if (!label) continue;
    // 记录 station 模式的原文，切回站内模式时恢复，避免首页长期停留在「官方入口」。
    if (!label.dataset.stationSource) label.dataset.stationSource = label.textContent;
    label.textContent = source[route];
  }
}

function applyStationMode() {
  stationEnabled = true;
  for (const panel of document.querySelectorAll("[data-official-entry]")) panel.hidden = true;
  document.querySelector("#classroom-app").hidden = false;
  document.querySelector("#service-catalog-app").hidden = false;
  for (const node of document.querySelectorAll(".library-campus-nav, .library-booking")) {
    node.hidden = node.dataset.officialPreviousHidden === "1";
  }
  const libraryOfficial = document.querySelector("#library-official");
  if (libraryOfficial) {
    libraryOfficial.hidden = false;
    const note = libraryOfficial.querySelector("[data-library-official-note]");
    if (note) note.hidden = true;
  }
  for (const label of document.querySelectorAll(".service-origin[data-station-source]")) {
    label.textContent = label.dataset.stationSource;
  }
  initializeClassrooms();
  setLibraryActive = initializeLibrary();
  showPage(false);
}

async function readSchoolAccess() {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 3000);
    const response = await fetch("./api/config", {
      cache: "no-store", credentials: "omit", redirect: "error", signal: controller.signal,
    });
    clearTimeout(timer);
    if (!response.ok) return "official";
    const data = await response.json();
    return data?.schoolAccess === "station" ? "station" : "official";
  } catch {
    return "official";
  }
}

renderServices();
updateDate();
applyOfficialMode();
initializeTimetable();
refreshFocus();
showPage(false);
readSchoolAccess().then(mode => {
  if (mode === "station") applyStationMode();
});
window.addEventListener("hashchange", () => showPage(true));
window.addEventListener("pageshow", resumePage);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) resumePage();
});

function registerOffline() {
  if (!window.isSecureContext || !("serviceWorker" in navigator)) return;
  navigator.serviceWorker.register("./sw.js").catch(() => {
    console.warn("UPC Pocket offline setup failed");
  });
}

// Let the first screen finish before the offline cache starts downloading.
if (document.readyState === "complete") registerOffline();
else window.addEventListener("load", registerOffline, { once: true });
