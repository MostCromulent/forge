import { EVENT, HOME, press, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, EVENT],
  save: 'run',
  steps: async page => {
    await press(page, '#campaign .rg-scene .ft button');
    await page.locator('#campaign .rg-folded').waitFor();
  },
  under: true,
} satisfies Sample;
