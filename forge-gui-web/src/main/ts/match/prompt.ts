import { cardImageSrc, hideOnError, setImage, setSymbolText } from '../images';
import { deref, derefAll, game, oldestRequest, stackPick, stateOf, type Model } from '../model';
import { playerAvatarUrl } from '../looks';
import { hoverable, inspectCard } from './detail';
import { longPress } from '../press';
import { stepName } from './phasebar';
import { byId, q, replay, reducedMotion } from '../dom';
import { changeUi, ui } from '../ui';
import type { Actions } from '../actions';
import { keyName } from '../keys';
import { boundKeys, setting } from '../settings';
import { isPortrait } from '../form';
import { notePick } from './overlay';
import type { CardView, PlayerView, PromptButton, Ref, StackItemView } from '../protocol';
import { t } from '../text';

// The console in the bottom-left corner, whose rim lights while the game waits on you

// Icons from Lucide (ISC, see web/licenses/lucide-license.txt), drawn on the same 24-unit grid
const ICONS = {
  endTurn: '<path d="m6 17 5-5-5-5"/><path d="m13 17 5-5-5-5"/>',
  autoPass: '<path d="M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z"/><circle cx="12" cy="12" r="10"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"/>',
  more: '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
};
const icon = (name: keyof typeof ICONS) => `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

let built = false;
/** The row of tools, which a phone moves out of the prompt and onto the player's bar. */
let tools: HTMLElement;
/** The prompt last drawn, and how many have arrived: a script waits for a newer one rather than for time to pass. */
let shown: Model['prompt'] = null;
let arrived = 0;

/** The glints are dashes along an outline of the box, not a turning gradient, so they keep one speed along long and short edges. */
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
  replay(button, 'pressed');
}

/** The same feedback for a key that stands in for a prompt button: "ok", "cancel", "end-turn" or "undo". */
export function pressPromptButton(name: string): void {
  showPressed(document.querySelector(`#match :is(#prompt, .tools) .${name}`));
}

/** How long the console takes to grow or shrink to a new prompt. */
const RESIZE_MS = 160;

/** Eases the console to a new prompt's height, anchored at its foot so any clipping while it does falls on the tools. */
export function renderPrompt(model: Model, actions: Actions): void {
  const root = byId('prompt');
  const before = built ? root.offsetHeight : 0;
  drawPrompt(model, actions);
  // On a phone, while a dialog or a zone asks the question and neither button can answer it, the buttons give up their room
  const asking = !!oldestRequest(model) || (model.zones.length > 0 && !ui.zonesMinimised);
  root.classList.toggle('blocked', isPortrait() && asking && !model.prompt?.selectablePlayers?.length
    && q<HTMLButtonElement>(root, '.ok').disabled && q<HTMLButtonElement>(root, '.cancel').disabled);
  const after = root.offsetHeight;
  if (!before || Math.abs(after - before) < 3 || reducedMotion()) return;
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
        <button class="auto-pass">${icon('autoPass')}</button>
        <button class="undo">${icon('undo')}</button>
        <span class="tools-gap" aria-hidden="true"></span>
        <button class="more">${icon('more')}</button>
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
    tools = q(root, '.tools');
    q(tools, '.auto-pass').title = t('lblWebPromptAutoPassTip');
    q(tools, '.more').title = t('lblWebPromptGameMenuTip');
    q(tools, '.auto-pass').onclick = () => actions.toggleAutoPass();
    q(tools, '.undo').onclick = () => actions.undo();
    q(tools, '.more').onclick = () => changeUi(u => { u.gameMenu = u.gameMenu ? null : 'menu'; });
    root.addEventListener('click', e => showPressed((e.target as Element).closest('button')));
    tools.addEventListener('click', e => { if (!root.contains(tools)) showPressed((e.target as Element).closest('button')); });
    buildGlints(root);
    // Long text is cut to two lines in the phone's dock, and a tap shows the rest
    q(root, '.message').onclick = () => root.classList.toggle('open');
    q(root, '.message').addEventListener('pointerdown', e => longPress(e, () => {
      const about = q<HTMLImageElement>(root, '.prompt-card');
      if (about.dataset.zoom) inspectCard(about);
    }));
    const card = q<HTMLImageElement>(root, '.prompt-card');
    hideOnError(card);
    hoverable(card);
    built = true;
  }
  // On a phone the tools ride on the player's own bar, which leaves the dock one row
  if (isPortrait() && tools.parentElement === root) byId('match').append(tools);
  else if (!isPortrait() && tools.parentElement !== root) q(root, '.step').before(tools);
  root.classList.toggle('swapped', !!setting('swapPrompt'));
  // The player can choose these keys in the options, so the labels follow whatever they chose
  const keys = boundKeys();
  q(tools, '.undo').title = t('lblWebPromptUndoTip', keyName(keys.undo));
  q(root, '.buttons .ok kbd').textContent = keyName(keys.ok);
  if (model.prompt !== shown) {
    shown = model.prompt;
    root.dataset.seq = String(++arrived);
    root.classList.remove('open');
    if (isPortrait()) replay(root, 'pulse');
  }
  root.classList.toggle('spectating', !!model.spectating);
  tools.hidden = !!model.spectating;
  if (model.spectating) {
    renderPlayerChoices(root, model, [], actions);
    q(root, '.step').textContent = stepName(game(model)?.Phase);
    q(root, '.message').textContent = t('lblWebPromptSpectating');
    return;
  }
  const autoPass = !!model.controls?.autoPass;
  const autoPassButton = q(tools, '.auto-pass');
  autoPassButton.classList.toggle('on', autoPass);
  autoPassButton.title = t(autoPass ? 'lblWebPromptAutoPassOnTip' : 'lblWebPromptAutoPassOffTip', keyName(keys.autoPass));
  // Lit while the turn is being passed through, as auto-pass is while it is on
  const endingTurn = !!model.controls?.untilEndOfTurn;
  const endTurnButton = q(tools, '.end-turn');
  endTurnButton.classList.toggle('on', endingTurn);
  // A second press stops the pass it started
  endTurnButton.onclick = () => (endingTurn ? actions.stopYield() : actions.endTurn());
  endTurnButton.title = endingTurn ? t('lblWebPromptEndingTurnTip')
    : t('lblWebPromptEndTurnTip', keyName(keys.endTurn));
  q(tools, '.more').classList.toggle('open', !!ui.gameMenu);
  const ok = q<HTMLButtonElement>(root, '.ok');
  const cancel = q<HTMLButtonElement>(root, '.cancel');
  ok.onclick = () => actions.ok();
  cancel.onclick = () => actions.cancel();
  // A spell picked on the stack has no dialog, so what it asks, and the way out of it, are shown here
  const pick = stackPick(model);
  if (pick) {
    setSymbolText(q(root, '.message'), pick.message ?? '');
    renderPromptCard(q<HTMLImageElement>(root, '.prompt-card'), model, null);
    renderPlayerChoices(root, model, [], actions);
    setButton(ok, undefined);
    setButton(cancel, pick.min === 0 ? { label: t('lblCancel'), enabled: true } : undefined);
    cancel.onclick = () => actions.answer(pick.id, []);
    root.classList.add('waiting');
    root.classList.remove('priority');
    return;
  }
  const p = model.prompt;
  if (!p) return;
  // Several prompts open with a short line naming the phase, which becomes the title rather than repeating under it
  const lines = (p.message ?? '').trim().split('\n');
  // A heading, not a sentence: short, with nothing that ends a sentence, and not a card named with its number
  const heading = lines.length > 1 && lines[0].length <= 24 && !/[.!?]$/.test(lines[0]) && !/\(\d+\)$/.test(lines[0]);
  q(root, '.step').textContent = heading ? lines[0] : p.priority ? t('lblPriority') : stepName(game(model)?.Phase);
  // The host's lines stay lines, as each is a part of its own: the source, what it does, what to do
  const body = (heading ? lines.slice(1) : lines).map(line => line.trim()).filter(line => line).join('\n');
  setSymbolText(q(root, '.message'), body || (p.priority && p.ok.enabled ? priorityLine(model, p.ok.label) : ''));
  renderPromptCard(q<HTMLImageElement>(root, '.prompt-card'), model, p.card);
  renderPlayerChoices(root, model, p.selectablePlayers ?? [], actions);
  setButton(q<HTMLButtonElement>(root, '.ok'), p.ok);
  setButton(q<HTMLButtonElement>(root, '.cancel'), p.cancel);
  q(root, '.ok').classList.toggle('focus', !!p.focusOk);
  const waiting = !!p.ok?.enabled || !!p.cancel?.enabled;
  root.classList.toggle('waiting', waiting);
  root.classList.toggle('priority', waiting && !!p.priority);
}

/** What having priority asks, which the host says only as the turn, phase and stack the page shows elsewhere. */
function priorityLine(model: Model, pass: string): string {
  const top = (derefAll(model, game(model)?.Stack) as StackItemView[])[0];
  const source = top && deref(model, top.SourceCard) as CardView | undefined;
  const name = source && stateOf(model, source).Name;
  return name ? t('lblWebPromptRespond', name, pass) : t('lblWebPromptAct', pass);
}

/** Offers as buttons the players a prompt lets you pick who have no seat on the board, which draws only one opponent. */
function renderPlayerChoices(root: HTMLElement, model: Model, choices: readonly Ref[], actions: Actions): void {
  const box = q(root, '.choose-players');
  // A seat in a hidden tab cannot be tapped, so its player is offered here as one with no seat is
  const offBoard = choices.filter(r => !document.querySelector(`.seat[data-player="${r.ref}"]:not([hidden])`));
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
  replay(root, 'flash');
}
