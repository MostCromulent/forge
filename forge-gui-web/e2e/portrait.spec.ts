import { expect, type Page } from '@playwright/test';
import { act, board, probe, PHONE, SMALL_PHONE, type Probe, type Table } from './probe';
import { hold } from './steps';

/** A board set up from a state arrives in pieces, and a card touched before the last piece lands is one about to be replaced. */
async function settledBoard(p: Probe, state: string, table?: Table): Promise<void> {
  await board(p, state, table);
  await p.page.waitForTimeout(1500);
}

const form = (page: Page) => page.evaluate(() => document.documentElement.dataset.form ?? '');

probe('a phone held upright gets the portrait form', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  expect(await form(p.page)).toBe('portrait');
}, PHONE);

probe('a desktop window does not', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  expect(await form(p.page)).toBe('');
});

// A tablet on its side keeps the desktop layout, and is still touched, not pointed at
const WIDE_TOUCH = { viewport: { width: 1280, height: 800 }, hasTouch: true };

probe('a tap on a card opens no hover preview', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  // Your own land: a tap on it acts, so nothing but a hover could open a preview
  const card = p.page.locator('#me .battlefield .card').first();
  await card.tap();
  // A phone's browser follows a tap with the mouse events a pointer would have made, which Chromium's emulation leaves out
  await card.dispatchEvent('mouseenter');
  await p.page.waitForTimeout(600);
  // The preview takes one of these the first time it draws a card, and keeps it after it closes
  await expect(p.page.locator('#zoom')).not.toHaveClass(/image-only|text-card/);
}, WIDE_TOUCH);

probe('a long-press asks for no ability menu', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  // A right-click asks the host what the card can do; a rested finger must not, on any browser
  await p.page.evaluate(() => {
    const seen = window as unknown as { menus: number };
    seen.menus = 0;
    const send = window.forge.actions.selectCard;
    window.forge.actions.selectCard = (key, menu, x, y) => { if (menu) seen.menus++; send(key, menu, x, y); };
  });
  const card = p.page.locator('#opponent .card').first();
  await hold(p.page, card);
  await card.dispatchEvent('contextmenu', { bubbles: true, cancelable: true });
  expect(await p.page.evaluate(() => (window as unknown as { menus: number }).menus)).toBe(0);
}, WIDE_TOUCH);

const box = async (page: Page, sel: string) => (await page.locator(sel).boundingBox())!;

probe('the prompt is a dock with OK and Cancel in opposite corners', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  const dock = await box(p.page, '#prompt');
  const ok = await box(p.page, '#prompt .ok');
  const cancel = await box(p.page, '#prompt .cancel');
  expect(dock.y + dock.height).toBeLessThanOrEqual(664);
  expect(dock.width).toBe(390);
  expect(ok.x).toBeLessThan(8);
  expect(cancel.x + cancel.width).toBeGreaterThan(382);
  expect(ok.height).toBeGreaterThanOrEqual(44);
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}, PHONE);

probe('the swap setting puts OK on the right', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.evaluate(() => localStorage.setItem('forge.settings', JSON.stringify({ ...JSON.parse(localStorage.getItem('forge.settings') ?? '{}'), swapPrompt: true })));
  await p.page.reload({ waitUntil: 'domcontentloaded' });
  await expect(p.page.locator('#prompt .ok')).toBeVisible();
  expect((await box(p.page, '#prompt .ok')).x).toBeGreaterThan(280);
}, PHONE);

// Different names, so the cards stand apart rather than folding into piles
const CROWD = 'humanbattlefield=' + ['Grizzly Bears', 'Runeclaw Bear', 'Hill Giant', 'Gray Ogre', 'Goblin Piker', 'Raging Goblin', 'Craw Wurm', 'Scathe Zombies',
  'Ironroot Treefolk', 'Giant Spider', 'Wall of Wood', 'Elvish Warrior', 'Forest', 'Forest', 'Forest', 'Forest'].join(';')
  + '\naibattlefield=' + ['Hill Giant', 'Gray Ogre', 'Goblin Piker', 'Raging Goblin', 'Craw Wurm', 'Scathe Zombies', 'Giant Spider', 'Wall of Wood', 'Mountain', 'Mountain'].join(';');

for (const [name, phone] of [['390', PHONE], ['360', SMALL_PHONE]] as const) {
  probe(`a crowded board keeps its cards 40px wide at ${name}`, async p => {
    await board(p, CROWD);
    await p.page.waitForTimeout(1200);
    const narrow = await p.page.evaluate(() => Math.min(...[...document.querySelectorAll<HTMLElement>('#match .battlefield .card')]
      .filter(c => !c.classList.contains('tapped')).map(c => c.getBoundingClientRect().width)));
    expect(narrow).toBeGreaterThanOrEqual(39.5);
    expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(phone.viewport!.width);
    for (const bar of ['#opponent .player', '#me .player']) expect((await box(p.page, bar)).height).toBeLessThanOrEqual(60);
    const dock = await box(p.page, '#prompt');
    expect(dock.y + dock.height).toBeLessThanOrEqual(phone.viewport!.height);
  }, phone);
}

probe('a tap on the phase strip opens stops as a sheet above the dock, and Back closes it', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  const strip = await box(p.page, '#phase-strip .pill');
  expect(strip.x + strip.width).toBeLessThanOrEqual(390);
  expect(strip.height).toBeGreaterThanOrEqual(44);
  await p.page.locator('#phase-strip .pill').tap();
  const stops = p.page.locator('#phase-strip .stops');
  await expect(stops).toBeVisible();
  const sheet = await box(p.page, '#phase-strip .stops');
  const dock = await box(p.page, '#prompt');
  expect(sheet.width).toBe(390);
  expect(Math.round(sheet.y + sheet.height)).toBeLessThanOrEqual(Math.round(dock.y) + 1);
  for (const cell of await stops.locator('.cell').all()) {
    const b = (await cell.boundingBox())!;
    expect(Math.min(b.width, b.height)).toBeGreaterThanOrEqual(44);
  }
  await expect(stops.locator('.until-row button')).toHaveCount(5);
  await p.page.goBack();
  await expect(stops).toBeHidden();
  await expect(p.page.locator('#match')).toBeVisible();
}, PHONE);

probe('a long-press on a card opens its details as a sheet above the dock', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  await hold(p.page, p.page.locator('#opponent .card').first());
  const zoom = p.page.locator('#zoom.sheet');
  await expect(zoom).toBeVisible();
  await expect(zoom.locator('.name')).toHaveText('Grizzly Bears');
  const sheet = await box(p.page, '#zoom');
  const dock = await box(p.page, '#prompt');
  expect(sheet.width).toBe(390);
  expect(Math.round(sheet.y + sheet.height)).toBeLessThanOrEqual(Math.round(dock.y) + 1);
  await zoom.locator('.sheet-close').tap();
  await expect(zoom).toBeHidden();
}, PHONE);

probe('a long-press inspects and does not play', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  const forest = p.page.locator('#me .battlefield .card').first();
  await hold(p.page, forest);
  await expect(p.page.locator('#zoom.sheet')).toBeVisible();
  await p.page.waitForTimeout(600);
  // A tap would have tapped the land for mana
  await expect(forest).not.toHaveClass(/tapped/);
}, PHONE);

probe('a tap on an opponent\'s card with nothing asked opens its details', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  await p.page.locator('#opponent .card').first().tap();
  await expect(p.page.locator('#zoom.sheet .name')).toHaveText('Grizzly Bears');
}, PHONE);

probe('a prompt that picks cards closes the sheet', async p => {
  await settledBoard(p, 'humanhand=Lightning Bolt\nhumanbattlefield=Mountain\naibattlefield=Grizzly Bears');
  await p.page.locator('#opponent .card').first().tap();
  await expect(p.page.locator('#zoom.sheet')).toBeVisible();
  const bolt = await p.page.evaluate(() => Number(document.querySelector<HTMLElement>('#hand .card')?.dataset.key));
  await act(p.page, 'selectCard', bolt, false, 0, 0);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await expect(p.page.locator('#zoom.sheet')).toBeHidden();
  await expect(p.page.locator('#prompt .cancel')).toBeVisible();
}, PHONE);

probe('on a wide touch screen a long-press opens a panel that can be closed', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  await hold(p.page, p.page.locator('#opponent .card').first());
  const zoom = p.page.locator('#zoom.sheet');
  await expect(zoom).toBeVisible();
  const at = await box(p.page, '#zoom');
  expect(at.x).toBeGreaterThan(0);
  expect(at.x + at.width).toBeLessThan(1280);
  await zoom.locator('.sheet-close').tap();
  await expect(zoom).toBeHidden();
}, WIDE_TOUCH);
