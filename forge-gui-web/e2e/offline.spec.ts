import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseDeck, concede, enterName, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

test('a new player names themselves, hosts a game against the computer, and plays it', async ({ page }) => {
  await page.goto(server.url);
  await expect(page.getByText('What should we call you?')).toBeVisible();
  await enterName(page, 'Alice');

  await hostTable(page, false);
  const seats = page.locator('#seats .plate');
  await expect(seats).toHaveCount(2);
  // Escape closes the deck finder from its search box without choosing anything
  await seats.nth(0).locator('.sleeve').click();
  await expect(page.locator('.finder .find')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.locator('.finder')).toHaveCount(0);

  await chooseDeck(page, seats.nth(0));
  await chooseDeck(page, seats.nth(1));
  await expect(page.locator('#play')).toBeEnabled();

  // As the lobby says, Enter starts the match
  await page.keyboard.press('Enter');
  await expect(page.locator('#match')).toBeVisible();
  await expect(page.locator('#me')).toContainText('Alice');
  await expect(page.locator('#prompt .message')).not.toBeEmpty();

  // A reload lands back in the same match, known by the same name, without being asked again
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.locator('#match')).toBeVisible();
  await expect(page.locator('#player-name')).toHaveCount(0);
  await expect(page.locator('#me')).toContainText('Alice');

  // Quitting a finished game goes back to a fresh table under the same name, and a second match starts from it
  await concede(page);
  await page.locator('#game-over button', { hasText: 'Quit match' }).click();
  await expect(page.locator('#lobby')).toBeVisible();
  await chooseDeck(page, seats.nth(0));
  await chooseDeck(page, seats.nth(1));
  await page.keyboard.press('Enter');
  await expect(page.locator('#match')).toBeVisible();
  await expect(page.locator('#me')).toContainText('Alice');
});

test('the sound is turned down from a control beside the options, not from the options list', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  const seats = page.locator('#seats .plate');
  await chooseDeck(page, seats.nth(0));
  await chooseDeck(page, seats.nth(1));
  await page.keyboard.press('Enter');
  await expect(page.locator('#match')).toBeVisible();

  // A match that has just opened resets what is open over the board, so wait until the game asks something
  await expect(page.locator('#prompt .message')).not.toBeEmpty();
  await page.locator('#prompt .volume').click();
  const music = page.locator('#volume .volume-row', { hasText: 'Music' }).locator('input');
  await music.fill('0');
  await expect(page.locator('#volume .volume-row', { hasText: 'Music' })).toContainText('Off');
  await page.locator('#volume .volume-row', { hasText: 'Effects' }).locator('input').fill('0');
  await expect(page.locator('#prompt .volume')).toHaveAttribute('data-silent', 'true');
  await page.screenshot({ path: test.info().outputPath('volume.png'), timeout: 10_000 }).catch(() => {});
  await page.keyboard.press('Escape');
  await expect(page.locator('#volume')).toHaveCount(0);

  // The setting outlives a reload, and the options list no longer carries it
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.locator('#prompt .volume')).toHaveAttribute('data-silent', 'true');
  await page.locator('#prompt .cog').click();
  await expect(page.locator('#options')).toBeVisible();
  await expect(page.locator('#options .rows')).not.toContainText('Music');
});

// Priority passing by itself used to happen out of sight: now the pass button fills first, and Escape stops it
test('a pass on its way fills the pass button, and stopping it gives priority back', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, false);
  const seats = page.locator('#seats .plate');
  await chooseDeck(page, seats.nth(0));
  await chooseDeck(page, seats.nth(1));
  // The computer comes with a name of its own, and the host can give it another
  const bot = seats.nth(1).locator('.who-name');
  await expect(bot).not.toHaveText(/^(Computer|Forge AI)?$/);
  await bot.fill('Rival');
  await bot.press('Enter');
  await expect(bot).toHaveText('Rival');
  await page.keyboard.press('Enter');
  await expect(page.locator('#prompt .message')).not.toBeEmpty();
  // The board says who goes first, or, to a player who won the toss, that they did before they choose
  const opening = page.locator('#first-reveal p');
  for (let i = 0; i < 20 && !(await opening.count()); i++) {
    if (await page.locator('#prompt .ok').isEnabled()) await page.keyboard.press(' ');
    await page.waitForTimeout(300);
  }
  await expect(opening).toHaveText(/^(You go first|Rival goes first|You won the coin toss)$/i);
  await page.screenshot({ path: test.info().outputPath('opening.png'), timeout: 10_000 }).catch(() => {});
  await page.locator('#prompt .auto-pass').click();
  await expect(page.locator('#prompt .auto-pass')).toHaveClass(/\bon\b/);

  // Answer whatever the game asks until the computer has done something and a pass is on its way
  const passing = page.locator('#prompt.auto-passing');
  const playOnUntilPassing = async () => {
    for (let i = 0; i < 240 && !(await passing.count()); i++) {
      if (/discard/i.test(await page.locator('#prompt .message').textContent() ?? '')) {
        await page.locator('#hand .card').first().click();
      } else if (await page.locator('#prompt .ok').isEnabled()) {
        await page.keyboard.press(' ');
      }
      await page.waitForTimeout(250);
    }
    await expect(passing).toBeVisible();
  };
  await playOnUntilPassing();
  await expect(page.locator('#prompt .ok')).toHaveClass(/filling/);
  await page.screenshot({ path: test.info().outputPath('passing.png'), timeout: 10_000 }).catch(() => {});

  await page.keyboard.press('Escape');
  await expect(passing).toHaveCount(0);
  await expect(page.locator('#prompt .ok')).toBeEnabled();
  await expect(page.locator('#prompt .ok .label')).not.toHaveText('Pass');

  // Left alone, the next one goes ahead by itself
  await playOnUntilPassing();
  await expect(passing).toHaveCount(0, { timeout: 5_000 });
});
