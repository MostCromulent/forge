// Lays out the whole match screen and redraws it from the model, leaving each piece to its own module

import { reconcile } from './render';
import { cardImageSrc, hideOnError, noImageOnError, setImage, symbolUrl } from '../images';
import { commanderTax, game, me, players, zone, deref, stackPick, stateOf, isLocal, type Model } from '../model';
import { renderHand } from './hand';
import { renderZones, togglePile, zoneTitle } from './zones';
import { renderBattlefield } from './battlefield';
import { followPointer, hoverPlayer, hoverable, inspectCard, inspectPlayer } from './detail';
import { foldStack, renderStack } from './stack';
import { renderPlanes } from './planes';
import { isArchenemy, renderOngoing, resetSchemes, revealSchemes } from './schemes';
import { renderPhaseBar, stopWaiting } from './phasebar';
import { forgetPictures } from './cards';
import { artUrl } from '../sleeves';
import { playerAvatarUrl, playerSleeveUrl, cssUrl, ROBOT_ICON } from '../looks';
import { animateCardMoves, noteBoard, resetMotion } from './motion';
import { canShatter, shatter } from './shatter';
import { byId, q, replay, make } from '../dom';
import { setting } from '../settings';
import { logTints } from './log';
import type { CardClick } from './cards';
import type { Actions } from '../actions';
import type { CardStateView, CardView, GameEvent, GameView, PlayerView, Ref, StateMessage, ZoneType } from '../protocol';
import { avatarModifiers, commandKind, type CommandKind } from './command';
import { notePick } from './overlay';
import { hovers, isTouch, longPress } from '../press';
import { fieldChanged, repeatTap, seatToOpen, tapInspects } from './portrait';
import { isPortrait } from '../form';
import { sheet, swipeDown } from '../sheet';
import { changeUi, ui } from '../ui';
import { t, type TextKey } from '../text';

// The Mana property counts the pool by Forge's mana bit (ManaAtom): the five colours as MagicColor has them, and colourless its own bit
const MANA: [number, string][] = [[1, 'W'], [2, 'U'], [4, 'B'], [8, 'R'], [16, 'G'], [32, 'C']];

/** Commander damage that loses the game, and the point from which the portrait warns of it. */
export const COMMANDER_LETHAL = 21;
export const COMMANDER_WARNING = 15;
// Lucide's swords (ISC, see web/licenses/lucide-license.txt)
const SWORDS = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14.5 17.5 3 6V3h3l11.5 11.5M13 19l6-6M16 16l4 4M19 21l2-2M14.5 6.5 18 3h3v3l-3.5 3.5M5 14l4 4M7 17l-3 3M3 19l2 2"/></svg>';

export function renderMatch(model: Model, actions: Actions, events: readonly GameEvent[]): void {
  const g = game(model);
  if (!g) return;
  // Before anything is redrawn, so each card's move is measured from where it stood on screen
  noteBoard();
  for (const e of events) {
    if (e.kind === 'gameStarted') {
      firstPlayer = e.first.ref;
      // Who chose who starts already knows who does
      if (!choseStarter) revealFirst(model, firstPlayer);
    }
  }
  const choice = model.prompt?.starterChoice;
  if (choice && !choseStarter) {
    choseStarter = true;
    chooseFirst(model, actions, choice);
  }
  // Answered from the prompt's own buttons instead, or a game conceded before anyone chose, the faces have nothing left to ask
  if (!choice || model.gameOver) document.querySelector('#first-reveal.choosing')?.remove();
  noticeLosses(model, actions);
  // A prompt offering cards or players to pick, or a spell picked on the stack, dims everything else (board.css); paying a cost is not such a pick
  const p = model.prompt;
  byId('match').classList.toggle('picking', !!stackPick(model) || (!!p && !p.paying
    && ((p.selectableMin > 0 && p.selectable.length > 0) || p.selectablePlayers.length > 0)));
  // The click position travels with the click, so an ability list opens on the card as desktop's menu does
  const select: CardClick = (el, menu, e) => {
    const key = Number(el.dataset.key);
    if (isTouch(e) && !menu) {
      if (repeatTap(key, performance.now())) return;
      // Read as the tap lands: a card keeps the handler it was made with, long after the question of that moment
      const card = model.objects.get(key) as CardView | undefined;
      const tapped = { playable: el.classList.contains('playable'), selectable: el.classList.contains('selectable'),
        mine: card?.Controller?.ref === me(model)?.$key, attacking: !!card?.Attacking };
      if (tapInspects(tapped, byId('match').classList.contains('picking'), game(model)?.Phase)) {
        inspectCard(el);
        return;
      }
      // Shown at once, since the host's answer can be a moment away
      replay(el, 'pressed');
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
      const changed = !shown && !!setting('tabNewCards') && fieldChanged(ui.seenOnField.get(o.$key), field);
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
    window.dispatchEvent(new Event('resize'));
  }
}

// Lucide's users (ISC, see web/licenses/lucide-license.txt)
const PLAYERS_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg><i class="dot" aria-hidden="true"></i>';

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

// Lucide's skull (ISC, see web/licenses/lucide-license.txt)
const SKULL = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12.5 17-.5-1-.5 1h1z"/><path d="M15 22a1 1 0 0 0 1-1v-1a2 2 0 0 0 1.56-3.25 8 8 0 1 0-11.12 0A2 2 0 0 0 8 20v1a1 1 0 0 0 1 1z"/><circle cx="15" cy="12" r="1"/><circle cx="9" cy="12" r="1"/></svg>';

interface Badge {
  key: string;
  text: string;
  title: string;
}

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
  avatar.classList.toggle('first', firstPlayer === player.$key && (game(model)?.Turn ?? 0) <= 1);
  // A player who has lost leaves an empty socket: their portrait broke, or breaks now, out of it
  avatar.classList.toggle('lost', !!player.HasLost);
  renderHandFan(q(root, '.hand-fan'), model, player);
  renderZoneTiles(q(root, '.zone-tiles'), model, player, select);
  renderZonesPill(root, model, player);
  renderManaPool(q(root, '.mana'), player, isLocal(model, player), actions);
  showCommanderDamage(avatar, model, player);
  const badges: Badge[] = Object.entries(player.Counters ?? {}).map(([name, n]) => ({ key: name, text: `${name.toLowerCase()} ${n}`, title: '' }));
  reconcile(q(root, '.player-counters'), badges, b => b.key,
    () => {
      const el = make('span', 'player-counter');
      return el;
    },
    (el, b) => {
      el.textContent = b.text;
      el.title = b.title;
    });
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
  W: 'lblWebBoardManaWhite', U: 'lblWebBoardManaBlue', B: 'lblWebBoardManaBlack', R: 'lblWebBoardManaRed',
  G: 'lblWebBoardManaGreen', C: 'lblWebBoardManaColourless',
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
  ['AttractionDeck', 'lblAttractions'], ['ContraptionDeck', 'lblContraptions'], ['Junkyard', 'lblWebZoneJunkyard']];

/** The card types rule 205.2a names, in its order. Delirium and the like count how many a graveyard holds. */
const CORE_TYPES = ['Artifact', 'Battle', 'Creature', 'Enchantment', 'Instant', 'Kindred', 'Land', 'Planeswalker', 'Sorcery'];

/** The card types among these type lines, in the rules' order, as desktop's graveyard label counts them. */
export function cardTypes(typeLines: readonly string[]): string[] {
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
export function graveyardTitle(count: number, types: string[]): string {
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
    // Put away by a tap anywhere else, and by the tap that chooses a tile
    document.addEventListener('click', e => {
      if (ui.zonesFor !== null && !(e.target instanceof Element && e.target.closest('.zones-pill'))) changeUi(u => { u.zonesFor = null; });
    });
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
      el.classList.toggle('hidden-deck', zoneName !== 'Junkyard' && EXTRA_ZONES.some(([z]) => z === zoneName));
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
      // A card still on its way here is not counted until it lands (motion.ts holdCounts)
      el.dataset.count = String(cards.length);
      if (!el.dataset.heldCount) q(el, '.zone-count').textContent = String(cards.length);
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

// The turn last announced, null on a new table so one first seen part way through says nothing until the turn changes
let announced: string | null = null;

/** Clears what is kept per game, so a new table announces its first turn even when the same player starts it. */
export function resetTable(): void {
  announced = null;
  resetMotion();
  stopWaiting();
  forgetPictures();
  firstPlayer = null;
  seating = null;
  outSaid = false;
  document.getElementById('out-banner')?.remove();
  choseStarter = false;
  broken = null;
  resetSchemes();
  titleReady = true;
  finalRunning = false;
  byId('match').classList.remove('ending');
  const over = byId('game-over');
  over.hidden = true;
  over.replaceChildren();
}

/** The player the game said takes the first turn, from the moment it is settled until the next game. */
let firstPlayer: number | null = null;
/** This viewer was asked who starts, having won the toss or lost the last game. */
let choseStarter = false;

/** Two players are asked to play or draw, so your own face answers OK and the other cancels, while with more a face picks that player. */
function chooseFirst(model: Model, actions: Actions, choice: string): void {
  const mine = me(model)?.$key;
  const two = players(model).length === 2;
  revealFirst(model, null, t(choice === 'toss' ? 'lblWebBoardWonCoinToss' : 'lblWebBoardLostLastGame'), key => {
    if (!two) actions.selectPlayer(key);
    else if (key === mine) actions.ok();
    else actions.cancel();
    document.getElementById('first-reveal')?.remove();
  });
}

/** Shows who goes first over the board, or with pick asks it: the faces are buttons and it stays until one is chosen. */
function revealFirst(model: Model, first: number | null, said?: string, pick?: (key: number) => void): void {
  const everyone = players(model);
  const starter = everyone.find(p => p.$key === first);
  if (!starter && !pick) return;
  document.getElementById('first-reveal')?.remove();
  const reveal = document.createElement('div');
  reveal.id = 'first-reveal';
  reveal.setAttribute('role', pick ? 'dialog' : 'status');
  reveal.classList.toggle('choosing', !!pick);
  const faces = make('div', 'reveal-faces');
  for (const p of everyone) {
    const face = document.createElement(pick ? 'button' : 'div');
    face.className = p.$key === first ? 'reveal-face first' : 'reveal-face';
    if (pick) face.onclick = () => pick(p.$key);
    const url = playerAvatarUrl(p);
    const picture = document.createElement(url ? 'img' : 'span');
    if (picture instanceof HTMLImageElement) {
      picture.alt = '';
      picture.src = url;
    } else {
      picture.textContent = (p.Name ?? '?').slice(0, 1).toUpperCase();
    }
    const name = make('span', 'reveal-name');
    name.textContent = p.Name ?? '';
    face.append(picture, name);
    faces.append(face);
  }
  const line = document.createElement('p');
  line.textContent = said ?? (starter && isLocal(model, starter) ? t('lblWebBoardYouGoFirst') : t('lblWebBoardPlayerGoesFirst', starter?.Name ?? ''));
  reveal.append(faces, line);
  byId('match').append(reveal);
  reveal.addEventListener('animationend', e => {
    if (e.animationName === 'first-reveal') reveal.remove();
  });
}

/** Announces the turn a state message starts before it is shown, and runs `then` only if a banner went up, as it leaves. */
export function announceComing(model: Model, msg: StateMessage, then: () => void): boolean {
  const g = game(model);
  const delta = msg.deltas[model.root] as { Turn?: number; PlayerTurn?: Ref } | undefined;
  // Only the turn's number starts a turn: its player is named in the cleanup step, which can come a message earlier
  if (msg.full || !g || !delta || delta.Turn === undefined) {
    return false;
  }
  return announce(model, delta.Turn, deref(model, delta.PlayerTurn ?? g.PlayerTurn), then);
}

// Only catches the turn a table was first drawn in, as every later turn is announced when its message arrives
function announceTurn(model: Model, g: GameView): void {
  if (announced === null) announce(model, g.Turn, deref(model, g.PlayerTurn), () => {});
}

/** How long before the turn banner has gone that the turn's own actions start. */
const TURN_OVERLAP_MS = 300;

function announce(model: Model, turnNumber: number | undefined, active: PlayerView | undefined, then: () => void): boolean {
  const turn = `${turnNumber ?? 0}/${active?.$key ?? ''}`;
  if (!active || announced === turn) {
    return false;
  }
  const seenBefore = announced !== null;
  announced = turn;
  if (!seenBefore && turnNumber !== 1) {
    return false;
  }
  const mine = isLocal(model, active);
  // With several opponents most turns are someone else's, and the phase pill already names whose it is
  if (!mine && players(model).length > 2) {
    return false;
  }
  const banner = make('div', `turn-banner${mine ? ' mine' : ''}`);
  banner.textContent = mine ? t('lblWebPhaseYourTurn') : t('lblWebBoardPlayersTurn', active.Name ?? '');
  // The banner takes the pill's place instead of covering it, because the pill animates its own width
  const strip = byId('phase-strip');
  strip.append(banner);
  strip.classList.add('announcing');
  // The pill returns as the banner starts to fade (84% of turn-sweep), so one fades in while the other fades out
  const sweep = parseFloat(getComputedStyle(banner).animationDuration) * 1000;
  // unless the next turn's banner has gone up meanwhile, which a turn that passes at once allows
  setTimeout(() => {
    if ([...strip.querySelectorAll('.turn-banner')].at(-1) === banner) strip.classList.remove('announcing');
  }, sweep * 0.84);
  // The turn goes on just before the banner has quite gone
  setTimeout(then, Math.max(0, sweep - TURN_OVERLAP_MS));
  // The end of the ::after light's animation reaches the banner too, so the sweep is named or the banner leaves early
  banner.addEventListener('animationend', e => {
    if (e.animationName === 'turn-sweep') banner.remove();
  });
  return true;
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
  // The designation's name is the card's, as the host sends it
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

/** Players whose portrait has broken this game, so each breaks once. Null until the table is first drawn. */
let broken: Set<number> | null = null;
/** False while a portrait breaking at the end of the game has not reached the moment its title follows. */
let titleReady = true;
let finalRunning = false;

/** Breaks the portrait of each player who has just lost, taking the losses a table already had when first seen as broken. */
function noticeLosses(model: Model, actions: Actions): void {
  const lost = players(model).filter(p => p.HasLost);
  if (!broken) {
    broken = new Set(lost.map(p => p.$key));
    return;
  }
  const fresh = lost.filter(p => !broken!.has(p.$key));
  if (!fresh.length) return;
  fresh.forEach(p => broken!.add(p.$key));
  // The loss and the end of the game can arrive a message apart, so the ending is decided a moment later
  titleReady = false;
  window.setTimeout(() => breakPortraits(model, fresh, actions), 120);
}

function breakPortraits(model: Model, losers: PlayerView[], actions: Actions): void {
  const g = game(model);
  const final = !!g?.GameOver;
  const release = () => {
    titleReady = true;
    const now = game(model);
    if (now) renderGameOver(model, now, actions);
  };
  const seated = losers
    .map(p => ({ p, avatar: document.querySelector<HTMLElement>(`.seat[data-player="${p.$key}"] .avatar`) }))
    .filter(s => s.avatar && s.avatar.offsetWidth);
  if (!canShatter() || !seated.length) {
    release();
    return;
  }
  const centre = pageCentre();
  seated.forEach(({ p, avatar }, i) => {
    const centreStage = final && i === 0;
    const run = shatter({
      from: avatar!.getBoundingClientRect(), image: playerAvatarUrl(p), final: centreStage, centre,
      onTitle: centreStage ? release : undefined,
    });
    if (centreStage) {
      finalRunning = true;
      const match = byId('match');
      match.style.setProperty('--end-x', centre.x + 'px');
      match.style.setProperty('--end-y', centre.y + 'px');
      match.classList.add('ending');
      // Without WebGL nothing has shown yet, so the title is simply not held back
      run.catch(release);
    } else {
      run.catch(() => {});
    }
  });
  if (!final) release();
}

/** The middle of the window, not of the board, so a breaking portrait lands where the result then shows the winner's face. */
function pageCentre(): { x: number; y: number } {
  return { x: window.innerWidth / 2, y: window.innerHeight / 2 };
}

/** Each player's games won in the match: their portrait, their name and the count, the match's winner in gold. */
function drawTally(root: HTMLElement, everyone: PlayerView[], wins: (p: PlayerView) => number | undefined,
  champion: PlayerView | undefined): void {
  const counted = everyone.filter(p => wins(p) !== undefined);
  root.hidden = counted.length === 0;
  for (const p of counted) {
    const row = make('div', p === champion ? 'tally-player champion' : 'tally-player');
    row.innerHTML = '<span class="tally-face"></span><span class="tally-name"></span><b class="tally-won"></b>';
    q(row, '.tally-face').style.backgroundImage = cssUrl(playerAvatarUrl(p));
    q(row, '.tally-name').textContent = p.Name ?? '';
    const n = wins(p) ?? 0;
    q(row, '.tally-won').textContent = String(n);
    row.title = t(n === 1 ? 'lblWebBoardOneGameWon' : 'lblWebBoardGamesWon', n);
    root.append(row);
  }
}

/** Shows the result once the losing portrait has broken, drawn once per ending so what fades in does so once. */
/** How many motes of light rise past a winner's portrait. */
const WIN_MOTES = 16;

function renderGameOver(model: Model, g: GameView, actions: Actions): void {
  const root = byId('game-over');
  const show = model.gameOver && titleReady;
  // Looking over the final board lifts the ending off it, and the result comes back from a button
  byId('match').classList.toggle('ending', (show || finalRunning) && !root.classList.contains('viewing'));
  if (!show) {
    if (!root.hidden) {
      root.hidden = true;
      root.classList.remove('viewing');
      root.replaceChildren();
    }
    return;
  }
  // A gauntlet's result and the match's score follow the game's end, so a panel drawn before they arrive is drawn again
  const limited = model.limitedResult;
  // A campaign's result says which buttons the ending offers, so the panel waits for it
  const campaign = model.campaignResult;
  if (model.campaignSave && !campaign) return;
  const drawnFor = `${!!limited}/${campaign ? `${campaign.won}:${campaign.matchOver}` : ''}/${model.matchScore.map(s => `${s.player.ref}:${s.won}`).join(',')}`;
  if (!root.hidden && root.dataset.drawnFor === drawnFor) return;
  root.dataset.drawnFor = drawnFor;
  root.hidden = false;
  const matchOver = !!g.MatchOver;
  const everyone = players(model);
  const winner = everyone.find(p => p.Name === g.WinningPlayerName);
  const losers = everyone.filter(p => p.HasLost);
  const seated = everyone.some(p => isLocal(model, p));
  const won = !!winner && isLocal(model, winner);
  const outcome = !winner ? 'draw' : !seated ? 'watched' : won ? 'win' : 'lose';
  // In a match of several games, a game is won or lost, and only the match is a victory or a defeat
  const games = g.NumGamesInMatch ?? 1;
  const inMatch = games > 1;
  const midMatch = inMatch && !matchOver;
  const word = t(midMatch
    ? ({ draw: 'lblWebBoardDraw', watched: 'lblWebBoardGameOver', win: 'lblWebBoardGameWon', lose: 'lblWebBoardGameLost' } as const)[outcome]
    : ({ draw: 'lblWebBoardDraw', watched: 'lblWebBoardGameOver', win: 'lblWebBoardVictory', lose: 'lblWebBoardDefeat' } as const)[outcome]);
  const wins = (p: PlayerView) => model.matchScore.find(s => s.player.ref === p.$key)?.won;
  // Two players' match score reads as the winner's wins to the other's, as it is said aloud
  const score = winner && everyone.length === 2 && wins(winner) !== undefined
    ? [wins(winner) ?? 0, wins(everyone.find(p => p !== winner) as PlayerView) ?? 0] : null;
  // The count of games played is taken as the game starts, so it counts the ones before this
  const gameNumber = (g.NumPlayedGamesInMatch ?? 0) + 1;
  const sub = !winner ? t('lblWebBoardNobodyWins')
    : midMatch ? (won ? t('lblWebBoardYouWinGame', gameNumber) : t('lblWebBoardPlayerWinsGame', winner.Name ?? '', gameNumber))
    : inMatch && score ? (won ? t('lblWebBoardYouWinMatchScore', score[0], score[1])
      : t('lblWebBoardPlayerWinsMatchScore', winner.Name ?? '', score[0], score[1]))
    : inMatch ? (won ? t('lblWebBoardYouWinMatch') : t('lblWebBoardPlayerWinsMatch', winner.Name ?? ''))
    : won && everyone.length === 2 && losers.length === 1 ? t('lblWebBoardPlayerHasLost', losers[0].Name ?? '')
    : won ? t('lblWebBoardLastOneStanding')
    : t('lblWebBoardPlayerWins', winner.Name ?? '');
  root.innerHTML = '<div class="panel"><p class="stage"></p><div class="face-wrap"><div class="face"></div></div><p class="word"></p><div class="rule"></div><p class="sub"></p>'
    + '<div class="tally"></div><div class="actions"></div></div>'
    + '<button class="to-result primary"></button>';
  q(root, '.to-result').textContent = t('lblWebBoardShowResult');
  const stage = q(root, '.stage');
  stage.hidden = !inMatch;
  stage.textContent = matchOver ? t('lblWebBoardMatchOverBestOf', games) : t('lblWebBoardGameBestOf', gameNumber, games);
  drawTally(q(root, '.tally'), inMatch ? everyone : [], wins, matchOver ? winner : undefined);
  const view = (board: boolean) => {
    root.classList.toggle('viewing', board);
    byId('match').classList.toggle('ending', !board);
  };
  q(root, '.to-result').onclick = () => view(false);
  const panel = q(root, '.panel');
  panel.classList.add(outcome);
  const face = q(root, '.face');
  // Your own win is lit from behind the portrait: rays that stay, and motes that rise past it once
  if (outcome === 'win') {
    face.before(make('span', 'win-rays'), ...Array.from({ length: WIN_MOTES }, (_, i) => {
      const mote = make('span', 'win-mote');
      mote.style.setProperty('--at', `${(i * 83) % 300 - 150}px`);
      mote.style.setProperty('--drift', `${(i * 53) % 50 - 25}px`);
      mote.style.setProperty('--wait', `${(0.35 + i * 0.16).toFixed(2)}s`);
      return mote;
    }));
  }
  if (winner) face.style.backgroundImage = cssUrl(playerAvatarUrl(winner));
  face.hidden = !winner;
  q(root, '.word').textContent = word;
  q(root, '.sub').textContent = limited
    ? t('lblWebBoardGauntletResult', sub, limited.round, limited.rounds, limited.wins, limited.losses)
    : campaign?.line ?? sub;
  const buttons = q(root, '.actions');
  const add = (label: string, primary: boolean, onClick: () => void) => {
    const b = document.createElement('button');
    b.textContent = label;
    if (primary) b.className = 'primary';
    b.onclick = onClick;
    buttons.append(b);
  };
  // A campaign's match ends as its mode says, and the battlefield can be looked at whatever the mode offers
  if (campaign) {
    const quit = () => { actions.quitMatch(); actions.leave(); };
    const draw = () => {
      buttons.replaceChildren();
      const does: Record<string, () => void> = {
        nextGame: () => actions.nextGame(), leave: () => actions.leave(), restart: () => actions.restartGame(), quit,
        // A forfeit loses what was paid to enter, so it is asked first, as desktop asks
        forfeit: () => {
          buttons.replaceChildren();
          const ask = make('p', 'ask');
          ask.textContent = t('lblWebQuestForfeitAsk');
          buttons.append(ask);
          add(t('lblCancel'), true, draw);
          add(t('lblWebQuestForfeitTournament'), false, quit);
        },
      };
      campaign.buttons.forEach((b, i) => {
        add(b.label, b.primary, does[b.action]);
        if (i === 0) add(t('lblWebBoardViewBattlefield'), false, () => view(true));
      });
    };
    draw();
    return;
  }
  if (limited?.nextRound) add(t('lblWebBoardNextRound', limited.round + 1, limited.rounds), true, () => actions.gauntletNext());
  if (!matchOver) add(t('lblWebBoardNextGame'), true, () => actions.nextGame());
  add(t('lblWebBoardViewBattlefield'), false, () => view(true));
  if (limited) add(t('lblWebBoardRestartRound'), false, () => actions.gauntletRestart());
  add(t(limited ? 'lblQuit' : matchOver ? 'lblWebBoardReturnToLobby' : 'lblWebBoardQuitMatch'), matchOver && !limited?.nextRound, () => {
    if (!matchOver) actions.quitMatch();
    actions.leave();
  });
}
