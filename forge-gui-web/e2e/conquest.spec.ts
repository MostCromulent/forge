import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { PROBE_SEED, startServer, type Server } from './server';
import { answerDialogs, enterName } from './steps';

const fixture = join(dirname(fileURLToPath(import.meta.url)), '../src/test/resources/conquest/Fixture_conquest');
const deck = readdirSync(join(fixture, 'decks'))[0];
const saved = 'conquest/saves/Fixture_conquest';

let server: Server;
test.beforeEach(async () => {
  server = await startServer(undefined, {
    ...PROBE_SEED,
    files: {
      [`${saved}/data.xml`]: readFileSync(join(fixture, 'data.xml'), 'utf8'),
      [`${saved}/decks/${deck}`]: readFileSync(join(fixture, 'decks', deck), 'utf8'),
    },
  });
});
test.afterEach(async () => { await server.stop(); });

/** Plays up to the player's first priority: the opening hand is kept, and anything else asked is given its first answer. */
async function toPriority(page: Page): Promise<void> {
  await expect(async () => {
    await answerDialogs(page);
    const prompt = await page.evaluate(() => (window as any).forge.model.prompt);
    if (prompt && !prompt.priority && prompt.ok?.enabled) await page.evaluate(() => (window as any).forge.actions.ok());
    expect(prompt?.priority).toBe(true);
  }).toPass({ timeout: 90_000, intervals: [500] });
}

// Fails if a battle cannot be reached from the map, a win is not shown as conquered on returning, or its reward is not offered
test('a conquest battle is fought and comes back to the map', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=conquest]');
  await page.locator('.cq-save', { hasText: 'Fixture conquest' }).getByRole('button', { name: 'Play' }).click();

  // The fixture has its first event won, so its two neighbours are open
  await expect(page.locator('.cq-tile.won')).toHaveCount(1);
  await expect(page.locator('.cq-tile.open')).toHaveCount(2);
  await page.locator('.cq-tile.open').first().click();
  await expect(page.locator('.cq-steps')).toHaveText('1 step away');
  await page.getByRole('button', { name: 'Battle' }).click();

  await expect(page.locator('#match')).toBeVisible({ timeout: 60_000 });
  await page.evaluate(() => (window as any).forge.actions.devConquestWheel('SHARDS'));
  await toPriority(page);
  await page.evaluate(() => (window as any).forge.actions.dev('winGame'));
  // The player in the battle is the player at the seat, so the board knows the win is theirs
  await expect(page.locator('#game-over .word')).toHaveText('Victory');
  await page.locator('#game-over button', { hasText: 'Great' }).click();

  await expect(page.locator('.cq-reward')).toContainText('Received Aether Shards: 1000');
  await page.locator('.cq-reward').getByRole('button', { name: 'OK' }).click();
  await expect(page.locator('.cq-reward')).toHaveCount(0);
  await expect(page.locator('.cq-tile.won')).toHaveCount(2);
  await expect(page.locator('.cq-purse')).toContainText('4,000');

  // A reload shows the same map and does not offer the reward again
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.locator('.cq-tile.won')).toHaveCount(2);
  await expect(page.locator('.cq-reward')).toHaveCount(0);
});
