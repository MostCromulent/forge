import { describe, expect, it } from 'vitest';
import { choose, openStep, sealedBlockChoice, sealedSteps, type SealedValue } from '../../main/ts/setup';
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
  draftBlocks: [], draftFantasyBlocks: [], cubes: [], themes: [],
};
const steps = sealedSteps(options);

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
