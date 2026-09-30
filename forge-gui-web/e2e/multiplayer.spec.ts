import { expect, test, type Browser, type Page } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseDeck, enterName, hostTable, inviteLink, say } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

/** A host at a table others can join, and a guest seated at it; both in match setup. */
async function hostAndGuest(page: Page, browser: Browser): Promise<Page> {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, true);
  const guest = await (await browser.newContext()).newPage();
  await guest.goto(await inviteLink(page, server.url));
  // Every browser shares the server, and two players of one name cannot share a game
  await enterName(guest, 'alice');
  await expect(guest.locator('.menu-note')).toContainText('already called');
  await enterName(guest, 'Bea');
  await expect(guest.locator('#lobby')).toBeVisible();
  await expect(page.locator('#seats')).toContainText('Bea');
  return guest;
}

async function startMatch(page: Page, guest: Page): Promise<void> {
  await chooseDeck(page, page.locator('.plate.mine'));
  await chooseDeck(guest, guest.locator('.plate.mine'));
  // With another player seated, a deck alone does not ready a seat: each player says so
  await expect(page.locator('#play')).toBeDisabled();
  await page.click('.plate.mine .ready-toggle');
  await expect(page.locator('#play')).toBeDisabled();
  await guest.click('.plate.mine .ready-toggle');
  await expect(page.locator('#play')).toBeEnabled();
  // A new deck takes a player's Ready back
  await chooseDeck(guest, guest.locator('.plate.mine'));
  await expect(page.locator('#play')).toBeDisabled();
  await guest.click('.plate.mine .ready-toggle');
  await expect(page.locator('#play')).toBeEnabled();
  await page.click('#play');
  await expect(page.locator('#match')).toBeVisible();
  await expect(guest.locator('#match')).toBeVisible();
}

test('a guest joins by link under a name of its own and follows the host into the match', async ({ page, browser }) => {
  const guest = await hostAndGuest(page, browser);
  // Outside a match the chat is folded into a bar at the bottom edge until opened
  for (const p of [page, guest]) {
    await p.click('#dock .dock.folded');
  }
  await say(guest, '#dock .dock-say input', 'hello from Bea');
  await expect(page.locator('#dock .dock-log')).toContainText('hello from Bea');

  // A reload in match setup lands back at the same seat
  await guest.reload({ waitUntil: 'domcontentloaded' });
  await expect(guest.locator('.plate.mine')).toContainText('Bea');

  await startMatch(page, guest);
  await expect(page.locator('#me')).toContainText('Alice');
  await expect(guest.locator('#me')).toContainText('Bea');
  // In a match others can join, the dock sits open under the log without being asked for
  await say(page, '#match-chat .dock-say input', 'good luck');
  await expect(guest.locator('#match-chat .dock-log')).toContainText('good luck');
});
