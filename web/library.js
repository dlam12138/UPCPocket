const choiceFields = ["building", "date", "room", "seat", "start", "end"];
const choiceLabels = ["馆区", "日期", "房间", "座位", "开始时间", "结束时间"];
const branchTitles = { tangdaowan: "唐岛湾图书馆", east: "古镇口东区图书馆", qiushi: "求是图书馆" };
const unsettled = new Set(["sending", "submitted", "unknown"]);
const resultMessages = {
  sending: "正在提交预约…",
  submitted: "预约已提交，正在核对学校记录…",
  unknown: "上一笔预约结果待核对，可刷新预约记录。",
  confirmed: "已在学校记录中核对预约成功。",
  accepted: "学校已受理预约，请到官方页面查看记录。",
  rejected: "本次预约未成功，请重新选座。",
  challenge: "学校要求验证，本次预约未完成。请在馆区官方页面处理。",
};
const cancellationMessages = {
  cancelled: "已在当前记录中核对预约取消。",
  absent: "当前记录已找不到这笔预约，未能确认取消结果。",
  rejected: "学校未能取消这笔预约，请查看最新记录。",
  unknown: "取消结果待核对，请刷新当前预约记录。",
};

class LibrarySessionChanged extends Error {}

function libraryPath(branch, path) {
  return `./api/library/${branch === "tangdaowan" ? "" : `${branch}/`}${path}`;
}

function resultMessage(outcome, bound) {
  if (outcome === "unknown" && !bound?.recordsAvailable) return "预约结果待核对，请到官方页面查看记录。";
  return resultMessages[outcome] ?? "";
}

async function query(path, { session, branch = session?.branch, params, payload, logout = false, signal } = {}) {
  const url = new URL(libraryPath(branch, path), document.baseURI);
  if (params) url.search = new URLSearchParams(params);
  const headers = { Accept: "application/json" };
  if (session) headers["X-Library-Session"] = session.revision;
  const write = !!payload || logout;
  if (write) headers["X-CSRF-Token"] = session.csrf;
  if (payload) headers["Content-Type"] = "application/json";

  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener("abort", abort, { once: true });
  const deadline = setTimeout(abort, 20000);
  try {
    const response = await fetch(url, {
      method: write ? "POST" : "GET",
      body: payload ? JSON.stringify(payload) : undefined,
      headers,
      mode: "same-origin",
      credentials: "same-origin",
      cache: "no-store",
      referrerPolicy: "same-origin",
      redirect: "error",
      signal: controller.signal,
    });
    if (session && (response.status === 401 || response.status === 409)) {
      throw new LibrarySessionChanged();
    }
    if (!response.ok || response.headers.get("Content-Type")?.split(";")[0].trim() !== "application/json") {
      throw new Error("library response");
    }
    return await response.json();
  } finally {
    clearTimeout(deadline);
    signal?.removeEventListener("abort", abort);
  }
}

function isText(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function normalizeLabel(value) {
  return value.trim().normalize("NFC");
}

function matchesRecordedChoice(item, attempt) {
  return choiceFields.every(field =>
    (field === "start" && ["now", "-1"].includes(attempt.start)) ||
    normalizeLabel(item[field]) === attempt.labels[field]);
}

function isDate(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().startsWith(value);
}

function validateItems(values, valid) {
  if (!Array.isArray(values) || !values.every(valid) ||
      new Set(values.map(value => value.id)).size !== values.length) {
    throw new Error("library items");
  }
  return values;
}

function optionsFrom(values) {
  return validateItems(values, entry => isText(entry?.id) && isText(entry.label));
}

function roomDetails(room) {
  const details = [];
  if (Number.isInteger(room.floor) && room.floor > 0) details.push(`${room.floor} 楼`);
  if (Number.isInteger(room.freeSeats) && room.freeSeats >= 0) details.push(`余 ${room.freeSeats} 座`);
  if (Number.isInteger(room.totalSeats) && room.totalSeats >= 0) details.push(`共 ${room.totalSeats} 座`);
  return details.join(" · ");
}

function seatDetails(seat) {
  if (seat.selectable === false) return ["disabled", "暂停使用"];
  switch (seat.status) {
    case "FREE":
    case "YESSELECT": return ["free", "空闲"];
    case "AWAY": return ["away", "暂离"];
    case "CHECK_IN":
    case "IN_USE": return ["occupied", "使用中"];
    case "RESERVE":
    case "BOOKED":
    case "FULL": return ["occupied", "已占用"];
    default: return ["unknown", ""];
  }
}

function layoutAvailable(layout, seats) {
  const { rows, cols } = layout ?? {};
  if (![rows, cols].every(size => Number.isInteger(size) && size > 0 && size <= 120) || !seats.length) return false;
  const cells = new Set();
  for (const { row, col } of seats) {
    const cell = `${row}/${col}`;
    if (![row, col].every(Number.isInteger) || row < 1 || row > rows || col < 1 || col > cols || cells.has(cell)) {
      return false;
    }
    cells.add(cell);
  }
  return true;
}

function coordinateScale(layout, seats) {
  const { width, height } = layout ?? {};
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 || !seats.length) return 0;
  let smallest = Infinity;
  for (const seat of seats) {
    const { x, y, rotate } = seat;
    if (![x, y, seat.width, seat.height, rotate].every(Number.isFinite) || x < 0 || y < 0 ||
      seat.width <= 0 || seat.height <= 0 || x + seat.width > width || y + seat.height > height) return 0;
    smallest = Math.min(smallest, seat.width, seat.height);
  }
  const scale = Math.min(Math.max(1, 44 / smallest), 6144 / Math.max(width, height));
  return smallest * scale >= 28 ? scale : 0;
}

export function initializeLibrary() {
  const form = document.querySelector("#library-booking-form");
  const gate = document.querySelector("#library-session-status");
  const bookingTitle = document.querySelector("#library-booking-title");
  const branchLinks = [...document.querySelectorAll("[data-library-branch]")];
  const officialLinks = [...document.querySelectorAll("[data-library-official]")];
  const retrySession = document.querySelector("#library-session-retry");
  const signIn = document.querySelector("#library-sign-in");
  const logout = document.querySelector("#library-logout");
  const recordsLink = document.querySelector("#library-records-link");
  const recordsTitle = document.querySelector("#library-reservations-title");
  const catalogStatus = document.querySelector("#library-catalog-status");
  const catalogFeedback = document.querySelector("#library-catalog-feedback");
  const retryCatalog = document.querySelector("#library-catalog-retry");
  const bookStatus = document.querySelector("#library-booking-status");
  const review = form.querySelector("[type=submit]");
  const reviewArea = document.querySelector("#library-review");
  const reviewSelection = document.querySelector("#library-review-selection");
  const reviewReason = document.querySelector("#library-review-reason");
  const checkResult = document.querySelector("#library-check-result");
  const selectedSeat = document.querySelector("#library-selected-seat");
  const selectedTime = document.querySelector("#library-selected-time");
  const roomList = document.querySelector("#library-room-list");
  const seatList = document.querySelector("#library-seat-list");
  const seatScroll = document.querySelector("#library-seat-scroll");
  const roomStage = document.querySelector("#library-rooms");
  const currentRoom = document.querySelector("#library-current-room");
  const changeRoom = document.querySelector("#library-change-room");
  const changeSeat = document.querySelector("#library-change-seat");
  const seatStage = document.querySelector("#library-seats");
  const timeStage = document.querySelector("#library-times");
  const seatViews = seatStage.querySelector(".library-seat-views");
  const viewButtons = [...seatViews.querySelectorAll("button")];
  const filters = form.querySelector(".library-fields");
  const confirmation = document.querySelector("#library-confirmation");
  const confirmationDetails = document.querySelector("#library-confirmation-details");
  const send = document.querySelector("#library-confirm-booking");
  const cancelConfirmation = document.querySelector("#library-cancel-confirmation");
  const cancelDetails = document.querySelector("#library-cancel-details");
  const confirmCancel = document.querySelector("#library-confirm-cancel");
  const records = document.querySelector("#library-reservations");
  const recordsStatus = document.querySelector("#library-reservations-status");
  const cancellationStatus = document.querySelector("#library-cancellation-status");
  const recordsList = document.querySelector("#library-reservation-list");
  const refreshRecords = document.querySelector("#library-refresh-reservations");
  const fields = choiceFields.map(name => form.elements.namedItem(name));

  let branch = null;
  let session;
  let sessionRequest;
  let catalogRequest;
  let recordsRequest;
  let failedLevel;
  let draft;
  let attempt;
  let cancelDraft;
  let sendingCancellation;
  let cancellationRevision;
  const cancellations = new Map();
  let pending = false;
  let recordsState = "unread";
  let recordIds = new Set();
  let currentRecords = new Map();
  let seatEntries = [];
  let seatLayout;
  let seatView = "list";
  let showRooms = true;
  let loggingOut = false;

  function isCurrent(bound) {
    return branch === bound.branch && session === bound;
  }

  function isCancelling() {
    return sendingCancellation?.branch === branch && !!branch;
  }

  function showGate(message, { login = false, retry = false } = {}) {
    gate.textContent = message;
    signIn.hidden = !login;
    retrySession.hidden = !retry;
    form.hidden = logout.hidden = !session;
    records.hidden = recordsLink.hidden = !session?.recordsAvailable;
  }

  function jumpTo(heading) {
    heading.focus({ preventScroll: true });
    heading.scrollIntoView({ block: "start" });
  }

  function makeChoices(level, list, entries, map = false, scale = 0) {
    const field = fields[level];
    const nodes = entries.map(entry => {
      const row = document.createElement("li");
      if (scale) {
        row.style.left = `${entry.x * scale}px`;
        row.style.top = `${entry.y * scale}px`;
        row.style.width = `${entry.width * scale}px`;
        row.style.height = `${entry.height * scale}px`;
        row.style.transform = `rotate(${entry.rotate}deg)`;
      } else if (map) {
        row.style.gridRow = entry.row;
        row.style.gridColumn = entry.col;
      }
      const button = document.createElement("button");
      button.type = "button";
      button.className = "library-choice";
      button.dataset.choice = entry.id;
      const [state, details] = level === 2 ? ["room", roomDetails(entry)] : seatDetails(entry);
      button.dataset.state = state;
      const title = document.createElement("strong");
      title.textContent = entry.label;
      button.append(title);
      if (details) {
        const detail = document.createElement("span");
        detail.textContent = details;
        button.append(detail);
      }
      button.addEventListener("click", () => {
        if (level === 2) showRooms = false;
        if (field.value === entry.id) {
          updateControls();
        } else {
          field.value = entry.id;
          field.dispatchEvent(new Event("change", { bubbles: true }));
        }
        jumpTo((level === 2 ? seatStage : timeStage).querySelector("h3"));
      });
      row.append(button);
      return row;
    });
    list.replaceChildren(...nodes);
  }

  function renderSeats() {
    const scale = coordinateScale(seatLayout, seatEntries);
    const available = !!scale || layoutAvailable(seatLayout, seatEntries);
    seatViews.hidden = !available;
    const map = seatView === "map" && available;
    seatList.dataset.view = map ? (scale ? "coordinates" : "map") : "list";
    seatScroll.dataset.view = map ? "map" : "list";
    if (map && scale) {
      seatList.style.setProperty("--library-map-width", `${seatLayout.width * scale}px`);
      seatList.style.setProperty("--library-map-height", `${seatLayout.height * scale}px`);
    } else if (map) {
      seatList.style.setProperty("--library-map-cols", seatLayout.cols);
      seatList.style.setProperty("--library-map-rows", seatLayout.rows);
    }
    for (const button of viewButtons) button.setAttribute("aria-pressed", String(button.dataset.seatView === seatScroll.dataset.view));
    makeChoices(3, seatList, seatEntries, map, map ? scale : 0);
    seatScroll.scrollLeft = 0;
    seatScroll.scrollTop = 0;
  }

  function setOptions(level, entries = [], layout) {
    const options = entries.map(entry => {
      const option = new Option(entry.label, entry.id);
      option.disabled = entry.selectable === false;
      return option;
    });
    fields[level].replaceChildren(new Option(`请选择${choiceLabels[level]}`, ""), ...options);
    if (level === 2) makeChoices(2, roomList, entries);
    if (level === 3) {
      seatEntries = entries;
      seatLayout = layout;
      renderSeats();
    }
  }

  function formSelection() {
    return Object.fromEntries(fields.map(field => [field.name, field.value]));
  }

  function bookingBlockReason(choice) {
    if (loggingOut) return "正在退出图书馆…";
    if (isCancelling()) return "正在取消预约…";
    if (attempt?.outcome === "sending") return resultMessages.sending;
    if (!session) return "请先登录学校账号。";
    if (!session.bookingAvailable) return "预约请在馆区官方页面办理。";
    if (session.recordsAvailable) {
      if (recordsRequest || recordsState === "unread") return "正在读取预约记录…";
      if (recordsState === "failed") return "预约记录读取失败，请刷新。";
    }
    if (catalogRequest) return "正在读取可选时段…";
    if (!retryCatalog.hidden) return "可选时段读取失败，请重新查询。";
    if (Object.values(choice).some(value => !value)) return "请选择开始和结束时间。";
    return "";
  }

  function updateControls() {
    const sending = loggingOut || isCancelling() || attempt?.outcome === "sending";
    for (const field of fields) field.disabled = sending || field.options.length < 2;
    const choice = formSelection();
    roomStage.hidden = !choice.building || !choice.date;
    seatStage.hidden = !choice.room;
    timeStage.hidden = !choice.seat;
    const waitingForResult = session?.recordsAvailable &&
      (pending || attempt?.outcome === "submitted" || attempt?.outcome === "unknown");
    const needsRecordRefresh = waitingForResult || (session?.recordsAvailable && recordsState === "failed");
    reviewArea.hidden = !choice.seat && !needsRecordRefresh;
    reviewSelection.hidden = !choice.seat;
    logout.disabled = sending;
    const roomCollapsed = !!choice.room && !showRooms;
    changeRoom.hidden = currentRoom.hidden = !roomCollapsed;
    roomList.hidden = roomCollapsed;
    currentRoom.textContent = roomCollapsed ? fields[2].selectedOptions[0].textContent : "";

    for (const [list, level] of [[roomList, 2], [seatList, 3]]) {
      for (const button of list.querySelectorAll("button")) {
        button.disabled = sending || button.dataset.state === "disabled";
        button.setAttribute("aria-pressed", String(button.dataset.choice === fields[level].value));
      }
    }
    const label = (index) => fields[index].selectedOptions[0]?.textContent ?? "";
    selectedSeat.textContent = choice.seat ? `${label(2)} · ${label(3)}` : "";
    selectedTime.textContent = choice.start && choice.end ? `${choice.date} · ${label(4)}–${label(5)}` : "";

    reviewReason.textContent = bookingBlockReason(choice);
    review.disabled = !!reviewReason.textContent;
    refreshRecords.disabled = sending || !!recordsRequest;
    checkResult.hidden = !needsRecordRefresh;
    checkResult.disabled = !session || refreshRecords.disabled;
    checkResult.textContent = waitingForResult ? "核对结果" : "刷新预约记录";
    for (const button of recordsList.querySelectorAll("button")) button.disabled = sending || !!recordsRequest;
  }

  function clearRecords() {
    recordIds = new Set();
    currentRecords.clear();
    recordsList.replaceChildren();
    recordsList.hidden = true;
  }

  function clearView() {
    sessionRequest?.abort();
    catalogRequest?.abort();
    recordsRequest?.abort();
    sessionRequest = catalogRequest = recordsRequest = null;
    session = draft = null;
    cancelDraft = null;
    pending = false;
    recordsState = "unread";
    showRooms = true;
    if (confirmation.open) confirmation.close();
    if (cancelConfirmation.open) cancelConfirmation.close();
    confirmationDetails.textContent = "";
    cancelDetails.textContent = "";
    fields.forEach((_, level) => setOptions(level));
    filters.after(catalogFeedback);
    retryCatalog.hidden = true;
    catalogStatus.textContent = bookStatus.textContent = recordsStatus.textContent = cancellationStatus.textContent = "";
    clearRecords();
    updateControls();
  }

  function expireSession(bound) {
    if (!isCurrent(bound)) return;
    clearView();
    showGate("学校会话已失效，请重新登录。", { login: true, retry: true });
    if (unsettled.has(attempt?.outcome)) {
      bookStatus.textContent = "预约结果待核对。请重新登录后查看预约记录。";
    }
    if (bound.branch === "tangdaowan" && cancellations.size) {
      bookStatus.textContent = "取消结果待核对。请重新登录后查看预约记录。";
    }
  }

  async function checkSession() {
    const requestedBranch = branch;
    clearView();
    showGate("正在检查站内预约…");
    const request = sessionRequest = new AbortController();
    try {
      const data = await query("session", { branch: requestedBranch, signal: request.signal });
      if (branch !== requestedBranch || sessionRequest !== request) return;
      if (data?.state === "unavailable") {
        showGate("本机站内预约未启用。", { retry: true });
        return;
      }
      if (data?.state === "sign_in") {
        showGate("登录学校账号后，即可查询座位与预约。", { login: true, retry: true });
        if (unsettled.has(attempt?.outcome)) {
          bookStatus.textContent = "上一笔预约结果待核对，登录后可查看预约记录。";
        }
        return;
      }
      if (data?.state !== "ready" || !isText(data.revision) || !isText(data.csrf)) {
        throw new Error("library session");
      }
      session = {
        branch: requestedBranch,
        revision: data.revision,
        csrf: data.csrf,
        bookingAvailable: data.bookingAvailable !== false,
        recordsAvailable: data.recordsAvailable !== false,
      };
      if (attempt?.revision !== session.revision) attempt = null;
      if (cancellationRevision !== session.revision) {
        cancellations.clear();
        cancellationRevision = session.revision;
      }
      showGate(session.bookingAvailable ? "" : "可在此查询座位。预约请在馆区官方页面办理。");
      bookStatus.textContent = resultMessage(attempt?.outcome, session);
      loadCatalog(0);
      loadRecords();
    } catch {
      if (branch !== requestedBranch || sessionRequest !== request) return;
      showGate(navigator.onLine ? "暂时无法连接站内预约。" : "联网后可使用图书馆预约。", { retry: true });
    } finally {
      if (sessionRequest === request) sessionRequest = null;
    }
  }

  async function loadCatalog(level) {
    catalogRequest?.abort();
    catalogRequest = null;
    retryCatalog.hidden = true;
    catalogStatus.textContent = "";
    (level < 2 ? filters : level === 2 ? roomStage : level === 3 ? seatStage : timeStage).after(catalogFeedback);
    const bound = session;
    const dependencies = fields.slice(0, level);
    if (!bound || dependencies.some(field => !field.value)) {
      updateControls();
      return;
    }
    const request = catalogRequest = new AbortController();
    const params = Object.fromEntries(dependencies.map(field => [field.name, field.value]));
    const label = level ? choiceLabels[level] : "可预约馆区和日期";
    catalogStatus.textContent = `正在读取${label}…`;
    updateControls();
    try {
      const data = await query("catalog", { session: bound, params, signal: request.signal });
      if (!isCurrent(bound) || catalogRequest !== request) return;
      if (level === 0) {
        const buildings = optionsFrom(data?.buildings);
        if (!Array.isArray(data?.dates) || !data.dates.every(isDate) ||
            new Set(data.dates).size !== data.dates.length) throw new Error("library dates");
        setOptions(0, buildings);
        setOptions(1, data.dates.map(date => ({ id: date, label: date })));
        catalogStatus.textContent = buildings.length && data.dates.length ? "" : "学校暂无可预约的馆区或日期。";
      } else {
        const choices = optionsFrom(data?.[`${choiceFields[level]}s`]);
        setOptions(level, choices, data.layout);
        catalogStatus.textContent = choices.length ? "" : `学校暂无可预约的${label}。`;
      }
    } catch (error) {
      if (!isCurrent(bound) || catalogRequest !== request) return;
      if (error instanceof LibrarySessionChanged) {
        expireSession(bound);
        return;
      }
      catalogStatus.textContent = `读取${label}失败。`;
      failedLevel = level;
      retryCatalog.hidden = false;
    } finally {
      if (catalogRequest === request) {
        catalogRequest = null;
        updateControls();
      }
    }
  }

  function renderRecords(items) {
    const nodes = items.map(item => {
      const row = document.createElement("li");
      const details = document.createElement("div");
      const title = document.createElement("strong");
      const time = document.createElement("span");
      title.textContent = `${item.building} · ${item.room} · ${item.seat}`;
      time.textContent = `${item.date} · ${item.start}–${item.end}`;
      details.append(title, time);
      row.append(details);
      if (item.cancellable === true || item.cancelPending === true || cancellations.has(item.id)) {
        const button = document.createElement("button");
        button.className = "text-button";
        button.type = "button";
        const operation = cancellations.get(item.id);
        if (item.cancelPending === true || operation) {
          button.textContent = "核对取消结果";
          button.addEventListener("click", () => loadRecords(operation));
        } else {
          button.classList.add("library-cancel-action");
          button.textContent = "取消预约";
          button.setAttribute("aria-label", `取消 ${item.room} ${item.seat} ${time.textContent} 的预约`);
          button.addEventListener("click", () => openCancel(item));
        }
        row.append(button);
      }
      return row;
    });
    recordsList.replaceChildren(...nodes);
    recordsList.hidden = nodes.length === 0;
  }

  function updateCancellation(data, observed) {
    if (!observed || cancellations.get(observed.reservationId) !== observed) {
      if (data.cancellationPending === true || cancellations.size) {
        cancellationStatus.textContent = cancellationMessages.unknown;
      } else if (cancellationStatus.textContent === cancellationMessages.unknown) {
        cancellationStatus.textContent = "";
      }
      return;
    }
    const result = data.cancellation?.id === observed.id ? data.cancellation.status : observed.outcome;
    if (result === "rejected" || (["cancelled", "absent"].includes(result) && !currentRecords.has(observed.reservationId))) {
      cancellations.delete(observed.reservationId);
      cancellationStatus.textContent = cancellationMessages[result];
      return;
    }
    observed.outcome = "unknown";
    cancellationStatus.textContent = cancellationMessages.unknown;
  }

  async function loadRecords(cancel = cancellations.values().next().value) {
    if (!session?.recordsAvailable || recordsRequest || isCancelling() || attempt?.outcome === "sending") return;
    const bound = session;
    const observed = attempt;
    const request = recordsRequest = new AbortController();
    const params = {};
    if (observed) params.attempt = observed.id;
    if (cancel) params.cancelAttempt = cancel.id;
    clearRecords();
    recordsStatus.textContent = "正在查询本人预约…";
    updateControls();
    try {
      const data = await query("reservations", { session: bound, params, signal: request.signal });
      if (!isCurrent(bound) || recordsRequest !== request) return;
      if (typeof data?.pending !== "boolean") throw new Error("library pending");
      const items = validateItems(data.records, item => isText(item?.id) && isDate(item.date) &&
        [item.building, item.room, item.seat, item.start, item.end].every(isText));
      pending = data.pending;
      recordsState = "ready";
      if (observed && attempt === observed && unsettled.has(observed.outcome)) {
        const result = data.attempt;
        if (result?.id === observed.id &&
            (result.status === "rejected" || result.status === "challenge")) {
          observed.outcome = result.status;
        } else if (result?.id === observed.id && result.status === "confirmed" && isText(result.recordId) &&
            !observed.priorRecordIds.has(result.recordId) &&
            items.some(item => item.id === result.recordId && matchesRecordedChoice(item, observed))) {
          observed.outcome = "confirmed";
        } else {
          observed.outcome = "unknown";
        }
        bookStatus.textContent = resultMessages[observed.outcome];
      }
      recordIds = new Set(items.map(item => item.id));
      currentRecords = new Map(items.map(item => [item.id, item]));
      updateCancellation(data, cancel);
      renderRecords(items);
      recordsStatus.textContent = pending && !unsettled.has(attempt?.outcome)
        ? "有预约结果待核对，可刷新预约记录。"
        : items.length ? "" : "当前没有进行中的预约。";
    } catch (error) {
      if (!isCurrent(bound) || recordsRequest !== request) return;
      if (error instanceof LibrarySessionChanged) {
        expireSession(bound);
        return;
      }
      if (observed?.outcome === "submitted") {
        observed.outcome = "unknown";
        bookStatus.textContent = resultMessages.unknown;
      }
      if (cancel) cancellationStatus.textContent = cancellationMessages.unknown;
      recordsState = "failed";
      recordsStatus.textContent = "未能读取学校记录，请刷新。";
    } finally {
      if (recordsRequest === request) {
        recordsRequest = null;
        updateControls();
      }
    }
  }

  function mayCancel(item) {
    return session?.recordsAvailable && recordsState === "ready" && !recordsRequest &&
      !loggingOut && !isCancelling() && attempt?.outcome !== "sending" &&
      currentRecords.get(item.id) === item && item.cancellable === true && item.cancelPending !== true &&
      !cancellations.has(item.id);
  }

  function openCancel(item) {
    if (!mayCancel(item) || cancelConfirmation.open) return;
    cancelDraft = { session, item };
    cancelDetails.textContent = `${item.building} · ${item.room} · ${item.seat}，${item.date} ${item.start}–${item.end}`;
    cancelConfirmation.showModal();
  }

  confirmCancel.addEventListener("click", async () => {
    if (!cancelConfirmation.open || !cancelDraft) return;
    const { session: bound, item } = cancelDraft;
    cancelDraft = null;
    cancelConfirmation.close();
    cancelDetails.textContent = "";
    if (!isCurrent(bound) || !mayCancel(item)) return;
    const id = globalThis.crypto?.randomUUID?.();
    if (!id) {
      cancellationStatus.textContent = "请使用安全连接取消预约。";
      return;
    }
    const operation = { id, branch: bound.branch, reservationId: item.id, outcome: "sending" };
    sendingCancellation = operation;
    cancellations.set(item.id, operation);
    cancellationStatus.textContent = "正在取消预约…";
    updateControls();
    try {
      const data = await query("cancel", { session: bound, payload: { id, reservationId: item.id } });
      operation.outcome = ["cancelled", "rejected", "absent"].includes(data?.status) ? data.status : "unknown";
    } catch (error) {
      operation.outcome = "unknown";
      if (error instanceof LibrarySessionChanged) expireSession(bound);
    } finally {
      if (sendingCancellation === operation) sendingCancellation = null;
      if (branch === bound.branch && session?.revision === bound.revision) {
        cancellationStatus.textContent = operation.outcome === "cancelled"
          ? "正在核对取消结果…" : cancellationMessages[operation.outcome];
        updateControls();
        loadRecords(operation);
      }
    }
  });

  form.addEventListener("change", event => {
    const changed = fields.indexOf(event.target);
    if (changed < 0) return;
    if (changed < 2) showRooms = true;
    if (!unsettled.has(attempt?.outcome)) bookStatus.textContent = "";
    const next = Math.max(2, changed + 1);
    fields.slice(next).forEach((_, offset) => setOptions(next + offset));
    if (next < fields.length) loadCatalog(next);
    else updateControls();
  });
  form.addEventListener("submit", event => {
    event.preventDefault();
    if (review.disabled || confirmation.open) return;
    const labels = Object.fromEntries(fields.map(field => [field.name,
      normalizeLabel(field.selectedOptions[0].textContent)]));
    draft = { session, selection: formSelection(), labels };
    const { building, date, room, seat, start, end } = labels;
    confirmationDetails.textContent = `${building} · ${room} · ${seat}，${date} ${start}–${end}`;
    confirmation.showModal();
  });

  send.addEventListener("click", async () => {
    if (!confirmation.open || !draft) return;
    const { session: bound, selection, labels } = draft;
    draft = null;
    confirmation.close();
    confirmationDetails.textContent = "";
    const current = formSelection();
    if (review.disabled || !isCurrent(bound) || choiceFields.some(field => current[field] !== selection[field])) return;
    const id = globalThis.crypto?.randomUUID?.();
    if (!id) {
      bookStatus.textContent = "请使用安全连接打开站内预约。";
      return;
    }
    const sent = attempt = {
      id,
      revision: bound.revision,
      branch: bound.branch,
      labels,
      start: selection.start,
      priorRecordIds: recordIds,
      outcome: "sending",
    };
    bookStatus.textContent = resultMessages.sending;
    updateControls();
    try {
      const data = await query("book", { session: bound, payload: { id, ...selection } });
      if (["challenge", "rejected"].includes(data?.status)) sent.outcome = data.status;
      else if (data?.status === "submitted" && bound.recordsAvailable) sent.outcome = "submitted";
      else if (data?.status === "accepted" && !bound.recordsAvailable) sent.outcome = "accepted";
      else sent.outcome = "unknown";
    } catch (error) {
      sent.outcome = "unknown";
      if (error instanceof LibrarySessionChanged) expireSession(bound);
    }
    if (branch === bound.branch && attempt === sent && session?.revision === sent.revision) {
      bookStatus.textContent = resultMessage(sent.outcome, bound);
      updateControls();
      loadRecords();
    }
  });
  retrySession.addEventListener("click", checkSession);
  for (const button of viewButtons) button.addEventListener("click", () => {
    seatView = button.dataset.seatView;
    renderSeats();
    updateControls();
  });
  recordsLink.addEventListener("click", () => {
    jumpTo(recordsTitle);
  });
  changeRoom.addEventListener("click", () => {
    showRooms = true;
    updateControls();
    jumpTo(roomStage.querySelector("h3"));
  });
  changeSeat.addEventListener("click", () => jumpTo(seatStage.querySelector("h3")));
  logout.addEventListener("click", async () => {
    const bound = session;
    if (!bound || logout.disabled) return;
    loggingOut = true;
    updateControls();
    try {
      const result = await query("logout", { session: bound, logout: true });
      if (result?.state !== "sign_in") throw new Error("logout response");
      if (isCurrent(bound)) checkSession();
    } catch (error) {
      if (!isCurrent(bound)) return;
      if (error instanceof LibrarySessionChanged) expireSession(bound);
      else bookStatus.textContent = "暂时无法确认退出，请重新检查。";
    } finally {
      loggingOut = false;
      updateControls();
    }
  });
  retryCatalog.addEventListener("click", () => loadCatalog(failedLevel));
  refreshRecords.addEventListener("click", () => loadRecords());
  checkResult.addEventListener("click", () => loadRecords());
  window.addEventListener("pageshow", event => {
    if (event.persisted && branch) checkSession();
  });

  return function setLibraryActive(nextBranch) {
    if (branch === nextBranch) return;
    branch = nextBranch;
    if (branch) {
      if (attempt?.branch !== branch) attempt = null;
      bookingTitle.textContent = branchTitles[branch];
      signIn.href = libraryPath(branch, "login");
      for (const link of branchLinks) {
        if (link.dataset.libraryBranch === branch) link.setAttribute("aria-current", "page");
        else link.removeAttribute("aria-current");
      }
      for (const link of officialLinks) link.hidden = link.dataset.libraryOfficial !== branch;
      checkSession();
    } else {
      if (!unsettled.has(attempt?.outcome)) attempt = null;
      clearView();
    }
  };
}
