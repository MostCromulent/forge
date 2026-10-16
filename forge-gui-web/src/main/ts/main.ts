// The controller, which is the only place that talks to the server and draws the model once per frame

import { connect } from './net';
import { createModel, applyState, cardMenu, isLocal, oldestRequest, players, stackPick } from './model';
// The campaign modes, which a build without them leaves out with this line
import './campaign/register';
import { campaigns } from './campaigns';
import { asked, createActions, type Actions } from './actions';
import { changeUi, initUi, resetMatchUi, ui } from './ui';
import { initForm, isPortrait } from './form';
import { initPress } from './press';
import { initSheets, matchHistory } from './sheet';
import { keyCommand, type KeyCommand } from './keys';
import { rememberName, rememberedAvatar, rememberedName } from './identity';
import { renderScreens, pageUnder, screenOf } from './screens';
import { readySplit } from './match/dialogs';
import { renderMatch, resetTable } from './match/board';
import { renderPrompt, flash, pressPromptButton } from './match/prompt';
import { appendLog, initLog, logTints } from './match/log';
import { initSide, renderSide, renderSky } from './match/side';
import { initDetail, nextFace, renderDetail } from './preview';
import { initStack } from './match/stack';
import { initOverlay, drawOverlay } from './match/overlay';
import { afterBlockDrags, initBlockDrag, renderBlockDrag } from './match/blockdrag';
import { boundKeys, initSettings, onServerSettings, restoreGuestSettings, setGuest } from './settings';
import { behindItsBoard, passingFor } from './match/pace';
import { createPacer } from './match/pacer';
import { setVersion } from './links';
import { applyAudioSettings, playMusic, playSound } from './audio';
import { rememberStops, restoreStops } from './match/stopmemory';
import { byId, relayout, saveText } from './dom';
import { initNotices, showNotice } from './notices';
import { forgetWakeLock, keepAwake } from './wakelock';
import { deleteDeviceDeck, listDeviceDecks, putDeviceDeck } from './deck/devicedecks';
import type { Send, ServerMessage } from './protocol';

const model = createModel();
const pacer = createPacer(model, apply);
let scheduled = false;
// Asked once, so a slow answer is not asked for again on every message that arrives meanwhile
let askedAddresses = false;
// A guest's settings live in its session on the server, so each connection is given back what the browser remembers
let restored = false;
// A guest's decks live in its browser, so each connection tells the server which it holds
let sentDeviceDecks = false;
// A sound or a log line is sent ahead of the board it belongs to, and is taken after it, or alone after 300 ms when no board follows (pace.ts)
const send = connect(behindItsBoard(msg => pacer.receive(msg), 300), online => {
  byId('banner').hidden = online;
  // The server replays the conversation for every connection, so the browser starts each one empty
  if (!online) {
    restored = false;
    sentDeviceDecks = false;
    model.chat = [];
  }
});

// A script error is otherwise seen only in the player's own browser. A few are enough to tell what broke.
let reported = 0;
const report = (text: string): void => {
  if (reported++ < 5) send({ t: 'pageError', text: text.slice(0, 1500) });
};
window.addEventListener('error', e => report(String((e.error as Error | undefined)?.stack ?? e.message)));
window.addEventListener('unhandledrejection', e => report(String((e.reason as Error | undefined)?.stack ?? e.reason)));

// While messages are held the prompt on screen is an earlier board's, so an answer to it would land on a prompt not yet seen
const HELD_INPUT = new Set(['selectCard', 'selectPlayer', 'selectStackItem', 'useMana', 'ok', 'cancel', 'endTurn', 'undo']);
const sendNow: Send = msg => {
  if (!pacer.holding() || !HELD_INPUT.has(msg.t)) send(msg);
  else console.warn(`Not sent while the page catches up: ${msg.t}`);
};
const wire = createActions(sendNow);
const actions = {
  ...wire,
  // Cancel on a pass stops the pass, which the host takes at any time, even while the page catches up
  cancel: () => (pacer.holding() && passingFor(model.controls) ? wire.stopYield() : wire.cancel()),
  ok: () => afterBlockDrags(() => wire.ok()),
  // An answered question leaves the model at once, so its dialog closes without waiting for the server
  answer: (id, value) => {
    model.requests.delete(id);
    wire.answer(id, value);
    schedule();
  },
  // An earlier answer is for an earlier menu, so the new one opens only when its own answer is in
  askStackMenu: key => {
    model.stackMenu = null;
    wire.askStackMenu(key);
  },
  // The engine thread waits on the answer, so the question goes as soon as it is answered
  answerHostChoice: (id, value) => {
    model.hostChoice = null;
    wire.answerHostChoice(id, value);
    schedule();
  },
  commitImport: c => {
    model.nameTaken = null;
    wire.commitImport(c);
  },
  addDeckFile: (text, format, clash) => {
    model.nameTaken = null;
    wire.addDeckFile(text, format, clash);
  },
  sealedCreate: c => {
    model.nameTaken = null;
    model.error = null;
    wire.sealedCreate(c);
  },
  ...campaigns()?.actions(sendNow, model, schedule),
} as Actions;

// The page's own actions and model, for the browser console and for e2e probes
Object.assign(window, { forge: { actions, model } });


initUi(schedule, render);
initForm(() => {
  // What only one layout draws is put away when the layout changes
  ui.menuSheet = null;
  ui.handOpen = false;
  ui.stopsOpen = false;
  ui.zonesFor = null;
  relayout();
  schedule();
});
initNotices(model, schedule);
initPress();
initSheets();
initDetail(actions);
initStack(actions);
initOverlay(schedule);
initBlockDrag(actions);
initLog();
initSide();
initSettings(actions.setSetting, () => {
  applyAudioSettings();
  schedule();
});

// Every key the page answers is decided in keys.ts, from what is open
document.addEventListener('keydown', e => {
  const target = e.target instanceof HTMLElement ? e.target : null;
  const command = keyCommand({
    key: e.key,
    typing: !!target && (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target.isContentEditable),
    modified: e.ctrlKey || e.altKey || e.metaKey,
  }, model, ui, boundKeys());
  if (!command) {
    return;
  }
  e.preventDefault();
  runKey(command);
});

function runKey(command: KeyCommand): void {
  switch (command) {
    case 'closeOptions': changeUi(u => { u.optionsOpen = false; }); break;
    case 'closeGameMenu': changeUi(u => { u.gameMenu = null; }); break;
    case 'confirmDistribute': {
      const split = readySplit();
      if (split && oldestRequest(model)?.id === split.id) actions.answer(split.id, split.values);
      break;
    }
    case 'closeReveal': {
      const reveal = oldestRequest(model);
      if (reveal) actions.answer(reveal.id, []);
      break;
    }
    case 'closeVolume': changeUi(u => { u.volumeOpen = false; }); break;
    case 'closeStackMenu': changeUi(u => { u.stackMenuAt = null; }); break;
    case 'stackAlwaysYes': if (ui.hoveredStackItem !== null) actions.stackYield(ui.hoveredStackItem, 'alwaysYes'); break;
    case 'stackAlwaysNo': if (ui.hoveredStackItem !== null) actions.stackYield(ui.hoveredStackItem, 'alwaysNo'); break;
    case 'closeStops': changeUi(u => { u.stopsOpen = false; }); break;
    case 'closeZones': changeUi(u => { u.openZones.clear(); u.zonesMinimised = false; }); break;
    case 'closePicker': changeUi(u => { u.picker = null; }); break;
    case 'closeImporter': changeUi(u => { u.importer = null; }); break;
    case 'closeBrowse': changeUi(u => { u.browse = null; }); break;
    case 'editorUndo': actions.editorUndo(); break;
    case 'declineHostChoice': if (model.hostChoice) actions.answerHostChoice(model.hostChoice.id, []); break;
    case 'ok': pressPromptButton('ok'); actions.ok(); break;
    case 'cancel': {
      pressPromptButton('cancel');
      const pick = stackPick(model);
      if (pick) actions.answer(pick.id, []); else actions.cancel();
      break;
    }
    case 'toggleAutoPass': pressPromptButton('auto-pass'); actions.toggleAutoPass(); break;
    case 'endTurn': pressPromptButton('end-turn'); if (model.controls?.untilEndOfTurn) actions.stopYield(); else actions.endTurn(); break;
    case 'undo': pressPromptButton('undo'); actions.undo(); break;
    case 'nextFace': nextFace(model); break;
    case 'cardText': changeUi(u => { u.cardText = !u.cardText; }); break;
    case 'startMatch': actions.startMatch(ui.spectate); break;
    case 'closeCardMenu': {
      const menu = cardMenu(model);
      if (menu) actions.answer(menu.id, []);
      break;
    }
    default: {
      const menu = cardMenu(model);
      if (menu && command.startsWith('pickCardMenu')) actions.answer(menu.id, [Number(command.slice(-1)) - 1]);
    }
  }
}

function apply(msg: ServerMessage): void {
  switch (msg.t) {
    case 'hello':
      model.host = msg.host;
      setGuest(!model.host);
      setVersion(msg.version);
      // Before any game opens, so the game is seeded with them rather than corrected afterwards
      if (!model.host && !restored) {
        restored = true;
        restoreGuestSettings();
        restoreStops(actions.setStops);
      }
      model.canClaimHost = msg.canClaimHost;
      onServerSettings(msg.settings);
      applyAudioSettings();
      model.screen = msg.screen;
      model.eventPool = msg.eventPool ?? null;
      model.sealedPools = msg.sealedPools;
      model.draftPools = msg.draftPools;
      // A gauntlet's result belongs to the game it followed; every new match arrives with a hello
      model.limitedResult = null;
      model.eventKind = msg.eventKind ?? null;
      model.campaign = msg.campaign ?? null;
      model.campaignSave = msg.campaignSave ?? null;
      model.campaignResume = msg.campaignResume;
      campaigns()?.hello(model, msg);
      model.drafting = msg.drafting;
      if (!msg.drafting) {
        model.draft = null;
        ui.draftHidden = false;
      }
      // A picker belongs to the table it was opened over
      if (model.screen !== 'setup') ui.picker = null;
      model.spectating = msg.spectating;
      model.playerName = msg.playerName ?? '';
      model.nameSent = false;
      if (model.playerName) {
        rememberName(model.playerName);
      } else if (!model.canClaimHost) {
        // While the host seat is free the prompt is shown instead, since it is where the seat is offered
        offerRememberedName();
      }
      model.looks = { avatarCount: msg.avatarCount, sleeveCount: msg.sleeveCount };
      model.savedSleeveArt = msg.sleeveArt ?? [];
      model.error = null;
      // A new lobby has an address and a conversation of its own; coming back to the same one keeps them
      if (msg.screen !== 'setup') {
        model.addresses = null;
        askedAddresses = false;
      }
      model.networked = msg.networked;
      // The server replays open requests after every hello
      model.requests.clear();
      if (msg.screen !== 'playing') {
        model.objects.clear();
        model.gameOver = false;
        model.matchScore = [];
        // Nothing draws the match once it is left, so a card still waiting to be paid for would float over the menu
        resetTable();
        send({ t: 'decks' });
      }
      if (!model.host && !sentDeviceDecks) {
        sentDeviceDecks = true;
        void listDeviceDecks().then(decks => actions.deviceDecks(decks));
      }
      break;
    case 'editor':
      // Back from a pool's deck, the pools are asked for again, since the deck just built changes them
      if (!msg.state && model.editor && model.screen === 'event') wire.limitedOpen(model.eventKind ?? 'sealed', false);
      model.editor = msg.state ?? null;
      break;
    // Scrolling asks for the next page of the same query, which is added to what is shown
    case 'catalogue':
      if (msg.request !== asked.catalogue) return;
      model.catalogue = msg.offset > 0 && model.catalogue ? { ...msg, rows: [...model.catalogue.rows, ...msg.rows] } : msg;
      break;
    case 'importResult':
      if (msg.request !== asked.import) return;
      model.importResult = msg;
      break;
    case 'nameTaken': model.nameTaken = msg.name; break;
    case 'limitedOptions': model.limitedOptions = msg; break;
    case 'cardPools': model.cardPools = msg.groups; break;
    case 'cardPoolDetails': model.cardPoolDetails = msg; break;
    case 'cardSets': model.cardSets = msg.sets; break;
    case 'limitedPools': model.limitedPools = msg; break;
    case 'limitedResult': model.limitedResult = msg; break;
    case 'draft':
      model.draft = msg;
      model.error = null;
      break;
    case 'deviceDeck':
      void (msg.text ? putDeviceDeck({ id: msg.id, text: msg.text, format: msg.format }) : deleteDeviceDeck(msg.id));
      return;
    case 'extraChoices':
      model.extraChoices = msg;
      break;
    case 'decks':
      model.decks = msg.decks;
      model.cardFormats = msg.cardFormats ?? [];
      model.deckCardPool = msg.cardPool ?? null;
      model.deckGen = msg.deckGen ?? null;
      break;
    case 'lobby': {
      model.lobby = msg.table ?? null;
      // Renaming your seat renames you, so the next server you reach knows you by it too
      const mine = model.lobby?.seats[model.lobby.mySeat]?.name;
      if (mine) rememberName(mine);
      // Finding the external address is a web request on the host, so it is asked for once per lobby
      if (!model.lobby?.shareable) {
        askedAddresses = false;
      } else if (!model.addresses && !askedAddresses) {
        askedAddresses = true;
        send({ t: 'addresses' });
      }
      break;
    }
    case 'addresses': model.addresses = msg.list; break;
    case 'chat': model.chat = [...model.chat, { from: msg.from ?? '', text: msg.text, earlier: msg.earlier }]; break;
    // The server sends the whole list whenever it changes, so there is nothing to merge
    case 'presence': model.presence = msg.people; break;
    case 'deckDetails': model.deckDetails = msg.deck; break;
    case 'deckMatches': model.deckMatches = { ...model.deckMatches, [msg.kind]: msg }; break;
    case 'cardSearch': model.cardNames = msg.names ?? []; break;
    case 'printings': model.printings = { name: msg.name, list: msg.printings ?? [] }; break;
    case 'hostChoice': model.hostChoice = msg; break;
    case 'error':
      // A name remembered from before can belong to someone else here, so the player is asked for another
      if (model.nameSent) {
        model.nameSent = false;
        rememberName(null);
      }
      model.error = msg.message;
      break;
    case 'state':
      applyState(model, msg);
      // The next game of a match reuses the card keys of the last one, so nothing keyed on them may survive
      if (msg.full) {
        model.gameOver = false;
        model.matchScore = [];
        model.prompt = null;
        model.drawOffer = null;
        model.zones = [];
        model.playable = null;
        model.cardDetails.clear();
        model.playerDetails.clear();
        model.stackMenu = null;
        resetMatchUi();
        resetTable();
      }
      break;
    case 'prompt': model.prompt = msg; break;
    case 'zones': model.zones = msg.show; break;
    case 'request':
      model.requests.set(msg.id, msg);
      break;
    case 'drawOffer': model.drawOffer = msg.open ? msg : null; break;
    case 'autoDecisions': model.autoDecisions = msg; break;
    case 'gameOver':
      model.gameOver = true;
      model.matchScore = msg.score;
      model.drawOffer = null;
      break;
    case 'sound': playSound(msg); return;
    case 'playable':
      model.playable = msg;
      break;
    case 'controls':
      model.controls = msg;
      onServerSettings(msg.settings);
      applyAudioSettings();
      if (!model.host) rememberStops(msg);
      break;
    case 'log':
      appendLog(msg, logTints(players(model).map(p => ({ name: p.Name ?? '', local: isLocal(model, p) }))));
      return;
    case 'detail': model.cardDetails.set(msg.key, msg); break;
    case 'playerDetail': model.playerDetails.set(msg.key, msg); break;
    case 'stackMenu': model.stackMenu = msg; break;
    case 'notice': showNotice(msg); break;
    case 'devState': model.devState = msg; break;
    case 'devDump': saveText(msg.text, 'game-state.txt', 'text/plain'); return;
    case 'deckFileText': saveText(msg.text, `${msg.name}.dck`, 'text/plain'); return;
    case 'flash': flash(); return;
    default: campaigns()?.message(model, msg); break;
  }
  schedule();
}

// A browser the server does not know yet, after a restart or on a first visit, is known by the name it last used
function offerRememberedName(): void {
  const name = rememberedName();
  if (name) {
    model.nameSent = true;
    send({ t: 'setName', name, avatar: rememberedAvatar() });
  }
}

// A file let go over the page would otherwise be opened in its place, ending the session
for (const kind of ['dragover', 'drop']) {
  addEventListener(kind, e => { if ((e as DragEvent).dataTransfer?.types.includes('Files')) e.preventDefault(); });
}
// The browser gives the wake lock up when the page is hidden, so the next draw asks for it again
document.addEventListener('visibilitychange', () => {
  forgetWakeLock();
  schedule();
});

function schedule(): void {
  if (scheduled) return;
  scheduled = true;
  requestAnimationFrame(() => { scheduled = false; render(); });
}

function render(): void {
  // What happened since the last frame is shown once, by this frame
  const events = model.events;
  model.events = [];
  const page = screenOf(model);
  const under = pageUnder(model);
  byId('editor').hidden = page !== 'editor';
  for (const id of ['menu', 'lobby', 'limited', 'drafting', 'campaign', 'match']) {
    const el = byId(id);
    // A phone has no room for a panel, so there the editor takes the screen and the page under it is put away
    el.hidden = id !== (under === 'name' ? 'menu' : under) || (page === 'editor' && isPortrait());
    // On a desktop the page under the editor is seen and not used
    el.inert = page === 'editor';
  }
  matchHistory(page === 'match');
  void keepAwake(page === 'match' && !model.gameOver);
  renderScreens(model, actions);
  // The name page comes before the player's volumes are known
  playMusic(page === 'name' ? null : page !== 'match' ? 'menu' : model.gameOver ? null : 'match');
  if (model.screen !== 'playing') {
    // Every page's cards share the one preview, so it is drawn with no match on too
    renderDetail(model);
    return;
  }
  renderSide(model);
  renderSky(model);
  renderMatch(model, actions, events);
  renderPrompt(model, actions);
  renderDetail(model);
  renderBlockDrag(model);
  drawOverlay(model);
}
