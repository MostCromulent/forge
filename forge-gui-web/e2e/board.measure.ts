// A measurement, not a test: the browser's own cost of drawing real games as the board widens. Two computer players
// run goblin token decks, which fill both boards, while the host watches, one game after another. Chrome's counters
// for script, layout and style time are read every second beside the number of cards drawn on the board and the state
// messages received, so the result is the browser's cost per message at each board size, whatever pace the computer
// plays at.
//
// Run from this folder, after building the jar:
//   npx playwright test --config playwright.measure.config.ts
// FORGE_MEASURE_SECONDS sets how long to watch (default 150); FORGE_MEASURE_CPU slows the CPU by that factor, to
// stand in for a slower laptop (default 1). The samples are also written to ../target/board-measure.json.

import { writeFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { enterName, hostTable } from './steps';

const TOKENS = ['4 Dragon Fodder', '4 Krenko\'s Command', '4 Hordeling Outburst', '4 Mogg War Marshal',
  '4 Beetleback Chief', '4 Siege-Gang Commander', '4 Goblin Rabblemaster', '32 Mountain'].join('\n');
const SECONDS = Number(process.env.FORGE_MEASURE_SECONDS ?? 150);
const CPU = Number(process.env.FORGE_MEASURE_CPU ?? 1);
const SAMPLE_MS = 1000;
/** Samples are grouped by how many cards the board draws, in bands this wide. */
const BAND = 10;

let server: Server;
// A port away from the tests' own, which may be running at the same time
test.beforeEach(async () => { server = await startServer(37600); });
test.afterEach(async () => { await server.stop(); });

interface Sample {
  cards: number;
  messages: number;
  bytes: number;
  scriptMs: number;
  layoutMs: number;
  styleMs: number;
  layouts: number;
}

test('the browser\'s cost of drawing token games as the board widens', async ({ page }) => {
  test.setTimeout((SECONDS + 240) * 1000);
  // Listened for before the page loads, since the page opens its socket as it starts
  let messages = 0;
  let bytes = 0;
  const byType = new Map<string, [number, number]>();
  page.on('websocket', ws => ws.on('framereceived', f => {
    const text = typeof f.payload === 'string' ? f.payload : f.payload.toString();
    bytes += text.length;
    if (text.startsWith('{"t":"state"')) messages++;
    const type = /"t":"(\w+)"/.exec(text)?.[1] ?? '?';
    const seen = byType.get(type) ?? [0, 0];
    byType.set(type, [seen[0] + 1, seen[1] + text.length]);
  }));
  await page.goto(server.url);
  await enterName(page, 'Watcher');
  await hostTable(page, false);
  const seats = page.locator('#seats .plate');
  await seats.nth(0).locator('.sleeve').click();
  await page.click('.finder-head >> text=Import');
  await page.fill('.importer textarea', TOKENS);
  await page.fill('.importer .namefield input', 'Goblin tokens');
  await page.click('.importer >> text=Import and use');
  await expect(seats.nth(0).locator('.deck-name')).toHaveText('Goblin tokens');
  await seats.nth(1).locator('.sleeve').click();
  await page.fill('.finder .find', 'Goblin tokens');
  await page.locator('.dk-hit', { hasText: 'Goblin tokens' }).first().dblclick();
  await expect(seats.nth(1).locator('.deck-name')).toHaveText('Goblin tokens');
  await page.locator('label.spectate input').check();

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  if (CPU > 1) await cdp.send('Emulation.setCPUThrottlingRate', { rate: CPU });
  const metrics = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(m => [m.name, m.value]));

  await page.click('#play');
  await expect(page.locator('#match')).toBeVisible();
  const samples: Sample[] = [];
  let games = 1;
  let before = await metrics();
  let seenMessages = messages;
  let seenBytes = bytes;
  const started = Date.now();
  while (Date.now() - started < SECONDS * 1000) {
    await page.waitForTimeout(SAMPLE_MS);
    const now = await metrics();
    const spent = (key: string) => (now[key] - before[key]) * 1000;
    samples.push({
      cards: await page.locator('#me .battlefield .card, #opponent .battlefield .card').count(),
      messages: messages - seenMessages, bytes: bytes - seenBytes,
      scriptMs: spent('ScriptDuration'), layoutMs: spent('LayoutDuration'), styleMs: spent('RecalcStyleDuration'),
      layouts: now.LayoutCount - before.LayoutCount,
    });
    before = now;
    seenMessages = messages;
    seenBytes = bytes;
    const next = page.locator('#game-over >> text=Next game');
    if (await next.isVisible()) {
      await next.click();
      games++;
    }
  }

  const bands = new Map<number, Sample>();
  for (const s of samples.filter(s => s.messages > 0)) {
    const band = Math.floor(s.cards / BAND) * BAND;
    const sum = bands.get(band) ?? { cards: 0, messages: 0, bytes: 0, scriptMs: 0, layoutMs: 0, styleMs: 0, layouts: 0 };
    for (const k of Object.keys(sum) as (keyof Sample)[]) sum[k] += s[k];
    sum.cards = band;
    bands.set(band, sum);
  }
  const rows = [...bands.values()].sort((a, b) => a.cards - b.cards).map(b => ({
    cards: `${b.cards}-${b.cards + BAND - 1}`, msgs: b.messages,
    'script/msg ms': +(b.scriptMs / b.messages).toFixed(2), 'layout/msg ms': +(b.layoutMs / b.messages).toFixed(2),
    'style/msg ms': +(b.styleMs / b.messages).toFixed(2), 'layouts/msg': +(b.layouts / b.messages).toFixed(1),
    'KB/msg': +(b.bytes / b.messages / 1024).toFixed(1),
  }));
  console.log(`CPU slowdown ${CPU}x, ${games} games, ${messages} state messages, ${(bytes / 1024).toFixed(0)} KB received`);
  console.table(rows);
  console.table([...byType].sort((a, b) => b[1][1] - a[1][1]).map(([t, [n, b]]) => ({ type: t, count: n, KB: Math.round(b / 1024) })));
  await page.screenshot({ path: test.info().outputPath('end.png') });
  const label = process.env.FORGE_MEASURE_LABEL ?? 'board';
  writeFileSync(new URL(`../target/${label}-measure.json`, import.meta.url), JSON.stringify({ cpu: CPU, games, bytes, samples, rows }, null, 2));
  expect(samples.length).toBeGreaterThan(0);
});
