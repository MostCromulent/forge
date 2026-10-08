// The browser reaches the game through a client even when it hosts, so your own seat arrives as REMOTE and is known by its "mine" flag

import { useEffect, useRef, useState } from 'preact/hooks';
import { changeUi, ui, type Picker } from '../ui';
import { sleeveUrl, avatarUrl } from '../looks';
import { LookPicker } from '../lookpicker';
import { rememberAvatar } from '../menu';
import { BracketMark, DeckFinder, peekAt } from '../deck/deckfinder';
import { imageUrl, smallImage } from '../images';
import { ExtraPicker } from './extrapicker';
import { CENTRE, SleevePicker, artUrl, objectPosition } from '../sleeves';
import { Pips } from '../symbols';
import { EventHead } from './event';
import { MatchBar, TableHeader, seatsLeaving } from './matchbar';
import { SetupHead, WAY_NAMES } from '../header';
import { t, type TextKey } from '../text';
import { useDismiss } from '../hooks';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { DeckSummary, LobbyTable, Seat, SeatExtra } from '../protocol';

export function Lobby({ model, actions }: { model: Model; actions: Actions }) {
  const picker = ui.picker;
  const lobby = model.lobby;
  // A lower player count being pointed at, whose leaving seats are dimmed before anything changes
  const [preview, setPreview] = useState<number | null>(null);
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);
  if (!lobby) {
    return null;
  }
  // A seat can go while its picker is open, when the host removes it
  const seat = picker ? lobby.seats[picker.seat] : undefined;
  const close = () => changeUi(u => { u.picker = null; });
  const lim = lobby.limited;
  const leaving = preview === null ? new Set<number>() : seatsLeaving(lobby, preview);
  // A guest only ever reaches a table by a link, so its table is always one with friends
  const way = lobby.shareable || !lobby.host ? 'friends' : 'play';
  // The host steps back by leaving the table, and a guest has no earlier steps of its own to go back to
  const back = (to: 'play' | 'friends' | null) => lobby.host ? () => {
    changeUi(u => { u.menuChoice = to; });
    actions.leaveLobby();
  } : undefined;
  const kind = t(lim ? (lim.kind === 'draft' ? 'lblDraft' : 'lblSealed') : 'lblConstructed');
  return (
    <>
      <TableHeader model={model} lobby={lobby} />
      <div class="lobby-main" onPointerOver={e => setPeek(peekAt(e, '.lobby-main'))} onPointerLeave={() => setPeek(null)}>
        {peek && <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}><img alt="" src={imageUrl(peek.image)} /></div>}
        <SetupHead trail={[{ label: t('lblWebHeadStart'), go: back(null) }, { label: WAY_NAMES[way], go: back(way) }, { label: kind }]}
          title={t(lobby.host ? 'lblWebLobbySetUpGame' : lim?.activeEventId ? 'lblWebLobbyBuildThenPlay'
            : lim?.started ? (lim.kind === 'draft' ? 'lblWebLobbyDraftOn' : 'lblWebLobbyOpeningPacks') : 'lblWebLobbyHostSettingUp')} />
        {/* A new kind of event is set up afresh, so the key makes its dialog open again */}
        {lim
          ? <EventHead key={lim.kind} model={model} lobby={lobby} actions={actions} preview={setPreview} start={() => actions.startMatch(ui.spectate)} />
          : <MatchBar model={model} lobby={lobby} actions={actions} preview={setPreview} />}
        <div class="seats" id="seats">
          {/* Keyed by who sits there too, so a seat added or newly taken comes in afresh (lobby.css) */}
          {lobby.seats.map((s, i) => <Plate key={`${i} ${s.type} ${s.name ?? ''}`} seat={s} index={i} lobby={lobby} actions={actions} leaving={leaving.has(i)} joinable={model.networked}
            avatarCount={model.looks?.avatarCount ?? 0} sleeveCount={model.looks?.sleeveCount ?? 0}
            choose={kind => changeUi(u => { u.picker = { kind, seat: i }; })} random={() => randomDeck(model, actions, i)} />)}
          {/* A seat can be added where it will appear, as well as by the count above */}
          {lobby.host && !lim && lobby.seats.length < lobby.maxSeats && (
            <button class="add-seat" onClick={() => actions.setPlayerCount(lobby.seats.length + 1)}>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>{t('lblWebLobbyAddSeat')}
            </button>
          )}
        </div>
        {/* An event's Play is on its rail, which also says what the match waits on; only a warning about illegal decks is left */}
        {lim ? lim.activeEventId && <IllegalDecks lobby={lobby} /> : <Verdict lobby={lobby} start={() => actions.startMatch(ui.spectate)} />}
      </div>
      {seat && picker?.kind === 'deck' && <DeckFinder model={model} actions={actions} seat={{ index: picker.seat, seat }} close={close} />}
      {seat && picker && (picker.kind === 'planes' || picker.kind === 'schemes' || picker.kind === 'vanguard') && (
        <ExtraPicker model={model} actions={actions} index={picker.seat} seat={seat} kind={picker.kind} close={close} />
      )}
      {seat && picker?.kind === 'sleeve' && <SleevePicker model={model} actions={actions} index={picker.seat} seat={seat} close={close} />}
      {seat && picker?.kind === 'avatar' && (
        <LookPicker title={t('lblWebLobbyChooseAvatarFor', seat.name ?? '')} count={model.looks?.avatarCount ?? 0} urlOf={avatarUrl}
          current={seat.avatar} taken={lobby.seats.filter((_, i) => i !== picker.seat).map(s => s.avatar)} close={chosen => {
            if (chosen !== null) {
              if (seat.mine) rememberAvatar(chosen);
              actions.setSeat(picker.seat, { avatar: chosen });
            }
            close();
          }} />
      )}
    </>
  );
}

// The seat kinds a netplay lobby can hold; offline shows only the first two
const KIND: Record<string, TextKey> = { LOCAL: 'lblWebLobbyKindYou', AI: 'lblAI', OPEN: 'lblWebLobbyKindOpen', REMOTE: 'lblWebLobbyKindRemote' };

/** What a seat of this type is called, or the type itself for one this page does not know. */
const kindName = (type: string): string => (KIND[type] ? t(KIND[type]) : type);

// Lucide's bot and user (ISC, see web/licenses/lucide-license.txt)
const AI_ICON = <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8V4H8" /><rect width="16" height="12" x="4" y="8" rx="2" /><path d="M2 14h2M20 14h2M15 13v2M9 13v2" /></svg>;
const PLAYER_ICON = <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></svg>;

/** Who plays a seat the host may hand over: a chip that opens the two choices, each with a line saying what it means. */
function SeatKind({ type, toAi, toOpen }: { type: string; toAi: () => void; toOpen: () => void }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLSpanElement>(null);
  useDismiss(open, root, () => setOpen(false));
  const ai = type === 'AI';
  // Choosing what the seat already is only closes the menu, since asking again would deal the AI a new name
  const choose = (change: boolean, act: () => void) => () => {
    setOpen(false);
    if (change) act();
  };
  return (
    <span ref={root} class="popup-anchor">
      <button class="kind chip" title={t('lblWebLobbyWhoPlaysSeat')} aria-expanded={open} onClick={() => setOpen(!open)}>
        {ai ? AI_ICON : PLAYER_ICON}{kindName(type)}<svg class="chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
      </button>
      {open && (
        <div class="popup kind-menu" role="menu">
          <button role="menuitemradio" aria-checked={ai} onClick={choose(!ai, toAi)}>{AI_ICON}<span>{t('lblAI')}</span><small>{t('lblWebLobbyAiPlaysSeat')}</small></button>
          <button role="menuitemradio" aria-checked={!ai} onClick={choose(ai, toOpen)}>{PLAYER_ICON}<span>{t('lblWebLobbyOpenForPlayer')}</span><small>{t('lblWebLobbyOpenForPlayerHint')}</small></button>
        </div>
      )}
    </span>
  );
}

/** A number below count other than current, at random; current itself when it is the only one. */
function another(current: number, count: number): number {
  if (count <= 1) return current;
  const pick = Math.floor(Math.random() * (count - (current >= 0 && current < count ? 1 : 0)));
  return current >= 0 && pick >= current ? pick + 1 : pick;
}

/** Decks a seat may be dealt at random: any the lobby would accept, generators included, up to the table's bracket. */
export function randomPool(decks: readonly DeckSummary[], maxBracket = 5): DeckSummary[] {
  return decks.filter(d => !d.problem && (d.bracket == null || d.bracket <= maxBracket));
}

/** A computer seat given a deck legal here, so a table fills without a trip to the chooser each. */
function randomDeck(model: Model, actions: Actions, index: number): void {
  const pool = randomPool(model.decks ?? [], model.lobby?.maxBracket);
  if (pool.length) actions.setSeat(index, { deck: pool[Math.floor(Math.random() * pool.length)].key });
}

function Plate({ seat, index, lobby, actions, leaving, joinable, avatarCount, sleeveCount, choose, random }: {
  seat: Seat; index: number; lobby: LobbyTable; actions: Actions; leaving: boolean; joinable: boolean; avatarCount: number; sleeveCount: number;
  choose: (kind: Picker['kind']) => void; random: () => void;
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
  // With another player seated, each player presses Ready once their deck is chosen; the computer is always ready
  const pressReady = !lim && seat.type !== 'AI' && !waiting && lobby.seats.some(s => s.type !== 'AI' && s.type !== 'OPEN' && s.mine !== mine);
  // Where others can join, the host turns a seat between the AI's and one someone can take; everyone else only reads it
  const swappable = lobby.host && joinable && !mine && (seat.type === 'AI' || seat.type === 'OPEN');
  // The host may hand their own seat to the computer and watch; a match is only ever watched from the host's seat
  const watchable = lobby.host && mine && (!lobby.limited || !!lobby.limited.activeEventId);
  // Any seat whose deck this browser chooses can be dealt one at random; an event's decks are its players' own pools
  const randomable = seat.mayEdit && !dealt && !lim && !waiting;
  // Right-click deals another at random: never the one already on, and a numbered sleeve replaces any card art
  const randomSleeve = () => {
    actions.setSleeveArt(index, '', CENTRE);
    actions.setSeat(index, { sleeve: another(seat.sleeveArt ? -1 : seat.sleeve, sleeveCount) });
  };
  // No two seats share a portrait, so the pick is among those nobody holds
  const randomAvatar = () => {
    const free = Array.from({ length: avatarCount }, (_, i) => i).filter(i => lobby.seats.every(s => s.avatar !== i));
    if (!free.length) return;
    const avatar = free[Math.floor(Math.random() * free.length)];
    if (seat.mine) rememberAvatar(avatar);
    actions.setSeat(index, { avatar });
  };
  // A deck's own card art wins over the numbered sleeve, exactly as it does in a match
  const sleeveSrc = seat.sleeveArt ? artUrl(seat.sleeveArt) : sleeveUrl(seat.sleeve);
  // A deck led by a commander shows the commander, which says more about it than its sleeve does
  const commander = seat.commander;
  return (
    <div class={`plate${mine ? ' mine' : ''}${waiting ? ' waiting' : ''}${!mine && !waiting && seat.type !== 'AI' ? ' guest' : ''}${seat.benched ? ' benched' : ''}${leaving ? ' leaving' : ''}`}>
      <div class="sleeve-slot" hidden={beforePools} data-image={commander ?? undefined}>
        {/* Nothing is sleeved until a deck is chosen, so the slot stands empty rather than showing a sleeve */}
        <button class={`sleeve${hasDeck || dealt ? '' : ' empty'}${seat.sleeveArt && !commander ? ' card-art' : ''}`} title={dealt ? '' : t('lblWebLobbyChooseDeck')}
          data-label={seat.mayEdit ? t('lblWebLobbyChooseDeck') : (waiting ? '' : t('lblWebLobbyNoDeck'))}
          disabled={!seat.mayEdit || dealt} onClick={() => choose('deck')}
          // A new sleeve dealt under a commander would not be seen
          onContextMenu={e => { if (hasDeck && seat.mayEdit && !commander) { e.preventDefault(); randomSleeve(); } }}>
          <img alt="" hidden={!hasDeck && !dealt} src={commander ? smallImage(imageUrl(commander)) : hasDeck || dealt ? sleeveSrc : undefined}
            style={commander ? undefined : { objectPosition: objectPosition(seat.sleeveOffset) }} />
          {/* The sleeve holds two controls, so pointing at it names what each part does */}
          {hasDeck && seat.mayEdit && !dealt && <span class="sleeve-deck">{t('lblWebLobbyChangeDeck')}</span>}
        </button>
        {/* A sleeve is worn by a deck, so there is nothing to choose until there is one */}
        <button class="sleeve-style" title={t('lblWebSleevesChooseSleeve')} hidden={!hasDeck || !seat.mayEdit}
          onClick={() => choose('sleeve')}>{t('lblWebLobbyChangeSleeve')}</button>
      </div>
      <button class="drop" aria-label={t('lblWebLobbyRemoveSeat')} hidden={mine || !lobby.host || lobby.seats.length <= 2}
        onClick={() => actions.removeSeat(index)}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12" /></svg></button>
      <div class="plate-body">
        <div class="who">
          {/* A seat nobody has taken has no face to show */}
          <button class="avatar" title={t('lblWebLobbyChooseAvatar')} hidden={waiting} disabled={!seat.mayEdit}
            onClick={() => choose('avatar')} onContextMenu={e => { if (seat.mayEdit) { e.preventDefault(); randomAvatar(); } }}>
            <img alt="" src={avatarUrl(seat.avatar)} /></button>
          <SeatName seat={seat} rename={name => actions.setSeat(index, { name })} />
          {watchable
            ? <button class="kind" title={t('lblWebLobbyWhoPlaysSeat')} aria-pressed={ui.spectate}
                onClick={() => changeUi(u => { u.spectate = !u.spectate; })}>{t(ui.spectate ? 'lblAI' : KIND.LOCAL)}</button>
            : swappable
              ? <SeatKind type={seat.type} toAi={() => actions.aiSeat(index)} toOpen={() => actions.openSeat(index)} />
              : <span class="kind">{mine ? t(KIND.LOCAL) : kindName(seat.type)}</span>}
          {seat.role && <span class={`role ${seat.role}`}>{t(seat.role === 'archenemy' ? 'lblArchenemy' : 'lblWebLobbyHero')}</span>}
          {lobby.host && seat.role === 'hero' && (
            <button class="make-archenemy" onClick={() => actions.setArchenemy(index)}>{t('lblWebLobbyMakeArchenemy')}</button>
          )}
        </div>
        {watchable && ui.spectate && <p class="seat-note">{t('lblWebLobbyComputerPlaysSeat')}</p>}
        {dealt && !waiting && <p class="deck-row fixed">{format?.facts[0]}</p>}
        {beforePools && !waiting && <Ready seat={seat} actions={actions} disabled={lim.started} />}
        {/* Sitting out matters only with a match to fill from more players than it needs */}
        {lim?.activeEventId && lobby.host && !waiting && lobby.seats.filter(s => s.type !== 'OPEN').length > 2 && (
          <label class="sits-out"><input type="checkbox" checked={seat.benched}
            onChange={e => actions.benchSeat(index, e.currentTarget.checked)} /> {t('lblWebLobbySitsOut')}</label>
        )}
        {/* With no deck yet the row offers a random one; the sleeve beside it already offers to choose one */}
        {randomable && !hasDeck && (
          <button class="deck-row random-row" onClick={random}><Dice />{t('lblWebLobbyRandomDeck')}</button>
        )}
        <div class="deck-line" hidden={dealt || beforePools || (!hasDeck && !waiting)}>
          <button class={`deck-row${hasDeck ? '' : ' unset'}`} disabled={!seat.mayEdit} onClick={() => choose('deck')}>
            <span class="pips"><Pips colors={seat.colors} /></span>
            <span class="deck-name" title={seat.deckName ?? ''}>{seat.deckName ?? (waiting ? t('lblWebLobbyWaitingForPlayer') : '')}</span>
            <span class="deck-size">{hasDeck ? String(seat.deckSize) : ''}</span>
            {hasDeck && seat.bracket != null && <BracketMark level={seat.bracket} />}
          </button>
          {randomable && hasDeck && (
            <button class="random-deck" title={t('lblWebLobbyDealAnotherDeck')} aria-label={t('lblWebLobbyRandomDeck')} onClick={random}><Dice /></button>
          )}
        </div>
        <p class="seat-problem" hidden={!seat.problem || !hasDeck}>{seat.problem ?? ''}</p>
        {hasDeck && seat.bracket != null && lobby.maxBracket < 5 && seat.bracket > lobby.maxBracket && (
          <p class="seat-problem bracket">{t('lblWebLobbyAboveTableBracket', lobby.maxBracket)}</p>
        )}
        {pressReady && <Ready seat={seat} actions={actions} disabled={!hasDeck && !dealt} />}
        {seat.planes && <ExtraRow name={t('lblPlanes')} extra={seat.planes} mayEdit={seat.mayEdit} open={() => choose('planes')} />}
        {seat.schemes && <ExtraRow name={t('lblSchemes')} extra={seat.schemes} mayEdit={seat.mayEdit} open={() => choose('schemes')} />}
        {seat.vanguard && <ExtraRow name={t('lblAvatar')} counted={false} extra={seat.vanguard} mayEdit={seat.mayEdit} open={() => choose('vanguard')} />}
      </div>
    </div>
  );
}

/** Your own seat's Ready, pressed again to take it back; anyone else's only says whether they are. */
function Ready({ seat, actions, disabled }: { seat: Seat; actions: Actions; disabled: boolean }) {
  return seat.mine
    ? <button class={`deck-row ready-toggle${seat.ready ? '' : ' unset'}`} disabled={disabled}
        aria-pressed={seat.ready} title={seat.ready ? t('lblWebLobbyNotReadyAfterAll') : ''}
        onClick={() => actions.ready(!seat.ready)}>{t(seat.ready ? 'lblWebLobbyReadyTick' : 'lblWebLobbyPressWhenReady')}</button>
    : <p class="deck-row fixed">{t(seat.ready ? 'lblReady' : 'lblWebLobbyNotReadyYet')}</p>;
}

function Dice() {
  return (
    <svg class="dice" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="4" /><circle cx="8.5" cy="8.5" r="1.2" />
      <circle cx="15.5" cy="15.5" r="1.2" /><circle cx="12" cy="12" r="1.2" /><circle cx="15.5" cy="8.5" r="1.2" /><circle cx="8.5" cy="15.5" r="1.2" /></svg>
  );
}

/** A planar deck, scheme deck or avatar on a seat: a plain row that opens its picker, with its fault when it has one. */
function ExtraRow({ name, counted = true, extra, mayEdit, open }: {
  name: string; counted?: boolean; extra: SeatExtra; mayEdit: boolean; open: () => void;
}) {
  return (
    <button class={`seat-extra${extra.problem ? ' warn' : ''}`} disabled={!mayEdit} onClick={open}>
      <span class="extra-kind">{name}</span>
      <span class="extra-label">{extra.label}</span>
      {extra.detail ? <span class="extra-detail">{extra.detail}</span>
        : extra.count > 0 && counted && <span class="extra-count">{extra.count}</span>}
      {extra.problem && <span class="extra-problem">{extra.problem}</span>}
    </button>
  );
}

// Your own name, and the computer's at a table you host, is edited in place and saved when you leave it. A right click gives the computer a new one.
function SeatName({ seat, rename }: { seat: Seat; rename: (name: string) => void }) {
  // Typing edits the page, not what Preact drew, so each edit ends by drawing the field afresh with the name the server has
  const [edits, setEdits] = useState(0);
  const name = seat.name || kindName(seat.type);
  if (!seat.mine && !(seat.mayEdit && seat.type === 'AI')) {
    return <span class="who-name" hidden={!seat.name}>{name}</span>;
  }
  return (
    <span key={edits} class="who-name" contentEditable="plaintext-only" spellcheck={false}
      onContextMenu={e => { if (!seat.mine) { e.preventDefault(); rename(''); } }}
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
  const over = lobby.overBracket;
  // A deck above the table's bracket is the host's to allow: the first press asks, the second starts
  const [asking, setAsking] = useState(false);
  useEffect(() => setAsking(false), [over.join('\n')]);
  const play = () => (over.length && !asking ? setAsking(true) : start());
  // Only the host can start, so a joined client is told what it is waiting for rather than shown a dead button
  if (!lobby.host) {
    return (
      <div class="play-row">
        <p class="match-line">{problems.length ? problems[0] : t('lblWebEventWaitingHostStart')}</p>
      </div>
    );
  }
  return (
    <div class="play-row">
      <button id="play" class="primary play" disabled={!lobby.canStart} onClick={play} title={over.length ? '' : t('ttWebEventEnterStarts')}>
        {t(asking ? 'lblWebLobbyPlayAnyway' : 'lblPlay')}
      </button>
      <div class="not-yet" hidden={lobby.canStart}>
        <b>{t('lblWebLobbyNotPlayableYet')}</b>
        <ul>{problems.map(p => <li key={p}>{p}</li>)}</ul>
      </div>
      <IllegalDecks lobby={lobby} />
      {lobby.canStart && over.length > 0 && (
        <div class="not-yet warn">
          <b>{t('lblWebLobbyAboveTableBracket', lobby.maxBracket)}</b>
          <ul>{over.map(p => <li key={p}>{p}</li>)}</ul>
          <p class="hint">{t(asking ? 'lblWebLobbyPlayAnywayHint' : 'lblWebLobbyAskIgnoreBracket')}</p>
        </div>
      )}
    </div>
  );
}

/** Illegal decks do not stop the match; as on desktop, Play asks whether to ignore them. */
function IllegalDecks({ lobby }: { lobby: LobbyTable }) {
  if (!lobby.canStart || !lobby.illegalDecks.length) return null;
  return (
    <div class="not-yet warn">
      <b>{t('lblWebLobbyNotLegal')}</b>
      <ul>{lobby.illegalDecks.map(p => <li key={p}>{p}</li>)}</ul>
      <p class="hint">{t(lobby.legalityEnforced ? 'lblWebLobbyAskIgnoreIllegal' : 'lblWebLobbyLegalityNotEnforced')}</p>
    </div>
  );
}
