// Match setup. Each seat is a plate anchored by its deck's sleeve. The sleeve stands for the deck, so it is
// the largest thing on the plate and clicking it chooses the deck, empty or not. Which sleeve to wear is a
// property of a deck you already have, so it hangs off a small button in the corner of a filled one.
// Seats are added, never presented as empty slots waiting to be filled.
//
// A seat's type is the one a netplay lobby slot carries. The browser reaches the game through a client even
// when it hosts it, so your own seat arrives as REMOTE and is recognised by its "mine" flag, not its type.

import { useState } from 'preact/hooks';
import { sleeveUrl, avatarUrl } from './looks';
import { LookPicker } from './lookpicker';
import { DeckFinder } from './deckfinder';
import { SleevePicker, artUrl, objectPosition } from './sleeves';
import { startMusic } from './audio';
import { ChatInput, ChatLog } from './chat';
import { Pips } from './symbols';
import type { Actions } from './actions';
import type { Model } from './model';
import type { Address, LobbyTable, Seat } from './protocol';

/** A picker open over the table, for one seat. */
type Picker = { kind: 'deck' | 'sleeve' | 'avatar'; index: number };

export function Lobby({ model, actions }: { model: Model; actions: Actions }) {
  const [picker, setPicker] = useState<Picker | null>(null);
  const [spectate, setSpectate] = useState(false);
  const lobby = model.lobby;
  if (!lobby) {
    return null;
  }
  // A seat can go while its picker is open, when the host removes it
  const seat = picker ? lobby.seats[picker.index] : undefined;
  const close = () => setPicker(null);
  return (
    <>
      <header class="lobby-head">
        <span class="wordmark">Forge</span>
        <div class="formats" id="formats">
          {lobby.formats.map(f => (
            <button key={f.id} class="format" data-format={f.id} aria-pressed={f.id === lobby.format}
              disabled={!lobby.host} onClick={() => actions.setFormat(f.id)}>{f.name}</button>
          ))}
        </div>
        <div class="head-right">
          <label class="spectate" hidden={!lobby.host}>
            <input id="spectate" type="checkbox" checked={spectate} onChange={e => setSpectate(e.currentTarget.checked)} /> Watch the computer play
          </label>
          {/* The table belongs to the host, so a joined client has no menu to go back to */}
          <button id="lobby-back" hidden={!lobby.host} onClick={() => actions.leaveLobby()}>Back</button>
        </div>
      </header>
      <div class="lobby-main">
        <div class="seats" id="seats" data-count={lobby.seats.length}>
          {lobby.seats.map((s, i) => <Plate key={i} seat={s} index={i} lobby={lobby} actions={actions}
            choose={kind => setPicker({ kind, index: i })} />)}
        </div>
        <div class="seat-add">
          <button id="add-seat" hidden={lobby.seats.length >= lobby.maxSeats} disabled={!lobby.host}
            onClick={() => actions.addSeat()}>+ Add a seat</button>
        </div>
        <Verdict lobby={lobby} start={() => {
          // A browser plays nothing before a click, so the music starts on this one
          startMusic();
          actions.startMatch(spectate);
        }} />
        {/* A game only this machine can reach has nothing to share and nobody to talk to */}
        <div class="lobby-net" id="lobby-net" hidden={!lobby.shareable && lobby.host}>
          <section class="share" id="share" hidden={!lobby.shareable}>
            <h3>Others join at</h3>
            <div class="share-list" id="share-list">
              {lobby.shareable && <Addresses list={model.addresses ?? []} />}
            </div>
          </section>
          <section class="chat">
            <ChatLog model={model} class="chat-log" id="lobby-chat-log" />
            <ChatInput id="lobby-chat-in" say={actions.say} />
          </section>
        </div>
      </div>
      {seat && picker?.kind === 'deck' && <DeckFinder model={model} actions={actions} index={picker.index} seat={seat} close={close} />}
      {seat && picker?.kind === 'sleeve' && <SleevePicker model={model} actions={actions} index={picker.index} seat={seat} close={close} />}
      {seat && picker?.kind === 'avatar' && (
        <LookPicker title={`Choose an avatar for ${seat.name}`} count={model.looks?.avatarCount ?? 0} urlOf={avatarUrl}
          current={seat.avatar} close={chosen => {
            if (chosen !== null) actions.setSeat(picker.index, { avatar: chosen });
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

function Plate({ seat, index, lobby, actions, choose }: {
  seat: Seat; index: number; lobby: LobbyTable; actions: Actions; choose: (kind: Picker['kind']) => void;
}) {
  // Your own seat is the one the server dealt you, whatever type it wears on the host's side
  const mine = seat.mine;
  const waiting = seat.type === 'OPEN';
  // Another player's deck comes from their own catalog, so it has a name here but no key
  const hasDeck = seat.deck != null || seat.deckName != null;
  // The host turns a seat between a computer and one someone can join; everyone else only reads it
  const swappable = lobby.host && !mine && (seat.type === 'AI' || seat.type === 'OPEN');
  // A deck's own card art wins over the numbered sleeve, exactly as it does in a match
  const sleeveSrc = seat.sleeveArt ? artUrl(seat.sleeveArt) : sleeveUrl(seat.sleeve);
  return (
    <div class={`plate${mine ? ' mine' : ''}${waiting ? ' waiting' : ''}`}>
      <div class="sleeve-slot">
        {/* Nothing is sleeved until a deck is chosen, so the slot stands empty rather than showing a sleeve */}
        <button class={`sleeve${hasDeck ? '' : ' empty'}${seat.sleeveArt ? ' card-art' : ''}`} title="Choose a deck"
          data-label={seat.mayEdit ? 'Choose a deck' : (waiting ? '' : 'No deck')}
          disabled={!seat.mayEdit} onClick={() => choose('deck')}>
          <img alt="" hidden={!hasDeck} src={hasDeck ? sleeveSrc : undefined}
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
          <button class="drop" title="Remove this seat" hidden={mine || !lobby.host || lobby.seats.length <= 2}
            onClick={() => actions.removeSeat(index)}>&times;</button>
        </div>
        {/* With no deck the sleeve above already offers to choose one, so an empty row would only repeat it */}
        <button class={`deck-row${hasDeck ? '' : ' unset'}`} hidden={!hasDeck && !waiting} disabled={!seat.mayEdit}
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
// Your own name is edited in place and saved when you leave it.
function SeatName({ seat, rename }: { seat: Seat; rename: (name: string) => void }) {
  // Typing edits the page, not what Preact drew, so each edit ends by drawing the field afresh: it then shows
  // the name as the server has it, the new one if the server takes it and this one if not
  const [edits, setEdits] = useState(0);
  const name = seat.name || KIND[seat.type] || seat.type;
  if (!seat.mine) {
    return <span class="who-name" hidden={!seat.name}>{name}</span>;
  }
  return (
    <span key={edits} class="who-name" contentEditable="plaintext-only" spellcheck={false}
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
        <p class="match-line" id="match-line">{problems.length ? problems[0] : 'Waiting for the host to start the match.'}</p>
      </div>
    );
  }
  const format = lobby.formats.find(f => f.id === lobby.format)?.name ?? lobby.format;
  return (
    <div class="play-row">
      <button id="play" class="primary play" disabled={!lobby.canStart} onClick={start}>Play</button>
      <p class="match-line" id="match-line" hidden={!lobby.canStart}>
        {`${format} · ${lobby.seats.length} players · Enter starts the match.`}
      </p>
      <div class="not-yet" id="not-yet" hidden={lobby.canStart}>
        <b>Not playable yet</b>
        <ul id="problems">{problems.map(p => <li key={p}>{p}</li>)}</ul>
      </div>
    </div>
  );
}
