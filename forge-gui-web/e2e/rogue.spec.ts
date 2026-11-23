import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { startServer, type Server } from './server';
import { enterName, toPriority } from './steps';

const here = dirname(fileURLToPath(import.meta.url));
// Saves written by desktop Forge. The run is at a chest in row 3, with a Merchant Caravan event at node 10, a bazaar at 12 and a plane at 6
const RUN = readFileSync(join(here, '../src/test/resources/rogue/run-master.xml'), 'utf8');
const PROGRESS = readFileSync(join(here, '../src/test/resources/rogue/meta-master.xml'), 'utf8');

// Card pictures come from Scryfall, which refuses a burst of requests. A stand-in picture keeps the pages off the network
const PICTURE = readFileSync(join(here, '../src/main/resources/web/playmats/selesnya-thumb.jpg'));

let server: Server | undefined;
test.afterEach(async () => {
  await server?.stop();
  server = undefined;
});

/** Starts a server with the desktop progress, and the desktop run changed by edit if one is given, then opens Rogue Commander. */
async function openRogue(page: Page, edit?: (run: string) => string): Promise<void> {
  // Dev mode is off, because it makes the flow ask which event, plane or encounter each node holds
  server = await startServer(undefined, {
    prefs: { DECKGEN_CARDBASED: 'false' },
    files: {
      'rogue-commander/meta_progress.dat': gzipSync(PROGRESS),
      ...(edit ? { 'rogue-commander/Aragorn_1.dat': gzipSync(edit(RUN)) } : {}),
    },
  });
  await page.route('**/img?key=*', route => route.fulfill({ contentType: 'image/jpeg', body: PICTURE }));
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=rogue]');
}

/** The desktop run standing on another node, with gold as given and out of Endless Mode, so a lost battle ends it. */
const at = (node: number, gold = 8) => (run: string) => run.replace('<currentNodeIndex>11</currentNodeIndex>', `<currentNodeIndex>${node}</currentNodeIndex>`)
  .replace('<currentGold>8</currentGold>', `<currentGold>${gold}</currentGold>`).replace('<endlessLevel>1</endlessLevel>', '<endlessLevel>0</endlessLevel>');

const gold = (page: Page) => page.locator('#campaign .camp-purse .camp-coin[data-icon="ICO_QUEST_COIN"] b');

/** Goes on through each result, story, message and announcement until target can be used, with none of them over it. */
async function until(page: Page, target: Locator): Promise<void> {
  const dialog = page.locator('#campaign .rg-dialog');
  const ready = async () => await target.isVisible() && await dialog.count() === 0 && await target.evaluate(el => !el.closest('[inert]'));
  await expect(async () => {
    if (!await ready()) {
      // A dialog lies over any scene
      const next = await dialog.count() ? dialog.locator('.actions .primary') : page.locator('#campaign .rg-scene .ft .primary:enabled');
      if (await next.first().isVisible()) await next.first().click({ timeout: 1000 });
    }
    expect(await ready()).toBe(true);
  }).toPass({ timeout: 60_000 });
}

/** Continues the saved run from Runs and enters the node it stands on. */
async function enterNode(page: Page): Promise<void> {
  const resume = page.locator('#campaign .rgl-run').getByRole('button', { name: 'Continue Run' });
  await until(page, resume);
  await resume.click();
  const enter = page.locator('#campaign .rg-go');
  await until(page, enter);
  await enter.click();
}

// Fails if a new run cannot be begun from the commanders, or its start-of-run boon cannot be taken on the way to the map
test('a run is started and its boon taken', async ({ page }) => {
  await openRogue(page);
  // With no run saved, Runs shows the commanders. Tyvar's level in the progress offers a boon as the run starts
  const start = page.locator('#campaign .rgm-end .hv.on .rg-start-btn');
  await until(page, start);
  await start.click();
  const boon = page.locator('#campaign .rg-scene .rg-opt-b:enabled').first();
  await until(page, boon);
  await boon.click();
  await until(page, page.locator('#campaign .rg-vmap'));
  await expect(page.locator('#campaign .camp-tab[data-tab="map"]')).toHaveAttribute('aria-current', 'page');
});

// Fails if a desktop run cannot be continued, a reload in the middle of an event loses its question, or the answer is not taken into the run
test('a desktop run is continued, and an event reloaded in the middle and answered', async ({ page }) => {
  await openRogue(page, at(10));
  await expect(page.locator('#campaign .rgl-run h1')).toHaveText('Aragorn, the Uniter');
  await enterNode(page);
  const event = page.locator('#campaign .rg-scene h3', { hasText: 'Merchant Caravan' });
  await expect(event).toBeVisible({ timeout: 30_000 });
  await page.reload();
  await expect(event).toBeVisible({ timeout: 30_000 });
  // Robbing the caravan costs 3 life and pays 8 gold
  const rob = page.locator('#campaign .rg-opt-b[aria-label="Rob Them"]');
  await until(page, rob);
  await rob.click();
  await until(page, page.locator('#campaign .rg-vmap'));
  await expect(gold(page)).toHaveText('16');
  await expect(page.locator('#campaign .camp-purse .camp-coin[data-icon="rogueLife"] b')).toHaveText('17 / 20');
});

// Fails if a bazaar's item cannot be bought, or its price is not taken from the run's gold
test('an item is bought in a bazaar', async ({ page }) => {
  await openRogue(page, at(12, 100));
  await enterNode(page);
  const item = page.locator('#campaign .rg-baz .rg-item:not(.no)').first();
  await until(page, item);
  const price = Number(await item.locator('.pr').evaluate(el => el.lastChild!.textContent));
  await item.click();
  const buy = page.locator('#campaign .rg-scene .ft .primary');
  await until(page, buy);
  await buy.click();
  await expect(gold(page)).toHaveText(String(100 - price));
});

// Fails if a battle cannot be fought and conceded from the map, or the lost run does not reach History
test('a battle is conceded and the run is shown in History', async ({ page }) => {
  await openRogue(page, at(6));
  await enterNode(page);
  // The plane's omen comes first
  await until(page, page.locator('#match'));
  await toPriority(page);
  // A trigger on the stack can push the game menu's button below a 900 pixel window, so the concession is sent as that menu sends it
  await page.evaluate(() => (window as any).forge.actions.concede());
  await page.locator('#game-over').getByRole('button', { name: 'End Run' }).click();
  const history = page.locator('#campaign .camp-tab[data-tab="history"]');
  await until(page, history);
  await history.click();
  await expect(page.locator('#campaign .rg-run').first()).toContainText('Aragorn, the Uniter');
  await expect(page.locator('#campaign .rg-run').first()).toContainText('Defeat');
});
