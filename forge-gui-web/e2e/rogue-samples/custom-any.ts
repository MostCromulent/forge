import { HOME, press, type Sample } from '../rogue-sample';
import { CUSTOM } from './custom';
import { pick } from './p-custom';

export default {
  messages: [HOME, CUSTOM],
  steps: async page => {
    await page.locator('#campaign .rgl-new').click();
    await press(page, '#campaign .rgm-end .hv.on .rg-toggle');
    await pick(page, ['Chosen One', 'Sealed']);
    await press(page, '#campaign .camp-side .rgm-lv button >> nth=3');
  },
  owned: ['.icon-button'],
} satisfies Sample;
