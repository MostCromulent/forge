// What a player does, in the words of the page. The specs read as the steps a person would take.

import { expect, type Locator, type Page } from '@playwright/test';

/** Answers the name prompt every browser the server does not know is shown first. */
export async function enterName(page: Page, name: string): Promise<void> {
  await page.fill('#player-name', name);
  await page.keyboard.press('Enter');
}

/** Takes the free host seat and opens a table: against the computer, or one others can join by link. */
export async function hostTable(page: Page, invite: boolean): Promise<void> {
  await page.click('#be-host');
  await page.click(invite ? '[data-mode=multiplayer]' : '[data-mode=play]');
  await expect(page.locator('#seats .plate').first()).toBeVisible();
}

/** Chooses the first legal deck for a seat through the deck finder. */
export async function chooseDeck(page: Page, plate: Locator): Promise<void> {
  await plate.locator('.sleeve').click();
  await page.check('.finder .legal-only input');
  await page.locator('.dk-hit:not(.generated)').first().dblclick();
  await expect(page.locator('.finder')).toHaveCount(0);
  await expect(plate.locator('.deck-name')).not.toHaveText('');
}

/** The address a guest opens, pointed at this machine. */
export async function inviteLink(page: Page, serverUrl: string): Promise<string> {
  const shared = await page.locator('.share-url').first().textContent();
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

/** Concedes from the options dialog, which asks twice. */
export async function concede(page: Page): Promise<void> {
  await answerDialogs(page);
  await page.click('#prompt .cog');
  await page.locator('#options .concede').click();
  await page.locator('#options .concede').click();
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
