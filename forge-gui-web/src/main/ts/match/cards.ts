// One card on the page, built here by every zone that shows one, so a card looks the same wherever it is

import { combatShown, commanderTax, deref, game, stateOf, type Model } from '../model';
import { hoverable, inspectCard } from './detail';
import { longPress } from '../press';
import { abilityUrl, cardImageSrc, hideOnError, noImageOnError, setImage, setSymbolText, smallImage } from '../images';
import { playerSleeveUrl, cssUrl } from '../looks';
import { reconcile } from './render';
import { q, replay, make } from '../dom';
import type { CardView, KeywordText, PlayerView, Ref } from '../protocol';
import { t } from '../text';

/** What a card does when clicked: the board selects it, a dialog toggles an option. menu is a right-click. */
export type CardClick = (el: HTMLElement, menu: boolean, e?: MouseEvent) => void;

// Lucide's eye (ISC, see web/licenses/lucide-license.txt)
const EYE = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/></svg>';

export function createCard(onClick: CardClick): HTMLDivElement {
  const el = make('div', 'card');
  el.innerHTML = '<img alt="" draggable="false"><div class="frame"><b class="name"></b><span class="cost"></span><span class="type"></span></div><span class="pt"><i class="pt-p"></i><i class="pt-t"></i></span><span class="badges"></span><span class="sick">Zz</span><span class="count"></span><span class="cost-badge"></span><span class="seen">' + EYE + '</span><span class="owned-by"></span><span class="kind-tag"></span><span class="corner"><span class="mech"></span><span class="kws"></span></span><span class="blocks"></span><span class="block-tab"></span><span class="combat-tag"></span><i class="halo" aria-hidden="true"></i><i class="rim" aria-hidden="true"></i><span class="haze" aria-hidden="true"></span><i class="pile-edge" aria-hidden="true"></i>';
  q(el, '.sick').title = t('lblWebCardSummoningSick');
  q(el, '.seen').title = t('lblWebCardRevealed');
  q(el, '.block-tab').textContent = t('lblWebCardBlocking');
  noImageOnError(el, q<HTMLImageElement>(el, 'img'));
  el.addEventListener('click', e => onClick(el, false, e));
  // The right button asks what else the card can do, as it does on desktop
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    onClick(el, true, e);
  });
  hoverable(el);
  el.addEventListener('pointerdown', e => longPress(e, () => inspectCard(el)));
  return el;
}

const has = (refs: Ref[] | undefined, key: number) => (refs ?? []).some(r => r.ref === key);

/** The frame of a card drawn without its image, from a MagicColor mask: its one colour, M for several, and L or C for none. */
export function frameColour(colours: number, type: string): string {
  const one = [[1, 'W'], [2, 'U'], [4, 'B'], [8, 'R'], [16, 'G']].filter(([bit]) => colours & (bit as number));
  if (one.length > 1) return 'M';
  if (one.length === 1) return one[0][1] as string;
  return /Land/.test(type) ? 'L' : 'C';
}

/** The last picture each card was shown with, for a spell whose card is hidden by the time its cast is seen. */
const pictures = new Map<number, { src: string; zoom: string; name: string }>();

export function lastPicture(key: number): { src: string; zoom: string; name: string } | undefined {
  return pictures.get(key);
}

/** A new table reuses card keys, so the pictures of the last one mean nothing. */
export function forgetPictures(): void {
  pictures.clear();
}

export function updateCard(el: HTMLElement, model: Model, card: CardView): void {
  const state = stateOf(model, card);
  // A card held face down in exile under a permanent shows its owner's sleeve, even when its owner may look at it
  const visible = model.visible.has(card.$key) && !(card.Facedown && el.classList.contains('ghost'));
  // Only a prompt that demands a pick rings its cards; an optional one leaves the playable outline to do it
  const selectable = (model.prompt?.selectableMin ?? 0) > 0 && has(model.prompt?.selectable, card.$key);
  const type = visible ? (state.Type ?? '') : '';
  el.classList.toggle('back', !visible);
  // A hidden card shows its owner's sleeve
  const owner = card.Owner ? model.objects.get(card.Owner.ref) : undefined;
  el.style.setProperty('--sleeve', cssUrl(visible ? '' : playerSleeveUrl(owner)));
  el.classList.toggle('tapped', !!card.Tapped);
  el.classList.toggle('flying', visible && !!state.Keywords?.some(k => k.icon === 'IMG_ABILITY_FLYING'));
  // Each flyer starts at its own point in its hover, so a row of them never moves together
  el.style.setProperty('--hover-at', `${-(card.$key * 1.37 % 4.6).toFixed(2)}s`);
  el.classList.toggle('selectable', selectable);
  el.classList.toggle('playable', has(model.playable?.cards, card.$key));
  el.classList.toggle('auto-tap', has(model.playable?.autoTap, card.$key));
  el.classList.toggle('highlighted', (model.prompt?.highlighted ?? []).includes(card.$key));
  const attacking = !!card.Attacking && combatShown(model);
  el.classList.toggle('attacking', attacking);
  // On a phone the attacker a tap on your creature will block says so, since the game may have picked it for you
  const blockThis = attacking && game(model)?.Phase === 'COMBAT_DECLARE_BLOCKERS' && (model.prompt?.highlighted ?? []).includes(card.$key);
  q(el, '.combat-tag').textContent = blockThis ? t('lblWebPortraitBlockingThis') : '';
  el.classList.toggle('blocking', !!card.Blocking);
  el.classList.toggle('phased', !!card.PhasedOut);
  // The zoom shows the card large, so it keeps the full image
  const src = cardImageSrc(model, card);
  const small = visible ? smallImage(src) : '';
  if (setImage(q<HTMLImageElement>(el, 'img'), small)) {
    el.classList.remove('noimg');
  }
  el.dataset.zoom = src;
  if (small) pictures.set(card.$key, { src: small, zoom: src, name: state.Name ?? '' });
  el.style.setProperty('--pile-img', small ? cssUrl(small) : 'none');
  q(el, '.name').textContent = visible ? (state.Name ?? '') : '';
  setCost(q(el, '.cost'), visible ? state.ManaCost ?? '' : '');
  // A commander waiting in the command zone costs its tax on top, so the cost to cast it says so
  const tax = card.Zone === 'Command' ? commanderTax(deref(model, card.Owner) as PlayerView | undefined, card) : 0;
  const badge = q(el, '.cost-badge');
  setCost(badge, visible ? withTax(state.ManaCost ?? '', tax) : '', true);
  // A perpetual effect or a commander's tax has changed what the card costs from what is printed on it
  const perpetual = state.OriginalManaCost !== undefined && (state.ManaCost ?? '') !== state.OriginalManaCost;
  badge.classList.toggle('changed', visible && (tax > 0 || perpetual));
  q(el, '.type').textContent = type;
  el.dataset.frame = visible ? frameColour(state.Colors ?? 0, type) : '';
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
    replay(pt, 'changed');
  }
  // Above or below what the card is on its own, as a pump, a counter or a shrink leaves it; damage counts as below
  const shift = (now: number, base: number | undefined) => (base === undefined || now === base ? '' : now > base ? 'up' : 'down');
  const p = q(el, '.pt-p');
  p.textContent = power;
  p.dataset.shift = creature ? shift(state.Power ?? 0, state.BasePower) : '';
  const hurt = q(el, '.pt-t');
  hurt.textContent = toughness;
  hurt.dataset.shift = creature ? shift((state.Toughness ?? 0) - damage, state.BaseToughness) : '';
  hurt.classList.toggle('hurt', creature && damage > 0);
  pt.classList.toggle('on', !!(power + toughness));
  showDamage(el, damage);
  showKeywords(q(el, '.kws'), visible ? state.Keywords : undefined, visible ? card.ShieldCount : undefined);
  const mech = visible ? mechanic(card, type) : [];
  const box = q(el, '.mech');
  const html = mech.map(n => (n as HTMLElement).outerHTML).join('');
  if (box.dataset.html !== html) {
    box.dataset.html = html;
    box.replaceChildren(...mech);
  }
  showBlocking(el, card);
  const badges: string[] = [];
  if (card.IsRingBearer) badges.push(t('lblWebCardRingBearer'));
  // The corner already gives a planeswalker's loyalty and a battle's defense, unless the card is a creature too
  const inCorner = creature ? null : /Planeswalker/.test(type) ? 'Loyalty' : /Battle/.test(type) ? 'Defense' : null;
  // The count is marked with ×, so +1/+1 ×2 is never read as a change to the P/T
  for (const [name, n] of Object.entries(card.Counters ?? {})) {
    if (name !== inCorner) badges.push(`${name} ×${n}`);
  }
  q(el, '.badges').textContent = badges.join(' · ');
}

/** Only keywords the host names an icon for appear, so neither client keeps its own table of them. */
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
  // A shield counter sits with the keywords because it is looked for when working out whether removal or a block kills
  const had = root.querySelector('.shield');
  if (!shields) {
    had?.remove();
    return;
  }
  const shield = had ?? root.appendChild(shieldBadge());
  q(shield as HTMLElement, '.n').textContent = String(shields);
  (shield as HTMLElement).title = t(shields === 1 ? 'lblWebCardShieldCounter' : 'lblWebCardShieldCounters', shields);
}

function shieldBadge(): HTMLElement {
  const el = make('span', 'shield');
  el.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.6 13.4 3.4v4.4c0 3.1-2.2 5.6-5.4 6.6-3.2-1-5.4-3.5-5.4-6.6V3.4Z"/></svg><i class="n"></i>';
  return el;
}

/** A mana cost with more generic mana added to it, as a tax adds it: {2}{G} with 2 more is {4}{G}. */
function withTax(cost: string, tax: number): string {
  if (!tax) return cost;
  const generic = /^\{(\d+)\}/.exec(cost);
  return generic ? `{${Number(generic[1]) + tax}}${cost.slice(generic[0].length)}` : `{${tax}}${cost}`;
}

// Every card is updated on every frame, so its cost is rebuilt only when it changes
function setCost(el: HTMLElement, text: string, pips = false): void {
  if (el.dataset.text !== text) {
    el.dataset.text = text;
    setSymbolText(el, text);
    // An image cannot carry the light drawn round a changed cost, so each symbol gets a box of its own to hold it
    if (pips) {
      for (const sym of [...el.children]) {
        const pip = make('span', 'pip');
        sym.replaceWith(pip);
        pip.append(sym);
      }
    }
  }
}

/** Where a permanent has got to in its set's track, and a card has at most one such track, so one chip serves them all. */
function mechanic(card: CardView, type: string): Node[] {
  const track = (now: number, of: number, text: string) => {
    const pips = make('span', 'pips');
    for (let i = 0; i < of; i++) {
      const pip = make('i', i < now ? 'on' : '');
      pips.append(pip);
    }
    return [pips, label(text)];
  };
  if (card.ClassLevel && /\bClass\b/.test(type)) return track(card.ClassLevel, 3, t('lblWebCardClassLevel', card.ClassLevel));
  if (card.RingLevel) return track(card.RingLevel, 4, t('lblWebCardRingLevel', 'I'.repeat(card.RingLevel).replace('IIII', 'IV')));
  if (card.CurrentRoom) return [label(card.CurrentRoom)];
  if (card.Sprocket) return track(card.Sprocket, 3, t('lblWebCardSprocket', card.Sprocket));
  if (card.AttractionLights?.length) {
    const lit = new Set(card.AttractionLights);
    const pips = make('span', 'pips');
    for (let i = 1; i <= 6; i++) {
      const pip = make('i', lit.has(i) ? 'lit' : '');
      pips.append(pip);
    }
    return [pips, label(card.AttractionLights.join(' '))];
  }
  // Intensity has no ceiling, so there is no track to draw — the number says it on its own
  if (card.Intensity) return [label(t('lblWebCardIntensity', card.Intensity))];
  return [];
}

function label(text: string): HTMLElement {
  const el = make('i', 'mech-text');
  el.textContent = text;
  return el;
}

/** A creature that must block something is lit, and one that may block more than one carries how many. */
function showBlocking(el: HTMLElement, card: CardView): void {
  const must = (card.MustBlockCards ?? []).filter(r => r).length > 0;
  const extra = card.BlockAny ? '∞' : card.BlockAdditional ? String(card.BlockAdditional + 1) : '';
  el.classList.toggle('must-block', must);
  const blocks = q(el, '.blocks');
  blocks.textContent = extra;
  blocks.title = card.BlockAny ? t('lblWebCardMayBlockAny')
    : card.BlockAdditional ? t('lblWebCardMayBlockCount', card.BlockAdditional + 1) : '';
}

// New damage flashes the card white, jolts it and throws the number off it, so combat is legible without the log
function showDamage(el: HTMLElement, damage: number): void {
  const before = el.dataset.damage === undefined ? damage : Number(el.dataset.damage);
  el.dataset.damage = String(damage);
  if (damage <= before) {
    return;
  }
  replay(el, 'struck');
  const flash = make('span', 'hit-white');
  el.append(flash);
  flash.addEventListener('animationend', () => flash.remove());
  const hit = make('span', 'hit-number');
  hit.textContent = `-${damage - before}`;
  el.append(hit);
  hit.addEventListener('animationend', () => hit.remove());
}

export function setPileCount(el: HTMLElement, count: number, opened: boolean): void {
  el.classList.toggle('pile', count > 1);
  // Up to three copies show behind the top card, so a pile of four or more shows four cards and the badge counts the rest
  el.dataset.depth = String(Math.min(3, Math.max(0, count - 1)));
  const badge = q(el, '.count');
  badge.textContent = opened ? '×' : count > 1 ? `×${count}` : '';
  badge.classList.toggle('opened', opened);
  badge.title = opened ? t('lblWebCardPileGather') : count > 1 ? t('lblWebCardPileSpread') : '';
}
