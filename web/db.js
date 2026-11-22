let database;

function openDatabase() {
  if (database) return database;
  database = new Promise((resolve, reject) => {
    const request = indexedDB.open("upc-pocket", 2);
    request.onupgradeneeded = event => {
      const db = request.result;
      if (event.oldVersion < 1) {
        db.createObjectStore("todos", { keyPath: "id", autoIncrement: true });
        db.createObjectStore("focusState");
        db.createObjectStore("focusHistory", { keyPath: "sessionId" });
      }
      if (event.oldVersion < 2) db.createObjectStore("timetableSemesters", { keyPath: "key" });
    };
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("Database upgrade blocked"));
    request.onsuccess = () => {
      const db = request.result;
      if (database !== pending) {
        db.close();
        return;
      }
      db.onversionchange = () => {
        db.close();
        database = undefined;
      };
      db.onclose = () => { database = undefined; };
      resolve(db);
    };
  }).catch(error => {
    database = undefined;
    throw error;
  });
  const pending = database;
  return pending;
}

// Resolve only once the browser confirms the whole transaction committed.
export async function transact(stores, mode, queueRequests) {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(stores, mode);
    let result;
    transaction.oncomplete = () => resolve(result);
    transaction.onabort = () => reject(transaction.error ?? new Error("Transaction aborted"));
    try {
      queueRequests(transaction, value => { result = value; });
    } catch (error) {
      transaction.abort();
      reject(error);
    }
  });
}
