// Starting points for a probe: a throwaway spec that looks at one thing in the real page. A probe gets a server of its
// own with dev mode on and a saved deck of the player's own, and each starting point returns with the page where the
// looking starts, so a probe holds only what it looks at:
//
//   import { board, probe } from './probe';
//   probe('haste', async p => {
//     await board(p, 'humanbattlefield=Raging Goblin|SummonSick;Mountain\naibattlefield=Grizzly Bears');
//     await p.snap('board');
//   });
//
// Name the file zz-<anything>.spec.ts (ignored by git) and run it with `npx playwright test zz-<anything>`. Screenshots
// land in forge-gui-web/target/probe/<title>/, and the page's errors are printed as they happen.

import { expect, test, type Browser, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { startServer, type Server } from './server';
import { chooseDeck, enterName, gameStarted, hostTable, inviteLink } from './steps';

export interface Probe {
  page: Page;
  browser: Browser;
  server: Server;
  /** Saves a screenshot of the page, or of another page such as a guest's, and prints where it went. */
  snap(name: string, on?: Page): Promise<void>;
}

/** The player's own deck every probe server starts with: sixty basics, legal in Constructed. */
export const PROBE_DECK = 'Probe deck';

export function probe(title: string, body: (p: Probe) => Promise<void>): void {
  test(title, async ({ page, browser }) => {
    test.setTimeout(600_000);
    const server = await startServer(undefined, {
      prefs: { DEV_MODE_ENABLED: 'true' },
      decks: { [PROBE_DECK]: `[metadata]\nName=${PROBE_DECK}\n[Main]\n30 Mountain\n30 Forest\n` },
    });
    const dir = `../target/probe/${title.replace(/[^\w-]+/g, '-')}`;
    mkdirSync(dir, { recursive: true });
    const listen = (p: Page, who: string) => {
      p.on('pageerror', e => console.log(`[${who} page error] ${e.message}`));
      // A card image the test machine has not downloaded is a 404 every time, and says nothing about the page
      p.on('console', m => {
        if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) console.log(`[${who} console] ${m.text()}`);
      });
    };
    listen(page, 'host');
    try {
      await body({
        page, browser, server,
        async snap(name, on = page) {
          const path = `${dir}/${name}.png`;
          await on.screenshot({ path });
          console.log(`[snap] forge-gui-web/target/probe/${dir.split('/').pop()}/${name}.png`);
        },
      });
    } finally {
      await server.stop();
    }
  });
}

/** A second browser joining the host's table by its invite link, under the name Bea. */
async function seatGuest(p: Probe): Promise<Page> {
  const guest = await (await p.browser.newContext({ viewport: p.page.viewportSize() ?? undefined })).newPage();
  guest.on('pageerror', e => console.log(`[guest page error] ${e.message}`));
  await guest.goto(await inviteLink(p.page, p.server.url));
  await enterName(guest, 'Bea');
  await expect(p.page.locator('#seats')).toContainText('Bea');
  return guest;
}

/** Match setup against the computer, with no decks chosen. */
export async function lobby(p: Probe): Promise<void> {
  await p.page.goto(p.server.url);
  await enterName(p.page, 'Alice');
  await hostTable(p.page, false);
}

/** Match setup with a guest seated; returns the guest's page. */
export async function lobbyWithGuest(p: Probe): Promise<Page> {
  await p.page.goto(p.server.url);
  await enterName(p.page, 'Alice');
  await hostTable(p.page, true);
  return seatGuest(p);
}

/** A networked match between the host and a guest, both past the goes-first screen; returns the guest's page. */
export async function matchWithGuest(p: Probe): Promise<Page> {
  const guest = await lobbyWithGuest(p);
  await chooseDeck(p.page, p.page.locator('.plate.mine'));
  await chooseDeck(guest, guest.locator('.plate.mine'));
  for (const page of [p.page, guest]) await page.click('.plate.mine .ready-toggle');
  await expect(p.page.locator('#play')).toBeEnabled();
  await p.page.click('#play');
  for (const page of [p.page, guest]) await gameStarted(page);
  return guest;
}

/** The deck finder open on your own seat, with the probe's own deck chosen and illegal decks shown too. */
export async function finderWithOwnDeck(p: Probe): Promise<void> {
  await lobby(p);
  await p.page.locator('.plate.mine .sleeve').click();
  await p.page.locator('.finder input[role="switch"]').first().check();
  await p.page.locator('.dk-hit', { hasText: PROBE_DECK }).click();
  await expect(p.page.locator('.dk-chosen-head')).toContainText(PROBE_DECK);
}

/** How a board's table is set up: the game from the Game menu (Constructed unless given), seats, and starting life. */
export interface Table {
  game?: string;
  players?: number;
  /** Each player's life unless the state says otherwise; 20, or 40 for Commander. */
  life?: number;
}

/**
 * A match against the computer with the board set up from a dev-mode game state, in the format the dev menu's
 * "Set up a game state" takes (lines of key=value). Players are human and ai in a two-player game, p0 (you) to p3
 * with more. activeplayer=human, activephase=MAIN1 and each player's life are added unless given, since a state
 * without a life total sets it to -1. Returns once the first card the state names is on the table, answering the coin
 * toss and mulligan on the way.
 */
export async function board(p: Probe, state: string, table: Table = {}): Promise<void> {
  const page = p.page;
  await lobby(p);
  if (table.game) {
    await page.locator('.popup-anchor .menu-button').first().click();
    await page.locator('.game-choice', { has: page.locator('.game-name', { hasText: new RegExp(`^${table.game}$`) }) }).click();
  }
  const players = table.players ?? 2;
  if (players !== 2) await page.locator('.count[aria-label="Players"] button', { hasText: String(players) }).click();
  const seats = page.locator('#seats .plate');
  await expect(seats).toHaveCount(players);
  for (let i = 0; i < players; i++) await chooseDeck(page, seats.nth(i));
  await page.click('#play');
  await gameStarted(page);
  await setState(p, state, table);
}

/**
 * Sets up a game state in the match already under way, with the same defaults as board: pass the table the match was
 * set up with. Placing is known by the first card the state names showing, so a state set up again over one that
 * already shows that card returns without waiting for it.
 */
export async function setState(p: Probe, state: string, table: Table = {}): Promise<void> {
  const page = p.page;
  const players = table.players ?? 2;
  const lines = state.trim().split('\n').map(l => l.trim()).filter(Boolean);
  const life = String(table.life ?? (table.game === 'Commander' ? 40 : 20));
  const defaults = [['activeplayer', 'human'], ['activephase', 'MAIN1'],
    ...Array.from({ length: players }, (_, i) => [players === 2 ? ['humanlife', 'ailife'][i] : `p${i}life`, life])];
  for (const [key, value] of defaults) {
    if (!lines.some(l => l.startsWith(`${key}=`))) lines.unshift(`${key}=${value}`);
  }
  const named = /^(?:human|ai|p\d)(?:battlefield|hand)=([^;|]+)/;
  const first = lines.map(l => named.exec(l)?.[1]).find(Boolean);
  const placed = first ? page.locator('#match .card:visible', { hasText: first }).first() : null;
  // The game takes a state only while a player has priority, which comes after the coin toss and the mulligan
  for (let tries = 0; ; tries++) {
    // A state does not clear the stack, so whatever the computer has on it is passed through first
    for (let i = 0; i < 6 && await page.locator('#stack').isVisible(); i++) {
      await page.keyboard.press('Space');
      await page.waitForTimeout(1000);
    }
    await page.click('#prompt .more');
    await page.getByRole('menuitem', { name: /Dev mode/ }).click();
    await page.getByRole('menuitem', { name: /Set up a game state/ }).click();
    await page.fill('.dev-state', lines.join('\n'));
    await page.getByRole('button', { name: 'Set up', exact: true }).click();
    if (!placed) {
      await page.waitForTimeout(3000);
      return;
    }
    try {
      await expect(placed).toBeVisible({ timeout: 8000 });
      // The board is sent after it is placed; answering a prompt before it lands answers the old one
      await page.waitForTimeout(1000);
      return;
    } catch (e) {
      if (tries >= 8) throw e;
      await page.keyboard.press('Space');
      await page.waitForTimeout(1500);
    }
  }
}

/**
 * An online Draft or Sealed table from Play with friends, set up with the full card pool, with a guest seated and
 * both players ready, one click from dealing ("Open packs" or "Start draft"). Returns the guest's page.
 */
export async function eventTable(p: Probe, kind: 'draft' | 'sealed'): Promise<Page> {
  const page = p.page;
  await page.goto(p.server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=multiplayer]');
  await page.click(`.chooser [data-kind=${kind}]`);
  await expect(page.locator('.event-setup .wiz')).toBeVisible({ timeout: 30_000 });
  await page.click('.tile-choice:has-text("Full card pool")');
  const next = page.locator('.stp-open button:has-text("Continue")');
  if (await next.count()) await next.click();
  await page.click('.wfoot button:has-text("Save")');
  await expect(page.locator('.event-head .event-product')).toBeVisible();
  const guest = await seatGuest(p);
  for (const on of [guest, page]) {
    await on.click('.plate.mine .ready-toggle');
    await expect(on.locator('.plate.mine .ready-toggle')).toContainText('Ready');
  }
  return guest;
}

/** An offline draft at its first pick. */
export async function drafting(p: Probe): Promise<void> {
  const page = p.page;
  await page.goto(p.server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=draft]');
  await page.click('.ev.new');
  await page.click('.tile-choice:has-text("Full card pool")');
  await page.click('.ticket button:has-text("Start drafting")');
  await expect(page.locator('#drafting')).toBeVisible({ timeout: 60_000 });
}
