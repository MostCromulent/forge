// The player's hand along the bottom of the board, fanned and sorted as the options say.

import { commandKind } from './command';
import { reconcile } from './render';
import { createCard, frameColour, updateCard, type CardClick } from './cards';
import { deref, derefAll, game, isLocal, players, stateOf, zone, type Model } from '../model';
import { isPortrait } from '../form';
import { sheet, swipeDown } from '../sheet';
import { changeUi, ui } from '../ui';
import { logTints } from './log';
import { setting } from '../settings';
import { byId, q } from '../dom';
import type { CardView, PlayerView, StackItemView, ZoneType } from '../protocol';
import { t } from '../text';

// {2}{W} counts as three; a hybrid shard counts as one and X as nothing
export function manaValue(cost: string | undefined): number {
  return [...String(cost ?? '').matchAll(/\{([^}]+)\}/g)].reduce((sum, [, shard]) => {
    const digits = shard.split('/').find(part => /^\d+$/.test(part));
    return sum + (digits !== undefined ? Number(digits) : /^[xyz]$/i.test(shard) ? 0 : 1);
  }, 0);
}

// As Arena sorts by colour: each single colour in WUBRG order, then multicoloured, colourless, and lands last
function colourRank(colours: number, type: string): number {
  return /Land/.test(type) ? 7 : 'WUBRGMC'.indexOf(frameColour(colours, type));
}

function handOrder(model: Model, cards: CardView[]): CardView[] {
  const sort = setting('handSort');
  if (sort !== 'mana' && sort !== 'color') {
    return cards;
  }
  return [...cards].sort((a, b) => {
    const sa = stateOf(model, a);
    const sb = stateOf(model, b);
    return (sort === 'color' ? colourRank(sa.Colors ?? 0, sa.Type ?? '') - colourRank(sb.Colors ?? 0, sb.Type ?? '') : 0)
      || manaValue(sa.ManaCost) - manaValue(sb.ManaCost)
      || String(sa.Name ?? '').localeCompare(sb.Name ?? '');
  });
}

/** Cards playable from outside the hand, which the engine gathers in its Flashback pseudo-zone, each with the zone it is really in. */
function fromElsewhere(model: Model, player: PlayerView | undefined): Map<number, ZoneType> {
  const found = new Map<number, ZoneType>();
  for (const card of zone(model, player, 'Flashback')) {
    if (card.Zone) {
      found.set(card.$key, card.Zone);
    }
  }
  return found;
}

/** The question open when the drawer was opened, so only a new one closes it. */
let askedWhenOpen: object | null = null;
/** The card last tapped in the drawer, which is opened again if that card's play is called off. */
let played: number | null = null;
/** The question the drawer opened itself for. */
let openedFor: object | null = null;
const NO_PROMPT = {};

const closeDrawer = () => {
  played = null;
  changeUi(u => { u.handOpen = false; });
};

/** On a phone the hand is a strip of card tops, and opens into a drawer to be read and played from. */
function drawerHead(root: HTMLElement): HTMLElement {
  let head = document.getElementById('hand-head');
  if (!head) {
    head = Object.assign(document.createElement('div'), { id: 'hand-head' });
    head.innerHTML = '<b></b><span class="on-stack"></span><button class="close"></button>';
    q(head, '.close').textContent = t('lblClose');
    q(head, '.close').onclick = closeDrawer;
    root.before(head);
    swipeDown(head, closeDrawer);
    root.addEventListener('click', e => {
      if (!isPortrait()) return;
      if (ui.handOpen) {
        played = Number((e.target as Element).closest<HTMLElement>('.card')?.dataset.key ?? NaN);
        return;
      }
      // The strip is too small to pick a card from, so a tap anywhere on it opens the drawer
      e.stopPropagation();
      played = null;
      changeUi(u => { u.handOpen = true; });
    }, true);
  }
  return head;
}

/** Whether the hand shows as the open drawer this frame, having closed it for a new question or opened it again for one called off. */
function drawDrawer(root: HTMLElement, model: Model, held: CardView[], count: number): boolean {
  const head = drawerHead(root);
  const portrait = isPortrait();
  const asked = model.prompt;
  // A question answered with cards from the hand, a discard say, needs them at a size that can be told apart and tapped
  const fromHand = portrait && !!asked && !asked.priority && !asked.paying && asked.selectableMin > 0
    && asked.selectable.some(r => held.some(c => c.$key === r.ref));
  if (fromHand) {
    if (asked !== openedFor) {
      openedFor = asked;
      ui.handOpen = true;
      askedWhenOpen = asked;
      played = null;
    }
  } else if (openedFor) {
    // Opened for a question, it goes with the question
    openedFor = null;
    ui.handOpen = false;
  } else if (ui.handOpen && (model.requests.size > 0 || (askedWhenOpen && asked !== askedWhenOpen && asked && !asked.priority))) {
    // What was tapped asks its next question on the board, in a menu or in a dialog, so the drawer gets out of the way
    ui.handOpen = false;
  } else if (!ui.handOpen && played !== null && asked?.priority) {
    ui.handOpen = portrait && held.some(c => c.$key === played);
    played = null;
  }
  askedWhenOpen = ui.handOpen ? askedWhenOpen ?? asked ?? NO_PROMPT : null;
  const open = portrait && ui.handOpen;
  root.classList.toggle('sheet', open);
  root.classList.toggle('strip', portrait && !open);
  head.hidden = !open;
  sheet('hand', open, closeDrawer);
  if (portrait) root.setAttribute('aria-label', t('lblWebPortraitHandTitle', count));
  else root.removeAttribute('aria-label');
  // The drawer scrolls, and the strip must not keep where it was scrolled to
  if (!open) {
    root.scrollTop = 0;
    return false;
  }
  q(head, 'b').textContent = `${t('lblWebPortraitHand')} ${count}`;
  // What is about to resolve is said here, since the drawer covers the stack
  const top = (derefAll(model, game(model)?.Stack) as StackItemView[])[0];
  const source = top ? deref(model, top.SourceCard) as CardView | undefined : undefined;
  q(head, '.on-stack').textContent = top ? t('lblWebPortraitStackTop', source ? stateOf(model, source).Name ?? '' : '', deref(model, top.ActivatingPlayer)?.Name ?? '') : '';
  head.style.bottom = `${innerHeight - root.getBoundingClientRect().top - head.offsetHeight}px`;
  return true;
}

export function renderHand(model: Model, player: PlayerView | undefined, select: CardClick): void {
  const root = byId('hand');
  const elsewhere = fromElsewhere(model, player);
  const tints = logTints(players(model).map(p => ({ name: p.Name ?? '', local: isLocal(model, p) })));
  // The planar die has a button of its own by the plane, so it is not laid out with the cards
  const others = zone(model, player, 'Flashback').filter(c => commandKind(c, stateOf(model, c)) !== 'dice');
  // The hand's list can lag its cards: one being cast is already on the stack, and hidden, while the list still holds it
  const held = zone(model, player, 'Hand').filter(c => !c.Zone || c.Zone === 'Hand');
  const cards = [...handOrder(model, others), ...handOrder(model, held)];
  reconcile(root, cards, c => c.$key, () => createCard(select), (el, c) => {
    updateCard(el, model, c);
    const source = elsewhere.get(c.$key);
    el.classList.toggle('elsewhere', source !== undefined);
    // The zone picks the glow's colour in CSS, and names itself in the detail panel when the card is hovered
    if (source) {
      el.dataset.from = source;
    } else {
      delete el.dataset.from;
    }
    // Another player's card you may play says whose it is, so it is not taken for one of your own
    const owner = deref(model, c.Owner);
    const foreign = !!owner && !!player && owner.$key !== player.$key;
    el.classList.toggle('foreign', foreign);
    q(el, '.owned-by').textContent = foreign ? t('lblWebHandOwnedBy', owner?.Name ?? '') : '';
    if (foreign) el.style.setProperty('--owner-tint', tints.find(t => t.name === owner?.Name)?.colour ?? 'var(--muted)');
    // A card another player may look at has been revealed to them
    el.classList.toggle('revealed', (c.PlayerMayLook ?? []).some(r => !!r && !model.localPlayers.includes(r.ref)));
  });
  if (drawDrawer(root, model, held, cards.length)) {
    return;
  }
  const cardWidth = (root.firstElementChild as HTMLElement | null)?.offsetWidth ?? 0;
  // The room the hand keeps clear for the prompt beside it is not room for cards
  const style = getComputedStyle(root);
  const width = root.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
  // Each card covers a fifth of the one before it, and more when the row would overflow
  const step = cards.length > 1 ? Math.min(-0.2 * cardWidth, (width - 16 - cards.length * cardWidth) / (cards.length - 1)) : 0;
  root.style.setProperty('--step', `${step}px`);
  // At most 3 degrees per card from the middle, 12 at the ends
  const mid = (cards.length - 1) / 2;
  const perCard = mid > 0 ? Math.min(3, 12 / mid) : 0;
  // Each card drops as far as its tilt would carry it if the whole hand turned about one point below the screen
  const radius = perCard > 0 ? (cardWidth + step) / Math.sin(perCard * Math.PI / 180) : 0;
  [...root.children].forEach((child, i) => {
    const tilt = (i - mid) * perCard;
    const el = child as HTMLElement;
    el.style.setProperty('--tilt', `${tilt}deg`);
    el.style.setProperty('--drop', `${radius * (1 - Math.cos(tilt * Math.PI / 180))}px`);
  });
}
