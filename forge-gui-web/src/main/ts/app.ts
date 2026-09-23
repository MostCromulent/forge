// The controller. It is the only place that talks to the server: messages from it update the model, what the player
// does arrives as actions and leaves as messages, and one render per frame draws the model and the table's
// arrangement. Nothing it draws with sends anything itself.

import { connect } from './net';
import { createModel, applyState } from './model';
import { createActions, type Actions } from './actions';
import { initUi, resetMatchUi } from './ui';
import { renderMenu } from './menu';
import { renderLobby } from './lobby';
import { onDeckDetails, onDecks, deckFinderOpen, closeDeckFinder } from './deckfinder';
import { initSleeves, onCardNames, onPrintings } from './sleeves';
import { initHostChoice, onHostChoice } from './hostchoice';
import { renderMatch } from './board';
import { renderPrompt, flash, showNotice } from './prompt';
import { renderDialogs } from './dialogs';
import { appendLog, initLog } from './log';
import { initSide, renderSide } from './side';
import { initDetail, nextFace, renderDetail } from './detail';
import { initStack } from './stack';
import { initOverlay, drawOverlay } from './overlay';
import { initSettings, onServerSettings, setPlaymats } from './settings';
import { refreshOptions } from './options';
import { applyAudioSettings, playSound, stopMusic } from './audio';
import { initPace, pace, resetPace } from './pace';
import { byId } from './dom';
import type { ServerMessage } from './protocol';

const model = createModel();
let scheduled = false;
initPace(apply);
const send = connect(pace, online => { byId('banner').hidden = online; });

const wire = createActions(send);
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
};

initUi(schedule);
initHostChoice(send);
initDetail(actions);
initStack(actions);
initOverlay(schedule);
initLog();
initSide(actions);
initSettings(actions.setSetting, () => {
  applyAudioSettings();
  schedule();
});

document.addEventListener('keydown', e => {
  if (e.key.toLowerCase() === 'f' && !(e.target instanceof HTMLInputElement) && !(e.target instanceof HTMLTextAreaElement)) {
    nextFace(model);
  }
});

function apply(msg: ServerMessage): void {
  switch (msg.t) {
    case 'hello':
      resetPace();
      model.host = msg.host !== false;
      model.canClaimHost = !!msg.canClaimHost;
      model.inMatch = msg.inMatch;
      model.inLobby = !!msg.inLobby;
      model.spectating = !!msg.spectating;
      model.playerName = msg.playerName ?? '';
      if (msg.avatars) model.looks = { avatars: msg.avatars, sleeves: msg.sleeves, avatarCount: msg.avatarCount, sleeveCount: msg.sleeveCount };
      initSleeves(msg.sleeveCount, msg.sleeveArt);
      setPlaymats(msg.playmats);
      model.error = null;
      // A new lobby has an address and a conversation of its own
      model.addresses = null;
      model.chat = [];
      model.networked = !!msg.networked;
      // The server replays open requests after every hello
      model.requests.clear();
      if (!msg.inMatch) {
        model.objects.clear();
        model.gameOver = false;
        send({ t: 'decks' });
      }
      break;
    case 'decks':
      model.decks = msg.decks;
      onDecks(msg.decks, msg.cardFormats);
      break;
    case 'lobby': model.lobby = msg.table ?? null; break;
    case 'addresses': model.addresses = msg.list; break;
    case 'chat': model.chat = [...model.chat, { from: msg.from ?? '', text: msg.text }]; break;
    case 'deckDetails': onDeckDetails(msg.deck); return;
    case 'cardSearch': onCardNames(msg.names); return;
    case 'printings': onPrintings(msg.printings); return;
    case 'hostChoice': onHostChoice(msg); return;
    case 'error': model.error = msg.message; break;
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
    case 'request': model.requests.set(msg.id, msg); break;
    case 'gameOver':
      model.gameOver = true;
      stopMusic();
      break;
    case 'sound': playSound(msg); return;
    case 'playable':
      model.playable = msg;
      break;
    case 'controls':
      model.controls = msg;
      onServerSettings(msg.settings);
      refreshOptions();
      break;
    case 'log': appendLog(msg); return;
    case 'detail': model.cardDetails.set(msg.key, msg); break;
    case 'playerDetail': model.playerDetails.set(msg.key, msg); break;
    case 'stackMenu': model.stackMenu = msg; break;
    case 'notice': showNotice(msg); return;
    case 'flash': flash(); return;
    default: break;
  }
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
  byId('menu').hidden = model.inMatch || model.inLobby;
  byId('lobby').hidden = model.inMatch || !model.inLobby;
  byId('match').hidden = !model.inMatch;
  if (!model.inMatch) {
    if (deckFinderOpen() && !model.inLobby) closeDeckFinder();
    if (model.inLobby) renderLobby(model, send);
    else renderMenu(model, send);
    return;
  }
  renderSide(model);
  renderMatch(model, actions, events);
  renderPrompt(model, actions);
  renderDialogs(model, actions);
  renderDetail(model);
  drawOverlay(model);
}
