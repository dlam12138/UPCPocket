const campusLabels = { all: "全部校区", tdw: "唐岛湾", gzk: "古镇口" };
const campusKey = "upc-pocket:preferred-campus";
const pinsKey = "upc-pocket:pinned-services";

const services = [
  { id: "classroom", name: "空闲教室", summary: "学校官方查询", icon: "room", color: "sage" },
  { id: "library", name: "图书馆座位", summary: "三个馆区的官方入口", icon: "book", color: "honey", keywords: "图书馆预约 libseat 超星" },
  { id: "calendar", name: "学校校历", summary: "教务处原图", icon: "calendar", color: "blue" },
  { id: "timetable", name: "我的课表", summary: "本机导入与离线查看", icon: "calendar", color: "sage" },
  { id: "directory", name: "校园通讯录", summary: "公开电话与办公地址", icon: "book", color: "honey", keywords: "学院 部门 拨号 电话" },
  { id: "map", name: "校园地图", summary: "地点搜索与路线预览", icon: "room", color: "sage", keywords: "导航 走路 步行 骑行 定位" },
  { id: "floorplan", name: "卓越楼平面图", summary: "古镇口 A、B 区离线楼层图", icon: "room", color: "blue", keywords: "卓越 教学楼 房号" },
  { id: "learning", name: "学习平台", summary: "学校与平台的网页入口", icon: "book", color: "lavender" },
  { id: "official", name: "校园官方网页", summary: "学校门户与办事入口", icon: "grid", color: "sage" },
  { id: "service-catalog", name: "e站通", summary: "学校官方服务大厅", icon: "layers", color: "lavender", keywords: "办事 事项 服务大厅" },
  { id: "power", name: "宿舍电量", summary: "两校区的官方入口", icon: "bolt", color: "blue", keywords: "剩余电量 电费 唐岛湾 古镇口" },
  { id: "venues", name: "场馆预约", summary: "体育馆与游泳馆的微信入口", icon: "grid", color: "honey", keywords: "体育馆 运动 游泳馆 微信" },
];

const learningLinks = [
  { id: "learning-zhidao", name: "知到（智慧树）", summary: "平台登录", href: "https://passport.zhihuishu.com/login" },
  { id: "learning-weizhen", name: "惟真学堂", summary: "石大专属 · 智慧树", href: "https://upcwzedu.zhihuishu.com/" },
  { id: "learning-yuketang", name: "雨课堂", summary: "网页版", href: "https://www.yuketang.cn/web" },
  { id: "learning-ketangpai", name: "课堂派", summary: "平台通用入口", href: "https://www.ketangpai.com/" },
  { id: "learning-cloud-classroom", name: "石大云课堂", summary: "学校登录", href: "https://learn.upc.edu.cn/meol/index.do" },
  { id: "learning-webvpn", name: "石大云课堂校外访问帮助", summary: "学校 WebVPN 说明", href: "https://appnic.upc.edu.cn/_s457/2026/0107/c25391a278495/page.psp" },
];

const powerLinks = [
  { name: "唐岛湾剩余电量", summary: "学校查询页 · HTTP", href: "http://dfcz.upc.edu.cn/ShiYou/Default.aspx", campus: "tdw" },
  { name: "古镇口电费", summary: "学校查询页", href: "https://gzkdf.upc.edu.cn/acmswx/index.aspx", campus: "gzk" },
];

const officialLinks = [
  { name: "学校官网", summary: "中国石油大学（华东）", href: "https://www.upc.edu.cn/" },
  { name: "学校课表", summary: "学校登录", href: "https://app.upc.edu.cn/site/weekschedule/index" },
  { name: "成绩与学分", summary: "学校成绩查询 · 学校登录", href: "https://app.upc.edu.cn/site/score/index", keywords: "绩点 均分 成绩单" },
  { name: "教务系统", summary: "学校登录", href: "https://jwxt.upc.edu.cn/jsxsd/framework/xsMainV.htmlx" },
  { name: "数字石大", summary: "学校门户", href: "https://i.upc.edu.cn/" },
  { name: "教务处官网", summary: "教务通知与办事指南", href: "https://jwc.upc.edu.cn/" },
  { name: "学院官网", summary: "教学科研单位", href: "https://www.upc.edu.cn/jxkydw.htm" },
  { name: "空闲教室", summary: "两校区 · 学校查询页", href: "https://app.upc.edu.cn/freeclass/wap/default/all" },
  { name: "图书馆座位", summary: "三个馆区的官方入口", href: "#library" },
  { name: "学校校历", summary: "教务处校历栏目", href: "https://jwc.upc.edu.cn/603/list.htm" },
  { name: "校园卡", summary: "校园卡服务 · 学校登录", href: "https://mb-xyk.upc.edu.cn/berserker-auth/cas/redirect/wislearn?targetUrl=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fplat%2F%3Fname%3DloginTransit", keywords: "余额 流水 充值 一卡通" },
  { name: "石光第二课堂", summary: "学校登录", href: "https://dekt.upc.edu.cn/", keywords: "活动 学时 学分" },
  { name: "e站通", summary: "学校官方服务大厅", href: "https://service.upc.edu.cn/" },
  ...powerLinks,
  { name: "体育馆与游泳馆预约", summary: "学校微信公众号", href: "#venues" },
];

const venues = [
  { name: "体育馆预约", menu: "场地预定 → 场地预定", info: "预约规则", href: "https://sports.upc.edu.cn/2023/0606/c7678a405213/page.htm" },
  { name: "游泳馆预约", menu: "场地预定 → 游泳馆预定", info: "游泳馆介绍", href: "https://news.upc.edu.cn/info/1432/114958.htm", campus: "tdw" },
];

const pinnableServices = [
  ...services,
  ...learningLinks.map(entry => ({ ...entry, icon: "book", color: "lavender" })),
];
const servicesById = new Map(pinnableServices.map(service => [service.id, service]));
const defaultPins = ["classroom", "library", "calendar", "timetable"];
let preferredCampus = "all";
let pinned = new Set(defaultPins);
let refreshServiceResults;
let refreshDirectory;
let directoryPending;
let directoryView;
let libraryCards;

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text) element.textContent = text;
  return element;
}

function icon(name, className = "icon") {
  const namespace = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(namespace, "svg");
  svg.setAttribute("class", className);
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(namespace, "use");
  use.setAttribute("href", `#i-${name}`);
  svg.append(use);
  return svg;
}

function link(label, href, className = "text-link") {
  const anchor = node("a", className, label);
  anchor.href = href;
  if (href.startsWith("https://") || href.startsWith("http://")) {
    anchor.target = "_blank";
    anchor.rel = "noopener noreferrer";
  }
  return anchor;
}

function campusOrder(items) {
  if (preferredCampus === "all") return items;
  return [
    ...items.filter(item => item.campus === preferredCampus),
    ...items.filter(item => item.campus !== preferredCampus),
  ];
}

export function getPreferredCampus() {
  return preferredCampus;
}

function readPreferences() {
  try {
    const campus = localStorage.getItem(campusKey);
    if (Object.hasOwn(campusLabels, campus)) preferredCampus = campus;
    const savedPins = JSON.parse(localStorage.getItem(pinsKey));
    if (Array.isArray(savedPins)) {
      pinned = new Set(savedPins.filter(id => servicesById.has(id)));
    }
  } catch {
    // The default and in-memory preferences still work without local storage.
  }
}

function updatePinButton(button) {
  const service = servicesById.get(button.dataset.campusPin);
  const isPinned = pinned.has(service.id);
  button.setAttribute("aria-pressed", String(isPinned));
  button.setAttribute("aria-label", `固定${service.name}到首页`);
  button.textContent = isPinned ? "已固定" : "固定";
}

function pinButton(service, status) {
  const button = node("button", "text-button campus-pin");
  button.type = "button";
  button.dataset.campusPin = service.id;
  updatePinButton(button);
  button.addEventListener("click", () => {
    if (pinned.has(service.id)) pinned.delete(service.id);
    else pinned.add(service.id);
    const action = pinned.has(service.id) ? "已固定到首页" : "已从首页移除";
    try {
      localStorage.setItem(pinsKey, JSON.stringify([...pinned]));
      status.textContent = service.name + action;
    } catch {
      status.textContent = `${service.name}${action}，仅在本次打开时生效`;
    }
    for (const peer of document.querySelectorAll("[data-campus-pin]")) {
      if (peer.dataset.campusPin === service.id) updatePinButton(peer);
    }
    renderHome();
  });
  return button;
}

function renderHome() {
  const root = document.querySelector('[data-service-list="common"]');
  if (!root) return;
  const cards = Array.from(pinned, id => {
    const service = servicesById.get(id);
    const anchor = link("", service.href ?? `#${id}`, "service-card");
    const tile = node("span", `tile-icon ${service.color}`);
    tile.append(icon(service.icon));
    anchor.append(
      icon(anchor.target ? "external" : "arrow", "icon service-arrow"),
      tile,
      node("span", "service-title", service.name),
      node("span", "service-origin", service.summary),
    );
    return anchor;
  });
  root.replaceChildren(...cards);
  if (cards.length === 0) root.append(node("p", "campus-empty", "还没有固定服务。可在服务页添加。"));
}

function serviceRow(entry, status) {
  const row = node("li", "campus-link-row");
  const anchor = link("", entry.href ?? `#${entry.id}`, "campus-link");
  const label = node("span", "campus-link-label");
  label.append(node("strong", "", entry.name), node("span", "campus-link-summary", entry.summary));
  anchor.append(label, icon(anchor.target ? "external" : "arrow"));
  row.append(anchor);
  if (entry.id) row.append(pinButton(entry, status));
  return row;
}

function listLinks(entries, status) {
  const list = node("ul", "campus-links");
  for (const entry of entries) list.append(serviceRow(entry, status));
  return list;
}

function normalize(text) {
  return text.normalize("NFKC").toLowerCase().replace(/[\s\p{P}]+/gu, "");
}

function searchTerms(query) {
  return query.trim().split(/\s+/u).map(normalize).filter(Boolean);
}

function matches(fields, terms) {
  const normalized = fields.filter(Boolean).map(normalize);
  return terms.every(term => normalized.some(field => field.includes(term)));
}

function searchableField(id, label, placeholder) {
  const field = node("div", "field campus-search");
  const fieldLabel = node("label", "", label);
  fieldLabel.htmlFor = id;
  const input = node("input");
  input.id = id;
  input.type = "search";
  input.placeholder = placeholder;
  input.autocomplete = "off";
  field.append(fieldLabel, input);
  return { field, input };
}

function renderServices() {
  const root = document.querySelector("#campus-services-app");
  if (!root) return;
  const { field, input } = searchableField("campus-service-search", "搜索服务", "服务名称、平台或办事关键词");
  const announcements = node("p", "campus-status");
  announcements.setAttribute("role", "status");
  const results = node("div");
  root.replaceChildren(field, announcements, results);

  refreshServiceResults = () => {
    const terms = searchTerms(input.value);
    const links = [...officialLinks, ...learningLinks].filter(entry => entry.href?.startsWith("http"));
    const filteredServices = services.filter(entry => matches([entry.name, entry.summary, entry.keywords], terms));
    const linkedResults = terms.length === 0
      ? []
      : campusOrder(links.filter(entry => matches([entry.name, entry.summary, entry.keywords], terms)));
    const entries = [...filteredServices, ...linkedResults];
    results.replaceChildren(entries.length > 0
      ? listLinks(entries, announcements)
      : node("p", "campus-empty", "没有找到匹配的服务"));
    announcements.textContent = terms.length > 0 ? `找到 ${entries.length} 个入口` : "";
  };
  input.addEventListener("input", refreshServiceResults);
  refreshServiceResults();
}

function renderLearning() {
  const root = document.querySelector("#campus-learning-app");
  if (!root) return;
  const status = node("p", "campus-status");
  status.setAttribute("role", "status");
  root.replaceChildren(listLinks(learningLinks, status), status);
}

function renderOfficial() {
  document.querySelector("#campus-official-app")?.replaceChildren(listLinks(campusOrder(officialLinks)));
}

function renderPower() {
  const root = document.querySelector("#campus-power-app");
  if (!root) return;
  root.replaceChildren(
    node("p", "campus-note", "请在学校页面查询。唐岛湾入口使用 HTTP，能否打开由当前网络与浏览器决定。"),
    listLinks(campusOrder(powerLinks)),
  );
}

function renderVenues() {
  const root = document.querySelector("#campus-venues-app");
  if (!root) return;
  const account = "中国石油大学体育场馆";
  const introduction = node("div", "detail-card campus-panel");
  const copy = node("button", "button button-secondary", "复制公众号名");
  copy.type = "button";
  const status = node("p", "campus-status");
  status.setAttribute("role", "status");
  copy.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(account);
      status.textContent = "已复制公众号名，请打开微信搜索。";
    } catch {
      status.textContent = "复制未完成，请在微信手动搜索公众号名。";
    }
  });
  introduction.append(
    node("h2", "", account),
    node("p", "", "在微信搜索官方公众号，再按下方菜单进入。场次、余位和预约结果以微信页面为准。"),
    copy,
    status,
  );
  const cards = campusOrder(venues).map(venue => {
    const article = node("article", "detail-card campus-panel");
    article.append(node("h2", "", venue.name), node("p", "", venue.menu), link(venue.info, venue.href));
    return article;
  });
  root.replaceChildren(introduction, ...cards);
}

function orderLibrary() {
  const root = document.querySelector('[data-route="library"]');
  const cards = [...(root?.querySelectorAll("[data-campus]") ?? [])];
  const parent = cards[0]?.parentElement;
  if (!parent || !cards.every(card => card.parentElement === parent)) return;

  libraryCards ??= cards.map(card => ({ card, campus: card.dataset.campus }));
  const sorted = campusOrder(libraryCards);
  if (sorted.every(({ card }, index) => card === cards[index])) return;
  for (const { card } of sorted) parent.append(card);
}

function directoryFields(office) {
  return [
    office.name, office.category, office.duties, office.region, office.address, office.email,
    campusLabels[office.campus], ...(office.consultations ?? []),
    ...(office.phones ?? []).flatMap(phone => [phone.number, phone.label]),
  ];
}

function officeCard(office) {
  const article = node("article", "campus-office");
  article.append(node("h3", "", office.name));
  const location = [campusLabels[office.campus], office.region, office.address].filter(Boolean);
  if (location.length) article.append(node("p", "campus-link-summary", location.join(" · ")));
  if (office.duties) article.append(node("p", "", office.duties));
  if (office.consultations?.length) article.append(node("p", "campus-link-summary", office.consultations.join("、")));

  const actions = node("div", "campus-office-actions");
  for (const phone of office.phones ?? []) {
    const number = phone.number.replace(/[^\d+]/g, "");
    if (number) actions.append(link(phone.label ? `${phone.label} ${phone.number}` : phone.number, `tel:${number}`));
  }
  if (office.email && /^[^\s@:/?#]+@[^\s@:/?#]+$/.test(office.email)) {
    actions.append(link(office.email, `mailto:${office.email}`));
  }
  if (office.mapTarget?.placeId) actions.append(link("在校园地图查看", `#map?place=${encodeURIComponent(office.mapTarget.placeId)}`));
  if (actions.childElementCount) article.append(actions);

  const sources = (office.sources ?? []).filter(url => url.startsWith("https://"));
  if (sources.length) {
    const references = node("div", "campus-office-sources");
    if (office.checkedOn) references.append(node("span", "", `核对于 ${office.checkedOn}`));
    sources.forEach((source, index) => {
      references.append(link(sources.length > 1 ? `公开来源 ${index + 1}` : "公开来源", source));
    });
    article.append(references);
  }
  return article;
}

function createDirectory(root, catalog) {
  const { field, input } = searchableField("campus-directory-search", "搜索通讯录", "单位、办事关键词或电话号码");
  const campusField = node("div", "field campus-directory-campus");
  const campusLabel = node("label", "", "校区");
  campusLabel.htmlFor = "campus-directory-filter";
  const filter = node("select");
  filter.id = campusLabel.htmlFor;
  for (const [id, label] of Object.entries(campusLabels)) {
    const option = node("option", "", label);
    option.value = id;
    filter.append(option);
  }
  filter.value = preferredCampus;
  campusField.append(campusLabel, filter);
  const controls = node("div", "campus-directory-controls");
  controls.append(field, campusField);
  const status = node("p", "campus-status");
  status.setAttribute("role", "status");
  const results = node("div");
  root.replaceChildren(controls, status, results);

  const refresh = () => {
    const terms = searchTerms(input.value);
    const campus = filter.value;
    const groups = { college: [], department: [] };
    let officesCount = 0;
    for (const unit of catalog.units) {
      const unitFields = [unit.name, unit.introduction, ...(unit.aliases ?? [])];
      const unitMatches = matches(unitFields, terms);
      const offices = catalog.offices.filter(office => office.unitId === unit.id
        && (campus === "all" || office.campus === campus)
        && (unitMatches || matches([...unitFields, ...directoryFields(office)], terms)));
      if (!offices.length) continue;
      officesCount += offices.length;
      const section = node("details", "campus-directory-unit");
      section.open = !unitMatches;
      const summary = node("summary");
      summary.append(node("strong", "", unit.name), node("span", "", `${offices.length} 个窗口`));
      section.append(summary);
      if (unit.introduction) section.append(node("p", "campus-unit-intro", unit.introduction));
      const officeList = node("div", "campus-offices");
      for (const office of offices) officeList.append(officeCard(office));
      section.append(officeList);
      groups[unit.college ? "college" : "department"].push(section);
    }
    const sections = [];
    for (const [title, entries] of [["学院", groups.college], ["校级部门", groups.department]]) {
      if (!entries.length) continue;
      const section = node("section", "campus-directory-group");
      section.append(node("h2", "", title), ...entries);
      sections.push(section);
    }
    const unitCount = groups.college.length + groups.department.length;
    status.textContent = unitCount ? `${unitCount} 个单位 · ${officesCount} 个窗口` : "没有找到匹配的通讯录条目";
    results.replaceChildren(...sections);
  };
  input.addEventListener("input", refresh);
  filter.addEventListener("change", refresh);
  refreshDirectory = () => {
    filter.value = preferredCampus;
    refresh();
  };
  refresh();
}

async function loadDirectory(root) {
  const loading = node("p", "campus-status", "正在读取校园通讯录…");
  loading.setAttribute("role", "status");
  root.replaceChildren(loading);
  try {
    const url = new URL("./data/campus_directory.json", import.meta.url);
    const response = await fetch(url, { credentials: "omit", redirect: "error" });
    if (!response.ok) throw new Error("directory unavailable");
    const catalog = await response.json();
    if (!Array.isArray(catalog.units) || !Array.isArray(catalog.offices)) throw new Error("directory format");
    createDirectory(root, catalog);
    directoryView = root;
  } catch {
    const error = node("p", "campus-status", "暂时无法读取校园通讯录，请联网后重试。");
    error.setAttribute("role", "status");
    const retry = node("button", "text-button", "重试");
    retry.type = "button";
    retry.addEventListener("click", showDirectory);
    root.replaceChildren(error, retry);
  }
}

function showDirectory() {
  const root = document.querySelector("#campus-directory-app");
  if (!root || root === directoryView || directoryPending) return;
  directoryPending = loadDirectory(root).finally(() => { directoryPending = undefined; });
}

export function initializeCampusServices() {
  readPreferences();
  renderHome();
  renderServices();
  const select = document.querySelector("#preferred-campus");
  const status = document.querySelector("#campus-preference-status");
  if (!select || !status) return;

  select.value = preferredCampus;
  select.addEventListener("change", () => {
    if (!Object.hasOwn(campusLabels, select.value)) return;
    preferredCampus = select.value;
    try {
      localStorage.setItem(campusKey, preferredCampus);
      status.textContent = `已设置为${campusLabels[preferredCampus]}`;
    } catch {
      status.textContent = "常用校区仅在本次打开时生效";
    }
    refreshServiceResults?.();
    refreshDirectory?.();
    document.dispatchEvent(new CustomEvent("campus-preference-change", { detail: preferredCampus }));
  });
}

export function showCampusServices(route) {
  if (route === "directory") showDirectory();
  if (route === "learning") renderLearning();
  if (route === "official") renderOfficial();
  if (route === "library") orderLibrary();
  if (route === "power") renderPower();
  if (route === "venues") renderVenues();
}
