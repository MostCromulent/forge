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

/** Opens the Mode field's menu over the match bar. */
export async function openGameMenu(page: Page): Promise<void> {
  await page.locator('.match-bar .field', { hasText: 'Mode' }).locator('.menu-button').click();
}

/** Chooses what is played from the Mode field: a format, Draft or Sealed. */
export async function chooseGame(page: Page, name: string): Promise<void> {
  await openGameMenu(page);
  await page.locator('.game-choice').filter({ has: page.locator('.game-name', { hasText: new RegExp(`^${name}$`) }) }).click();
  await expect(page.locator('.match-bar .field', { hasText: 'Mode' }).locator('.menu-button')).toHaveText(name);
}

/** Turns a casual variant on or off from the Variants field. */
export async function toggleVariant(page: Page, name: string): Promise<void> {
  await page.locator('.match-bar .field', { hasText: 'Variants' }).locator('.menu-button').click();
  await page.locator('.variant').filter({ has: page.locator('b', { hasText: new RegExp(`^${name}$`) }) }).locator('input').click();
  await page.keyboard.press('Escape');
}

/** The address a guest opens, pointed at this machine, read from the header's Invite. */
export async function inviteLink(page: Page, serverUrl: string): Promise<string> {
  await page.locator('.page-head .menu-button', { hasText: 'Invite' }).click();
  await expect(page.locator('.share-url').first()).toBeVisible();
  const shared = await page.locator('.share-url').first().textContent();
  await page.keyboard.press('Escape');
  return (shared ?? '').replace(/^https?:\/\/[^/]+/, new URL(serverUrl).origin);
}

/**
 * Answers whatever the game asks in a dialog with its first choice, until none is open. Cards only put up to be seen,
 * such as those the AI plays poorly, are closed.
 */
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

/** How many prompts the page has shown. A newer one is the game's answer to whatever was just done. */
export async function promptCount(page: Page): Promise<number> {
  return Number(await page.locator('#prompt').getAttribute('data-seq') ?? 0);
}

/**
 * Waits for a prompt newer than the count given, whose message matches when a pattern is given, and returns its
 * count. Waiting on the game's own next question, rather than on time, is what keeps a script in step with it.
 */
export async function nextPrompt(page: Page, after: number, message?: RegExp): Promise<number> {
  let seen = after;
  await expect(async () => {
    seen = await promptCount(page);
    expect(seen).toBeGreaterThan(after);
    if (message) await expect(page.locator('#prompt .message')).toHaveText(message, { timeout: 500 });
  }).toPass({ timeout: 30_000 });
  return seen;
}

/**
 * Waits until nothing on the page is moving. A card flying between zones is drawn over the board until it lands, so a
 * click aimed at the board while one passes lands on the card in flight. Endless effects, such as a glow, never end.
 */
export async function settled(page: Page): Promise<void> {
  await page.waitForFunction(() => !document.getAnimations().some(a =>
    a.playState === 'running' && a.effect?.getTiming().iterations !== Infinity));
}

/** Does something, then waits for the prompt that answers it and for the board to stop moving. */
export async function andThen(page: Page, act: () => Promise<void>, message?: RegExp): Promise<void> {
  const before = await promptCount(page);
  await act();
  await nextPrompt(page, before, message);
  await settled(page);
}

/**
 * Places a game state with dev mode's "Set up a game state" the first time this player holds priority on an empty
 * stack, since a trigger left on it would stay under the new board, and waits for the card named to be on the board. Auto-pass is turned off first: a pass the server asks for when there is nothing
 * to play would carry the game on past the phase the state names. Nothing is passed afterwards, so the player holds
 * priority in that phase. Each question before then is answered once, when it is asked.
 */
export async function setUpState(page: Page, state: string[], card: string): Promise<void> {
  await gameStarted(page);
  await flipOption(page, 'Dev mode');
  const autoPass = page.locator('#prompt .auto-pass.on');
  if (await autoPass.count()) await autoPass.click();
  await expect(autoPass).toHaveCount(0);
  const priority = page.locator('#phase-strip .pill.priority');
  const stack = page.locator('#stack:not([hidden])');
  const ready = async () => await priority.count() > 0 && await stack.count() === 0;
  let seen = await promptCount(page);
  for (let i = 0; i < 40 && !(await ready()); i++) {
    await answerDialogs(page);
    if (await page.locator('#prompt .ok').isEnabled()) await page.keyboard.press(' ');
    seen = await nextPrompt(page, seen);
  }
  expect(await ready()).toBe(true);
  await settled(page);
  await page.click('#prompt .more');
  await page.getByRole('menuitem', { name: 'Dev mode ›' }).click();
  await page.getByRole('menuitem', { name: 'Set up a game state…' }).click();
  await page.fill('.dev-state', state.join('\n'));
  await page.getByRole('button', { name: 'Set up', exact: true }).click();
  await expect(page.locator('#me .card, #opponent .card, #hand .card', { hasText: card }).first()).toBeVisible();
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
    [...row.querySelectorAll('.choice button.on')].map(b => b.textContent).join(''),
  ])));
  await page.keyboard.press('Escape');
  await expect(page.locator('#options')).toHaveCount(0);
  return values;
}

export async function flipOption(page: Page, label: string): Promise<void> {
  await page.click('#prompt .cog');
  // An on-or-off setting is two segments, so the one not chosen is the flip
  await page.locator('#options .setting', { hasText: label }).locator('.choice button:not(.on)').click();
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
