// Lays out the whole match screen and redraws it from the model, leaving each piece to its own module

import { reconcile } from './reconcile';
import { cardImageSrc, hideOnError, noImageOnError, setImage, symbolUrl } from '../images';
import { commanderTax, game, me, players, zone, deref, stackPick, stateOf, isLocal, type Model } from '../model';
import { renderHand, resetHand } from './hand';
import { renderZones, togglePile, zoneTitle } from './zones';
import { renderBattlefield } from './battlefield';
import { followPointer, hoverPlayer, hoverable, inspectCard, inspectPlayer } from '../preview';
import { foldStack, renderStack, resetStack } from './stack';
import { renderPlanes } from './planes';
import { isArchenemy, renderOngoing, resetSchemes, revealSchemes } from './schemes';
import { renderPhaseBar, stopWaiting } from './phasebar';
import { forgetPictures } from './cards';
import { artUrl } from '../sleeves';
import { playerAvatarUrl, playerSleeveUrl, cssUrl, ROBOT_ICON } from '../looks';
import { animateCardMoves, noteBoard, resetMotion, showCount } from './motion';
import { boardBehind } from './pace';
import { byId, q, replay, make, onPressOutside, relayout } from '../dom';
import { setting } from '../settings';
import { logTints } from './log';
import type { CardClick } from './cards';
import type { Actions } from '../actions';
import type { CardStateView, CardView, GameEvent, PlayerView, ZoneType } from '../protocol';
import { avatarModifiers, commandKind, type CommandKind } from './command';
import { notePick, resetOverlay } from './overlay';
import { resetStarter, showStarter, wentFirst } from './starter';
import { announceTurn, resetTurnBanner } from './turnbanner';
import { noticeLosses, renderGameOver, resetGameOver } from './gameover';
import { hovers, isTouch, longPress } from '../press';
import { fieldChanged, repeatTap, seatToOpen, tapInspects } from './portrait';
import { isPortrait } from '../form';
import { sheet, swipeDown } from '../sheet';
import { changeUi, ui } from '../ui';
import { t, type TextKey } from '../text';
import { ICONS, iconSvg } from '../icons';
import { COLOUR_BITS, COMMANDER_LETHAL, COMMANDER_WARNING } from './rules';

// The Mana property counts the pool by Forge's mana bit (ManaAtom): the five colours as MagicColor has them, and colourless its own bit
const MANA: [number, string][] = [...COLOUR_BITS, [32, 'C']];

const SWORDS = iconSvg(ICONS.swords);

export function renderMatch(model: Model, actions: Actions, events: readonly GameEvent[]): void {
  const g = game(model);
  if (!g) return;
  // Before anything is redrawn, so each card's move is measured from where it stood on screen
  noteBoard();
  showStarter(model, actions, events);
  noticeLosses(model, actions);
  // A prompt offering cards or players to pick, or a spell picked on the stack, dims everything else (board.css); paying a cost is not such a pick
  const p = model.prompt;
  byId('match').classList.toggle('picking', !!stackPick(model) || (!!p && !p.paying
    && (p.selectable.length > 0 || p.selectablePlayers.length > 0 || p.selectableStack.length > 0)));
  // The click position travels with the click, so an ability list opens on the card as desktop's menu does
  const select: CardClick = (el, menu, e) => {
    const key = Number(el.dataset.key);
    if (isTouch(e) && !menu) {
      if (repeatTap(key, performance.now())) return;
      // Read as the tap lands: a card keeps the handler it was made with, long after the prompt it was made under
      const card = model.objects.get(key) as CardView | undefined;
      const tapped = { playable: el.classList.contains('playable'), selectable: el.classList.contains('selectable'),
        mine: card?.Controller?.ref === me(model)?.$key, attacking: !!card?.Attacking };
      if (tapInspects(tapped, byId('match').classList.contains('picking'), game(model)?.Phase)) {
        inspectCard(el);
        return;
      }
      // Shown at once, since the server's answer can be a moment away. Not while newer boards wait, as the click is not sent then
      if (!boardBehind()) replay(el, 'pressed');
    }
    notePick(key);
    foldStack();
    actions.selectCard(key, menu, e?.clientX ?? 0, e?.clientY ?? 0);
  };
  // Attachments can cross players (an aura on an opponent's creature), so slots are built from every battlefield
  const onField = players(model).flatMap(p => zone(model, p, 'Battlefield'));
  renderStack(model, events);
  renderOpponents(byId('opponent'), model, onField, actions, select);
  renderSeat(byId('me'), model, me(model), onField, actions, select);
  renderOut(model, actions);
  announceTurn(model, g);
  renderPhaseBar(model, g, actions);
  renderPlanes(model, actions);
  revealSchemes(model);
  renderHand(model, me(model), select);
  renderZones(model, actions, select);
  renderGameOver(model, g, actions);
  animateCardMoves(model, events);
}

/** Seat order, taken once when the game is first drawn so a card that reverses the turn order does not move anyone. */
let seating: number[] | null = null;

function seated(model: Model): PlayerView[] {
  const everyone = players(model);
  if (!seating) {
    const mine = everyone.findIndex(p => isLocal(model, p));
    const from = Math.max(0, mine);
    seating = [...everyone.slice(from), ...everyone.slice(0, from)].map(p => p.$key);
  }
  for (const p of everyone) {
    if (!seating.includes(p.$key)) seating.push(p.$key);
  }
  const byKey = new Map(everyone.map(p => [p.$key, p]));
  return seating.map(k => byKey.get(k)).filter((p): p is PlayerView => !!p && !isLocal(model, p));
}

/** One opponent takes the top half as a full seat. Two or three share it in columns or quarters, unless the player asks for one at a time behind tabs, which four or more and a phone always get. */
function renderOpponents(root: HTMLElement, model: Model, onField: CardView[], actions: Actions, select: CardClick): void {
  const across = seated(model);
  const many = across.length > 1;
  const tabbed = many && (isPortrait() || across.length > 3 || setting('boardLayout') === 'tabs');
  const quads = many && !tabbed && setting('boardLayout') === 'quadrants';
  const match = byId('match');
  match.classList.toggle('many', many);
  match.classList.toggle('quads', quads);
  match.dataset.opponents = String(across.length);
  if (root.classList.contains('many') !== many) {
    root.replaceChildren();
    delete root.dataset.player;
    root.classList.toggle('many', many);
    root.classList.toggle('seat', !many);
  }
  if (!many) {
    renderSeat(root, model, across[0], onField, actions, select);
    renderSeatTabs(root, model, [], []);
    return;
  }
  // Each opponent's seat is in the colour their name has in the log
  const tints = logTints(players(model).map(p => ({ name: p.Name ?? '', local: isLocal(model, p) })));
  reconcile(root, across, p => p.$key,
    () => {
      const el = make('section', 'seat compact');
      return el;
    },
    (el, p) => {
      // Alone on a desktop's top half, a seat is laid out as a two-player game's is, with its cards at full size
      el.classList.toggle('compact', !tabbed || isPortrait());
      el.style.setProperty('--tint', tints.find(t => t.name === p.Name)?.colour ?? 'var(--muted)');
      renderSeat(el, model, p, onField, actions, select);
    });
  renderSeatTabs(root, model, tabbed ? across : [], tints);
}

/** The seat last shown, so a change is measured again. */
let shownSeat: number | null = null;

/** One opponent's seat shows at a time, and each of the others has a tab that says whose turn it is and whether their table changed out of sight. A phone lists the tabs in a sheet that its players button opens. */
function renderSeatTabs(root: HTMLElement, model: Model, across: PlayerView[], tints: { name: string; colour: string }[]): void {
  const match = byId('match');
  let tabs = document.getElementById('seat-tabs');
  let button = document.getElementById('seat-switch');
  if (!tabs || !button) {
    tabs = Object.assign(document.createElement('div'), { id: 'seat-tabs' });
    tabs.setAttribute('role', 'tablist');
    tabs.setAttribute('aria-label', t('lblWebSetupOpponents'));
    swipeDown(tabs, () => changeUi(u => { u.seatsOpen = false; }));
    root.before(tabs);
    button = Object.assign(document.createElement('button'), { id: 'seat-switch', title: t('lblWebSetupOpponents'), innerHTML: PLAYERS_ICON });
    button.onclick = () => changeUi(u => { u.seatsOpen = !u.seatsOpen; });
    match.append(button);
  }
  const tabbed = across.length >= 2;
  const listed = tabbed && isPortrait();
  tabs.hidden = !tabbed || (listed && !ui.seatsOpen);
  tabs.classList.toggle('sheet', listed);
  button.hidden = !listed;
  sheet('seats', listed && ui.seatsOpen, () => changeUi(u => { u.seatsOpen = false; }));
  root.classList.toggle('tabbed', tabbed);
  match.classList.toggle('tabbed', tabbed);
  if (!tabbed) {
    if (shownSeat !== null) {
      for (const seat of root.querySelectorAll<HTMLElement>(':scope > .seat')) seat.hidden = false;
      shownSeat = null;
    }
    return;
  }
  const g = game(model);
  const p = model.prompt;
  const seatOf = (key: number) => Number(document.querySelector<HTMLElement>(`#opponent .card[data-key="${key}"]`)?.closest<HTMLElement>('.seat')?.dataset.player);
  const asked = new Set([...(p?.selectable ?? []).map(r => seatOf(r.ref)), ...(p?.highlighted ?? []).map(seatOf), ...(p?.selectablePlayers ?? []).map(r => r.ref)]
    .filter(k => across.some(o => o.$key === k)));
  const open = seatToOpen({ open: ui.openSeat, seats: across.map(o => o.$key), active: g?.PlayerTurn?.ref ?? null, turn: g?.Turn ?? 0, chosenTurn: ui.seatChosenTurn });
  ui.openSeat = open;
  const tintOf = (o: PlayerView) => tints.find(x => x.name === o.Name)?.colour ?? 'var(--muted)';
  match.style.setProperty('--open-tint', tintOf(across.find(o => o.$key === open) ?? across[0]));
  let changedOutOfSight = false;
  reconcile<PlayerView, HTMLButtonElement>(tabs, across, o => o.$key,
    () => {
      const el = make('button', 'seat-tab');
      el.setAttribute('role', 'tab');
      el.innerHTML = `<span class="pic"><img alt="" draggable="false"><i class="dot" aria-hidden="true"></i><span class="skull-mark">${SKULL}</span></span><span class="who"></span><span class="note"></span><b class="life"></b>`;
      // A tab only shows a seat; the player is chosen on the portrait inside it
      el.onclick = () => changeUi(u => {
        u.openSeat = Number(el.dataset.key);
        u.seatChosenTurn = Number(el.dataset.turn);
        u.seatsOpen = false;
      });
      return el;
    },
    (el, o) => {
      const shown = o.$key === open;
      const field = zone(model, o, 'Battlefield').map(c => c.$key);
      if (shown) ui.seenOnField.set(o.$key, new Set(field));
      const changed = !shown && fieldChanged(ui.seenOnField.get(o.$key), field);
      changedOutOfSight ||= changed;
      const turn = g?.PlayerTurn?.ref === o.$key;
      el.dataset.turn = String(g?.Turn ?? 0);
      el.title = o.Name ?? '';
      el.classList.toggle('on', shown);
      el.setAttribute('aria-selected', String(shown));
      el.classList.toggle('turn', turn);
      el.classList.toggle('out', !!o.HasLost);
      el.classList.toggle('changed', changed);
      el.classList.toggle('asked', !shown && asked.has(o.$key));
      el.style.setProperty('--tint', tintOf(o));
      setImage(q<HTMLImageElement>(el, 'img'), playerAvatarUrl(o));
      q(el, '.life').textContent = String(o.Life ?? 0);
      q(el, '.who').textContent = o.Name ?? '';
      q(el, '.note').textContent = o.HasLost ? t('lblWebBoardOutOfGame') : turn ? t('lblWebSeatTheirTurn') : '';
    });
  // The players button stands for every seat out of sight
  button.classList.toggle('changed', changedOutOfSight);
  button.classList.toggle('asked', [...asked].some(k => k !== open));
  for (const seat of root.querySelectorAll<HTMLElement>(':scope > .seat')) seat.hidden = Number(seat.dataset.player) !== open;
  if (shownSeat !== open) {
    // The seat comes in from the side its tab is on, so a change reads as a step along the row
    const order = across.map(o => o.$key);
    const seat = root.querySelector<HTMLElement>(`:scope > .seat[data-player="${open}"]`);
    if (seat && shownSeat !== null) {
      const later = order.indexOf(open as number) > order.indexOf(shownSeat);
      replay(seat, later ? 'from-right' : 'from-left', 'from-right', 'from-left');
      // The classes also hold the card size still, so they go once the seat is in
      seat.addEventListener('animationend', () => seat.classList.remove('from-right', 'from-left'), { once: true });
    }
    shownSeat = open;
    // A seat that was out of sight had no size to fit its cards to
    relayout();
  }
}

const PLAYERS_ICON = `${iconSvg(ICONS.users)}<i class="dot" aria-hidden="true"></i>`;

/** Out of a game that goes on: said once, over the board, with a way to leave when nobody else is waiting on you. */
let outSaid = false;

function renderOut(model: Model, actions: Actions): void {
  const mine = me(model);
  const out = !!mine?.HasLost && !model.gameOver;
  let banner = document.getElementById('out-banner');
  if (!out) {
    banner?.remove();
    if (!mine?.HasLost) outSaid = false;
    return;
  }
  if (banner || outSaid) return;
  outSaid = true;
  banner = document.createElement('div');
  banner.id = 'out-banner';
  banner.setAttribute('role', 'status');
  banner.innerHTML = `<span class="skull">${SKULL}</span><div><b></b><p></p></div>`
    + '<button class="watch"></button>' + (model.networked ? '' : '<button class="leave primary"></button>');
  q(banner, 'b').textContent = t('lblWebBoardYoureOut');
  q(banner, 'p').textContent = t('lblWebBoardGameGoesOn');
  q(banner, '.watch').textContent = t('lblWebBoardKeepWatching');
  q(banner, '.watch').onclick = () => banner?.remove();
  const leave = banner.querySelector<HTMLButtonElement>('.leave');
  if (leave) {
    leave.textContent = t('lblWebBoardLeaveMatch');
    leave.onclick = () => {
      actions.quitMatch();
      actions.leave();
    };
  }
  byId('match').append(banner);
}

const SKULL = iconSvg(ICONS.skull);


function renderSeat(root: HTMLElement, model: Model, player: PlayerView | undefined, onField: CardView[], actions: Actions, select: CardClick): void {
  if (!player) {
    root.replaceChildren();
    return;
  }
  if (!root.firstChild) {
    root.innerHTML = `
      <div class="player">
        <div class="player-id">
        <div class="avatar"><img class="portrait" alt="" draggable="false"><span class="initial"></span><span class="skull-mark">${SKULL}</span><span class="ai-badge">${ROBOT_ICON}</span><span class="cmdr-arc" hidden><svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="48"/></svg></span><span class="cmdr-chip" hidden>${SWORDS}<b></b></span><span class="life"></span></div>
        <div class="name"><span class="who"></span><span class="role-tag" hidden></span></div>
        <button class="hand-fan" hidden><span class="backs"><i></i><i></i><i></i></span><span class="hand-count"></span></button>
        </div>
        <button class="zones-pill"></button>
        <div class="zone-tiles"></div>
        <div class="player-extra">
        <div class="player-counters"></div>
        <div class="emblems"></div>
        <div class="schemes-ongoing"></div>
        <div class="mana" hidden><span class="mana-label"></span><div class="mana-chips"></div></div>
        </div>
      </div>
      <div class="battlefield">
        <div class="row paired"><div class="group lands"></div><div class="group support"></div></div>
        <div class="row together"><div class="group creatures"></div><div class="group far"></div></div>
      </div>`;
    q(root, '.skull-mark').title = t('lblWebBoardOutOfGame');
    q(root, '.ai-badge').title = t('lblWebBoardComputerPlayer');
    q(root, '.role-tag').textContent = t('lblArchenemy');
    q(root, '.mana-label').textContent = t('lblWebBoardFloatingMana');
    const avatarEl = q(root, '.avatar');
    // Said by the mark board.css draws on the portrait of whoever goes first
    avatarEl.dataset.firstLabel = t('lblWebBoardFirst');
    avatarEl.onclick = () => {
      notePick(Number(root.dataset.player));
      actions.selectPlayer(Number(root.dataset.player));
    };
    avatarEl.addEventListener('pointerenter', e => {
      if (!hovers(e)) return;
      followPointer(e);
      hoverPlayer(Number(root.dataset.player));
    });
    avatarEl.addEventListener('pointermove', e => { if (hovers(e)) followPointer(e); });
    avatarEl.addEventListener('pointerleave', e => { if (hovers(e)) hoverPlayer(null); });
    avatarEl.addEventListener('pointerdown', e => longPress(e, () => inspectPlayer(Number(root.dataset.player))));
    q(root, '.hand-fan').onclick = () => togglePile(Number(root.dataset.player), 'Hand');
    const pill = q(root, '.zones-pill');
    pill.innerHTML = PILL_ZONES.map(([zoneName, icon]) => `<span data-zone="${zoneName}"><svg viewBox="0 0 24 24" aria-hidden="true">${icon}</svg><b></b></span>`).join('');
    pill.onclick = () => changeUi(u => { u.zonesFor = u.zonesFor === Number(root.dataset.player) ? null : Number(root.dataset.player); });
    // Your line sits beside your hand, so the hand starts where the line ends, however wide its zones make it
    if (root.id === 'me') {
      new ResizeObserver(() => byId('match').style.setProperty('--line-w', `${q(root, '.player').offsetWidth + 32}px`))
        .observe(q(root, '.player'));
    }
  }
  root.dataset.player = String(player.$key);
  const avatar = q(root, '.avatar');
  q(root, '.initial').textContent = (player.Name ?? '?').slice(0, 1).toUpperCase();
  const portrait = q<HTMLImageElement>(root, '.portrait');
  if (setImage(portrait, playerAvatarUrl(player))) {
    portrait.hidden = true;
    portrait.onload = () => { portrait.hidden = false; };
  }
  avatar.classList.toggle('ai', !!player.IsAI);
  root.style.setProperty('--sleeve', cssUrl(playerSleeveUrl(player)));
  showLife(q(root, '.life'), avatar, player.Life ?? 0, isLocal(model, player));
  q(root, '.name .who').textContent = player.Name ?? '';
  q(root, '.name').title = player.Name ?? '';
  // Out of the game: the seat stays where it was, greyed, with a skull by the name
  root.classList.toggle('out', !!player.HasLost);
  root.classList.toggle('turn', game(model)?.PlayerTurn?.ref === player.$key);
  avatar.classList.toggle('highlighted', (model.prompt?.highlighted ?? []).includes(player.$key));
  avatar.classList.toggle('selectable', (model.prompt?.selectablePlayers ?? []).some(r => r.ref === player.$key));
  avatar.classList.toggle('active', game(model)?.PlayerTurn?.ref === player.$key);
  // Who went first stays marked until their first turn is over, for anyone who missed the reveal
  avatar.classList.toggle('first', wentFirst(player.$key) && (game(model)?.Turn ?? 0) <= 1);
  // A player who has lost leaves an empty socket: their portrait broke, or breaks now, out of it
  avatar.classList.toggle('lost', !!player.HasLost);
  renderHandFan(q(root, '.hand-fan'), model, player);
  renderZoneTiles(q(root, '.zone-tiles'), model, player, select);
  renderZonesPill(root, model, player);
  renderManaPool(q(root, '.mana'), player, isLocal(model, player), actions);
  showCommanderDamage(avatar, model, player);
  reconcile(q(root, '.player-counters'), Object.entries(player.Counters ?? {}), ([name]) => name,
    () => make('span', 'player-counter'),
    (el, [name, n]) => { el.textContent = `${name.toLowerCase()} ${n}`; });
  renderEmblems(q(root, '.emblems'), model, player, zone(model, player, 'Command'), select);
  q(root, '.role-tag').hidden = !isArchenemy(model, player);
  renderOngoing(q(root, '.schemes-ongoing'), model, player);
  renderBattlefield(root, model, zone(model, player, 'Battlefield'), onField, select);
}

/** The arc and chip on the portrait show the most damage any one commander has dealt this player, not the total. */
function showCommanderDamage(avatar: HTMLElement, model: Model, player: PlayerView): void {
  const hits = (player.CommanderDamage ?? []).filter(h => h.value > 0);
  const top = hits.reduce((best, h) => (h.value > best.value ? h : best), { card: { ref: -1 }, value: 0 });
  const arc = q(avatar, '.cmdr-arc');
  const chip = q(avatar, '.cmdr-chip');
  arc.hidden = chip.hidden = top.value === 0;
  if (top.value === 0) return;
  const near = top.value >= COMMANDER_WARNING;
  arc.classList.toggle('near', near);
  chip.classList.toggle('near', near);
  const circle = q(arc, 'circle');
  const around = 2 * Math.PI * 48;
  circle.setAttribute('stroke-dasharray', `${around * Math.min(top.value, COMMANDER_LETHAL) / COMMANDER_LETHAL} ${around}`);
  q(chip, 'b').textContent = String(top.value);
  const commander = deref(model, top.card);
  const name = (commander ? stateOf(model, commander).Name : undefined) ?? t('lblWebBoardACommander');
  chip.title = t('lblWebBoardCommanderDamageFrom', top.value, COMMANDER_LETHAL, name);
}

// Mana in the pool is lost when the step ends, so it is shown apart from everything that stays
function renderManaPool(root: HTMLElement, player: PlayerView, own: boolean, actions: Actions): void {
  const pool = MANA.filter(([bit]) => player.Mana?.[bit]);
  root.hidden = pool.length === 0;
  reconcile<[number, string], HTMLButtonElement>(q(root, '.mana-chips'), pool, ([bit]) => bit,
    ([bit, sym]) => {
      const el = make('button', 'mana-chip');
      el.innerHTML = '<img class="sym" alt="" draggable="false"><span class="amount"></span>';
      const img = q<HTMLImageElement>(el, 'img');
      img.src = symbolUrl(sym);
      img.alt = sym;
      el.onclick = () => actions.useMana(bit);
      return el;
    },
    (el, [bit, sym]) => {
      const amount = player.Mana?.[bit] ?? 0;
      q(el, '.amount').textContent = String(amount);
      el.disabled = !own;
      const name = t(MANA_NAMES[sym]);
      el.title = own ? t('lblWebBoardManaFloatingOwn', amount, name) : t('lblWebBoardManaFloating', amount, name);
    });
}

const MANA_NAMES: Record<string, TextKey> = {
  W: 'lblWhite', U: 'lblBlue', B: 'lblBlack', R: 'lblRed',
  G: 'lblGreen', C: 'lblColorless',
};

// A hand is drawn as a few fanned backs with its count, and opens in a window when clicked, your own included
function renderHandFan(el: HTMLElement, model: Model, player: PlayerView): void {
  const count = zone(model, player, 'Hand').length;
  el.hidden = false;
  el.dataset.count = String(Math.min(count, 3));
  q(el, '.hand-count').textContent = String(count);
  // Your own hand is laid out along the bottom too, but a big one reads more easily laid out in a window
  el.title = isLocal(model, player)
    ? t(count === 1 ? 'lblWebBoardOwnHandOneCard' : 'lblWebBoardOwnHandCards', count)
    : t(count === 1 ? 'lblWebBoardHandOneCard' : 'lblWebBoardHandCards', count);
}

// Cards drift down into a graveyard and circle in exile, so the two piles read as places at a glance
const AMBIENT = '<span class="ambient" aria-hidden="true">' + '<i></i>'.repeat(6) + '</span>';

/** Decks some variants and cards bring, shown only while they hold something. Only the junkyard is face up. */
const EXTRA_ZONES: [ZoneType, TextKey][] = [['PlanarDeck', 'lblPlanes'], ['SchemeDeck', 'lblSchemes'],
  ['AttractionDeck', 'lblAttractions'], ['ContraptionDeck', 'lblContraptions'], ['Junkyard', 'lblWebZoneJunkyard'],
  ['Sideboard', 'lblSideboard'], ['Ante', 'lblWebZoneAnte'], ['StickerSheets', 'lblStickerSheets']];
const FACE_DOWN_DECKS: ReadonlySet<ZoneType> = new Set(['PlanarDeck', 'SchemeDeck', 'AttractionDeck', 'ContraptionDeck']);

/** The card types rule 205.2a names, in its order. Delirium and the like count how many a graveyard holds. */
const CORE_TYPES = ['Artifact', 'Battle', 'Creature', 'Enchantment', 'Instant', 'Kindred', 'Land', 'Planeswalker', 'Sorcery'];

/** The card types among these type lines, in the rules' order, as desktop's graveyard label counts them. */
function cardTypes(typeLines: readonly string[]): string[] {
  const found = new Set<string>();
  for (const line of typeLines) {
    // Only the words before the dash are types; subtypes such as "Forest" follow it
    for (const word of line.split(/\s[-—]\s/)[0].split(/\s+/)) {
      if (CORE_TYPES.includes(word)) found.add(word);
    }
  }
  return CORE_TYPES.filter(t => found.has(t));
}

/** A graveyard tile's tooltip: how many cards, and how many card types among them. */
function graveyardTitle(count: number, types: string[]): string {
  const cards = t(count === 1 ? 'lblWebOneCard' : 'lblWebNCards', count);
  if (!types.length) return t('lblWebBoardGraveyardTitle', cards);
  const kinds = t(types.length === 1 ? 'lblWebBoardOneCardType' : 'lblWebBoardCardTypes', types.length);
  return t('lblWebBoardGraveyardTitleTypes', cards, kinds, types.join(', '));
}

// Drawn on the same 24-unit grid as the other icons: a fan of cards for the hand, a deck, a headstone, and a ring for exile
const PILL_ZONES: [ZoneType, string][] = [
  ['Hand', '<rect x="9" y="6" width="9" height="13" rx="1.5" transform="rotate(14 13.5 12.5)"/><rect x="5.5" y="6" width="9" height="13" rx="1.5" transform="rotate(-10 10 12.5)"/>'],
  ['Library', '<rect x="5" y="7" width="11" height="14" rx="1.6"/><path d="M8.5 4h8.3a1.7 1.7 0 0 1 1.7 1.7V17"/>'],
  ['Graveyard', '<path d="M7 21V10a5 5 0 0 1 10 0v11"/><path d="M4.5 21h15M12 9.5v5M10 11.5h4"/>'],
  ['Exile', '<circle cx="12" cy="12" r="7.5"/><circle cx="12" cy="12" r="2.2"/>'],
];
let pillWired = false;

/** A phone has no room to keep each zone's tile on show, so a player's bar carries the counts, and a tap opens the tiles over the board. */
function renderZonesPill(root: HTMLElement, model: Model, player: PlayerView): void {
  if (!pillWired) {
    pillWired = true;
    // Put away by a tap anywhere else, and by the tap that chooses a tile, which a click lets reach the tile first
    onPressOutside('.zones-pill', () => { if (ui.zonesFor !== null) changeUi(u => { u.zonesFor = null; }); }, 'click');
  }
  const pill = q(root, '.zones-pill');
  for (const span of pill.querySelectorAll<HTMLElement>('span')) {
    q(span, 'b').textContent = String(zone(model, player, span.dataset.zone as ZoneType).length);
  }
  // Your own hand is on show along the bottom, so only another player's is counted here
  q(pill, '[data-zone="Hand"]').hidden = isLocal(model, player);
  const tiles = q(root, '.zone-tiles');
  // Lit when a zone behind it wants the player: a commander to cast, or a pile that holds more zones than the pill names
  pill.classList.toggle('lit', !!tiles.querySelector('.zone-tile.selectable, .zone-tile[data-cast="yes"]'));
  pill.classList.toggle('more', tiles.childElementCount > PILL_ZONES.length - 1);
  const open = isPortrait() && ui.zonesFor === player.$key;
  root.classList.toggle('zones-open', open);
  pill.setAttribute('aria-expanded', String(open));
  pill.setAttribute('aria-label', PILL_ZONES.filter(([z]) => z !== 'Hand' || !isLocal(model, player)).map(([z]) => `${zoneTitle(z)} ${zone(model, player, z).length}`).join(', '));
}

function renderZoneTiles(root: HTMLElement, model: Model, player: PlayerView, select: CardClick): void {
  // A player with a commander has a Command tile for the whole game, empty while the commander is elsewhere
  const commanded = (player.Commander ?? []).some(r => r);
  const zones: ZoneType[] = [...(commanded ? ['Command' as ZoneType] : []), 'Library', 'Graveyard', 'Exile',
    ...EXTRA_ZONES.map(([z]) => z).filter(z => zone(model, player, z).length > 0)];
  reconcile<ZoneType, HTMLButtonElement>(root, zones, z => z,
    zoneName => {
      const el = make('button', 'zone-tile');
      el.dataset.zone = zoneName;
      el.innerHTML = '<img alt="" draggable="false">' + (zoneName === 'Library' ? '' : AMBIENT)
        + '<span class="zone-name"></span><span class="zone-count"></span>';
      const extra = EXTRA_ZONES.find(([z]) => z === zoneName)?.[1];
      q(el, '.zone-name').textContent = extra ? t(extra) : zoneTitle(zoneName);
      // A planar, scheme, attraction or contraption deck is face down, so only its count is shown
      el.classList.toggle('hidden-deck', FACE_DOWN_DECKS.has(zoneName));
      const img = q<HTMLImageElement>(el, 'img');
      hideOnError(img);
      el.onclick = () => {
        // One commander here is cast straight from its tile; partners open the zone to choose which
        if (zoneName === 'Command' && el.dataset.cast) select(q(el, 'img'), false);
        else if (!el.classList.contains('hidden-deck')) togglePile(Number(el.closest<HTMLElement>('.seat')?.dataset.player), zoneName);
      };
      // The hover data sits on the image: the tile's own data-key is how the render finds it again
      hoverable(el, img);
      return el;
    },
    (el, zoneName) => {
      if (zoneName === 'Command') {
        updateCommandTile(el, model, player);
        return;
      }
      const cards = zone(model, player, zoneName);
      // New cards go to the end of the graveyard and exile lists, so the last is on top
      const top = zoneName === 'Graveyard' || zoneName === 'Exile' ? cards[cards.length - 1] : undefined;
      const src = cardImageSrc(model, top);
      const img = q<HTMLImageElement>(el, 'img');
      setImage(img, src);
      img.hidden = !src;
      img.dataset.key = String(top?.$key ?? '');
      img.dataset.zoom = src;
      el.classList.toggle('back', zoneName === 'Library' && cards.length > 0);
      el.classList.toggle('empty', cards.length === 0);
      // A card still on its way here is not counted until it lands
      el.dataset.count = String(cards.length);
      showCount(el);
      // The types a graveyard holds matter to delirium and cards like it, so hovering it says them
      if (zoneName === 'Graveyard') {
        el.title = graveyardTitle(cards.length, cardTypes(cards.map(c => stateOf(model, c).Type ?? '')));
      }
    });
}

/** The Command tile's badge carries the commander's tax, or how many cards are there when partners share it. */
function updateCommandTile(el: HTMLElement, model: Model, player: PlayerView): void {
  const cards = castFromCommand(model, zone(model, player, 'Command'));
  const top = cards[0];
  const src = cardImageSrc(model, top);
  const img = q<HTMLImageElement>(el, 'img');
  setImage(img, src);
  img.hidden = !src;
  img.dataset.key = String(top?.$key ?? '');
  img.dataset.zoom = src;
  // The tile's own data-key is how the render finds it again, so the card to cast is marked apart from it
  el.dataset.cast = cards.length === 1 ? 'yes' : '';
  el.classList.toggle('empty', cards.length === 0);
  el.classList.toggle('selectable', cards.some(c => (model.prompt?.selectable ?? []).some(r => r.ref === c.$key)));
  const tax = top ? commanderTax(player, top) : 0;
  q(el, '.zone-count').textContent = cards.length > 1 ? String(cards.length) : tax > 0 ? t('lblWebBoardTax', tax) : '';
  el.title = cards.map(c => {
    const more = commanderTax(player, c);
    const name = stateOf(model, c).Name ?? '';
    return more > 0 ? t('lblWebBoardCostsMoreHere', name, more) : name;
  }).join('\n');
}

/** The command zone's cards that are cast from it, as against avatars and reminders like the monarch. */
function castFromCommand(model: Model, cards: CardView[]): CardView[] {
  return cards.filter(c => ['commander', 'signature'].includes(commandKind(c, stateOf(model, c))));
}

/** Clears what is kept per game, so a new table announces its first turn even when the same player starts it. */
export function resetTable(): void {
  resetTurnBanner();
  resetMotion();
  stopWaiting();
  forgetPictures();
  resetStarter();
  seating = null;
  outSaid = false;
  document.getElementById('out-banner')?.remove();
  resetSchemes();
  resetStack();
  resetHand();
  resetOverlay();
  resetGameOver();
}

// A life change is easy to miss as a number, so the amount floats off the avatar and the ring answers
function showLife(el: HTMLElement, avatar: HTMLElement, life: number, local: boolean): void {
  const before = el.dataset.life === undefined ? life : Number(el.dataset.life);
  el.dataset.life = String(life);
  el.textContent = String(life);
  const change = life - before;
  if (!change) {
    return;
  }
  const hurt = change < 0;
  replay(el, hurt ? 'hurt' : 'healed', 'hurt', 'healed');
  const float = make('span', `life-change ${hurt ? 'hurt' : 'healed'}`);
  float.textContent = `${hurt ? '' : '+'}${change}`;
  avatar.append(float);
  float.addEventListener('animationend', () => float.remove());
  if (hurt) {
    if (local) takeHit(-change);
    else hitAvatar(avatar, -change);
  }
}

// Damage to another player rings and shakes their portrait, and seeps in from the edges of the screen near it
function hitAvatar(avatar: HTMLElement, amount: number): void {
  const at = avatar.getBoundingClientRect();
  const wash = make('div', `hit-flash near${amount >= 5 ? ' hard' : ''}`);
  wash.style.setProperty('--at', `${at.left + at.width / 2}px ${at.top + at.height / 2}px`);
  document.body.append(wash);
  wash.addEventListener('animationend', () => wash.remove());
  replay(avatar, amount >= 5 ? 'hit-hard' : 'hit', 'hit', 'hit-hard');
  avatar.addEventListener('animationend', e => {
    if (e.target === avatar) avatar.classList.remove('hit', 'hit-hard');
  });
  const glow = make('span', 'avatar-hit');
  avatar.append(glow);
  glow.addEventListener('animationend', () => glow.remove());
}

// Damage to your own life shakes the board and seeps red in from the edges, so it cannot be missed
function takeHit(amount: number): void {
  const match = byId('match');
  replay(match, amount >= 5 ? 'hit-hard' : 'hit', 'hit', 'hit-hard');
  // A card's own animations end inside the board and are heard here too, so only the board's own ends the shake
  const settled = (e: AnimationEvent) => {
    if (e.target !== match) return;
    match.classList.remove('hit', 'hit-hard');
    match.removeEventListener('animationend', settled);
  };
  match.addEventListener('animationend', settled);
  const flash = make('div', amount >= 5 ? 'hit-flash hard' : 'hit-flash');
  document.body.append(flash);
  flash.addEventListener('animationend', () => flash.remove());
}

const STAR_ICON = '<svg viewBox="0 0 16 16" aria-hidden="true"><path fill="currentColor" d="M8 1l1.9 4.2 4.6.5-3.4 3.1 1 4.5L8 11l-4.1 2.3 1-4.5L1.5 5.7l4.6-.5z"/></svg>';
const HOURGLASS_ICON = '<svg viewBox="0 0 16 16" aria-hidden="true"><path fill="currentColor" d="M3 1h10v2l-3.6 5L13 13v2H3v-2l3.6-5L3 3zm2 2l3 4 3-4z"/></svg>';

/** What a command zone effect is called on its plaque: an emblem or a lasting designation by name, else an effect. */
function effectKind(state: Partial<CardStateView>): { label: string; lasting: boolean } {
  if (/\bEmblem\b/.test(state.Type ?? '')) return { label: t('lblEmblem'), lasting: true };
  // The designation's name is the card's, as the server sends it
  const named = /^The (Monarch|Initiative|Ring)$/.exec(state.Name ?? '');
  return named ? { label: named[1], lasting: true } : { label: t('lblEffect'), lasting: false };
}

/** More kinds of effect than this beside one portrait fold into a single plaque. */
const MAX_EFFECT_PLAQUES = 4;

/** One plaque beside the portrait: a card of its own, or the effects it stands for when there are several. */
interface Plaque {
  card: CardView;
  members: CardView[];
}

/** An effect is named after its source and that card's number, which tells two copies of the source apart. */
const sourceName = (name: string | undefined) => (name ?? '').replace(/ \(\d+\)/, '');

/** Effects alike in name and number share a plaque, and too many kinds share one between them. */
function plaques(model: Model, cards: CardView[]): Plaque[] {
  const avatars: Plaque[] = [];
  const effects = new Map<string, Plaque>();
  for (const card of cards) {
    const state = stateOf(model, card);
    const kind = commandKind(card, state);
    if (kind === 'avatar') {
      avatars.push({ card, members: [card] });
    } else if (kind === 'effect') {
      const like = `${sourceName(state.Name)}|${card.OverlayText ?? ''}`;
      const pile = effects.get(like);
      if (pile) pile.members.push(card);
      else effects.set(like, { card, members: [card] });
    }
  }
  const piles = [...effects.values()];
  return piles.length > MAX_EFFECT_PLAQUES ? [...avatars, { card: piles[0].card, members: piles.flatMap(p => p.members) }]
    : [...avatars, ...piles];
}

// The command zone apart from commanders and signature spells, which have the Command tile among the zones
function renderEmblems(root: HTMLElement, model: Model, player: PlayerView | undefined, cards: CardView[],
    select: CardClick): void {
  // Planes, schemes and the planar die have places of their own; the rest stay beside the portrait
  reconcile(root, plaques(model, cards), p => p.card.$key,
    ({ card }) => {
      // A card the player reads or activates is drawn as a card; a reminder like the monarch stays a round token
      const tile = commandKind(card, stateOf(model, card)) !== 'effect';
      const el = make('div', tile ? 'cmd-tile' : 'emblem');
      el.innerHTML = tile ? '<img alt="" draggable="false"><span class="band"></span>'
        : '<img alt="" draggable="false"><span class="initials"></span><span class="band"><i></i><b></b></span><span class="tax"></span><span class="n"></span>';
      const img = q<HTMLImageElement>(el, 'img');
      if (tile) {
        noImageOnError(el, img);
      } else {
        // The art alone is fetched as a card-art sleeve's is; a card with none has its art box cut from the whole card
        img.addEventListener('error', () => {
          if (!el.classList.contains('whole') && el.dataset.zoom) {
            el.classList.add('whole');
            img.src = el.dataset.zoom;
          } else {
            el.classList.add('noimg');
          }
        });
      }
      hoverable(el);
      return el;
    },
    (el, { card, members }) => {
      // Several effects open the command zone, where each can be read and chosen
      el.onclick = () => (members.length > 1 && player ? togglePile(player.$key, 'Command') : select(el, false));
      const state = stateOf(model, card);
      const src = cardImageSrc(model, card);
      el.dataset.zoom = src;
      if (el.classList.contains('cmd-tile')) {
        setImage(q<HTMLImageElement>(el, 'img'), src);
        el.classList.toggle('noimg', !src);
      } else if (el.dataset.art !== (state.ImageKey ?? '')) {
        el.dataset.art = state.ImageKey ?? '';
        el.classList.remove('whole', 'noimg');
        setImage(q<HTMLImageElement>(el, 'img'), src && state.ImageKey ? artUrl(state.ImageKey) : '');
        el.classList.toggle('noimg', !src);
      }
      const tax = commanderTax(player, card);
      el.title = tax > 0 ? t('lblWebBoardEffectCostsMoreHere', state.Name ?? '', tax)
        : members.length > 1 ? [...new Set(members.map(m => sourceName(stateOf(model, m).Name)))].join('\n') : state.Name ?? '';
      el.classList.toggle('selectable', (model.prompt?.selectable ?? []).some(r => members.some(m => m.$key === r.ref)));
      if (el.classList.contains('cmd-tile')) {
        q(el, '.band').textContent = tileBand(commandKind(card, state), state, tax);
        return;
      }
      const words = (state.Name ?? '').replace(/^(The|Emblem) /, '').split(/[\s-]+/).filter(w => /^\w/.test(w));
      q(el, '.initials').textContent = words.map(w => w[0]).join('').slice(0, 2).toUpperCase();
      const kind = effectKind(state);
      q(el, '.band i').innerHTML = kind.lasting ? STAR_ICON : HOURGLASS_ICON;
      q(el, '.band b').textContent = kind.label;
      // An effect that keeps a number, such as a player's speed, carries it as the text desktop lays over the card
      q(el, '.tax').textContent = tax > 0 ? t('lblWebBoardTax', tax) : card.OverlayText ?? '';
      q(el, '.n').textContent = members.length > 1 ? `×${members.length}` : '';
    });
}

/** The one number a command tile carries: an avatar's modifiers, a commander's tax, or what a signature spell is. */
function tileBand(kind: CommandKind, state: Partial<CardStateView>, tax: number): string {
  if (kind === 'avatar') {
    const mods = avatarModifiers(state.RulesText);
    const signed = (n: number) => (n < 0 ? `−${-n}` : `+${n}`);
    return mods ? t('lblWebLobbyAvatarMods', signed(mods[0]), signed(mods[1])) : '';
  }
  if (kind === 'signature') return t('lblWebBoardSignature');
  return tax > 0 ? t('lblWebBoardTax', tax) : '';
}
