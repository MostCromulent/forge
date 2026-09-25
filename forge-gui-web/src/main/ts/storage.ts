// What this browser remembers between visits. localStorage throws when the browser blocks it (a private window,
// site data turned off), and then nothing is kept: every value simply starts from its default again next time.

export function stored(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/** Keeps a value, or forgets it when given null. */
export function store(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    // Nowhere to keep it; the value lasts until the page is reloaded
  }
}

/** A value kept as JSON, or the fallback when there is none or it no longer parses. */
export function storedJson<T>(key: string, fallback: T): T {
  const text = stored(key);
  if (text === null) return fallback;
  try {
    return JSON.parse(text) ?? fallback;
  } catch {
    return fallback;
  }
}

export function storeJson(key: string, value: unknown): void {
  store(key, JSON.stringify(value));
}
