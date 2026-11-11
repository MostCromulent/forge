import { expect, test, type Page } from '@playwright/test';
import { PROBE_SEED, startServer, type Server } from './server';
import { enterName } from './steps';

let server: Server;
test.beforeEach(async () => {
  // Adventure reads its plane from the shared settings once, when it first starts
  server = await startServer(undefined, { ...PROBE_SEED, files: { 'adventure/settings.json': '{"plane":"Realm of Legends"}' } });
});
test.afterEach(async () => { await server.stop(); });

/** Opens Adventure from the start page and makes a new game on Realm of Legends, which offers only commander starter decks. */
async function newAdventure(page: Page): Promise<void> {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=adventure]');
  await page.getByText('New adventure', { exact: true }).first().click();
  await page.locator('.tile-choice', { hasText: 'Normal' }).click();
  await page.getByRole('button', { name: 'Commander', exact: true }).click();
  await page.getByRole('button', { name: 'White', exact: true }).click();
  await page.getByRole('button', { name: 'Male', exact: true }).click();
  await page.getByRole('button', { name: 'Human', exact: true }).click();
  await page.getByPlaceholder('Name').fill('Tester');
  await page.locator('.setup').getByRole('button', { name: 'Start', exact: true }).click();
}

// Fails if a new adventure cannot be made from the start page or does not open its world map
test('a new adventure starts on its world map', async ({ page }) => {
  await newAdventure(page);
  await expect(page.locator('[data-testid=adventure-world]')).toBeAttached({ timeout: 90_000 });
});
