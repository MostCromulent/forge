import { cardImageSrc, hideOnError, setImage, setSymbolText } from './images.js';
import { game } from './model.js';
import { hoverable } from './detail.js';
import { stepName, stopsOpen } from './phasebar.js';
import { openOptions, closeOptions } from './options.js';

// The console in the bottom-left corner: turn controls on top, the prompt in the middle, its answers along the
// bottom. Its rim lights while the game waits on you.

// Icons from Lucide (ISC, see web/licenses/lucide-license.txt), drawn on the same 24-unit grid
const ICONS = {
  endTurn: '<path d="m6 17 5-5-5-5"/><path d="m13 17 5-5-5-5"/>',
  autoPass: '<path d="M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z"/><circle cx="12" cy="12" r="10"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"/>',
  cog: '<path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"/><circle cx="12" cy="12" r="3"/>',
};
const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

let built = false;

export function renderPrompt(model, send) {
  const root = document.getElementById('prompt');
  if (!built) {
    root.innerHTML = `
      <div class="tools">
        <p class="step"></p>
        <span class="spacer"></span>
        <button class="end-turn" title="Pass priority until the end of this turn (E)">${icon('endTurn')}</button>
        <button class="auto-pass" title="Pass priority automatically when you have nothing to play">${icon('autoPass')}</button>
        <button class="undo" title="Undo your last undoable action, such as tapping a land for mana (Z)">${icon('undo')}</button>
        <button class="cog" title="Options">${icon('cog')}</button>
      </div>
      <div class="prompt-body">
        <img class="prompt-card" alt="" hidden>
        <p class="message"></p>
      </div>
      <div class="buttons">
        <button class="cancel"><span class="label"></span><kbd>Esc</kbd></button>
        <button class="ok primary"><span class="label"></span><kbd>Space</kbd></button>
      </div>`;
    root.querySelector('.ok').onclick = () => send({ t: 'ok' });
    root.querySelector('.cancel').onclick = () => send({ t: 'cancel' });
    root.querySelector('.end-turn').onclick = () => send({ t: 'endTurn' });
    root.querySelector('.auto-pass').onclick = () => send({ t: 'autoPass' });
    root.querySelector('.undo').onclick = () => send({ t: 'undo' });
    root.querySelector('.cog').onclick = () => openOptions(() => send({ t: 'concede' }));
    document.addEventListener('keydown', e => {
      if (e.target instanceof HTMLInputElement || document.querySelector('#dialog-layer .dialog') || e.ctrlKey || e.altKey || e.metaKey) return;
      const ok = root.querySelector('.ok');
      const cancel = root.querySelector('.cancel');
      if (document.getElementById('options') || stopsOpen()) {
        // Escape belongs to whatever is open over the board; the prompt keeps its answer
        if (e.key === 'Escape' && document.getElementById('options')) {
          closeOptions();
        }
        return;
      } else if ((e.key === ' ' || e.key === 'Enter') && !ok.disabled) {
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
  root.classList.toggle('spectating', !!model.spectating);
  if (model.spectating) {
    root.querySelector('.step').textContent = stepName(game(model)?.Phase);
    root.querySelector('.message').textContent = 'Two AI players. You are spectating.';
    return;
  }
  const autoPass = !!model.controls?.autoPass;
  const autoPassButton = root.querySelector('.auto-pass');
  autoPassButton.classList.toggle('on', autoPass);
  autoPassButton.title = `Auto-pass is ${autoPass ? 'on' : 'off'}: pass priority automatically when you have nothing to play`;
  const p = model.prompt;
  if (!p) return;
  // Several prompts open with a short line naming the phase; that line becomes the title rather than repeating
  // under it, and a plain priority prompt has nothing left to print
  const lines = (p.message ?? '').trim().split('\n');
  // A heading, not a sentence: short, and with nothing that ends a sentence
  const heading = lines.length > 1 && lines[0].length <= 24 && !/[.!?]$/.test(lines[0]);
  root.querySelector('.step').textContent = heading ? lines[0] : p.priority ? 'Priority' : stepName(game(model)?.Phase);
  setSymbolText(root.querySelector('.message'), (heading ? lines.slice(1) : lines).join(' ').trim());
  renderPromptCard(root.querySelector('.prompt-card'), model, p.card);
  setButton(root.querySelector('.ok'), p.ok);
  setButton(root.querySelector('.cancel'), p.cancel);
  root.querySelector('.ok').classList.toggle('focus', !!p.focusOk);
  root.classList.toggle('waiting', !!p.ok?.enabled || !!p.cancel?.enabled);
}

// The card the prompt is about (the spell being targeted, the trigger being paid for), as desktop shows it
function renderPromptCard(img, model, ref) {
  if (!img.dataset.wired) {
    img.dataset.wired = '1';
    hideOnError(img);
    hoverable(img);
  }
  const card = ref ? model.objects.get(ref.ref) : null;
  const src = cardImageSrc(model, card);
  img.dataset.key = card?.$key ?? '';
  img.dataset.zoom = src;
  setImage(img, src);
  img.hidden = !src;
}

function setButton(button, spec) {
  setSymbolText(button.querySelector('.label'), spec?.label);
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
