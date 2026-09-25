// A guest's decks, kept in this browser rather than on the host's disk. The host sends each deck as .dck text after
// every change, and is sent the whole list once per connection. Browser storage belongs to one address, so a host
// reached by another address or port finds none of them; Copy as text in the editor is the backup.

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

// Storage can be missing or refused (a private window, blocked site data); the decks are then simply not kept
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
