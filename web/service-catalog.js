const PAGE_SIZE = 20;

class CatalogRequestError extends Error {
  constructor(status) {
    super("service catalog");
    this.status = status;
  }
}

async function readPublicApi(path) {
  const response = await fetch(path, {
    credentials: "omit",
    cache: "no-store",
    redirect: "error",
  });
  if (!response.ok) throw new CatalogRequestError(response.status);
  return response.json();
}

function formatSearchTerm(text) {
  return text.normalize("NFKC").toUpperCase().toLowerCase();
}

function guideSection(label, content) {
  const section = document.createElement("section");
  const heading = document.createElement("h3");
  heading.textContent = label;
  const text = document.createElement("p");
  text.textContent = content;
  section.append(heading, text);
  return section;
}

export function initializeServiceCatalog() {
  const root = document.querySelector("#service-catalog-app");
  if (!root || root.dataset.initialized) return;
  root.dataset.initialized = "true";
  root.innerHTML = `
    <div class="service-catalog-search">
      <label for="service-catalog-query">搜索事项</label>
      <input id="service-catalog-query" type="search" maxlength="40" placeholder="事项名称或办理部门" disabled>
    </div>
    <div class="service-catalog-feedback"><p role="status"></p><button class="text-button catalog-retry" type="button" hidden>重试</button></div>
    <ul class="service-catalog-results" aria-label="公开事项"></ul>
    <div class="service-catalog-pages" hidden>
      <button class="text-button catalog-previous" type="button">上一页</button>
      <span class="catalog-page-label"></span>
      <button class="text-button catalog-next" type="button">下一页</button>
    </div>
    <dialog class="service-guide" aria-labelledby="service-guide-title">
      <form method="dialog"><h2 id="service-guide-title"></h2><button class="text-button">关闭</button></form>
      <div class="service-guide-content"></div>
    </dialog>`;

  const search = root.querySelector("input");
  const status = root.querySelector(".service-catalog-feedback p");
  const retry = root.querySelector(".catalog-retry");
  const results = root.querySelector("ul");
  const pages = root.querySelector(".service-catalog-pages");
  const pageLabel = root.querySelector(".catalog-page-label");
  const previous = root.querySelector(".catalog-previous");
  const next = root.querySelector(".catalog-next");
  const dialog = root.querySelector("dialog");
  const guideTitle = root.querySelector("#service-guide-title");
  const guideContent = root.querySelector(".service-guide-content");
  let items = [];
  let currentPage = 1;
  let guideVersion = 0;

  async function loadGuide(item) {
    const requestVersion = ++guideVersion;
    guideContent.innerHTML = "<p role=\"status\">正在读取办事指南…</p>";
    const query = new URLSearchParams({ id: item.id });
    try {
      const guide = await readPublicApi(`./api/service-catalog/guide?${query}`);
      if (requestVersion !== guideVersion || !dialog.open) return;
      if (typeof guide.name !== "string" || typeof guide.department !== "string"
          || !Array.isArray(guide.sections) || !guide.sections.every(section =>
            typeof section?.label === "string" && typeof section.text === "string")) {
        throw new Error("service guide format");
      }
      guideTitle.textContent = guide.name;
      guideContent.replaceChildren();
      if (guide.department) {
        guideContent.append(guideSection("办理部门", guide.department));
      }
      if (item.ambiguous || guide.name !== item.name) {
        guideContent.append(guideSection("来源提示", "学校目录中该事项编号对应的名称可能有差异，请核对指南标题。"));
      }
      for (const section of guide.sections) guideContent.append(guideSection(section.label, section.text));
      if (!guide.sections.length) {
        guideContent.append(guideSection("办事指南", "学校指南未提供公开说明。"));
      }
      if (guide.truncated) {
        guideContent.append(guideSection("显示范围", "部分内容过长，已省略。"));
      }
    } catch (error) {
      if (requestVersion !== guideVersion || !dialog.open) return;
      const unavailable = error.status === 403 || error.status === 404;
      const message = document.createElement("p");
      message.setAttribute("role", "status");
      message.textContent = unavailable ? "学校未提供此事项的公开指南。" : "暂时无法读取办事指南。";
      guideContent.replaceChildren(message);
      if (!unavailable) {
        const retryGuide = document.createElement("button");
        retryGuide.className = "text-button";
        retryGuide.type = "button";
        retryGuide.textContent = "重试";
        retryGuide.addEventListener("click", () => loadGuide(item));
        guideContent.append(retryGuide);
      }
    }
  }

  function openGuide(item) {
    guideTitle.textContent = item.name;
    dialog.showModal();
    loadGuide(item);
  }

  function renderResults() {
    const terms = formatSearchTerm(search.value).split(/\p{White_Space}+/u).filter(Boolean);
    const found = items.filter(item => {
      const fullText = formatSearchTerm(`${item.name} ${item.department}`);
      return terms.every(term => fullText.includes(term));
    });
    const pageCount = Math.max(1, Math.ceil(found.length / PAGE_SIZE));
    currentPage = Math.min(currentPage, pageCount);
    const offset = (currentPage - 1) * PAGE_SIZE;
    const rows = found.slice(offset, offset + PAGE_SIZE).map(item => {
      const entry = document.createElement("li");
      const target = document.createElement("button");
      target.type = "button";
      const label = document.createElement("strong");
      label.textContent = item.name;
      target.append(label);
      if (item.department) {
        const detail = document.createElement("span");
        detail.textContent = item.department;
        target.append(detail);
      }
      target.addEventListener("click", () => openGuide(item));
      entry.append(target);
      return entry;
    });
    results.replaceChildren(...rows);
    status.textContent = found.length ? `${found.length} 项公开事项`
      : terms.length ? "没有匹配的公开事项" : "学校公开目录为空";
    pages.hidden = pageCount <= 1;
    pageLabel.textContent = `${currentPage} / ${pageCount}`;
    previous.disabled = currentPage === 1;
    next.disabled = currentPage === pageCount;
  }

  async function loadDirectory() {
    status.textContent = "正在读取学校事项目录…";
    retry.hidden = true;
    try {
      const payload = await readPublicApi("./api/service-catalog");
      if (!Array.isArray(payload.items) || !payload.items.every(item =>
        typeof item?.id === "string" && /^[1-9][0-9]{0,18}$/.test(item.id)
        && typeof item.name === "string" && typeof item.department === "string")) {
        throw new Error("service catalog format");
      }
      items = payload.items;
      search.disabled = false;
      renderResults();
    } catch {
      status.textContent = "暂时无法读取学校事项目录";
      retry.hidden = false;
    }
  }

  search.addEventListener("input", () => {
    currentPage = 1;
    renderResults();
  });
  retry.addEventListener("click", loadDirectory);
  previous.addEventListener("click", () => {
    currentPage--;
    renderResults();
  });
  next.addEventListener("click", () => {
    currentPage++;
    renderResults();
  });
  loadDirectory();
}
