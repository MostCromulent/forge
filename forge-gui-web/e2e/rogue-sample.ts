// What zz-rogue-match sends for one Rogue Commander mockup. Each screen has a file rogue-samples/<id>.ts, named by the mockup's id

import type { Page } from '@playwright/test';
import type { Modification, Part, RogueCommander, RogueHome, RogueQuestion, RogueRoster, ServerMessage } from '../src/main/ts/protocol';

export interface Sample {
  /** Sent in order after the hello, with the mockup's own content. */
  messages: ServerMessage[];
  /** The clicks the screen needs once the messages are drawn, such as opening a menu. */
  steps?: (page: Page) => Promise<void>;
  /** Sent once the steps are taken, such as a question asked over the page they chose. */
  later?: ServerMessage[];
  /** Selectors of the parts the client owns (design section 8), left out of both comparisons. */
  owned?: string[];
  /**
   * The page beneath the screen's dialog is another screen's, not drawn by this one. The probe puts the mockup's own markup there,
   * so the dialog is compared over the page it was drawn over.
   */
  under?: boolean;
  /** The stage the hello opens the mode at, such as "run". */
  save?: string;
  /** An element tapped once the page is drawn and the pointer has left it, such as a term to explain. */
  tap?: string;
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


/** The texts the server words from RogueStart.descensionLockText, with Descension Mode unlocked. */
const commander = (name: string, slug: string, colors: string, text: string, themes: readonly string[], record: string, facts: number[], startable: number): RogueCommander => ({
  deck: name, name, image: `k-${slug}`, colors, text, themes: [...themes], record, runs: facts[0], wins: facts[1], maxDescension: facts[2], seen: facts[3], startable,
  lock: startable ? undefined : `Win a Run with ${name} to unlock Descension Mode for this Commander.`,
  next: startable ? `Win a Run with ${name} in Descension Level ${startable} to unlock Level ${startable + 1}.` : undefined,
});
const locked = (deck: string, unlock: string): RogueCommander => ({
  deck, colors: '', text: '', themes: [], record: 'Locked', runs: 0, wins: 0, maxDescension: 0, seen: 0, startable: -1, unlock,
});
const NO_DESCENSION = 'Descension Mode - Win a Run with 3 different Commanders to unlock.';
const ARAGORN = ['Aragorn, the Uniter', 'aragorn-the-uniter', 'RGWU', 'Unleash an army of human soldiers, scry with all-seeing elves, or rule with legends.',
  ['Colors', 'Legendary', 'Tokens', 'Scry', 'Counters']] as const;
const KAALIA = ['Kaalia of the Vast', 'kaalia-of-the-vast', 'RWB', 'Cheat lifebringing angels, sadistic demons, or annihilating dragons into combat.',
  ['Flying', 'Cheat-Into-Play', 'Aggro', 'Lifegain']] as const;
const LOCKED = [locked('Muldrotha', 'Have 15+ nonbasic lands in your deck.'), locked('Nick Fury', 'Have 15+ legendary permanents in your deck.'),
  locked('Satya', 'Have 10+ artifacts in your deck.'), locked("Y'shtola", 'Have 50+ life at the end of any match.')];

const mod = (id: string, name: string, text: string, group: string, more: Partial<Modification> = {}): Modification => ({
  id, name, text, group, generatesCommander: false, experimental: false, ...more,
});

/** The New Run page of the mockups: four commanders open and four locked, with Descension and Custom Run unlocked. */
export const ROSTER: RogueRoster = {
  t: 'rogueRoster', snapshot: 1, custom: true, descension: true, experimental: [],
  commanders: [
    commander(...ARAGORN, '3 runs · 2 won', [3, 2, 1, 45], 2),
    commander("Atraxa, Praetors' Voice", 'atraxa-praetors-voice', 'GWUB', 'Buff your creatures with +1/+1 Counters, let the enemy rot with phyrexian poison, or gather an army of mighty planeswalkers.',
      ['Counters', 'Poison', 'Planeswalkers'], '1 run · 1 won', [1, 1, 0, 22], 1),
    commander(...KAALIA, '2 runs · 1 won', [2, 1, 0, 31], 1),
    commander('Morophon, the Boundless', 'morophon-the-boundless', 'WUBRG', 'Swarm with interlocking Slivers, unleash devastating Eldrazi, or master every creature type through Shapeshifters.',
      ['Tribal', 'Shapeshifters', 'Colorless'], 'Not played', [0, 0, 0, 0], 0),
    ...LOCKED,
  ],
  levels: [
    { name: 'Wrathful', text: 'Some Planebounds on the path become Wrathful, gaining random buffs for the match.' },
    { name: 'Cursed', text: 'Some Planebounds on the path become Cursed, gaining powerful buffs for the opponent.' },
    { name: 'Wounded', text: 'Start the run with a random Wound.' },
    { name: 'Elites', text: '2 random Normal Planes of the Path are replaced by Elite Planes.' },
    { name: 'Hardened', text: 'All Planebounds have +5 starting life.' },
    { name: 'Crisis', text: 'Healing at a Sanctum only cures all Wounds, all Bazaar offers cost 2 more Gold.' },
    { name: 'Nemesis', text: 'Bosses have +15 starting life and an additional instance of Cursed.' },
  ],
  modifications: [
    mod('EXPERIMENT', 'Experiment', 'Choose from Rogue Commanders that are not or not yet in the official roster.', 'Commander',
      { experimental: true }),
    mod('CHOSEN_ONE', 'Chosen One', 'Choose any eligible Commander.', 'Commander', { generatesCommander: true }),
    mod('MANIA', 'Mania', 'A random eligible Commander will be chosen for you.', 'Commander', { generatesCommander: true }),
    mod('RANDOM_START', 'Random Start', 'Start with an automatically created Start Deck according to synergy and color identity for the selected Commander instead of its default deck.',
      'Deck'),
    mod('RANDOM_REWARDS', 'Random Rewards', 'Start with an automatically created Reward Pool according to synergy and color identity for the selected Commander instead of its default pool.',
      'Deck'),
    mod('SEALED', 'Sealed', 'Choose 25 out of 60 non-mythic cards from the Start and Reward Pool as your Start Deck. (also works with automatically generated decks and pools)', 'Deck'),
    mod('PATHFINDER', 'Pathfinder', 'Ignore Paths when choosing the next Node.', 'Map'),
    mod('RICH', 'Rich', 'Gain 5 additional Gold after each victorious match. All Side Nodes are Bazaars.', 'Map'),
    mod('USURPER', 'Usurper', 'Start with 2 random Chest Traits. All Planebounds gain +1 Cursed.', 'Map'),
    mod('BORROWED_HEALTH', 'Borrowed Health', 'Resting at a Sanctum heals all Life but costs 3 Max. Life.', 'Map'),
    mod('VOID', 'Void', 'No Planes or Planar Die are added for any battle.', 'Map'),
    mod('PATH_OF_THE_BANNED', 'Path of the Banned', 'All Side Nodes are Chests offering only cards from the Commander Banlist.', 'Map'),
  ],
};

/** A new player's roster: the two commanders open from the start, unplayed, and nothing else unlocked. */
export const FIRST: RogueRoster = {
  ...ROSTER, custom: false, descension: false, customLock: NO_DESCENSION,
  commanders: [{ ...commander(...ARAGORN, 'Not played', [0, 0, 0, 0], 0), lock: NO_DESCENSION }, { ...commander(...KAALIA, 'Not played', [0, 0, 0, 0], 0), lock: NO_DESCENSION },
    locked('Atraxa', 'Win a Run.'), locked('Morophon', 'Have 10+ creatures that share a creature type in your deck.'), ...LOCKED],
};

/** Opens the commanders from Runs and puts the one at index in front, then chooses Descension level 1 when level is set. */
export async function choose(page: Page, index: number, level = false): Promise<void> {
  await page.locator('#campaign .rgl-new').click();
  if (index) await press(page, `#campaign .rgx-dots button[data-i="${index}"]`);
  if (level) await press(page, '#campaign .rgm-body .hv.on .rgm-lv button >> nth=1');
}

/** The server's answer about a custom run with Aragorn in front, as the probe has no server to ask. */
export const answer = (modifications: string[], descension: number, forced: string[] = []): ServerMessage => ({
  t: 'rogueChoice', deck: 'Aragorn, the Uniter', modifications, descension, forced, ready: true,
});

/** Clicks without scrolling first: a click scrolls the page's clipped boxes, which no player can do, to bring the button to their middle. */
export const press = (page: Page, selector: string) => page.locator(selector).dispatchEvent('click');

const text = (words: string): Part => ({ kind: 'text', text: words });
const card = (name: string, image?: string): Part => ({ kind: 'card', text: name, image });
/** The terms as the server explains them, from KeywordHint. */
const TIPS = {
  Item: 'An Artifact added to your command zone as a carry card. It persists between matches unless it ends a match neither in the command zone nor on the battlefield.',
  Fellow: 'A Creature added to your command zone as a carry card. It persists between matches unless it ends a match neither in the command zone nor on the battlefield.',
  Trait: 'A permanent effect that lasts for the rest of the Run, or until its last charge is consumed.',
};
const term = (shown: string, name: keyof typeof TIPS): Part => ({ kind: 'term', text: shown, term: name, tip: TIPS[name] });

/** Henzie's boon of the mockups, with one reroll left. */
export const BOON: RogueQuestion = {
  t: 'rogueQuestion', id: 1, kind: 'boon',
  choice: {
    kind: 'boon', face: 59, title: 'Henzie "Toolbox" Torre', art: 'a-the-dark-barony', rerolls: 1, skip: false,
    paragraphs: [[text(`Henzie pulls up a chair. "What do you need, Commander? Let me guess...Illegal stuff the whole goddamn Multiverse is after? Well, you're in luck. Best offers since a long time."`)]],
    options: [
      {
        label: "Henzie's Containment", enabled: true,
        text: [text('Gain the '), term('Item', 'Item'), text(' '), card('Mana Vault'), text(' and the '), term('Fellow', 'Fellow'), text(' '), card('Emrakul, the Aeons Torn'), text('.')],
      },
      { label: "Henzie's Precious", enabled: true, text: [text('Gain the '), term('Item', 'Item'), text(' '), card('The One Ring', 'c-the-one-ring'), text('.')] },
      { label: 'Exquisite Traits', enabled: true, text: [text('Gain 2 random Chest '), term('Traits', 'Trait'), text('.')] },
    ],
  },
};

/** The event of the mockups, asked on the map. */
export const EVENT: RogueQuestion = {
  t: 'rogueQuestion', id: 3, kind: 'event',
  choice: {
    kind: 'event', title: 'Crooked Counsel', rerolls: 0, skip: false,
    paragraphs: [[text(`An old wizard, once a cherished friend, blocks the road beneath a black stone tower. "The shadow rises in the east," he murmurs. "Stand against it if you must... but it wouldn't be wise, my friend."`)]],
    options: [
      { label: 'Rally the Free Peoples', enabled: true,
        text: [text('Remove all black cards from your deck. Choose up to 9 creatures from a set of legendary Halflings, Humans, Elves, Dwarves, and Wizards to add to your deck.')] },
      { label: 'Join with the Dark Lord', enabled: true, text: [text('Remove 9 random creatures from your deck. When you do, add 9 copies of '), card('Nazgûl'), text(' to your deck.')] },
      { label: 'Keep to your own path', enabled: true, text: [text('Lose 5 Life. Gain the legendary artifact '), term('Item', 'Item'), text(' '), card('The One Ring', 'c-the-one-ring'), text('.')] },
    ],
  },
};

/** The first two parts of Teferi's first story. The mockups show the second. */
export const MEETING_TEFERI = [
  `A dark-skinned man in blue robes looks up from a dismantled machine, slightly surprised. He sets down his tool and comes to meet you, smiling.
"Commander. You came." He offers his hand. "I'm Teferi. Forgive me, I keep forgetting we haven't met."`,
  `"Come in. Welcome to the Aether." He leads you past a broken archway, keeping to the cleared side of the passage. "I built it outside ordinary space and time. Thought I could give people somewhere the Phyrexian invasion wouldn't reach them." He stops beside a scar in the stone.
"Well, I was wrong. The invasion is over, but the Aether was heavily damaged despite fighting the Phyrexians off from here. Don't worry. I think we can put the place back together."`,
];

/** Teferi telling a story, a part per paragraph. */
export const story = (paragraphs: string[], heading?: string): RogueQuestion => ({
  t: 'rogueQuestion', id: 2, kind: 'story',
  choice: { kind: 'story', face: 65, title: 'Teferi, Master of Time', heading, art: 'a-teferi-master-of-time', paragraphs: paragraphs.map(p => [text(p)]), options: [], rerolls: 0, skip: false },
});
