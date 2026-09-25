import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { enterName } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if any step of offline sealed cannot be reached from the start page: the chooser, the setup form, the
// limited editor building a forty-card deck from the pool, the opponents screen, and the match
test('a sealed pool opened from the menu is played against the computer', async ({ page }) => {
  test.setTimeout(240_000);
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=sealed]');
  await expect(page.locator('#limited')).toBeVisible();

  await page.click('text=New event');
  await page.click('.tile-choice:has-text("Full card pool")');
  await page.click('.stp-open button:has-text("Continue")');
  await page.click('.stp-open button:has-text("Continue")');
  await expect(page.locator('.wfoot .sentence')).toContainText('6 booster packs');
  await page.click('.wfoot button:has-text("Open the packs")');

  await expect(page.locator('#editor')).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('.check-fixed')).toHaveText('Limited · 40 cards');
  const tiles = page.locator('.cat-grid .slot .tile');
  await expect(tiles.first()).toBeVisible();
  for (let i = 0; i < 23; i++) {
    await tiles.nth(i).click();
    await expect(page.locator('.deck-head .sizes')).toContainText(`${i + 1} cards`);
  }
  await page.click('.land-row button:has-text("Suggest")');
  await expect(page.locator('.deck-head .sizes')).not.toContainText('23 cards ·');
  await page.click('.editor-head button.primary');

  await expect(page.locator('.opponents')).toBeVisible();
  const play = page.locator('.opp-side button.primary');
  await expect(play).toBeEnabled();
  await play.click();
  await expect(page.locator('#match')).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('#prompt .message')).not.toBeEmpty({ timeout: 60_000 });
});
