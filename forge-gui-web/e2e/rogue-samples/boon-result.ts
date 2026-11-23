import { HOME, type Sample } from '../rogue-sample';

const card = (name: string, slug: string) => ({ name, image: `c-${slug}` });

export default {
  messages: [HOME, {
    t: 'rogueQuestion', id: 2, kind: 'result',
    tell: {
      kind: 'BOON', title: 'NPC Reward Gained', text: [{ kind: 'text', text: "You gained a reward from Henzie's Mythics." }], gains: [], art: 'a-the-dark-barony',
      sections: [{
        label: 'Cards Added to Your Deck', text: [],
        cards: [card('Éomer, King of Rohan', 'eomer-king-of-rohan'), card('Flowering of the White Tree', 'flowering-of-the-white-tree'),
          card('Andúril, Narsil Reforged', 'anduril-narsil-reforged')],
      }],
    },
  }],
  under: true,
} satisfies Sample;
