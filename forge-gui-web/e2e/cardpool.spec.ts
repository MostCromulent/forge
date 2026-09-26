import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseGame, enterName, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if the host's card pool does not reach the deck finder, lets a computer seat be dealt an illegal deck,
// or outlives the switch away from Constructed
test('a Constructed table held to Pauper offers and deals only Pauper decks', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  const seats = page.locator('#seats .plate');

  const cards = page.locator('.match-bar .field', { hasText: 'Format' }).locator('.menu-button');
  await cards.click();
  await expect(page.locator('.pool-tile', { hasText: 'Pauper' })).toContainText('Commons only');
  await page.locator('.pool-tile', { hasText: 'Pauper' }).click();
  await expect(cards).toHaveText('Pauper');

  // The finder opens pinned to the table's card pool, with illegal decks left out until asked for
  await seats.nth(0).locator('.sleeve').click();
  await expect(page.locator('.finder .rail .pinned')).toContainText('Pauper');
  await expect(page.locator('.dk-hit').first()).toBeVisible();
  await expect(page.locator('.dk-hit .legal.no')).toHaveCount(0);
  await page.click('.finder .legal-only input');
  await expect(page.locator('.dk-hit .legal.no').first()).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('.finder')).toHaveCount(0);

  // Every random deal to the computer is legal in Pauper
  const computer = seats.nth(1);
  for (let i = 0; i < 3; i++) {
    await computer.locator('.random-row, .random-deck').click();
    await expect(computer.locator('.deck-name')).not.toHaveText('');
    await expect(computer.locator('.seat-problem')).toBeHidden();
  }

  // A card pool belongs to Constructed, so leaving it clears the pool
  await chooseGame(page, 'Commander');
  await chooseGame(page, 'Constructed');
  await expect(cards).toHaveText('Any cards');
});
