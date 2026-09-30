// Starting points for a probe: a throwaway spec that looks at one thing in the real page. A probe gets a server with
// dev mode on and a saved deck of the player's own, and each starting point returns with the page where the looking
// starts, so a probe holds only what it looks at:
//
//   import { board, probe } from './probe';
//   probe('haste', async p => {
//     await board(p, 'humanbattlefield=Raging Goblin|SummonSick;Mountain\naibattlefield=Grizzly Bears');
//     await p.snap('board');
//   });
//
// Inside a match a probe acts through the page's own actions and reads its model (act, asking, untilPriority,
// passUntil, p.until), never through keys or buttons, which answer whatever happens to be asked. A wait that runs out
// says what the page was asking, with a screenshot, rather than only that it timed out.
//
// Name the file zz-<anything>.spec.ts (ignored by git) and run it with `npx playwright test zz-<anything>`. Screenshots
// land in forge-gui-web/target/probe/<title>/, and the page's errors are printed as they happen.
//
// The server serves the page from the source folder, so a TS or CSS change needs only `npm run build` before a probe.
// Java and new language keys need the jar rebuilt, which cannot happen while any server is running from it.

import { expect, test, type Browser, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { PROBE_DECK, PROBE_SEED, startServer, type Server } from './server';
import { chooseDeck, enterName, gameStarted, hostTable, inviteLink } from './steps';
import type { Actions } from '../src/main/ts/actions';
import type { Model } from '../src/main/ts/model';

// main.ts puts the page's own actions and model here, which is how a probe drives the game
declare global {
  interface Window { forge: { actions: Actions; model: Model } }
}

export { PROBE_DECK };

export interface Probe {
  page: Page;
  browser: Browser;
  server: Server;
  /** Saves a screenshot of the page, or of another page such as a guest's, and prints where it went. */
  snap(name: string, on?: Page): Promise<void>;
  /** Waits for a test run in the page; if it never holds, fails saying what the page was asking, with a screenshot. */
  until<A>(what: string, test: (arg: A) => unknown, arg?: A, on?: Page, timeout?: number): Promise<void>;
  /** Screenshots taken one after another, laid out as one image, to see something move. */
  frames(name: string, count?: number, everyMs?: number, clip?: { x: number; y: number; width: number; height: number }, on?: Page): Promise<void>;
}

const VIEWPORT = { width: 1440, height: 900 };
const SHOTS = join(import.meta.dirname, '..', 'target', 'probe');
function listen(page: Page, who: string): void {
  page.on('pageerror', e => console.log(`[${who} page error] ${e.message}`));
  // A card image the test machine has not downloaded is a 404 every time, and says nothing about the page
  page.on('console', m => {
    if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) console.log(`[${who} console] ${m.text()}`);
  });
}

/** What the page shows and asks, in one line, for a wait that ran out. */
export function explain(page: Page): Promise<string> {
  return page.evaluate(() => {
    const m = window.forge?.model;
    if (!m) return 'The page never loaded the game client.';
    const prompt = m.prompt;
    const text = (sel: string) => [...document.querySelectorAll<HTMLElement>(sel)].map(e => e.innerText.replace(/\s+/g, ' ').trim()).filter(Boolean);
    return JSON.stringify({
      where: m.inMatch ? 'match' : m.inLobby ? 'lobby' : 'elsewhere',
      prompt: prompt && { message: prompt.message, priority: prompt.priority, ok: prompt.ok, cancel: prompt.cancel,
        selectable: prompt.selectable.length, players: prompt.selectablePlayers.length },
      problems: m.lobby?.problems,
      notices: text('.notice'),
      dialogs: text('#dialog-layer .dialog').map(t => t.slice(0, 160)),
    });
  });
}

export function probe(title: string, body: (p: Probe) => Promise<void>): void {
  test(title, async ({ browser }) => {
    test.setTimeout(600_000);
    const server = await startServer(undefined, PROBE_SEED);
    const context = await browser.newContext({ viewport: VIEWPORT });
    const page = await context.newPage();
    listen(page, 'host');
    const dir = join(SHOTS, title.replace(/[^\w-]+/g, '-'));
    mkdirSync(dir, { recursive: true });
    const snap = async (name: string, on: Page = page) => {
      await on.screenshot({ path: join(dir, `${name}.png`) });
      console.log(`[snap] forge-gui-web/target/probe/${title.replace(/[^\w-]+/g, '-')}/${name}.png`);
    };
    const p: Probe = {
      page, browser, server, snap,
      async until(what, test, arg, on = page, timeout = 30_000) {
        try {
          await on.waitForFunction(test as (arg: unknown) => unknown, arg, { timeout });
        } catch {
          await snap(`timeout-${what.replace(/[^\w-]+/g, '-')}`, on).catch(() => {});
          throw new Error(`Waited ${timeout / 1000} s for ${what}. The page: ${await explain(on)}`);
        }
      },
      async frames(name, count = 8, everyMs = 100, clip, on = page) {
        const shots: { at: number; png: string }[] = [];
        const start = Date.now();
        for (let i = 0; i < count; i++) {
          shots.push({ at: Date.now() - start, png: (await on.screenshot({ clip })).toString('base64') });
          await on.waitForTimeout(everyMs);
        }
        const columns = Math.max(1, Math.min(count, Math.floor(1800 / (clip?.width ?? VIEWPORT.width)))) || 1;
        const sheet = await context.newPage();
        await sheet.setContent(`<body style="margin:0;background:#000;display:grid;gap:4px;align-items:start;align-content:start;grid-template-columns:repeat(${columns},auto)">${
          shots.map(s => `<div style="position:relative"><img style="display:block" src="data:image/png;base64,${s.png}"><b style="position:absolute;left:4px;top:2px;padding:0 4px;background:#000b;color:#fff;font:12px sans-serif">${s.at} ms</b></div>`).join('')}</body>`);
        await sheet.screenshot({ path: join(dir, `${name}.png`), fullPage: true });
        await sheet.close();
        console.log(`[frames] forge-gui-web/target/probe/${title.replace(/[^\w-]+/g, '-')}/${name}.png`);
      },
    };
    try {
      await body(p);
    } finally {
      await context.close();
      await server.stop();
    }
  });
}

/** The start page, as the probe's player, named Alice. */
async function home(p: Probe): Promise<void> {
  await p.page.goto(p.server.url);
  await enterName(p.page, 'Alice');
}

/** A second browser joining the host's table by its invite link, under the name Bea. */
async function seatGuest(p: Probe): Promise<Page> {
  const guest = await (await p.browser.newContext({ viewport: VIEWPORT })).newPage();
  listen(guest, 'guest');
  await guest.goto(await inviteLink(p.page, p.server.url));
  await enterName(guest, 'Bea');
  await expect(p.page.locator('#seats')).toContainText('Bea');
  return guest;
}

/** Match setup against the computer, with no decks chosen. */
export async function lobby(p: Probe): Promise<void> {
  await home(p);
  await hostTable(p.page, false);
}

/** Match setup with a guest seated; returns the guest's page. */
export async function lobbyWithGuest(p: Probe): Promise<Page> {
  await home(p);
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
  const want = { game: table.game ?? null, players: table.players ?? 2 };
  // Set up through the lobby's own actions: the game, the seats, and each seat's deck, as the menus would
  await page.evaluate(({ game, players }) => {
    const { actions, model } = window.forge;
    const format = game && model.lobby?.formats.find(f => f.name === game);
    if (format) actions.setFormat(format.id);
    if (players !== model.lobby?.seats.length) actions.setPlayerCount(players);
  }, want);
  await p.until(`${want.players} seats${want.game ? ` at ${want.game}` : ''}`, ({ game, players }) => {
    const lobby = window.forge.model.lobby;
    return lobby?.seats.length === players && (!game || lobby.formats.find(f => f.id === lobby.format)?.name === game);
  }, want);
  // Each seat without a legal deck is dealt the first legal one by name, as the finder lists them. The deck list for a
  // new game arrives after it, so a seat dealt from the old list is dealt again until the table can start.
  for (let tries = 0; ; tries++) {
    const ready = await page.evaluate(() => {
      const { actions, model } = window.forge;
      const lobby = model.lobby!;
      if (lobby.canStart) return true;
      const legal = model.decks.filter(d => !d.generated && !d.problem).sort((a, b) => a.name.localeCompare(b.name));
      lobby.seats.forEach((seat, i) => {
        const deck = legal.find(d => d.key !== seat.deck);
        if ((!seat.deckName || seat.problem) && deck) actions.setSeat(i, { deck: deck.key });
      });
      return false;
    });
    if (ready) break;
    if (tries > 30) throw new Error(`The table never became ready. The page: ${await explain(page)}`);
    await page.waitForTimeout(700);
  }
  await act(page, 'startMatch', false);
  await gameStarted(page);
  await setState(p, state, table);
}

/**
 * Sets up a game state in the match already under way, with the same defaults as board: pass the table the match was
 * set up with, less any player who has lost, and the pages of any other people at the table. It waits for priority
 * first, and then for the first card the state names.
 */
export async function setState(p: Probe, state: string, table: Table = {}, others: Page[] = []): Promise<void> {
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
  await untilPriority(page, others);
  await act(page, 'dev', 'setupGameState', lines.join('\n'));
  if (!first) return;
  // The board is sent after it is placed; answering a prompt before it lands answers the old one
  await p.until(`${first} on the table`, name => [...document.querySelectorAll<HTMLElement>('#match .card')]
    .some(c => c.offsetParent !== null && c.textContent?.includes(name)), first, page, 10_000);
}

/** Calls one of the page's own actions, as its buttons and keys do, whichever key or button that is. */
export async function act<K extends keyof Actions>(page: Page, name: K, ...args: Parameters<Actions[K]>): Promise<void> {
  await page.evaluate(([n, a]) => (window.forge.actions[n] as (...x: unknown[]) => void)(...a), [name, args] as const);
}

/** What the prompt is asking you now, for a probe to wait on or to print when a wait runs out. */
export function asking(page: Page): Promise<{ message: string; priority: boolean; stack: boolean } | null> {
  return page.evaluate(() => {
    const prompt = window.forge.model.prompt;
    return prompt && { message: prompt.message, priority: prompt.priority, stack: !document.getElementById('stack')?.hidden };
  });
}

/**
 * Waits until you have priority with nothing on the stack, which is when the game takes a state. On the way it answers
 * the questions before the first turn with their first choice (play first, keep the hand) and passes whatever is on
 * the stack, for you and for the other pages given.
 */
export async function untilPriority(page: Page, others: Page[] = [], timeout = 60_000): Promise<void> {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const last = await asking(page);
    if (last?.priority && !last.stack) return;
    // Another player's page holding priority or asking before the first turn is answered for them, or it waits forever
    for (const other of others) {
      if (await other.evaluate(() => !!window.forge.model.prompt?.ok?.enabled)) await act(other, 'ok');
    }
    if (last && (!last.priority || last.stack)) {
      await act(page, 'ok');
      // Answered once: the next look waits for the prompt to change, rather than answering the same one twice
      await page.waitForFunction(m => window.forge.model.prompt?.message !== m, last.message, { timeout: 5000 }).catch(() => {});
    } else {
      await page.waitForTimeout(250);
    }
  }
  throw new Error(`No priority within ${timeout / 1000} s. The page: ${await explain(page)}`);
}

/**
 * Answers OK on every page given, yours first, until the test holds in yours: passing priority, keeping hands,
 * letting a spell resolve. OK declares no attackers and no blockers too, so stop before a declaration you want to make.
 */
export async function passUntil<A>(p: Probe, what: string, test: (arg: A) => unknown, arg?: A, others: Page[] = [], timeout = 60_000): Promise<void> {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (await p.page.evaluate(test, arg as A)) return;
    for (const page of [p.page, ...others]) {
      if (await page.evaluate(() => !!window.forge.model.prompt?.ok?.enabled)) await act(page, 'ok');
    }
    await p.page.waitForTimeout(400);
  }
  await p.snap(`timeout-${what.replace(/[^\w-]+/g, '-')}`).catch(() => {});
  const pages = await Promise.all([p.page, ...others].map(async (page, i) => `${i ? `other ${i}` : 'yours'}: ${await explain(page)}`));
  throw new Error(`Passed for ${timeout / 1000} s without ${what}. ${pages.join(' ')}`);
}

/**
 * An online Draft or Sealed table from Play with friends, set up with the full card pool, with a guest seated and
 * both players ready, one click from dealing ("Open packs" or "Start draft"). Returns the guest's page.
 */
export async function eventTable(p: Probe, kind: 'draft' | 'sealed'): Promise<Page> {
  const page = p.page;
  await home(p);
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
  await home(p);
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=draft]');
  await page.click('.ev.new');
  await page.click('.tile-choice:has-text("Full card pool")');
  await page.click('.ticket button:has-text("Start drafting")');
  await expect(page.locator('#drafting')).toBeVisible({ timeout: 60_000 });
}
