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
export async function newAdventure(page: Page, url = server.url): Promise<void> {
  await page.goto(url);
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

/** Whether the canvas has drawn anything other than its black background. */
const painted = (page: Page) => page.locator('[data-testid=adventure-world]').evaluate((c: HTMLCanvasElement) => {
  const data = c.getContext('2d')!.getImageData(0, 0, c.width, c.height).data;
  for (let i = 0; i < data.length; i += 4 * 97) if (data[i] + data[i + 1] + data[i + 2] > 0) return true;
  return false;
});

// Fails if a new adventure cannot be made from the start page, or its world map is not drawn
test('a new adventure starts on its world map', async ({ page }) => {
  await newAdventure(page);
  await expect(page.locator('[data-testid=adventure-world]')).toBeVisible({ timeout: 90_000 });
  await expect.poll(() => painted(page), { timeout: 30_000 }).toBe(true);
});
