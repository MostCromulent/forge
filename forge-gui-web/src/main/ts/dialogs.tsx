// The game's open questions, shown one at a time, oldest first. Answering one takes it out of the model at once,
// so its dialog closes without waiting for the server.

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import { createCard, updateCard } from './cards';
import { imageUrl } from './images';
import { hoverCard } from './detail';
import { rankByName } from './search';
import { SymbolText } from './symbols';
import { useDebounced } from './hooks';
import type { Actions } from './actions';
import { keyName } from './keys';
import { boundKeys } from './settings';
import { playerAvatarUrl } from './looks';
import { cardMenu, isLocal, oldestRequest, stackPick, type Model } from './model';
import type {
  ChoicesRequest, DistributeRequest, ManipulateRequest, OptionRequest, OrderRequest, Request, RequestOption,
  PlayerView, SideboardRequest, TextRequest, TrackedObject,
} from './protocol';
import { t } from './text';

type Answer = (value: unknown) => void;

export function Requests({ model, actions }: { model: Model; actions: Actions }) {
  const req = oldestRequest(model);
  // Choosing between spells that are on the stack is done on the stack, so no list is drawn for it
  if (!req || stackPick(model) === req) {
    return null;
  }
  if (cardMenu(model) === req) {
    return <CardMenu key={req.id} req={req as ChoicesRequest} answer={value => actions.answer(req.id, value)} />;
  }
  // Cards shown and nothing asked of them: the zone window, the same as looking through a pile
  if (req.kind === 'reveal' && !req.atX && !req.atY) {
    return <RevealWindow key={req.id} model={model} title={req.message ?? ''} cards={req.options} close={() => actions.answer(req.id, [])} />;
  }
  // Keyed by the question, so nothing picked for one is still picked for the next
  return <RequestDialog key={req.id} req={req} model={model} answer={value => actions.answer(req.id, value)} />;
}

/**
 * What one clicked card can do, as a menu at the pointer the way desktop opens it. Picking an item answers at once;
 * a click anywhere else, or Escape, closes it having chosen nothing. Items are numbered for the keys 1 to 9.
 */
function CardMenu({ req, answer }: { req: ChoicesRequest; answer: Answer }) {
  const menu = useRef<HTMLDivElement>(null);
  const [at, setAt] = useState({ x: req.atX ?? 0, y: req.atY ?? 0 });
  // Opens down and right from the pointer, turning back where that would leave the window
  useLayoutEffect(() => {
    const el = menu.current;
    if (!el) return;
    const x = Math.min(req.atX ?? 0, window.innerWidth - el.offsetWidth - 8);
    const y = Math.min(req.atY ?? 0, window.innerHeight - el.offsetHeight - 8);
    setAt({ x: Math.max(8, x), y: Math.max(8, y) });
  }, [req]);
  return (
    <div class="backdrop anchored" onMouseDown={e => { if (e.target === e.currentTarget) answer([]); }}
      onContextMenu={e => { e.preventDefault(); answer([]); }}>
      <div ref={menu} class="card-menu" role="menu" aria-label={req.message ?? t('lblAbilities')} style={{ left: `${at.x}px`, top: `${at.y}px` }}>
        {req.message && <p class="card-menu-title">{req.message}</p>}
        {req.options.map((o, i) => (
          <button key={i} type="button" role="menuitem" class="card-menu-item" onClick={() => answer([i])}>
            {i < 9 ? <kbd>{i + 1}</kbd> : <span class="card-menu-gap" />}
            <span><SymbolText text={o.label} /></span>
          </button>
        ))}
      </div>
    </div>
  );
}

function RequestDialog({ req, model, answer }: { req: Request; model: Model; answer: Answer }) {
  const body = (() => {
    switch (req.kind) {
      case 'choices':
      case 'reveal': return <Choices req={req} model={model} answer={answer} />;
      case 'order': return <Order req={req} model={model} answer={answer} />;
      case 'manipulate': return <Manipulate req={req} model={model} answer={answer} />;
      case 'option': return <Option req={req} model={model} answer={answer} />;
      case 'text': return <Text req={req} answer={answer} />;
      case 'distribute': return <Distribute req={req} model={model} answer={answer} />;
      case 'sideboard': return <Sideboard req={req} model={model} answer={answer} />;
      default: {
        // A kind this browser does not know yet still gets an answer, so the game is never left waiting
        const unknown = req as { default?: unknown };
        return <ButtonRow><Button primary onClick={() => answer(unknown.default)}>{t('lblOK')}</Button></ButtonRow>;
      }
    }
  })();
  // Not every kind of question has a title or a message; the first there is heads the dialog
  const heading = 'title' in req ? req.title : undefined;
  const message = 'message' in req ? req.message : undefined;
  // A list of what one card can do belongs on that card, the way desktop opens its menu under the cursor
  const at = (req.kind === 'choices' || req.kind === 'reveal') && (req.atX || req.atY) ? { x: req.atX ?? 0, y: req.atY ?? 0 } : null;
  return (
    <div class={at ? 'backdrop anchored' : 'backdrop'}>
      <div class={at ? 'dialog at-card' : 'dialog'} style={at ? { left: `${at.x}px`, top: `${at.y}px` } : undefined}>
        <h3><SymbolText text={heading || message || ''} /></h3>
        {heading && message && <p><SymbolText text={message} /></p>}
        {body}
      </div>
    </div>
  );
}

function Button({ primary, disabled, onClick, children }: {
  primary?: boolean; disabled?: boolean; onClick: () => void; children: ComponentChildren;
}) {
  return <button class={primary ? 'primary' : ''} disabled={disabled} onClick={onClick}>{children}</button>;
}

function ButtonRow({ children }: { children: ComponentChildren }) {
  return <div class="actions">{children}</div>;
}

// A deck list arrives with its sections marked out as entries of their own, which read as headings, not choices
const SECTION = /^=+\s*(.*?)\s*=+$/;

/**
 * Cards laid out to be looked at, in the window a zone opens in, with OK (or Space) to put them away. It folds to a
 * bar, as a zone's window does, so the board under it can be read before answering.
 */
export function RevealWindow({ model, title, cards, close }: { model: Model; title: string; cards: RequestOption[]; close: () => void }) {
  const [folded, setFolded] = useState(false);
  const count = cards.filter(c => c.card || c.imageKey || !SECTION.test(c.label ?? '')).length;
  const counted = t(count === 1 ? 'lblWebOneCard' : 'lblWebNCards', count);
  if (folded) {
    return (
      <div class="reveal-back minimised">
        <section class="zone-bar">
          <span class="zone-dot" aria-hidden="true" /><b><SymbolText text={title} /></b><span class="zone-count">{counted}</span>
          <button class="zone-unfold primary" onClick={() => setFolded(false)}>{t('lblWebZoneShowCards')}</button>
        </section>
      </div>
    );
  }
  return (
    <div class="reveal-back" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <section class="zone-panel reveal-panel" role="dialog" aria-label={title}>
        <header><b class="zone-who"><SymbolText text={title} /></b><span class="zone-count">{counted}</span><span class="zone-gap" />
          <button class="zone-fold" onClick={() => setFolded(true)}>{t('lblWebZoneShowBoard')}</button></header>
        <div class="cards">{cards.map((o, i) => <OptionView key={i} model={model} opt={o} />)}</div>
        <footer><span class="zone-hint" /><button class="zone-answer ok primary" onClick={close}><span class="label">{t('lblOK')}</span><kbd>{keyName(boundKeys().ok)}</kbd></button></footer>
      </section>
    </div>
  );
}

/** What an option can show: a card on the table, a card by image and name, or a line of text. */
type OptionLike = Pick<RequestOption, 'card' | 'name' | 'imageKey' | 'player'> & { label?: string };

function OptionView({ model, opt, picked, onClick }: { model: Model; opt: OptionLike; picked?: boolean; onClick?: () => void }) {
  const section = SECTION.exec(opt.label ?? '');
  if (section && !opt.card && !opt.imageKey) {
    return <div class="section">{section[1]}</div>;
  }
  const player = opt.player ? model.objects.get(opt.player.ref) as PlayerView | undefined : undefined;
  if (player) {
    return <PlayerOption model={model} player={player} picked={picked} onClick={onClick} />;
  }
  const card = opt.card ? model.objects.get(opt.card.ref) : null;
  if (card) {
    return <BoardCard model={model} card={card} title={opt.label} picked={picked} onClick={onClick} />;
  }
  if (opt.imageKey || opt.name) {
    return <InlineCard imageKey={opt.imageKey} name={opt.name ?? opt.label} title={opt.label ?? opt.name}
      picked={picked} onClick={onClick} />;
  }
  return <button class={picked ? 'text-option picked' : 'text-option'} onClick={onClick}><SymbolText text={opt.label} /></button>;
}

/** A player as the board shows them: their portrait, or their initial without one, with their life and name. */
function PlayerOption({ model, player, picked, onClick }: { model: Model; player: PlayerView; picked?: boolean; onClick?: () => void }) {
  const [broken, setBroken] = useState(false);
  const src = playerAvatarUrl(player);
  const name = player.Name ?? '';
  return (
    <button class={`player-option${picked ? ' picked' : ''}${isLocal(model, player) ? ' own' : ''}`} onClick={onClick} title={name}>
      <span class="po-avatar">
        {src && !broken ? <img src={src} alt="" draggable={false} onError={() => setBroken(true)} /> : <span class="po-initial">{name.slice(0, 1).toUpperCase()}</span>}
        <span class="po-life">{player.Life ?? 0}</span>
      </span>
      <span class="po-name">{name}</span>
    </button>
  );
}

/** A card on the table, drawn by the board's own card code so it looks and updates exactly as it does there. */
function BoardCard({ model, card, title, picked, onClick }: {
  model: Model; card: TrackedObject; title?: string; picked?: boolean; onClick?: () => void;
}) {
  const host = useRef<HTMLSpanElement>(null);
  const el = useRef<HTMLDivElement | null>(null);
  const click = useRef(onClick);
  click.current = onClick;
  useLayoutEffect(() => {
    let c = el.current;
    if (!c) {
      c = createCard(() => click.current?.());
      el.current = c;
      host.current?.append(c);
    }
    c.dataset.key = String(card.$key);
    c.title = title ?? '';
    updateCard(c, model, card);
    c.classList.toggle('picked', !!picked);
  });
  return <span class="card-host" ref={host} />;
}

/** A card that is not on the table: an image and a name, and the frame alone when there is no image. */
function InlineCard({ imageKey, name, title, picked, onClick }: {
  imageKey?: string; name?: string; title?: string; picked?: boolean; onClick?: () => void;
}) {
  const [broken, setBroken] = useState(!imageKey);
  const src = imageKey ? imageUrl(imageKey) : undefined;
  return (
    <div class={`card inline${broken ? ' noimg' : ''}${picked ? ' picked' : ''}`} title={title ?? ''} data-zoom={src}
      onClick={onClick} onMouseEnter={e => hoverCard(e.currentTarget)} onMouseLeave={() => hoverCard(null)}>
      <img alt="" loading="lazy" src={src} onError={() => setBroken(true)} />
      <div class="frame">{name}</div>
    </div>
  );
}

// Naming a card offers every card face, so long lists get a search box and draw only the first matches
const SEARCH_FROM = 20;
const SHOW_AT_MOST = 200;

function Choices({ req, model, answer }: { req: ChoicesRequest; model: Model; answer: Answer }) {
  const reveal = req.kind === 'reveal';
  const [picked, setPicked] = useState<ReadonlySet<number>>(() => new Set(reveal ? [] : req.selected));
  const [typed, setTyped] = useState('');
  const query = useDebounced(typed);
  const searchable = req.options.length > SEARCH_FROM;
  const search = useRef<HTMLInputElement>(null);
  useEffect(() => {
    search.current?.focus();
  }, []);
  const toggle = (i: number) => {
    if (reveal) return;
    setPicked(old => {
      const next = new Set(req.max === 1 ? [] : old);
      if (old.has(i)) next.delete(i);
      else next.add(i);
      return next;
    });
  };
  const matches = rankByName(req.options.map(o => String(o.label ?? o.name ?? '')), query);
  const shown = matches.slice(0, SHOW_AT_MOST);
  const note = matches.length > SHOW_AT_MOST ? t('lblWebDialogShowingFirst', SHOW_AT_MOST, req.options.length)
    : searchable && picked.size ? t('lblWebDialogSelected', picked.size) : '';
  const ready = reveal || (picked.size >= req.min && (req.max < 0 || picked.size <= req.max));
  return (
    <>
      {searchable && <input ref={search} class="choice-search" placeholder={t('lblWebDialogSearchOptions', req.options.length)}
        value={typed} onInput={e => setTyped(e.currentTarget.value)} />}
      <div class={reveal ? 'options grid' : 'options'}>
        {shown.map(i => <OptionView key={i} model={model} opt={req.options[i]} picked={picked.has(i)} onClick={() => toggle(i)} />)}
      </div>
      <p class="hint">{note}</p>
      <ButtonRow><Button primary disabled={!ready} onClick={() => answer(reveal ? [] : [...picked])}>{reveal ? t('lblOK') : t('lblWebDialogConfirm')}</Button></ButtonRow>
    </>
  );
}

function Order({ req, model, answer }: { req: OrderRequest; model: Model; answer: Answer }) {
  const [chosen, setChosen] = useState<number[]>(() => [...req.selected]);
  const [remember, setRemember] = useState(false);
  const [dropAt, setDropAt] = useState<number | null>(null);
  const dragFrom = useRef<number | null>(null);
  const move = (from: number, to: number) => setChosen(list => {
    const next = [...list];
    next.splice(to, 0, next.splice(from, 1)[0]);
    return next;
  });
  return (
    <>
      <p class="hint">{req.top || t('lblWebDialogPickInOrder')}</p>
      <div class="options">
        {req.options.map((o, i) => chosen.includes(i) ? null
          : <OptionView key={i} model={model} opt={o} onClick={() => setChosen(list => [...list, i])} />)}
      </div>
      <p class="hint">{t('lblWebDialogChosenOrder')}</p>
      <div class="options ordered">
        {chosen.map((i, pos) => (
          <div key={i} class={dropAt === pos ? 'ordered-item drop-here' : 'ordered-item'} draggable
            onDragStart={e => {
              dragFrom.current = pos;
              if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
            }}
            onDragOver={e => {
              e.preventDefault();
              setDropAt(pos);
            }}
            onDragLeave={() => setDropAt(null)}
            onDrop={e => {
              e.preventDefault();
              setDropAt(null);
              if (dragFrom.current !== null && dragFrom.current !== pos) move(dragFrom.current, pos);
              dragFrom.current = null;
            }}>
            {/* The number is the order they go back in, which is the question the dialog is asking */}
            <span class="order-number">{pos + 1}</span>
            <OptionView model={model} opt={req.options[i]} onClick={() => setChosen(list => list.filter(c => c !== i))} />
            <Button onClick={() => { if (pos > 0) move(pos, pos - 1); }}>↑</Button>
          </div>
        ))}
      </div>
      {req.remember && <label><input type="checkbox" checked={remember} onChange={e => setRemember(e.currentTarget.checked)} /> {t('lblWebDialogRememberOrder')}</label>}
      <ButtonRow>
        <Button primary disabled={chosen.length < req.min || chosen.length > req.max}
          onClick={() => answer({ indices: chosen, remember })}>{t('lblWebDialogConfirm')}</Button>
      </ButtonRow>
    </>
  );
}

// Scry and friends: the host passes the whole library with only the top cards movable.
// Movable cards placed after the untouched middle go to the bottom (PlayerControllerHuman.arrangeForMove).
type Shelf = 'top' | 'bottom';

/**
 * Where the cards you are looking at go back to: a shelf for the top of the library and one for the bottom, with
 * the rest of the library drawn between them so the two ends mean something. Drag a card to the other shelf or
 * past its neighbours; the button does the same for touch and the keyboard.
 */
function Manipulate({ req, model, answer }: { req: ManipulateRequest; model: Model; answer: Answer }) {
  const all = req.options.map((_, i) => i);
  const rest = all.filter(i => !req.movable.includes(i));
  const [piles, setPiles] = useState(() => ({ top: all.filter(i => req.movable.includes(i)), bottom: [] as number[] }));
  const held = useRef<number | null>(null);
  const bothEnds = req.toBottom || req.toTop;

  const place = (shelf: Shelf, at: number) => {
    const card = held.current;
    held.current = null;
    if (card === null) return;
    setPiles(p => {
      const next = { top: p.top.filter(i => i !== card), bottom: p.bottom.filter(i => i !== card) };
      next[shelf].splice(Math.min(at, next[shelf].length), 0, card);
      return next;
    });
  };
  const send = (from: Shelf, pos: number) => setPiles(p => {
    const card = p[from][pos];
    const to: Shelf = from === 'top' ? 'bottom' : 'top';
    const next = { top: [...p.top], bottom: [...p.bottom] };
    next[from] = next[from].filter((_, k) => k !== pos);
    next[to] = [...next[to], card];
    return next;
  });

  const shelf = (name: Shelf, label: string, note: string) => (
    <div class={`shelf ${name}`} onDragOver={e => e.preventDefault()} onDrop={() => place(name, piles[name].length)}>
      <p class="shelf-label"><b>{label}</b><span>{note}</span></p>
      <div class="shelf-cards">
        {piles[name].map((i, pos) => (
          <div key={i} class="shelf-card" draggable onDragStart={() => { held.current = i; }}
            onDragOver={e => e.preventDefault()} onDrop={e => { e.stopPropagation(); place(name, pos); }}>
            <span class="seq">{pos + 1}</span>
            <OptionView model={model} opt={req.options[i]} />
            {bothEnds && <button class="send" onClick={() => send(name, pos)}
              title={name === 'top' ? t('lblWebDialogPutOnBottom') : t('lblWebDialogPutOnTop')}>
              {name === 'top' ? t('lblWebDialogToBottom') : t('lblWebDialogToTop')}</button>}
          </div>
        ))}
        {piles[name].length === 0 && <p class="shelf-empty">{t('lblWebDialogNothingHere')}</p>}
      </div>
    </div>
  );

  return (
    <div class="manipulate">
      {shelf('top', t('lblWebDialogTopOfLibrary'), t('lblWebDialogDrawnFirst'))}
      <p class="between">{t('lblWebDialogOtherCards', rest.length)}</p>
      {bothEnds && shelf('bottom', t('lblWebDialogBottomOfLibrary'), t('lblWebDialogLastFurthestDown'))}
      <ButtonRow><Button primary onClick={() => answer([...piles.top, ...rest, ...piles.bottom])}>{t('lblWebDialogConfirm')}</Button></ButtonRow>
    </div>
  );
}

function Option({ req, model, answer }: { req: OptionRequest; model: Model; answer: Answer }) {
  const card = req.card ? model.objects.get(req.card.ref) : undefined;
  return (
    <>
      {card && <BoardCard model={model} card={card} />}
      <ButtonRow>{req.labels.map((label, i) => <Button key={i} primary={i === req.default} onClick={() => answer(i)}>{label}</Button>)}</ButtonRow>
    </>
  );
}

function Text({ req, answer }: { req: TextRequest; answer: Answer }) {
  const [value, setValue] = useState(req.initial ?? '');
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    input.current?.focus();
  }, []);
  return (
    <>
      <input ref={input} type={req.numeric ? 'number' : 'text'} value={value} onInput={e => setValue(e.currentTarget.value)} />
      <ButtonRow><Button primary onClick={() => answer(value)}>{t('lblOK')}</Button></ButtonRow>
    </>
  );
}

/** The split shown in the open distribute dialog, for the OK key to confirm; ready once every point is assigned. */
let shownSplit: { id: number; values: number[]; ready: boolean } | null = null;

/** The split the OK key confirms, or null when there is none or it is not all assigned yet. */
export function readySplit(): { id: number; values: number[] } | null {
  return shownSplit?.ready ? shownSplit : null;
}

function Distribute({ req, model, answer }: { req: DistributeRequest; model: Model; answer: Answer }) {
  // It opens on desktop's Auto split, lethal damage to each in turn and the rest onward, for the player to change
  const [values, setValues] = useState(() => [...req.default]);
  useEffect(() => () => {
    if (shownSplit?.id === req.id) shownSplit = null;
  }, []);
  const step = (i: number, delta: number) => setValues(v => {
    if (v[i] + delta < req.perMin) return v;
    const next = [...v];
    next[i] += delta;
    return next;
  });
  const left = req.amount - values.reduce((a, b) => a + b, 0);
  shownSplit = { id: req.id, values, ready: left === 0 };
  // Players first, opponents before you, then the cards; each keeps its own place in the answer
  const rank = (i: number) => {
    const ref = req.options[i].player;
    const player = ref ? model.objects.get(ref.ref) as PlayerView | undefined : undefined;
    return !player ? 2 : isLocal(model, player) ? 1 : 0;
  };
  const order = req.options.map((_, i) => i).sort((a, b) => rank(a) - rank(b) || a - b);
  // Side by side, each with its count under it, as the cards stand on the table
  return (
    <>
      <div class="dist-row">
        {order.map(i => (
          <div key={i} class="dist-target">
            <OptionView model={model} opt={req.options[i]} />
            <div class="dist-step">
              <Button onClick={() => step(i, -1)}>−</Button>
              <b>{values[i]}</b>
              <Button onClick={() => step(i, 1)}>+</Button>
            </div>
          </div>
        ))}
      </div>
      <p class="hint">{t('lblWebDialogLeftToAssign', left)}</p>
      <ButtonRow>
        {req.maySkip && <Button onClick={() => answer(null)}>{t('lblSkip')}</Button>}
        <Button onClick={() => setValues([...req.default])}>{t('lblReset')}</Button>
        <Button primary disabled={left !== 0} onClick={() => answer(values)}>{t('lblWebDialogConfirm')} <kbd>{keyName(boundKeys().ok)}</kbd></Button>
      </ButtonRow>
    </>
  );
}

// Between games: move copies between the main deck and the sideboard. The host re-asks if the deck is illegal
function Sideboard({ req, model, answer }: { req: SideboardRequest; model: Model; answer: Answer }) {
  const [inMain, setInMain] = useState(() => [...req.main]);
  const move = (i: number, delta: number) => setInMain(m => m.map((n, k) => (k === i ? n + delta : n)));
  const row = (i: number, count: number, arrow: string, delta: number) => (
    <div key={i} class="sb-row">
      <OptionView model={model} opt={req.entries[i]} onClick={() => move(i, delta)} />
      <span class="sb-name">{req.entries[i].name}</span>
      <span class="sb-count">{`×${count}`}</span>
      <Button onClick={() => move(i, delta)}>{arrow}</Button>
    </div>
  );
  const mainTotal = inMain.reduce((a, b) => a + b, 0);
  const sideTotal = req.entries.reduce((a, e, i) => a + e.total - inMain[i], 0);
  return (
    <>
      <p class="hint">{t('lblWebDialogMoveOneCopy')}</p>
      <div class="sb-columns">
        <div>
          <h4>{t('lblWebDialogMainDeckCount', mainTotal)}</h4>
          <div class="sb-list">{req.entries.map((_, i) => (inMain[i] > 0 ? row(i, inMain[i], '→', -1) : null))}</div>
        </div>
        <div>
          <h4>{t('lblSideboardNCards', sideTotal)}</h4>
          <div class="sb-list">{req.entries.map((e, i) => (e.total - inMain[i] > 0 ? row(i, e.total - inMain[i], '←', 1) : null))}</div>
        </div>
      </div>
      <ButtonRow>
        <Button onClick={() => setInMain([...req.main])}>{t('lblReset')}</Button>
        <Button primary onClick={() => answer(inMain)}>{t('lblDone')}</Button>
      </ButtonRow>
    </>
  );
}
