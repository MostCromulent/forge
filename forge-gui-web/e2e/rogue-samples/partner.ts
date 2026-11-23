import { COMMANDER_ROWS, HOME, pickCards, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, {
    t: 'rogueQuestion', id: 7, kind: 'partner',
    commander: { title: 'Choose a partner for Rograkh, Son of Rohgahh', total: 8, avatars: [], skip: true, art: 'a-the-maelstrom' },
  }],
  later: [{ t: 'catalogue', request: 1, rows: COMMANDER_ROWS.slice(0, 8), total: 8, offset: 0, hiddenBySwitch: 0, ranked: false, source: 'rogue-commanders' }],
  then: async page => {
    await page.locator('#campaign .rg-scene .rg-card >> nth=7').waitFor();
    await pickCards(page, [2]);
  },
  under: true,
} satisfies Sample;
