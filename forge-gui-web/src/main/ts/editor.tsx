// The deck editor: the catalogue on the left, the deck on the right, under the lobby's own head. Every change is sent
// to the server, which saves it at once, so there is no Save button; Undo covers mistakes. The deck half is the deck
// finder's panel made editable, so a player who has chosen a deck has already read it.

import { useEffect, useRef, useState } from 'preact/hooks';
import { Catalogue } from './catalogue';
import { CardMenu, PrintingPicker, type MenuAt } from './cardmenu';
import { DeckHalf, removeOne } from './deckhalf';
import { saveText } from './dom';
import { longPress, startDrag, verdictFor, type CardHandlers, type Carried, type Verdict } from './drag';
import { deckText } from './decklist';
import { peekAt } from './deckfinder';
import { imageUrl } from './images';
import { changeUi } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';
import type { DeckSection, EditorState } from './protocol';

/** The formats a deck can be built for, as the lobby names them. */
export const DECK_FORMATS: [string, string][] = [
  ['Constructed', 'Constructed'], ['Commander', 'Commander'], ['Brawl', 'Brawl'], ['Oathbreaker', 'Oathbreaker'],
  ['TinyLeaders', 'Tiny Leaders'],
];

export function Editor({ model, actions }: { model: Model; actions: Actions }) {
  const state = model.editor;
  const [menu, setMenu] = useState<'menu' | 'new' | null>(null);
  const [dialog, setDialog] = useState<'text' | 'delete' | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);
  const [cardMenu, setCardMenu] = useState<MenuAt | null>(null);
  const [picking, setPicking] = useState<{ name: string; zone: DeckSection } | null>(null);
  // A list pasted anywhere but a field opens the importer with it, as a file dropped on the page does
  useEffect(() => {
    const pasted = (e: ClipboardEvent) => {
      const target = e.target as HTMLElement | null;
      const text = e.clipboardData?.getData('text') ?? '';
      if (target?.closest('input, textarea') || !text.trim()) return;
      e.preventDefault();
      changeUi(u => { u.importer = { from: 'editor', text }; });
    };
    document.addEventListener('paste', pasted);
    return () => document.removeEventListener('paste', pasted);
  }, []);
  if (!state) {
    return null;
  }
  const dropped = (carried: Carried, v: Verdict) => {
    if (v.zone === 'catalogue' && carried.from !== 'catalogue') {
      if (carried.count === 1) removeOne(actions, carried.name, carried.from);
      else actions.edit({ op: 'remove', name: carried.name, from: carried.from, count: carried.count });
    } else if (v.zone === 'Commander') {
      actions.edit({ op: 'commander', name: carried.name, from: carried.from === 'catalogue' ? undefined : carried.from, count: 1 });
    } else if (v.zone !== 'catalogue' && carried.from === 'catalogue') {
      actions.edit({ op: 'add', name: carried.name, to: v.zone, count: carried.count });
    } else if (v.zone !== 'catalogue' && carried.from !== 'catalogue') {
      actions.edit({ op: 'move', name: carried.name, from: carried.from, to: v.zone, count: carried.count });
    }
  };
  // Alt at the press carries every copy. Whether a card can lead the deck is the server's to say, so the command zone
  // takes any card in a commander format and a refusal comes back as a notice
  const handlers: CardHandlers = (name, from, image, count) => ({
    onPointerDown: e => {
      const carried: Carried = { name, from, count: e.altKey ? Math.max(1, count) : 1 };
      startDrag(e, carried, image, zone => verdictFor(carried, zone, state, true), v => dropped(carried, v));
      longPress(e, (x, y) => setCardMenu({ name, from, x, y }));
    },
    onContextMenu: e => {
      e.preventDefault();
      setCardMenu({ name, from, x: e.clientX, y: e.clientY });
    },
  });
  const openAnother = () => {
    const seat = model.inLobby ? model.lobby?.mySeat : undefined;
    actions.closeEditor();
    changeUi(u => {
      if (seat !== undefined) u.picker = { kind: 'deck', seat };
      else u.browse = { format: state.format };
    });
  };
  return (
    <div class="editor-page" onPointerOver={e => setPeek(peekAt(e, '.editor-page'))} onPointerLeave={() => setPeek(null)}
      onDragOver={e => { if (e.dataTransfer?.types.includes('Files')) e.preventDefault(); }}
      onDrop={e => {
        const file = e.dataTransfer?.files[0];
        if (!file) return;
        e.preventDefault();
        void file.text().then(text => changeUi(u => { u.importer = { from: 'editor', text }; }));
      }}>
      <header class="lobby-head editor-head">
        <span class="wordmark">Forge</span>
        {state.limited
          ? <span class="deck-name">{state.name}</span>
          : renaming
          ? <RenameField name={state.name} done={name => {
              setRenaming(false);
              if (name && name !== state.name) actions.renameDeck(name);
            }} />
          : <button class="deck-name" title="Rename" onClick={() => setRenaming(true)}>{state.name}</button>}
        {state.limited
          ? <span class="check-fixed">Limited · 40 cards</span>
          : <CheckControl model={model} state={state} actions={actions} />}
        <div class="head-right">
          <span class="save-state">{saveState(state)}</span>
          <button disabled={!state.canUndo} onClick={() => actions.editorUndo()} title="Undo (Ctrl+Z)">&#8630; Undo</button>
          <div class="menu-anchor">
            <button aria-expanded={menu !== null} onClick={() => setMenu(menu ? null : 'menu')}>Deck &#8964;</button>
            {menu === 'menu' && state.limited && (
              <div class="deck-menu" role="menu">
                <button role="menuitem" onClick={() => { setMenu(null); setDialog('text'); }}>Copy as text</button>
              </div>
            )}
            {menu === 'menu' && !state.limited && (
              <div class="deck-menu" role="menu">
                <button role="menuitem" onClick={() => setMenu('new')}>New deck…</button>
                <button role="menuitem" onClick={() => { setMenu(null); openAnother(); }}>Open another…</button>
                <hr />
                <button role="menuitem" onClick={() => { setMenu(null); actions.deckOp('duplicate'); }}>Duplicate</button>
                <button role="menuitem" onClick={() => { setMenu(null); setRenaming(true); }}>Rename</button>
                <hr />
                <button role="menuitem" onClick={() => { setMenu(null); changeUi(u => { u.importer = { from: 'editor' }; }); }}>Import a list…</button>
                <button role="menuitem" onClick={() => { setMenu(null); setDialog('text'); }}>Copy as text</button>
                {/* A deck only being read (a precon, someone else's) has nothing of the player's to delete */}
                {!state.copyOf && <hr />}
                {!state.copyOf && (
                  <button role="menuitem" class="danger" onClick={() => { setMenu(null); setDialog('delete'); }}>Delete this deck…</button>
                )}
              </div>
            )}
            {menu === 'new' && (
              <div class="deck-menu" role="menu">
                <span class="menu-cap">A new deck for</span>
                {DECK_FORMATS.map(([id, name]) => (
                  <button key={id} role="menuitem" onClick={() => { setMenu(null); actions.openEditor({ newFormat: id }); }}>{name}</button>
                ))}
              </div>
            )}
          </div>
          <button class="primary" onClick={() => actions.closeEditor()}>Done</button>
        </div>
      </header>
      <div class="editor-shell">
        <Catalogue model={model} actions={actions} state={state} handlers={handlers} />
        <DeckHalf actions={actions} state={state} handlers={handlers} />
      </div>
      {dialog === 'text' && <TextDialog state={state} close={() => setDialog(null)} />}
      {dialog === 'delete' && (
        <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) setDialog(null); }}>
          <div class="dialog">
            <h3>Delete {state.name}?</h3>
            <p class="hint">This can't be undone.</p>
            <div class="actions">
              <button onClick={() => setDialog(null)}>Cancel</button>
              <button class="primary" onClick={() => { setDialog(null); actions.deckOp('delete'); }}>Delete</button>
            </div>
          </div>
        </div>
      )}
      {cardMenu && <CardMenu at={cardMenu} state={state} actions={actions} close={() => setCardMenu(null)}
        printings={zone => setPicking({ name: cardMenu.name, zone })} />}
      {picking && <PrintingPicker name={picking.name} zone={picking.zone} model={model} state={state} actions={actions}
        close={() => setPicking(null)} />}
      {peek && <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}><img alt="" src={imageUrl(peek.image)} /></div>}
    </div>
  );
}

function saveState(state: EditorState): string {
  if (state.copyOf) return `Changes save as a copy of ${state.copyOf}`;
  return state.target === 'device' ? 'Saved in this browser' : 'Saved';
}

function RenameField({ name, done }: { name: string; done: (name: string | null) => void }) {
  const field = useRef<HTMLInputElement>(null);
  useEffect(() => {
    field.current?.select();
  }, []);
  return (
    <input ref={field} class="deck-name-field" defaultValue={name} maxLength={60} aria-label="Deck name"
      onKeyDown={e => {
        if (e.key === 'Enter') done(e.currentTarget.value.trim());
        if (e.key === 'Escape') done(null);
      }}
      onBlur={e => done(e.currentTarget.value.trim())} />
  );
}

/** "Check legality against", for the open deck. */
export function CheckControl({ model, state, actions }: { model: Model; state: EditorState; actions: Actions }) {
  return (
    <CheckSelect model={model} value={state.unrestricted ? 'none' : `${state.format}|${state.cardPool ?? ''}`}
      change={(format, pool, unrestricted) => actions.setCheck(format, pool, unrestricted)} />
  );
}

/**
 * "Check legality against": the lobby's formats, each Constructed card pool, and no restriction at all. value is
 * "format|pool", or "none".
 */
export function CheckSelect({ model, value, change }: {
  model: Model; value: string; change: (format: string, cardPool: string | null, unrestricted: boolean) => void;
}) {
  const pools = model.lobby?.cardPools ?? [{ name: 'Sanctioned', formats: model.cardFormats }];
  return (
    <label class="legality set">
      Check legality against
      <span class="pill-select"><select value={value} onChange={e => {
        const [format, pool] = e.currentTarget.value.split('|');
        if (format === 'none') change('Constructed', null, true);
        else change(format, pool || null, false);
      }}>
        <option value="Constructed|">Constructed · any cards</option>
        {pools.map(group => (
          <optgroup key={group.name} label={`Constructed · ${group.name}`}>
            {group.formats.map(f => <option key={f} value={`Constructed|${f}`}>{f}</option>)}
          </optgroup>
        ))}
        {DECK_FORMATS.filter(([id]) => id !== 'Constructed').map(([id, name]) => <option key={id} value={`${id}|`}>{name}</option>)}
        <option value="none">No restriction</option>
      </select></span>
    </label>
  );
}

/** The deck as text to copy, or to save as a file, which is a guest's only copy away from this browser. */
function TextDialog({ state, close }: { state: EditorState; close: () => void }) {
  const text = deckText(state);
  const area = useRef<HTMLTextAreaElement>(null);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    area.current?.select();
  }, []);
  const download = () => saveText(text, `${state.name}.txt`, 'text/plain');
  return (
    <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="dialog deck-text">
        <h3>{state.name}</h3>
        <textarea ref={area} readOnly value={text} rows={16} />
        <div class="actions">
          <button onClick={download}>Download as a file</button>
          {navigator.clipboard && (
            <button onClick={async () => { await navigator.clipboard.writeText(text); setCopied(true); }}>{copied ? 'Copied' : 'Copy'}</button>
          )}
          <button class="primary" onClick={close}>Close</button>
        </div>
      </div>
    </div>
  );
}
