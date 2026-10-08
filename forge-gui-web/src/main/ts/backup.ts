// What this browser keeps for its player, as one file to carry to another browser or to put back after its storage is cleared

import { listDeviceDecks, putDeviceDeck, type DeviceDeck } from './deck/devicedecks';
import { saveText } from './dom';

const APP = 'forge-web';
// Which browser this is to the server, and a list half typed into the importer, are not the player's settings
const LEFT_OUT = ['forge.clientId', 'forge.importDraft'];

export interface Backup {
  app: string;
  version: number;
  storage: Record<string, string>;
  decks: DeviceDeck[];
}

const kept = (key: string): boolean => key.startsWith('forge.') && !LEFT_OUT.includes(key);

/** The file's contents for what a browser holds: every value of Forge's own, and the decks kept in it. */
export function backupOf(storage: Record<string, string>, decks: DeviceDeck[]): Backup {
  return { app: APP, version: 1, storage: Object.fromEntries(Object.entries(storage).filter(([key]) => kept(key))), decks };
}

/** What a file would put back, or null when it is not one of these files. Anything in it that is not Forge's own is dropped. */
export function readBackup(text: string): Backup | null {
  try {
    const file = JSON.parse(text) as Partial<Backup> | null;
    if (file?.app !== APP || typeof file.storage !== 'object' || file.storage === null) return null;
    const storage = Object.fromEntries(Object.entries(file.storage).filter(([key, value]) => kept(key) && typeof value === 'string'));
    const decks = (Array.isArray(file.decks) ? file.decks : [])
      .filter(d => typeof d?.id === 'string' && typeof d.text === 'string' && typeof d.format === 'string');
    return { app: APP, version: 1, storage, decks };
  } catch {
    return null;
  }
}

/** Hands the player the file. Storage the browser refuses to read exports as nothing kept. */
export async function exportBackup(): Promise<void> {
  const storage: Record<string, string> = {};
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key !== null) storage[key] = localStorage.getItem(key) ?? '';
    }
  } catch {
    // Blocked storage holds nothing to export
  }
  saveText(JSON.stringify(backupOf(storage, await listDeviceDecks()), null, 2), 'forge-web-settings.json', 'application/json');
}

/** Puts a file's contents back over what the browser holds and loads the page again to use them. False when the file is not one of these. */
export async function importBackup(text: string): Promise<boolean> {
  const backup = readBackup(text);
  if (!backup) return false;
  try {
    for (const [key, value] of Object.entries(backup.storage)) localStorage.setItem(key, value);
  } catch {
    return false;
  }
  for (const deck of backup.decks) await putDeviceDeck(deck);
  location.reload();
  return true;
}
