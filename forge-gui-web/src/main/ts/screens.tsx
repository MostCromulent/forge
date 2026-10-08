// Everything outside the board is drawn with Preact, and the board by hand (board.ts) because it is placed by measuring

import { render } from 'preact';
import { Menu, NamePrompt, rememberedName } from './menu';
import { Lobby } from './lobby/lobby';
import { Editor } from './deck/editor';
import { Limited } from './limited/limited';
import { Drafting } from './limited/drafting';
import { Conquest } from './conquest/conquest';
import { Quest } from './quest/quest';
import { Importer } from './deck/importer';
import { DeckFinder } from './deck/deckfinder';
import { Requests } from './match/dialogs';
import { HostChoice } from './hostchoice';
import { Options } from './options';
import { AutoDecisionsDialog, AutoPassStops, DrawOfferQuestion, GameMenu } from './match/gamemenu';
import { DevSetupDialog } from './match/devmenu';
import { Volume } from './volume';
import { Notices } from './notices';
import { Dock } from './match/dock';
import { MenuSheet } from './match/menusheet';
import { isPortrait } from './form';
import { byId } from './dom';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';

/** Whether the deck editor was open at the last frame, and how many times it has shut. */
let editing = false;
let edits = 0;

/** How far in each page is from the first one, so a move to a lower number is a move back. */
const DEPTH = { name: 0, menu: 1, lobby: 2, limited: 2, drafting: 2, conquest: 2, quest: 2, editor: 3, match: 4 };
let shown: keyof typeof DEPTH | null = null;

export function renderScreens(model: Model, actions: Actions, dismissNotice: (id: number) => void): void {
  // A screen that is not showing is not drawn, so what it held (a picker, a half-typed search) goes with it
  const page = screenOf(model);
  const under = pageUnder(model);
  if (page !== shown) {
    // Stays until the next change of page, because taking it off would start the arrival animation late
    document.documentElement.toggleAttribute('data-back', shown !== null && DEPTH[page] < DEPTH[shown]);
    shown = page;
  }
  // The page under the editor is drawn afresh when the editor shuts, since what it shows may be what was edited
  if (editing && page !== 'editor') edits++;
  editing = page === 'editor';
  render(under === 'name' ? <NamePrompt model={model} actions={actions} initial={rememberedName() ?? ''} />
    : under === 'menu' ? <Menu key={edits} model={model} actions={actions} /> : null, byId('menu'));
  render(under === 'lobby' ? <Lobby key={edits} model={model} actions={actions} /> : null, byId('lobby'));
  render(page === 'editor' ? <Editor model={model} actions={actions} /> : null, byId('editor'));
  render(under === 'limited' ? <Limited key={edits} model={model} actions={actions} /> : null, byId('limited'));
  render(under === 'drafting' ? <Drafting key={edits} model={model} actions={actions} /> : null, byId('drafting'));
  render(under === 'conquest' ? <Conquest key={edits} model={model} actions={actions} /> : null, byId('conquest'));
  render(under === 'quest' ? <Quest key={edits} model={model} actions={actions} /> : null, byId('quest'));
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
    {page === 'match' && ui.gameMenu === 'devSetup' && <DevSetupDialog actions={actions} close={() => changeUi(u => { u.gameMenu = null; })} />}
    {page === 'match' && <DrawOfferQuestion model={model} actions={actions} />}
    {page === 'match' && isPortrait() && ui.menuSheet && <MenuSheet model={model} actions={actions} />}
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
export function screenOf(model: Model): 'name' | 'menu' | 'lobby' | 'editor' | 'drafting' | 'limited' | 'conquest' | 'quest' | 'match' {
  // The editor sits over the page it was opened from without leaving it, so closing it returns there
  return model.editor && !model.inMatch && model.playerName ? 'editor' : pageUnder(model);
}

/** The page the deck editor lies over as a panel, which is the page showing when it is shut. */
export function pageUnder(model: Model): 'name' | 'menu' | 'lobby' | 'drafting' | 'limited' | 'conquest' | 'quest' | 'match' {
  if (model.inMatch) return 'match';
  if (!model.playerName) return 'name';
  // An online draft runs at a table, which the player may look at while it goes on
  if (model.drafting && !(model.inLobby && ui.draftHidden)) return 'drafting';
  if (model.inEvent) return 'limited';
  if (model.campaign === 'conquest') return 'conquest';
  if (model.campaign === 'quest') return 'quest';
  return model.inLobby ? 'lobby' : 'menu';
}
