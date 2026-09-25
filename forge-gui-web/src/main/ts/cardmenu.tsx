// A card's menu in the editor, opened by right-click or, on touch, by holding a finger on the card. It holds every
// route a drag offers, in words, so nothing depends on dragging; and it opens the printing picker.

import { useEffect } from 'preact/hooks';
import { imageUrl } from './images';
import { removeOne } from './deckhalf';
import type { Actions } from './actions';
import type { Model } from './model';
import type { DeckSection, EditorCard, EditorState } from './protocol';
import type { Zone } from './drag';

const COMMANDER_FORMATS = new Set(['Commander', 'Brawl', 'Oathbreaker', 'TinyLeaders']);

export interface MenuAt {
  name: string;
  from: Zone;
  x: number;
  y: number;
}

/** The card as a section holds it, or undefined when that section has none. */
export function cardIn(state: EditorState, zone: Zone | DeckSection, name: string): EditorCard | undefined {
  const cards = zone === 'Main' ? state.main.flatMap(g => g.cards) : zone === 'Sideboard' ? state.sideboard
    : zone === 'Commander' ? state.commanders : [];
  return cards.find(c => c.name === name);
}

export function CardMenu({ at, state, actions, close, printings }: {
  at: MenuAt; state: EditorState; actions: Actions; close: () => void; printings: (zone: DeckSection) => void;
}) {
  useEffect(() => {
    const away = () => close();
    document.addEventListener('pointerdown', away);
    return () => document.removeEventListener('pointerdown', away);
  }, []);
  const commanderFormat = !state.unrestricted && COMMANDER_FORMATS.has(state.format);
  const act = (run: () => void) => () => {
    run();
    close();
  };
  const inDeck = at.from === 'catalogue' ? undefined : cardIn(state, at.from, at.name);
  const zone = at.from === 'catalogue' ? null : at.from;
  const other: DeckSection = zone === 'Main' ? 'Sideboard' : 'Main';
  const anywhere = (['Main', 'Sideboard', 'Commander'] as DeckSection[]).find(z => cardIn(state, z, at.name));
  return (
    <div class="deck-menu card-menu" role="menu" style={{ left: `${at.x}px`, top: `${at.y}px` }} onPointerDown={e => e.stopPropagation()}>
      <span class="menu-cap">{at.name}</span>
      {zone === null && <>
        <button role="menuitem" onClick={act(() => actions.edit({ op: 'add', name: at.name, to: 'Main', count: 1 }))}>Add one to the main deck</button>
        <button role="menuitem" onClick={act(() => actions.edit({ op: 'add', name: at.name, to: 'Sideboard', count: 1 }))}>Add one to the sideboard</button>
      </>}
      {zone !== null && zone !== 'Commander' && <>
        <button role="menuitem" onClick={act(() => actions.edit({ op: 'add', name: at.name, to: zone, count: 1 }))}>Add one more</button>
        <button role="menuitem" onClick={act(() => actions.edit({ op: 'move', name: at.name, from: zone, to: other, count: 1 }))}>
          Move one to the {other === 'Main' ? 'main deck' : 'sideboard'}
        </button>
        {inDeck && inDeck.count > 1 && (
          <button role="menuitem" onClick={act(() => actions.edit({ op: 'move', name: at.name, from: zone, to: other, count: inDeck.count }))}>
            Move all {inDeck.count}
          </button>
        )}
      </>}
      <hr />
      {zone !== 'Commander' && (
        <button role="menuitem" disabled={!commanderFormat} title={commanderFormat ? undefined : 'Only commander formats have a commander'}
          onClick={act(() => actions.edit({ op: 'commander', name: at.name, from: zone ?? undefined, count: 1 }))}>
          Make this the commander
        </button>
      )}
      <button role="menuitem" disabled={!anywhere} title={anywhere ? undefined : 'Add the card first'}
        onClick={act(() => anywhere && printings(zone ?? anywhere))}>Change printing…</button>
      {zone !== null && <>
        <hr />
        <button role="menuitem" onClick={act(() => removeOne(actions, at.name, zone))}>Remove one</button>
        {inDeck && inDeck.count > 1 && (
          <button role="menuitem" onClick={act(() => actions.edit({ op: 'remove', name: at.name, from: zone, count: inDeck.count }))}>
            Remove all {inDeck.count}
          </button>
        )}
      </>}
    </div>
  );
}

/**
 * Which printing each copy of a card is. Steppers move copies between printings, so the total never changes; every
 * step is saved as it is made, which is why there is no Cancel. A printing outside the card pool is shown greyed.
 */
export function PrintingPicker({ name, zone, model, state, actions, close }: {
  name: string; zone: DeckSection; model: Model; state: EditorState; actions: Actions; close: () => void;
}) {
  useEffect(() => {
    actions.askPrintings(name, state.cardPool ?? null);
  }, [name]);
  const card = cardIn(state, zone, name);
  const list = model.printings?.name === name ? model.printings.list : [];
  const counts = new Map((card?.split ?? []).map(p => [p.key, p.count]));
  const send = (next: Map<string, number>) => actions.edit({
    op: 'printings', name, from: zone, count: card?.count ?? 0,
    printings: [...next].filter(([, n]) => n > 0).map(([key, count]) => ({ name: key, count })),
  });
  // One more of this printing is one fewer of the printing with the most copies, and the other way round
  const shift = (key: string, by: 1 | -1) => {
    const next = new Map(counts);
    const others = list.map(p => p.key).filter(k => k !== key && !list.find(p => p.key === k)?.problem);
    const donor = by > 0 ? others.filter(k => (next.get(k) ?? 0) > 0).sort((a, b) => (next.get(b) ?? 0) - (next.get(a) ?? 0))[0]
      : others.sort((a, b) => (next.get(b) ?? 0) - (next.get(a) ?? 0))[0];
    if (!donor) return;
    next.set(key, (next.get(key) ?? 0) + by);
    next.set(donor, (next.get(donor) ?? 0) - by);
    send(next);
  };
  return (
    <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="dialog picker">
        <h3>Printings of {name}</h3>
        <p class="hint">{card?.count ?? 0} in the {zone === 'Main' ? 'main deck' : zone.toLowerCase()}. Set how many of each printing.</p>
        <div class="print-rows">
          {!list.length && <p class="hint">Reading the printings…</p>}
          {list.map(p => {
            const n = counts.get(p.key) ?? 0;
            return (
              <div key={p.key} class={`print-row${n ? ' cur' : ''}${p.problem ? ' off' : ''}`} data-image={p.key}>
                <img loading="lazy" alt="" src={imageUrl(p.key)} />
                <span class="ed"><b>{p.setName}</b><span>{p.problem ? `⊘ ${p.problem}` : `${p.edition}${p.year ? ` · ${p.year}` : ''}`}</span></span>
                <span class="under">
                  <button class="step" disabled={!n} onClick={() => shift(p.key, -1)}>&minus;</button>
                  <span class={n ? 'n' : 'n zero'}>{n}</span>
                  <button class="step" disabled={!!p.problem || n >= (card?.count ?? 0)} onClick={() => shift(p.key, 1)}>+</button>
                </span>
              </div>
            );
          })}
        </div>
        <div class="actions"><button class="primary" onClick={close}>Done</button></div>
      </div>
    </div>
  );
}
