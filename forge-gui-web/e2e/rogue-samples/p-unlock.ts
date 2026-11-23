import { HOME, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, {
    t: 'rogueAnnounce', kind: 'unlock', title: 'Commander Unlocked!', text: 'Muldrotha, the Gravetide\nHave 15+ nonbasic lands in your deck.',
    image: 'c-muldrotha-the-gravetide',
  }],
  owned: ['.ph'],
} satisfies Sample;
