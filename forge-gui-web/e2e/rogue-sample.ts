// What zz-rogue-match sends for one Rogue Commander mockup. Each screen has a file rogue-samples/<id>.ts, named by the mockup's id

import type { Page } from '@playwright/test';
import type { RogueHome, ServerMessage } from '../src/main/ts/protocol';

export interface Sample {
  /** Sent in order after the hello, with the mockup's own content. */
  messages: ServerMessage[];
  /** The clicks the screen needs once the messages are drawn, such as opening a menu. */
  steps?: (page: Page) => Promise<void>;
  /** Selectors of the parts the client owns (design section 8), left out of both comparisons. */
  owned?: string[];
  /**
   * The page beneath the screen's dialog is another screen's, not drawn by this one. The probe puts the mockup's own markup there,
   * so the dialog is compared over the page it was drawn over.
   */
  under?: boolean;
}

/** The Runs page of the mockups, which several screens draw beneath a dialog. */
export const HOME: RogueHome = {
  t: 'rogueHome', snapshot: 1, unlocked: true,
  bar: {
    t: 'campaignBar', name: 'Rogue Commander', line: '6 runs · 4 won', fresh: {},
    balances: [
      { icon: 'ICO_QUEST_GOLD', amount: 52, label: 'Echoes', term: 'Persistent memories of the Aether used to build and upgrade Aetherworks.' },
      { icon: 'ICO_QUEST_ELIXIR', amount: 1, label: 'Sparks' },
    ],
  },
  run: {
    commander: 'Aragorn, the Uniter', image: 'k-aragorn-the-uniter', line: 'Descension: Level 1 - Wrathful', life: 14, maxLife: 23, gold: 8, cards: 64, credits: 3,
    carry: [
      { name: 'The One Ring', image: 'c-the-one-ring', type: 'ITEM' },
      { name: 'Gilded Goose', image: 'c-gilded-goose', type: 'FELLOW' },
      { name: 'Lightning Helix', image: 'c-lightning-helix', type: 'SCROLL' },
    ],
  },
  fan: ['c-kaalia-of-the-vast', 'c-morophon-the-boundless', 'c-atraxa-praetors-voice'],
};

