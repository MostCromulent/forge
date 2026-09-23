// The game's open questions, shown one at a time, oldest first. Answering one takes it out of the model at once,
// so its dialog closes without waiting for the server.

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import { createCard, updateCard } from './cards';
import { imageUrl } from './images';
import { hoverCard } from './detail';
import { SymbolText } from './symbols';
import type { Actions } from './actions';
import { oldestRequest, stackPick, type Model } from './model';
import type {
  ChoicesRequest, DistributeRequest, ManipulateRequest, OptionRequest, OrderRequest, Request, RequestOption,
  SideboardRequest, TextRequest, TrackedObject,
} from './protocol';

type Answer = (value: unknown) => void;

export function Requests({ model, actions }: { model: Model; actions: Actions }) {
  const req = oldestRequest(model);
  // Choosing between spells that are on the stack is done on the stack, so no list is drawn for it
  if (!req || stackPick(model) === req) {
    return null;
  }
  // Keyed by the question, so nothing picked for one is still picked for the next
  return <RequestDialog key={req.id} req={req} model={model} answer={value => actions.answer(req.id, value)} />;
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
        return <Actions><Button primary onClick={() => answer(unknown.default)}>OK</Button></Actions>;
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

function Actions({ children }: { children: ComponentChildren }) {
  return <div class="actions">{children}</div>;
}

// A deck list arrives with its sections marked out as entries of their own, which read as headings, not choices
const SECTION = /^=+\s*(.*?)\s*=+$/;

/** What an option can show: a card on the table, a card by image and name, or a line of text. */
type OptionLike = Pick<RequestOption, 'card' | 'name' | 'imageKey'> & { label?: string };

function OptionView({ model, opt, picked, onClick }: { model: Model; opt: OptionLike; picked?: boolean; onClick?: () => void }) {
  const section = SECTION.exec(opt.label ?? '');
  if (section && !opt.card && !opt.imageKey) {
    return <div class="section">{section[1]}</div>;
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
// Drawing a long list costs an image request per option, so typing waits for a pause
const SEARCH_DELAY_MS = 200;

function Choices({ req, model, answer }: { req: ChoicesRequest; model: Model; answer: Answer }) {
  const reveal = req.kind === 'reveal';
  const [picked, setPicked] = useState<ReadonlySet<number>>(() => new Set(reveal ? [] : req.selected));
  const [typed, setTyped] = useState('');
  const [query, setQuery] = useState('');
  const searchable = req.options.length > SEARCH_FROM;
  const search = useRef<HTMLInputElement>(null);
  useEffect(() => {
    search.current?.focus();
  }, []);
  useEffect(() => {
    const timer = setTimeout(() => setQuery(typed.trim().toLowerCase()), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [typed]);
  const toggle = (i: number) => {
    if (reveal) return;
    setPicked(old => {
      const next = new Set(req.max === 1 ? [] : old);
      if (old.has(i)) next.delete(i);
      else next.add(i);
      return next;
    });
  };
  const matches: number[] = [];
  for (let i = 0; i < req.options.length && matches.length <= SHOW_AT_MOST; i++) {
    const o = req.options[i];
    if (!query || String(o.label ?? o.name ?? '').toLowerCase().includes(query)) matches.push(i);
  }
  const shown = matches.slice(0, SHOW_AT_MOST);
  const note = matches.length > SHOW_AT_MOST ? `Showing the first ${SHOW_AT_MOST} of ${req.options.length}. Type to narrow the list.`
    : searchable && picked.size ? `${picked.size} selected` : '';
  const ready = reveal || (picked.size >= req.min && (req.max < 0 || picked.size <= req.max));
  return (
    <>
      {searchable && <input ref={search} class="choice-search" placeholder={`Search ${req.options.length} options`}
        value={typed} onInput={e => setTyped(e.currentTarget.value)} />}
      <div class={reveal ? 'options grid' : 'options'}>
        {shown.map(i => <OptionView key={i} model={model} opt={req.options[i]} picked={picked.has(i)} onClick={() => toggle(i)} />)}
      </div>
      <p class="hint">{note}</p>
      <Actions><Button primary disabled={!ready} onClick={() => answer(reveal ? [] : [...picked])}>{reveal ? 'OK' : 'Confirm'}</Button></Actions>
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
      <p class="hint">{req.top || 'Pick in order'}</p>
      <div class="options">
        {req.options.map((o, i) => chosen.includes(i) ? null
          : <OptionView key={i} model={model} opt={o} onClick={() => setChosen(list => [...list, i])} />)}
      </div>
      <p class="hint">Chosen, first to last. Drag to reorder.</p>
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
      {req.remember && <label><input type="checkbox" checked={remember} onChange={e => setRemember(e.currentTarget.checked)} /> Remember this order</label>}
      <Actions>
        <Button primary disabled={chosen.length < req.min || chosen.length > req.max}
          onClick={() => answer({ indices: chosen, remember })}>Confirm</Button>
      </Actions>
    </>
  );
}

// Scry and friends: the host passes the whole library with only the top cards movable.
// Movable cards placed after the untouched middle go to the bottom (PlayerControllerHuman.arrangeForMove).
function Manipulate({ req, model, answer }: { req: ManipulateRequest; model: Model; answer: Answer }) {
  const all = req.options.map((_, i) => i);
  const rest = all.filter(i => !req.movable.includes(i));
  const [piles, setPiles] = useState(() => ({ top: all.filter(i => req.movable.includes(i)), bottom: [] as number[] }));
  const raise = (pile: 'top' | 'bottom', pos: number) => setPiles(p => {
    const list = [...p[pile]];
    if (pos > 0) [list[pos - 1], list[pos]] = [list[pos], list[pos - 1]];
    return { ...p, [pile]: list };
  });
  const toBottom = (pos: number) => setPiles(p => ({ top: p.top.filter((_, k) => k !== pos), bottom: [...p.bottom, p.top[pos]] }));
  const toTop = (pos: number) => setPiles(p => ({ bottom: p.bottom.filter((_, k) => k !== pos), top: [...p.top, p.bottom[pos]] }));
  const pile = (name: 'top' | 'bottom') => (
    <div class="options ordered">
      {piles[name].map((i, pos) => (
        <div key={i} class="ordered-item">
          <OptionView model={model} opt={req.options[i]} />
          <Button onClick={() => raise(name, pos)}>↑</Button>
          {name === 'top' && req.toBottom && <Button onClick={() => toBottom(pos)}>To bottom</Button>}
          {name === 'bottom' && req.toTop && <Button onClick={() => toTop(pos)}>To top</Button>}
        </div>
      ))}
    </div>
  );
  return (
    <>
      <p class="hint">Top of library, first is on top</p>
      {pile('top')}
      <p class="hint">{`${rest.length} other cards`}</p>
      <p class="hint">Bottom of library, last is at the bottom</p>
      {pile('bottom')}
      <Actions><Button primary onClick={() => answer([...piles.top, ...rest, ...piles.bottom])}>Confirm</Button></Actions>
    </>
  );
}

function Option({ req, model, answer }: { req: OptionRequest; model: Model; answer: Answer }) {
  const card = req.card ? model.objects.get(req.card.ref) : undefined;
  return (
    <>
      {card && <BoardCard model={model} card={card} />}
      <Actions>{req.labels.map((label, i) => <Button key={i} primary={i === req.default} onClick={() => answer(i)}>{label}</Button>)}</Actions>
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
      <Actions><Button primary onClick={() => answer(value)}>OK</Button></Actions>
    </>
  );
}

function Distribute({ req, model, answer }: { req: DistributeRequest; model: Model; answer: Answer }) {
  const [values, setValues] = useState(() => [...req.default]);
  const step = (i: number, delta: number) => setValues(v => {
    if (v[i] + delta < req.perMin) return v;
    const next = [...v];
    next[i] += delta;
    return next;
  });
  const left = req.amount - values.reduce((a, b) => a + b, 0);
  return (
    <>
      {req.options.map((opt, i) => (
        <div key={i} class="row">
          <OptionView model={model} opt={opt} />
          <Button onClick={() => step(i, -1)}>−</Button>
          <b>{values[i]}</b>
          <Button onClick={() => step(i, 1)}>+</Button>
        </div>
      ))}
      <p class="hint">{`${left} left to assign`}</p>
      <Actions>
        {req.maySkip && <Button onClick={() => answer(null)}>Skip</Button>}
        <Button primary disabled={left !== 0} onClick={() => answer(values)}>Confirm</Button>
      </Actions>
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
      <p class="hint">Click a card or its arrow to move one copy.</p>
      <div class="sb-columns">
        <div>
          <h4>{`Main deck (${mainTotal})`}</h4>
          <div class="sb-list">{req.entries.map((_, i) => (inMain[i] > 0 ? row(i, inMain[i], '→', -1) : null))}</div>
        </div>
        <div>
          <h4>{`Sideboard (${sideTotal})`}</h4>
          <div class="sb-list">{req.entries.map((e, i) => (e.total - inMain[i] > 0 ? row(i, e.total - inMain[i], '←', 1) : null))}</div>
        </div>
      </div>
      <Actions>
        <Button onClick={() => setInMain([...req.main])}>Reset</Button>
        <Button primary onClick={() => answer(inMain)}>Done</Button>
      </Actions>
    </>
  );
}
