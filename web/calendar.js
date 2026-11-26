const calendar = {
  academicYear: "2026–2027",
  publishedOn: "2026-05-08",
  articleUrl: "https://jwc.upc.edu.cn/2026/0508/c603a490075/page.htm",
  indexUrl: "https://jwc.upc.edu.cn/603/list.htm",
  semesters: [
    {
      name: "第一学期",
      imageUrl: "https://jwc.upc.edu.cn/_upload/article/images/d2/55/315a3cf7465299d5d0e552f9b517/f4f20582-4ba5-4cf8-97ec-d02bc1f31813.jpg",
    },
    {
      name: "第二学期",
      imageUrl: "https://jwc.upc.edu.cn/_upload/article/images/d2/55/315a3cf7465299d5d0e552f9b517/c628162a-3cb3-4d0a-b2c4-df4106d1c3ea.jpg",
    },
  ],
};

function calendarImage(semester, showExpanded) {
  const figure = document.createElement("figure");
  figure.className = "calendar-semester";
  figure.innerHTML = `
    <figcaption><h3></h3><button class="text-button calendar-expand" type="button" disabled>放大</button></figcaption>
    <img referrerpolicy="no-referrer" hidden>
    <div class="calendar-feedback">
      <p role="status">正在加载学校校历图片…</p>
      <button class="text-button calendar-reload-image" type="button" hidden>重试</button>
    </div>`;
  const image = figure.querySelector("img");
  const title = figure.querySelector("h3");
  const expand = figure.querySelector(".calendar-expand");
  const feedback = figure.querySelector(".calendar-feedback");
  const status = feedback.querySelector("p");
  const retry = feedback.querySelector("button");
  title.textContent = semester.name;
  image.alt = `${calendar.academicYear} 学年${semester.name}校历`;
  expand.setAttribute("aria-label", `放大${semester.name}校历`);

  function load() {
    image.hidden = true;
    expand.disabled = true;
    feedback.hidden = false;
    retry.hidden = true;
    status.textContent = "正在加载学校校历图片…";
    image.removeAttribute("src");
    image.src = semester.imageUrl;
  }

  image.addEventListener("load", () => {
    feedback.hidden = true;
    image.hidden = false;
    expand.disabled = false;
  });
  image.addEventListener("error", () => {
    status.textContent = navigator.onLine
      ? "学校校历图片加载失败，可重试或查看教务处原文。"
      : "当前离线，学校校历图片需联网加载。";
    retry.hidden = false;
  });
  retry.addEventListener("click", load);
  expand.addEventListener("click", () => showExpanded(semester, image.alt));
  load();
  return figure;
}

export function showCampusCalendar() {
  const root = document.querySelector("#campus-calendar-app");
  if (!root || root.childElementCount) return;

  root.innerHTML = `
    <div class="detail-card calendar-heading">
      <h2 class="calendar-year"></h2>
      <p>教务处发布于 <time></time></p>
      <div class="calendar-links">
        <a class="text-link calendar-article" target="_blank" rel="noopener noreferrer">教务处原文</a>
        <a class="text-link calendar-index" target="_blank" rel="noopener noreferrer">历年校历</a>
      </div>
    </div>
    <div class="calendar-semesters"></div>
    <dialog class="calendar-dialog" aria-labelledby="calendar-dialog-title">
      <div class="calendar-dialog-heading">
        <h2 id="calendar-dialog-title"></h2>
        <form method="dialog"><button class="text-button" type="submit">关闭</button></form>
      </div>
      <div class="calendar-zoom-view" tabindex="0" aria-label="滚动查看校历原图">
        <img alt="" referrerpolicy="no-referrer">
        <p role="status" hidden>校历原图暂时无法加载，请关闭后查看教务处原文。</p>
      </div>
    </dialog>`;
  root.querySelector(".calendar-year").textContent = `${calendar.academicYear} 学年`;
  const publication = root.querySelector("time");
  publication.dateTime = calendar.publishedOn;
  publication.textContent = calendar.publishedOn;
  root.querySelector(".calendar-article").href = calendar.articleUrl;
  root.querySelector(".calendar-index").href = calendar.indexUrl;

  const dialog = root.querySelector("dialog");
  const dialogTitle = dialog.querySelector("h2");
  const viewport = dialog.querySelector(".calendar-zoom-view");
  const enlarged = viewport.querySelector("img");
  const zoomError = viewport.querySelector("p");
  enlarged.addEventListener("load", () => {
    enlarged.hidden = false;
    zoomError.hidden = true;
  });
  enlarged.addEventListener("error", () => {
    enlarged.hidden = true;
    zoomError.hidden = false;
  });

  function showExpanded(semester, alt) {
    dialogTitle.textContent = semester.name;
    zoomError.hidden = true;
    enlarged.hidden = false;
    enlarged.alt = alt;
    enlarged.src = semester.imageUrl;
    dialog.showModal();
    viewport.scrollTo(0, 0);
  }

  root.querySelector(".calendar-semesters").append(
    ...calendar.semesters.map(semester => calendarImage(semester, showExpanded)),
  );
}
