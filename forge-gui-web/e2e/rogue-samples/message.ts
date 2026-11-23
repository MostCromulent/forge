import { HOME, ROSTER, choose, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, ROSTER],
  steps: page => choose(page, 0, true),
  later: [{
    t: 'rogueQuestion', id: 1, kind: 'message',
    tell: { kind: 'MESSAGE', title: 'Rogue Commander', text: [{ kind: 'text', text: 'Could not generate a valid Commander deck. Please try again.' }], gains: [], sections: [] },
  }],
  owned: ['.icon-button'],
} satisfies Sample;
