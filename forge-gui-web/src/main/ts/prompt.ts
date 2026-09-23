import { cardImageSrc, hideOnError, setImage, setSymbolText } from './images';
import { game, type Model } from './model';
import { hoverable } from './detail';
import { stepName } from './phasebar';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import { isSilent } from './volume';
import { countdown, finishCountdown } from './autopass';
import type { Actions } from './actions';
import type { PromptButton, Ref } from './protocol';

// The console in the bottom-left corner: turn controls on top, the prompt in the middle, its answers along the
// bottom. Its rim lights while the game waits on you.

// Icons from Lucide (ISC, see web/licenses/lucide-license.txt), drawn on the same 24-unit grid
const ICONS = {
  endTurn: '<path d="m6 17 5-5-5-5"/><path d="m13 17 5-5-5-5"/>',
  autoPass: '<path d="M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z"/><circle cx="12" cy="12" r="10"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"/>',
  volume: '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><path d="M16 9a5 5 0 0 1 0 6"/><path d="M19.364 18.364a9 9 0 0 0 0-12.728"/>',
  muted: '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><line x1="22" x2="16" y1="9" y2="15"/><line x1="16" x2="22" y1="9" y2="15"/>',
  cog: '<path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"/><circle cx="12" cy="12" r="3"/>',
};
const icon = (name: keyof typeof ICONS) => `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

let built = false;

export function renderPrompt(model: Model, actions: Actions): void {
  const root = byId('prompt');
  if (!built) {
    root.innerHTML = `
      <div class="tools">
        <p class="step"></p>
        <span class="spacer"></span>
        <button class="end-turn" title="Pass priority until the end of this turn (E)">${icon('endTurn')}</button>
        <button class="auto-pass" title="Pass priority automatically when you have nothing to play">${icon('autoPass')}</button>
        <button class="undo" title="Undo your last undoable action, such as tapping a land for mana (Z)">${icon('undo')}</button>
        <button class="volume" title="Volume">${icon('volume')}</button>
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
    q(root, '.end-turn').onclick = () => actions.endTurn();
    q(root, '.auto-pass').onclick = () => actions.toggleAutoPass();
    q(root, '.undo').onclick = () => actions.undo();
    q(root, '.volume').onclick = () => changeUi(u => { u.volumeOpen = !u.volumeOpen; });
    q(root, '.cog').onclick = () => changeUi(u => { u.optionsOpen = true; });
    const card = q<HTMLImageElement>(root, '.prompt-card');
    hideOnError(card);
    hoverable(card);
    built = true;
  }
  root.classList.toggle('spectating', !!model.spectating);
  const volume = q(root, '.volume');
  const silent = String(isSilent());
  if (volume.dataset.silent !== silent) {
    volume.dataset.silent = silent;
    volume.innerHTML = icon(silent === 'true' ? 'muted' : 'volume');
  }
  volume.classList.toggle('open', ui.volumeOpen);
  if (model.spectating) {
    q(root, '.step').textContent = stepName(game(model)?.Phase);
    q(root, '.message').textContent = 'Two AI players. You are spectating.';
    return;
  }
  const autoPass = !!model.controls?.autoPass;
  const autoPassButton = q(root, '.auto-pass');
  autoPassButton.classList.toggle('on', autoPass);
  autoPassButton.title = `Auto-pass is ${autoPass ? 'on' : 'off'}: pass priority automatically when you have nothing to play`;
  const ok = q<HTMLButtonElement>(root, '.ok');
  const cancel = q<HTMLButtonElement>(root, '.cancel');
  const passing = countdown();
  root.classList.toggle('auto-passing', !!passing);
  if (passing) {
    q(root, '.step').textContent = 'Passing';
    q(root, '.message').textContent = 'Nothing to play.';
    renderPromptCard(q<HTMLImageElement>(root, '.prompt-card'), model, null);
    setButton(ok, { label: 'Pass', enabled: true });
    setButton(cancel, { label: 'Stop', enabled: true });
    ok.onclick = () => finishCountdown(true);
    cancel.onclick = () => finishCountdown(false);
    fill(ok, passing.id, passing.ms);
    root.classList.add('waiting');
    return;
  }
  fill(ok, null, 0);
  ok.onclick = () => actions.ok();
  cancel.onclick = () => actions.cancel();
  const p = model.prompt;
  if (!p) return;
  // Several prompts open with a short line naming the phase; that line becomes the title rather than repeating
  // under it, and a plain priority prompt has nothing left to print
  const lines = (p.message ?? '').trim().split('\n');
  // A heading, not a sentence: short, and with nothing that ends a sentence
  const heading = lines.length > 1 && lines[0].length <= 24 && !/[.!?]$/.test(lines[0]);
  q(root, '.step').textContent = heading ? lines[0] : p.priority ? 'Priority' : stepName(game(model)?.Phase);
  setSymbolText(q(root, '.message'), (heading ? lines.slice(1) : lines).join(' ').trim());
  renderPromptCard(q<HTMLImageElement>(root, '.prompt-card'), model, p.card);
  setButton(q<HTMLButtonElement>(root, '.ok'), p.ok);
  setButton(q<HTMLButtonElement>(root, '.cancel'), p.cancel);
  q(root, '.ok').classList.toggle('focus', !!p.focusOk);
  root.classList.toggle('waiting', !!p.ok?.enabled || !!p.cancel?.enabled);
}

// The pass button fills over the countdown, from empty, once for each pass on its way
function fill(button: HTMLButtonElement, id: number | null, ms: number): void {
  const key = id === null ? '' : String(id);
  if (button.dataset.countdown === key) {
    return;
  }
  button.dataset.countdown = key;
  button.classList.remove('filling');
  if (id !== null) {
    button.style.setProperty('--fill-ms', `${ms}ms`);
    void button.offsetWidth;
    button.classList.add('filling');
  }
}

// The card the prompt is about (the spell being targeted, the trigger being paid for), as desktop shows it
function renderPromptCard(img: HTMLImageElement, model: Model, ref: Ref | null | undefined): void {
  const card = ref ? model.objects.get(ref.ref) : null;
  const src = cardImageSrc(model, card);
  img.dataset.key = String(card?.$key ?? '');
  img.dataset.zoom = src;
  setImage(img, src);
  img.hidden = !src;
}

function setButton(button: HTMLButtonElement, spec: PromptButton | undefined): void {
  setSymbolText(q(button, '.label'), spec?.label);
  // The console cuts a long label short, so the whole of it is kept where hovering finds it
  button.title = spec?.label ?? '';
  button.disabled = !spec?.enabled;
  button.hidden = !spec?.label;
}

export function flash(): void {
  const root = byId('prompt');
  root.classList.remove('flash');
  void root.offsetWidth;
  root.classList.add('flash');
}
