import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseDeck, enterName, flipOption, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

// Fails if the host's cheats cannot reach its own seat: netplay gives a client none, so they go through the server
test('the host turns on dev mode, sets up a game state and saves one', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  const seats = page.locator('#seats .plate');
  await chooseDeck(page, seats.nth(0));
  await chooseDeck(page, seats.nth(1));
  await page.keyboard.press('Enter');
  await expect(page.locator('#prompt .message')).not.toBeEmpty();

  // Off by default, so the menu offers nothing until the option is on
  await page.click('#prompt .more');
  await expect(page.getByRole('menuitem', { name: 'Dev mode ›' })).toHaveCount(0);
  await page.keyboard.press('Escape');
  await flipOption(page, 'Dev mode');

  // A game state is placed by the game's own thread, so it waits on whatever the game is asking. Holding priority,
  // the game is asking nothing else; a discard at cleanup, say, would hold the state back until it is answered.
  const priority = page.locator('#phase-strip .pill.priority');
  for (let i = 0; i < 40 && !(await priority.count()); i++) {
    if (await page.locator('#prompt .ok').isEnabled()) await page.keyboard.press(' ');
    await page.waitForTimeout(300);
  }
  await expect(priority).toHaveCount(1);

  await page.click('#prompt .more');
  await page.getByRole('menuitem', { name: 'Dev mode ›' }).click();
  const lands = page.getByRole('menuitemcheckbox', { name: /Play any number of lands/ });
  await expect(lands).toHaveAttribute('aria-checked', 'false');
  await lands.click();
  await expect(lands).toHaveAttribute('aria-checked', 'true');
  await page.screenshot({ path: test.info().outputPath('dev-menu.png'), timeout: 10_000 }).catch(() => {});

  await page.getByRole('menuitem', { name: 'Set up a game state…' }).click();
  // A state names whose turn and which phase it is and every player, or Forge refuses it; a life left out is 0
  await page.fill('.dev-state', ['activeplayer=human', 'activephase=MAIN1', 'humanlife=20', 'humanbattlefield=Grizzly Bears;Llanowar Elves;Forest',
    'ailife=20'].join('\n'));
  await page.getByRole('button', { name: 'Set up', exact: true }).click();
  await expect(page.locator('#me .battlefield .card')).toHaveCount(3);

  await page.click('#prompt .more');
  await page.getByRole('menuitem', { name: 'Dev mode ›' }).click();
  const saved = page.waitForEvent('download');
  await page.getByRole('menuitem', { name: 'Save the game state' }).click();
  const file = await saved;
  expect(file.suggestedFilename()).toBe('game-state.txt');
});
