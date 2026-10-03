import { expect, type Page } from '@playwright/test';
import { board, probe, PHONE } from './probe';
import { hold } from './steps';

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
  await board(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  const card = p.page.locator('#opponent .card').first();
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
