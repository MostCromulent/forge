import { describe, expect, it } from 'vitest';
import {
  choose, draftBlockChoice, draftCombo, draftSteps, openStep, podChoices, sealedBlockChoice, sealedSteps, type DraftValue, type SealedValue,
} from '../../main/ts/setup';
import type { LimitedOptions } from '../../main/ts/protocol';

const options: LimitedOptions = {
  t: 'limitedOptions',
  blocks: [
    { name: 'Innistrad', packs: 6, combos: ['3 ISD, 3 DKA', '6 ISD'] },
    { name: 'Magic 2014', packs: 6, combos: ['M14'] },
  ],
  fantasyBlocks: [],
  prereleases: [{ code: 'DSK', name: 'Duskmourn' }],
  templates: ['Vintage cube'],
  draftBlocks: [
    { name: 'Innistrad', packs: 3, sets: ['DKA', 'ISD', 'AVR', 'SOI'], combos: [] },
    { name: 'Return to Ravnica', packs: 3, sets: ['DGM', 'GTC', 'RTR'], combos: ['RTR/RTR/RTR', 'GTC/GTC/RTR'] },
    { name: 'Magic 2014', packs: 3, sets: ['M14'], combos: [] },
  ],
  draftFantasyBlocks: [], cubes: ['Vintage cube'], themes: ['Core sets'],
};
const steps = sealedSteps(options);
const draft = draftSteps(options);

describe('the sealed setup form', () => {
  // Fails if the form skips the pack count and jumps to naming the pool
  it('asks for the pack count after choosing the full card pool', () => {
    expect(openStep(steps, { product: 'Full' })).toBe('packs');
  });

  // Fails if a stale block rides along to the server after the product changes
  it('forgets the block when the product changes', () => {
    const chosen: SealedValue = { product: 'Block', block: 'Innistrad', combo: '6 ISD', name: 'Pool' };
    const changed = choose(steps, chosen, 'product', { product: 'Full' });
    expect(changed.block).toBeUndefined();
    expect(changed.combo).toBeUndefined();
    expect(changed.name).toBeUndefined();
    expect(openStep(steps, changed)).toBe('packs');
    expect(steps.find(s => s.id === 'block')!.applies!(changed)).toBe(false);
  });

  // Fails if a question with only one possible answer is still asked
  it('fills in the only set combination a block has', () => {
    const v = choose(steps, { product: 'Block' }, 'block', sealedBlockChoice(options, 'Block', 'Magic 2014'));
    expect(v.combo).toBe('M14');
    expect(openStep(steps, v)).toBe('name');
  });

  // Fails if a block with several combinations is opened without asking which
  it('asks which combination when a block has several', () => {
    const v = choose(steps, { product: 'Block' }, 'block', sealedBlockChoice(options, 'Block', 'Innistrad'));
    expect(openStep(steps, v)).toBe('combo');
  });

  // Fails if the form can be submitted before every step that applies is answered
  it('is finished only when every step that applies has an answer', () => {
    expect(openStep(steps, { product: 'Prerelease', edition: 'DSK' })).toBe('name');
    expect(openStep(steps, { product: 'Prerelease', edition: 'DSK', name: 'Pre' })).toBeNull();
  });
});

describe('the draft setup form', () => {
  // Fails if a block without preset combinations joins its per-pack sets wrongly, or asks for presets it has none of
  it('asks one set per pack when a block has no presets', () => {
    const v = choose(draft, { product: 'Block' }, 'block', draftBlockChoice(options, 'Block', 'Innistrad'));
    expect(openStep(draft, v)).toBe('packs');
    const done = choose(draft, v, 'packs', { packs: ['ISD', 'DKA', 'ISD'] });
    expect(openStep(draft, done)).toBeNull();
    expect(draftCombo(done)).toBe('ISD/DKA/ISD');
  });

  // Fails if a block with presets is asked pack by pack instead
  it('offers a block\'s preset combinations', () => {
    const v = choose(draft, { product: 'Block' }, 'block', draftBlockChoice(options, 'Block', 'Return to Ravnica'));
    expect(openStep(draft, v)).toBe('combo');
  });

  // Fails if a single-set block asks a question with one answer
  it('needs nothing more for a single-set block', () => {
    const v: DraftValue = choose(draft, { product: 'Block' }, 'block', draftBlockChoice(options, 'Block', 'Magic 2014'));
    expect(openStep(draft, v)).toBeNull();
    expect(draftCombo(v)).toBe('M14');
  });

  // Fails if the sets chosen for one block ride along after the product changes
  it('forgets the packs when the product changes', () => {
    const v: DraftValue = { product: 'Block', block: 'Innistrad', packs: ['ISD', 'ISD', 'ISD'] };
    const changed = choose(draft, v, 'product', { product: 'Full' });
    expect(changed.packs).toBeUndefined();
    expect(openStep(draft, changed)).toBeNull();
  });
});

describe('the table rules of an online draft', () => {
  const done: DraftValue = { product: 'Full' };

  // Fails if an offline draft is asked for table rules, which only a lobby of players has
  it('asks only when the draft is online', () => {
    expect(openStep(draft, done)).toBeNull();
    expect(openStep(draftSteps(options, { seated: 3 }), done)).toBe('rules');
  });

  // Fails if the pod starts at a size other than the set's own, or the stepper allows fewer seats than players
  it('starts at the set\'s pod size and never goes under the players seated', () => {
    expect(podChoices(3)).toEqual([0, 3, 4, 5, 6, 7, 8]);
    expect(podChoices(1)[1]).toBe(2);
    expect(podChoices(3)[0]).toBe(0);
  });
});
