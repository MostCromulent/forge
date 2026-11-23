import { BOON, HOME, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, BOON],
  // Henzie's Precious is pointed at, so its term and card are shown beside the text
  steps: page => page.locator('#campaign .rg-opt >> nth=1').dispatchEvent('pointerenter'),
  under: true,
} satisfies Sample;
