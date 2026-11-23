import sealed from './sealed';
import { SEALED, type Sample } from '../rogue-sample';
import type { RogueQuestion } from '../../src/main/ts/protocol';

const [home, question] = sealed.messages as [Sample['messages'][number], RogueQuestion];

// The phone mockup offers the first sixteen cards
export default {
  ...sealed,
  messages: [home, { ...question, cards: { ...question.cards!, cards: SEALED.slice(0, 16) } }],
} satisfies Sample;
