// The zones panel, for a graveyard, exile or other zone opened over the board.
// It folds to a bar instead of closing. So the player can look at the board during a prompt and come back to the same place.

import { reconcile } from './reconcile';
import { createCard, markPicked, updateCard, type CardClick } from './cards';
import { fitCards } from './fit';
import { stateOf, zone, type Model } from '../model';
import { byId, q, make, reducedMotion } from '../dom';
import { setSymbolText } from '../images';
import { sourceName, splitQuestion } from './asking';
import { changeUi, ui, type ZoneSort } from '../ui';
import { normalize, rankByName } from '../search';
import { isPortrait } from '../form';
import { sheet } from '../sheet';
import type { Actions } from '../actions';
import { keyName } from '../keys';
import { boundKeys } from '../settings';
import type { CardView, PromptButton, ZoneType } from '../protocol';
import { t, type TextKey } from '../text';
import { manaValue } from './rules';

// One open zone. It is either a zone the game asks to show or a pile the player opened by clicking.
interface Panel {
  player: number;
  zone: ZoneType;
  /** True when the game is showing it. Then the game closes it, not a Close button. */
  forced: boolean;
}

const SORTS: [ZoneSort, TextKey][] = [['order', 'lblWebZoneSortOrder'], ['name', 'lblWebZoneSortName'], ['type', 'lblWebZoneSortType'],
  ['mana', 'lblWebZoneSortManaValue']];

/** Each zone's name as the page shows it. */
const ZONE_NAMES: Record<ZoneType, TextKey> = {
  Hand: 'lblWebZoneHand', Library: 'lblLibrary', Graveyard: 'lblGraveyard', Battlefield: 'lblWebZoneBattlefield',
  Exile: 'lblExile', Flashback: 'lblWebZoneFlashback', Command: 'lblWebZoneCommand', Stack: 'lblStack',
  Sideboard: 'lblSideboard', Ante: 'lblWebZoneAnte', Merged: 'lblWebZoneMerged', SchemeDeck: 'lblSchemeDeck',
  PlanarDeck: 'lblPlanarDeck', AttractionDeck: 'lblWebZoneAttractionDeck', Junkyard: 'lblWebZoneJunkyard',
  ContraptionDeck: 'lblWebZoneContraptionDeck', Subgame: 'lblWebZoneSubgame', ExtraHand: 'lblWebZoneExtraHand',
  StickerSheets: 'lblStickerSheets', None: 'lblNone',
};

export function zoneTitle(zoneName: ZoneType): string {
  const key = ZONE_NAMES[zoneName];
  return key ? t(key) : zoneName;
}

export function togglePile(playerKey: number, zoneName: ZoneType): void {
  const k = `${playerKey}/${zoneName}`;
  changeUi(u => {
    if (!u.openZones.delete(k)) u.openZones.add(k);
  });
}

export function renderZones(model: Model, actions: Actions, select: CardClick): void {
  const panels = new Map<string, Panel>();
  for (const z of model.zones) panels.set(`${z.player.ref}/${z.zone}`, { player: z.player.ref, zone: z.zone, forced: true });
  for (const k of ui.openZones) {
    if (panels.has(k)) continue;
    const [player, zoneName] = k.split('/');
    panels.set(k, { player: Number(player), zone: zoneName as ZoneType, forced: false });
  }
  const root = byId('zones');
  const open = panels.size > 0;
  // With no zone open, nothing is folded either. So the folded state is cleared when the last zone closes.
  if (!open && ui.zonesMinimised) {
    changeUi(u => { u.zonesMinimised = false; });
  }
  root.hidden = !open;
  // Back closes the zones the player opened. The game closes the zones it showed.
  sheet('zones', isPortrait() && open && ui.openZones.size > 0, () => changeUi(u => { u.openZones.clear(); }));
  root.classList.toggle('minimised', ui.zonesMinimised);
  // A zone the game shows is a question about its cards, laid out on the board like other questions. A zone opened only to look at stays a window.
  root.classList.toggle('asking', [...panels.values()].some(p => p.forced));
  if (!open) {
    root.replaceChildren();
    return;
  }
  if (ui.zonesMinimised) {
    drawBar(root, model, [...panels.values()]);
    return;
  }
  // Add the shelf again only when it is missing. Removing an open dialog from the page resets its scroll and loses the search box's focus.
  const box = shelf(root);
  if (root.firstElementChild !== box || root.childElementCount !== 1) root.replaceChildren(box);
  reconcile(q(root, '.zone-shelf'), [...panels.entries()], ([k]) => k, createPanel,
    (el, [, p]) => updatePanel(el, model, actions, p, select));
  for (const el of box.querySelectorAll<HTMLElement>('.zone-panel:not([data-opened])')) growFromPile(el);
  fitZones();
}

let fitting = false;

/** Sizes the cards of zones the game asks about to fill the space the board leaves, as for a question's cards. */
function fitZones(): void {
  if (!fitting) {
    fitting = true;
    window.addEventListener('resize', fitZones);
  }
  const root = byId('zones');
  const asked = [...root.querySelectorAll<HTMLElement>('.zone-panel.asking')];
  for (const el of asked) {
    const row = q(el, '.cards');
    // A long zone does not shrink its cards below the size used in a pile opened to look at. It scrolls instead.
    fitCards(row, el, root, row.querySelectorAll(':scope > :not(.back:not(.selectable))').length, { gap: 14, wrap: true, least: 1.45, share: asked.length });
  }
}

/** A pile opened to look at animates out of its pile on the board. Others use the normal dialog animation: a zone the game shows, any zone on a phone, or one with no pile on the board. */
function growFromPile(el: HTMLElement): void {
  const [player, zoneName] = (el.dataset.key ?? '').split('/');
  const pile = isPortrait() || el.classList.contains('asking') ? null
    : document.querySelector(`.seat[data-player="${player}"] ${zoneName === 'Hand' ? '.hand-fan' : `.zone-tile[data-zone="${zoneName}"]`}`)?.getBoundingClientRect();
  if (!pile?.width) {
    el.dataset.opened = 'plain';
    return;
  }
  const at = el.getBoundingClientRect();
  el.style.transformOrigin = `${pile.left + pile.width / 2 - at.left}px ${pile.top + pile.height / 2 - at.top}px`;
  el.dataset.opened = 'pile';
}

/** A window that opened out of a pile animates back into it before it closes. */
function putAway(el: HTMLElement, close: () => void): void {
  if (el.dataset.opened !== 'pile' || reducedMotion()) {
    close();
    return;
  }
  el.animate([{}, { opacity: 1, offset: .5 }, { opacity: 0, scale: .06 }], { duration: 170, easing: 'cubic-bezier(.5, 0, .8, .3)', fill: 'forwards' })
    .onfinish = close;
}

/** The single container for the dialogs. It is kept across renders so the reconciled panels are not lost. */
function shelf(root: HTMLElement): HTMLElement {
  const known = root.querySelector<HTMLElement>('.zone-shelf');
  if (known) {
    return known;
  }
  const el = make('div', 'zone-shelf');
  return el;
}

function createPanel(): HTMLElement {
  const el = make('section', 'zone-panel');
  el.innerHTML = '<p class="zone-ask"><span class="ask-source"></span><span class="ask-sep" aria-hidden="true">·</span><span class="ask-text"></span></p><header><b class="zone-who"></b><span class="zone-count"></span><span class="zone-gap"></span>'
    + '<input class="zone-find" type="search">'
    + '<label class="zone-sort"><select></select></label>'
    + '<button class="zone-fold"></button></header>'
    + '<div class="cards"></div>'
    + '<footer><span class="zone-hint"></span><button class="zone-done primary"></button>'
    + '<button class="zone-answer ok primary"><span class="label"></span><kbd>Space</kbd></button></footer>';
  const find = q<HTMLInputElement>(el, '.zone-find');
  find.placeholder = t('lblWebZoneSearch');
  find.setAttribute('aria-label', t('lblWebZoneSearch'));
  q(el, '.zone-sort').prepend(t('lblSort'));
  q(el, '.zone-fold').textContent = t('lblWebZoneShowBoard');
  q(el, '.zone-done').textContent = t('lblDone');
  const sort = q<HTMLSelectElement>(el, '.zone-sort select');
  for (const [id, name] of SORTS) sort.append(new Option(t(name), id));
  return el;
}

function updatePanel(el: HTMLElement, model: Model, actions: Actions, p: Panel, select: CardClick): void {
  const player = model.objects.get(p.player);
  const cards = shown(model, zone(model, player, p.zone));
  q(el, '.zone-who').textContent = `${player?.Name ?? ''} \u00b7 ${zoneTitle(p.zone)}`;
  q(el, '.zone-count').textContent = t(cards.length === 1 ? 'lblWebOneCard' : 'lblWebNCards', cards.length);
  const find = q<HTMLInputElement>(el, '.zone-find');
  if (find.value !== ui.zoneSearch) {
    find.value = ui.zoneSearch;
  }
  find.oninput = () => changeUi(u => { u.zoneSearch = find.value; });
  const sort = q<HTMLSelectElement>(el, '.zone-sort select');
  sort.value = ui.zoneSort;
  // A search orders the zone by how well each name matches, so the sort is disabled until the search is cleared
  sort.disabled = !!normalize(ui.zoneSearch);
  sort.onchange = () => changeUi(u => { u.zoneSort = sort.value as ZoneSort; });
  const fold = q(el, '.zone-fold');
  fold.onclick = () => changeUi(u => { u.zonesMinimised = true; });
  // A pile the player opened already has Done to close it
  fold.hidden = !p.forced;
  const asking = p.forced;
  el.classList.toggle('asking', asking);
  // On the board the fold button goes beside the answer, as in a question. A phone answers from the dock, so it stays in the header.
  const beside = asking && !isPortrait();
  const home = beside ? q(el, 'footer') : q(el, 'header');
  if (fold.parentElement !== home) {
    if (beside) home.insertBefore(fold, q(el, '.zone-answer.ok'));
    else home.append(fold);
  }
  // The game opens and closes a zone it shows, so that zone has no Done button
  const done = q<HTMLButtonElement>(el, '.zone-done');
  done.hidden = p.forced;
  done.onclick = () => {
    // A second press during the close animation would open it again
    done.disabled = true;
    putAway(el, () => togglePile(p.player, p.zone));
  };
  // A zone the game shows has the prompt's question and its OK button. It leaves out the other button, which is the way out of priority.
  const prompt = p.forced ? model.prompt : null;
  const question = p.forced ? prompt?.message || t('lblWebZoneWaitingOnYou') : '';
  q(el, '.zone-hint').textContent = question;
  // On the board the question is one line: the source card, then the question, as in a dialog
  const asked = splitQuestion(question);
  const source = sourceName(model, asked, prompt?.card);
  q(el, '.zone-ask .ask-source').textContent = source;
  q(el, '.zone-ask .ask-sep').hidden = !source;
  setSymbolText(q(el, '.zone-ask .ask-text'), asked.ask);
  answer(q(el, '.zone-answer.ok'), prompt?.ok, () => actions.ok());
  q(el, '.zone-answer.ok kbd').textContent = keyName(boundKeys().ok);
  reconcile(q(el, '.cards'), cards, c => c.$key, () => createCard(select), (c, card) => {
    updateCard(c, model, card);
    markPicked(c, asking && c.classList.contains('highlighted'));
  });
}

function answer(el: HTMLElement, button: PromptButton | undefined, run: () => void): void {
  el.hidden = !button?.enabled;
  q(el, '.label').textContent = button?.label ?? '';
  el.onclick = run;
}

/** The zone's cards in dialog order. While searching, the best match is first. Otherwise they follow the chosen sort. */
function shown(model: Model, cards: CardView[]): CardView[] {
  const name = (c: CardView) => stateOf(model, c).Name ?? '';
  if (normalize(ui.zoneSearch)) {
    return rankByName(cards.map(name), ui.zoneSearch).map(i => cards[i]);
  }
  const kept = [...cards];
  if (ui.zoneSort === 'name') {
    return kept.sort((a, b) => name(a).localeCompare(name(b)));
  }
  if (ui.zoneSort === 'type') {
    const type = (c: CardView) => stateOf(model, c).Type ?? '';
    return kept.sort((a, b) => type(a).localeCompare(type(b)) || name(a).localeCompare(name(b)));
  }
  if (ui.zoneSort === 'mana') {
    const value = (c: CardView) => manaValue(stateOf(model, c).ManaCost);
    return kept.sort((a, b) => value(a) - value(b) || name(a).localeCompare(name(b)));
  }
  return kept;
}

/** The folded bar. It shows what is open and a button to unfold it. */
function drawBar(root: HTMLElement, model: Model, panels: Panel[]): void {
  const first = panels[0];
  const player = model.objects.get(first.player);
  const count = panels.length > 1 ? t('lblWebZoneNZones', panels.length) : t('lblWebNCards', zone(model, player, first.zone).length);
  // Kept across renders, so a render between press and release does not lose the click
  let bar = root.querySelector<HTMLElement>(':scope > .zone-bar');
  if (!bar) {
    bar = document.createElement('section');
    bar.className = 'zone-bar';
    bar.innerHTML = '<span class="zone-dot" aria-hidden="true"></span><b></b><span class="zone-count"></span>'
      + '<button class="zone-unfold primary"></button>'
      + '<button class="zone-shut">×</button>';
    q(bar, '.zone-unfold').textContent = t('lblWebZoneShowCards');
    q(bar, '.zone-shut').setAttribute('aria-label', t('lblWebZoneClose'));
  }
  q(bar, 'b').textContent = panels.length > 1 ? t('lblWebZoneOpenZones') : `${player?.Name ?? ''} \u00b7 ${zoneTitle(first.zone)}`;
  q(bar, '.zone-count').textContent = count;
  q(bar, '.zone-unfold').onclick = () => changeUi(u => { u.zonesMinimised = false; });
  // Folding is not closing. So the close button still closes only the zones the player opened.
  const shut = q(bar, '.zone-shut');
  shut.hidden = panels.every(p => p.forced);
  shut.onclick = () => changeUi(u => {
    u.zonesMinimised = false;
    for (const p of panels) {
      if (!p.forced) u.openZones.delete(`${p.player}/${p.zone}`);
    }
  });
  if (root.firstElementChild !== bar || root.childElementCount !== 1) root.replaceChildren(bar);
}
