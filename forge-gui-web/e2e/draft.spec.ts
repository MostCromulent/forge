import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { enterName, gameStarted } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if any step of an offline draft cannot be reached from the start page, from the chooser to a gauntlet game
test('an offline draft is picked, saved, built and played as a gauntlet', async ({ page }) => {
  test.setTimeout(480_000);
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=draft]');
  await expect(page.locator('#limited')).toBeVisible();

  await page.click('.ev.new');
  await page.click('.tile-choice:has-text("Full card pool")');
  await page.click('.ticket button:has-text("Start drafting")');
  await expect(page.locator('#drafting')).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('.table-btn')).toBeVisible();

  const picked = page.locator('.draft-picks .draft-panel-head b .muted');
  for (let i = 0; i < 45; i++) {
    const first = page.locator('.draft-pack .draft-slot .tile').first();
    await first.click();
    await first.click();
    await expect(picked).toHaveText(String(i + 1));
  }

  await expect(page.locator('.draft-save')).toBeVisible();
  await page.fill('.draft-save input', 'E2E draft');
  await page.click('.draft-save button:has-text("Save")');

  await expect(page.locator('#editor')).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('.check-fixed')).toHaveText('Limited · 40 cards');
  // A click picks into the main deck, so the deck opens already holding every pick
  await expect(page.locator('.deck-head .sizes')).toContainText('45 cards');
  await page.click('.editor-head button.primary');

  await expect(page.locator('.opponents')).toBeVisible();
  // The deck saved in the editor is the one the opponents screen reads
  await expect(page.locator('.your-deck')).toContainText('45 cards');
  await page.click('.opp-foot button:has-text("Start the gauntlet")');
  await expect(page.locator('#match')).toBeVisible({ timeout: 60_000 });
  await gameStarted(page);
});
