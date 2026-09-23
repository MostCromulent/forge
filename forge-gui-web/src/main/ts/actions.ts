// Everything a player can do at the table. Renderers call these and never see the protocol; the controller is the
// one place that turns them into messages, so a different renderer (a canvas board) drives the game the same way.

import type { PhaseType, Send, YieldAction } from './protocol';

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
  /** Pass priority until this phase, or stop doing so. */
  toggleMarker(phase: PhaseType, mine: boolean): void;
  /** Asks for a card's rules text, or a player's details, which arrive later in the model. */
  inspectCard(key: number): void;
  inspectPlayer(key: number): void;
  askStackMenu(itemKey: number): void;
  stackYield(itemKey: number, action: YieldAction): void;
  say(text: string): void;
  setSetting(key: string, value: string): void;
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
    toggleMarker: (phase, mine) => send({ t: 'toggleMarker', phase, mine }),
    inspectCard: key => send({ t: 'detail', key }),
    inspectPlayer: key => send({ t: 'playerDetail', key }),
    askStackMenu: key => send({ t: 'stackMenu', key }),
    stackYield: (key, action) => send({ t: 'stackYield', key, action }),
    say: text => send({ t: 'chat', text }),
    setSetting: (key, value) => send({ t: 'setSetting', key, value }),
  };
}
