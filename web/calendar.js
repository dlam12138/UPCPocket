function schoolUrl(value, pathPattern) {
  if (typeof value !== "string") throw new Error("calendar source");
  const url = new URL(value);
  if (url.origin !== "https://jwc.upc.edu.cn" || url.username || url.password
      || url.search || url.hash || !pathPattern.test(url.pathname)) {
    throw new Error("calendar source");
  }
  return url.href;
}

function validateCalendar(payload) {
  if (!/^20\d\d–20\d\d$/.test(payload?.academicYear) || payload?.semesters?.length !== 2) {
    throw new Error("calendar format");
  }
  const articleUrl = schoolUrl(payload.articleUrl, /^\/20\d{2}\/\d{4}\/c603a\d+\/page\.htm$/);
  const images = payload.semesters.map(semester =>
    schoolUrl(semester.imageUrl, /^\/_upload\/article\/images\/(?:[\w-]+\/)*[\w-]+\.(?:jpe?g|png)$/i)
  );
  if (images[0] === images[1]) throw new Error("calendar images");
  const publishedOn = /^20\d\d-\d\d-\d\d$/.test(payload.publishedOn) ? payload.publishedOn : "";
  return { academicYear: payload.academicYear, articleUrl, publishedOn, images };
}

function semesterCard(label, imageUrl, showExpanded, retry) {
  const figure = document.createElement("figure");
  figure.className = "calendar-semester";
  figure.innerHTML = `
    <figcaption><h2>${label}</h2><button class="text-button calendar-expand" type="button" disabled>放大</button></figcaption>
    <img alt="${label}校历" hidden>
    <div class="calendar-feedback"><p role="status">正在加载校历图片…</p>
      <button class="text-button calendar-reload-image" type="button" hidden>重试</button></div>`;
  const image = figure.querySelector("img");
  const expand = figure.querySelector(".calendar-expand");
  const feedback = figure.querySelector(".calendar-feedback");
  const retryButton = figure.querySelector(".calendar-reload-image");
  image.addEventListener("load", () => {
    feedback.hidden = true;
    expand.disabled = false;
    image.hidden = false;
  });
  image.addEventListener("error", () => {
    feedback.querySelector("p").textContent = "校历图片加载失败";
    retryButton.hidden = false;
  });
  retryButton.addEventListener("click", retry);
  expand.addEventListener("click", () => showExpanded(label, imageUrl));
  image.referrerPolicy = "no-referrer";
  image.src = imageUrl;
  return figure;
}

function renderCalendar(root, calendar, retry) {
  root.innerHTML = `
    <div class="calendar-masthead">
      <div><h2 class="calendar-year"></h2><p class="calendar-published"></p></div>
      <a class="text-link calendar-source" target="_blank" rel="noopener noreferrer">教务处原文 ↗</a>
    </div>
    <div class="calendar-semesters"></div>
    <dialog class="calendar-zoom" aria-labelledby="calendar-zoom-title">
      <form method="dialog"><h2 id="calendar-zoom-title"></h2><button class="text-button">关闭</button></form>
      <div class="calendar-zoom-view" tabindex="0" aria-label="校历，滚动查看完整图片">
        <img alt="" referrerpolicy="no-referrer">
      </div>
    </dialog>`;
  root.querySelector(".calendar-year").textContent = `${calendar.academicYear} 学年`;
  root.querySelector(".calendar-published").textContent = calendar.publishedOn
    ? `教务处发布于 ${calendar.publishedOn}` : "教务处公开校历";
  root.querySelector(".calendar-source").href = calendar.articleUrl;
  const dialog = root.querySelector("dialog");
  const zoom = dialog.querySelector(".calendar-zoom-view");
  const title = dialog.querySelector("h2");
  const enlarged = dialog.querySelector("img");

  function showExpanded(label, imageUrl) {
    title.textContent = `${label}校历`;
    enlarged.src = imageUrl;
    dialog.showModal();
    zoom.scrollTo(0, 0);
  }

  const list = root.querySelector(".calendar-semesters");
  calendar.images.forEach((url, index) => {
    const label = index === 0 ? "第一学期" : "第二学期";
    list.append(semesterCard(label, url, showExpanded, retry));
  });
}

export function initializeCalendar() {
  const root = document.querySelector("#calendar-app");
  if (!root || root.dataset.initialized) return;
  root.dataset.initialized = "true";

  async function loadCalendar() {
    root.innerHTML = '<div class="calendar-feedback"><p role="status">正在读取学校校历…</p></div>';
    try {
      const response = await fetch("./api/calendar", {
        credentials: "omit",
        cache: "no-store",
        redirect: "error",
      });
      if (!response.ok) throw new Error("calendar unavailable");
      renderCalendar(root, validateCalendar(await response.json()), loadCalendar);
    } catch {
      root.innerHTML = '<div class="calendar-feedback"><p role="status">暂时无法读取学校校历</p><button class="text-button" type="button">重试</button></div>';
      root.querySelector("button").addEventListener("click", loadCalendar);
    }
  }

  loadCalendar();
}
