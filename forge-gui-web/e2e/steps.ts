// What a player does, in the words of the page. The specs read as the steps a person would take.

import { expect, type Locator, type Page } from '@playwright/test';

/** Answers the name prompt, where the first browser to answer is the host because the host seat is offered already ticked. */
export async function enterName(page: Page, name: string): Promise<void> {
  await page.fill('#player-name', name);
  await page.keyboard.press('Enter');
}

/** Opens a table from the host's menu: against the computer, or one others can join by link. */
export async function hostTable(page: Page, invite: boolean): Promise<void> {
  await page.click(invite ? '[data-mode=multiplayer]' : '[data-mode=play]');
  await page.click('.chooser [data-kind=constructed]');
  await expect(page.locator('#seats .plate').first()).toBeVisible();
}

/** Chooses the first legal deck for a seat through the deck finder, which opens on legal decks only. */
export async function chooseDeck(page: Page, plate: Locator): Promise<void> {
  await plate.locator('.sleeve').click();
  await page.locator('.dk-hit:not(.generated)').first().dblclick();
  await expect(page.locator('.finder')).toHaveCount(0);
  await expect(plate.locator('.deck-name')).not.toHaveText('');
}

/** The address a guest opens, pointed at this machine, read from the header's Invite. */
export async function inviteLink(page: Page, serverUrl: string): Promise<string> {
  await page.locator('.page-head .menu-button', { hasText: 'Invite' }).click();
  await expect(page.locator('.share-url').first()).toBeVisible();
  const shared = await page.locator('.share-url').first().textContent();
  await page.keyboard.press('Escape');
  return (shared ?? '').replace(/^https?:\/\/[^/]+/, new URL(serverUrl).origin);
}

/** Answers each dialog with its first choice until none is open, and closes cards only put up to be seen. */
export async function answerDialogs(page: Page): Promise<void> {
  const dialog = page.locator('#dialog-layer .dialog');
  const reveal = page.locator('#dialog-layer .reveal-panel');
  for (let i = 0; i < 10 && await reveal.count(); i++) {
    await reveal.locator('.zone-answer.ok').click();
    await page.waitForTimeout(300);
  }
  for (let i = 0; i < 10 && await dialog.count(); i++) {
    const choice = dialog.locator('.options .card, .options .text-option').first();
    if (await choice.count()) await choice.click();
    await dialog.locator('.actions button').last().click();
    await page.waitForTimeout(300);
  }
}

/** Waits for the game's first question, closing the window of cards the AI plays poorly, which the game waits on. */
export async function gameStarted(page: Page): Promise<void> {
  await expect(async () => {
    const reveal = page.locator('#dialog-layer .reveal-panel .zone-answer.ok');
    if (await reveal.count()) await reveal.click();
    await expect(page.locator('#prompt .message')).not.toBeEmpty({ timeout: 1000 });
  }).toPass({ timeout: 60_000 });
}

/** Concedes from the game menu, which asks twice. */
export async function concede(page: Page): Promise<void> {
  await answerDialogs(page);
  await page.click('#prompt .more');
  await page.locator('.game-menu .concede').click();
  await page.locator('.game-menu .concede').click();
  await expect(page.locator('#game-over')).toBeVisible();
}

export async function say(page: Page, input: string, text: string): Promise<void> {
  await page.fill(input, text);
  await page.press(input, 'Enter');
}

/** Builds the open limited deck to forty cards, topping up with basics because a short deck is refused when legality is enforced. */
export async function buildLimitedDeck(page: Page): Promise<void> {
  const sizes = page.locator('.deck-head .sizes');
  const tiles = page.locator('.cat-grid .slot .tile');
  for (let i = 0; i < 23; i++) {
    await tiles.nth(i).click();
    await expect(sizes).toContainText(`${i + 1} cards`);
  }
  await page.click('.land-row button:has-text("Suggest")');
  const size = async () => Number(/^(\d+) cards/.exec(await sizes.innerText())?.[1] ?? 0);
  await expect.poll(size).toBeGreaterThan(23);
  while (await size() < 40) {
    const before = await size();
    await page.locator('.land-row .land button[aria-label^="One more"]').first().click();
    await expect.poll(size).toBeGreaterThan(before);
  }
}

/** A finger rested on something, which Playwright's tap cannot do. Chromium only. */
export async function hold(page: Page, target: Locator, ms = 650): Promise<void> {
  const box = await target.boundingBox();
  if (!box) throw new Error('Nothing to hold: the element is not on the page.');
  const x = Math.round(box.x + box.width / 2);
  const y = Math.round(box.y + box.height / 2);
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  await page.waitForTimeout(ms);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
}
