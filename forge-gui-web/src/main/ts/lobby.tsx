// Match setup. Each seat is a plate anchored by its deck's sleeve. The sleeve stands for the deck, so it is
// the largest thing on the plate and clicking it chooses the deck, empty or not. Which sleeve to wear is a
// property of a deck you already have, so it hangs off a small button in the corner of a filled one.
// Seats are added, never presented as empty slots waiting to be filled.
//
// A seat's type is the one a netplay lobby slot carries. The browser reaches the game through a client even
// when it hosts it, so your own seat arrives as REMOTE and is recognised by its "mine" flag, not its type.

import { useEffect, useState } from 'preact/hooks';
import { changeUi, ui, type Picker } from './ui';
import { sleeveUrl, avatarUrl } from './looks';
import { LookPicker } from './lookpicker';
import { DeckFinder } from './deckfinder';
import { ExtraPicker } from './extrapicker';
import { SleevePicker, artUrl, objectPosition } from './sleeves';
import { Pips } from './symbols';
import { EventPanel } from './event';
import { MatchBar, TableHeader, seatsLeaving } from './matchbar';
import type { Actions } from './actions';
import type { Model } from './model';
import type { DeckSummary, LobbyTable, Seat, SeatExtra } from './protocol';

export function Lobby({ model, actions }: { model: Model; actions: Actions }) {
  const picker = ui.picker;
  const lobby = model.lobby;
  // A lower player count being pointed at, whose leaving seats are dimmed before anything changes
  const [preview, setPreview] = useState<number | null>(null);
  if (!lobby) {
    return null;
  }
  // A seat can go while its picker is open, when the host removes it
  const seat = picker ? lobby.seats[picker.seat] : undefined;
  const close = () => changeUi(u => { u.picker = null; });
  const lim = lobby.limited;
  const leaving = preview === null ? new Set<number>() : seatsLeaving(lobby, preview);
  return (
    <>
      <TableHeader model={model} lobby={lobby} actions={actions} openOptions={() => changeUi(u => { u.optionsOpen = true; })} />
      <div class="lobby-main">
        <MatchBar lobby={lobby} actions={actions} preview={setPreview} cards={<ConstructedMenu lobby={lobby} actions={actions} />} />
        {/* A new kind of event is set up afresh, so its dialog opens again */}
        {lim && <EventPanel key={lim.kind} model={model} lobby={lobby} actions={actions} />}
        <div class="seats" id="seats" data-count={lobby.seats.length}>
          {lobby.seats.map((s, i) => <Plate key={i} seat={s} index={i} lobby={lobby} actions={actions} leaving={leaving.has(i)}
            choose={kind => changeUi(u => { u.picker = { kind, seat: i }; })} random={() => randomDeck(model, actions, i)} />)}
        </div>
        {/* Until the pools are out the event panel says what comes next; the match follows them */}
        {(!lim || lim.activeEventId) && <Verdict lobby={lobby} start={() => actions.startMatch(ui.spectate)} />}
      </div>
      {seat && picker?.kind === 'deck' && <DeckFinder model={model} actions={actions} seat={{ index: picker.seat, seat }} close={close} />}
      {seat && picker && (picker.kind === 'planes' || picker.kind === 'schemes' || picker.kind === 'vanguard') && (
        <ExtraPicker model={model} actions={actions} index={picker.seat} seat={seat} kind={picker.kind} close={close} />
      )}
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
  const on = (lobby.casualVariants ?? []).filter(v => (lobby.variantsOn ?? []).includes(v.id)).map(v => v.name);
  const variants = on.length ? ` with ${on.length > 1 ? `${on.slice(0, -1).join(', ')} and ${on[on.length - 1]}` : on[0]}` : '';
  return { title: name + variants, text: format?.desc ?? '' };
}

/** The card pool menu's placeholder: no Constructed format, while another format is chosen. */
const NO_POOL_CHOSEN = '-';

/** The card pool menu's value. Under another format it shows no entry, so choosing any one of them, "Any cards" included, is a change. */
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

/** Decks a computer seat may be dealt at random: any the lobby would accept, generators included. */
export function randomPool(decks: readonly DeckSummary[]): DeckSummary[] {
  return decks.filter(d => !d.problem);
}

/** A computer seat given a deck legal here, so a table fills without a trip to the chooser each. */
function randomDeck(model: Model, actions: Actions, index: number): void {
  const pool = randomPool(model.decks ?? []);
  if (pool.length) actions.setSeat(index, { deck: pool[Math.floor(Math.random() * pool.length)].key });
}

function Plate({ seat, index, lobby, actions, leaving, choose, random }: {
  seat: Seat; index: number; lobby: LobbyTable; actions: Actions; leaving: boolean; choose: (kind: Picker['kind']) => void; random: () => void;
}) {
  // Your own seat is the one the server dealt you, whatever type it wears on the host's side
  const mine = seat.mine;
  const waiting = seat.type === 'OPEN';
  // Another player's deck comes from their own catalog, so it has a name here but no key
  const hasDeck = seat.deck != null || seat.deckName != null;
  // Momir Basic and MoJhoSto deal every seat its deck at the start, so there is none to choose
  const format = lobby.formats.find(f => f.id === lobby.format);
  const dealt = format?.group === 'Other';
  // A Limited seat has no deck to choose until its pool is out; until then it says whether it is ready
  const lim = lobby.limited;
  const beforePools = !!lim && !lim.activeEventId;
  // The host turns a seat between a computer and one someone can join; everyone else only reads it
  const swappable = lobby.host && !mine && (seat.type === 'AI' || seat.type === 'OPEN');
  // The host may hand their own seat to the computer and watch; a match is only ever watched from the host's seat
  const watchable = lobby.host && mine && (!lobby.limited || !!lobby.limited.activeEventId);
  // A deck's own card art wins over the numbered sleeve, exactly as it does in a match
  const sleeveSrc = seat.sleeveArt ? artUrl(seat.sleeveArt) : sleeveUrl(seat.sleeve);
  return (
    <div class={`plate${mine ? ' mine' : ''}${waiting ? ' waiting' : ''}${seat.benched ? ' benched' : ''}${leaving ? ' leaving' : ''}`}>
      <div class="sleeve-slot" hidden={beforePools}>
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
          {watchable
            ? <button class="kind" title="Choose who plays this seat" aria-pressed={ui.spectate}
                onClick={() => changeUi(u => { u.spectate = !u.spectate; })}>{ui.spectate ? 'Computer' : KIND.LOCAL}</button>
            : <button class="kind" disabled={!swappable} title={swappable ? 'Swap between a computer and an open seat' : ''}
                onClick={() => (seat.type === 'AI' ? actions.openSeat(index) : actions.aiSeat(index))}>
                {mine ? KIND.LOCAL : (KIND[seat.type] ?? seat.type)}
              </button>}
          <button class="random-deck" title="Give this seat a random deck" aria-label="Random deck"
            hidden={seat.type !== 'AI' || !lobby.host || dealt} onClick={random}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="4" /><circle cx="8.5" cy="8.5" r="1.2" /><circle cx="15.5" cy="15.5" r="1.2" /><circle cx="12" cy="12" r="1.2" /><circle cx="15.5" cy="8.5" r="1.2" /><circle cx="8.5" cy="15.5" r="1.2" /></svg>
          </button>
          {seat.role && <span class={`role ${seat.role}`}>{seat.role === 'archenemy' ? 'Archenemy' : 'Hero'}</span>}
          {lobby.host && seat.role === 'hero' && (
            <button class="make-archenemy" onClick={() => actions.setArchenemy(index)}>Make archenemy</button>
          )}
          <button class="drop" title="Remove this seat" hidden={mine || !lobby.host || lobby.seats.length <= 2}
            onClick={() => actions.removeSeat(index)}>&times;</button>
        </div>
        {watchable && ui.spectate && <p class="seat-note">The computer plays this seat. You watch.</p>}
        {dealt && !waiting && <p class="deck-row fixed">{format?.facts[0]}</p>}
        {beforePools && !waiting && (mine
          ? <button class={`deck-row ready-toggle${seat.ready ? '' : ' unset'}`} disabled={lim.started}
              aria-pressed={seat.ready} title={seat.ready ? 'Press again if you are not ready after all' : ''}
              onClick={() => actions.ready(!seat.ready)}>{seat.ready ? '✓ Ready' : 'Press when ready'}</button>
          : <p class="deck-row fixed">{seat.ready ? 'Ready' : 'Not ready yet'}</p>)}
        {/* Sitting out matters only with a match to fill from more players than it needs */}
        {lim?.activeEventId && lobby.host && !waiting && lobby.seats.filter(s => s.type !== 'OPEN').length > 2 && (
          <label class="sits-out"><input type="checkbox" checked={seat.benched}
            onChange={e => actions.benchSeat(index, e.currentTarget.checked)} /> Sits out the next match</label>
        )}
        {/* With no deck the sleeve above already offers to choose one, so an empty row would only repeat it */}
        <button class={`deck-row${hasDeck ? '' : ' unset'}`} hidden={dealt || beforePools || (!hasDeck && !waiting)} disabled={!seat.mayEdit}
          onClick={() => choose('deck')}>
          <span class="pips"><Pips colors={seat.colors} /></span>
          <span class="deck-name">{seat.deckName ?? (waiting ? 'Waiting for a player' : '')}</span>
          <span class="deck-size">{hasDeck ? String(seat.deckSize) : ''}</span>
        </button>
        <p class="seat-problem" hidden={!seat.problem || !hasDeck}>{seat.problem ?? ''}</p>
        {seat.planes && <ExtraRow name="Planes" extra={seat.planes} mayEdit={seat.mayEdit} open={() => choose('planes')} />}
        {seat.schemes && <ExtraRow name="Schemes" extra={seat.schemes} mayEdit={seat.mayEdit} open={() => choose('schemes')} />}
        {seat.vanguard && <ExtraRow name="Avatar" extra={seat.vanguard} mayEdit={seat.mayEdit} open={() => choose('vanguard')} />}
      </div>
    </div>
  );
}

/** A planar deck, scheme deck or avatar on a seat: a plain row that opens its picker, with its fault when it has one. */
function ExtraRow({ name, extra, mayEdit, open }: { name: string; extra: SeatExtra; mayEdit: boolean; open: () => void }) {
  return (
    <button class={`seat-extra${extra.problem ? ' warn' : ''}`} disabled={!mayEdit} onClick={open}>
      <span class="extra-kind">{name}</span>
      <span class="extra-label">{extra.label}</span>
      {extra.detail ? <span class="extra-detail">{extra.detail}</span>
        : extra.count > 0 && name !== 'Avatar' && <span class="extra-count">{extra.count}</span>}
      {extra.problem && <span class="extra-problem">{extra.problem}</span>}
    </button>
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
        {`${rules} · ${lobby.seats.length} players${ui.spectate ? ' · You watch' : ''} · Enter starts the match.`}
      </p>
      <div class="not-yet" hidden={lobby.canStart}>
        <b>Not playable yet</b>
        <ul>{problems.map(p => <li key={p}>{p}</li>)}</ul>
      </div>
    </div>
  );
}
