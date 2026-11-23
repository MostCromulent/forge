import { HOME, SEALED, pickCards, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, {
    t: 'rogueQuestion', id: 4, kind: 'cards',
    cards: {
      kind: 'STARTING_DECK', title: 'Starting Deck', subtitle: 'Choose cards for your starting deck.', cards: SEALED, min: 25, max: 25, art: 'a-naya',
      deck: { title: 'Starting Deck', total: 1, groups: [{ heading: 'Commander', cards: [
        { name: 'Aragorn, the Uniter', count: 1, image: 'c-aragorn-the-uniter', cost: '', mv: 4, colors: 'RGWU', printings: 1, split: [], foil: false }] }] },
    },
  }],
  steps: page => pickCards(page, [0, 1, 3, 5, 8, 14, 15]),
  under: true,
} satisfies Sample;
