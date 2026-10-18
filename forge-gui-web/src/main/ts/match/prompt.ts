// The prompt shows the game's question and its buttons. It is at the bottom of the side column, or in the dock on a phone. Its border glows while the game waits on you.

import { cardImageSrc, hideOnError, setImage, setSymbolText } from '../images';
import { askingCard, cardPrompt, deref, game, me, oldestRequest, stackPick, stackTopSource, stateOf, type Model } from '../model';
import { playerAvatarUrl } from '../looks';
import { hoverable, inspectCard } from '../preview';
import { longPress } from '../press';
import { stepName } from './phasebar';
import { boardBehind } from './pace';
import { byId, q, replay, reducedMotion } from '../dom';
import { changeUi, ui } from '../ui';
import type { Actions } from '../actions';
import { keyName, shortcutName } from '../keys';
import { boundKeys, shortcutKeys } from '../settings';
import { isPortrait } from '../form';
import { notePick } from './overlay';
import { declaringBlocks } from './blockdrag';
import { sourceName, splitQuestion } from './asking';
import type { CardView, PlayerView, PromptButton, Ref } from '../protocol';
import { t } from '../text';
import { ICONS, iconSvg } from '../icons';

let built = false;
/** The row of tools. On a phone it is moved out of the prompt and onto the player's bar. */
let tools: HTMLElement;
/** The prompt last drawn, and how many prompts have arrived. A test script waits for a higher count instead of a fixed time. */
let shown: Model['prompt'] = null;
let arrived = 0;

/** The glints are dashes along an outline of the box, not a rotating gradient. So they move at one speed on long and short edges. */
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
    // Centred on the 1px border, which has a 12px outer corner radius
    for (const r of svg.querySelectorAll('rect')) {
      r.setAttribute('x', '0.5');
      r.setAttribute('y', '0.5');
      r.setAttribute('width', String(Math.max(0, w - 1)));
      r.setAttribute('height', String(Math.max(0, h - 1)));
      r.setAttribute('rx', '11.5');
    }
  }).observe(root);
}

/** Darkens an enabled prompt button for a moment, so the player sees that a click or key press was taken. */
function showPressed(button: Element | null): void {
  if (!(button instanceof HTMLButtonElement) || button.disabled) return;
  // An answer given while newer boards are still held back is not sent, so the press is not shown either
  if (boardBehind() && button.closest('.buttons')) return;
  replay(button, 'pressed');
}

/** Shows the same feedback for a key bound to a prompt button: "ok", "cancel", "end-turn" or "undo". */
export function pressPromptButton(name: string): void {
  showPressed(document.querySelector(`#match :is(#prompt, .tools) .${name}`));
}

/** How long the prompt takes to grow or shrink to a new prompt's height. */
const RESIZE_MS = 160;

/** Draws the prompt and animates it to its new height. Content is aligned to the bottom, so any clipping during the animation hides the tools first. */
export function renderPrompt(model: Model, actions: Actions): void {
  const root = byId('prompt');
  const before = built ? root.offsetHeight : 0;
  drawPrompt(model, actions);
  // On a phone, the buttons are hidden while a dialog or zone asks the question and neither button can answer it
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
        <button class="end-turn">${iconSvg(ICONS.yields)}</button>
        <button class="auto-pass">${iconSvg(ICONS.autoPass)}</button>
        <button class="undo">${iconSvg(ICONS.undo)}</button>
        <button class="macro" hidden></button>
        <span class="tools-gap" aria-hidden="true"></span>
        <button class="more">${iconSvg(ICONS.ellipsis)}</button>
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
    // Long text is cut to two lines in the phone's dock. A tap shows the rest.
    q(root, '.message').onclick = () => root.classList.toggle('open');
    q(root, '.message').addEventListener('pointerdown', e => longPress(e, () => {
      const about = q<HTMLImageElement>(root, '.prompt-card');
      if (about.dataset.zoom) inspectCard(about);
    }));
    const card = q<HTMLImageElement>(root, '.prompt-card');
    hideOnError(card);
    hoverable(card);
    const buttons = q(root, '.buttons');
    new ResizeObserver(() => buttons.classList.toggle('stacked', q(buttons, '.cancel').offsetTop > q(buttons, '.ok').offsetTop)).observe(buttons);
    built = true;
  }
  // On a phone the tools are moved to the player's own bar, so the dock keeps one row
  if (isPortrait() && tools.parentElement === root) byId('match').append(tools);
  else if (!isPortrait() && tools.parentElement !== root) q(root, '.step').before(tools);
  // The player can change these keys in the options, so the labels show the current bindings
  const keys = boundKeys();
  const shortcuts = shortcutKeys();
  const shortcut = (pref: string) => shortcutName(shortcuts[pref] ?? '') || t('lblNone');
  q(tools, '.undo').title = t('lblWebPromptUndoTip', shortcut('SHORTCUT_UNDO'));
  const okKey = q(root, '.buttons .ok kbd');
  okKey.textContent = keyName(keys.ok);
  okKey.hidden = !keys.ok;
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
    setSymbolText(q(root, '.message'), t('lblWebPromptSpectating'));
    return;
  }
  const autoPass = !!model.controls?.autoPass;
  const autoPassButton = q(tools, '.auto-pass');
  autoPassButton.classList.toggle('on', autoPass);
  autoPassButton.title = t(autoPass ? 'lblWebPromptAutoPassOnTip' : 'lblWebPromptAutoPassOffTip', shortcut('SHORTCUT_YIELD_AUTO_PASS'));
  // Highlighted while the rest of the turn is being passed, as the auto-pass button is while it is on
  const endingTurn = !!model.controls?.untilEndOfTurn;
  const endTurnButton = q(tools, '.end-turn');
  endTurnButton.classList.toggle('on', endingTurn);
  // A second press stops the pass it started
  endTurnButton.onclick = () => (endingTurn ? actions.stopYield() : actions.endTurn());
  endTurnButton.title = endingTurn ? t('lblWebPromptEndingTurnTip')
    : t('lblWebPromptEndTurnTip', shortcut('SHORTCUT_ENDTURN'));
  q(tools, '.more').classList.toggle('open', !!ui.gameMenu);
  // While a macro is recording or recorded, the prompt has a button for it. A press stops the recording or plays the macro again.
  const macro = q<HTMLButtonElement>(tools, '.macro');
  macro.hidden = ui.macro === 'none';
  macro.classList.toggle('recording', ui.macro === 'recording');
  macro.textContent = t(ui.macro === 'recording' ? 'lblWebPromptMacroStop' : 'lblWebPromptMacroRepeat');
  macro.onclick = () => {
    if (ui.macro === 'recording') {
      actions.macroRecord();
      changeUi(u => { u.macro = 'recorded'; });
    } else {
      actions.macroRepeat();
    }
  };
  const ok = q<HTMLButtonElement>(root, '.ok');
  const cancel = q<HTMLButtonElement>(root, '.cancel');
  ok.onclick = () => actions.ok();
  cancel.onclick = () => actions.cancel();
  const req = oldestRequest(model);
  const onBoard = !!req && cardPrompt(req);
  root.classList.toggle('answering', onBoard);
  // A pick of a spell on the stack has no dialog. So its question and its cancel button are shown here.
  const pick = stackPick(model);
  if (pick) {
    setSymbolText(q(root, '.message'), pick.message ?? '');
    renderPromptCard(q<HTMLImageElement>(root, '.prompt-card'), model, null);
    renderPlayerChoices(root, model, [], actions);
    setButton(ok, undefined);
    setButton(cancel, pick.min === 0 ? { label: t('lblCancel'), enabled: true } : undefined);
    cancel.onclick = () => actions.answer(pick.id, []);
    root.classList.add('waiting');
    root.classList.remove('priority', 'attack', 'strike', 'blocks', 'no-blocks');
    return;
  }
  // A question about cards is answered on the board. The prompt names its source, or says the question is waiting if it is folded away.
  if (req && onBoard) {
    const folded = ui.requestFolded === req.id;
    const ref = askingCard(model, req);
    const asked = splitQuestion(('title' in req ? req.title : 'message' in req ? req.message : '') ?? '');
    const source = sourceName(model, asked, ref);
    q(root, '.step').textContent = folded ? t('lblWebPromptQuestionWaits') : source ? t('lblWebPromptResolving') : stepName(game(model)?.Phase);
    // With no source to name, the prompt shows the question itself
    setSymbolText(q(root, '.message'), source || asked.ask);
    renderPromptCard(q<HTMLImageElement>(root, '.prompt-card'), model, ref);
    renderPlayerChoices(root, model, [], actions);
    setButton(ok, folded ? { label: t('lblWebZoneShowCards'), enabled: true } : undefined);
    ok.onclick = () => changeUi(u => { u.requestFolded = null; });
    setButton(cancel, undefined);
    root.classList.remove('waiting', 'priority', 'attack', 'strike', 'blocks', 'no-blocks');
    return;
  }
  const p = model.prompt;
  if (!p) return;
  // Several prompts start with a short line naming the phase. It becomes the title instead of being repeated under it.
  const lines = (p.message ?? '').trim().split('\n');
  // A heading is short, has no sentence-ending punctuation, and is not a card name followed by its number
  const heading = lines.length > 1 && lines[0].length <= 24 && !/[.!?]$/.test(lines[0]) && !/\(\d+\)$/.test(lines[0]);
  const step = heading ? lines[0] : p.priority ? t('lblPriority') : stepName(game(model)?.Phase);
  // A pick of several cards shows how many are picked, because OK is not enabled until enough are
  q(root, '.step').textContent = !p.priority && !p.paying && p.selectableMin > 1 ? `${step} · ${t('lblWebPromptPickedOf', p.highlighted.length, p.selectableMin)}` : step;
  // The host's line breaks are kept, because each line is a separate part: the source, what it does, what to do
  const body = (heading ? lines.slice(1) : lines).map(line => line.trim()).filter(line => line).join('\n');
  setSymbolText(q(root, '.message'), body || (p.priority && p.ok.enabled ? priorityLine(model, p.ok.label) : ''));
  renderPromptCard(q<HTMLImageElement>(root, '.prompt-card'), model, p.card);
  // Who goes first is answered on the board, under the coin (starter.ts). The prompt keeps only its message
  const elsewhere = !!p.starterChoice;
  renderPlayerChoices(root, model, elsewhere ? [] : p.selectablePlayers ?? [], actions);
  setButton(q<HTMLButtonElement>(root, '.ok'), elsewhere ? undefined : p.ok);
  setButton(q<HTMLButtonElement>(root, '.cancel'), elsewhere ? undefined : p.cancel);
  // When declaring attackers, each button says what it will do. The host's OK label is the same whether nothing attacks or everything does.
  const strike = p.ok.enabled && p.cancel.label === t('lblAlphaStrike');
  const attackers = p.ok.enabled && p.cancel.label === t('lblCallBack')
    ? countAttacking(model) : 0;
  root.classList.toggle('attack', strike || attackers > 0);
  root.classList.toggle('strike', strike);
  if (strike) setButton(ok, { label: t('lblWebPromptNoAttack'), enabled: true });
  const cancelKey = q(root, '.buttons .cancel kbd');
  const strikeKey = shortcutName(shortcuts.SHORTCUT_ALPHASTRIKE ?? '');
  // When the button ends the turn it shows the end turn shortcut, which does the same. Escape still presses it
  const endKey = p.endsTurn ? shortcutName(shortcuts.SHORTCUT_ENDTURN ?? '') : '';
  cancelKey.textContent = strike ? strikeKey : endKey || 'Esc';
  cancelKey.hidden = strike && !strikeKey;
  if (attackers) {
    setButton(ok, { label: t('lblWebPromptAttackWith', attackers), enabled: true });
    setButton(cancel, { label: t('lblWebPromptClearAttackers'), enabled: p.cancel.enabled });
  }
  // Declaring blockers has only the OK button. Its label says what it will do, as for attackers.
  const blocks = declaringBlocks(model) && !p.priority && p.ok.enabled && !p.cancel.enabled && p.ok.label === t('lblOK');
  const mine = me(model)?.$key;
  const blockers = blocks ? new Set((game(model)?.CombatView ?? []).flatMap(b => [...b.blockers ?? [], ...b.plannedBlockers ?? []])
    .filter(r => r && (model.objects.get(r.ref) as CardView | undefined)?.Controller?.ref === mine).map(r => r?.ref)).size : 0;
  if (blocks) setButton(ok, { label: blockers ? t('lblWebPromptBlockWith', blockers) : t('lblWebPromptNoBlocks'), enabled: true });
  root.classList.toggle('blocks', blocks);
  root.classList.toggle('no-blocks', blocks && !blockers);
  q(root, '.ok').classList.toggle('focus', !!p.focusOk);
  const waiting = !!p.ok?.enabled || !!p.cancel?.enabled;
  root.classList.toggle('waiting', waiting);
  root.classList.toggle('priority', waiting && !!p.priority);
}

/** Counts the cards that are attacking. */
function countAttacking(model: Model): number {
  let n = 0;
  for (const o of model.objects.values()) {
    if ((o as CardView).Attacking) n++;
  }
  return n;
}

function priorityLine(model: Model, pass: string): string {
  const source = deref(model, stackTopSource(model)) as CardView | undefined;
  const name = source && stateOf(model, source).Name;
  return name ? t('lblWebPromptRespond', name, pass) : t('lblWebPromptAct', pass);
}

/** Shows buttons for pickable players whose seat is not visible on the board. */
function renderPlayerChoices(root: HTMLElement, model: Model, choices: readonly Ref[], actions: Actions): void {
  const box = q(root, '.choose-players');
  // A seat in a hidden tab cannot be tapped, so its player gets a button here too
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
  button.disabled = !spec?.enabled;
  button.hidden = !spec?.label;
}

export function flash(): void {
  const root = byId('prompt');
  replay(root, 'flash');
}
