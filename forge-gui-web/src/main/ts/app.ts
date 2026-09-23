// The controller. It is the only place that talks to the server: messages from it update the model, what the player
// does arrives as actions and leaves as messages, and one render per frame draws the model and the table's
// arrangement. Nothing it draws with sends anything itself.

import { connect } from './net';
import { createModel, applyState } from './model';
import { createActions, type Actions } from './actions';
import { changeUi, initUi, resetMatchUi, ui } from './ui';
import { keyCommand, type KeyCommand } from './keys';
import { hostedBefore, rememberName, rememberedName } from './menu';
import { renderScreens, screenOf } from './screens';
import { renderMatch } from './board';
import { renderPrompt, flash } from './prompt';
import { appendLog, initLog } from './log';
import { initSide, renderSide } from './side';
import { initDetail, nextFace, renderDetail } from './detail';
import { initStack } from './stack';
import { initOverlay, drawOverlay } from './overlay';
import { initSettings, onServerSettings, restoreGuestSettings, setGuest, setPlaymats } from './settings';
import { applyAudioSettings, playSound, startMusic, stopMusic } from './audio';
import { countdown, dropCountdown, finishCountdown, initAutoPass, startCountdown } from './autopass';
import { createStopMemory, localStopStore } from './stopmemory';
import { byId } from './dom';
import type { Notice, ServerMessage } from './protocol';

const model = createModel();
let scheduled = false;
// Asked once each, so a slow answer is not asked for again on every message that arrives meanwhile
let claimed = false;
let askedAddresses = false;
// A guest's settings live in its session on the server, so each connection is given back what the browser remembers
let restored = false;
// The game is paced where it runs: it holds for the player on a pass they have something new to see before
// (autopass.ts), so every message is shown as it arrives
const send = connect(apply, online => {
  byId('banner').hidden = online;
  if (!online) restored = false;
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
  claimHost: () => {
    claimed = true;
    wire.claimHost();
  },
  // The engine thread waits on the answer, so the question goes as soon as it is answered
  answerHostChoice: (id, value) => {
    model.hostChoice = null;
    wire.answerHostChoice(id, value);
    schedule();
  },
};

const stopMemory = createStopMemory(localStopStore('forge.guestStops'));

initUi(schedule);
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
  }, model, ui, !!countdown());
  if (!command) {
    return;
  }
  e.preventDefault();
  runKey(command);
});

function runKey(command: KeyCommand): void {
  switch (command) {
    case 'closeOptions': changeUi(u => { u.optionsOpen = false; }); break;
    case 'closeVolume': changeUi(u => { u.volumeOpen = false; }); break;
    case 'closeStackMenu': changeUi(u => { u.stackMenuAt = null; }); break;
    case 'closeStops': changeUi(u => { u.stopsOpen = false; }); break;
    case 'closePicker': changeUi(u => { u.picker = null; }); break;
    case 'declineHostChoice': if (model.hostChoice) actions.answerHostChoice(model.hostChoice.id, []); break;
    case 'ok': actions.ok(); break;
    case 'cancel': actions.cancel(); break;
    case 'passNow': finishCountdown(true); break;
    case 'stopAutoPass': finishCountdown(false); break;
    case 'endTurn': actions.endTurn(); break;
    case 'undo': actions.undo(); break;
    case 'nextFace': nextFace(model); break;
    case 'startMatch': actions.startMatch(ui.spectate); break;
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
      // A picker belongs to the table it was opened over
      if (!model.inLobby) ui.picker = null;
      model.spectating = msg.spectating;
      model.playerName = msg.playerName ?? '';
      model.nameSent = false;
      if (model.playerName) {
        rememberName(model.playerName);
      } else {
        offerRememberedName();
      }
      model.looks = { avatarCount: msg.avatarCount, sleeveCount: msg.sleeveCount };
      model.savedSleeveArt = msg.sleeveArt ?? [];
      setPlaymats(msg.playmats);
      model.error = null;
      // A new lobby has an address and a conversation of its own
      model.addresses = null;
      askedAddresses = false;
      model.chat = [];
      model.networked = msg.networked;
      // The server replays open requests after every hello
      model.requests.clear();
      dropCountdown();
      if (!msg.inMatch) {
        model.objects.clear();
        model.gameOver = false;
        send({ t: 'decks' });
      }
      break;
    case 'decks':
      model.decks = msg.decks;
      model.cardFormats = msg.cardFormats ?? [];
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
        model.zones = [];
        model.playable = null;
        model.cardDetails.clear();
        model.playerDetails.clear();
        model.stackMenu = null;
        resetMatchUi();
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
    case 'gameOver':
      model.gameOver = true;
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
    case 'log': appendLog(msg); return;
    case 'detail': model.cardDetails.set(msg.key, msg); break;
    case 'playerDetail': model.playerDetails.set(msg.key, msg); break;
    case 'stackMenu': model.stackMenu = msg; break;
    case 'notice': notify(msg); break;
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
    send({ t: 'setName', name });
  }
}

let noticeId = 0;
const NOTICE_MS = 6000;

// An error stays until the player dismisses it; anything else goes by itself
function notify(notice: Notice): void {
  const id = ++noticeId;
  model.notices = [...model.notices, { id, notice }];
  if (!notice.error) setTimeout(() => dismissNotice(id), NOTICE_MS);
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
  byId('match').hidden = page !== 'match';
  reclaimHostSeat();
  renderScreens(model, actions, dismissNotice);
  if (!model.inMatch) {
    return;
  }
  renderSide(model);
  renderMatch(model, actions, events);
  renderPrompt(model, actions);
  renderDetail(model);
  drawOverlay(model);
}

// A browser that has hosted this server before takes the free host seat back without being asked
function reclaimHostSeat(): void {
  if (!model.host && model.canClaimHost && !claimed && hostedBefore()) {
    actions.claimHost();
  }
}
