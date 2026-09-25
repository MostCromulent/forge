import { expect, test, type Page } from '@playwright/test';
import { startServer, type Server } from './server';
import { buildLimitedDeck, chooseDeck, enterName, inviteLink } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

/** The host opens a draft or sealed table from Play with friends. */
async function hostEvent(page: Page, kind: 'draft' | 'sealed'): Promise<void> {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=multiplayer]');
  await page.click(`.chooser [data-kind=${kind}]`);
  await expect(page.locator('.event-panel .wiz')).toBeVisible({ timeout: 30_000 });
}

async function ready(page: Page): Promise<void> {
  await page.click('.plate.mine .ready-toggle');
  await expect(page.locator('.plate.mine .ready-toggle')).toContainText('Ready');
}

/** Builds forty cards from the pool in the limited editor, then puts the event deck on the seat. */
async function buildAndSit(page: Page): Promise<void> {
  await expect(page.locator('#editor')).toBeVisible({ timeout: 90_000 });
  await buildLimitedDeck(page);
  await page.click('.editor-head button.primary');
  await expect(page.locator('#lobby')).toBeVisible();
  await chooseDeck(page, page.locator('.plate.mine'));
}

// Fails if any step of an online sealed event is unreachable: the friends chooser, the event form, a guest readying
// up, both pools opening in the editor, the event decks on the seats, and the match
test('online sealed with a guest', async ({ page, browser }) => {
  test.setTimeout(480_000);
  await hostEvent(page, 'sealed');
  await page.click('.tile-choice:has-text("Full card pool")');
  await page.click('.stp-open button:has-text("Continue")');
  await page.click('.wfoot button:has-text("Save event")');
  await expect(page.locator('.event-panel .event-product')).toContainText('Full');

  const guest = await (await browser.newContext()).newPage();
  await guest.goto(await inviteLink(page, server.url));
  await enterName(guest, 'Bea');
  await expect(page.locator('#seats')).toContainText('Bea');
  await ready(guest);
  await ready(page);

  await page.click('.event-panel button:has-text("Open packs")');
  await buildAndSit(page);
  await buildAndSit(guest);
  await expect(page.locator('#play')).toBeEnabled({ timeout: 30_000 });
  await page.click('#play');
  for (const p of [page, guest]) {
    await expect(p.locator('#match')).toBeVisible({ timeout: 60_000 });
    await expect(p.locator('#prompt .message')).not.toBeEmpty({ timeout: 60_000 });
  }
});

// Fails if a host alone cannot draft a full pod online: the table rules, 45 picks while computer drafters fill the
// other seats, and the pool opening in the editor
test('online draft for one', async ({ page }) => {
  test.setTimeout(600_000);
  await hostEvent(page, 'draft');
  await page.click('.tile-choice:has-text("Full card pool")');
  await expect(page.locator('.table-rules')).toBeVisible();
  // One pick per pass, so the draft is exactly 45 picks
  await page.selectOption('.table-rules select >> nth=0', 'NEVER');
  await page.selectOption('.table-rules select >> nth=1', '90');
  await page.click('.table-rules button:has-text("Continue")');
  await page.click('.wfoot button:has-text("Save event")');
  await expect(page.locator('.event-panel .event-product')).toContainText('Full');
  await ready(page);
  await page.click('.event-panel button:has-text("Start draft")');
  await expect(page.locator('#drafting')).toBeVisible({ timeout: 60_000 });
  await expect(page.locator('.dial-clock')).toBeVisible();

  const picked = page.locator('.draft-picks-head h3 .muted');
  for (let i = 0; i < 45; i++) {
    const first = page.locator('.draft-pack .draft-slot .tile').first();
    await first.click();
    await first.click();
    // The last pick ends the draft, and the pool's editor takes the screen straight away
    if (i < 44) await expect(picked).toHaveText(String(i + 1), { timeout: 30_000 });
  }
  await expect(page.locator('#editor')).toBeVisible({ timeout: 90_000 });
  await expect(page.locator('.check-fixed')).toContainText('Limited');
});
