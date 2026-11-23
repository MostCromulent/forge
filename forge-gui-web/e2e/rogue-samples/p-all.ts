import { onMap, type Sample } from '../rogue-sample';

export default {
  messages: onMap(),
  save: 'run',
  steps: async page => {
    await page.locator('#campaign .ph-more .ph-icon').click();
    await page.getByRole('menuitem', { name: 'Whole map' }).click();
  },
  owned: ['.ph'],
} satisfies Sample;
