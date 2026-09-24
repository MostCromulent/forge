import { stateOf, type Model } from './model';
import { hoverable } from './detail';
import { abilityUrl, cardImageSrc, hideOnError, noImageOnError, setImage, setSymbolText } from './images';
import { playerSleeveUrl, cssUrl } from './looks';
import { reconcile } from './render';
import { q } from './dom';
import type { CardView, KeywordText, Ref } from './protocol';

/** What a card does when clicked: the board selects it, a dialog toggles an option. menu is a right-click. */
export type CardClick = (el: HTMLElement, menu: boolean, e?: MouseEvent) => void;

export function createCard(onClick: CardClick): HTMLDivElement {
  const el = document.createElement('div');
  el.className = 'card';
  el.innerHTML = '<img alt="" draggable="false"><div class="frame"><b class="name"></b><span class="cost"></span><span class="type"></span></div><span class="pt"><i class="pt-p"></i><i class="pt-t"></i></span><span class="badges"></span><span class="sick" title="Summoning sick">Zz</span><span class="count"></span><span class="cost-badge"></span><span class="corner"><span class="mech"></span><span class="kws"></span></span><span class="blocks"></span><i class="halo" aria-hidden="true"></i><i class="rim" aria-hidden="true"></i><span class="haze" aria-hidden="true"></span>';
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
  // A battle lies on its side, as it is printed and as every other client draws it
  el.classList.toggle('battle', /Battle/.test(type));
  // Damage comes off the toughness the way a player counts it, rather than being listed beside the card
  const toughness = creature ? `${(state.Toughness ?? 0) - damage}`
    : /Planeswalker/.test(type) ? `${state.Loyalty ?? ''}`
    : /Battle/.test(type) ? `${state.Defense ?? ''}` : '';
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
  showKeywords(q(el, '.kws'), visible ? state.Keywords : undefined, visible ? card.ShieldCount : undefined);
  q(el, '.mech').replaceChildren(...(visible ? mechanic(card) : []));
  showBlocking(el, card);
  const badges: string[] = [];
  if (card.IsRingBearer) badges.push('Ring-bearer');
  for (const [name, n] of Object.entries(card.Counters ?? {})) badges.push(`${n} ${name}`);
  q(el, '.badges').textContent = badges.join(' · ');
}

/**
 * The keywords that decide a block, as the pictures desktop uses for them. Only ones the skin has an icon for
 * appear: the host names the icon (FSkinProp.iconFromKeyword) so neither client keeps its own table. The rest of
 * a card's keywords are in its rules text, which the detail panel shows on hover.
 *
 * Reminder text goes in the title so a player who does not know the picture can still find out what it means.
 */
function showKeywords(root: HTMLElement, keywords: KeywordText[] | undefined, shields: number | undefined): void {
  const shown = (keywords ?? []).filter(k => k.icon);
  reconcile(root, shown, k => k.icon ?? k.title, () => {
    const img = document.createElement('img');
    img.alt = '';
    hideOnError(img);
    return img;
  }, (img, k) => {
    setImage(img as HTMLImageElement, abilityUrl(k.icon ?? ''));
    img.title = k.reminder ? `${k.title} — ${k.reminder}` : k.title;
  });
  // A shield counter is a counter by the rules and a keyword by the way it is used: you look for it when working
  // out whether removal resolves or a block kills, which is when you are reading this strip anyway
  const had = root.querySelector('.shield');
  if (!shields) {
    had?.remove();
    return;
  }
  const shield = had ?? root.appendChild(shieldBadge());
  q(shield as HTMLElement, '.n').textContent = String(shields);
  (shield as HTMLElement).title = `${shields} shield counter${shields === 1 ? '' : 's'}`;
}

function shieldBadge(): HTMLElement {
  const el = document.createElement('span');
  el.className = 'shield';
  el.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.6 13.4 3.4v4.4c0 3.1-2.2 5.6-5.4 6.6-3.2-1-5.4-3.5-5.4-6.6V3.4Z"/></svg><i class="n"></i>';
  return el;
}

/**
 * Where a permanent has got to in whatever track its set gave it: a Class's level, the Ring's tier, an unlocked
 * Room, a contraption's sprocket, an attraction's lit numbers, an Omen's intensity. They never co-occur, so one
 * chip serves them all, and it sits above the keyword icons rather than on the top edge, which is the card's name.
 */
function mechanic(card: CardView): Node[] {
  const track = (now: number, of: number, text: string) => {
    const pips = document.createElement('span');
    pips.className = 'pips';
    for (let i = 0; i < of; i++) {
      const pip = document.createElement('i');
      pip.className = i < now ? 'on' : '';
      pips.append(pip);
    }
    return [pips, label(text)];
  };
  if (card.ClassLevel) return track(card.ClassLevel, 3, `Level ${card.ClassLevel}`);
  if (card.RingLevel) return track(card.RingLevel, 4, `Ring ${'I'.repeat(card.RingLevel).replace('IIII', 'IV')}`);
  if (card.CurrentRoom) return [label(card.CurrentRoom)];
  if (card.Sprocket) return track(card.Sprocket, 3, `Sprocket ${card.Sprocket}`);
  if (card.AttractionLights?.length) {
    const lit = new Set(card.AttractionLights);
    const pips = document.createElement('span');
    pips.className = 'pips';
    for (let i = 1; i <= 6; i++) {
      const pip = document.createElement('i');
      pip.className = lit.has(i) ? 'lit' : '';
      pips.append(pip);
    }
    return [pips, label(card.AttractionLights.join(' '))];
  }
  // Intensity has no ceiling, so there is no track to draw — the number says it on its own
  if (card.Intensity) return [label(`Intensity ${card.Intensity}`)];
  return [];
}

function label(text: string): HTMLElement {
  const el = document.createElement('i');
  el.className = 'mech-text';
  el.textContent = text;
  return el;
}

/**
 * Why a block is or is not allowed. A creature that must block something is lit and tied to it by the overlay;
 * one that may block more than one carries how many. Both only while blockers are being declared, because that
 * is the only step either fact can change anything.
 */
function showBlocking(el: HTMLElement, card: CardView): void {
  const must = (card.MustBlockCards ?? []).filter(r => r).length > 0;
  const extra = card.BlockAny ? '∞' : card.BlockAdditional ? String(card.BlockAdditional + 1) : '';
  el.classList.toggle('must-block', must);
  const blocks = q(el, '.blocks');
  blocks.textContent = extra;
  blocks.title = card.BlockAny ? 'May block any number of creatures'
    : card.BlockAdditional ? `May block ${card.BlockAdditional + 1} creatures` : '';
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
