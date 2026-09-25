// The importer's text while it is not yet imported, so closing the dialog by mistake loses nothing.

import { store, stored } from './storage';

const DRAFT_KEY = 'forge.importDraft';

export function loadDraft(): string {
  return stored(DRAFT_KEY) ?? '';
}

export function saveDraft(text: string): void {
  store(DRAFT_KEY, text || null);
}
