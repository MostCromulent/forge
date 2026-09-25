import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { answerDialogs, chooseDeck, enterName, hostTable } from './steps';

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

// Fails if the plane dock never shows the face-up plane, or the die button never enables on the viewer's main phase or does not roll
test('a Planechase match shows the plane, and the die button rolls on your own main phase', async ({ page }) => {
  test.setTimeout(240_000);
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  const seats = page.locator('#seats .plate');
  await chooseDeck(page, seats.nth(0));
  await chooseDeck(page, seats.nth(1));
  await page.locator('.variants button.format', { hasText: 'Planechase' }).click();
  await expect(seats.nth(0).locator('.seat-extra', { hasText: 'Planes' })).toBeVisible();
  await expect(page.locator('#play')).toBeEnabled();
  await page.click('#play');
  await expect(page.locator('#match')).toBeVisible();

  // The first plane is laid once the opening hands are kept, so the game is played on until the die can be rolled
  const die = page.locator('#plane-dock .die-button');
  const ready = async () => (await die.isVisible().catch(() => false)) && (await die.isEnabled().catch(() => false));
  for (let i = 0; i < 400 && !(await ready()); i++) {
    if (await page.locator('#dialog-layer .dialog').count()) {
      await answerDialogs(page);
    } else if (/discard/i.test(await page.locator('#prompt .message').textContent() ?? '')) {
      await page.locator('#hand .card').first().click();
    } else if (await page.locator('#prompt .ok').isEnabled().catch(() => false)) {
      await page.keyboard.press(' ');
    }
    await page.waitForTimeout(250);
  }
  await expect(die).toBeEnabled();
  await expect(page.locator('#plane-dock .plane').first()).toBeVisible();
  await die.click();
  await expect(page.getByText(/Planar dice result/i).first()).toBeVisible({ timeout: 20_000 });
});
