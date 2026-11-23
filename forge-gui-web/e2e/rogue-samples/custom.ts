import { HOME, ROSTER, press, type Sample } from '../rogue-sample';
import { pick } from './p-custom';

/** The panel shows the commander's art where the carousel shows its card, so the sample names the art. */
export const CUSTOM = { ...ROSTER, commanders: ROSTER.commanders.map((c, i) => (i ? c : { ...c, image: 'a-aragorn-the-uniter' })) };

export default {
  messages: [HOME, CUSTOM],
  steps: async page => {
    await page.locator('#campaign .rgl-new').click();
    await press(page, '#campaign .rgm-end .hv.on .rg-toggle');
    await pick(page, ['Rich', 'Sealed']);
    await press(page, '#campaign .camp-side .rgm-lv button >> nth=3');
  },
  owned: ['.icon-button'],
} satisfies Sample;
