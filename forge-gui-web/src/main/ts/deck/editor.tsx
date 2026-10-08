// The deck editor: every change is sent to the server, which saves it at once, so there is no Save button

import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { useDismiss } from '../hooks';
import { Catalogue } from './catalogue';
import { CardMenu, PrintingPicker, type MenuAt } from './cardmenu';
import { DeckHalf, removeOne } from './deckhalf';
import { saveText } from '../dom';
import { startDrag, verdictFor, type CardHandlers, type Carried, type Verdict } from './drag';
import { longPress } from '../press';
import { deckText } from './decklist';
import { peekAt } from './deckfinder';
import { imageUrl } from '../images';
import { changeUi } from '../ui';
import { HeadControls, PageHeader } from '../header';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { DeckSection, EditorState } from '../protocol';
import { t, type TextKey } from '../text';

// Lucide's plus, folder, copy, pencil-line, download, upload and trash-2 (ISC, see web/licenses/lucide-license.txt)
const ICONS = {
  plus: <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>,
  folder: <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" /></svg>,
  copy: <svg viewBox="0 0 24 24" aria-hidden="true"><rect width="14" height="14" x="8" y="8" rx="2" /><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" /></svg>,
  pen: <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20h9" /><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" /></svg>,
  down: <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12M7 10l5 5 5-5M5 21h14" /></svg>,
  up: <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 15V3M7 8l5-5 5 5M5 21h14" /></svg>,
  bin: <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>,
};

/** The formats a deck can be built for, as the lobby names them. */
export const DECK_FORMATS: [string, TextKey][] = [
  ['Constructed', 'lblConstructed'], ['Commander', 'lblCommander'], ['Brawl', 'lblBrawl'], ['Oathbreaker', 'lblOathbreaker'],
  ['TinyLeaders', 'lblTinyLeaders'],
];

export function Editor({ model, actions }: { model: Model; actions: Actions }) {
  const state = model.editor;
  const [menu, setMenu] = useState<'menu' | 'new' | null>(null);
  const [dialog, setDialog] = useState<'text' | 'delete' | null>(null);
  const [renaming, setRenaming] = useState(false);
  const nameMenu = useRef<HTMLDivElement>(null);
  useDismiss(menu !== null, nameMenu, () => setMenu(null));
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);
  const [cardMenu, setCardMenu] = useState<MenuAt | null>(null);
  const [picking, setPicking] = useState<{ name: string; zone: DeckSection } | null>(null);
  /** Which half a phone shows, where there is room for one at a time. */
  const [half, setHalf] = useState<'find' | 'deck'>('find');
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
  // Alt at the press carries every copy, and the command zone takes any card because the server decides what can lead a deck
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
  // A draft or sealed pool, and a deck built from a campaign's cards, are neither made, opened, renamed nor deleted from here
  const ownDeck = !state.limited && !state.collection;
  /** A row of the deck's menu, which closes the menu unless it opens another. */
  const item = (icon: ComponentChildren, label: string, act: () => void, stays = false, cls?: string) => (
    <button role="menuitem" class={cls} onClick={() => {
      if (!stays) setMenu(null);
      act();
    }}>{icon}<span>{label}</span></button>
  );
  // Shown in the head, and on a phone in the deck's own summary, beside the verdict it explains
  const check = state.limited
    ? <span class="check-fixed">{t('lblWebEditorLimitedFixed')}</span>
    : state.collection ? <span class="check-fixed">{state.check}</span>
    : <CheckControl model={model} state={state} actions={actions} />;
  return (
    <div class="editor-page" onPointerOver={e => setPeek(peekAt(e, '.editor-page'))} onPointerLeave={() => setPeek(null)}
      onDragOver={e => { if (e.dataTransfer?.types.includes('Files')) e.preventDefault(); }}
      onDrop={e => {
        const file = e.dataTransfer?.files[0];
        if (!file) return;
        e.preventDefault();
        void file.text().then(text => changeUi(u => { u.importer = { from: 'editor', text }; }));
      }}>
      <PageHeader class="editor-head">
        {state.collection && <span class="deck-owner">{state.collection} &rsaquo;</span>}
        {renaming
          ? <RenameField name={state.name} done={name => {
              setRenaming(false);
              if (name && name !== state.name) actions.renameDeck(name);
            }} />
          : (
            <div ref={nameMenu} class="menu-anchor name-menu">
              {/* The deck's name opens what can be done with the deck */}
              <button class="deck-name" aria-expanded={menu !== null} onClick={() => setMenu(menu ? null : 'menu')}>
                <span>{state.name}</span><svg class="chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
              </button>
              {menu === 'menu' && (
                <div class="deck-menu" role="menu">
                  {ownDeck && item(ICONS.plus, t('lblWebEditorNewDeck'), () => setMenu('new'), true)}
                  {ownDeck && item(ICONS.folder, t('lblWebEditorOpenAnother'), openAnother)}
                  {ownDeck && <hr />}
                  {ownDeck && item(ICONS.copy, t('lblDuplicate'), () => actions.deckOp('duplicate'))}
                  {ownDeck && item(ICONS.pen, t('lblRename'), () => setRenaming(true))}
                  {ownDeck && <hr />}
                  {!state.limited && item(ICONS.down, t('lblWebEditorImportList'), () => changeUi(u => { u.importer = { from: 'editor' }; }))}
                  {item(ICONS.up, t('lblWebEditorCopyAsText'), () => setDialog('text'))}
                  {/* A deck only being read (a precon, someone else's) has nothing of the player's to delete */}
                  {ownDeck && !state.copyOf && <hr />}
                  {ownDeck && !state.copyOf && item(ICONS.bin, t('lblWebEditorDeleteThisDeck'), () => setDialog('delete'), false, 'danger')}
                </div>
              )}
              {menu === 'new' && (
                <div class="deck-menu" role="menu">
                  <span class="menu-cap">{t('lblWebEditorNewDeckFor')}</span>
                  {DECK_FORMATS.map(([id, name]) => (
                    <button key={id} role="menuitem" onClick={() => { setMenu(null); actions.openEditor({ newFormat: id }); }}>{t(name)}</button>
                  ))}
                </div>
              )}
            </div>
          )}
        {check}
        <div class="head-right">
          <span class="save-state">{saveState(state)}</span>
          <button disabled={!state.canUndo} onClick={() => actions.editorUndo()} title={t('lblWebEditorUndoTip')} aria-label={t('lblUndo')}>&#8630;<span class="word"> {t('lblUndo')}</span></button>
          <HeadControls />
          <button class="primary" onClick={() => actions.closeEditor()}>{t('lblDone')}</button>
        </div>
      </PageHeader>
      <div class="editor-tabs" role="group">
        <button aria-pressed={half === 'find'} onClick={() => setHalf('find')}>{t('lblWebEditorFindCards')}</button>
        <button aria-pressed={half === 'deck'} onClick={() => setHalf('deck')}>
          {t('lblDeck')} <span class={state.verdict ? 'n no' : 'n'}>{state.verdict ? '! ' : ''}{state.stats.main}</span>
        </button>
      </div>
      <div class={half === 'deck' ? 'editor-shell deck' : 'editor-shell'}>
        <Catalogue model={model} actions={actions} state={state} handlers={handlers} />
        <DeckHalf actions={actions} state={state} handlers={handlers} check={check} />
      </div>
      {dialog === 'text' && <TextDialog state={state} close={() => setDialog(null)} />}
      {dialog === 'delete' && (
        <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) setDialog(null); }}>
          <div class="dialog">
            <h3>{t('lblWebEditorDeleteDeck', state.name)}</h3>
            <p class="hint">{t('lblWebEditorCannotUndo')}</p>
            <div class="actions">
              <button onClick={() => setDialog(null)}>{t('lblCancel')}</button>
              <button class="primary" onClick={() => { setDialog(null); actions.deckOp('delete'); }}>{t('lblDelete')}</button>
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
  if (state.copyOf) return t('lblWebEditorSavesAsCopy', state.copyOf);
  return state.target === 'device' ? t('lblWebEditorSavedInBrowser') : t('lblWebEditorSaved');
}

function RenameField({ name, done }: { name: string; done: (name: string | null) => void }) {
  const field = useRef<HTMLInputElement>(null);
  useEffect(() => {
    field.current?.select();
  }, []);
  return (
    <input ref={field} class="deck-name-field" defaultValue={name} maxLength={60} aria-label={t('lblWebEditorDeckName')}
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

/** "Check legality against" a format, a Constructed card pool or nothing, where value is "format|pool" or "none". */
export function CheckSelect({ model, value, change }: {
  model: Model; value: string; change: (format: string, cardPool: string | null, unrestricted: boolean) => void;
}) {
  const pools = model.cardPools.length ? model.cardPools : [{ name: 'Sanctioned', formats: model.cardFormats }];
  return (
    <label class="legality set">
      {t('lblWebEditorCheckAgainst')}
      <span class="pill-select"><select value={value} onChange={e => {
        const [format, pool] = e.currentTarget.value.split('|');
        if (format === 'none') change('Constructed', null, true);
        else change(format, pool || null, false);
      }}>
        <option value="Constructed|">{t('lblWebEditorConstructedAnyCards')}</option>
        {pools.map(group => (
          <optgroup key={group.name} label={t('lblWebEditorConstructedPool', group.name)}>
            {group.formats.map(f => <option key={f} value={`Constructed|${f}`}>{f}</option>)}
          </optgroup>
        ))}
        {DECK_FORMATS.filter(([id]) => id !== 'Constructed').map(([id, name]) => <option key={id} value={`${id}|`}>{t(name)}</option>)}
        <option value="none">{t('lblWebEditorNoRestriction')}</option>
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
          <button onClick={download}>{t('lblWebEditorDownloadFile')}</button>
          {navigator.clipboard && (
            <button onClick={async () => { await navigator.clipboard.writeText(text); setCopied(true); }}>{copied ? t('lblWebMatchBarCopied') : t('lblCopy')}</button>
          )}
          <button class="primary" onClick={close}>{t('lblClose')}</button>
        </div>
      </div>
    </div>
  );
}
