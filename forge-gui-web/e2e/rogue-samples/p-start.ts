import type { Page } from '@playwright/test';
import { HOME, ROSTER, choose, press, type Sample } from '../rogue-sample';

/** Puts Atraxa in front at Descension level 1, chosen in the sheet, and leaves the sheet open when open is set. */
export async function atraxa(page: Page, open: boolean): Promise<void> {
  await choose(page, 1);
  await press(page, '#campaign .rgm-end .go button >> nth=0');
  await press(page, '#campaign .rg-psheet .rgm-lv button >> nth=1');
  if (!open) await press(page, '#campaign .rg-psheet .ft2 button');
}

export default { messages: [HOME, ROSTER], steps: page => atraxa(page, false), owned: ['.ph'] } satisfies Sample;
