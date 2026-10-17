// A guest's decks are stored in this browser per site address. The same host reached by a different address shows none of them.

export interface DeviceDeck {
  id: string;
  text: string;
  format: string;
}

const DB = 'forge';
const STORE = 'decks';

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE, { keyPath: 'id' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

// Storage can be missing or blocked, for example in a private window. The decks are then not saved and the fallback is returned.
async function run<T>(mode: IDBTransactionMode, act: (store: IDBObjectStore) => IDBRequest, fallback: T): Promise<T> {
  try {
    const db = await open();
    return await new Promise<T>((resolve, reject) => {
      const request = act(db.transaction(STORE, mode).objectStore(STORE));
      request.onsuccess = () => resolve(request.result as T);
      request.onerror = () => reject(request.error);
    });
  } catch {
    return fallback;
  }
}

export function listDeviceDecks(): Promise<DeviceDeck[]> {
  return run<DeviceDeck[]>('readonly', store => store.getAll(), []);
}

export async function putDeviceDeck(deck: DeviceDeck): Promise<void> {
  await run('readwrite', store => store.put(deck), undefined);
}

export async function deleteDeviceDeck(id: string): Promise<void> {
  await run('readwrite', store => store.delete(id), undefined);
}
