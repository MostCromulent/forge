import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { enterName, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if the guide cannot change the format, if the sentence does not follow it, or if a chip explains nothing
test('a host reads about the formats and picks one from the guide', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);

  await page.click('.guide-link');
  const oathbreaker = page.locator('.guide-item', { hasText: 'Oathbreaker' });
  await expect(oathbreaker.locator('.fact').first()).toHaveText('60 cards, one of each');
  await oathbreaker.locator('.guide-choose').click();
  await expect(page.locator('.guide-item.on')).toContainText('Oathbreaker');
  await page.keyboard.press('Escape');
  await expect(page.locator('.guide')).toHaveCount(0);

  await expect(page.locator('button.format[aria-pressed=true]')).toHaveText('Oathbreaker');
  await expect(page.locator('.match-sentence')).toContainText('Oathbreaker.');

  // Resting on a chip explains it, and moving away puts the card away again
  await page.locator('button.format', { hasText: 'Brawl' }).hover();
  await expect(page.locator('.format-card')).toContainText('Life 25, or 30 with 3 or more players');
  await page.mouse.move(0, 400);
  await expect(page.locator('.format-card')).toHaveCount(0);
});

// Fails if Momir Basic cannot start without decks, or its avatar is not a card tile the player can find
test('a Momir Basic match starts with no deck chosen, and the avatar sits beside the portrait', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  await page.click('.guide-link');
  await page.locator('.guide-item', { hasText: 'Momir Basic' }).locator('.guide-choose').click();
  await page.keyboard.press('Escape');
  await expect(page.locator('.deck-row.fixed').first()).toHaveText('No deck to build: 60 basic lands');
  await expect(page.locator('#play')).toBeEnabled();
  await page.click('#play');
  await expect(page.locator('#match')).toBeVisible();
  await expect(page.locator('#me .cmd-tile').first()).toHaveAttribute('title', /Momir Vig/, { timeout: 30_000 });
});
