import { stateOf } from './model.js';
import { hoverCard } from './detail.js';
import { playerSleeveUrl, cssUrl } from './looks.js';

export const imageUrl = key => `img?key=${encodeURIComponent(key)}`;

// onClick receives the card element; the board selects the card, dialogs toggle an option
export function createCard(onClick) {
  const el = document.createElement('div');
  el.className = 'card';
  el.innerHTML = '<img alt="" draggable="false"><div class="frame"><b class="name"></b><span class="cost"></span><span class="type"></span></div><span class="pt"></span><span class="badges"></span><span class="sick" title="Summoning sick">Zz</span><span class="count"></span>';
  el.querySelector('img').addEventListener('error', () => el.classList.add('noimg'));
  el.addEventListener('click', () => onClick(el));
  el.addEventListener('mouseenter', () => hoverCard(el));
  el.addEventListener('mouseleave', () => hoverCard(null));
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
  const src = visible && state.ImageKey ? imageUrl(state.ImageKey) : '';
  const img = el.querySelector('img');
  if (img.dataset.src !== src) {
    img.dataset.src = src;
    el.classList.remove('noimg');
    if (src) img.src = src;
    else img.removeAttribute('src');
  }
  el.dataset.zoom = src;
  el.querySelector('.name').textContent = visible ? (state.Name ?? '') : '';
  el.querySelector('.cost').textContent = visible ? (state.ManaCost ?? '') : '';
  el.querySelector('.type').textContent = type;
  el.querySelector('.pt').textContent = /Creature/.test(type) ? `${state.Power ?? 0}/${state.Toughness ?? 0}`
    : /Planeswalker/.test(type) ? (state.Loyalty ?? '') : '';
  const badges = [];
  if (card.Damage) badges.push(`${card.Damage} dmg`);
  if (card.IsRingBearer) badges.push('Ring-bearer');
  for (const [name, n] of Object.entries(card.Counters ?? {})) badges.push(`${n} ${name}`);
  el.querySelector('.badges').textContent = badges.join(' · ');
}

export function setPileCount(el, count) {
  el.classList.toggle('pile', count > 1);
  el.querySelector('.count').textContent = count > 1 ? `×${count}` : '';
}
