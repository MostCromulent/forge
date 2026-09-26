// The bar above the seats: what is played and by how many, as labelled fields that each open their own control. The
// page header above it says only whose table this is and how to leave it.

import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { avatarUrl } from './looks';
import type { Actions } from './actions';
import type { Model } from './model';
import type { Address, Format, LobbyTable } from './protocol';

/** A field's popup: opened by its button, closed by Escape or a press anywhere outside it. */
function Popup({ label, disabled, children, wide, onOpen }: {
  label: ComponentChildren; disabled?: boolean; children: (close: () => void) => ComponentChildren; wide?: boolean; onOpen?: () => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    const outside = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);
  return (
    <span ref={root} class="popup-anchor">
      <button class="field-value menu-button" aria-expanded={open} disabled={disabled} onClick={() => {
        if (!open) onOpen?.();
        setOpen(!open);
      }}>{label}</button>
      {open && <div class={wide ? 'popup wide' : 'popup'} role="dialog">{children(() => setOpen(false))}</div>}
    </span>
  );
}

function Field({ name, grow, children }: { name: string; grow?: boolean; children: ComponentChildren }) {
  return (
    <div class={grow ? 'field grow' : 'field'}>
      <span class="field-name">{name}</span>
      {children}
    </div>
  );
}

/** Draft and Sealed, in the shape the server gives a format, so the menu's card reads the same for them. */
const LIMITED: (Format & { kind: 'draft' | 'sealed' })[] = [
  { id: 'Draft', kind: 'draft', name: 'Draft', group: 'Limited', desc: 'Players pass packs around the table, taking one card at a time, then build a deck from their picks.',
    facts: ['40 cards from your picks', 'Life 20'], play: 'Computers fill the empty seats in the draft; only the people at the table play the matches.' },
  { id: 'Sealed', kind: 'sealed', name: 'Sealed', group: 'Limited', desc: 'Everyone opens six packs and builds a deck from what they opened.',
    facts: ['40 cards from your pool', 'Life 20'], play: 'Each player plays the deck they built from their own pool.' },
];

/** A game's deck size in a few characters, read from its first fact: "60+", "100", or a dash when the deck is dealt. */
export function deckMark(format: Format): string {
  const size = /^(\d+\+?)/.exec(format.facts[0] ?? '')?.[1];
  return size ?? '–';
}

/**
 * What is played: a list of every game beside a card that explains one. The card shows the chosen game until another is
 * pointed at. A click on a name chooses it; a tap only shows its card, which then offers to choose it, so a touch never
 * changes the game by accident.
 */
function GameMenu({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  const lim = lobby.limited;
  const chosen: Format | undefined = lim ? LIMITED.find(l => l.kind === lim.kind) : lobby.formats.find(f => f.id === lobby.format);
  const [pointed, setPointed] = useState<Format | null>(null);
  const touch = useRef(false);
  // A new kind of event waits until the one begun is over; a format waits out a draft
  const drafting = lim?.phase === 'DRAFTING' && !lim.activeEventId;
  const limitedOffered = lobby.shareable || !!lim;
  const shown = pointed ?? chosen ?? null;
  const pick = (f: Format, close: () => void) => {
    const kind = LIMITED.find(l => l.id === f.id)?.kind;
    if (kind) {
      actions.setLimited(kind);
    } else {
      if (lim) actions.setLimited(null);
      actions.setFormat(f.id);
    }
    setPointed(null);
    close();
  };
  const blocked = (f: Format) => (LIMITED.some(l => l.id === f.id) ? !!lim?.started : drafting);
  const groups: [string, Format[]][] = [...groupsOf(lobby.formats), ...(limitedOffered ? [['Limited', LIMITED] as [string, Format[]]] : [])];
  return (
    <Popup label={chosen?.name ?? lobby.format} disabled={!lobby.host} wide>
      {close => (
        <div class="game-menu-list">
          <div class="game-choices" onPointerLeave={() => { if (!touch.current) setPointed(null); }}>
            {groups.map(([group, formats]) => (
              <div key={group} class="game-group">
                <span class="game-group-name">{group}</span>
                {formats.map(f => (
                  <button key={f.id} class="game-choice" aria-pressed={f === chosen} disabled={blocked(f)}
                    onPointerDown={e => { touch.current = e.pointerType === 'touch'; }}
                    onPointerEnter={e => { if (e.pointerType === 'mouse') setPointed(f); }} onFocus={() => setPointed(f)}
                    onClick={() => (touch.current ? setPointed(f) : pick(f, close))}>
                    <i class="game-mark">{deckMark(f)}</i><span class="game-name">{f.name}</span>
                  </button>
                ))}
              </div>
            ))}
          </div>
          {shown && (
            <div class="game-card">
              <h5>{shown.name}</h5>
              <p class="desc">{shown.desc}</p>
              <div class="facts">{shown.facts.map(x => <span key={x} class="fact">{x}</span>)}</div>
              <p class="format-play"><b>In a match:</b> {shown.play}</p>
              {shown !== chosen && !blocked(shown) && <button class="primary game-choose" onClick={() => pick(shown, close)}>Choose {shown.name}</button>}
            </div>
          )}
        </div>
      )}
    </Popup>
  );
}

/** Formats under their group, in the order the server lists them. */
function groupsOf(formats: Format[]): [string, Format[]][] {
  const groups = new Map<string, Format[]>();
  for (const f of formats) groups.set(f.group, [...(groups.get(f.group) ?? []), f]);
  return [...groups];
}

/** Why a casual variant cannot be switched on, or null. Momir Basic and MoJhoSto bring their own avatars. */
export function variantBlocked(lobby: LobbyTable, id: string): string | null {
  const group = lobby.formats.find(f => f.id === lobby.format)?.group;
  return id === 'Vanguard' && group === 'Other' ? 'Vanguard is off: this format brings its own avatar.' : null;
}

function VariantsMenu({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  const on = lobby.casualVariants.filter(v => lobby.variantsOn.includes(v.id));
  return (
    <Popup label={on.length ? on.map(v => v.name).join(', ') : <span class="muted">None</span>} disabled={!lobby.host}>
      {() => (
        <div class="variant-list">
          {lobby.casualVariants.map(v => {
            const blocked = variantBlocked(lobby, v.id);
            return (
              <label key={v.id} class={blocked ? 'variant blocked' : 'variant'}>
                <input type="checkbox" checked={lobby.variantsOn.includes(v.id)} disabled={!!blocked}
                  onChange={e => actions.setVariant(v.id, e.currentTarget.checked)} />
                <span><b>{v.name}</b><span class="muted">{blocked ?? v.desc}</span></span>
              </label>
            );
          })}
        </div>
      )}
    </Popup>
  );
}

/** Seats a lower count takes away, as the server takes them: open seats, then computers, from the end; never a person's. */
export function seatsLeaving(lobby: LobbyTable, count: number): Set<number> {
  const out = new Set<number>();
  let extra = lobby.seats.length - count;
  for (const type of ['OPEN', 'AI']) {
    for (let i = lobby.seats.length - 1; i >= 0 && extra > 0; i--) {
      if (!lobby.seats[i].mine && lobby.seats[i].type === type && !out.has(i)) {
        out.add(i);
        extra--;
      }
    }
  }
  return out;
}

/** The fewest seats a table can have: two, or everyone who is seated. */
export function fewestSeats(lobby: LobbyTable): number {
  return Math.max(2, lobby.seats.filter(s => s.mine || s.type === 'LOCAL' || s.type === 'REMOTE').length);
}

function PlayerCount({ lobby, actions, preview }: { lobby: LobbyTable; actions: Actions; preview: (count: number | null) => void }) {
  const fewest = fewestSeats(lobby);
  const counts = Array.from({ length: lobby.maxSeats - 1 }, (_, i) => i + 2);
  const drafting = lobby.limited?.phase === 'DRAFTING' && !lobby.limited.activeEventId;
  return (
    <span class="count" role="group" aria-label="Players" onPointerLeave={() => preview(null)}>
      {counts.map(n => (
        <button key={n} aria-pressed={n === lobby.seats.length} disabled={!lobby.host || drafting || n < fewest}
          title={n < fewest ? 'Fewer seats than the people seated' : ''}
          onPointerEnter={() => preview(n < lobby.seats.length ? n : null)}
          onClick={() => { preview(null); actions.setPlayerCount(n); }}>{n}</button>
      ))}
    </span>
  );
}

const day = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

/**
 * Which cards a Constructed game allows: the sanctioned formats as tiles, the casual ones as chips, and a block or an
 * old snapshot of a format on the bottom line. Where each format's cards come from is asked for when it first opens.
 */
function CardPoolPicker({ model, lobby, actions }: { model: Model; lobby: LobbyTable; actions: Actions }) {
  const details = model.cardPoolDetails;
  const lines = new Map((details?.lines ?? []).map(l => [l.name, l.line]));
  const group = (name: string) => lobby.cardPools.find(g => g.name === name)?.formats ?? [];
  const archived = details?.archived ?? [];
  const kinds = [...new Set(archived.map(a => a.kind))];
  const current = archived.find(a => a.name === lobby.cardPool);
  const [kind, setKind] = useState<string | null>(null);
  const shownKind = kind ?? current?.kind ?? kinds[0];
  return (
    <Popup label={lobby.cardPool ?? 'Any cards'} disabled={!lobby.host} wide
      onOpen={() => { if (!details) actions.askCardPoolDetails(); }}>
      {close => {
        const choose = (name: string | null) => { actions.setCardPool(name); close(); };
        return (
          <div class="pool-picker">
            <span class="field-name">Any cards, or a format</span>
            <div class="pool-tiles">
              <button class="pool-tile" aria-pressed={!lobby.cardPool} onClick={() => choose(null)}><b>Any cards</b><span>No limit</span></button>
              {group('Sanctioned').map(name => (
                <button key={name} class="pool-tile" aria-pressed={lobby.cardPool === name} onClick={() => choose(name)}>
                  <b>{name}</b><span>{lines.get(name) ?? ''}</span>
                </button>
              ))}
            </div>
            {group('Casual').length > 0 && <>
              <span class="field-name">Casual</span>
              <div class="pool-chips">
                {group('Casual').map(name => (
                  <button key={name} class="pool-chip" aria-pressed={lobby.cardPool === name} title={lines.get(name)} onClick={() => choose(name)}>{name}</button>
                ))}
              </div>
            </>}
            <div class="pool-more">
              {group('Block').length > 0 && (
                <label>A block
                  <span class="pill-select"><select value={group('Block').includes(lobby.cardPool ?? '') ? lobby.cardPool : ''}
                    onChange={e => choose(e.currentTarget.value)}>
                    <option value="" disabled>Choose a block</option>
                    {group('Block').map(name => <option key={name} value={name}>{name}</option>)}
                  </select></span>
                </label>
              )}
              {kinds.length > 0 && (
                <label>An older format
                  <span class="pill-select"><select value={shownKind} onChange={e => setKind(e.currentTarget.value)}>
                    {kinds.map(k => <option key={k} value={k}>{k}</option>)}
                  </select></span>
                  as of
                  <span class="pill-select"><select value={current?.kind === shownKind ? current.name : ''} onChange={e => choose(e.currentTarget.value)}>
                    <option value="" disabled>Choose a date</option>
                    {archived.filter(a => a.kind === shownKind).map(a => <option key={a.name} value={a.name}>{day(a.date)}</option>)}
                  </select></span>
                </label>
              )}
            </div>
          </div>
        );
      }}
    </Popup>
  );
}

/** Constructed · its card pool · players · variants, or at a Draft or Sealed table the game and players only. */
export function MatchBar({ model, lobby, actions, preview, event }: {
  model: Model; lobby: LobbyTable; actions: Actions; preview: (count: number | null) => void; event?: ComponentChildren;
}) {
  const lim = lobby.limited;
  return (
    <div class="match-bar">
      <div class="fields">
        <Field name="Game"><GameMenu lobby={lobby} actions={actions} /></Field>
        {!lim && lobby.format === 'Constructed' && <Field name="Cards"><CardPoolPicker model={model} lobby={lobby} actions={actions} /></Field>}
        <Field name="Players" grow={!!lim}><PlayerCount lobby={lobby} actions={actions} preview={preview} /></Field>
        {!lim && <Field name="Variants" grow><VariantsMenu lobby={lobby} actions={actions} /></Field>}
      </div>
      {event}
    </div>
  );
}

/** Whose table this is and who is here; how others join; the options; and the way out. */
export function TableHeader({ model, lobby, actions, openOptions }: { model: Model; lobby: LobbyTable; actions: Actions; openOptions: () => void }) {
  const host = model.presence.find(p => p.host);
  const here = model.presence.filter(p => p.doing !== 'waiting');
  return (
    <header class="lobby-head">
      <span class="wordmark">Forge</span>
      <span class="table-name"><b>{host ? `${host.name}'s table` : 'The table'}</b> · {here.length || 1} here</span>
      <span class="faces" aria-hidden="true">{here.slice(0, 4).map(p => <img key={p.name} alt="" src={avatarUrl(p.avatar)} />)}</span>
      <div class="head-right">
        {lobby.shareable && (
          <Popup label="Invite">
            {() => (
              <div class="invite">
                <b>Others join at</b>
                <Addresses list={model.addresses ?? []} />
              </div>
            )}
          </Popup>
        )}
        <button class="icon-button" title="Options" aria-label="Options" onClick={openOptions}>⚙</button>
        {/* The table belongs to the host, so a joined client has no menu to go back to */}
        <button hidden={!lobby.host} onClick={() => actions.leaveLobby()}>Leave table</button>
      </div>
    </header>
  );
}

function Addresses({ list }: { list: Address[] }) {
  const [copied, setCopied] = useState<string | null>(null);
  if (!list.length) {
    return <p class="muted">Working out your address…</p>;
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
