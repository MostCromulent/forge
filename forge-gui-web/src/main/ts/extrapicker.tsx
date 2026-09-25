// Choosing what a seat brings beyond its deck: a planar deck, a scheme deck or a Vanguard avatar. The server lists
// the choices for the seat, so a computer seat is only offered avatars the computer can play.

import { useEffect, useState } from 'preact/hooks';
import { imageUrl } from './images';
import type { Actions } from './actions';
import type { Model } from './model';
import type { ExtraChoice, Seat } from './protocol';

export type ExtraKind = 'planes' | 'schemes' | 'vanguard';

const SECTION: Record<ExtraKind, string> = { planes: 'Planes', schemes: 'Schemes', vanguard: 'Avatar' };
const TITLE: Record<ExtraKind, string> = { planes: 'Choose a planar deck', schemes: 'Choose a scheme deck', vanguard: 'Choose an avatar' };

/** "+1" or "−3", as the modifiers are printed. */
export const signed = (n: number): string => (n < 0 ? `−${-n}` : `+${n}`);

export function ExtraPicker({ model, actions, index, seat, kind, close }: {
  model: Model; actions: Actions; index: number; seat: Seat; kind: ExtraKind; close: () => void;
}) {
  const section = SECTION[kind];
  const [query, setQuery] = useState('');
  useEffect(() => actions.askExtraChoices(index, section), [index, section]);
  const listed = model.extraChoices?.section === section && model.extraChoices.index === index ? model.extraChoices.choices : null;
  const use = (choice: ExtraChoice) => {
    actions.setSeatExtra(index, section, choice.key);
    close();
  };
  const shown = (listed ?? []).filter(c => !query || c.label.toLowerCase().includes(query.toLowerCase()));
  return (
    <div class="finder-back" onClick={e => { if (e.target === e.currentTarget) close(); }}>
      <div class={`finder extra-picker ${kind}`}>
        <header class="finder-head">
          <h2>{TITLE[kind]}</h2>
          <span class="seat-note">for {seat.name}</span>
          <button class="dk-close" title="Close" onClick={close}>&times;</button>
        </header>
        <div class="extra-body">
          <input class="find" type="search" placeholder="Search by name" value={query} onInput={e => setQuery(e.currentTarget.value)} />
          {!listed ? <p class="none">Reading the choices…</p>
            : kind === 'vanguard' ? <AvatarGrid choices={shown} use={use} />
              : <div class="extra-list">{shown.map(c => (
                <button key={c.key} class={`extra-choice${c.problem ? ' illegal' : ''}`} title={c.problem ?? ''} onClick={() => use(c)}>
                  <span class="name">{c.label}</span>
                  <span class="count">{c.count != null ? `${c.count} cards` : ''}</span>
                  {c.problem && <span class="legal no">{c.problem}</span>}
                </button>
              ))}</div>}
        </div>
      </div>
    </div>
  );
}

// An avatar is chosen by its effect and its two numbers, so each is shown as its card with the numbers under it
function AvatarGrid({ choices, use }: { choices: ExtraChoice[]; use: (c: ExtraChoice) => void }) {
  return (
    <div class="avatar-grid">
      {choices.map(c => (
        <button key={c.key} class="avatar-choice" title={c.label} onClick={() => use(c)}>
          {c.image ? <img alt="" src={imageUrl(c.image)} loading="lazy" /> : <span class="plain">{c.label}</span>}
          <span class="avatar-name">{c.label}</span>
          {c.hand != null && c.life != null && <span class="mods">hand {signed(c.hand)} · life {signed(c.life)}</span>}
        </button>
      ))}
    </div>
  );
}
