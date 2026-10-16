import { describe, expect, it } from 'vitest';
import { splitQuestion } from '../../main/ts/match/asking';

describe('splitQuestion', () => {
  it('keeps the instruction of a target prompt, without the rules text, the id or the trigger note', () => {
    expect(splitQuestion('Gravedigger (121) - When Gravedigger enters, you may return target creature card from your graveyard to your hand. '
      + '[Zone Changer: Gravedigger (121)]\n\nSelect target Creature.YouCtrl'))
      .toEqual({ source: 'Gravedigger', ask: 'Select target Creature.YouCtrl' });
  });

  it('drops the targets picked so far, which follow the instruction', () => {
    expect(splitQuestion('Arc Lightning (121) - Arc Lightning deals 3 damage divided as you choose among one, two, or three targets.\n'
      + 'Select any target to distribute damage to\nTargeted: \nBewul\nGrizzly Bears (125)\n(1 more can be targeted)'))
      .toEqual({ source: 'Arc Lightning', ask: 'Select any target to distribute damage to' });
  });

  it('reads a one-line prompt as its source and what follows the dash', () => {
    expect(splitQuestion('Thoughtseize (121) - Choose 1 card(s) to discard')).toEqual({ source: 'Thoughtseize', ask: 'Choose 1 card(s) to discard' });
  });

  it('keeps every line of a question that names no source', () => {
    expect(splitQuestion('Choose order of cards\nto put into the library')).toEqual({ source: '', ask: 'Choose order of cards to put into the library' });
  });
});
