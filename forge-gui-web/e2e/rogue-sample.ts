// What zz-rogue-match sends for one Rogue Commander mockup. Each screen has a file rogue-samples/<id>.ts, named by the mockup's id

import type { Page } from '@playwright/test';
import type { CatalogueRow, MapNode, Modification, Part, PickCard, RogueCommander, RogueHome, RogueMap, RogueQuestion, RogueRoster, RogueRunBar, ServerMessage } from '../src/main/ts/protocol';

export interface Sample {
  /** Sent in order after the hello, with the mockup's own content. */
  messages: ServerMessage[];
  /** The clicks the screen needs once the messages are drawn, such as opening a menu. */
  steps?: (page: Page) => Promise<void>;
  /** Sent once the steps are taken, such as a question asked over the page they chose. */
  later?: ServerMessage[];
  /** The clicks taken once the later messages are drawn, such as picking from a page of the catalogue. */
  then?: (page: Page) => Promise<void>;
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
export const story = (paragraphs: string[]): RogueQuestion => ({
  t: 'rogueQuestion', id: 2, kind: 'story',
  choice: { kind: 'story', face: 65, title: 'Teferi, Master of Time', heading: 'Teferi, Master of Time', art: 'a-teferi-master-of-time', paragraphs: paragraphs.map(p => [text(p)]), options: [], rerolls: 0, skip: false },
});

/** A card offered by a pick, with the mockups' picture of it. */
export const pick = (id: number, slug: string, name: string, cmc: number): PickCard => ({ id, name, image: `c-${slug}`, cmc, mythic: false });

/** The cards of the mockups' Sealed pick, in their order. */
export const SEALED: PickCard[] = ([
  ['boromir-warden-of-the-tower', 'Boromir, Warden of the Tower', 3], ['eowyn-fearless-knight', '\u00c9owyn, Fearless Knight', 4], ['frodo-baggins', 'Frodo Baggins', 2],
  ['arwen-undomiel', 'Arwen Und\u00f3miel', 2], ['growth-spiral', 'Growth Spiral', 2], ['cultivate', 'Cultivate', 3], ['paradise-druid', 'Paradise Druid', 2],
  ['delighted-halfling', 'Delighted Halfling', 1], ['bant-charm', 'Bant Charm', 3], ['boros-charm', 'Boros Charm', 2], ['elvish-warmaster', 'Elvish Warmaster', 2],
  ['artificer-s-assistant', "Artificer's Assistant", 1], ['faramir-prince-of-ithilien', 'Faramir, Prince of Ithilien', 3], ['mirror-of-galadriel', 'Mirror of Galadriel', 2],
  ['lightning-helix', 'Lightning Helix', 2], ['swords-to-plowshares', 'Swords to Plowshares', 1], ['legolas-master-archer', 'Legolas, Master Archer', 3],
  ['elven-chorus', 'Elven Chorus', 4],
] as const).map(([slug, name, cmc], i) => pick(i, slug, name, cmc));

/** Picks the scene's cards in order, by their place on the page. */
export async function pickCards(page: Page, at: number[]): Promise<void> {
  for (const i of at) await press(page, `#campaign .rg-scene .rg-card >> nth=${i}`);
}

/** A row of the commanders' catalogue, keyed by its place in the offer. */
const commanderRow = ([slug, name]: readonly [string, string], i: number): CatalogueRow => ({
  name, image: `c-${slug}`, cost: '', mv: 0, colors: '', type: 'Legendary Creature', heading: 'Creatures', inDeck: 0, key: String(i),
});

/** The commanders the mockups list, in their order. */
export const COMMANDER_ROWS = ([['aragorn-the-uniter', 'Aragorn, the Uniter'], ['atraxa-praetors-voice', "Atraxa, Praetors' Voice"],
  ['kaalia-of-the-vast', 'Kaalia of the Vast'], ['karador-ghost-chieftain', 'Karador, Ghost Chieftain'], ['korvold-fae-cursed-king', 'Korvold, Fae-Cursed King'],
  ['lathliss-dragon-queen', 'Lathliss, Dragon Queen'], ['maelstrom-wanderer', 'Maelstrom Wanderer'], ['morophon-the-boundless', 'Morophon, the Boundless'],
  ['muldrotha-the-gravetide', 'Muldrotha, the Gravetide'], ['niv-mizzet-visionary', 'Niv-Mizzet, Visionary'], ['pantlaza-sun-favored', 'Pantlaza, Sun-Favored'],
  ['queen-marchesa', 'Queen Marchesa'], ['satya-aetherflux-genius', 'Satya, Aetherflux Genius'], ['teysa-karlov', 'Teysa Karlov'],
  ['urza-chief-artificer', 'Urza, Chief Artificer'], ['yuriko-the-tiger-s-shadow', "Yuriko, the Tiger's Shadow"]] as const).map(commanderRow);

// ---- the map ----

/** A node of the mockups' trail, as parts.mjs writes it: a plane, a face-down plane or a side node, with its state and marks. */
export interface Mock { opp?: string; plane?: string; on?: string; pn?: string; kind?: string; hid?: boolean; life?: number; st: string; star?: boolean; boss?: boolean; w?: number; cu?: number }
const B = (opp: string, plane: string, life: number, st: string, x: Partial<Mock> = {}): Mock => ({ opp, plane, life, st, ...x });
const P = (kind: string, st: string): Mock => ({ kind, st });
const H = (life: number, st: string, x: Partial<Mock> = {}): Mock => ({ hid: true, life, st, ...x });

/** The mockups' trail (parts.mjs ROWS). */
export const ROWS: Mock[][] = [
  [B('talrand-sky-summoner', 'minamo', 5, 'past', { on: 'Talrand, Sky Summoner', pn: 'Minamo' }), B('acererak-the-archlich', 'onakke-catacomb', 5, 'walked', { on: 'Acererak the Archlich', pn: 'Onakke Catacomb' }), B('hakbal-of-the-surging-soul', 'ten-wizards-mountain', 5, 'past', { on: 'Hakbal of the Surging Soul', pn: 'Ten Wizards Mountain' })],
  [P('event', 'past'), P('chest', 'walked'), P('sanctum', 'past'), P('bazaar', 'past')],
  [B('niv-mizzet-visionary', 'izzet-steam-maze', 10, 'past', { on: 'Niv-Mizzet, Visionary', pn: 'Izzet Steam Maze' }), B('rafiq-of-the-many', 'bant', 10, 'walked', { on: 'Rafiq of the Many', pn: 'Bant' }), H(10, 'past')],
  [P('sanctum', 'past'), P('event', 'walked'), P('bazaar', 'past')],
  [H(15, 'dead'), B('trostani-selesnya-s-voice', 'selesnya-loft-gardens', 20, 'sel', { on: "Trostani, Selesnya's Voice", pn: 'Selesnya Loft Gardens', star: true, w: 1 }), B('aurelia-the-warleader', 'bloodhill-bastion', 15, 'open', { on: 'Aurelia, the Warleader', pn: 'Bloodhill Bastion', w: 2 }), H(20, 'dead', { star: true })],
  [P('sanctum', 'far'), P('bazaar', 'far'), P('chest', 'far')],
  [H(20, 'far'), H(25, 'far', { star: true }), H(20, 'far')],
  [P('event', 'far'), P('sanctum', 'far'), P('bazaar', 'far'), P('event', 'far')],
  [H(25, 'far'), H(25, 'far')],
  [P('chest', 'far'), P('sanctum', 'far'), P('event', 'far')],
  [H(30, 'far', { boss: true })],
];

/** The columns of the next row a node leads to, as RoguePath.getReachableNodes computes them (parts.mjs reach). */
const reach = (n: number, m: number, i: number): number[] => {
  const all = [...Array(m).keys()];
  if (n === 1) return all;
  if (i === 0) return m <= n ? [0] : [0, 1];
  if (i === n - 1) return m <= n ? [m - 1] : [m - 2, m - 1];
  if (m === n) return [i];
  const shift = m >= n ? 0 : Math.round(n / 2 - m / 2);
  return all.filter(c2 => c2 + shift === i || (n % 2 !== m % 2 && c2 + shift - i === 1));
};

const SIDES: Record<string, [string, string]> = {
  sanctum: ['Sanctum', 'Rest to regain life and cure all wounds or cook a random food item.'],
  bazaar: ['Bazaar', 'Spend your gold on cards and special offers.'],
  chest: ['Chest', 'Contains a random reward, like gold, cards from your Reward Pool, or permanent buffs that last for the rest of the Run.'],
  event: ['Event', 'A random encounter.'],
};
const STATES: Record<string, string> = { sel: 'selected' };
const TERM = (name: string, tip: string): Part => ({ kind: 'term', text: name, term: name, tip });
const MAP_TERMS: Record<string, Part> = {
  PLANEBOUND: TERM('Planebound', 'The opponent you will face in a match dedicated to a specific Plane Node on the Map. Neither you nor the Planebound can planeswalk away from the Plane during the match.'),
  WRATHFUL: TERM('Wrathful', 'For each instance of Wrathful, the Planebound gets a minor advantage for the match.'),
  CURSED: TERM('Cursed', 'For each instance of Cursed, the Planebound gets a powerful advantage for the match.'),
  CARD_REWARD: TERM('Card Reward', 'A reward you receive after winning a Match during the Run. A default Card Reward consists of 6 non-mythic and 1 mythic Card from your Reward Pool and you can add up to 3 Cards to your deck.'),
};

/** The mockups' plane texts by plane. They label the chaos ability, which the game's text does not. */
export const PLANE_TEXT: Record<string, string> = {
  'selesnya-loft-gardens': 'If an effect would create one or more tokens, it creates twice that many instead. The same is true of counters put on a permanent.\nChaos: until end of turn, a land tapped for mana adds one more mana of any type it produced.',
  jund: 'Whenever a player casts a black, red, or green creature spell, it gains devour 5.\nChaos: create two 1/1 red Goblin creature tokens.',
};

export interface RunFigures { life: number; max: number; gold: number; echoes: number; credits: number }
export const RUN: RunFigures = { life: 14, max: 23, gold: 8, echoes: 52, credits: 3 };

/** The run's bar of the mockups, with the Descension line and its tip. */
export function runBar(r: RunFigures = RUN, line = 'Descension: Level 1 - Wrathful'): RogueRunBar {
  return {
    traits: 4, tip: 'Level 1 - Wrathful: Some Planebounds on the path become Wrathful, gaining random buffs for the match.',
    bar: {
      t: 'campaignBar', name: 'Aragorn, the Uniter', line, fresh: {},
      balances: [
        { icon: 'rogueLife', amount: r.life, label: 'Max. Life', text: `${r.life} / ${r.max}`, glyph: 'heart', term: 'Your life can only exceed Max-Life during a match.' },
        { icon: 'ICO_QUEST_COIN', amount: r.gold, label: 'Gold', term: 'Currency to spend during the Run, like for buying cards at a Bazaar or rerolling rewards.' },
        { icon: 'ICO_QUEST_GOLD', amount: r.echoes, label: 'Echoes', term: 'Persistent memories of the Aether used to build and upgrade Aetherworks.' },
        { icon: 'rogueCredits', amount: r.credits, label: 'Removal Credits', glyph: 'cut',
          term: 'Earned for each card added to your deck from a Card Reward or Bazaar. Can be spent to remove cards from your Deck. Basic lands can always be removed.' },
      ],
    },
  };
}

/** A copy of the trail to change one screen's nodes in. */
export const copyRows = (): Mock[][] => ROWS.map(r => r.map(n => ({ ...n })));

/** The pages of a run on its map, as the server sends them: the hub's, with the run's commander and deck size, and the map. */
export function onMap(rows: Mock[][] = ROWS, o: { bar?: RogueRunBar; rerolls?: number; hidden?: string; text?: Record<string, string>; cards?: number } = {}): ServerMessage[] {
  let index = 0;
  const texts = o.text ?? PLANE_TEXT;
  const nodes: MapNode[][] = rows.map(row => row.map((m): MapNode => {
    const base = { index: index++, state: STATES[m.st] ?? m.st, life: m.life ?? 0, elite: !!m.star, boss: !!m.boss, wrathful: m.w ?? 0, cursed: m.cu ?? 0 };
    if (m.kind) return { ...base, kind: m.kind.toUpperCase(), faceDown: false, name: SIDES[m.kind][0], text: SIDES[m.kind][1], gold: 0, echoes: 0, enter: `Enter ${SIDES[m.kind][0]}` };
    const [gold, echoes] = m.boss ? [0, 10] : m.star ? [4, 4] : [2, 2];
    if (m.hid) return { ...base, kind: 'PLANEBOUND', faceDown: true, gold, echoes, enter: 'Enter ???' };
    return { ...base, kind: 'PLANEBOUND', faceDown: false, name: m.on, plane: m.pn, art: `o-${m.opp}`, planeImage: `p-${m.plane}`, text: texts[m.plane!] ?? '',
      gold, echoes, enter: `Enter ${m.pn}` };
  }));
  const links = rows.slice(0, -1).flatMap((row, r) => row.flatMap((_, i) => reach(row.length, rows[r + 1].length, i).map(j => ({ from: nodes[r][i].index, to: nodes[r + 1][j].index }))));
  const selected = nodes.flat().find(n => n.state === 'selected')!.index;
  const home: RogueHome = { ...HOME, run: { ...HOME.run!, image: 'a-aragorn-the-uniter', cards: o.cards ?? 64 } };
  const map: RogueMap = {
    t: 'rogueMap', snapshot: 1, rows: nodes, links, selected, bar: o.bar ?? runBar(), rerolls: o.rerolls ?? 1, hidden: o.hidden, terms: MAP_TERMS,
    deck: { key: 'rogue:run', name: 'Aragorn, the Uniter', colors: '', stats: [], main: [], sideboard: [], deckSize: 64 } as unknown as RogueMap['deck'],
  };
  return [home, map];
}
