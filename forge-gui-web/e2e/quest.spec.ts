import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { expect, test } from '@playwright/test';
import { PROBE_SEED, startServer, type Server } from './server';
import { answerDialogs, concede, enterName, gameStarted } from './steps';

const fixture = join(dirname(fileURLToPath(import.meta.url)), '../src/test/resources/quest/Fixture_quest.xml');

let server: Server;
test.beforeEach(async () => {
  // Quest reads its saves gzipped; one game a match, so a conceded game ends it; three copies of one card, to see a deck take each one owned; credits for a booster
  const xml = readFileSync(fixture, 'utf8').replace('<matchLength>3</matchLength>', '<matchLength>1</matchLength>')
    .replace('<card c="Naya Hushblade" s="ARB" i="1" n="1"/>', '<card c="Naya Hushblade" s="ARB" i="1" n="3"/>')
    .replace('<credits>250</credits>', '<credits>5000</credits>');
  server = await startServer(undefined, { ...PROBE_SEED, files: { 'quest/saves/Fixture quest.dat': gzipSync(xml) } });
});
test.afterEach(async () => { await server.stop(); });

// Fails if a quest cannot be opened, a duel fought and conceded, and the page come back to the Duels with the loss shown
test('a quest duel is conceded and the loss is shown on the Duels page', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=quest]');
  await page.locator('.qu-save', { hasText: 'Fixture quest' }).getByRole('button', { name: 'Play' }).click();

  await expect(page.locator('.qu-ev')).toHaveCount(4);
  await expect(page.locator('.qu-status .cq-chip').nth(1)).toHaveText('0 Losses');
  await page.getByRole('button', { name: 'Start Duel' }).click();

  await expect(page.locator('#match')).toBeVisible({ timeout: 60_000 });
  await gameStarted(page);
  await concede(page);
  await expect(page.locator('#game-over .word')).toHaveText('Defeat');
  // Easy quests give a booster for a loss too, and its format is asked over the result screen
  await page.locator('.host-choice .host-option').first().click({ timeout: 30_000 });
  await answerDialogs(page);
  await page.locator('#game-over button', { hasText: 'OK' }).click();

  // The match's results, then the booster, are revealed over the Duels page
  const reveal = page.locator('.cq-reveal');
  await expect(reveal.locator('.cq-gift h3')).toHaveText('Gameplay Results');
  await reveal.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(reveal.locator('.cq-cards-card').first()).toBeVisible();
  await reveal.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(reveal).toHaveCount(0);
  await expect(page.locator('.qu-status .cq-chip').nth(1)).toHaveText('1 Losses');
});

// Fails if a deck cannot be made, edited to forty cards and chosen as current from the Decks tab, or the editor does not take each copy owned and no more
test('a quest deck is made, filled and chosen as current', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=quest]');
  await page.locator('.qu-save', { hasText: 'Fixture quest' }).getByRole('button', { name: 'Play' }).click();

  await page.locator('.cq-tab', { hasText: 'Quest Decks' }).click();
  await page.getByRole('button', { name: 'New Deck' }).click();
  await page.getByRole('textbox', { name: 'Deck Name' }).fill('Ice deck');
  await page.getByRole('button', { name: 'Create' }).click();

  await expect(page.locator('#editor')).toBeVisible();
  await expect(page.locator('#editor .side-zone')).toBeVisible();
  const owned = page.locator('.slot[data-card="Naya Hushblade"]');
  const plus = owned.locator('.step').last();
  for (let i = 1; i <= 3; i++) {
    await plus.click();
    await expect(owned.locator('.badge')).toHaveText(String(i));
  }
  await expect(plus).toBeDisabled();
  await page.evaluate(() => (window as any).forge.actions.edit({ op: 'lands', lands: [{ name: 'Island', count: 37 }] }));
  await expect(page.locator('.main-zone > h4 .count')).toHaveText('40');
  await page.getByRole('button', { name: 'Done' }).click();

  const plate = page.locator('.cq-cmd', { hasText: 'Ice deck' });
  await expect(plate).toContainText('40 cards');
  await plate.click();
  await page.locator('.cq-side').getByRole('button', { name: 'Current Deck' }).click();
  await expect(plate.locator('.tag')).toHaveText('Current Deck');
  await page.locator('.cq-tab', { hasText: 'Duels' }).click();
  await expect(page.locator('.cq-mine b')).toHaveText('Ice deck');
});

// Fails if a booster cannot be bought and its cards revealed, or the credits do not fall by its price
test('a booster is bought in the Spell Shop and its cards revealed', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=quest]');
  await page.locator('.qu-save', { hasText: 'Fixture quest' }).getByRole('button', { name: 'Play' }).click();

  const credits = page.locator('.cq-purse .cq-coin').first().locator('b');
  const before = Number((await credits.innerText()).replace(/\D/g, ''));
  await page.locator('.cq-tab', { hasText: 'Spell Shop' }).click();
  const booster = page.locator('.qu-product', { hasText: 'Booster Pack' }).first();
  const price = Number((await booster.locator('.qu-price').innerText()).replace(/\D/g, ''));
  expect(price).toBeLessThanOrEqual(before);
  await booster.click();
  await page.locator('.cq-sel-bar .primary').click();
  await page.locator('.cq-exile').getByRole('button', { name: 'OK', exact: true }).click();

  const reveal = page.locator('.cq-reveal');
  await expect(reveal.locator('.cq-cards-card').first()).toBeVisible();
  await reveal.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(reveal).toHaveCount(0);
  await expect(credits).toHaveText((before - price).toLocaleString('en-GB'));
});

// Fails if a quest cannot be started from the form and lands on its Duels page with the difficulty's credits
test('a quest is started from the new-quest form', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=quest]');
  await page.getByRole('button', { name: /New quest/ }).click();

  const tile = (text: string) => page.locator('.stp-open .tile-choice', { hasText: text }).first();
  await tile('Trained').click();
  await tile('Fantasy Mode').click();
  await page.locator('.stp-open .pick-list button').filter({ hasText: /^Main world$/ }).click();
  await tile('Unrestricted').click();
  await page.locator('.stp-open').getByRole('button', { name: 'Continue' }).click();
  await tile('Same as starting pool').click();
  await page.getByRole('textbox', { name: 'Quest Name' }).fill('Form quest');
  await page.getByRole('button', { name: 'Embark!' }).click();

  await expect(page.locator('.qu-ev').first()).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('.cq-id b')).toHaveText('Form quest');
  await expect(page.locator('.cq-purse .cq-coin').first().locator('b')).toHaveText('200');
});

// Fails if a world with sets of its own still asks for a starting pool its sets replace
test('a world with its own sets goes straight to the distribution', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=quest]');
  await page.getByRole('button', { name: /New quest/ }).click();
  await page.locator('.stp-open .tile-choice', { hasText: 'Trained' }).click();
  await page.locator('.stp-open .tile-choice', { hasText: 'Fantasy Mode' }).click();
  await page.locator('.stp-open input[type=search]').fill('Ravnica');
  await page.locator('.stp-open .pick-list button').filter({ hasText: /^Ravnica$/ }).click();
  await expect(page.locator('.stp-open .q b')).toHaveText('Starting pool distribution');
  await expect(page.locator('.ticket dd').nth(3)).toHaveText('Ravnica');
});

// Fails if a bazaar item cannot be bought from its stall and the credits do not fall by its price
test('a bazaar item is bought from its stall', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=quest]');
  await page.locator('.qu-save', { hasText: 'Fixture quest' }).getByRole('button', { name: 'Play' }).click();

  const credits = page.locator('.cq-purse .cq-coin').first().locator('b');
  await expect(credits).toHaveText('5,000');
  await page.locator('.cq-tab', { hasText: 'Bazaar' }).click();
  await page.locator('.qu-stall', { hasText: 'Pet Shop' }).click();
  const bird = page.locator('.qu-item', { hasText: 'Bird' });
  const price = Number((await bird.locator('button.primary').innerText()).replace(/\D/g, ''));
  await bird.locator('button.primary').click();
  await expect(credits).toHaveText((5000 - price).toLocaleString('en-GB'));
  // A pet bought is offered for duels; a level-0 quest may raise no pet past level 1, so it leaves the stall
  await page.locator('.cq-tab', { hasText: 'Duels' }).click();
  await expect(page.locator('.qu-row select option', { hasText: 'Bird' })).toHaveCount(1);
});
