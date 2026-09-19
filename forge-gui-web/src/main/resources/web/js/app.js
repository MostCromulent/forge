import { connect } from './net.js';
import { createModel, applyState } from './model.js';
import { renderStart } from './start.js';

const model = createModel();
let scheduled = false;
const send = connect(onMessage, online => { document.getElementById('banner').hidden = online; });

function onMessage(msg) {
  switch (msg.t) {
    case 'hello':
      model.inMatch = msg.inMatch;
      model.playerName = msg.playerName ?? '';
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
  if (!model.inMatch) renderStart(model, send);
}
