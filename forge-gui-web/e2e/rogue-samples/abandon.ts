import { HOME, type Sample } from '../rogue-sample';

export default {
  messages: [HOME],
  steps: async page => { await page.getByRole('button', { name: 'Abandon Run' }).click(); },
  under: true,
} satisfies Sample;
