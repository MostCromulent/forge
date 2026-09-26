// What a player does, in the words of the page. The specs read as the steps a person would take.

import { expect, type Locator, type Page } from '@playwright/test';

/**
 * Answers the name prompt every browser the server does not know is shown first. While the host seat is free the
 * prompt offers it, already ticked, so the first browser to answer is the host.
 */
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

/** Opens the Game field's menu over the match bar. */
export async function openGameMenu(page: Page): Promise<void> {
  await page.locator('.match-bar .field', { hasText: 'Game' }).locator('.menu-button').click();
}

/** Chooses what is played from the Game field: a format, Draft or Sealed. */
export async function chooseGame(page: Page, name: string): Promise<void> {
  await openGameMenu(page);
  await page.locator('.game-choice').filter({ has: page.locator('.game-name', { hasText: new RegExp(`^${name}$`) }) }).click();
  await expect(page.locator('.match-bar .field', { hasText: 'Game' }).locator('.menu-button')).toHaveText(name);
}

/** Turns a casual variant on or off from the Variants field. */
export async function toggleVariant(page: Page, name: string): Promise<void> {
  await page.locator('.match-bar .field', { hasText: 'Variants' }).locator('.menu-button').click();
  await page.locator('.variant').filter({ has: page.locator('b', { hasText: new RegExp(`^${name}$`) }) }).locator('input').click();
  await page.keyboard.press('Escape');
}

/** The address a guest opens, pointed at this machine, read from the header's Invite. */
export async function inviteLink(page: Page, serverUrl: string): Promise<string> {
  await page.locator('.lobby-head .menu-button', { hasText: 'Invite' }).click();
  await expect(page.locator('.share-url').first()).toBeVisible();
  const shared = await page.locator('.share-url').first().textContent();
  await page.keyboard.press('Escape');
  return (shared ?? '').replace(/^https?:\/\/[^/]+/, new URL(serverUrl).origin);
}

/** Answers whatever the game asks in a dialog with its first choice, until none is open. */
export async function answerDialogs(page: Page): Promise<void> {
  const dialog = page.locator('#dialog-layer .dialog');
  for (let i = 0; i < 10 && await dialog.count(); i++) {
    const choice = dialog.locator('.options .card, .options .text-option').first();
    if (await choice.count()) await choice.click();
    await dialog.locator('.actions button').last().click();
    await page.waitForTimeout(300);
  }
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

/** The value each option in the options dialog shows, by its label. */
export async function readOptions(page: Page): Promise<Record<string, string>> {
  await page.click('#prompt .cog');
  // The dialog is drawn on the next frame, so its rows are read once they are there
  await expect(page.locator('#options .setting').first()).toBeVisible();
  const values = await page.locator('#options .setting').evaluateAll(rows => Object.fromEntries(rows.map(row => [
    (row as HTMLElement).innerText.split('\n')[0],
    row.querySelector('.switch')?.classList.contains('on') ? 'on'
      : row.querySelector('.switch') ? 'off'
        : [...row.querySelectorAll('.choice button.on')].map(b => b.textContent).join(''),
  ])));
  await page.keyboard.press('Escape');
  await expect(page.locator('#options')).toHaveCount(0);
  return values;
}

export async function flipOption(page: Page, label: string): Promise<void> {
  await page.click('#prompt .cog');
  await page.locator('#options .setting', { hasText: label }).locator('.switch').click();
  await page.keyboard.press('Escape');
  await expect(page.locator('#options')).toHaveCount(0);
}

/**
 * Builds the open limited deck to forty cards: 23 cards from the pool, the suggested lands, then basics until there are
 * forty, since the suggestion depends on the pool and a short deck is refused when deck legality is enforced.
 */
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
