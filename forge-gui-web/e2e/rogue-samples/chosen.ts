import { COMMANDER_ROWS, HOME, pickCards, press, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, {
    t: 'rogueQuestion', id: 6, kind: 'commander',
    commander: { title: 'Choose any Commander', total: 2914, avatars: [5, 8, 12, 17, 18, 24, 26, 40, 42, 59, 65, 68], skip: false },
  }],
  later: [{ t: 'catalogue', request: 1, rows: COMMANDER_ROWS, total: 2914, offset: 0, hiddenBySwitch: 0, ranked: false, source: 'rogue-commanders' }],
  then: async page => {
    await page.locator('#campaign .rg-scene .rg-card >> nth=15').waitFor();
    await pickCards(page, [6]);
    await press(page, '#campaign .rg-avs img >> nth=3');
  },
  under: true,
} satisfies Sample;
