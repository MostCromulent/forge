// The importer's text while it is not yet imported, so closing the dialog by mistake loses nothing.

const DRAFT_KEY = 'forge.importDraft';

export function loadDraft(): string {
  try {
    return localStorage.getItem(DRAFT_KEY) ?? '';
  } catch {
    return '';
  }
}

export function saveDraft(text: string): void {
  try {
    if (text) localStorage.setItem(DRAFT_KEY, text);
    else localStorage.removeItem(DRAFT_KEY);
  } catch {
    // Storage can be unavailable; the draft is then not kept
  }
}
