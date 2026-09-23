import { reconcile } from './render';
import { cardImageSrc, hideOnError, noImageOnError, setImage, symbolUrl } from './images';
import { game, me, opponents, players, zone, deref, stateOf, isLocal, type Model } from './model';
import { renderHand } from './hand';
import { renderZones, togglePile } from './zones';
import { renderBattlefield } from './battlefield';
import { hoverPlayer, hoverable } from './detail';
import { renderStack } from './stack';
import { renderPhaseBar } from './phasebar';
import { playerAvatarUrl, playerSleeveUrl, cssUrl, ROBOT_ICON } from './looks';
import { animateCardMoves } from './motion';
import { byId, q } from './dom';
import type { CardClick } from './cards';
import type { Actions } from './actions';
import type { CardView, GameEvent, GameView, PlayerView, ZoneName } from './protocol';

// The Mana property counts the pool by Forge's mana bit (ManaAtom): the five colours as MagicColor has them, and colourless its own bit
const MANA: [number, string][] = [[1, 'W'], [2, 'U'], [4, 'B'], [8, 'R'], [16, 'G'], [32, 'C']];

export function renderMatch(model: Model, actions: Actions, events: readonly GameEvent[]): void {
  const g = game(model);
  if (!g) return;
  // The click position travels with the click, so an ability list opens on the card as desktop's menu does
  const select: CardClick = (el, menu, e) => actions.selectCard(Number(el.dataset.key), menu, e?.clientX ?? 0, e?.clientY ?? 0);
  // Attachments can cross players (an aura on an opponent's creature), so slots are built from every battlefield
  const onField = players(model).flatMap(p => zone(model, p, 'Battlefield'));
  renderSeat(byId('opponent'), model, opponents(model)[0], onField, actions, select);
  renderSeat(byId('me'), model, me(model), onField, actions, select);
  announceTurn(model, g);
  renderPhaseBar(model, g, actions);
  renderStack(model);
  renderHand(model, me(model), select);
  renderZones(model, select);
  renderGameOver(model, g, actions);
  animateCardMoves(model, events);
}

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
        <div class="avatar"><img class="portrait" alt="" draggable="false"><span class="initial"></span><span class="ai-badge" title="Computer player">${ROBOT_ICON}</span><span class="life"></span></div>
        <div class="name"></div>
        <button class="hand-fan" hidden><span class="backs"><i></i><i></i><i></i></span><span class="hand-count"></span></button>
        <div class="player-counters"></div>
        <div class="emblems"></div>
        <div class="zone-tiles"></div>
        <div class="mana" hidden><span class="mana-label">Floating mana</span><div class="mana-chips"></div></div>
      </div>
      <div class="battlefield"><div class="row lands"></div><div class="row permanents"></div></div>`;
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
  q(root, '.name').textContent = player.Name ?? '';
  avatar.classList.toggle('highlighted', (model.prompt?.highlighted ?? []).includes(player.$key));
  avatar.classList.toggle('selectable', (model.prompt?.selectablePlayers ?? []).some(r => r.ref === player.$key));
  avatar.classList.toggle('active', game(model)?.PlayerTurn?.ref === player.$key);
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
  renderEmblems(q(root, '.emblems'), model, zone(model, player, 'Command'), select);
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

function renderZoneTiles(root: HTMLElement, model: Model, player: PlayerView): void {
  const zones: ZoneName[] = ['Library', 'Graveyard', 'Exile'];
  reconcile<ZoneName, HTMLButtonElement>(root, zones, z => z,
    zoneName => {
      const el = document.createElement('button');
      el.className = 'zone-tile';
      el.dataset.zone = zoneName;
      el.innerHTML = '<img alt="" draggable="false">' + (zoneName === 'Library' ? '' : AMBIENT)
        + '<span class="zone-name"></span><span class="zone-count"></span>';
      q(el, '.zone-name').textContent = zoneName;
      const img = q<HTMLImageElement>(el, 'img');
      hideOnError(img);
      el.onclick = () => togglePile(Number(el.closest<HTMLElement>('.seat')?.dataset.player), zoneName);
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

// Whose turn it is, said once as the turn begins
let announced: string | null = null;

function announceTurn(model: Model, g: GameView): void {
  const active = deref(model, g.PlayerTurn);
  const turn = `${g.Turn ?? 0}/${active?.$key ?? ''}`;
  if (!active || announced === turn) {
    return;
  }
  const first = announced === null;
  announced = turn;
  if (first) {
    return;
  }
  const banner = document.createElement('div');
  banner.className = `turn-banner${isLocal(model, active) ? ' mine' : ''}`;
  banner.textContent = isLocal(model, active) ? 'Your turn' : `${active.Name}'s turn`;
  // The banner takes the pill's place for as long as it shows, rather than covering it. The pill animates
  // its own width, so anything sized to cover it is measuring a number that is about to change.
  const strip = byId('phase-strip');
  strip.append(banner);
  strip.classList.add('announcing');
  banner.addEventListener('animationend', () => {
    banner.remove();
    strip.classList.remove('announcing');
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

// The command zone: the monarch, the initiative, emblems and commanders, shown as round tokens beside the player
function renderEmblems(root: HTMLElement, model: Model, cards: CardView[], select: CardClick): void {
  reconcile(root, cards, c => c.$key,
    () => {
      const el = document.createElement('div');
      el.className = 'emblem';
      el.innerHTML = '<img alt="" draggable="false"><span class="initials"></span>';
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
      el.title = state.Name ?? '';
      const words = (state.Name ?? '').replace(/^(The|Emblem) /, '').split(/[\s-]+/).filter(w => /^\w/.test(w));
      q(el, '.initials').textContent = words.map(w => w[0]).join('').slice(0, 2).toUpperCase();
      el.classList.toggle('selectable', (model.prompt?.selectable ?? []).some(r => r.ref === card.$key));
    });
}

function renderGameOver(model: Model, g: GameView, actions: Actions): void {
  const root = byId('game-over');
  root.hidden = !model.gameOver;
  if (!model.gameOver) return;
  const matchOver = !!g.MatchOver;
  root.innerHTML = '<div class="panel"><h2></h2><div class="actions"></div></div>';
  q(root, 'h2').textContent = g.WinningPlayerName ? `${g.WinningPlayerName} wins` : 'Game over';
  const buttons = q(root, '.actions');
  const add = (label: string, primary: boolean, onClick: () => void) => {
    const b = document.createElement('button');
    b.textContent = label;
    if (primary) b.className = 'primary';
    b.onclick = onClick;
    buttons.append(b);
  };
  if (!matchOver) add('Next game', true, () => actions.nextGame());
  add(matchOver ? 'Back to start' : 'Quit match', matchOver, () => {
    if (!matchOver) actions.quitMatch();
    actions.leave();
  });
}
