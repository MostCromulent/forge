import { imageUrl } from './cards.js';
import { stateOf } from './model.js';
import { hoverCard } from './detail.js';

let built = false;
let concedeTimer = 0;

export function renderPrompt(model, send) {
  const root = document.getElementById('prompt');
  if (!built) {
    root.innerHTML = `
      <div class="controls">
        <button class="end-turn" title="Pass priority until the end of this turn (E)">End turn</button>
        <button class="auto-pass" title="Pass priority automatically when you have nothing to play"></button>
        <button class="undo" title="Undo your last undoable action, such as tapping a land for mana (Z)">Undo</button>
        <button class="attack-all" title="Declare every creature that can attack">Attack all</button>
        <button class="concede">Concede</button>
      </div>
      <div class="prompt-body"><img class="prompt-card" alt="" hidden><p class="message"></p></div>
      <div class="buttons"><button class="cancel"></button><button class="ok primary"></button></div>`;
    root.querySelector('.ok').onclick = () => send({ t: 'ok' });
    root.querySelector('.cancel').onclick = () => send({ t: 'cancel' });
    root.querySelector('.end-turn').onclick = () => send({ t: 'endTurn' });
    root.querySelector('.auto-pass').onclick = () => send({ t: 'autoPass' });
    root.querySelector('.undo').onclick = () => send({ t: 'undo' });
    root.querySelector('.attack-all').onclick = () => send({ t: 'attackAll' });
    const concede = root.querySelector('.concede');
    // The first click arms it; a second click within a few seconds concedes
    concede.onclick = () => {
      if (concede.classList.contains('armed')) {
        disarm(concede);
        send({ t: 'concede' });
        return;
      }
      concede.classList.add('armed');
      concede.textContent = 'Confirm concede';
      concedeTimer = setTimeout(() => disarm(concede), 3000);
    };
    document.addEventListener('keydown', e => {
      if (e.target instanceof HTMLInputElement || document.querySelector('#dialog-layer .dialog') || e.ctrlKey || e.altKey || e.metaKey) return;
      const ok = root.querySelector('.ok');
      const cancel = root.querySelector('.cancel');
      if ((e.key === ' ' || e.key === 'Enter') && !ok.disabled) {
        e.preventDefault();
        ok.click();
      } else if (e.key === 'Escape' && !cancel.disabled) {
        cancel.click();
      } else if (e.key.toLowerCase() === 'e') {
        send({ t: 'endTurn' });
      } else if (e.key.toLowerCase() === 'z') {
        send({ t: 'undo' });
      }
    });
    built = true;
  }
  const autoPass = !!model.controls?.autoPass;
  const autoPassButton = root.querySelector('.auto-pass');
  autoPassButton.textContent = `Auto-pass: ${autoPass ? 'on' : 'off'}`;
  autoPassButton.classList.toggle('on', autoPass);
  const p = model.prompt;
  if (!p) return;
  root.querySelector('.message').textContent = p.message ?? '';
  renderPromptCard(root.querySelector('.prompt-card'), model, p.card);
  setButton(root.querySelector('.ok'), p.ok);
  setButton(root.querySelector('.cancel'), p.cancel);
  root.querySelector('.ok').classList.toggle('focus', !!p.focusOk);
}

// The card the prompt is about (the spell being targeted, the trigger being paid for), as desktop shows it
function renderPromptCard(img, model, ref) {
  if (!img.dataset.wired) {
    img.dataset.wired = '1';
    img.addEventListener('error', () => { img.hidden = true; });
    img.addEventListener('mouseenter', () => hoverCard(img));
    img.addEventListener('mouseleave', () => hoverCard(null));
  }
  const card = ref ? model.objects.get(ref.ref) : null;
  const state = card ? stateOf(model, card) : {};
  const src = card && model.visible.has(card.$key) && state.ImageKey ? imageUrl(state.ImageKey) : '';
  img.dataset.key = card?.$key ?? '';
  img.dataset.zoom = src;
  if ((img.getAttribute('src') ?? '') !== src) {
    img.hidden = !src;
    if (src) img.src = src;
    else img.removeAttribute('src');
  }
}

function disarm(concede) {
  clearTimeout(concedeTimer);
  concede.classList.remove('armed');
  concede.textContent = 'Concede';
}

function setButton(button, spec) {
  button.textContent = spec?.label ?? '';
  button.disabled = !spec?.enabled;
  button.hidden = !spec?.label;
}

export function flash() {
  const root = document.getElementById('prompt');
  root.classList.remove('flash');
  void root.offsetWidth;
  root.classList.add('flash');
}

export function showNotice(n) {
  const el = document.createElement('div');
  el.className = n.error ? 'notice error' : 'notice';
  const title = document.createElement('b');
  title.textContent = n.title ?? '';
  const body = document.createElement('div');
  body.textContent = n.message ?? '';
  el.append(title, body);
  el.onclick = () => el.remove();
  document.getElementById('notices').append(el);
  if (!n.error) setTimeout(() => el.remove(), 6000);
}
