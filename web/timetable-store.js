import { transact } from "./db.js";

const STORE = "timetableSemesters";

export async function listTimetableSemesters() {
  const semesters = await transact(STORE, "readonly", (transaction, setResult) => {
    const request = transaction.objectStore(STORE).getAll();
    request.onsuccess = () => setResult(request.result);
  });
  return semesters.sort((first, second) => second.savedAt - first.savedAt);
}

export function saveTimetableSemester(semester) {
  const record = { ...semester, savedAt: Date.now() };
  return transact(STORE, "readwrite", (transaction, setResult) => {
    transaction.objectStore(STORE).put(record);
    setResult(record);
  });
}

export function deleteTimetableSemester(key) {
  return transact(STORE, "readwrite", transaction => {
    transaction.objectStore(STORE).delete(key);
  });
}
