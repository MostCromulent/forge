import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { buildLimitedDeck, enterName } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if any step of an offline draft cannot be reached from the start page: the chooser, the setup form, 45
// picks on the pack dial, saving, building a deck from the picks, and a gauntlet against the computer's decks
test('an offline draft is picked, saved, built and played as a gauntlet', async ({ page }) => {
  test.setTimeout(480_000);
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=draft]');
  await expect(page.locator('#limited')).toBeVisible();

  await page.click('text=New event');
  await page.click('.tile-choice:has-text("Full card pool")');
  await page.click('.wfoot button:has-text("Start draft")');
  await expect(page.locator('#drafting')).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('.dial')).toBeVisible();

  const picked = page.locator('.draft-picks-head h3 .muted');
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
  await buildLimitedDeck(page);
  await page.click('.editor-head button.primary');

  await expect(page.locator('.opponents')).toBeVisible();
  await page.click('.opponents .radio[data-mode=gauntlet]');
  await page.click('.opp-side button.primary:has-text("Start the gauntlet")');
  await expect(page.locator('#match')).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('#prompt .message')).not.toBeEmpty({ timeout: 60_000 });
});
