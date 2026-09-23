// Everything outside the board is drawn with Preact: the start page, match setup and its pickers, the options,
// the game's questions and the chat. Each is a function of the model and the table's arrangement, drawn again on
// every frame the controller renders, and each acts only through Actions. The board is drawn by hand (board.ts),
// because it is placed by measuring and animated card by card.

import { render } from 'preact';
import { Menu } from './menu';
import { Lobby } from './lobby';
import { Requests } from './dialogs';
import { HostChoice } from './hostchoice';
import { Options } from './options';
import { Notices } from './notices';
import { ChatInput, ChatLog } from './chat';
import { byId } from './dom';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';

export function renderScreens(model: Model, actions: Actions, dismissNotice: (id: number) => void): void {
  // A screen that is not showing is not drawn, so what it held (a picker, a half-typed search) goes with it
  const page = model.inMatch ? 'match' : model.inLobby ? 'lobby' : 'menu';
  render(page === 'menu' ? <Menu model={model} actions={actions} /> : null, byId('menu'));
  render(page === 'lobby' ? <Lobby model={model} actions={actions} /> : null, byId('lobby'));
  render(page === 'match' && model.networked ? <>
    <ChatLog model={model} id="match-chat-log" />
    <ChatInput id="match-chat-in" say={actions.say} />
  </> : null, byId('match-chat'));
  render(<>
    {page === 'match' && <Requests model={model} actions={actions} />}
    {page === 'match' && ui.optionsOpen && (
      <Options close={() => changeUi(u => { u.optionsOpen = false; })} concede={() => actions.concede()} />
    )}
    {model.hostChoice && <HostChoice key={model.hostChoice.id} question={model.hostChoice} actions={actions} />}
  </>, byId('dialog-layer'));
  render(<Notices model={model} dismiss={dismissNotice} />, byId('notices'));
}
