import { connect } from './net.js';
import { createModel, applyState } from './model.js';
import { renderStart } from './start.js';
import { renderMatch } from './board.js';
import { renderPrompt, flash, showNotice } from './prompt.js';
import { renderDialogs } from './dialogs.js';
import { appendLog, initLog } from './log.js';
import { initDetail, onDetail, onPlayerDetail } from './detail.js';
import { initStack, onStackMenu } from './stack.js';
import { initOverlay, drawOverlay } from './overlay.js';
import { initSettings, onServerSettings } from './settings.js';

const model = createModel();
let scheduled = false;
const send = connect(onMessage, online => { document.getElementById('banner').hidden = online; });
initDetail(send);
initStack(send);
initOverlay();
initLog();
initSettings(send, () => send({ t: 'concede' }));

function onMessage(msg) {
  switch (msg.t) {
    case 'hello':
      model.inMatch = msg.inMatch;
      model.spectating = !!msg.spectating;
      model.playerName = msg.playerName ?? '';
      if (msg.avatars) model.looks = { avatars: msg.avatars, sleeves: msg.sleeves, avatarCount: msg.avatarCount, sleeveCount: msg.sleeveCount };
      model.error = null;
      // The server replays open requests after every hello
      model.requests.clear();
      if (!msg.inMatch) {
        model.objects.clear();
        model.gameOver = false;
        send({ t: 'decks' });
      }
      break;
    case 'decks': model.decks = msg.decks; break;
    case 'error': model.error = msg.message; break;
    case 'state':
      applyState(model, msg);
      if (msg.full) model.gameOver = false;
      break;
    case 'prompt': model.prompt = msg; break;
    case 'zones': model.zones = msg.show; break;
    case 'request': model.requests.set(msg.id, msg); break;
    case 'gameOver': model.gameOver = true; break;
    case 'controls':
      model.controls = msg;
      onServerSettings(msg.settings);
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

function schedule() {
  if (scheduled) return;
  scheduled = true;
  requestAnimationFrame(() => { scheduled = false; render(); });
}

function render() {
  document.getElementById('start').hidden = model.inMatch;
  document.getElementById('match').hidden = !model.inMatch;
  if (!model.inMatch) {
    renderStart(model, send);
    return;
  }
  renderMatch(model, send);
  renderPrompt(model, send);
  renderDialogs(model, send, schedule);
  drawOverlay(model);
}
