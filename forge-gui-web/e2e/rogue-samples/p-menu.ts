import { onMap, type Sample } from '../rogue-sample';

// The phone's run menu is the header's own, with the map's rows in it
export default {
  messages: onMap(),
  save: 'run',
  steps: page => page.locator('#campaign .ph-more .ph-icon').click(),
  owned: ['.ph', '.rg-psheet'],
} satisfies Sample;
