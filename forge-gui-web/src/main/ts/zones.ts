import { reconcile } from './render';
import { createCard, updateCard, type CardClick } from './cards';
import { stateOf, zone, type Model } from './model';
import { byId, q } from './dom';
import { changeUi, ui, type ZoneSort } from './ui';
import type { Actions } from './actions';
import type { CardView, PromptButton, ZoneType } from './protocol';

// Looking through a zone: a dialog over a dimmed board, because the board has nothing to say while you are
// reading a library of thirty. It folds to a bar instead of closing, so the board can be read without losing
// your place — which is what the game's own openings need, since those sit beside a prompt you are answering.

// Piles the player opened by clicking, on top of the zones the game asks to show
interface Panel {
  player: number;
  zone: ZoneType;
  /** The game is showing it, so the game, not a Close button, puts it away. */
  forced: boolean;
}

const SORTS: [ZoneSort, string][] = [['order', 'Zone order'], ['name', 'By name'], ['type', 'By type']];

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
  // Nothing open means nothing folded away either, so the bar cannot outlive what it stands for
  if (!open && ui.zonesMinimised) {
    changeUi(u => { u.zonesMinimised = false; });
  }
  root.hidden = !open;
  root.classList.toggle('minimised', ui.zonesMinimised);
  if (!open) {
    root.replaceChildren();
    return;
  }
  if (ui.zonesMinimised) {
    drawBar(root, model, [...panels.values()]);
    return;
  }
  root.replaceChildren(shelf(root));
  reconcile(q(root, '.zone-shelf'), [...panels.entries()], ([k]) => k, createPanel,
    (el, [, p]) => updatePanel(el, model, actions, p, select));
}

/** The one element the dialogs sit in, kept across renders so a reconcile of the panels is not undone. */
function shelf(root: HTMLElement): HTMLElement {
  const known = root.querySelector<HTMLElement>('.zone-shelf');
  if (known) {
    return known;
  }
  const el = document.createElement('div');
  el.className = 'zone-shelf';
  return el;
}

function createPanel(): HTMLElement {
  const el = document.createElement('section');
  el.className = 'zone-panel';
  el.innerHTML = '<header><b class="zone-who"></b><span class="zone-count"></span><span class="zone-gap"></span>'
    + '<input class="zone-find" type="search" placeholder="Search this zone" aria-label="Search this zone">'
    + '<label class="zone-sort">Sort<select></select></label>'
    + '<button class="zone-fold">Show board</button></header>'
    + '<div class="cards"></div>'
    + '<footer><span class="zone-hint"></span><button class="zone-done primary">Done</button>'
    + '<button class="zone-answer ok primary"><span class="label"></span><kbd>Space</kbd></button>'
    + '<button class="zone-answer cancel"><span class="label"></span><kbd>Esc</kbd></button></footer>';
  const sort = q<HTMLSelectElement>(el, '.zone-sort select');
  sort.innerHTML = SORTS.map(([id, name]) => `<option value="${id}">${name}</option>`).join('');
  return el;
}

function updatePanel(el: HTMLElement, model: Model, actions: Actions, p: Panel, select: CardClick): void {
  const player = model.objects.get(p.player);
  const cards = shown(model, zone(model, player, p.zone));
  q(el, '.zone-who').textContent = `${player?.Name ?? ''} · ${p.zone}`;
  q(el, '.zone-count').textContent = `${cards.length} ${cards.length === 1 ? 'card' : 'cards'}`;
  const find = q<HTMLInputElement>(el, '.zone-find');
  if (find.value !== ui.zoneSearch) {
    find.value = ui.zoneSearch;
  }
  find.oninput = () => changeUi(u => { u.zoneSearch = find.value.trim().toLowerCase(); });
  const sort = q<HTMLSelectElement>(el, '.zone-sort select');
  sort.value = ui.zoneSort;
  sort.onchange = () => changeUi(u => { u.zoneSort = sort.value as ZoneSort; });
  q(el, '.zone-fold').onclick = () => changeUi(u => { u.zonesMinimised = true; });
  // The game put this one up and the game takes it down, so it offers no way out of its own
  const done = q(el, '.zone-done');
  done.hidden = p.forced;
  done.onclick = () => togglePile(p.player, p.zone);
  // The dialog covers the prompt, so one the game put up carries the prompt's question and its buttons
  const prompt = p.forced ? model.prompt : null;
  q(el, '.zone-hint').textContent = p.forced ? prompt?.message || 'The game is waiting on your choice.' : 'Click a card to pick it up.';
  answer(q(el, '.zone-answer.ok'), prompt?.ok, () => actions.ok());
  answer(q(el, '.zone-answer.cancel'), prompt?.cancel, () => actions.cancel());
  reconcile(q(el, '.cards'), cards, c => c.$key, () => createCard(select), (c, card) => updateCard(c, model, card));
}

function answer(el: HTMLElement, button: PromptButton | undefined, run: () => void): void {
  el.hidden = !button?.enabled;
  q(el, '.label').textContent = button?.label ?? '';
  el.onclick = run;
}

/** The zone as the dialog lists it: narrowed by the search, then in the order asked for. */
function shown(model: Model, cards: CardView[]): CardView[] {
  const find = ui.zoneSearch;
  const kept = find ? cards.filter(c => (stateOf(model, c).Name ?? '').toLowerCase().includes(find)) : [...cards];
  const name = (c: CardView) => stateOf(model, c).Name ?? '';
  if (ui.zoneSort === 'name') {
    return kept.sort((a, b) => name(a).localeCompare(name(b)));
  }
  if (ui.zoneSort === 'type') {
    const type = (c: CardView) => stateOf(model, c).Type ?? '';
    return kept.sort((a, b) => type(a).localeCompare(type(b)) || name(a).localeCompare(name(b)));
  }
  return kept;
}

/** Folded away: what is open, and the way back. */
function drawBar(root: HTMLElement, model: Model, panels: Panel[]): void {
  const first = panels[0];
  const player = model.objects.get(first.player);
  const count = panels.length > 1 ? `${panels.length} zones` : `${zone(model, player, first.zone).length} cards`;
  root.replaceChildren();
  const bar = document.createElement('section');
  bar.className = 'zone-bar';
  bar.innerHTML = '<span class="zone-dot" aria-hidden="true"></span><b></b><span class="zone-count"></span>'
    + '<button class="zone-unfold primary">Show cards</button>'
    + '<button class="zone-shut" aria-label="Close the zone">×</button>';
  q(bar, 'b').textContent = panels.length > 1 ? 'Open zones' : `${player?.Name ?? ''} · ${first.zone}`;
  q(bar, '.zone-count').textContent = count;
  q(bar, '.zone-unfold').onclick = () => changeUi(u => { u.zonesMinimised = false; });
  // Folding away is not closing, so the way out is still only offered for the ones the player opened
  const shut = q(bar, '.zone-shut');
  shut.hidden = panels.every(p => p.forced);
  shut.onclick = () => changeUi(u => {
    u.zonesMinimised = false;
    for (const p of panels) {
      if (!p.forced) u.openZones.delete(`${p.player}/${p.zone}`);
    }
  });
  root.append(bar);
}
