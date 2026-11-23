import { HOME, ROSTER, press, type Sample } from '../rogue-sample';

/** Level 1 is chosen before the locked commander, which clears it as desktop does; the mockup keeps it. */
export default {
  messages: [HOME, ROSTER],
  steps: async page => {
    await page.locator('#campaign .rgl-new').click();
    await press(page, '#campaign .rgm-body .hv.on .rgm-lv button >> nth=1');
    await press(page, '#campaign .rgx-dots button[data-i="4"]');
  },
  owned: ['.icon-button'],
} satisfies Sample;
