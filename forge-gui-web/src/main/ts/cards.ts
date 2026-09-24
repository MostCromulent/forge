import { stateOf, type Model } from './model';
import { hoverable } from './detail';
import { cardImageSrc, noImageOnError, setImage, setSymbolText } from './images';
import { playerSleeveUrl, cssUrl } from './looks';
import { q } from './dom';
import type { CardView, Ref } from './protocol';

/** What a card does when clicked: the board selects it, a dialog toggles an option. menu is a right-click. */
export type CardClick = (el: HTMLElement, menu: boolean, e?: MouseEvent) => void;

export function createCard(onClick: CardClick): HTMLDivElement {
  const el = document.createElement('div');
  el.className = 'card';
  el.innerHTML = '<img alt="" draggable="false"><div class="frame"><b class="name"></b><span class="cost"></span><span class="type"></span></div><span class="pt"><i class="pt-p"></i><i class="pt-t"></i></span><span class="badges"></span><span class="sick" title="Summoning sick">Zz</span><span class="count"></span><span class="cost-badge"></span><i class="halo" aria-hidden="true"></i><i class="rim" aria-hidden="true"></i><span class="haze" aria-hidden="true"></span>';
  noImageOnError(el, q<HTMLImageElement>(el, 'img'));
  el.addEventListener('click', e => onClick(el, false, e));
  // The right button asks what else the card can do, as it does on desktop
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    onClick(el, true, e);
  });
  hoverable(el);
  return el;
}

const has = (refs: Ref[] | undefined, key: number) => (refs ?? []).some(r => r.ref === key);

export function updateCard(el: HTMLElement, model: Model, card: CardView): void {
  const state = stateOf(model, card);
  const visible = model.visible.has(card.$key);
  // Only a prompt that demands a pick rings its cards; an optional one leaves the playable outline to do it
  const selectable = (model.prompt?.selectableMin ?? 0) > 0 && has(model.prompt?.selectable, card.$key);
  const type = visible ? (state.Type ?? '') : '';
  el.classList.toggle('back', !visible);
  // A hidden card shows its owner's sleeve
  const owner = card.Owner ? model.objects.get(card.Owner.ref) : undefined;
  el.style.setProperty('--sleeve', cssUrl(visible ? '' : playerSleeveUrl(owner)));
  el.classList.toggle('tapped', !!card.Tapped);
  el.classList.toggle('selectable', selectable);
  el.classList.toggle('playable', has(model.playable?.cards, card.$key));
  el.classList.toggle('auto-tap', has(model.playable?.autoTap, card.$key));
  el.classList.toggle('highlighted', (model.prompt?.highlighted ?? []).includes(card.$key));
  el.classList.toggle('attacking', !!card.Attacking);
  el.classList.toggle('blocking', !!card.Blocking);
  el.classList.toggle('phased', !!card.PhasedOut);
  const src = cardImageSrc(model, card);
  if (setImage(q<HTMLImageElement>(el, 'img'), src)) {
    el.classList.remove('noimg');
  }
  el.dataset.zoom = src;
  q(el, '.name').textContent = visible ? (state.Name ?? '') : '';
  setSymbolText(q(el, '.cost'), visible ? state.ManaCost : '');
  setSymbolText(q(el, '.cost-badge'), visible ? state.ManaCost : '');
  q(el, '.type').textContent = type;
  const pt = q(el, '.pt');
  const creature = /Creature/.test(type);
  const damage = card.Damage ?? 0;
  const power = creature ? `${state.Power ?? 0}/` : '';
  // Damage comes off the toughness the way a player counts it, rather than being listed beside the card
  const toughness = creature ? `${(state.Toughness ?? 0) - damage}`
    : /Planeswalker/.test(type) ? `${state.Loyalty ?? ''}` : '';
  // A pump, a counter or a loyalty change is a number the player must notice
  if (pt.textContent && power + toughness && pt.textContent !== power + toughness) {
    pt.classList.remove('changed');
    void pt.offsetWidth;
    pt.classList.add('changed');
  }
  q(el, '.pt-p').textContent = power;
  const hurt = q(el, '.pt-t');
  hurt.textContent = toughness;
  hurt.classList.toggle('hurt', creature && damage > 0);
  pt.classList.toggle('on', !!(power + toughness));
  showDamage(el, damage);
  const badges: string[] = [];
  if (card.IsRingBearer) badges.push('Ring-bearer');
  for (const [name, n] of Object.entries(card.Counters ?? {})) badges.push(`${n} ${name}`);
  q(el, '.badges').textContent = badges.join(' · ');
}

// New damage jolts the card and throws the number off it, so combat is legible without the log
function showDamage(el: HTMLElement, damage: number): void {
  const before = el.dataset.damage === undefined ? damage : Number(el.dataset.damage);
  el.dataset.damage = String(damage);
  if (damage <= before) {
    return;
  }
  el.classList.remove('struck');
  void el.offsetWidth;
  el.classList.add('struck');
  const hit = document.createElement('span');
  hit.className = 'hit-number';
  hit.textContent = `-${damage - before}`;
  el.append(hit);
  hit.addEventListener('animationend', () => hit.remove());
}

export function setPileCount(el: HTMLElement, count: number, opened: boolean): void {
  el.classList.toggle('pile', count > 1);
  // Up to three edges show behind the top card, so a pile of two never looks like a pile of five
  el.dataset.depth = String(Math.min(3, Math.max(0, count - 1)));
  const badge = q(el, '.count');
  badge.textContent = opened ? '×' : count > 1 ? `×${count}` : '';
  badge.classList.toggle('opened', opened);
  badge.title = opened ? 'Put the pile back together' : count > 1 ? 'Lay the pile out' : '';
}
