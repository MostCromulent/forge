// Everything outside the board is drawn with Preact: the start page, match setup and its pickers, the options,
// the game's questions and the chat. Each is a function of the model and the table's arrangement, drawn again on
// every frame the controller renders, and each acts only through Actions. The board is drawn by hand (board.ts),
// because it is placed by measuring and animated card by card.

import { render } from 'preact';
import { Menu, NamePrompt, rememberedName } from './menu';
import { Lobby } from './lobby';
import { Editor } from './editor';
import { Limited } from './limited';
import { Drafting } from './drafting';
import { Importer } from './importer';
import { DeckFinder } from './deckfinder';
import { RevealWindow, Requests } from './dialogs';
import { HostChoice } from './hostchoice';
import { Options } from './options';
import { AutoDecisionsDialog, AutoPassStops, DrawOfferQuestion, GameMenu } from './gamemenu';
import { KeysDialog } from './keysdialog';
import { DevSetupDialog } from './devmenu';
import { Volume } from './volume';
import { Notices } from './notices';
import { Dock } from './dock';
import { byId } from './dom';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';

export function renderScreens(model: Model, actions: Actions, dismissNotice: (id: number) => void): void {
  // A screen that is not showing is not drawn, so what it held (a picker, a half-typed search) goes with it
  const page = screenOf(model);
  render(page === 'name' ? <NamePrompt model={model} actions={actions} initial={rememberedName() ?? ''} />
    : page === 'menu' ? <Menu model={model} actions={actions} /> : null, byId('menu'));
  render(page === 'lobby' ? <Lobby model={model} actions={actions} /> : null, byId('lobby'));
  render(page === 'editor' ? <Editor model={model} actions={actions} /> : null, byId('editor'));
  render(page === 'limited' ? <Limited model={model} actions={actions} /> : null, byId('limited'));
  render(page === 'drafting' ? <Drafting model={model} actions={actions} /> : null, byId('drafting'));
  // The dock has two homes: the bottom edge before a match, the side column under the log during one
  render(page === 'match' && model.networked ? <Dock model={model} actions={actions} /> : null, byId('match-chat'));
  render(page !== 'match' ? <Dock model={model} actions={actions}
    rename={page === 'menu' ? () => changeUi(u => { u.renaming = true; }) : undefined} /> : null, byId('dock'));
  render(<>
    {page === 'match' && <Requests model={model} actions={actions} />}
    {page === 'match' && ui.volumeOpen && <Volume close={() => changeUi(u => { u.volumeOpen = false; })} />}
    {page !== 'match' && ui.volumeOpen && <Volume anchor=".page-head .volume" close={() => changeUi(u => { u.volumeOpen = false; })} />}
    {page !== 'name' && ui.optionsOpen && <Options close={() => changeUi(u => { u.optionsOpen = false; })} />}
    {page === 'match' && ui.gameMenu === 'menu' && (
      <GameMenu model={model} actions={actions} close={() => changeUi(u => { u.gameMenu = null; })}
        open={dialog => changeUi(u => { u.gameMenu = dialog; })} />
    )}
    {page === 'match' && ui.gameMenu === 'decisions' && (
      <AutoDecisionsDialog model={model} actions={actions} close={() => changeUi(u => { u.gameMenu = null; })} />
    )}
    {page === 'match' && ui.gameMenu === 'stops' && <AutoPassStops close={() => changeUi(u => { u.gameMenu = null; })} />}
    {page === 'match' && ui.gameMenu === 'keys' && <KeysDialog close={() => changeUi(u => { u.gameMenu = null; })} />}
    {page === 'match' && ui.gameMenu === 'devSetup' && <DevSetupDialog actions={actions} close={() => changeUi(u => { u.gameMenu = null; })} />}
    {page === 'match' && <DrawOfferQuestion model={model} actions={actions} />}
    {page === 'match' && ui.viewing && (
      <RevealWindow model={model} title={ui.viewing.title} cards={ui.viewing.cards} close={() => changeUi(u => { u.viewing = null; })} />
    )}
    {page !== 'match' && ui.importer && (
      <Importer model={model} actions={actions} from={ui.importer.from} seat={ui.importer.seat} initialText={ui.importer.text}
        initialUrl={ui.importer.url} sync={ui.importer.sync}
        close={() => changeUi(u => { u.importer = null; })} />
    )}
    {page === 'menu' && ui.browse && <DeckFinder model={model} actions={actions} close={() => changeUi(u => { u.browse = null; })} />}
    {model.hostChoice && <HostChoice key={model.hostChoice.id} question={model.hostChoice} actions={actions} />}
  </>, byId('dialog-layer'));
  render(<Notices model={model} dismiss={dismissNotice} />, byId('notices'));
}

/** Which page is showing. A browser without a name is asked for one before it goes anywhere. */
export function screenOf(model: Model): 'name' | 'menu' | 'lobby' | 'editor' | 'drafting' | 'limited' | 'match' {
  if (model.inMatch) return 'match';
  if (!model.playerName) return 'name';
  // The editor sits over the menu or the table without leaving either, so closing it returns to where it was opened
  if (model.editor) return 'editor';
  // An online draft runs at a table, which the player may look at while it goes on
  if (model.drafting && !(model.inLobby && ui.draftHidden)) return 'drafting';
  if (model.inEvent) return 'limited';
  return model.inLobby ? 'lobby' : 'menu';
}
