// A phone that locks while the others take their turns drops its connection, so the screen is kept on during a match

type WakeLock = { release(): Promise<void> };
let lock: WakeLock | null = null;
let locking = false;

export async function keepAwake(on: boolean): Promise<void> {
  const wake = (navigator as Navigator & { wakeLock?: { request(type: 'screen'): Promise<WakeLock> } }).wakeLock;
  if (!wake || locking || on === !!lock) return;
  locking = true;
  if (on && document.visibilityState === 'visible') {
    // Refused, as a browser saving power may, it is not asked for again until the page is next shown
    lock = await wake.request('screen').catch(() => ({ release: async () => {} }));
  } else if (!on && lock) {
    await lock.release().catch(() => {});
    lock = null;
  }
  locking = false;
}

/** The browser has given the lock up, as it does when the page is hidden, so it is asked for again when next wanted. */
export function forgetWakeLock(): void {
  lock = null;
}
