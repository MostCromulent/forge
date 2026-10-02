// The server marks every line of the list, each fix edits the text, and nothing is saved until the player presses Import

import { useEffect, useRef, useState } from 'preact/hooks';
import { CheckSelect } from './editor';
import { leaveOut, makeCommander, useName } from './importfix';
import { loadDraft, saveDraft } from './drafts';
import { Pips } from './symbols';
import { ui } from './ui';
import type { Actions } from './actions';
import type { Model } from './model';
import type { EditorCard, ImportAction, ImportFix, ImportProblem } from './protocol';
import { t } from './text';

const READ_DELAY_MS = 300;
const LINE_PX = 22;
const MARKS: Record<string, string> = { read: '✓', problem: '!', ignored: '–', heading: '' };
const SITES = 'Moxfield, Archidekt, TappedOut, MTGGoldfish';

export interface ImporterProps {
  model: Model;
  actions: Actions;
  from: 'seat' | 'start' | 'editor';
  seat?: number;
  initialText?: string;
  /** A link to fetch as the dialog opens, which Sync now uses. */
  initialUrl?: string;
  /** Syncing a linked deck: importing replaces the deck of the same name without asking. */
  sync?: boolean;
  close: () => void;
}

export function Importer({ model, actions, from, seat, initialText, initialUrl, sync, close }: ImporterProps) {
  const [text, setText] = useState(() => initialText ?? loadDraft());
  const [typedName, setTypedName] = useState<string | null>(null);
  const [check, setCheck] = useState(() => startingCheck(model, from, ui.browse?.format));
  const [url, setUrl] = useState(initialUrl ?? '');
  const [fetching, setFetching] = useState(false);
  const [lit, setLit] = useState<Set<number>>(() => new Set());
  const [undo, setUndo] = useState<string[]>([]);
  const [sent, setSent] = useState<{ action: ImportAction; decks: unknown; editor: unknown } | null>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const gutter = useRef<HTMLDivElement>(null);
  const washes = useRef<HTMLDivElement>(null);
  const result = model.importResult;
  const fetched = result?.fetched ?? null;

  // The list is read again once typing pauses; a change of check reads it again at once
  useEffect(() => {
    if (fetching) return;
    const timer = setTimeout(() => {
      const [format, pool] = check.split('|');
      if (text.trim()) actions.readImport(0, text, format === 'none' ? 'Constructed' : format, pool || null, format === 'none');
    }, READ_DELAY_MS);
    return () => clearTimeout(timer);
  }, [text, check]);
  // A fetched list fills the box, and it is read with the format the site gave
  useEffect(() => {
    if (fetching && result) {
      setFetching(false);
      if (result.fetched) {
        setText(result.fetched.text);
        // The site says what format the deck is for, which also decides where it is saved
        setCheck(`${result.fetched.format}|`);
      }
    }
  }, [result]);
  // After Import the dialog waits for the server: a taken name asks what to do, anything else means it is done
  useEffect(() => {
    if (sent && !model.nameTaken && (model.decks !== sent.decks || model.editor !== sent.editor)) {
      saveDraft('');
      close();
    }
  }, [model.decks, model.editor, model.nameTaken]);

  useEffect(() => {
    if (initialUrl) fetch(initialUrl);
  }, []);

  const edit = (next: string, line?: number) => {
    setUndo([...undo, text]);
    setText(next);
    setLit(line === undefined ? new Set() : new Set([line]));
  };
  const typed = (next: string) => {
    setText(next);
    setLit(new Set());
    saveDraft(next);
  };
  const summary = result?.summary;
  const name = typedName ?? result?.name ?? (summary?.commander ? t('lblWebImportCommanderDeck', summary.commander) : t('lblWebImportDefaultName'));
  const commit = (action: ImportAction, clash?: 'replace' | 'keep') => {
    const [format, pool] = check.split('|');
    setSent({ action, decks: model.decks, editor: model.editor });
    actions.commitImport({
      text, name, format: format === 'none' ? 'Constructed' : format, cardPool: pool || undefined, unrestricted: format === 'none',
      action, seat, url: fetched?.url, clash: clash ?? (sync ? 'replace' : undefined),
    });
  };
  const fix = (p: ImportProblem, f: ImportFix) => {
    if (f.kind === 'use' && f.text) edit(useName(text, p.line, f.text), p.line);
    else if (f.kind === 'leaveOut') edit(leaveOut(text, p.line), p.line);
    else if (f.kind === 'commander' && f.text) edit(makeCommander(text, f.text));
    else if (f.kind === 'other') commit('edit');
  };
  const fetch = (link: string) => {
    if (!link.trim()) return;
    setFetching(true);
    actions.fetchImport(0, link.trim());
  };
  const lines = text.split('\n');
  const marks = result?.lines ?? [];
  const cards = (summary?.cards ?? 0) + (summary?.sideboard ?? 0);
  const [secondary, primary]: [[ImportAction, string], [ImportAction, string]] = from === 'editor'
    ? [['replace', t('lblWebImportReplaceDeck')], ['add', t('lblWebImportAddToDeck')]]
    : [['edit', t('lblWebImportAndEdit')], from === 'seat' ? ['use', t('lblWebImportAndUse')] : ['save', t('lblImport')]];
  return (
    <div class="backdrop importer-back" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="importer" onDragOver={e => e.preventDefault()} onDrop={e => {
        e.preventDefault();
        void e.dataTransfer?.files[0]?.text().then(dropped => typed(dropped));
      }}>
        <header class="imp-head">
          <h2>{t('lblWebImportTitle')}</h2>
          <span class="muted">{t('lblWebImportSubtitle')}</span>
          <button class="dk-close" title={t('lblClose')} onClick={close}>&times;</button>
        </header>
        <div class="imp-sub">
          <label class="namefield">{t('lblWebEditorDeckName')}
            <input value={name} maxLength={60} onInput={e => setTypedName(e.currentTarget.value)} />
          </label>
          {/* A deck built from a collection is of one format, which the server reads the list as */}
          {from === 'editor' && model.editor?.collection
            ? <span class="check-fixed">{model.editor.check}</span>
            : <CheckSelect model={model} value={check} change={(format, pool, none) => setCheck(none ? 'none' : `${format}|${pool ?? ''}`)} />}
        </div>
        <div class="imp-body">
          <div class="paste">
            <h4>{t('lblWebImportFromLink')} <span>{SITES}</span></h4>
            <div class="linkfield">
              <input class="find" placeholder="https://moxfield.com/decks/…" value={url}
                onInput={e => setUrl(e.currentTarget.value)}
                onPaste={e => { const pasted = e.clipboardData?.getData('text') ?? ''; setUrl(pasted); fetch(pasted); e.preventDefault(); }}
                onKeyDown={e => { if (e.key === 'Enter') fetch(url); }} />
              <button onClick={() => fetch(url)}>{t('lblWebImportFetch')}</button>
            </div>
            <h4>{fetched ? t('lblWebFinderFrom', fetched.site) : t('lblWebImportOrPaste')}</h4>
            {fetching
              ? <div class="fetching"><span class="spinner" /> {t('lblWebImportFetching')}</div>
              : (
                <div class="text-box">
                  <div class="gutter" ref={gutter} aria-hidden="true">
                    {lines.map((_, i) => {
                      const kind = marks[i]?.kind ?? '';
                      return <div key={i} class={`gl k-${kind}${lit.has(i) ? ' lit' : ''}`}><span class="ln">{i + 1}</span><span class="mk">{MARKS[kind] ?? ''}</span></div>;
                    })}
                  </div>
                  <div class="text-wrap">
                    <div class="washes" ref={washes} aria-hidden="true">
                      {lines.map((_, i) => <div key={i} class={`wash k-${marks[i]?.kind ?? ''}${lit.has(i) ? ' lit' : ''}`} style={{ top: `${i * LINE_PX}px` }} />)}
                    </div>
                    <textarea ref={area} wrap="off" spellcheck={false} value={text} readOnly={!!fetched}
                      placeholder={'4 Lightning Bolt\n1 Sol Ring (C21) 263\n\nSideboard\n2 Duress'}
                      onInput={e => typed(e.currentTarget.value)}
                      onScroll={e => {
                        // The marks and the washes sit beside and behind the text, so they follow its scrolling
                        const { scrollTop, scrollLeft } = e.currentTarget;
                        if (gutter.current) gutter.current.scrollTop = scrollTop;
                        if (washes.current) washes.current.style.transform = `translate(${-scrollLeft}px, ${-scrollTop}px)`;
                      }}
                      onKeyDown={e => {
                        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && undo.length) {
                          e.preventDefault();
                          setText(undo[undo.length - 1]);
                          setUndo(undo.slice(0, -1));
                        }
                      }} />
                  </div>
                </div>
              )}
          </div>
          <div class="read">
            <h4>{t('lblWebImportResult')}</h4>
            {!summary || !text.trim()
              ? <Guide />
              : <>
                  <div class="summary">
                    <span class="big"><span class="pips"><Pips colors={summary.colors} /></span> {t('lblWebNCards', summary.cards)}</span>
                    <span class="sub">{summary.notImported
                      ? t('lblWebImportSideboardNotImported', summary.sideboard, summary.notImported)
                      : t('lblWebImportSideboard', summary.sideboard)}</span>
                    {summary.verdict && <p class="verdict no">{summary.verdict}</p>}
                    {summary.commanderChosen && summary.commander && <p class="verdict yes">{t('lblWebImportIsCommander', summary.commander)}</p>}
                  </div>
                  {result.problems.length > 0 && (
                    <div class="attn">
                      <h5>{t('lblWebImportProblems')} <span>{result.problems.length}</span></h5>
                      {result.problems.map((p, i) => (
                        <div key={i} class="issue">
                          <span class="g">!</span>
                          <span class="what"><b>{p.title}</b><span>{p.detail}</span></span>
                          <span class="acts">
                            {p.fixes.map((f, j) => <button key={j} class={f.kind === 'leaveOut' ? 'quiet' : 'small'} onClick={() => fix(p, f)}>{f.label}</button>)}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                  <div class="readlist cols">
                    <div>{summary.main.map(g => <CardGroup key={g.heading} heading={g.heading} cards={g.cards} />)}</div>
                    <div>{summary.sideboardCards.length > 0 && <CardGroup heading={t('lblSideboard')} cards={summary.sideboardCards} />}</div>
                  </div>
                </>}
          </div>
        </div>
        <footer class="imp-foot">
          <span class="grow" />
          <button onClick={close}>{t('lblCancel')}</button>
          <button disabled={!cards || fetching} onClick={() => commit(secondary[0])}>{secondary[1]}</button>
          <button class="primary" disabled={!cards || fetching} onClick={() => commit(primary[0])}>{primary[1]}</button>
        </footer>
        {model.nameTaken && sent && (
          <div class="backdrop">
            <div class="dialog">
              <h3>{t('lblWebImportNameTaken', model.nameTaken)}</h3>
              <div class="actions">
                <button onClick={() => setSent(null)}>{t('lblCancel')}</button>
                <button onClick={() => commit(sent.action, 'replace')}>{t('lblWebDraftReplaceIt')}</button>
                <button class="primary" onClick={() => commit(sent.action, 'keep')}>{t('lblWebImportKeepBoth')}</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/** One heading of a deck list and its cards, which the deck finder uses too, marking the Commander game changers. */
export function CardGroup({ heading, cards, marked }: { heading: string; cards: EditorCard[]; marked?: ReadonlySet<string> }) {
  return (
    <div class="group">
      <h4>{heading}<span>{cards.reduce((n, c) => n + c.count, 0)}</span></h4>
      {cards.map(c => (
        <div key={c.name} class={`dk-line ed-line${c.problem ? ' bad' : ''}${marked?.has(c.name) ? ' changer' : ''}`} data-image={c.image}>
          <span class="n">{c.count}</span><span class="nm">{c.name}</span>{c.problem && <span class="flag">! {c.problem}</span>}
          {marked?.has(c.name) && <span class="gc" title={t('lblWebBracketGameChanger')}>GC</span>}
        </div>
      ))}
    </div>
  );
}

/** What the result side shows before anything is pasted: the forms a list can take. */
function Guide() {
  return (
    <div class="guide">
      <p>{t('lblWebImportGuideOnePerLine')}</p>
      <pre>{'4 Lightning Bolt\n1 Sol Ring (C21) 263\n\nSideboard\n2 Duress'}</pre>
      <p class="muted">{t('lblWebImportGuideSections')}</p>
      <p class="muted">{t('lblWebImportGuideLinks', SITES)}</p>
    </div>
  );
}

/** Where "Check legality against" starts, which also decides where the deck is saved. */
export function startingCheck(model: Model, from: 'seat' | 'start' | 'editor', browseFormat?: string): string {
  if (from === 'editor' && model.editor) {
    return model.editor.unrestricted ? 'none' : `${model.editor.format}|${model.editor.cardPool ?? ''}`;
  }
  if (from === 'seat' && model.lobby) {
    return `${model.lobby.format}|${model.lobby.format === 'Constructed' ? model.lobby.cardPool ?? '' : ''}`;
  }
  return `${browseFormat ?? 'Constructed'}|`;
}
