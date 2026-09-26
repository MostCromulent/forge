import { expect, test, type Page } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseDeck, enterName, flipOption, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

/** Presses on one card and lets go over another, moving in steps as a hand does. */
async function drag(page: Page, from: string, to: string): Promise<void> {
  const a = await page.locator('.card', { hasText: from }).first().boundingBox();
  const b = await page.locator('.card', { hasText: to }).first().boundingBox();
  if (!a || !b) throw new Error(`No card for ${from} or ${to}`);
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
  await page.mouse.down();
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 12 });
  await page.mouse.up();
}

// Fails if a drag from a blocker onto an attacker does not reach the engine as a block, if dragging the same blocker
// onto another attacker straight after does not move the block there, or if a block the engine refuses goes unexplained
test('a blocker dragged onto an attacker blocks it, and dragged onto another moves the block', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  const seats = page.locator('#seats .plate');
  await chooseDeck(page, seats.nth(0));
  await chooseDeck(page, seats.nth(1));
  await page.keyboard.press('Enter');
  await expect(page.locator('#prompt .message')).not.toBeEmpty();
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
  await page.fill('.dev-state', ['activeplayer=ai', 'activephase=COMBAT_DECLARE_ATTACKERS', 'humanlife=20', 'ailife=20',
    'aibattlefield=Grizzly Bears|Attacking;Hill Giant|Attacking;Serra Angel|Attacking', 'humanbattlefield=Wall of Wood'].join('\n'));
  await page.getByRole('button', { name: 'Set up', exact: true }).click();
  // The state is placed on a game thread beside the one waiting on this player, so nothing is answered until the new
  // board has arrived: a pass sent while it is still being placed wakes the game over a half-built board
  await expect(page.locator('#me .card', { hasText: 'Wall of Wood' })).toHaveCount(1);
  // It is placed under the priority prompt that was open, and the game reaches the blocks once that is passed
  const message = page.locator('#prompt .message');
  for (let i = 0; i < 40 && !/block/.test(await message.innerText()); i++) {
    if (await page.locator('#prompt .ok').isEnabled() && !/block/.test(await message.innerText())) await page.keyboard.press(' ');
    await page.waitForTimeout(300);
  }
  await expect(message).toContainText('block');

  // A wall cannot block a flier, and a note by the pointer quotes the keyword's own rule
  await drag(page, 'Wall of Wood', 'Serra Angel');
  await expect(page.locator('.block-tip', { hasText: "Wall of Wood can't block Serra Angel" })).toContainText('flying');
  // The second drag follows the first at once; the drag waits for the first block to reach the board before moving it
  await drag(page, 'Wall of Wood', 'Grizzly Bears');
  await drag(page, 'Wall of Wood', 'Hill Giant');
  await page.locator('#prompt .ok').click();
  await expect(page.locator('#log')).toContainText(/assigned Wall of Wood \(\d+\) to block Hill Giant/);
});
