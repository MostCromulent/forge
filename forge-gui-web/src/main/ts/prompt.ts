import { cardImageSrc, hideOnError, setImage, setSymbolText } from './images';
import { deref, game, type Model } from './model';
import { playerAvatarUrl } from './looks';
import { hoverable } from './detail';
import { stepName } from './phasebar';
import { byId, q } from './dom';
import { changeUi, ui } from './ui';
import { isSilent } from './volume';
import { countdown, finishCountdown } from './autopass';
import type { Actions } from './actions';
import { keyName } from './keys';
import { boundKeys } from './settings';
import { notePick } from './overlay';
import type { PlayerView, PromptButton, Ref } from './protocol';

// The console in the bottom-left corner: turn controls on top, the prompt in the middle, its answers along the
// bottom. Its rim lights while the game waits on you.

// Icons from Lucide (ISC, see web/licenses/lucide-license.txt), drawn on the same 24-unit grid
const ICONS = {
  endTurn: '<path d="m6 17 5-5-5-5"/><path d="m13 17 5-5-5-5"/>',
  autoPass: '<path d="M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z"/><circle cx="12" cy="12" r="10"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"/>',
  volume: '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><path d="M16 9a5 5 0 0 1 0 6"/><path d="M19.364 18.364a9 9 0 0 0 0-12.728"/>',
  muted: '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><line x1="22" x2="16" y1="9" y2="15"/><line x1="16" x2="22" y1="9" y2="15"/>',
  more: '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
  cog: '<path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"/><circle cx="12" cy="12" r="3"/>',
};
const icon = (name: keyof typeof ICONS) => `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

let built = false;
/** The prompt last drawn, and how many have arrived: a script waits for a newer one rather than for time to pass. */
let shown: Model['prompt'] = null;
let arrived = 0;

/**
 * Two glints that travel round the rim while you hold priority. They are dashes along an outline of the box, not a
 * turning gradient, so they keep one speed along the long and short edges alike; the outline follows the box's size.
 */
const GLINT_LAYERS = ['halo', 'mid', 'core'];
function buildGlints(root: HTMLElement): void {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('glints');
  svg.setAttribute('aria-hidden', 'true');
  svg.innerHTML = GLINT_LAYERS.map(l => `<rect class="${l}" pathLength="1000"/>`).join('');
  root.prepend(svg);
  new ResizeObserver(() => {
    const w = root.offsetWidth;
    const h = root.offsetHeight;
    svg.setAttribute('width', String(w));
    svg.setAttribute('height', String(h));
    // Centred on the 1px border, whose outer corners round at 12px
    for (const r of svg.querySelectorAll('rect')) {
      r.setAttribute('x', '0.5');
      r.setAttribute('y', '0.5');
      r.setAttribute('width', String(Math.max(0, w - 1)));
      r.setAttribute('height', String(Math.max(0, h - 1)));
      r.setAttribute('rx', '11.5');
    }
  }).observe(root);
}

/** Dips an enabled prompt button dark for a moment, so a press by click or by key is seen to land. */
function showPressed(button: Element | null): void {
  if (!(button instanceof HTMLButtonElement) || button.disabled) return;
  button.classList.remove('pressed');
  void button.offsetWidth;
  button.classList.add('pressed');
}

/** The same feedback for a key that stands in for a prompt button: "ok", "cancel", "end-turn" or "undo". */
export function pressPromptButton(name: string): void {
  showPressed(document.querySelector(`#prompt .${name}`));
}

/** How long the console takes to grow or shrink to a new prompt. */
const RESIZE_MS = 160;

/**
 * The console changes height as prompts come and go (a card, a longer message, a row of players). It eases there
 * rather than jumping, anchored at its foot where the buttons are, so any clipping while it does falls on the tools.
 */
export function renderPrompt(model: Model, actions: Actions): void {
  const root = byId('prompt');
  const before = built ? root.offsetHeight : 0;
  drawPrompt(model, actions);
  const after = root.offsetHeight;
  if (!before || Math.abs(after - before) < 3 || document.documentElement.dataset.motion === 'reduced') return;
  for (const a of root.getAnimations()) if (a.id === 'prompt-resize') a.cancel();
  root.style.overflow = 'clip';
  root.style.alignContent = 'end';
  const run = root.animate([{ height: `${before}px` }, { height: `${after}px` }], { duration: RESIZE_MS, easing: 'ease-out' });
  run.id = 'prompt-resize';
  run.finished.then(() => { root.style.overflow = ''; root.style.alignContent = ''; }, () => { /* replaced by a newer resize */ });
}

function drawPrompt(model: Model, actions: Actions): void {
  const root = byId('prompt');
  if (!built) {
    root.innerHTML = `
      <div class="tools">
        <button class="end-turn">${icon('endTurn')}</button>
        <button class="auto-pass" title="Pass priority automatically when you have nothing to play">${icon('autoPass')}</button>
        <button class="undo">${icon('undo')}</button>
        <span class="tools-gap" aria-hidden="true"></span>
        <button class="volume" title="Volume">${icon('volume')}</button>
        <button class="more" title="Game: offer a draw, auto-pass interrupts, concede">${icon('more')}</button>
        <button class="cog" title="Options">${icon('cog')}</button>
      </div>
      <p class="step"></p>
      <div class="prompt-body">
        <img class="prompt-card" alt="" hidden>
        <p class="message"></p>
      </div>
      <div class="choose-players" hidden></div>
      <div class="buttons">
        <button class="ok primary"><span class="label"></span><kbd>Space</kbd></button>
        <button class="cancel"><span class="label"></span><kbd>Esc</kbd></button>
      </div>`;
    q(root, '.auto-pass').onclick = () => actions.toggleAutoPass();
    q(root, '.undo').onclick = () => actions.undo();
    q(root, '.volume').onclick = () => changeUi(u => { u.volumeOpen = !u.volumeOpen; });
    q(root, '.more').onclick = () => changeUi(u => { u.gameMenu = u.gameMenu ? null : 'menu'; });
    q(root, '.cog').onclick = () => changeUi(u => { u.optionsOpen = true; });
    root.addEventListener('click', e => showPressed((e.target as Element).closest('button')));
    buildGlints(root);
    const card = q<HTMLImageElement>(root, '.prompt-card');
    hideOnError(card);
    hoverable(card);
    built = true;
  }
  // The player can choose these keys in the options, so the labels follow whatever they chose
  const keys = boundKeys();
  q(root, '.undo').title = `Undo your last undoable action, such as tapping a land for mana (${keyName(keys.undo)})`;
  q(root, '.buttons .ok kbd').textContent = keyName(keys.ok);
  if (model.prompt !== shown) {
    shown = model.prompt;
    root.dataset.seq = String(++arrived);
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
    renderPlayerChoices(root, model, [], actions);
    q(root, '.step').textContent = stepName(game(model)?.Phase);
    q(root, '.message').textContent = 'Two AI players. You are spectating.';
    return;
  }
  const autoPass = !!model.controls?.autoPass;
  const autoPassButton = q(root, '.auto-pass');
  autoPassButton.classList.toggle('on', autoPass);
  autoPassButton.title = `Auto-pass is ${autoPass ? 'on' : 'off'}: pass priority automatically when you have nothing to play (${keyName(keys.autoPass)})`;
  // Lit while the turn is being passed through, as auto-pass is while it is on
  const endingTurn = !!model.controls?.untilEndOfTurn;
  const endTurnButton = q(root, '.end-turn');
  endTurnButton.classList.toggle('on', endingTurn);
  // A second press stops the pass it started
  endTurnButton.onclick = () => (endingTurn ? actions.stopYield() : actions.endTurn());
  endTurnButton.title = endingTurn ? 'Passing priority until the end of this turn. Click to stop'
    : `Pass priority until the end of this turn (${keyName(keys.endTurn)})`;
  q(root, '.more').classList.toggle('open', !!ui.gameMenu);
  const ok = q<HTMLButtonElement>(root, '.ok');
  const cancel = q<HTMLButtonElement>(root, '.cancel');
  const passing = countdown();
  root.classList.toggle('auto-passing', !!passing);
  if (passing) {
    q(root, '.step').textContent = 'Passing';
    q(root, '.message').textContent = 'Nothing to play.';
    renderPromptCard(q<HTMLImageElement>(root, '.prompt-card'), model, null);
    setButton(ok, { label: 'Pass', enabled: true });
    // Stopping means auto-pass is not wanted just now, so the button turns it off, under its own key
    setButton(cancel, { label: 'Auto-pass off', enabled: true });
    q(root, '.buttons .cancel kbd').textContent = keyName(keys.autoPass);
    ok.onclick = () => finishCountdown(true);
    cancel.onclick = () => {
      finishCountdown(false);
      if (model.controls?.autoPass) actions.toggleAutoPass();
    };
    fill(ok, passing.id, passing.ms);
    root.classList.add('waiting');
    root.classList.remove('priority');
    renderPlayerChoices(root, model, [], actions);
    return;
  }
  fill(ok, null, 0);
  q(root, '.buttons .cancel kbd').textContent = 'Esc';
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
  renderPlayerChoices(root, model, p.selectablePlayers ?? [], actions);
  setButton(q<HTMLButtonElement>(root, '.ok'), p.ok);
  setButton(q<HTMLButtonElement>(root, '.cancel'), p.cancel);
  q(root, '.ok').classList.toggle('focus', !!p.focusOk);
  const waiting = !!p.ok?.enabled || !!p.cancel?.enabled;
  root.classList.toggle('waiting', waiting);
  root.classList.toggle('priority', waiting && !!p.priority);
}

/**
 * The players a prompt lets you pick who have no seat on the board to click, offered as buttons in the prompt. The
 * board draws one opponent, so in a game of three or more the others are reached from here.
 */
function renderPlayerChoices(root: HTMLElement, model: Model, choices: readonly Ref[], actions: Actions): void {
  const box = q(root, '.choose-players');
  const offBoard = choices.filter(r => !document.querySelector(`.seat[data-player="${r.ref}"]`));
  const key = offBoard.map(r => r.ref).join(',');
  if (box.dataset.key === key) {
    return;
  }
  box.dataset.key = key;
  box.hidden = !offBoard.length;
  box.replaceChildren(...offBoard.map(r => {
    const player = deref(model, r) as PlayerView | undefined;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'choose-player';
    const face = document.createElement('img');
    face.alt = '';
    face.src = player ? playerAvatarUrl(player) : '';
    const name = document.createElement('span');
    name.textContent = player?.Name ?? '';
    button.append(face, name);
    button.onclick = () => {
      notePick(r.ref);
      actions.selectPlayer(r.ref);
    };
    return button;
  }));
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
