import { cardImageSrc, hideOnError, setImage, setSymbolText } from './images';
import { game, type Model } from './model';
import { hoverable } from './detail';
import { stepName } from './phasebar';
import { byId, q } from './dom';
import { changeUi } from './ui';
import type { Actions } from './actions';
import type { PromptButton, Ref } from './protocol';

// The console in the bottom-left corner: turn controls on top, the prompt in the middle, its answers along the
// bottom. Its rim lights while the game waits on you.

// Icons from Lucide (ISC, see web/licenses/lucide-license.txt), drawn on the same 24-unit grid
const ICONS = {
  endTurn: '<path d="m6 17 5-5-5-5"/><path d="m13 17 5-5-5-5"/>',
  autoPass: '<path d="M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z"/><circle cx="12" cy="12" r="10"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"/>',
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
    q(root, '.ok').onclick = () => actions.ok();
    q(root, '.cancel').onclick = () => actions.cancel();
    q(root, '.end-turn').onclick = () => actions.endTurn();
    q(root, '.auto-pass').onclick = () => actions.toggleAutoPass();
    q(root, '.undo').onclick = () => actions.undo();
    q(root, '.cog').onclick = () => changeUi(u => { u.optionsOpen = true; });
    const card = q<HTMLImageElement>(root, '.prompt-card');
    hideOnError(card);
    hoverable(card);
    built = true;
  }
  root.classList.toggle('spectating', !!model.spectating);
  if (model.spectating) {
    q(root, '.step').textContent = stepName(game(model)?.Phase);
    q(root, '.message').textContent = 'Two AI players. You are spectating.';
    return;
  }
  const autoPass = !!model.controls?.autoPass;
  const autoPassButton = q(root, '.auto-pass');
  autoPassButton.classList.toggle('on', autoPass);
  autoPassButton.title = `Auto-pass is ${autoPass ? 'on' : 'off'}: pass priority automatically when you have nothing to play`;
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
  button.disabled = !spec?.enabled;
  button.hidden = !spec?.label;
}

export function flash(): void {
  const root = byId('prompt');
  root.classList.remove('flash');
  void root.offsetWidth;
  root.classList.add('flash');
}
