import { expect, test, type Page } from '@playwright/test';
import { act, board, lobby, passUntil, probe, setState, PHONE, SMALL_PHONE, type Probe, type Table } from './probe';
import { enterName, hold } from './steps';

// A board set up through dev mode sometimes lands a moment late on a busy machine, and a tap or hold made meanwhile goes nowhere
test.describe.configure({ retries: 2 });

/** A board set up from a state arrives in pieces, and a card touched before the last piece lands is one about to be replaced. */
async function settledBoard(p: Probe, state: string, table?: Table): Promise<void> {
  await board(p, state, table);
  await p.page.waitForTimeout(1500);
}

/** Plays the first card in hand, as a tap on it would, again if the page was still showing a new turn's banner and held the tap back. */
async function castFirstInHand(p: Probe): Promise<void> {
  for (let tries = 0; tries < 6; tries++) {
    const key = await p.page.evaluate(() => Number(document.querySelector<HTMLElement>('#hand .card')?.dataset.key));
    await act(p.page, 'selectCard', key, false, 0, 0);
    const asked = await p.page.waitForFunction(() => {
      const prompt = window.forge.model.prompt;
      return !!prompt && (!prompt.priority || prompt.selectable.length > 0 || prompt.selectablePlayers.length > 0);
    }, undefined, { timeout: 1500 }).then(() => true, () => false);
    if (asked) return;
  }
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
    for (const bar of ['#opponent .player', '#me .player']) expect((await box(p.page, bar)).height).toBeLessThanOrEqual(88);
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
  await castFirstInHand(p);
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

probe('the hand opens as a drawer, a card is played from it, and it closes for the target', async p => {
  await settledBoard(p, 'humanhand=Lightning Bolt;Forest;Giant Growth\nhumanbattlefield=Mountain\naibattlefield=Grizzly Bears');
  const strip = await box(p.page, '#hand');
  expect(strip.height).toBeLessThanOrEqual(66);
  await p.page.locator('#hand').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  const drawer = await box(p.page, '#hand');
  const dock = await box(p.page, '#prompt');
  expect(Math.round(drawer.y + drawer.height)).toBeLessThanOrEqual(Math.round(dock.y) + 1);
  const bolt = p.page.locator('#hand .card', { hasText: 'Lightning Bolt' });
  expect((await bolt.boundingBox())!.width).toBeGreaterThanOrEqual(90);
  await bolt.tap();
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await expect(p.page.locator('#hand.sheet')).toHaveCount(0);
  await expect(p.page.locator('#opponent .card.selectable')).toBeVisible();
  // Called off, with the card still in hand, the drawer comes back
  await p.page.locator('#prompt .cancel').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  await p.page.locator('#hand-head .close').tap();
  await expect(p.page.locator('#hand.sheet')).toHaveCount(0);
}, PHONE);

probe('your bar and the costs over your hand do not overlap', async p => {
  await settledBoard(p, 'humanhand=Giant Growth;Lightning Bolt;Counterspell\nhumanbattlefield=Forest;Forest;Grizzly Bears;Hill Giant\naibattlefield=Mountain');
  const bar = await box(p.page, '#me .player');
  const hand = await box(p.page, '#hand');
  expect(Math.round(bar.y + bar.height)).toBeLessThanOrEqual(Math.round(hand.y) + 1);
  const cost = (await p.page.locator('#hand .card .cost-badge:not(:empty)').first().boundingBox())!;
  expect(cost.y).toBeGreaterThanOrEqual(hand.y);
  // A cost's symbol fills its pip, so it cannot sit off-centre in it
  const pip = (await p.page.locator('#hand .card .cost-badge .pip').first().boundingBox())!;
  const sym = (await p.page.locator('#hand .card .cost-badge .sym').first().boundingBox())!;
  expect(Math.abs(pip.width - sym.width)).toBeLessThan(1.5);
}, PHONE);

probe('a bar keeps its zones as counts, and a tap opens them', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\nhumangraveyard=Grizzly Bears;Hill Giant\naibattlefield=Mountain');
  for (const bar of ['#opponent .player', '#me .player']) expect((await box(p.page, bar)).height).toBeLessThanOrEqual(88);
  await expect(p.page.locator('#me .zone-tile').first()).toBeHidden();
  const pill = p.page.locator('#me .zones-pill');
  await expect(pill.locator('[data-zone="Graveyard"] b')).toHaveText('2');
  // The counts are the table's outer edge: under your portrait, over the opponent's
  const mine = (await pill.boundingBox())!;
  expect(mine.y).toBeGreaterThanOrEqual((await box(p.page, '#me .avatar')).y + 40);
  const theirs = await box(p.page, '#opponent .zones-pill');
  expect(theirs.y + theirs.height).toBeLessThanOrEqual((await box(p.page, '#opponent .avatar')).y + 1);
  await pill.tap();
  const tile = p.page.locator('#me .zone-tile[data-zone="Graveyard"]');
  await expect(tile).toBeVisible();
  const at = (await tile.boundingBox())!;
  expect(at.height / at.width).toBeGreaterThan(1.3);
  expect(at.x + at.width).toBeLessThanOrEqual(390);
  await tile.tap();
  await expect(p.page.locator('#zones .zone-panel')).toBeVisible();
  await expect(tile).toBeHidden();
}, PHONE);

probe('a spell on the stack shows as a chip under the strip, and its target wears its number', async p => {
  await settledBoard(p, 'humanhand=Lightning Bolt\nhumanbattlefield=Mountain\naibattlefield=Grizzly Bears');
  await castFirstInHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await p.page.locator('#opponent .card.selectable').first().tap();
  await p.until('the bolt is paid for or on the stack', () => !!window.forge.model.prompt?.paying || document.querySelectorAll('#stack .stack-item:not(.awaiting)').length > 0);
  if (await p.page.evaluate(() => !!window.forge.model.prompt?.paying)) await p.page.locator('#prompt .ok').tap();
  await p.until('the bolt is on the stack', () => document.querySelectorAll('#stack .stack-item:not(.awaiting)').length > 0, undefined, p.page, 20_000);
  const head = await box(p.page, '#stack .head');
  expect(head.height).toBeGreaterThanOrEqual(44);
  expect(head.x + head.width).toBeLessThanOrEqual(390);
  await expect(p.page.locator('#stack .head')).toContainText('Lightning Bolt');
  await expect(p.page.locator('#opponent .card .target-tag.top')).toHaveText('1');
  const dock = await box(p.page, '#prompt');
  const stack = await box(p.page, '#stack');
  expect(stack.y + stack.height).toBeLessThanOrEqual(dock.y + 1);
}, PHONE);

const FOUR = { players: 4 };

probe('three opponents are tabs, and a new permanent marks a hidden tab', async p => {
  await settledBoard(p, 'p0battlefield=Forest\np1battlefield=Mountain\np2battlefield=Island\np3battlefield=Swamp\nactiveplayer=p0', FOUR);
  const tabs = p.page.locator('#seat-tabs .seat-tab');
  await expect(tabs).toHaveCount(3);
  await expect(p.page.locator('#opponent .seat:not([hidden])')).toHaveCount(1);
  for (const tab of await tabs.all()) expect((await tab.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  const dock = await box(p.page, '#prompt');
  expect(dock.y + dock.height).toBeLessThanOrEqual(664);
  // Each seat is looked at once, so what it holds now is what was seen
  for (const n of [1, 2, 0]) await tabs.nth(n).tap();
  await expect(tabs.nth(0)).toHaveClass(/\bon\b/);
  // The dev state replaces the whole board, so every card in the hidden seats is new
  await setState(p, 'p0battlefield=Forest\np1battlefield=Mountain\np2battlefield=Island;Grizzly Bears\np3battlefield=Swamp\nactiveplayer=p0', FOUR);
  await expect(tabs.nth(1).locator('.new')).toHaveText('+2');
  await tabs.nth(1).tap();
  await expect(tabs.nth(1).locator('.new')).toBeEmpty();
  await expect(p.page.locator('#opponent .seat:not([hidden]) .card', { hasText: 'Grizzly Bears' })).toBeVisible();
}, PHONE);

probe('a hidden opponent can be chosen from the dock', async p => {
  await settledBoard(p, 'p0hand=Lightning Bolt\np0battlefield=Mountain\np1battlefield=Mountain\np2battlefield=Island\np3battlefield=Swamp\nactiveplayer=p0', FOUR);
  await castFirstInHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectablePlayers.length ?? 0) > 0);
  // Two of the three opponents are in hidden tabs, and each has a button in the dock
  await expect(p.page.locator('#prompt .choose-players button')).toHaveCount(2);
  await expect(p.page.locator('#seat-tabs .seat-tab.asked')).toHaveCount(2);
}, PHONE);

probe('a question about one hidden opponent\'s cards brings their tab forward', async p => {
  await settledBoard(p, 'p0hand=Murder\np0battlefield=Swamp;Swamp;Swamp\np1battlefield=Mountain\np2battlefield=Island;Hill Giant\np3battlefield=Swamp\nactiveplayer=p0', FOUR);
  const tabs = p.page.locator('#seat-tabs .seat-tab');
  // Looked away from the only player with a creature, in this same turn
  await tabs.nth(0).tap();
  await expect(tabs.nth(0)).toHaveClass(/\bon\b/);
  await castFirstInHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await expect(tabs.nth(1)).toHaveClass(/\bon\b/);
  await expect(p.page.locator('#opponent .seat:not([hidden]) .card.selectable')).toBeVisible();
}, PHONE);

probe('the menu sheet holds the log, and conceding asks twice', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.locator('#menu-button').tap();
  const sheet = p.page.locator('.menu-sheet');
  await expect(sheet).toBeVisible();
  await expect(sheet.locator('#log')).toBeVisible();
  const at = await box(p.page, '.menu-sheet');
  const dock = await box(p.page, '#prompt');
  expect(at.width).toBe(390);
  expect(Math.round(at.y + at.height)).toBeLessThanOrEqual(Math.round(dock.y) + 1);
  await sheet.locator('.concede').tap();
  await expect(p.page.locator('#game-over')).toBeHidden();
  await sheet.locator('.concede').tap();
  await expect(p.page.locator('#game-over')).toBeVisible();
}, PHONE);

probe('a graveyard opens as a sheet above the dock, and Back closes it', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\nhumangraveyard=Grizzly Bears;Hill Giant\naibattlefield=Mountain');
  await p.page.locator('#me .zones-pill').tap();
  await p.page.locator('#me .zone-tile[data-zone="Graveyard"]').tap();
  const panel = p.page.locator('#zones .zone-panel');
  await expect(panel).toBeVisible();
  const at = (await panel.boundingBox())!;
  const dock = await box(p.page, '#prompt');
  expect(at.width).toBe(390);
  expect(at.y + at.height).toBeLessThanOrEqual(dock.y + 2);
  await expect(p.page.locator('#prompt .ok')).toBeVisible();
  await p.page.goBack();
  await expect(panel).toBeHidden();
  await expect(p.page.locator('#match')).toBeVisible();
}, PHONE);

probe('Back with nothing open asks before leaving the match', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.goBack();
  await expect(p.page.locator('#leave-ask')).toBeVisible();
  await p.page.locator('#leave-ask .stay').tap();
  await expect(p.page.locator('#leave-ask')).toBeHidden();
  await expect(p.page.locator('#match')).toBeVisible();
  await expect(p.page.locator('#prompt .ok')).toBeVisible();
}, PHONE);

probe('a phone on its side is asked to turn upright', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await expect(p.page.locator('#turn-upright')).toBeVisible();
}, { viewport: { width: 664, height: 390 }, hasTouch: true, isMobile: true });

probe('the page can be installed to the home screen', async p => {
  await p.page.goto(p.server.url);
  const href = await p.page.locator('link[rel=manifest]').getAttribute('href');
  const answer = await p.page.request.get(new URL(href!, p.server.url).href);
  expect(answer.headers()['content-type']).toContain('application/manifest+json');
  const manifest = await answer.json();
  expect(manifest.display).toBe('standalone');
  expect(manifest.icons.map((i: { sizes: string }) => i.sizes)).toEqual(['192x192', '512x512']);
  const icon = await p.page.request.get(new URL(manifest.icons[1].src, p.server.url).href);
  expect(icon.status()).toBe(200);
  expect(icon.headers()['content-type']).toBe('image/png');
});

for (const [name, phone] of [['390', PHONE], ['360', SMALL_PHONE]] as const) {
  probe(`match setup fits a phone at ${name}`, async p => {
    await lobby(p);
    const { width, height } = phone.viewport!;
    expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    const play = await box(p.page, '#play');
    expect(play.y + play.height).toBeLessThanOrEqual(height);
    expect(play.height).toBeGreaterThanOrEqual(44);
    expect(play.width).toBeGreaterThan(width * 0.8);
    for (const plate of await p.page.locator('#seats .plate').all()) expect((await plate.boundingBox())!.width).toBeGreaterThan(width * 0.85);
    for (const b of await p.page.locator('.match-bar button:visible').all()) expect((await b.boundingBox())!.height).toBeGreaterThanOrEqual(40);
  }, phone);
}

probe('the start page and the deck editor do not scroll sideways on a phone', async p => {
  await p.page.goto(p.server.url);
  await enterName(p.page, 'Alice');
  await expect(p.page.locator('[data-mode=play]')).toBeVisible();
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
}, PHONE);

probe('a turn is played by touch: a land from the drawer, then an attack', async p => {
  await settledBoard(p, 'humanhand=Forest;Giant Growth\nhumanbattlefield=Forest;Grizzly Bears\naibattlefield=Mountain');
  for (const button of ['#prompt .ok', '#prompt .cancel']) {
    const at = await box(p.page, button);
    expect(at.y).toBeGreaterThanOrEqual(0);
    expect(at.y + at.height).toBeLessThanOrEqual(664);
  }
  await p.page.locator('#hand').tap();
  await p.page.locator('#hand.sheet .card', { hasText: 'Forest' }).tap();
  await p.until('the land is on the battlefield', () => document.querySelectorAll('#hand .card').length === 1);
  if (await p.page.locator('#hand.sheet').count()) await p.page.locator('#hand-head .close').tap();
  await passUntil(p, 'attackers are asked for', () => {
    const m = window.forge.model;
    return (m.objects.get(m.root) as { Phase?: string } | undefined)?.Phase === 'COMBAT_DECLARE_ATTACKERS' && !!m.prompt?.ok?.enabled;
  });
  await p.page.locator('#me .battlefield .card', { hasText: 'Grizzly Bears' }).tap();
  await expect(p.page.locator('#me .card.attacking')).toHaveCount(1);
  await p.page.locator('#prompt .ok').tap();
  await passUntil(p, 'the attack lands', () => [...window.forge.model.objects.values()].some(o => (o as { IsAI?: boolean }).IsAI && (o as { Life?: number }).Life === 18));
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await expect(p.page.locator('#zoom.placed')).toHaveCount(0);
}, PHONE);

probe('closing a sheet by its own button does not ask to leave the match', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  await p.page.locator('#opponent .card').first().tap();
  await p.page.locator('#zoom.sheet .sheet-close').tap();
  await expect(p.page.locator('#zoom.sheet')).toHaveCount(0);
  await p.page.waitForTimeout(400);
  await expect(p.page.locator('#leave-ask')).toBeHidden();
}, PHONE);

probe('a card in an open graveyard can be read, and the graveyard stays open under it', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\nhumangraveyard=Grizzly Bears;Hill Giant\naibattlefield=Mountain');
  await p.page.locator('#me .zones-pill').tap();
  await p.page.locator('#me .zone-tile[data-zone="Graveyard"]').tap();
  const card = p.page.locator('#zones .zone-panel .card', { hasText: 'Hill Giant' });
  await expect(card).toBeVisible();
  await hold(p.page, card);
  await expect(p.page.locator('#zoom.sheet .name')).toHaveText('Hill Giant');
  await p.page.locator('#zoom.sheet .sheet-close').tap();
  await expect(p.page.locator('#zones .zone-panel')).toBeVisible();
}, PHONE);

probe('a swipe down from a sheet\'s handle closes it', async p => {
  await settledBoard(p, 'humanhand=Forest;Giant Growth\nhumanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.locator('#hand').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  const head = await box(p.page, '#hand-head');
  const x = Math.round(head.x + 60);
  const y = Math.round(head.y + 12);
  const cdp = await p.page.context().newCDPSession(p.page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  for (const dy of [30, 70, 110]) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y + dy }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await expect(p.page.locator('#hand.sheet')).toHaveCount(0);
}, PHONE);

probe('after a reload in a match, Leave still leaves', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.reload({ waitUntil: 'domcontentloaded' });
  await expect(p.page.locator('#prompt .ok')).toBeVisible();
  const depth = await p.page.evaluate(() => history.length);
  await p.page.goBack();
  await expect(p.page.locator('#leave-ask')).toBeVisible();
  // Asking costs no history: staying and asking again does not pile entries up
  await p.page.locator('#leave-ask .stay').tap();
  await p.page.goBack();
  await expect(p.page.locator('#leave-ask')).toBeVisible();
  expect(await p.page.evaluate(() => history.length)).toBeLessThanOrEqual(depth);
  await p.page.locator('#leave-ask .leave').tap();
  await expect.poll(() => p.page.url()).not.toContain(new URL(p.server.url).host);
}, PHONE);

probe('a deck is chosen in the finder by taps, with nothing off the screen', async p => {
  await lobby(p);
  const plate = p.page.locator('.plate.mine');
  await plate.locator('.sleeve').tap();
  const finder = p.page.locator('.finder');
  await expect(finder).toBeVisible();
  // It rises into place over a moment
  await p.page.waitForTimeout(500);
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  for (const part of ['.finder', '.finder .results', '.finder .dk-hits', '.finder-foot .use']) {
    const at = await box(p.page, part);
    expect(at.x).toBeGreaterThanOrEqual(0);
    expect(at.x + at.width).toBeLessThanOrEqual(390);
    expect(at.y + at.height).toBeLessThanOrEqual(664);
  }
  await p.page.locator('.dk-hit:not(.generated)').first().tap();
  const use = p.page.locator('.finder-foot .use');
  expect((await use.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await use.tap();
  await expect(finder).toHaveCount(0);
  await expect(plate.locator('.deck-name')).not.toHaveText('');
}, PHONE);

probe('a choice of cards from your hand opens the hand by itself', async p => {
  await settledBoard(p, 'humanhand=Faithless Looting;Giant Growth;Shock\nhumanbattlefield=Mountain\naibattlefield=Mountain');
  // By name and once: the hand is sorted, and a second tap would land on the discard this is about
  const looting = await p.page.evaluate(() => Number([...document.querySelectorAll<HTMLElement>('#hand .card')].find(c => c.textContent?.includes('Faithless'))?.dataset.key));
  await act(p.page, 'selectCard', looting, false, 0, 0);
  await p.until('a card from hand is asked for', () => {
    const prompt = window.forge.model.prompt;
    if (prompt?.paying && prompt.ok?.enabled) window.forge.actions.ok();
    return !!prompt && !prompt.priority && !prompt.paying && prompt.selectable.length > 0;
  }, undefined, p.page, 30_000);
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  // The stack's list would lie over the board the choice is about, so it is folded
  await expect(p.page.locator('#stack.chip:not(.collapsed) .pile')).toHaveCount(0);
  const pick = p.page.locator('#hand.sheet .card.selectable').first();
  await expect(pick).toBeVisible();
  await pick.tap();
  await p.until('the choice is taken', () => (window.forge.model.prompt?.selectable.length ?? 0) < 4 || !!window.forge.model.prompt?.priority);
}, PHONE);

probe('the result of a game fits the screen', async p => {
  await settledBoard(p, 'humanbattlefield=Grizzly Bears\nailife=1\naibattlefield=Mountain');
  await passUntil(p, 'attackers are asked for', () => {
    const m = window.forge.model;
    return (m.objects.get(m.root) as { Phase?: string } | undefined)?.Phase === 'COMBAT_DECLARE_ATTACKERS' && !!m.prompt?.ok?.enabled;
  });
  await p.page.locator('#me .battlefield .card').first().tap({ force: true });
  await passUntil(p, 'the game ends', () => window.forge.model.gameOver);
  const word = p.page.locator('#game-over .word');
  await expect(word).toBeVisible();
  await p.page.waitForTimeout(1500);
  for (const part of ['#game-over .word', '#game-over .actions']) {
    const at = await box(p.page, part);
    expect(at.x).toBeGreaterThanOrEqual(0);
    expect(at.x + at.width).toBeLessThanOrEqual(390);
  }
  for (const button of await p.page.locator('#game-over .actions button:visible').all()) {
    const at = (await button.boundingBox())!;
    expect(at.height).toBeGreaterThanOrEqual(44);
    expect(at.x).toBeGreaterThanOrEqual(8);
    expect(at.x + at.width).toBeLessThanOrEqual(382);
  }
}, PHONE);

probe('a card that asks how to play it puts the hand away for its menu', async p => {
  await settledBoard(p, 'humanhand=Fireblast;Forest\nhumanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain\naibattlefield=Grizzly Bears');
  await p.page.locator('#hand').tap();
  await p.page.locator('#hand.sheet .card', { hasText: 'Fireblast' }).tap();
  const item = p.page.locator('#dialog-layer .card-menu:not(.game-menu) .card-menu-item').first();
  await expect(item).toBeVisible();
  await expect(p.page.locator('#hand.sheet')).toHaveCount(0);
  const menu = await box(p.page, '#dialog-layer .card-menu:not(.game-menu)');
  const dock = await box(p.page, '#prompt');
  expect(menu.y + menu.height).toBeLessThanOrEqual(dock.y + 2);
  await item.tap();
  await expect(item).toHaveCount(0);
}, PHONE);

probe('after viewing the final battlefield, the way back to the result can be tapped', async p => {
  await settledBoard(p, 'humanbattlefield=Grizzly Bears\nailife=1\naibattlefield=Mountain');
  await passUntil(p, 'attackers are asked for', () => {
    const m = window.forge.model;
    return (m.objects.get(m.root) as { Phase?: string } | undefined)?.Phase === 'COMBAT_DECLARE_ATTACKERS' && !!m.prompt?.ok?.enabled;
  });
  await p.page.locator('#me .battlefield .card').first().tap({ force: true });
  await passUntil(p, 'the game ends', () => window.forge.model.gameOver);
  const view = p.page.locator('#game-over .actions button', { hasText: 'View battlefield' });
  await expect(view).toBeVisible();
  await p.page.waitForTimeout(1200);
  await view.tap();
  await p.page.locator('#game-over .to-result').tap({ timeout: 5000 });
  await expect(p.page.locator('#game-over .actions button', { hasText: 'Quit match' })).toBeVisible();
}, PHONE);

probe('an opponent\'s open zones do not cover their portrait, and their spell\'s list does not cover your cards', async p => {
  await settledBoard(p, 'humanbattlefield=Forest;Grizzly Bears\naihand=Hill Giant\naibattlefield=Mountain;Mountain;Mountain;Mountain\nactiveplayer=ai\nactivephase=UPKEEP');
  await p.page.locator('#opponent .zones-pill').tap();
  await expect(p.page.locator('#opponent .zone-tiles')).toBeVisible();
  const tiles = await box(p.page, '#opponent .zone-tiles');
  const face = await box(p.page, '#opponent .avatar');
  expect(tiles.y).toBeGreaterThanOrEqual(face.y + face.height - 1);
  await p.page.locator('#opponent .zones-pill').tap();
  await passUntil(p, 'the opponent casts', () => document.querySelectorAll('#stack .stack-item:not(.awaiting)').length > 0);
  await p.page.waitForTimeout(600);
  const list = await box(p.page, '#stack');
  const mine = await box(p.page, '#me .battlefield .group.creatures .card');
  expect(list.y + list.height).toBeLessThanOrEqual(mine.y + 1);
  // The chip itself stays on the strip, whichever way its list opens
  const head = await box(p.page, '#stack .head');
  const strip = await box(p.page, '#phase-strip');
  expect(Math.abs(head.y - strip.y)).toBeLessThan(8);
}, PHONE);

probe('the stack chip names a spell while its target is chosen', async p => {
  await settledBoard(p, 'humanhand=Lightning Bolt\nhumanbattlefield=Mountain\naibattlefield=Grizzly Bears');
  await castFirstInHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await expect(p.page.locator('#stack .head b')).toContainText('Lightning Bolt');
}, PHONE);

probe('a block is declared by taps: the attacker, then your creature', async p => {
  await settledBoard(p, 'humanlife=5\nhumanbattlefield=Wall of Stone\naibattlefield=Craw Wurm;Hill Giant\nactiveplayer=ai\nactivephase=MAIN1');
  await passUntil(p, 'blockers are asked for', () => document.getElementById('match')!.classList.contains('declaring-blocks'), undefined, [], 90_000);
  const attackers = p.page.locator('#opponent .card.attacking');
  await expect(attackers).toHaveCount(2);
  // The game picks one attacker itself, and a tap on the other moves the pick
  const other = p.page.locator('#opponent .card.attacking:not(.highlighted)');
  await expect(other).toHaveCount(1);
  const key = await other.getAttribute('data-key');
  await other.tap({ force: true });
  const picked = p.page.locator(`#opponent .card[data-key="${key}"]`);
  await expect(picked).toHaveClass(/highlighted/);
  await expect(picked.locator('.combat-tag')).toHaveText('Blocking this');
  await expect(p.page.locator('#zoom.sheet')).toHaveCount(0);
  await p.page.locator('#me .battlefield .card', { hasText: 'Wall of Stone' }).tap({ force: true });
  await expect(p.page.locator('#me .card.blocking')).toHaveCount(1);
}, PHONE);

probe('a zone the game opens for a choice, with a spell on the stack, is a whole sheet whose cards can be tapped', async p => {
  await settledBoard(p, 'humanhand=Diabolic Vision\nhumanbattlefield=Island;Swamp\nhumanlibrary=Forest;Mountain;Plains;Island;Swamp;Hill Giant;Grizzly Bears\naibattlefield=Mountain');
  await castFirstInHand(p);
  // Paid for, then let resolve: one answer at a time, since a second sent early would land on the choice itself
  const shown = () => p.page.evaluate(() => document.querySelectorAll('#zones .card.selectable').length > 0);
  for (let i = 0; i < 20 && !await shown(); i++) {
    if (await p.page.evaluate(() => { const q = window.forge.model.prompt; return !!q && (q.paying || q.priority) && !!q.ok?.enabled; })) await act(p.page, 'ok');
    await p.page.waitForTimeout(700);
  }
  expect(await shown()).toBe(true);
  await p.page.waitForTimeout(500);
  // The dock's two buttons can do nothing while the choice is open, so they give their room to it
  await expect(p.page.locator('#prompt .ok')).toBeHidden();
  await expect(p.page.locator('#prompt .cancel')).toBeHidden();
  await expect(p.page.locator('#prompt .more')).toBeVisible();
  expect((await box(p.page, '#prompt')).height).toBeLessThanOrEqual(50);
  const panel = await box(p.page, '#zones .zone-panel');
  const dock = await box(p.page, '#prompt');
  expect(panel.width).toBe(390);
  expect(panel.y + panel.height).toBeLessThanOrEqual(dock.y + 2);
  expect(panel.y + panel.height).toBeGreaterThanOrEqual(dock.y - 2);
  // The stack's chip does not lie over the sheet
  expect(await p.page.evaluate(() => { const r = document.querySelector('#zones .zone-panel header')!.getBoundingClientRect(); const hit = document.elementFromPoint(r.x + 20, r.y + 10); return !!hit?.closest('#zones'); })).toBe(true);
  for (const each of await p.page.locator('#zones .card.selectable').all()) await each.tap({ trial: true, timeout: 3000 });
  const card = p.page.locator('#zones .card.selectable').first();
  const at = (await card.boundingBox())!;
  expect(at.y + at.height).toBeLessThanOrEqual(dock.y + 2);
  await card.tap();
}, PHONE);
