import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseGame, enterName, flipOption, gameStarted, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if commander damage dealt by the engine does not reach the damaged player's portrait as a number, or the
// portrait's hover does not name the commander that dealt it
test('commander damage shows on the portrait and is broken down in its hover', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  await chooseGame(page, 'Commander');
  for (let i = 0; i < 2; i++) await page.locator('#seats .plate').nth(i).locator('.random-row').click();
  await expect(page.locator('#play')).toBeEnabled({ timeout: 60_000 });
  await page.click('#play');
  await gameStarted(page);
  await flipOption(page, 'Dev mode');
  // As in dev.spec: the state is placed by the game's thread, so it is set while the game waits on this player
  const priority = page.locator('#phase-strip .pill.priority');
  for (let i = 0; i < 40 && !(await priority.count()); i++) {
    if (await page.locator('#prompt .ok').isEnabled()) await page.keyboard.press(' ');
    await page.waitForTimeout(300);
  }
  await expect(priority).toHaveCount(1);

  await page.click('#prompt .more');
  await page.getByRole('menuitem', { name: 'Dev mode ›' }).click();
  await page.getByRole('menuitem', { name: 'Set up a game state…' }).click();
  await page.fill('.dev-state', ['activeplayer=ai', 'activephase=COMBAT_DECLARE_ATTACKERS', 'humanlife=40', 'ailife=40',
    'aibattlefield=Ghalta, Primal Hunger|IsCommander|Attacking', 'humanbattlefield=Forest'].join('\n'));
  await page.getByRole('button', { name: 'Set up', exact: true }).click();
  // Nothing is answered until the placed board has arrived (see blocks.spec)
  await expect(page.locator('#opponent .card', { hasText: 'Ghalta' })).toHaveCount(1, { timeout: 20_000 });

  // Declare no blocks and pass until the 12 damage lands
  const chip = page.locator('#me .avatar .cmdr-chip');
  for (let i = 0; i < 60 && await chip.isHidden(); i++) {
    if (await page.locator('#prompt .ok').isEnabled()) await page.keyboard.press(' ');
    await page.waitForTimeout(300);
  }
  await expect(chip).toHaveText('12');
  await expect(page.locator('#me .avatar .cmdr-arc')).toBeVisible();

  await page.hover('#me .avatar');
  const row = page.locator('#zoom .cmdr-taken .cmdr-row');
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('Ghalta, Primal Hunger');
  await expect(row).toContainText('12');
});
