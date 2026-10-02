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

// Fails if the marker does not walk to a selected place, a battle cannot be reached from the map, the wheel or the pack
// cannot be gone through, or the win is not shown as conquered on returning
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
  // The marker walks to the place before the battle: part way through its one step it is between the two
  const centre = async () => { const b = (await page.locator('.cq-you').boundingBox())!; return b.x + b.y; };
  const from = await centre();
  await page.getByRole('button', { name: 'Battle' }).click();
  await expect(async () => {
    const now = await centre();
    expect(now).toBeGreaterThan(from + 10);
    expect(now).toBeLessThan(from + 104);
  }).toPass({ timeout: 2000, intervals: [30] });

  await expect(page.locator('#match')).toBeVisible({ timeout: 60_000 });
  await page.evaluate(() => (window as any).forge.actions.devConquestWheel('BOOSTER'));
  await toPriority(page);
  await page.evaluate(() => (window as any).forge.actions.dev('winGame'));
  // The player in the battle is the player at the seat, so the board knows the win is theirs
  await expect(page.locator('#game-over .word')).toHaveText('Victory');
  await page.locator('#game-over button', { hasText: 'Great' }).click();

  // The emblem of the first conquest is held back from the bar until the reveal shows it, with the wheel
  const reveal = page.locator('.cq-reveal');
  await expect(reveal.locator('.cq-wheel-first')).toBeVisible();
  await reveal.getByRole('button', { name: 'Spin' }).click();
  await expect(reveal.locator('.cq-dl.done')).toBeVisible({ timeout: 15_000 });
  await reveal.getByRole('button', { name: 'Great' }).click();

  // A booster: the pack is opened, every card turns face up, and the reveal ends on the map
  await reveal.locator('.cq-pk').click({ force: true });
  await expect(reveal.getByRole('button', { name: 'Great' })).toBeVisible({ timeout: 30_000 });
  const cards = await reveal.locator('.cq-rv-card').count();
  expect(cards).toBeGreaterThan(0);
  await expect(reveal.locator('.cq-rv-card.up')).toHaveCount(cards);
  await reveal.getByRole('button', { name: 'Great' }).click();
  await expect(reveal).toHaveCount(0);
  await expect(page.locator('.cq-tile.won')).toHaveCount(2);

  // A reload shows the same map and does not offer the reward again
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.locator('.cq-tile.won')).toHaveCount(2);
  // A reward still pending follows the map, so it is given the time to arrive before it is found absent
  await page.waitForTimeout(1500);
  await expect(page.locator('.cq-reveal')).toHaveCount(0);
});
