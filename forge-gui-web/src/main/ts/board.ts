import { reconcile } from './render';
import { cardImageSrc, hideOnError, noImageOnError, setImage, symbolUrl } from './images';
import { game, me, opponents, players, zone, deref, stateOf, isLocal, type Model } from './model';
import { renderHand } from './hand';
import { renderZones, togglePile } from './zones';
import { renderBattlefield } from './battlefield';
import { hoverPlayer, hoverable } from './detail';
import { renderStack } from './stack';
import { renderPlanes } from './planes';
import { isArchenemy, renderOngoing, resetSchemes, revealSchemes } from './schemes';
import { renderPhaseBar } from './phasebar';
import { playerAvatarUrl, playerSleeveUrl, cssUrl, ROBOT_ICON } from './looks';
import { animateCardMoves } from './motion';
import { canShatter, shatter } from './shatter';
import { byId, q } from './dom';
import { setting } from './settings';
import { logTints } from './log';
import type { CardClick } from './cards';
import type { Actions } from './actions';
import type { CardStateView, CardView, GameEvent, GameView, PlayerView, ZoneType } from './protocol';
import { avatarModifiers, commandKind, type CommandKind } from './command';

// The Mana property counts the pool by Forge's mana bit (ManaAtom): the five colours as MagicColor has them, and colourless its own bit
const MANA: [number, string][] = [[1, 'W'], [2, 'U'], [4, 'B'], [8, 'R'], [16, 'G'], [32, 'C']];

export function renderMatch(model: Model, actions: Actions, events: readonly GameEvent[]): void {
  const g = game(model);
  if (!g) return;
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
    const mine = me(model);
    if (choice === 'toss' && mine) revealFirst(model, mine.$key, 'You won the coin toss');
  }
  noticeLosses(model, actions);
  // The click position travels with the click, so an ability list opens on the card as desktop's menu does
  const select: CardClick = (el, menu, e) => actions.selectCard(Number(el.dataset.key), menu, e?.clientX ?? 0, e?.clientY ?? 0);
  // Attachments can cross players (an aura on an opponent's creature), so slots are built from every battlefield
  const onField = players(model).flatMap(p => zone(model, p, 'Battlefield'));
  renderOpponents(byId('opponent'), model, onField, actions, select);
  renderSeat(byId('me'), model, me(model), onField, actions, select);
  renderOut(model, actions);
  announceTurn(model, g);
  renderPhaseBar(model, g, actions);
  renderStack(model);
  renderPlanes(model, actions);
  revealSchemes(model);
  renderHand(model, me(model), select);
  renderZones(model, actions, select);
  renderGameOver(model, g, actions);
  animateCardMoves(model, events);
}

/**
 * The players across the table, in the order they sit: turn order, going round from you. The order is taken once,
 * when the game is first drawn, so a card that reverses the turn order does not move anyone.
 */
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

/**
 * One opponent fills the top half as a seat of its own. Two or three share it as columns, or with you as four
 * quarters of the table, as the player chooses; each is then a compact seat, its details in a row above its cards.
 */
function renderOpponents(root: HTMLElement, model: Model, onField: CardView[], actions: Actions, select: CardClick): void {
  const across = seated(model);
  const many = across.length > 1;
  const quads = many && setting('boardLayout') === 'quadrants';
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
    return;
  }
  // Each opponent's seat is in the colour their name has in the log
  const tints = logTints(players(model).map(p => ({ name: p.Name ?? '', local: isLocal(model, p) })));
  reconcile(root, across, p => p.$key,
    () => {
      const el = document.createElement('section');
      el.className = 'seat compact';
      return el;
    },
    (el, p) => {
      el.style.setProperty('--tint', tints.find(t => t.name === p.Name)?.colour ?? 'var(--muted)');
      renderSeat(el, model, p, onField, actions, select);
    });
}

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
  banner.innerHTML = `<span class="skull">${SKULL}</span><div><b>You're out</b><p>The game goes on between the others.</p></div>`
    + '<button class="watch">Keep watching</button>' + (model.networked ? '' : '<button class="leave primary">Leave match</button>');
  q(banner, '.watch').onclick = () => banner?.remove();
  const leave = banner.querySelector<HTMLButtonElement>('.leave');
  if (leave) {
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
        <div class="avatar"><img class="portrait" alt="" draggable="false"><span class="initial"></span><span class="skull-mark" title="Out of the game">${SKULL}</span><span class="ai-badge" title="Computer player">${ROBOT_ICON}</span><span class="life"></span></div>
        <div class="name"><span class="who"></span><span class="role-tag" hidden>Archenemy</span></div>
        <button class="hand-fan" hidden><span class="backs"><i></i><i></i><i></i></span><span class="hand-count"></span></button>
        <div class="player-counters"></div>
        <div class="emblems"></div>
        <div class="schemes-ongoing"></div>
        <div class="zone-tiles"></div>
        <div class="mana" hidden><span class="mana-label">Floating mana</span><div class="mana-chips"></div></div>
      </div>
      <div class="battlefield">
        <div class="row"><div class="group lands"></div><div class="group support"></div></div>
        <div class="row together"><div class="group creatures"></div><div class="group tokens"></div><div class="group far"></div></div>
      </div>`;
    const avatarEl = q(root, '.avatar');
    avatarEl.onclick = () => actions.selectPlayer(Number(root.dataset.player));
    avatarEl.addEventListener('mouseenter', () => hoverPlayer(Number(root.dataset.player)));
    avatarEl.addEventListener('mouseleave', () => hoverPlayer(null));
    q(root, '.hand-fan').onclick = () => togglePile(Number(root.dataset.player), 'Hand');
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
  renderZoneTiles(q(root, '.zone-tiles'), model, player);
  renderManaPool(q(root, '.mana'), player, isLocal(model, player), actions);
  const badges: Badge[] = Object.entries(player.Counters ?? {}).map(([name, n]) => ({ key: name, text: `${name.toLowerCase()} ${n}`, title: '' }));
  for (const { card, value } of player.CommanderDamage ?? []) {
    const commander = deref(model, card);
    const name = (commander ? stateOf(model, commander).Name : undefined) ?? 'Commander';
    if (value > 0) badges.push({ key: `cmdr-${card.ref}`, text: `${name} ${value}`, title: `Commander damage from ${name}` });
  }
  reconcile(q(root, '.player-counters'), badges, b => b.key,
    () => {
      const el = document.createElement('span');
      el.className = 'player-counter';
      return el;
    },
    (el, b) => {
      el.textContent = b.text;
      el.title = b.title;
      el.classList.toggle('commander-damage', !!b.title);
    });
  renderEmblems(q(root, '.emblems'), model, player, zone(model, player, 'Command'), select);
  q(root, '.role-tag').hidden = !isArchenemy(model, player);
  renderOngoing(q(root, '.schemes-ongoing'), model, player);
  renderBattlefield(root, model, zone(model, player, 'Battlefield'), onField, select);
}

// Mana in the pool is spent or lost when the step ends, so it is shown apart from everything that stays, under its
// own label. Clicking your own pays with that colour, as on desktop.
function renderManaPool(root: HTMLElement, player: PlayerView, own: boolean, actions: Actions): void {
  const pool = MANA.filter(([bit]) => player.Mana?.[bit]);
  root.hidden = pool.length === 0;
  reconcile<[number, string], HTMLButtonElement>(q(root, '.mana-chips'), pool, ([bit]) => bit,
    ([bit, sym]) => {
      const el = document.createElement('button');
      el.className = 'mana-chip';
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
      const name = MANA_NAMES[sym];
      el.title = own ? `${amount} ${name} mana floating. Click to pay with it; it empties when the step ends.`
        : `${amount} ${name} mana floating`;
    });
}

const MANA_NAMES: Record<string, string> = { W: 'white', U: 'blue', B: 'black', R: 'red', G: 'green', C: 'colourless' };

// An opponent's hand is cards held, not a pile, so it is drawn as a few backs fanned in the hand with the count.
// Your own hand is laid out along the bottom.
function renderHandFan(el: HTMLElement, model: Model, player: PlayerView): void {
  const count = zone(model, player, 'Hand').length;
  el.hidden = isLocal(model, player);
  el.dataset.count = String(Math.min(count, 3));
  q(el, '.hand-count').textContent = String(count);
  el.title = `${count} ${count === 1 ? 'card' : 'cards'} in hand`;
}

// Cards drift down into a graveyard and circle in exile, so the two piles read as places at a glance
const AMBIENT = '<span class="ambient" aria-hidden="true">' + '<i></i>'.repeat(6) + '</span>';

/** Decks some variants and cards bring, shown only while they hold something. Only the junkyard is face up. */
const EXTRA_ZONES: [ZoneType, string][] = [['PlanarDeck', 'Planes'], ['SchemeDeck', 'Schemes'],
  ['AttractionDeck', 'Attractions'], ['ContraptionDeck', 'Contraptions'], ['Junkyard', 'Junkyard']];

function renderZoneTiles(root: HTMLElement, model: Model, player: PlayerView): void {
  const zones: ZoneType[] = ['Library', 'Graveyard', 'Exile',
    ...EXTRA_ZONES.map(([z]) => z).filter(z => zone(model, player, z).length > 0)];
  reconcile<ZoneType, HTMLButtonElement>(root, zones, z => z,
    zoneName => {
      const el = document.createElement('button');
      el.className = 'zone-tile';
      el.dataset.zone = zoneName;
      el.innerHTML = '<img alt="" draggable="false">' + (zoneName === 'Library' ? '' : AMBIENT)
        + '<span class="zone-name"></span><span class="zone-count"></span>';
      q(el, '.zone-name').textContent = EXTRA_ZONES.find(([z]) => z === zoneName)?.[1] ?? zoneName;
      // A planar, scheme, attraction or contraption deck is face down, so only its count is shown
      el.classList.toggle('hidden-deck', zoneName !== 'Junkyard' && EXTRA_ZONES.some(([z]) => z === zoneName));
      const img = q<HTMLImageElement>(el, 'img');
      hideOnError(img);
      el.onclick = () => {
        if (!el.classList.contains('hidden-deck')) togglePile(Number(el.closest<HTMLElement>('.seat')?.dataset.player), zoneName);
      };
      // The hover data sits on the image: the tile's own data-key is how the render finds it again
      hoverable(el, img);
      return el;
    },
    (el, zoneName) => {
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
      q(el, '.zone-count').textContent = String(cards.length);
    });
}

// Whose turn it is, said once as the turn begins. The game's first turn says who goes first. A table first seen
// part way through a game (a reload) says nothing until the turn changes.
let announced: string | null = null;

/**
 * A new table: its first turn is announced afresh, even when the same player starts it as last game, and its
 * losses and its ending start over.
 */
export function resetTable(): void {
  announced = null;
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

/**
 * Who goes first, said over the board as the opening hands are dealt: every player's face, then the one who
 * starts lit in brass and the rest stepping back. The prompt says it too, but a sentence above a hand is easy to
 * read past.
 */
function revealFirst(model: Model, first: number, said?: string): void {
  const everyone = players(model);
  const starter = everyone.find(p => p.$key === first);
  if (!starter) return;
  document.getElementById('first-reveal')?.remove();
  const reveal = document.createElement('div');
  reveal.id = 'first-reveal';
  reveal.setAttribute('role', 'status');
  const faces = document.createElement('div');
  faces.className = 'reveal-faces';
  for (const p of everyone) {
    const face = document.createElement('div');
    face.className = p.$key === first ? 'reveal-face first' : 'reveal-face';
    const url = playerAvatarUrl(p);
    const picture = document.createElement(url ? 'img' : 'span');
    if (picture instanceof HTMLImageElement) {
      picture.alt = '';
      picture.src = url;
    } else {
      picture.textContent = (p.Name ?? '?').slice(0, 1).toUpperCase();
    }
    const name = document.createElement('span');
    name.className = 'reveal-name';
    name.textContent = p.Name ?? '';
    face.append(picture, name);
    faces.append(face);
  }
  const line = document.createElement('p');
  line.textContent = said ?? (isLocal(model, starter) ? 'You go first' : `${starter.Name} goes first`);
  reveal.append(faces, line);
  byId('match').append(reveal);
  reveal.addEventListener('animationend', e => {
    if (e.animationName === 'first-reveal') reveal.remove();
  });
}

function announceTurn(model: Model, g: GameView): void {
  const active = deref(model, g.PlayerTurn);
  const turn = `${g.Turn ?? 0}/${active?.$key ?? ''}`;
  if (!active || announced === turn) {
    return;
  }
  const seenBefore = announced !== null;
  announced = turn;
  const opening = g.Turn === 1;
  if (!seenBefore && !opening) {
    return;
  }
  const mine = isLocal(model, active);
  // With several opponents most turns are someone else's, and the phase pill already names whose it is
  if (!mine && players(model).length > 2) {
    return;
  }
  const banner = document.createElement('div');
  banner.className = `turn-banner${mine ? ' mine' : ''}`;
  banner.textContent = mine ? 'Your turn' : `${active.Name}'s turn`;
  // The banner takes the pill's place for as long as it shows, rather than covering it. The pill animates
  // its own width, so anything sized to cover it is measuring a number that is about to change.
  const strip = byId('phase-strip');
  strip.append(banner);
  strip.classList.add('announcing');
  // The pill returns as the banner starts to fade (84% of turn-sweep), so one fades in while the other fades out
  const sweep = parseFloat(getComputedStyle(banner).animationDuration) * 1000;
  setTimeout(() => strip.classList.remove('announcing'), sweep * 0.84);
  // The light that travels across the plate is an animation on the banner's own ::after, and its end reaches
  // the banner too, so the sweep has to be named or the banner leaves less than half way through
  banner.addEventListener('animationend', e => {
    if (e.animationName === 'turn-sweep') banner.remove();
  });
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
  el.classList.remove('hurt', 'healed');
  void el.offsetWidth;
  el.classList.add(hurt ? 'hurt' : 'healed');
  const float = document.createElement('span');
  float.className = `life-change ${hurt ? 'hurt' : 'healed'}`;
  float.textContent = `${hurt ? '' : '+'}${change}`;
  avatar.append(float);
  float.addEventListener('animationend', () => float.remove());
  if (hurt && local) {
    takeHit(-change);
  }
}

// Damage to your own life shakes the board and washes the edges, so it cannot be missed
function takeHit(amount: number): void {
  const match = byId('match');
  match.classList.remove('hit', 'hit-hard');
  void match.offsetWidth;
  match.classList.add(amount >= 5 ? 'hit-hard' : 'hit');
  match.addEventListener('animationend', () => match.classList.remove('hit', 'hit-hard'), { once: true });
  const flash = document.createElement('div');
  flash.className = 'hit-flash';
  document.body.append(flash);
  flash.addEventListener('animationend', () => flash.remove());
}

/**
 * What a commander costs beyond its printed cost: two generic for each time it has already been cast from here.
 * The word goes on the badge because a bare "+6" on a card reads as a counter or a pump long before it reads as
 * a tax, and this is a number a player meets only a few times in a game.
 */
function commanderTax(player: PlayerView | undefined, card: CardView): number {
  const cast = (player?.CommanderCast ?? []).find(c => c.card.ref === card.$key);
  return (cast?.value ?? 0) * 2;
}

// The command zone: the monarch, the initiative, emblems and commanders, shown as round tokens beside the player
function renderEmblems(root: HTMLElement, model: Model, player: PlayerView | undefined, cards: CardView[],
    select: CardClick): void {
  // Planes, schemes and the planar die have places of their own; the rest stay beside the portrait
  const shown = cards.filter(c => ['avatar', 'commander', 'signature', 'effect'].includes(commandKind(c, stateOf(model, c))));
  reconcile(root, shown, c => c.$key,
    card => {
      // A card the player reads or activates is drawn as a card; a reminder like the monarch stays a round token
      const tile = commandKind(card, stateOf(model, card)) !== 'effect';
      const el = document.createElement('div');
      el.className = tile ? 'cmd-tile' : 'emblem';
      el.innerHTML = tile ? '<img alt="" draggable="false"><span class="band"></span>'
        : '<img alt="" draggable="false"><span class="initials"></span><span class="tax"></span>';
      noImageOnError(el, q<HTMLImageElement>(el, 'img'));
      el.onclick = () => select(el, false);
      hoverable(el);
      return el;
    },
    (el, card) => {
      const state = stateOf(model, card);
      const src = cardImageSrc(model, card);
      setImage(q<HTMLImageElement>(el, 'img'), src);
      el.classList.toggle('noimg', !src);
      el.dataset.zoom = src;
      const tax = commanderTax(player, card);
      el.title = tax > 0 ? `${state.Name ?? ''} — costs ${tax} more to cast from here` : state.Name ?? '';
      el.classList.toggle('selectable', (model.prompt?.selectable ?? []).some(r => r.ref === card.$key));
      if (el.classList.contains('cmd-tile')) {
        q(el, '.band').textContent = tileBand(commandKind(card, state), state, tax);
        return;
      }
      const words = (state.Name ?? '').replace(/^(The|Emblem) /, '').split(/[\s-]+/).filter(w => /^\w/.test(w));
      q(el, '.initials').textContent = words.map(w => w[0]).join('').slice(0, 2).toUpperCase();
      q(el, '.tax').textContent = tax > 0 ? `Tax +${tax}` : '';
    });
}

/** The one number a command tile carries: an avatar's modifiers, a commander's tax, or what a signature spell is. */
function tileBand(kind: CommandKind, state: Partial<CardStateView>, tax: number): string {
  if (kind === 'avatar') {
    const mods = avatarModifiers(state.RulesText);
    const signed = (n: number) => (n < 0 ? `−${-n}` : `+${n}`);
    return mods ? `hand ${signed(mods[0])} · life ${signed(mods[1])}` : '';
  }
  if (kind === 'signature') return 'Signature';
  return tax > 0 ? `Tax +${tax}` : '';
}

/** Players whose portrait has broken this game, so each breaks once. Null until the table is first drawn. */
let broken: Set<number> | null = null;
/** False while a portrait breaking at the end of the game has not reached the moment its title follows. */
let titleReady = true;
let finalRunning = false;

/**
 * A player who has just lost has their portrait broken. When that ends the game it breaks in the middle of the
 * board and the title follows it; in a game of three or more that carries on, it breaks where they sit. A table
 * first seen part way through (a reload) takes the losses it already had as broken.
 */
function noticeLosses(model: Model, actions: Actions): void {
  const lost = players(model).filter(p => p.HasLost);
  if (!broken) {
    broken = new Set(lost.map(p => p.$key));
    return;
  }
  const fresh = lost.filter(p => !broken!.has(p.$key));
  if (!fresh.length) return;
  fresh.forEach(p => broken!.add(p.$key));
  // The loss and the end of the game can arrive a message apart, so the title waits and the ending is decided a
  // moment later
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
  const centre = boardCentre();
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

/** The middle of the two seats' boards, where a portrait breaks at the end of the game. */
function boardCentre(): { x: number; y: number } {
  const a = byId('opponent').getBoundingClientRect(), b = byId('me').getBoundingClientRect();
  return { x: (Math.min(a.left, b.left) + Math.max(a.right, b.right)) / 2, y: (a.top + b.bottom) / 2 };
}

/**
 * The end of a game: the board recedes, and once the losing portrait has broken the result is said over it.
 * Drawn once per ending, so what fades in does so once.
 */
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
  if (!root.hidden) return;
  root.hidden = false;
  const matchOver = !!g.MatchOver;
  const everyone = players(model);
  const winner = everyone.find(p => p.Name === g.WinningPlayerName);
  const losers = everyone.filter(p => p.HasLost);
  const seated = everyone.some(p => isLocal(model, p));
  const won = !!winner && isLocal(model, winner);
  const outcome = !winner ? 'draw' : !seated ? 'watched' : won ? 'win' : 'lose';
  const word = { draw: 'Draw', watched: 'Game over', win: 'Victory', lose: 'Defeat' }[outcome];
  const sub = !winner ? 'Nobody wins'
    : won && everyone.length === 2 && losers.length === 1 ? `${losers[0].Name} has lost`
    : won ? 'Last one standing'
    : `${winner.Name} wins`;
  root.innerHTML = '<div class="panel"><div class="face"></div><p class="word"></p><div class="rule"></div><p class="sub"></p><div class="actions"></div></div>'
    + '<button class="to-result">Show result</button>';
  const view = (board: boolean) => {
    root.classList.toggle('viewing', board);
    byId('match').classList.toggle('ending', !board);
  };
  q(root, '.to-result').onclick = () => view(false);
  const panel = q(root, '.panel');
  panel.classList.add(outcome);
  const face = q(root, '.face');
  if (winner) face.style.backgroundImage = cssUrl(playerAvatarUrl(winner));
  face.hidden = !winner;
  q(root, '.word').textContent = word;
  q(root, '.sub').textContent = sub;
  const buttons = q(root, '.actions');
  const add = (label: string, primary: boolean, onClick: () => void) => {
    const b = document.createElement('button');
    b.textContent = label;
    if (primary) b.className = 'primary';
    b.onclick = onClick;
    buttons.append(b);
  };
  if (!matchOver) add('Next game', true, () => actions.nextGame());
  add('View battlefield', false, () => view(true));
  add(matchOver ? 'Back to start' : 'Quit match', matchOver, () => {
    if (!matchOver) actions.quitMatch();
    actions.leave();
  });
}
