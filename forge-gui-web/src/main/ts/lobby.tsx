// Match setup. Each seat is a plate anchored by its deck's sleeve. The sleeve stands for the deck, so it is
// the largest thing on the plate and clicking it chooses the deck, empty or not. Which sleeve to wear is a
// property of a deck you already have, so it hangs off a small button in the corner of a filled one.
// Seats are added, never presented as empty slots waiting to be filled.
//
// A seat's type is the one a netplay lobby slot carries. The browser reaches the game through a client even
// when it hosts it, so your own seat arrives as REMOTE and is recognised by its "mine" flag, not its type.

import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { changeUi, ui, type Picker } from './ui';
import { sleeveUrl, avatarUrl } from './looks';
import { LookPicker } from './lookpicker';
import { DeckFinder } from './deckfinder';
import { SleevePicker, artUrl, objectPosition } from './sleeves';
import { Pips } from './symbols';
import type { Actions } from './actions';
import type { Model } from './model';
import type { Address, DeckSummary, Format, LobbyTable, Seat } from './protocol';

export function Lobby({ model, actions }: { model: Model; actions: Actions }) {
  const picker = ui.picker;
  const lobby = model.lobby;
  const [guide, setGuide] = useState(false);
  if (!lobby) {
    return null;
  }
  // A seat can go while its picker is open, when the host removes it
  const seat = picker ? lobby.seats[picker.seat] : undefined;
  const close = () => changeUi(u => { u.picker = null; });
  const sentence = matchSentence(lobby);
  return (
    <>
      <header class="lobby-head">
        <span class="wordmark">Forge</span>
        <div class="formats">
          {groupsOf(lobby.formats).map(([group, formats]) => (
            <div key={group} class="format-group">
              {formats.map(f => (
                <FormatChip key={f.id} format={f} pressed={f.id === lobby.format} host={lobby.host}
                  label={f.id === 'Constructed' ? constructedName(lobby) : f.name}
                  choose={() => actions.setFormat(f.id)}>
                  {f.id === 'Constructed' && <ConstructedMenu lobby={lobby} actions={actions} />}
                </FormatChip>
              ))}
            </div>
          ))}
          <button class="guide-link" onClick={() => setGuide(true)}>What are these?</button>
        </div>
        <div class="head-right">
          <label class="spectate" hidden={!lobby.host}>
            <input type="checkbox" checked={ui.spectate}
              onChange={e => { const on = e.currentTarget.checked; changeUi(u => { u.spectate = on; }); }} /> Watch the computer play
          </label>
          {/* The table belongs to the host, so a joined client has no menu to go back to */}
          <button hidden={!lobby.host} onClick={() => actions.leaveLobby()}>Back</button>
        </div>
      </header>
      <p class="match-sentence"><b>{sentence.title}.</b> {sentence.text}</p>
      {guide && <Guide lobby={lobby} choose={id => actions.setFormat(id)} close={() => setGuide(false)} />}
      <div class="lobby-main">
        <div class="seats" id="seats" data-count={lobby.seats.length}>
          {lobby.seats.map((s, i) => <Plate key={i} seat={s} index={i} lobby={lobby} actions={actions}
            choose={kind => changeUi(u => { u.picker = { kind, seat: i }; })} random={() => randomDeck(model, actions, i)} />)}
        </div>
        <div class="seat-add">
          <button hidden={lobby.seats.length >= lobby.maxSeats} disabled={!lobby.host}
            onClick={() => actions.addSeat()}>+ Add a seat</button>
        </div>
        <Verdict lobby={lobby} start={() => actions.startMatch(ui.spectate)} />
        {/* The conversation moved to the dock, which follows you in; only the links belong to the table */}
        <div class="lobby-net" hidden={!lobby.shareable}>
          <section class="share">
            <h3>Others join at</h3>
            <div class="share-list">
              {lobby.shareable && <Addresses list={model.addresses ?? []} />}
            </div>
          </section>
        </div>
      </div>
      {seat && picker?.kind === 'deck' && <DeckFinder model={model} actions={actions} index={picker.seat} seat={seat} close={close} />}
      {seat && picker?.kind === 'sleeve' && <SleevePicker model={model} actions={actions} index={picker.seat} seat={seat} close={close} />}
      {seat && picker?.kind === 'avatar' && (
        <LookPicker title={`Choose an avatar for ${seat.name}`} count={model.looks?.avatarCount ?? 0} urlOf={avatarUrl}
          current={seat.avatar} close={chosen => {
            if (chosen !== null) actions.setSeat(picker.seat, { avatar: chosen });
            close();
          }} />
      )}
    </>
  );
}

function Addresses({ list }: { list: Address[] }) {
  const [copied, setCopied] = useState<string | null>(null);
  if (!list.length) {
    return <>Working out your address…</>;
  }
  return <>{list.map(a => (
    <button key={a.url} class="share-row" onClick={async () => {
      await navigator.clipboard.writeText(a.url);
      setCopied(a.url);
    }}>
      <span class="share-label">{a.label}</span><code class="share-url">{a.url}</code>
      <span class="share-copy">{copied === a.url ? 'Copied' : 'Copy'}</span>
    </button>
  ))}</>;
}

// The seat kinds a netplay lobby can hold; offline shows only the first two
const KIND: Record<string, string> = { LOCAL: 'You', AI: 'Computer', OPEN: 'Open seat', REMOTE: 'Another player' };

/** Constructed with the format that limits its cards, as the chip and the sentence both say it. */
function constructedName(lobby: LobbyTable): string {
  return `Constructed · ${lobby.cardPool ?? 'any cards'}`;
}

/** The line under the header: the format, then what it is, in the engine's words. */
export function matchSentence(lobby: LobbyTable): { title: string; text: string } {
  const format = lobby.formats.find(f => f.id === lobby.format);
  const name = lobby.format === 'Constructed' ? constructedName(lobby) : format?.name ?? lobby.format;
  return { title: name, text: format?.desc ?? '' };
}

/** Formats under their group, in the order the server lists them. */
function groupsOf(formats: Format[]): [string, Format[]][] {
  const groups = new Map<string, Format[]>();
  for (const f of formats) groups.set(f.group, [...(groups.get(f.group) ?? []), f]);
  return [...groups];
}

/**
 * The Constructed formats behind the chip's caret. Choosing one also switches to Constructed, since a card pool
 * belongs to Constructed alone.
 */
/** The caret menu's placeholder: no Constructed format, while another format is chosen. */
const NO_POOL_CHOSEN = '-';

/** The caret menu's value. Under another format it shows no entry, so choosing any one of them, "Any cards" included, is a change. */
export function poolMenuValue(lobby: LobbyTable): string {
  return lobby.format === 'Constructed' ? lobby.cardPool ?? '' : NO_POOL_CHOSEN;
}


function ConstructedMenu({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  return (
    <select class="format-pool" aria-label="Constructed format" disabled={!lobby.host}
      value={poolMenuValue(lobby)}
      onChange={e => {
        const pool = e.currentTarget.value || null;
        if (lobby.format !== 'Constructed') actions.setFormat('Constructed');
        actions.setCardPool(pool);
      }}>
      <option value={NO_POOL_CHOSEN} disabled hidden>Choose a Constructed format</option>
      <option value="">Any cards</option>
      {lobby.cardPools.map(g => (
        <optgroup key={g.name} label={g.name}>
          {g.formats.map(f => <option key={f} value={f}>{f}</option>)}
        </optgroup>
      ))}
    </select>
  );
}

/** How long a pointer rests on a format, or a finger presses one, before its card opens. */
const CARD_REST_MS = 400;
const CARD_PRESS_MS = 500;

/**
 * A format chip that explains itself. The card opens on a resting pointer, on keyboard focus, or on a long press,
 * so it is never behind a hover alone; a long press opens the card instead of choosing the format.
 */
function FormatChip({ format, label, pressed, host, choose, children }: {
  format: Format; label: string; pressed: boolean; host: boolean; choose: () => void; children?: ComponentChildren;
}) {
  const [open, setOpen] = useState(false);
  const timer = useRef<number>(0);
  const pressedLong = useRef(false);
  const later = (ms: number, then: () => void) => { clearTimeout(timer.current); timer.current = window.setTimeout(then, ms); };
  const shut = () => { clearTimeout(timer.current); setOpen(false); };
  const chip = useRef<HTMLSpanElement>(null);
  useEffect(() => () => clearTimeout(timer.current), []);
  // An open card goes on Escape or a press anywhere else, however it was opened
  useEffect(() => {
    if (!open) return;
    const outside = (e: PointerEvent) => { if (!chip.current?.contains(e.target as Node)) shut(); };
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') shut(); };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);
  // Resting opens the card for a mouse only: a touch screen fires the same events for every tap
  return (
    <span ref={chip} class={`format-chip${children ? ' split' : ''}`}
      onPointerEnter={e => { if (e.pointerType === 'mouse') later(CARD_REST_MS, () => setOpen(true)); }}
      onPointerLeave={e => { if (e.pointerType === 'mouse') shut(); }}>
      <button class="format" aria-pressed={pressed} disabled={!host} aria-describedby={open ? `card-${format.id}` : undefined}
        onFocus={e => { if (e.currentTarget.matches(':focus-visible')) setOpen(true); }} onBlur={shut}
        onPointerDown={e => {
          if (e.pointerType !== 'touch') return;
          pressedLong.current = false;
          later(CARD_PRESS_MS, () => { pressedLong.current = true; setOpen(true); });
        }}
        onPointerUp={() => { if (!pressedLong.current) clearTimeout(timer.current); }}
        onClick={() => { if (pressedLong.current) { pressedLong.current = false; return; } choose(); }}>{label}</button>
      {children}
      {/* A disabled button takes no focus or pointer, so a guest reads the card by resting on the chip's wrapper */}
      {open && <FormatCard id={`card-${format.id}`} format={format} />}
    </span>
  );
}

function FormatCard({ id, format }: { id?: string; format: Format }) {
  return (
    <div class="format-card" id={id} role="tooltip">
      <h5>{format.name}</h5>
      <p class="desc">{format.desc}</p>
      <div class="facts">{format.facts.map(f => <span key={f} class="fact">{f}</span>)}</div>
      <p class="format-play"><b>In a match:</b> {format.play}</p>
    </div>
  );
}

/** Every format side by side, each with its own Choose, so a player can read and pick in one place. */
function Guide({ lobby, choose, close }: { lobby: LobbyTable; choose: (id: string) => void; close: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return (
    <div class="guide-back" onClick={e => { if (e.target === e.currentTarget) close(); }}>
      <aside class="guide" aria-label="Formats">
        <header>
          <h2>Formats</h2>
          <p>Each is a different way to build a deck and play.</p>
          <button class="guide-close" title="Close" onClick={close}>&times;</button>
        </header>
        {groupsOf(lobby.formats).map(([group, formats]) => [
          <h3 key={group} class="guide-group">{group}</h3>,
          ...formats.map(f => (
          <div key={f.id} class={`guide-item${f.id === lobby.format ? ' on' : ''}`}>
            <div class="guide-name">{f.id === lobby.format && <small>Chosen</small>}{f.name}</div>
            <div>
              <p class="desc">{f.desc}</p>
              <div class="facts">{f.facts.map(x => <span key={x} class="fact">{x}</span>)}</div>
              <p class="format-play"><b>In a match:</b> {f.play}</p>
            </div>
            {lobby.host && f.id !== lobby.format && <button class="guide-choose" onClick={() => choose(f.id)}>Choose</button>}
          </div>
          )),
        ])}
      </aside>
    </div>
  );
}

/** Decks a computer seat may be dealt at random: any the lobby would accept, generators included. */
export function randomPool(decks: readonly DeckSummary[]): DeckSummary[] {
  return decks.filter(d => !d.problem);
}

/** A computer seat given a deck legal here, so a table fills without a trip to the chooser each. */
function randomDeck(model: Model, actions: Actions, index: number): void {
  const pool = randomPool(model.decks ?? []);
  if (pool.length) actions.setSeat(index, { deck: pool[Math.floor(Math.random() * pool.length)].key });
}

function Plate({ seat, index, lobby, actions, choose, random }: {
  seat: Seat; index: number; lobby: LobbyTable; actions: Actions; choose: (kind: Picker['kind']) => void; random: () => void;
}) {
  // Your own seat is the one the server dealt you, whatever type it wears on the host's side
  const mine = seat.mine;
  const waiting = seat.type === 'OPEN';
  // Another player's deck comes from their own catalog, so it has a name here but no key
  const hasDeck = seat.deck != null || seat.deckName != null;
  // Momir Basic and MoJhoSto deal every seat its deck at the start, so there is none to choose
  const format = lobby.formats.find(f => f.id === lobby.format);
  const dealt = format?.group === 'Other';
  // The host turns a seat between a computer and one someone can join; everyone else only reads it
  const swappable = lobby.host && !mine && (seat.type === 'AI' || seat.type === 'OPEN');
  // A deck's own card art wins over the numbered sleeve, exactly as it does in a match
  const sleeveSrc = seat.sleeveArt ? artUrl(seat.sleeveArt) : sleeveUrl(seat.sleeve);
  return (
    <div class={`plate${mine ? ' mine' : ''}${waiting ? ' waiting' : ''}`}>
      <div class="sleeve-slot">
        {/* Nothing is sleeved until a deck is chosen, so the slot stands empty rather than showing a sleeve */}
        <button class={`sleeve${hasDeck || dealt ? '' : ' empty'}${seat.sleeveArt ? ' card-art' : ''}`} title={dealt ? '' : 'Choose a deck'}
          data-label={seat.mayEdit ? 'Choose a deck' : (waiting ? '' : 'No deck')}
          disabled={!seat.mayEdit || dealt} onClick={() => choose('deck')}>
          <img alt="" hidden={!hasDeck && !dealt} src={hasDeck || dealt ? sleeveSrc : undefined}
            style={{ objectPosition: objectPosition(seat.sleeveOffset) }} />
        </button>
        {/* A sleeve is worn by a deck, so there is nothing to choose until there is one */}
        <button class="sleeve-style" title="Choose a sleeve" hidden={!hasDeck || !seat.mayEdit}
          onClick={() => choose('sleeve')}>Sleeve</button>
      </div>
      <div class="plate-body">
        <div class="who">
          {/* A seat nobody has taken has no face to show */}
          <button class="avatar" title="Choose an avatar" hidden={waiting} disabled={!seat.mayEdit}
            onClick={() => choose('avatar')}><img alt="" src={avatarUrl(seat.avatar)} /></button>
          <SeatName seat={seat} rename={name => actions.setSeat(index, { name })} />
          <button class="kind" disabled={!swappable} title={swappable ? 'Swap between a computer and an open seat' : ''}
            onClick={() => (seat.type === 'AI' ? actions.openSeat(index) : actions.aiSeat(index))}>
            {mine ? KIND.LOCAL : (KIND[seat.type] ?? seat.type)}
          </button>
          <button class="random-deck" title="Give this seat a random deck" aria-label="Random deck"
            hidden={seat.type !== 'AI' || !lobby.host || dealt} onClick={random}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="4" /><circle cx="8.5" cy="8.5" r="1.2" /><circle cx="15.5" cy="15.5" r="1.2" /><circle cx="12" cy="12" r="1.2" /><circle cx="15.5" cy="8.5" r="1.2" /><circle cx="8.5" cy="15.5" r="1.2" /></svg>
          </button>
          <button class="drop" title="Remove this seat" hidden={mine || !lobby.host || lobby.seats.length <= 2}
            onClick={() => actions.removeSeat(index)}>&times;</button>
        </div>
        {dealt && !waiting && <p class="deck-row fixed">{format?.facts[0]}</p>}
        {/* With no deck the sleeve above already offers to choose one, so an empty row would only repeat it */}
        <button class={`deck-row${hasDeck ? '' : ' unset'}`} hidden={dealt || (!hasDeck && !waiting)} disabled={!seat.mayEdit}
          onClick={() => choose('deck')}>
          <span class="pips"><Pips colors={seat.colors} /></span>
          <span class="deck-name">{seat.deckName ?? (waiting ? 'Waiting for a player' : '')}</span>
          <span class="deck-size">{hasDeck ? String(seat.deckSize) : ''}</span>
        </button>
        <p class="seat-problem" hidden={!seat.problem || !hasDeck}>{seat.problem ?? ''}</p>
      </div>
    </div>
  );
}

// A seat nobody holds has no name of its own, and its kind beside it would only say the same thing twice.
// Your own name, and the computer's at a table you host, is edited in place and saved when you leave it.
function SeatName({ seat, rename }: { seat: Seat; rename: (name: string) => void }) {
  // Typing edits the page, not what Preact drew, so each edit ends by drawing the field afresh: it then shows
  // the name as the server has it, the new one if the server takes it and this one if not
  const [edits, setEdits] = useState(0);
  const name = seat.name || KIND[seat.type] || seat.type;
  if (!seat.mine && !(seat.mayEdit && seat.type === 'AI')) {
    return <span class="who-name" hidden={!seat.name}>{name}</span>;
  }
  return (
    <span key={edits} class="who-name" contentEditable="plaintext-only" spellcheck={false}
      onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); e.currentTarget.blur(); } }}
      onBlur={e => {
        const typed = (e.currentTarget.textContent ?? '').trim();
        setEdits(n => n + 1);
        if (typed && typed !== name) rename(typed);
      }}>{name}</span>
  );
}

function Verdict({ lobby, start }: { lobby: LobbyTable; start: () => void }) {
  const problems = lobby.problems ?? [];
  // Only the host can start, so a joined client is told what it is waiting for rather than shown a dead button
  if (!lobby.host) {
    return (
      <div class="play-row">
        <p class="match-line">{problems.length ? problems[0] : 'Waiting for the host to start the match.'}</p>
      </div>
    );
  }
  const rules = matchSentence(lobby).title;
  return (
    <div class="play-row">
      <button id="play" class="primary play" disabled={!lobby.canStart} onClick={start}>Play</button>
      <p class="match-line" hidden={!lobby.canStart}>
        {`${rules} · ${lobby.seats.length} players · Enter starts the match.`}
      </p>
      <div class="not-yet" hidden={lobby.canStart}>
        <b>Not playable yet</b>
        <ul>{problems.map(p => <li key={p}>{p}</li>)}</ul>
      </div>
    </div>
  );
}
