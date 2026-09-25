// The controller. It is the only place that talks to the server: messages from it update the model, what the player
// does arrives as actions and leaves as messages, and one render per frame draws the model and the table's
// arrangement. Nothing it draws with sends anything itself.

import { connect } from './net';
import { createModel, applyState, cardMenu, isLocal, oldestRequest, players } from './model';
import { createActions, type Actions } from './actions';
import { changeUi, initUi, resetMatchUi, ui } from './ui';
import { keyCommand, type KeyCommand } from './keys';
import { rememberName, rememberedAvatar, rememberedName } from './menu';
import { renderScreens, screenOf } from './screens';
import { renderMatch, resetTable } from './board';
import { renderPrompt, flash } from './prompt';
import { appendLog, initLog, logTints } from './log';
import { initSide, renderSide, renderSky } from './side';
import { initDetail, nextFace, renderDetail } from './detail';
import { initStack } from './stack';
import { initOverlay, drawOverlay } from './overlay';
import { boundKeys, initSettings, onServerSettings, restoreGuestSettings, setGuest } from './settings';
import { applyAudioSettings, playSound, startMusic, stopMusic } from './audio';
import { countdown, dropCountdown, finishCountdown, initAutoPass, startCountdown } from './autopass';
import { createStopMemory, localStopStore } from './stopmemory';
import { byId, saveText } from './dom';
import { initNotices } from './notices';
import { deleteDeviceDeck, listDeviceDecks, putDeviceDeck } from './devicedecks';
import type { Notice, ServerMessage } from './protocol';

const model = createModel();
let scheduled = false;
// Asked once, so a slow answer is not asked for again on every message that arrives meanwhile
let askedAddresses = false;
// A guest's settings live in its session on the server, so each connection is given back what the browser remembers
let restored = false;
// A guest's decks live in its browser, so each connection tells the server which it holds
let sentDeviceDecks = false;
// Queries are numbered, so an answer to one the player has since changed is dropped
let catalogueRequest = 0;
let importRequest = 0;
// The game is paced where it runs: it holds for the player on a pass they have something new to see before
// (autopass.ts), so every message is shown as it arrives
const send = connect(apply, online => {
  byId('banner').hidden = online;
  // The server replays the conversation for every connection, so the browser starts each one empty
  if (!online) {
    restored = false;
    sentDeviceDecks = false;
    model.chat = [];
  }
});

const wire = createActions(send);
initAutoPass((id, go) => wire.answer(id, go), () => schedule());
const actions: Actions = {
  ...wire,
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
  // A browser plays nothing before the player does something, so the music starts on the click or key that starts
  startMatch: spectate => {
    startMusic();
    wire.startMatch(spectate);
  },
  // The engine thread waits on the answer, so the question goes as soon as it is answered
  answerHostChoice: (id, value) => {
    model.hostChoice = null;
    wire.answerHostChoice(id, value);
    schedule();
  },
  queryCatalogue: (_, q) => wire.queryCatalogue(++catalogueRequest, q),
  readImport: (_, text, format, cardPool, unrestricted) => wire.readImport(++importRequest, text, format, cardPool, unrestricted),
  fetchImport: (_, url) => wire.fetchImport(++importRequest, url),
  commitImport: c => {
    model.nameTaken = null;
    wire.commitImport(c);
  },
  sealedCreate: c => {
    model.nameTaken = null;
    model.error = null;
    wire.sealedCreate(c);
  },
};

const stopMemory = createStopMemory(localStopStore('forge.guestStops'));

initUi(schedule);
initNotices((notice, view, label) => {
  notify(notice, view, NOTICE_MS, label);
  schedule();
});
initDetail(actions);
initStack(actions);
initOverlay(schedule);
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
  }, model, ui, !!countdown(), boundKeys());
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
    case 'closeViewing': changeUi(u => { u.viewing = null; }); break;
    case 'closeReveal': {
      const reveal = oldestRequest(model);
      if (reveal) actions.answer(reveal.id, []);
      break;
    }
    case 'closeVolume': changeUi(u => { u.volumeOpen = false; }); break;
    case 'closeStackMenu': changeUi(u => { u.stackMenuAt = null; }); break;
    case 'closeStops': changeUi(u => { u.stopsOpen = false; }); break;
    case 'closePicker': changeUi(u => { u.picker = null; }); break;
    case 'closeImporter': changeUi(u => { u.importer = null; }); break;
    case 'closeBrowse': changeUi(u => { u.browse = null; }); break;
    case 'editorUndo': actions.editorUndo(); break;
    case 'declineHostChoice': if (model.hostChoice) actions.answerHostChoice(model.hostChoice.id, []); break;
    case 'ok': actions.ok(); break;
    case 'cancel': actions.cancel(); break;
    case 'passNow': finishCountdown(true); break;
    case 'stopAutoPass': finishCountdown(false); break;
    case 'endTurn': actions.endTurn(); break;
    case 'undo': actions.undo(); break;
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
      // Before any game opens, so the game is seeded with them rather than corrected afterwards
      if (!model.host && !restored) {
        restored = true;
        restoreGuestSettings();
        stopMemory.restore(actions.setStops);
      }
      model.canClaimHost = msg.canClaimHost;
      model.inMatch = msg.inMatch;
      model.inLobby = msg.inLobby;
      model.inEvent = msg.inEvent;
      model.eventPool = msg.eventPool ?? null;
      model.sealedPools = msg.sealedPools;
      // A picker belongs to the table it was opened over
      if (!model.inLobby) ui.picker = null;
      model.joining = msg.joining;
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
      // A new lobby has an address and a conversation of its own
      model.addresses = null;
      askedAddresses = false;
      model.networked = msg.networked;
      // The server replays open requests after every hello
      model.requests.clear();
      dropCountdown();
      if (!msg.inMatch) {
        model.objects.clear();
        model.gameOver = false;
        send({ t: 'decks' });
      }
      if (!model.host && !sentDeviceDecks) {
        sentDeviceDecks = true;
        void listDeviceDecks().then(decks => actions.deviceDecks(decks));
      }
      break;
    case 'editor':
      // Back from a pool's deck, the pools are asked for again, since the deck just built changes them
      if (!msg.state && model.editor && model.inEvent) wire.limitedOpen('sealed');
      model.editor = msg.state ?? null;
      break;
    // Scrolling asks for the next page of the same query, which is added to what is shown
    case 'catalogue':
      if (msg.request !== catalogueRequest) return;
      model.catalogue = msg.offset > 0 && model.catalogue ? { ...msg, rows: [...model.catalogue.rows, ...msg.rows] } : msg;
      break;
    case 'importResult':
      if (msg.request !== importRequest) return;
      model.importResult = msg;
      break;
    case 'nameTaken': model.nameTaken = msg.name; break;
    case 'limitedOptions': model.limitedOptions = msg; break;
    case 'limitedPools': model.limitedPools = msg; break;
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
    case 'chat': model.chat = [...model.chat, { from: msg.from ?? '', text: msg.text }]; break;
    // The server sends the whole list whenever it changes, so there is nothing to merge
    case 'presence': model.presence = msg.people; break;
    case 'deckDetails': model.deckDetails = msg.deck; break;
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
      // A pass on its way is shown on the pass button, not asked in a dialog
      if (msg.kind === 'autoPass') {
        startCountdown(msg);
      } else {
        model.requests.set(msg.id, msg);
      }
      break;
    case 'drawOffer': model.drawOffer = msg.open ? msg : null; break;
    case 'autoDecisions': model.autoDecisions = msg; break;
    case 'aside': notify({ t: 'notice', title: msg.title, message: '', error: false }, () => changeUi(u => { u.viewing = msg; }), ASIDE_MS); break;
    case 'gameOver':
      model.gameOver = true;
      model.drawOffer = null;
      dropCountdown();
      stopMusic();
      break;
    case 'sound': playSound(msg); return;
    case 'playable':
      model.playable = msg;
      break;
    case 'controls':
      model.controls = msg;
      onServerSettings(msg.settings);
      stopMemory.onControls(msg, !model.host);
      break;
    case 'log':
      appendLog(msg, logTints(players(model).map(p => ({ name: p.Name ?? '', local: isLocal(model, p) }))));
      return;
    case 'detail': model.cardDetails.set(msg.key, msg); break;
    case 'playerDetail': model.playerDetails.set(msg.key, msg); break;
    case 'stackMenu': model.stackMenu = msg; break;
    case 'notice': notify(msg); break;
    case 'devState': model.devState = msg; break;
    case 'devDump': saveText(msg.text, 'game-state.txt', 'text/plain'); return;
    case 'flash': flash(); return;
    default: break;
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

let noticeId = 0;
const NOTICE_MS = 6000;

/** Long enough to reach the notice's button before it goes. */
const ASIDE_MS = 15000;

// An error stays until the player dismisses it; anything else goes by itself
function notify(notice: Notice, view?: () => void, ms = NOTICE_MS, label?: string): void {
  const id = ++noticeId;
  model.notices = [...model.notices, { id, notice, view, label }];
  if (!notice.error) setTimeout(() => dismissNotice(id), ms);
}

function dismissNotice(id: number): void {
  model.notices = model.notices.filter(n => n.id !== id);
  schedule();
}

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
  byId('menu').hidden = page !== 'menu' && page !== 'name';
  byId('lobby').hidden = page !== 'lobby';
  byId('editor').hidden = page !== 'editor';
  byId('limited').hidden = page !== 'limited';
  byId('match').hidden = page !== 'match';
  renderScreens(model, actions, dismissNotice);
  if (!model.inMatch) {
    return;
  }
  renderSide(model);
  renderSky(model);
  renderMatch(model, actions, events);
  renderPrompt(model, actions);
  renderDetail(model);
  drawOverlay(model);
}
