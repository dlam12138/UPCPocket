import { transact } from "./db.js";

const form = document.querySelector("#todo-form");
const titleInput = document.querySelector("#todo-name");
const dueInput = document.querySelector("#todo-due");
const addButton = document.querySelector("#add-todo");
const list = document.querySelector("#todo-list");
const readStatus = document.querySelector("#todo-read-status");
const writeStatus = document.querySelector("#todo-write-status");
const retryButton = document.querySelector("#retry-todos");
const dateFormat = new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" });

const dialog = document.createElement("dialog");
dialog.className = "settings-dialog";
dialog.setAttribute("aria-labelledby", "todo-edit-title");
dialog.innerHTML = `
  <form class="todo-edit-form">
    <div class="section-heading">
      <h2 id="todo-edit-title">编辑待办</h2>
      <button class="text-button" type="button" data-cancel>取消</button>
    </div>
    <label class="field">标题<input name="title" type="text" required></label>
    <label class="field">截止时间（可选）<input name="due" type="datetime-local"></label>
    <p class="operation-error" role="status"></p>
    <div class="todo-edit-actions">
      <button class="button" type="submit">保存</button>
      <button class="text-button todo-delete" type="button" data-delete>删除待办</button>
    </div>
  </form>
`;
document.body.append(dialog);

const editForm = dialog.querySelector("form");
const editTitle = editForm.elements.title;
const editDue = editForm.elements.due;
const editStatus = editForm.querySelector("[role=status]");
const saveButton = editForm.querySelector("button[type=submit]");
const deleteButton = editForm.querySelector("[data-delete]");
const cancelButton = editForm.querySelector("[data-cancel]");

const undoNotice = document.createElement("div");
undoNotice.className = "workspace-status";
undoNotice.hidden = true;
undoNotice.innerHTML = `<p role="status">已删除待办</p><button class="text-button" type="button">撤销删除</button>`;
list.before(undoNotice);
const undoButton = undoNotice.querySelector("button");

let busy = false;
let editing = null;
let deleted = null;

function orderTodos(left, right) {
  return Number(left.completed) - Number(right.completed)
    || (left.completed
      ? right.completedAt - left.completedAt
      : (left.dueAt ?? Infinity) - (right.dueAt ?? Infinity))
    || right.createdAt - left.createdAt
    || right.id - left.id;
}

function readAll(store, setResult) {
  const request = store.getAll();
  request.onsuccess = () => setResult(request.result);
}

function localDateTime(timestamp) {
  if (timestamp == null) return "";
  const date = new Date(timestamp);
  const pad = number => String(number).padStart(2, "0");
  return `${String(date.getFullYear()).padStart(4, "0")}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function readTodoFields(name, due) {
  const title = name.value.trim();
  if (!title) {
    name.setCustomValidity("请输入待办标题");
    name.reportValidity();
    name.focus();
    return null;
  }
  const dueAt = due.value ? new Date(due.value).getTime() : null;
  if (Number.isNaN(dueAt)) {
    due.setCustomValidity("请选择有效的截止时间");
    due.reportValidity();
    return null;
  }
  return { title, dueAt };
}

function sameTodo(saved, expected) {
  if (!saved || (saved.revision ?? 0) !== (expected.revision ?? 0)) return false;
  return ["title", "createdAt", "dueAt", "completed", "completedAt"].every(key => saved[key] === expected[key]);
}

function nextRevision(todo) {
  return (todo.revision ?? 0) + 1;
}

function changeSavedTodo(expected, mutation) {
  return transact("todos", "readwrite", (tx, setResult) => {
    const store = tx.objectStore("todos");
    const request = store.get(expected.id);
    request.onsuccess = () => {
      const saved = request.result;
      const changed = sameTodo(saved, expected);
      if (changed) mutation(store, saved);
      readAll(store, todos => setResult({ changed, todos }));
    };
  });
}

function openEditor(todo) {
  if (busy || dialog.open) return;
  editing = todo;
  editStatus.textContent = "";
  editTitle.setCustomValidity("");
  editDue.setCustomValidity("");
  editTitle.value = todo.title;
  editDue.value = localDateTime(todo.dueAt);
  saveButton.disabled = false;
  deleteButton.disabled = false;
  dialog.showModal();
  editTitle.focus();
}

function renderTodos(todos) {
  const focusedId = list.contains(document.activeElement) ? document.activeElement.id : null;
  const rows = todos.sort(orderTodos).map(todo => {
    const row = document.createElement("li");
    row.className = "todo-row";
    const label = document.createElement("label");
    label.className = "check-row";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.id = `task-${todo.id}`;
    checkbox.checked = todo.completed;
    checkbox.addEventListener("change", () => toggleTodo(todo, checkbox));
    const copy = document.createElement("span");
    const title = document.createElement("span");
    title.className = "record-title";
    title.textContent = todo.title;
    copy.append(title);
    if (todo.dueAt !== null) {
      const due = document.createElement("time");
      due.className = "record-date";
      due.dateTime = new Date(todo.dueAt).toISOString();
      due.textContent = `截止 ${dateFormat.format(todo.dueAt)}`;
      copy.append(due);
    }
    label.append(checkbox, copy);
    const edit = document.createElement("button");
    edit.type = "button";
    edit.className = "text-button";
    edit.id = `edit-task-${todo.id}`;
    edit.textContent = "编辑";
    edit.setAttribute("aria-label", `编辑 ${todo.title}`);
    edit.addEventListener("click", () => openEditor(todo));
    row.append(label, edit);
    return row;
  });
  list.replaceChildren(...rows);
  list.hidden = todos.length === 0;
  readStatus.textContent = todos.length === 0 ? "还没有待办" : "";
  retryButton.hidden = true;
  if (focusedId) document.getElementById(focusedId)?.focus({ preventScroll: true });
}

async function toggleTodo(todo, checkbox) {
  const complete = checkbox.checked;
  checkbox.checked = todo.completed;
  if (busy) return;
  busy = true;
  addButton.disabled = true;
  writeStatus.textContent = "";
  try {
    const { changed, todos } = await changeSavedTodo(todo, (store, saved) => {
      store.put({
        ...saved, completed: complete, completedAt: complete ? Date.now() : null, revision: nextRevision(saved),
      });
    });
    renderTodos(todos);
    if (!changed) writeStatus.textContent = "待办已更改，请按最新状态重试";
  } catch {
    writeStatus.textContent = "保存失败，请重试";
  } finally {
    busy = false;
    addButton.disabled = false;
  }
}

export async function refreshTodos() {
  if (busy) return;
  busy = true;
  addButton.disabled = true;
  if (list.hidden) readStatus.textContent = "正在读取待办…";
  retryButton.hidden = true;
  try {
    renderTodos(await transact("todos", "readonly", (tx, setResult) => {
      readAll(tx.objectStore("todos"), setResult);
    }));
  } catch {
    readStatus.textContent = "待办读取失败";
    retryButton.hidden = false;
  } finally {
    busy = false;
    addButton.disabled = false;
  }
}

retryButton.addEventListener("click", refreshTodos);
for (const input of [titleInput, dueInput, editTitle, editDue]) {
  input.addEventListener("input", () => input.setCustomValidity(""));
}

cancelButton.addEventListener("click", () => { if (!busy) dialog.close(); });
dialog.addEventListener("cancel", event => { if (busy) event.preventDefault(); });
dialog.addEventListener("close", () => { if (!dialog.open) editing = null; });
window.addEventListener("hashchange", () => {
  if (dialog.open && location.hash !== "#todo") dialog.close();
});

async function submitEditor(remove) {
  if (busy || !editing || saveButton.disabled) return;
  const todo = editing;
  const fields = remove ? null : readTodoFields(editTitle, editDue);
  if (!remove && !fields) return;
  if (fields && editDue.value === localDateTime(todo.dueAt)) fields.dueAt = todo.dueAt;

  busy = true;
  addButton.disabled = true;
  saveButton.disabled = true;
  deleteButton.disabled = true;
  cancelButton.disabled = true;
  editTitle.readOnly = true;
  editDue.readOnly = true;
  editStatus.textContent = "";
  writeStatus.textContent = "";
  let stale = false;
  try {
    const { changed, todos } = await changeSavedTodo(todo, (store, saved) => {
      if (remove) store.delete(saved.id);
      else store.put({ ...saved, ...fields, revision: nextRevision(saved) });
    });
    renderTodos(todos);
    if (!changed) {
      stale = true;
      editStatus.textContent = "这条待办已更改，请关闭后重新打开。";
      return;
    }
    if (remove) {
      deleted = todo;
      undoNotice.hidden = false;
    }
    if (dialog.open) {
      dialog.close();
      const nextFocus = remove ? undoButton : document.getElementById(`edit-task-${todo.id}`);
      nextFocus?.focus({ preventScroll: true });
    }
  } catch {
    editStatus.textContent = remove ? "删除失败，请重试。" : "保存失败，请重试。";
  } finally {
    busy = false;
    addButton.disabled = false;
    saveButton.disabled = stale;
    deleteButton.disabled = stale;
    cancelButton.disabled = false;
    editTitle.readOnly = false;
    editDue.readOnly = false;
  }
}

editForm.addEventListener("submit", event => {
  event.preventDefault();
  submitEditor(false);
});
deleteButton.addEventListener("click", () => submitEditor(true));

undoButton.addEventListener("click", async () => {
  if (busy || !deleted) return;
  const todo = deleted;
  busy = true;
  addButton.disabled = true;
  undoButton.disabled = true;
  writeStatus.textContent = "";
  try {
    const { restored, todos } = await transact("todos", "readwrite", (tx, setResult) => {
      const store = tx.objectStore("todos");
      const request = store.get(todo.id);
      request.onsuccess = () => {
        const restored = request.result === undefined;
        if (restored) store.add({ ...todo, revision: nextRevision(todo) });
        readAll(store, todos => setResult({ restored, todos }));
      };
    });
    deleted = null;
    undoNotice.hidden = true;
    renderTodos(todos);
    if (restored) document.getElementById(`edit-task-${todo.id}`)?.focus({ preventScroll: true });
    else writeStatus.textContent = "这条待办已有新状态，无法撤销。";
  } catch {
    writeStatus.textContent = "撤销失败，请重试。";
  } finally {
    busy = false;
    addButton.disabled = false;
    undoButton.disabled = false;
  }
});

form.addEventListener("submit", async event => {
  event.preventDefault();
  if (busy) return;
  const fields = readTodoFields(titleInput, dueInput);
  if (!fields) return;
  busy = true;
  addButton.disabled = true;
  titleInput.readOnly = true;
  dueInput.readOnly = true;
  writeStatus.textContent = "";
  try {
    const todos = await transact("todos", "readwrite", (tx, setResult) => {
      const store = tx.objectStore("todos");
      store.add({ ...fields, createdAt: Date.now(), completed: false, completedAt: null, revision: 1 });
      readAll(store, setResult);
    });
    form.reset();
    if (document.activeElement === titleInput || document.activeElement === dueInput) {
      document.activeElement.blur();
    }
    renderTodos(todos);
  } catch {
    writeStatus.textContent = "保存失败，请重试";
  } finally {
    busy = false;
    addButton.disabled = false;
    titleInput.readOnly = false;
    dueInput.readOnly = false;
  }
});
