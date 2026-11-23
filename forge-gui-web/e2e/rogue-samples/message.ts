import { HOME, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, {
    t: 'rogueQuestion', id: 1, kind: 'message',
    tell: { kind: 'MESSAGE', title: 'Rogue Commander', text: [{ kind: 'text', text: 'Could not generate a valid Commander deck. Please try again.' }], gains: [], sections: [] },
  }],
  under: true,
} satisfies Sample;
