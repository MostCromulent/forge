import { expect, type Page } from '@playwright/test';
import { act, explain, lobby, probe, untilPriority, type Probe } from './probe';
import { gameStarted } from './steps';

// Two-Headed Giant: four seats in two fixed pairs, and a table split into four equal quarters with your team along the bottom

const seatTeams = (page: Page) => page.evaluate(() => window.forge.model.lobby!.seats.map(s => s.team));

/** Turns the variant on at a two-seat table and gives every seat a deck, so the table can start. */
async function giantTable(p: Probe): Promise<void> {
  const page = p.page;
  await lobby(p);
  await page.evaluate(() => window.forge.actions.setVariant('TwoHeadedGiant', true));
  await p.until('four paired seats', () => window.forge.model.lobby?.fixedPairs && window.forge.model.lobby.seats.length === 4);
  for (let tries = 0; ; tries++) {
    const ready = await page.evaluate(() => {
      const { actions, model } = window.forge;
      const table = model.lobby!;
      if (table.canStart) return true;
      const legal = model.decks.filter(d => !d.generated && !d.problem).sort((a, b) => a.name.localeCompare(b.name));
      table.seats.forEach((seat, i) => { if (!seat.deckName && legal[i]) actions.setSeat(i, { deck: legal[i].key }); });
      return false;
    });
    if (ready) return;
    if (tries > 30) throw new Error(`The table never became ready. The page: ${await explain(page)}`);
    await page.waitForTimeout(700);
  }
}

probe('turning Two-Headed Giant on sets four seats in two locked teams, one per column', async p => {
  const page = p.page;
  await giantTable(p);
  expect(await seatTeams(page)).toEqual([0, 0, 1, 1]);
  // The seat count and the teams cannot change while the variant is on
  await page.evaluate(() => { window.forge.actions.setPlayerCount(3); window.forge.actions.setTeam(1, 1); });
  await page.waitForTimeout(600);
  expect(await page.evaluate(() => window.forge.model.lobby!.seats.length)).toBe(4);
  expect(await seatTeams(page)).toEqual([0, 0, 1, 1]);
  await expect(page.locator('.add-seat')).toHaveCount(0);
  await expect(page.locator('button.team-tab')).toHaveCount(0);
  // Each team fills a column: seats 1 and 2 on the left, 3 and 4 on the right
  const plates = await page.locator('#seats .plate').evaluateAll(els => els.map(el => Math.round(el.getBoundingClientRect().x)));
  expect(plates[0]).toBe(plates[1]);
  expect(plates[2]).toBe(plates[3]);
  expect(plates[2]).toBeGreaterThan(plates[0]);
  // Turning it off frees the table again
  await page.evaluate(() => window.forge.actions.setVariant('TwoHeadedGiant', false));
  await p.until('teams cleared', () => window.forge.model.lobby?.teams === 0 && !window.forge.model.lobby.fixedPairs);
});

probe('a Two-Headed Giant match shows four equal quarters, with the teammate drawn like you and their hand dealt face up', async p => {
  const page = p.page;
  await giantTable(p);
  await act(page, 'startMatch', false);
  await gameStarted(page);
  // Answers who starts and keeps the opening hand. The hands are dealt once who goes first has been shown
  await untilPriority(page);
  await p.until('both hands dealt', () => !document.getElementById('first-reveal')
    && document.querySelectorAll('#hand .card').length === 7 && document.querySelectorAll('.mate-hand .card').length === 7, undefined, page, 60_000);
  await page.waitForTimeout(1500);
  const table = await page.evaluate(() => {
    const box = (el: Element) => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; };
    const seats = [...document.querySelectorAll('#opponent > .seat')];
    return {
      quads: document.getElementById('match')!.classList.contains('quads'),
      me: box(document.getElementById('me')!),
      seats: seats.map(box),
      // Each seat is on screen, not covered and not hidden: the point just inside its top left corner belongs to it
      drawn: seats.map(s => { const r = s.getBoundingClientRect(); return s.contains(document.elementFromPoint(r.x + 4, r.y + 4)); }),
      mate: seats.map(s => s.classList.contains('mate')),
      faceUp: [...document.querySelectorAll('.mate-hand .card')].filter(c => !c.classList.contains('back')).length,
      visible: [...document.querySelectorAll<HTMLElement>('.mate-hand .card')].filter(c => getComputedStyle(c).visibility === 'visible').length,
    };
  });
  expect(table.quads).toBe(true);
  expect(table.seats).toHaveLength(3);
  expect(table.drawn).toEqual([true, true, true]);
  // The teammate takes the quarter beside you, and the other team sits across the top
  expect(table.mate).toEqual([false, false, true]);
  const [q1, q2, q3] = table.seats;
  for (const q of [q1, q2, q3]) {
    expect(Math.abs(q.w - table.me.w)).toBeLessThan(2);
    expect(Math.abs(q.h - table.me.h)).toBeLessThan(2);
  }
  expect(Math.abs(q3.y - table.me.y)).toBeLessThan(2);
  expect(q3.x).toBeGreaterThan(table.me.x);
  expect(table.faceUp).toBe(7);
  expect(table.visible).toBe(7);
  // The phase pill names the team whose turn it is
  const owner = await page.locator('#phase-strip .owner b').textContent();
  expect(owner === "Your team's turn" || (owner ?? '').includes(' & ')).toBe(true);
}, { viewport: { width: 1600, height: 950 } });
