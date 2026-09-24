// Everything a player can do, at the table and around it. Renderers and screens call these and never see the
// protocol; the controller is the one place that turns them into messages, so a different renderer (a canvas board)
// drives the game the same way.

import type { PhaseType, Send, SetSeat, YieldAction } from './protocol';

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

  // Match setup
  leaveLobby(): void;
  setFormat(format: string): void;
  addSeat(): void;
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
  askPrintings(name: string): void;
  /** Answers a question the host asked outside a match. An empty choice is a cancel. */
  answerHostChoice(id: number, value: number[]): void;
}

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
    setFormat: format => send({ t: 'setFormat', format }),
    addSeat: () => send({ t: 'addSeat' }),
    removeSeat: index => send({ t: 'removeSeat', index }),
    openSeat: index => send({ t: 'openSeat', index }),
    aiSeat: index => send({ t: 'aiSeat', index }),
    setSeat: (index, change) => send({ t: 'setSeat', index, ...change }),
    setSleeveArt: (index, key, offset) => send({ t: 'sleeveArt', index, key, offset }),
    startMatch: spectate => send({ t: 'start', spectate }),
    askDeckDetails: key => send({ t: 'deckDetails', key }),
    fetchNetDecks: () => send({ t: 'netDecks' }),
    searchCards: query => send({ t: 'cardSearch', query }),
    askPrintings: name => send({ t: 'printings', name }),
    answerHostChoice: (id, value) => send({ t: 'hostChoice', id, value }),
  };
}
