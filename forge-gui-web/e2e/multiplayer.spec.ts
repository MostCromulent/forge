import { expect, test, type Browser, type Page } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseDeck, enterName, flipOption, hostTable, inviteLink, readOptions, say } from './steps';

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
  await expect(page.locator('#play')).toBeEnabled();
  await page.click('#play');
  await expect(page.locator('#match')).toBeVisible();
  await expect(guest.locator('#match')).toBeVisible();
}

test('a guest joins by link under a name of its own and follows the host into the match', async ({ page, browser }) => {
  const guest = await hostAndGuest(page, browser);
  await say(guest, '#lobby-chat-in', 'hello from Bea');
  await expect(page.locator('#lobby-chat-log')).toContainText('hello from Bea');

  // A reload in match setup lands back at the same seat
  await guest.reload({ waitUntil: 'domcontentloaded' });
  await expect(guest.locator('.plate.mine')).toContainText('Bea');

  await startMatch(page, guest);
  await expect(page.locator('#me')).toContainText('Alice');
  await expect(guest.locator('#me')).toContainText('Bea');
  for (const p of [page, guest]) {
    await p.click('.side-toggle[data-panel=chat]');
  }
  await say(page, '#match-chat-in', 'good luck');
  await expect(guest.locator('#match-chat-log')).toContainText('good luck');
});

// The server keeps a guest's settings only as long as it runs, so the guest's browser gives them back to a new one
test('a guest\'s phase stops outlive a server restart', async ({ page, browser }) => {
  let guest = await hostAndGuest(page, browser);
  await startMatch(page, guest);
  const stopAt = (p: Page, phase: string) => p.locator(`#phase-strip .stops button.cell[data-mine="true"][data-phase="${phase}"] .square`);
  await guest.click('#phase-strip .pill');
  const phase = await guest.locator('#phase-strip .stops button.cell[data-mine="true"]').first().getAttribute('data-phase') ?? '';
  const wasOn = await stopAt(guest, phase).evaluate(el => el.classList.contains('on'));
  await guest.locator(`#phase-strip .stops button.cell[data-mine="true"][data-phase="${phase}"]`).click();
  await expect(stopAt(guest, phase)).toHaveClass(wasOn ? /^(?!.*\bon\b)/ : /\bon\b/);

  const context = guest.context();
  await guest.close();
  const port = server.port;
  await server.stop();
  server = await startServer(port);
  // Both browsers are known by the names they remember, and the host's takes its seat back without being asked
  await page.goto(server.url);
  await page.click('[data-mode=multiplayer]');
  await expect(page.locator('#seats .plate').first()).toBeVisible();
  guest = await context.newPage();
  await guest.goto(await inviteLink(page, server.url));
  await expect(guest.locator('#lobby')).toBeVisible();
  await startMatch(page, guest);
  await guest.click('#phase-strip .pill');
  await expect(stopAt(guest, phase)).toHaveClass(wasOn ? /^(?!.*\bon\b)/ : /\bon\b/);
});

test('a guest\'s settings are its own, not the host\'s', async ({ page, browser }) => {
  const guest = await hostAndGuest(page, browser);
  await startMatch(page, guest);
  const label = 'Stop auto-passing when attackers are declared';
  const before = (await readOptions(page))[label];
  await flipOption(guest, label);
  expect((await readOptions(guest))[label]).not.toBe(before);
  expect((await readOptions(page))[label]).toBe(before);
});
