// The browser's copy of the game: every object the server has sent, the prompt, the open questions and the table.
// Messages from the server change it here, and everything on the page is drawn from it.

import type { CardPoolDetails, DevState } from './protocol';
import type { Address, CataloguePage, EditorState, ImportResult, CardStateView, AutoDecisions, ChoicesRequest, CardView, Controls, DeckDetails, DrawOffer, DeckSummary, Detail, GameEvent, GameView, HostChoice, LobbyTable, Notice, Person, PlayerDetail, Playable, PlayerView, PlayerZone, Printing, Prompt, Ref, Refs, Request, SavedSleeveArt, ShownZone, StackMenu, StateMessage, TrackedObject, ZoneType, ExtraChoices, LimitedOptions, LimitedPools, DraftState, LimitedResult } from './protocol';

/** How many avatars and sleeves the skin's sprite sheets hold. */
export interface Looks {
  avatarCount: number;
  sleeveCount: number;
}

// Everything the browser knows. objects is its copy of the game's table, which forge.web.BrowserModel applies and prunes by the same rules
export interface Model {
  objects: Map<number, TrackedObject>;
  root: number;
  visible: Set<number>;
  localPlayers: number[];
  prompt: Prompt | null;
  zones: ShownZone[];
  requests: Map<number, Request>;
  gameOver: boolean;
  controls: Controls | null;
  playable: Playable | null;
  looks: Looks | null;
  spectating: boolean;
  inMatch: boolean;
  inLobby: boolean;
  /** A seat is being taken: the server is between the menu and the table. */
  joining: boolean;
  playerName: string;
  decks: DeckSummary[];
  error: string | null;
  lobby: LobbyTable | null;
  addresses: Address[] | null;
  host: boolean;
  canClaimHost: boolean;
  /** What the game did since the board was last drawn, oldest first. The render hands them to whatever animates
   *  them and empties the list, so each is shown once. */
  events: GameEvent[];
  /** Rules text the server composed for cards and players the pointer has been over, kept for the match. */
  cardDetails: Map<number, Detail>;
  playerDetails: Map<number, PlayerDetail>;
  /** What a right-click on a stack item may do, as the server last answered it. */
  stackMenu: StackMenu | null;
  /** The conversation with the other players, in the lobby and beside the board. */
  /** A line with no sender is netplay announcing somebody coming or going. */
  chat: { from: string; text: string }[];
  /** Everyone on this server who has named themselves. */
  presence: Person[];
  /** Someone else is at the table, so there is someone to talk to. */
  networked: boolean;
  /** The formats a deck's cards can be checked against, for narrowing the deck list. */
  cardFormats: string[];
  /** The card pool the lobby holds decks to, which the deck finder pins. */
  deckCardPool: string | null;
  /** What the seat being edited may choose for a planar deck, scheme deck or avatar. */
  extraChoices: ExtraChoices | null;
  /** The deck last asked about, with its card list and statistics. */
  deckDetails: DeckDetails | null;
  /** Card names matching the last search, and the printings of the last name asked about, for picking sleeve art. */
  cardNames: string[];
  printings: { name: string; list: Printing[] } | null;
  /** Card art this installation has already sleeved a deck in. */
  savedSleeveArt: SavedSleeveArt[];
  /** A question the host is waiting on outside a match. */
  hostChoice: HostChoice | null;
  /** A name this browser remembered has been offered to the server, whose answer is not in yet. */
  nameSent: boolean;
  /** Messages from the server, each until it is dismissed or times out. */
  notices: { id: number; notice: Notice; view?: () => void; label?: string }[];
  /** A draw offer while it is open. */
  drawOffer: DrawOffer | null;
  /** The auto-yields and trigger answers the player has set, as last asked for. */
  autoDecisions: AutoDecisions | null;
  /** Where dev mode's switches stand for the host's seat, once asked. */
  devState: DevState | null;
  /** The deck open in the editor; the editor page shows while there is one. */
  editor: EditorState | null;
  /** The catalogue's rows so far: the pages asked for since the query last changed. */
  catalogue: CataloguePage | null;
  /** What reading the importer's list last found. */
  importResult: ImportResult | null;
  /** An import's or a pool's name belongs to one already, and the page asks what to do. */
  nameTaken: string | null;
  /** On the Limited pages; eventPool names the pool whose opponents screen shows. */
  inEvent: boolean;
  eventPool: string | null;
  /** How many sealed pools are saved, for the menu. */
  sealedPools: number;
  draftPools: number;
  /** Which Limited pages are open: 'sealed' or 'draft'. */
  eventKind: string | null;
  /** An offline draft is running, and draft is its latest state. */
  drafting: boolean;
  draft: DraftState | null;
  /** Where a limited gauntlet stands after its last game; null outside a gauntlet. */
  limitedResult: LimitedResult | null;
  limitedOptions: LimitedOptions | null;
  /** The card pool picker's lines and old snapshots, asked for the first time it opens. */
  cardPoolDetails: CardPoolDetails | null;
  limitedPools: LimitedPools | null;
}

export function createModel(): Model {
  return {
    objects: new Map(), root: -1, visible: new Set(), localPlayers: [],
    prompt: null, zones: [], requests: new Map(), gameOver: false, controls: null, playable: null,
    looks: null, spectating: false,
    inMatch: false, inLobby: false, joining: false, playerName: '', decks: [], error: null,
    lobby: null, addresses: null, host: true, canClaimHost: false, events: [],
    cardDetails: new Map(), playerDetails: new Map(), stackMenu: null, chat: [], presence: [], networked: false,
    cardFormats: [], deckCardPool: null, extraChoices: null, deckDetails: null, cardNames: [], printings: null, savedSleeveArt: [], hostChoice: null, nameSent: false, notices: [],
    drawOffer: null, autoDecisions: null, devState: null, editor: null, catalogue: null, importResult: null, nameTaken: null,
    inEvent: false, eventPool: null, sealedPools: 0, draftPools: 0, eventKind: null, drafting: false, draft: null, limitedResult: null,
    limitedOptions: null, cardPoolDetails: null, limitedPools: null,
  };
}

export function applyState(model: Model, msg: StateMessage): void {
  if (msg.full) {
    model.objects.clear();
    model.root = msg.root;
    // A whole new table has nothing to move from
    model.events = [];
  }
  model.events.push(...msg.events);
  for (const [k, props] of Object.entries(msg.newObjects)) {
    model.objects.set(Number(k), { ...withoutNulls(props), $key: Number(k) });
  }
  for (const [k, props] of Object.entries(msg.deltas)) {
    const target = model.objects.get(Number(k)) as Record<string, unknown> | undefined;
    if (!target) continue;
    for (const [name, value] of Object.entries(props)) {
      if (value === null) delete target[name];
      else target[name] = value;
    }
  }
  // Only a new object, or a property cleared or given an object or list, can leave something unreachable. Most
  // packets only change numbers and flags, and walking every object for each of them is wasted.
  const mayOrphan = msg.full || Object.keys(msg.newObjects).length > 0
    || Object.values(msg.deltas).some(props => Object.values(props).some(v => v === null || typeof v === 'object'));
  if (mayOrphan) prune(model);
  model.visible = new Set(msg.visible);
  model.localPlayers = msg.localPlayers;
}

/** A property still at its default is left out of an object, so a null in a new one means the same. */
function withoutNulls<T extends object>(props: T): T {
  return Object.fromEntries(Object.entries(props).filter(([, v]) => v !== null)) as T;
}

// Packets carry no removal signal, so anything the game no longer reaches is dropped here
function prune(model: Model): void {
  if (!model.objects.has(model.root)) return;
  const reachable = new Set<number>();
  const todo = [model.root];
  while (todo.length) {
    const key = todo.pop() as number;
    if (reachable.has(key)) continue;
    reachable.add(key);
    const o = model.objects.get(key);
    if (o) collectRefs(o, todo);
  }
  for (const key of [...model.objects.keys()]) {
    if (!reachable.has(key)) model.objects.delete(key);
  }
}

function collectRefs(value: unknown, out: number[]): void {
  if (Array.isArray(value)) {
    value.forEach(v => collectRefs(v, out));
  } else if (value && typeof value === 'object') {
    const keys = Object.keys(value);
    if (keys.length === 1 && keys[0] === 'ref') {
      out.push((value as Ref).ref);
      return;
    }
    for (const [k, v] of Object.entries(value)) {
      if (k !== '$key') collectRefs(v, out);
    }
  }
}

/** The game's oldest open question, which is the one shown. */
export function oldestRequest(model: Model): Request | undefined {
  return [...model.requests.values()].sort((a, b) => a.id - b.id)[0];
}

/** The question the game is asking, when it is which of one clicked card's abilities to use: a short list of words,
 *  answered from a menu at the pointer rather than a dialog. */
export function cardMenu(model: Model): ChoicesRequest | null {
  const oldest = oldestRequest(model);
  return oldest?.kind === 'choices' && oldest.max === 1 && (oldest.atX != null || oldest.atY != null)
    && oldest.options.every(o => !o.card && !o.imageKey && !o.player) ? oldest : null;
}

/** The question the game is asking, when it is which spell on the stack to choose: that is answered by clicking
 *  the spell where it already is, rather than from a list. */
export function stackPick(model: Model): ChoicesRequest | null {
  const oldest = oldestRequest(model);
  return oldest?.kind === 'choices' && oldest.stackKeys ? oldest : null;
}

export const deref = (model: Model, v: Ref | null | undefined): TrackedObject | undefined =>
  (v && typeof v === 'object' && 'ref' in v) ? model.objects.get(v.ref) : undefined;
export const derefAll = (model: Model, list: Refs | null | undefined): TrackedObject[] =>
  (list ?? []).map(v => deref(model, v)).filter((o): o is TrackedObject => !!o);
export const game = (model: Model): GameView | undefined => model.objects.get(model.root);
export const players = (model: Model): PlayerView[] => derefAll(model, game(model)?.Players);
export const isLocal = (model: Model, player: PlayerView): boolean => model.localPlayers.includes(player.$key);
export const me = (model: Model): PlayerView | undefined => players(model).find(p => isLocal(model, p));
export const opponents = (model: Model): PlayerView[] => players(model).filter(p => !isLocal(model, p));
const PLAYER_ZONES: ReadonlySet<string> = new Set<PlayerZone>(['Hand', 'Library', 'Graveyard', 'Battlefield', 'Exile',
  'Flashback', 'Command', 'Sideboard', 'Ante', 'SchemeDeck', 'PlanarDeck', 'AttractionDeck', 'Junkyard', 'ContraptionDeck']);
const isPlayerZone = (name: ZoneType): name is PlayerZone => PLAYER_ZONES.has(name);

/** The cards in one of a player's zones. A zone that is not a player's (the stack, a merged pile) holds none. */
export const zone = (model: Model, player: PlayerView | undefined, name: ZoneType): CardView[] =>
  isPlayerZone(name) ? derefAll(model, player?.[name]) : [];
export const stateOf = (model: Model, card: CardView): Partial<CardStateView> => deref(model, card.CurrentState) ?? {};
