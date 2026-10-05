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
  // Quest reads its saves gzipped; one game a match, so a conceded game ends it
  const xml = readFileSync(fixture, 'utf8').replace('<matchLength>3</matchLength>', '<matchLength>1</matchLength>');
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
  await reveal.getByRole('button', { name: 'OK' }).click();
  await expect(reveal.locator('.cq-cards-card').first()).toBeVisible();
  await reveal.getByRole('button', { name: 'OK' }).click();
  await expect(reveal).toHaveCount(0);
  await expect(page.locator('.qu-status .cq-chip').nth(1)).toHaveText('1 Losses');
});
