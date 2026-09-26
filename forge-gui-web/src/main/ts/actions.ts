// Everything a player can do, at the table and around it. Renderers and screens call these and never see the
// protocol; the controller is the one place that turns them into messages, so a different renderer (a canvas board)
// drives the game the same way.

import type {
  AutoDecisionAction, CatalogueQuery, DeckOp, DevAction, DeviceDeckText, EditorEdit, EventSetup, ImportCommit, PhaseType, SealedCreate, DraftStart, Send, SetSeat,
  YieldAction,
} from './protocol';

/** What a seat's owner can change about it. */
export type SeatChange = Omit<SetSeat, 't' | 'index'>;

export interface Actions {
  /** A click on a card. menu is the right button, which asks for everything the card can do; x and y place that list. */
  selectCard(key: number, menu: boolean, x: number, y: number): void;
  selectPlayer(key: number): void;
  /** Pays with one colour of the pool, by its bit in the player's Mana property. */
  useMana(color: number): void;
  ok(): void;
  cancel(): void;
  endTurn(): void;
  toggleAutoPass(): void;
  undo(): void;
  concede(): void;
  drawOffer(action: 'OFFER' | 'ACCEPT' | 'DECLINE'): void;
  autoDecisions(action: AutoDecisionAction, key?: string, on?: boolean): void;
  /** One of Forge's developer cheats, for the host's own seat. text is a game state to set up. */
  dev(action: DevAction, text?: string): void;
  nextGame(): void;
  quitMatch(): void;
  /** Leaves a finished match for the start page. */
  leave(): void;
  /** Answers one of the game's open questions. */
  answer(requestId: number, value: unknown): void;
  toggleStop(phase: PhaseType, mine: boolean): void;
  /** Every stop of one row at once; the rest of the row goes off. */
  setStops(mine: boolean, phases: PhaseType[]): void;
  /** Pass priority until this phase, or stop doing so. */
  toggleMarker(phase: PhaseType, mine: boolean): void;
  /** Asks for a card's rules text, or a player's details, which arrive later in the model. */
  inspectCard(key: number): void;
  inspectPlayer(key: number): void;
  askStackMenu(itemKey: number): void;
  stackYield(itemKey: number, action: YieldAction): void;
  say(text: string): void;
  setSetting(key: string, value: string): void;

  // The start page
  /** The name and face this browser plays under. The server refuses a name another player already has. */
  setName(name: string, avatar: number): void;
  /** Opens a table against the computer, or one others can join by link. */
  openLobby(invite: boolean): void;
  claimHost(): void;
  /** Tries again for a seat after a join found none free. */
  join(): void;
  quit(): void;

  // Limited
  /** Opens the Limited pages for a kind of event. */
  limitedOpen(kind: 'sealed' | 'draft'): void;
  draftStart(d: Omit<DraftStart, 't'>): void;
  /** Picks the card at index of the pack shown in state step. */
  draftPick(step: number, index: number): void;
  draftSave(name: string, replace: boolean): void;
  draftDiscard(): void;
  limitedLeave(): void;
  sealedCreate(c: Omit<SealedCreate, 't'>): void;
  /** A pool's opponents screen, and back from it. */
  poolOpen(name: string): void;
  poolClose(): void;
  poolEdit(name: string): void;
  poolDelete(name: string): void;
  /** Plays a pool: mode is one, several or gauntlet; opponent counts from 0 and count is how many for several. */
  poolPlay(name: string, mode: 'one' | 'several' | 'gauntlet', opponent: number, count: number, games: number): void;
  gauntletNext(): void;
  gauntletRestart(): void;

  // Online draft and sealed, at a table
  /** Opens a table others can join by link and makes it a draft or sealed table once it is open. */
  openLimitedTable(kind: 'sealed' | 'draft'): void;
  /** The table's Limited switch: a kind of event, or null for Constructed. */
  setLimited(kind: 'sealed' | 'draft' | null): void;
  eventSetup(s: Omit<EventSetup, 't'>): void;
  eventStart(): void;
  eventHostAgain(eventId: string): void;
  eventDecksOnly(on: boolean): void;
  benchSeat(index: number, benched: boolean): void;
  ready(on: boolean): void;

  // Match setup
  leaveLobby(): void;
  setFormat(format: string): void;
  setCardPool(cardPool: string | null): void;
  /** Where each format's cards come from, and the old snapshots, for the card pool picker; they arrive later in the model. */
  askCardPoolDetails(): void;
  setVariant(variant: string, on: boolean): void;
  setArchenemy(index: number): void;
  setSeatExtra(index: number, section: string, choice: string): void;
  askExtraChoices(index: number, section: string): void;
  /** How many seats the table has; seats come and go at the end, and never one a person holds. */
  setPlayerCount(count: number): void;
  removeSeat(index: number): void;
  /** Turns a seat between a computer and one someone can join. */
  openSeat(index: number): void;
  aiSeat(index: number): void;
  setSeat(index: number, change: SeatChange): void;
  /** Sleeves a seat's deck in a card's art, cropped at offset; an empty key goes back to the numbered sleeve. */
  setSleeveArt(index: number, key: string, offset: number): void;
  startMatch(spectate: boolean): void;
  /** Asks for a deck's card list and statistics, which arrive later in the model. */
  askDeckDetails(key: string): void;
  fetchNetDecks(): void;
  /** Card names and their printings, for picking a card's art; both arrive later in the model. */
  searchCards(query: string): void;
  /** A card's printings; cardPool marks those outside it. They arrive later in the model. */
  askPrintings(name: string, cardPool?: string | null): void;
  /** Answers a question the host asked outside a match. An empty choice is a cancel. */
  answerHostChoice(id: number, value: number[]): void;

  // Decks: the editor and the importer
  /** The format the start page's deck finder lists. */
  browseFormat(format: string): void;
  openEditor(o: { key?: string; newFormat?: string; seat?: number; copy?: boolean }): void;
  closeEditor(): void;
  editorUndo(): void;
  edit(e: Omit<EditorEdit, 't'>): void;
  renameDeck(name: string): void;
  setCheck(format: string, cardPool: string | null, unrestricted: boolean): void;
  deckOp(op: DeckOp): void;
  /** A page of the catalogue. request numbers the query, so an answer to an older one can be told apart. */
  queryCatalogue(request: number, q: Omit<CatalogueQuery, 't' | 'request'>): void;
  readImport(request: number, text: string, format: string, cardPool: string | null, unrestricted: boolean): void;
  fetchImport(request: number, url: string): void;
  commitImport(c: Omit<ImportCommit, 't'>): void;
  deviceDecks(decks: DeviceDeckText[]): void;
}

/** The kind of event a table opened from the start page is to be, until the table arrives and is switched to it. */
export const pendingTable: { kind: 'sealed' | 'draft' | null } = { kind: null };

export function createActions(send: Send): Actions {
  return {
    selectCard: (key, menu, x, y) => send({ t: 'selectCard', key, menu, x: Math.round(x), y: Math.round(y) }),
    selectPlayer: key => send({ t: 'selectPlayer', key }),
    useMana: color => send({ t: 'useMana', color }),
    ok: () => send({ t: 'ok' }),
    cancel: () => send({ t: 'cancel' }),
    endTurn: () => send({ t: 'endTurn' }),
    toggleAutoPass: () => send({ t: 'autoPass' }),
    undo: () => send({ t: 'undo' }),
    concede: () => send({ t: 'concede' }),
    drawOffer: action => send({ t: 'drawOffer', action }),
    autoDecisions: (action, key, on = false) => send({ t: 'autoDecisions', action, key, on }),
    dev: (action, text) => send({ t: 'dev', action, text }),
    nextGame: () => send({ t: 'nextGame', decision: 'CONTINUE' }),
    quitMatch: () => send({ t: 'nextGame', decision: 'QUIT' }),
    leave: () => send({ t: 'leave' }),
    answer: (id, value) => send({ t: 'reply', id, value }),
    toggleStop: (phase, mine) => send({ t: 'toggleStop', phase, mine }),
    setStops: (mine, phases) => send({ t: 'setStops', mine, phases }),
    toggleMarker: (phase, mine) => send({ t: 'toggleMarker', phase, mine }),
    inspectCard: key => send({ t: 'detail', key }),
    inspectPlayer: key => send({ t: 'playerDetail', key }),
    askStackMenu: key => send({ t: 'stackMenu', key }),
    stackYield: (key, action) => send({ t: 'stackYield', key, action }),
    say: text => send({ t: 'chat', text }),
    setSetting: (key, value) => send({ t: 'setSetting', key, value }),
    setName: (name, avatar) => send({ t: 'setName', name, avatar }),
    openLobby: invite => send({ t: invite ? 'invite' : 'lobby' }),
    claimHost: () => send({ t: 'claimHost' }),
    join: () => send({ t: 'join' }),
    quit: () => send({ t: 'quit' }),
    leaveLobby: () => send({ t: 'leaveLobby' }),
    openLimitedTable: kind => {
      pendingTable.kind = kind;
      send({ t: 'invite' });
    },
    setLimited: kind => send(kind ? { t: 'setLimited', kind } : { t: 'setLimited' }),
    eventSetup: s => send({ t: 'eventSetup', ...s }),
    eventStart: () => send({ t: 'eventStart' }),
    eventHostAgain: eventId => send({ t: 'eventHostAgain', eventId }),
    eventDecksOnly: on => send({ t: 'eventDecksOnly', on }),
    benchSeat: (index, benched) => send({ t: 'benchSeat', index, benched }),
    ready: on => send({ t: 'ready', ready: on }),
    limitedOpen: kind => send({ t: 'limitedOpen', kind }),
    draftStart: d => send({ t: 'draftStart', ...d }),
    draftPick: (step, index) => send({ t: 'draftPick', step, index }),
    draftSave: (name, replace) => send({ t: 'draftSave', name, replace }),
    draftDiscard: () => send({ t: 'draftDiscard' }),
    limitedLeave: () => send({ t: 'limitedLeave' }),
    sealedCreate: c => send({ t: 'sealedCreate', ...c }),
    poolOpen: name => send({ t: 'poolOpen', name }),
    poolClose: () => send({ t: 'poolClose' }),
    poolEdit: name => send({ t: 'poolEdit', name }),
    poolDelete: name => send({ t: 'poolDelete', name }),
    poolPlay: (name, mode, opponent, count, games) => send({ t: 'poolPlay', name, mode, opponent, count, games }),
    gauntletNext: () => send({ t: 'gauntletNext' }),
    gauntletRestart: () => send({ t: 'gauntletRestart' }),
    setFormat: format => send({ t: 'setFormat', format }),
    setVariant: (variant, on) => send({ t: 'setVariant', variant, on }),
    setArchenemy: index => send({ t: 'setArchenemy', index }),
    setSeatExtra: (index, section, choice) => send({ t: 'setSeatExtra', index, section, choice }),
    askExtraChoices: (index, section) => send({ t: 'extraChoices', index, section }),
    setCardPool: cardPool => send(cardPool ? { t: 'setCardPool', cardPool } : { t: 'setCardPool' }),
    askCardPoolDetails: () => send({ t: 'cardPoolDetails' }),
    setPlayerCount: count => send({ t: 'setPlayerCount', count }),
    removeSeat: index => send({ t: 'removeSeat', index }),
    openSeat: index => send({ t: 'openSeat', index }),
    aiSeat: index => send({ t: 'aiSeat', index }),
    setSeat: (index, change) => send({ t: 'setSeat', index, ...change }),
    setSleeveArt: (index, key, offset) => send({ t: 'sleeveArt', index, key, offset }),
    startMatch: spectate => send({ t: 'start', spectate }),
    askDeckDetails: key => send({ t: 'deckDetails', key }),
    fetchNetDecks: () => send({ t: 'netDecks' }),
    searchCards: query => send({ t: 'cardSearch', query }),
    askPrintings: (name, cardPool) => send(cardPool ? { t: 'printings', name, cardPool } : { t: 'printings', name }),
    browseFormat: format => send({ t: 'browseFormat', format }),
    openEditor: o => send({ t: 'editorOpen', ...o, copy: !!o.copy }),
    closeEditor: () => send({ t: 'editorClose' }),
    editorUndo: () => send({ t: 'editorUndo' }),
    edit: e => send({ t: 'editorEdit', ...e }),
    renameDeck: name => send({ t: 'editorRename', name }),
    setCheck: (format, cardPool, unrestricted) => send(cardPool ? { t: 'editorCheck', format, cardPool, unrestricted } : { t: 'editorCheck', format, unrestricted }),
    deckOp: op => send({ t: 'editorDeck', op }),
    queryCatalogue: (request, q) => send({ t: 'catalogue', request, ...q }),
    readImport: (request, text, format, cardPool, unrestricted) =>
      send(cardPool ? { t: 'importRead', request, text, format, cardPool, unrestricted } : { t: 'importRead', request, text, format, unrestricted }),
    fetchImport: (request, url) => send({ t: 'importFetch', request, url }),
    commitImport: c => send({ t: 'importCommit', ...c }),
    deviceDecks: decks => send({ t: 'deviceDecks', decks }),
    answerHostChoice: (id, value) => send({ t: 'hostChoice', id, value }),
  };
}
