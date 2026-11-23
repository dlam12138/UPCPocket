import { refreshFocus } from "./focus.js";
import { refreshTodos } from "./todo.js";
import { initializeTimetable, refreshTimetableDate } from "./timetable.js";

const services = [
  { name: "空闲教室", source: "学校官方查询入口", icon: "room", color: "sage", route: "classroom", common: true },
  { name: "图书馆", source: "三个馆区的官方入口", icon: "book", color: "honey", route: "library", common: true },
  { name: "学校校历", source: "教务处校历栏目", icon: "calendar", color: "blue", route: "calendar", common: true },
  { name: "我的课表", source: "本机导入与离线查看", icon: "calendar", color: "sage", route: "timetable", common: true },
  { name: "e站通", source: "学校官方服务大厅", icon: "layers", color: "lavender", route: "service-catalog" },
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
// 旧版图书馆分馆地址仍指向同一页，三个馆区都在该页给出官方入口。
const legacyLibraryRoutes = new Set(["library-east", "library-qiushi"]);

function showPage(moveFocus) {
  const id = window.location.hash.slice(1);
  const route = legacyLibraryRoutes.has(id) ? "library" : id;
  const active = pages.find(page => page.dataset.route === route) || pages[0];
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

renderServices();
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
