import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseDeck, enterName, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if a casual variant cannot be switched on from the lobby, or a picker cannot set its section for the seat
test('a host sets up Vanguard and Planechase, choosing an avatar and a planar deck', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  const mine = page.locator('#seats .plate.mine');
  await chooseDeck(page, mine);

  await page.locator('.variants button.format', { hasText: 'Vanguard' }).click();
  await page.locator('.variants button.format', { hasText: 'Planechase' }).click();
  await expect(mine.locator('.seat-extra', { hasText: 'Avatar' })).toContainText('Random');

  await mine.locator('.seat-extra', { hasText: 'Avatar' }).click();
  const first = page.locator('.avatar-choice').first();
  const name = (await first.getAttribute('title')) ?? '';
  await first.click();
  await expect(mine.locator('.seat-extra', { hasText: 'Avatar' })).toContainText(name);

  await mine.locator('.seat-extra', { hasText: 'Planes' }).click();
  await page.locator('.extra-choice', { hasText: 'Generated' }).click();
  await expect(mine.locator('.seat-extra', { hasText: 'Planes' }).locator('.extra-count')).toHaveText(/^[1-9]\d+$/);
});
