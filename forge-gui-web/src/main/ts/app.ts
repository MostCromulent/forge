import { connect } from './net';
import { createModel, applyState } from './model';
import { renderMenu } from './menu';
import { renderLobby } from './lobby';
import { onDeckDetails, onDecks, deckFinderOpen, closeDeckFinder } from './deckfinder';
import { initSleeves, onCardNames, onPrintings } from './sleeves';
import { initHostChoice, onHostChoice } from './hostchoice';
import { renderMatch } from './board';
import { renderPrompt, flash, showNotice } from './prompt';
import { renderDialogs } from './dialogs';
import { appendLog, initLog } from './log';
import { initChat, addChat, clearChat } from './chat';
import { initSide, setChatAvailable, renderSide } from './side';
import { initDetail, onDetail, onPlayerDetail, resetDetail } from './detail';
import { initZones, resetZones } from './zones';
import { initPhaseBar } from './phasebar';
import { initBattlefield } from './battlefield';
import { initStack, onStackMenu } from './stack';
import { initOverlay, drawOverlay } from './overlay';
import { initSettings, onServerSettings, setPlaymats } from './settings';
import { refreshOptions } from './options';
import { applyAudioSettings, playSound, startMusic, stopMusic } from './audio';
import { initPace, pace, resetPace } from './pace';
import { byId } from './dom';
import type { ServerMessage } from './protocol';

const model = createModel();
let scheduled = false;
initPace(apply);
const send = connect(pace, online => { byId('banner').hidden = online; });
initHostChoice(send);
initDetail(send);
initZones(schedule);
initPhaseBar(schedule);
initBattlefield(schedule);
initStack(send, schedule);
initOverlay(schedule);
initLog();
initChat(send);
initSide();
initSettings(send, () => {
  applyAudioSettings();
  schedule();
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
      clearChat();
      setChatAvailable(!!msg.networked);
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
    case 'chat': addChat(msg.from ?? '', msg.text); break;
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
        resetDetail();
        resetZones();
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
    case 'detail': onDetail(msg); return;
    case 'playerDetail': onPlayerDetail(msg); return;
    case 'stackMenu': onStackMenu(msg); return;
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
  renderSide();
  renderMatch(model, send, events);
  renderPrompt(model, send);
  renderDialogs(model, send, schedule);
  drawOverlay(model);
}
