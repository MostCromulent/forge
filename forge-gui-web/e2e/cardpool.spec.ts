import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { enterName, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if the host's card pool is not offered from the Format control, or does not reach the deck finder. That the
// computer is dealt only legal decks, and that the pool goes with Constructed, is LobbyCardPoolTest's
test('a Constructed table held to Pauper offers only Pauper decks', async ({ page }) => {
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
});
