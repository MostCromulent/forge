import type { Page } from '@playwright/test';
import { HOME, ROSTER, press, type Sample } from '../rogue-sample';

/** Ticks the custom run's options by name. */
export async function pick(page: Page, names: string[]): Promise<void> {
  for (const name of names) await page.locator('#campaign .rg-mod', { has: page.locator(`b:text-is("${name}")`) }).dispatchEvent('click');
}

export default {
  messages: [HOME, ROSTER],
  steps: async page => {
    await page.locator('#campaign .rgl-new').click();
    await press(page, '#campaign .rgm-end .go button >> nth=1');
    await pick(page, ['Chosen One']);
    await press(page, '#campaign .rg-sheetbar .go button >> nth=0');
    await press(page, '#campaign .rg-psheet .rgm-lv button >> nth=3');
    await press(page, '#campaign .rg-psheet .ft2 button');
  },
  owned: ['.ph'],
} satisfies Sample;
