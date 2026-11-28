import { initializeAgenda, refreshAgenda, selectAgendaDate } from "./agenda.js?v=24";
import { showCampusCalendar } from "./calendar.js";
import { getPreferredCampus, initializeCampusServices, showCampusServices } from "./campus-services.js?v=26";
import { refreshFocus } from "./focus.js?v=26";
import { refreshTodos } from "./todo.js?v=26";
import { initializeTimetable, refreshTimetableDate } from "./timetable.js?v=24";

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
const dock = document.querySelector(".dock");
const navigation = Array.from(dock.querySelectorAll("a"));
// 旧版图书馆分馆地址仍指向同一页，三个馆区都在该页给出官方入口。
const legacyLibraryRoutes = new Set(["library-east", "library-qiushi"]);

let activePage;
let mapModule;

function openStaticFeature(load, page, containerId, show) {
  load().then(module => {
    if (activePage === page && location.hash === page.hash) return show(module);
  }).catch(() => {
    if (activePage !== page || location.hash !== page.hash) return;
    const container = document.getElementById(containerId);
    container.replaceChildren();
    const message = document.createElement("p");
    message.textContent = "暂时无法打开，请重新加载页面后再试。";
    message.setAttribute("role", "status");
    container.append(message);
  });
}

function showPage(moveFocus) {
  const [id, query] = window.location.hash.slice(1).split("?", 2);
  const route = legacyLibraryRoutes.has(id) ? "library" : id;
  const active = pages.find(page => page.dataset.route === route) || pages[0];
  const activeRoute = active.dataset.route;
  const params = new URLSearchParams(query);
  const agendaDate = activeRoute === "timetable" ? params.get("agenda") : null;
  const dockRoute = active.dataset.parent ?? activeRoute;
  const dockIndex = navigation.findIndex(link => link.hash === "#" + dockRoute);
  const previous = activePage;
  dock.style.setProperty("--dock-index", dockIndex);
  if (previous && previous.element !== active) {
    delete previous.element.dataset.enter;
    const backward = dockIndex < previous.dockIndex ||
      (dockIndex === previous.dockIndex && previous.element.dataset.parent === activeRoute);
    active.dataset.enter = backward ? "backward" : "forward";
  }
  if (activePage?.route === "map" && (activeRoute !== "map" || activePage.hash !== location.hash)) {
    mapModule?.hideCampusMap();
  }
  const page = { route: activeRoute, hash: location.hash, element: active, dockIndex };
  activePage = page;
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
  showCampusServices(activeRoute);
  if (activeRoute === "todo") refreshTodos();
  if (activeRoute === "focus") refreshFocus();
  if (agendaDate && /^\d{4}-\d{2}-\d{2}$/.test(agendaDate)) selectAgendaDate(agendaDate);
  else if (activeRoute === "home" || activeRoute === "timetable") refreshAgenda();
  if (activeRoute === "calendar") showCampusCalendar();
  if (activeRoute === "map") {
    openStaticFeature(() => import("./campus-map.js?v=26"), page, "campus-map-app", module => {
      mapModule = module;
      return module.showCampusMap({ campus: getPreferredCampus(), place: params.get("place") });
    });
  }
  if (activeRoute === "floorplan") {
    openStaticFeature(() => import("./floorplan.js?v=27"), page, "campus-floorplan-app",
      module => module.showFloorplan(params.get("room")));
  }
  if (moveFocus) {
    active.querySelector("h1").focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }
  if (agendaDate) document.getElementById("campus-agenda").scrollIntoView();
}

function resumePage() {
  updateDate();
  refreshTimetableDate();
  refreshFocus();
  if (activePage?.route === "todo") refreshTodos();
  if (activePage?.route === "home" || activePage?.route === "timetable") refreshAgenda();
}

const lightTracking = matchMedia("(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)");
let highlight;
let highlightFrame;
document.querySelector(".content").addEventListener("pointermove", event => {
  if (!lightTracking.matches || event.pointerType !== "mouse") return;
  const card = event.target.closest(".service-card, .timetable-card");
  if (!card) return;
  highlight = { card, x: event.clientX, y: event.clientY };
  if (highlightFrame !== undefined) return;
  highlightFrame = requestAnimationFrame(() => {
    highlightFrame = undefined;
    const { card, x, y } = highlight;
    const bounds = card.getBoundingClientRect();
    card.style.setProperty("--pointer-x", `${x - bounds.left}px`);
    card.style.setProperty("--pointer-y", `${y - bounds.top}px`);
  });
});

initializeCampusServices();
initializeAgenda();
updateDate();
initializeTimetable();
refreshFocus();
showPage(false);
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
