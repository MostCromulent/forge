import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseGame, enterName, hostTable, openGameMenu } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if the Mode menu cannot change the mode, or a format in it explains nothing when pointed at
test('a host reads about the formats and picks one from the Mode menu', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);

  await openGameMenu(page);
  await page.locator('.game-choice', { hasText: 'Brawl' }).hover();
  await expect(page.locator('.game-card')).toContainText('Life 25, or 30 with 3 or more players');
  await page.locator('.game-choice', { hasText: 'Oathbreaker' }).hover();
  await expect(page.locator('.game-card .fact').first()).toHaveText('60 cards, one of each');
  await page.keyboard.press('Escape');
  await expect(page.locator('.game-card')).toHaveCount(0);

  await chooseGame(page, 'Oathbreaker');
  await expect(page.locator('.match-bar .field', { hasText: 'Format' })).toHaveCount(0);
});

// Fails if Momir Basic cannot start without decks, or its avatar is not a card tile the player can find
test('a Momir Basic match starts with no deck chosen, and the avatar sits beside the portrait', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  await chooseGame(page, 'Momir Basic');
  await expect(page.locator('.deck-row.fixed').first()).toHaveText('No deck to build: 60 basic lands');
  await expect(page.locator('#play')).toBeEnabled();
  await page.click('#play');
  await expect(page.locator('#match')).toBeVisible();
  await expect(page.locator('#me .cmd-tile').first()).toHaveAttribute('title', /Momir Vig/, { timeout: 30_000 });
});
