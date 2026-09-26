import { expect, test, type Browser, type Page } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseGame, enterName, hostTable, inviteLink } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

/** Searches the editor's catalogue and waits for a card of that name to be listed. */
async function search(page: Page, text: string, card: string): Promise<void> {
  await page.fill('.catalogue .find', text);
  await expect(page.locator(`.slot[data-card="${card}"]`)).toBeVisible();
}

// Fails if a deck built from the start page cannot be finished, undone, and then played: the commander-first
// catalogue, Enter adding the top card, Ctrl+Z, saving as it goes, and the lobby's finder listing the saved deck
test('a Commander deck built from the start page reaches a seat', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=editor]');
  await page.selectOption('.finder-head select', 'Commander');
  await page.click('text=+ New deck');
  await expect(page.locator('#editor')).toBeVisible();
  await expect(page.locator('.commander-empty')).toBeVisible();

  await search(page, 'meren of clan', 'Meren of Clan Nel Toth');
  await page.locator('.slot[data-card="Meren of Clan Nel Toth"] .under .side').click();
  await expect(page.locator('.commander .cname')).toContainText('Meren of Clan Nel Toth');
  await expect(page.locator('.deck-name')).toHaveText('Meren of Clan Nel Toth deck');

  await search(page, 'sol ring', 'Sol Ring');
  await page.press('.catalogue .find', 'Enter');
  await expect(page.locator('.ed-line[data-card="Sol Ring"]')).toBeVisible();
  // Undo is the page's only while nothing is being typed
  await page.click('.deck-head h3');
  await page.keyboard.press('Control+z');
  await expect(page.locator('.ed-line[data-card="Sol Ring"]')).toHaveCount(0);
  await expect(page.locator('.commander .cname')).toContainText('Meren of Clan Nel Toth');

  await page.click('.editor-head button.primary');
  await expect(page.locator('#editor')).toBeHidden();
  await hostTable(page, false);
  await chooseGame(page, 'Commander');
  await page.locator('#seats .plate.mine .sleeve').click();
  // Two cards is not yet a legal Commander deck, and the finder hides illegal decks until asked
  await page.click('.finder .legal-only input');
  await page.locator('.finder .dk-hit', { hasText: 'Meren of Clan Nel Toth deck' }).dblclick();
  await expect(page.locator('.plate.mine .deck-name')).toHaveText('Meren of Clan Nel Toth deck');
});

// Fails if a misspelt card cannot be fixed from the importer's problem list, or the import does not reach the seat
test('a pasted list with a typo is fixed and put on the seat', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  await page.locator('#seats .plate.mine .sleeve').click();
  await page.click('.finder-head >> text=Import');
  await page.fill('.importer textarea', '4 Lightning Bolt\n4 Nihil Spelbomb\n52 Mountain');
  await expect(page.locator('.importer .gl.k-problem')).toHaveCount(1);
  await page.click('.importer >> text=Use Nihil Spellbomb');
  await expect(page.locator('.importer textarea')).toHaveValue('4 Lightning Bolt\n4 Nihil Spellbomb\n52 Mountain');
  await expect(page.locator('.importer .gl.k-problem')).toHaveCount(0);
  await page.fill('.importer .namefield input', 'Typo Burn');
  await page.click('.importer >> text=Import and use');
  await expect(page.locator('.importer')).toHaveCount(0);
  await expect(page.locator('.plate.mine .deck-name')).toHaveText('Typo Burn');
});

async function seatedGuest(page: Page, browser: Browser): Promise<Page> {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, true);
  const guest = await (await browser.newContext()).newPage();
  await guest.goto(await inviteLink(page, server.url));
  await enterName(guest, 'Bea');
  await expect(guest.locator('#lobby')).toBeVisible();
  return guest;
}

// Fails if a guest's imported deck is not kept in the guest's own browser, where a reload finds it again
test('a guest\'s imported deck is kept on the guest\'s device', async ({ page, browser }) => {
  const guest = await seatedGuest(page, browser);
  await guest.locator('#seats .plate.mine .sleeve').click();
  await guest.click('.finder-head >> text=Import');
  await guest.fill('.importer textarea', '60 Mountain');
  await guest.fill('.importer .namefield input', 'Bea Mountains');
  await guest.click('.importer >> text=Import and use');
  await expect(guest.locator('.plate.mine .deck-name')).toHaveText('Bea Mountains');

  await guest.reload({ waitUntil: 'domcontentloaded' });
  await guest.locator('#seats .plate.mine .sleeve').click();
  await guest.locator('.rail .source', { hasText: 'On this device' }).click();
  await expect(guest.locator('.finder .dk-hit', { hasText: 'Bea Mountains' })).toBeVisible();
});
