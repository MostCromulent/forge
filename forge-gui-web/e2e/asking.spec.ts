import { expect, type Locator, type Page } from '@playwright/test';
import { board, passUntil, probe, type Probe } from './probe';

// A question about cards is laid out on the dimmed board, with the log, the stack and the prompt left to read

// At the start of your upkeep Arms Scavenger offers three cards from outside the game to draft, which only the card choice can show
const DRAFT = ['activeplayer=ai', 'activephase=END_OF_TURN', 'humanbattlefield=Arms Scavenger;Mountain',
  'humanlibrary=Forest;Forest;Forest;Forest;Forest;Forest', 'aibattlefield=Mountain', 'ailibrary=Mountain;Mountain;Mountain;Mountain'].join('\n');

async function drafting(p: Probe): Promise<Locator> {
  await board(p, DRAFT);
  await passUntil(p, 'the draft', () => !!document.querySelector('.backdrop.on-board .options .card'));
  return p.page.locator('.backdrop.on-board');
}

/** Whether a click at the middle of what the locator finds lands on it, rather than on something drawn over it. */
function onTop(page: Page, selector: string): Promise<boolean> {
  return page.evaluate(sel => {
    const el = document.querySelector<HTMLElement>(sel);
    const box = el?.getBoundingClientRect();
    if (!el || !box?.width) return false;
    return el.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2));
  }, selector);
}

probe('a card question dims the board, and the log, the stack and the prompt stay on top of it', async p => {
  const question = await drafting(p);
  await expect(question).toBeVisible();
  for (const lit of ['#log-panel', '#stack', '#prompt']) await expect.poll(() => onTop(p.page, lit), { message: lit }).toBe(true);
  await expect.poll(() => onTop(p.page, '#opponent')).toBe(false);
  // Centred in the board left of the stack, not in the window
  const cards = (await question.locator('.options').boundingBox())!;
  const stack = (await p.page.locator('#stack').boundingBox())!;
  expect(Math.abs(cards.x + cards.width / 2 - stack.x / 2)).toBeLessThan(40);
});

probe('Escape puts a card question away and keeps what was picked, and the OK key brings it back and answers it', async p => {
  const question = await drafting(p);
  const page = p.page;
  await question.locator('.options .card').first().click();
  await expect(question.locator('.card.picked')).toHaveCount(1);
  await page.keyboard.press('Escape');
  await expect(question).toBeHidden();
  await expect(page.locator('#prompt')).toHaveClass(/answering/);
  await page.keyboard.press(' ');
  await expect(question).toBeVisible();
  await expect(question.locator('.card.picked')).toHaveCount(1);
  await page.keyboard.press(' ');
  await expect(page.locator('.backdrop.on-board')).toHaveCount(0);
});

probe('the game menu opens above a card question', async p => {
  await drafting(p);
  await p.page.locator('#prompt .more').click();
  await expect(p.page.locator('.game-menu')).toBeVisible();
  await expect.poll(() => onTop(p.page, '.game-menu')).toBe(true);
});

probe('with the side column folded, the prompt still stays on top of a card question', async p => {
  const question = await drafting(p);
  for (const toggle of await p.page.locator('.side-toggle[aria-expanded="true"]').all()) await toggle.click();
  await expect(p.page.locator('#match')).toHaveClass(/side-folded/);
  await expect(question).toBeVisible();
  // The column slides off as it folds
  await expect.poll(() => onTop(p.page, '#prompt')).toBe(true);
});
