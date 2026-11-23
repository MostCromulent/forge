import { HOME, pick, pickCards, type Sample } from '../rogue-sample';
import type { EditorCard } from '../../src/main/ts/protocol';

const card = (name: string, mv: number): EditorCard => ({ name, count: 1, image: '', cost: '', mv, colors: '', printings: 1, split: [], foil: false });

export default {
  messages: [HOME, {
    t: 'rogueQuestion', id: 5, kind: 'cards',
    cards: {
      kind: 'REMOVE', title: 'Card Selection', subtitle: 'Choose 3 cards to remove.', min: 3, max: 3, art: 'a-esper',
      cards: [pick(0, 'boromir-warden-of-the-tower', 'Boromir, Warden of the Tower', 3), pick(1, 'eowyn-fearless-knight', 'Éowyn, Fearless Knight', 4),
        pick(2, 'frodo-baggins', 'Frodo Baggins', 2), pick(3, 'arwen-undomiel', 'Arwen Undómiel', 2), pick(4, 'paradise-druid', 'Paradise Druid', 2),
        pick(5, 'delighted-halfling', 'Delighted Halfling', 1), pick(6, 'elvish-warmaster', 'Elvish Warmaster', 2),
        pick(7, 'artificer-s-assistant', "Artificer's Assistant", 1), pick(8, 'legolas-master-archer', 'Legolas, Master Archer', 3),
        pick(9, 'gilded-goose', 'Gilded Goose', 1)],
      deck: { title: 'Deck', total: 64, groups: [
        { heading: 'Creatures', cards: [card("Artificer's Assistant", 1), card('Delighted Halfling', 1), card('Gilded Goose', 1), card('Arwen Undómiel', 2),
          card('Paradise Druid', 2), card('Elvish Warmaster', 2), card('Boromir, Warden of the Tower', 3), card('Legolas, Master Archer', 3),
          card('Éowyn, Fearless Knight', 4)] },
        { heading: 'Spells', cards: [card('Swords to Plowshares', 1), card('Lightning Helix', 2), card('Cultivate', 3)] },
      ] },
    },
  }],
  save: 'run',
  steps: page => pickCards(page, [4, 7]),
  under: true,
} satisfies Sample;
