import { stateOf } from './model.js';
import { hoverable } from './detail.js';
import { cardImageSrc, noImageOnError, setImage, setSymbolText } from './images.js';
import { playerSleeveUrl, cssUrl } from './looks.js';

// onClick receives the card element; the board selects the card, dialogs toggle an option
export function createCard(onClick) {
  const el = document.createElement('div');
  el.className = 'card';
  el.innerHTML = '<img alt="" draggable="false"><div class="frame"><b class="name"></b><span class="cost"></span><span class="type"></span></div><span class="pt"></span><span class="badges"></span><span class="sick" title="Summoning sick">Zz</span><span class="count"></span><span class="cost-badge"></span>';
  noImageOnError(el, el.querySelector('img'));
  el.addEventListener('click', () => onClick(el, false));
  // The right button asks what else the card can do, as it does on desktop
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    onClick(el, true);
  });
  hoverable(el);
  return el;
}

const has = (refs, key) => (refs ?? []).some(r => r?.ref === key);

export function updateCard(el, model, card) {
  const state = stateOf(model, card);
  const visible = model.visible.has(card.$key);
  // Only a prompt that demands a pick rings its cards; an optional one leaves the playable outline to do it
  const selectable = (model.prompt?.selectableMin ?? 0) > 0 && has(model.prompt?.selectable, card.$key);
  const type = visible ? (state.Type ?? '') : '';
  el.classList.toggle('back', !visible);
  // A hidden card shows its owner's sleeve
  el.style.setProperty('--sleeve', cssUrl(visible ? '' : playerSleeveUrl(model.objects.get(card.Owner?.ref))));
  el.classList.toggle('tapped', !!card.Tapped);
  el.classList.toggle('selectable', selectable);
  el.classList.toggle('playable', has(model.playable?.cards, card.$key));
  el.classList.toggle('auto-tap', has(model.playable?.autoTap, card.$key));
  el.classList.toggle('highlighted', (model.prompt?.highlighted ?? []).includes(card.$key));
  el.classList.toggle('attacking', !!card.Attacking);
  el.classList.toggle('blocking', !!card.Blocking);
  el.classList.toggle('phased', !!card.PhasedOut);
  const src = cardImageSrc(model, card);
  if (setImage(el.querySelector('img'), src)) {
    el.classList.remove('noimg');
  }
  el.dataset.zoom = src;
  el.querySelector('.name').textContent = visible ? (state.Name ?? '') : '';
  setSymbolText(el.querySelector('.cost'), visible ? state.ManaCost : '');
  setSymbolText(el.querySelector('.cost-badge'), visible ? state.ManaCost : '');
  el.querySelector('.type').textContent = type;
  const pt = el.querySelector('.pt');
  const shown = /Creature/.test(type) ? `${state.Power ?? 0}/${state.Toughness ?? 0}`
    : /Planeswalker/.test(type) ? `${state.Loyalty ?? ''}` : '';
  // A pump, a counter or a loyalty change is a number the player must notice
  if (pt.textContent && shown && pt.textContent !== shown) {
    pt.classList.remove('changed');
    void pt.offsetWidth;
    pt.classList.add('changed');
  }
  pt.textContent = shown;
  showDamage(el, card.Damage ?? 0);
  const badges = [];
  if (card.Damage) badges.push(`${card.Damage} dmg`);
  if (card.IsRingBearer) badges.push('Ring-bearer');
  for (const [name, n] of Object.entries(card.Counters ?? {})) badges.push(`${n} ${name}`);
  el.querySelector('.badges').textContent = badges.join(' · ');
}

// New damage jolts the card and throws the number off it, so combat is legible without the log
function showDamage(el, damage) {
  const before = el.dataset.damage === undefined ? damage : Number(el.dataset.damage);
  el.dataset.damage = damage;
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

export function setPileCount(el, count, opened) {
  el.classList.toggle('pile', count > 1);
  // Up to three edges show behind the top card, so a pile of two never looks like a pile of five
  el.dataset.depth = String(Math.min(3, Math.max(0, count - 1)));
  const badge = el.querySelector('.count');
  badge.textContent = opened ? '×' : count > 1 ? `×${count}` : '';
  badge.classList.toggle('opened', !!opened);
  badge.title = opened ? 'Put the pile back together' : count > 1 ? 'Lay the pile out' : '';
}
