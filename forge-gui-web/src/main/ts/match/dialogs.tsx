// The game's open questions, shown oldest first, where answering one closes its dialog without waiting for the server

import { useContext, useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { createContext, type ComponentChildren, type RefObject } from 'preact';
import { createCard, updateCard } from './cards';
import { imageUrl, symbolUrl } from '../images';
import { hoverCard } from '../preview';
import { matchedSpan, rankByName } from '../search';
import { COLOURS, SymbolText } from '../symbols';
import { useDebounced } from '../hooks';
import type { Actions } from '../actions';
import { keyName } from '../keys';
import { boundKeys } from '../settings';
import { cssUrl, playerAvatarUrl, playerSleeveUrl } from '../looks';
import { askingCard, cardMenu, cardPrompt, deref, derefAll, game, isLocal, me, oldestRequest, stackPick, stateOf, type Model } from '../model';
import { splitQuestion } from './asking';
import { zoneTitle } from './zones';
import { changeUi, ui } from '../ui';
import { MenuIcon } from './gamemenu';
import { ICONS } from '../icons';
import type {
  ChoicesRequest, DistributeRequest, ManipulateRequest, NumberRequest, OptionRequest, OrderRequest, Request, RequestOption,
  CardView, PlayerView, SideboardRequest, StackItemView, TextRequest, TrackedObject, YieldAction,
} from '../protocol';
import { t } from '../text';
import { make, reducedMotion } from '../dom';
import { isPortrait } from '../form';
import { cutFor } from './stickers';
import { Backdrop } from '../dialog';
import { trackDrag } from '../press';

type Answer = (value: unknown) => void;

// Cards on the board grow into the room it leaves them, up to about 200px wide, where their text reads easily
const MOST_FIT = 2.3;

/**
 * How much to enlarge a question's cards so they fill the room given. Cards are gap pixels apart, and each has below
 * pixels of buttons under it. Wrapping rows are tried when allowed, and the layout with the biggest cards wins.
 */
function cardScale(count: number, width: number, height: number, card: { w: number; h: number }, gap: number, below: number, wrap: boolean): number {
  let best = 0;
  for (let rows = 1; rows <= (wrap ? count : 1); rows++) {
    const cols = Math.ceil(count / rows);
    best = Math.max(best, Math.min((width - gap * (cols - 1)) / cols / card.w, (height - gap * (rows - 1) - below * rows) / rows / card.h));
  }
  return Math.max(1, Math.min(MOST_FIT, best));
}

/** Enlarges the cards in a row of a question on the board to fill the space the rest of the question leaves. */
function useCardFit(row: RefObject<HTMLElement>, count: number, gap: number, below: number, wrap: boolean): void {
  useLayoutEffect(() => {
    const el = row.current;
    const back = el?.closest<HTMLElement>('.backdrop.on-board');
    const dialog = el?.closest<HTMLElement>('.dialog');
    if (!el || !back || !dialog || !count) return;
    const fit = () => {
      const around = getComputedStyle(back);
      const own = getComputedStyle(el);
      const card = { w: parseFloat(own.getPropertyValue('--card-w')), h: parseFloat(own.getPropertyValue('--card-h')) };
      const width = back.clientWidth - parseFloat(around.paddingLeft) - parseFloat(around.paddingRight)
        - parseFloat(own.paddingLeft) - parseFloat(own.paddingRight) - 16;
      // The row's padding holds the labels under its cards, and the rest of the question keeps its height
      const used = dialog.offsetHeight - el.clientHeight + parseFloat(own.paddingTop) + parseFloat(own.paddingBottom);
      const height = back.clientHeight - parseFloat(around.paddingTop) - parseFloat(around.paddingBottom) - used - 16;
      el.style.setProperty('--fit', cardScale(count, width, height, card, gap, below, wrap).toFixed(2));
    };
    fit();
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, [count]);
}

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
    return <RevealWindow key={req.id} model={model} title={req.message ?? ''} cards={req.options} close={() => actions.answer(req.id, [])}
      aiSkipsOf={req.aiSkipsOf ? model.objects.get(req.aiSkipsOf.ref) as PlayerView | undefined : undefined} />;
  }
  // Keyed by the question, so nothing picked for one is still picked for the next
  return <RequestDialog key={req.id} req={req} model={model} answer={value => actions.answer(req.id, value)} />;
}

/** A menu at the pointer, which a press or a right-click anywhere else closes having chosen nothing. A phone draws it along the bottom edge (portrait.css). */
function PointMenu({ x, y, id, cls, label, title, dismiss, children }: {
  x: number; y: number; id?: string; cls: string; label: string; title?: string | null; dismiss: () => void; children: ComponentChildren;
}) {
  const menu = useRef<HTMLDivElement>(null);
  const [at, setAt] = useState({ x, y });
  // Opens down and right from the pointer, turning back where that would leave the window
  useLayoutEffect(() => {
    const el = menu.current;
    if (!el) return;
    setAt({ x: Math.max(8, Math.min(x, window.innerWidth - el.offsetWidth - 8)), y: Math.max(8, Math.min(y, window.innerHeight - el.offsetHeight - 8)) });
  }, [x, y]);
  return (
    <Backdrop cls="anchored" dismiss={dismiss} onContextMenu={e => { e.preventDefault(); dismiss(); }}>
      <div ref={menu} id={id} class={cls} role="menu" aria-label={label} style={{ left: `${at.x}px`, top: `${at.y}px` }}>
        {title && <p class="card-menu-title">{title}</p>}
        {children}
      </div>
    </Backdrop>
  );
}

/** What one right-clicked card can do, as the host lists it. */
function CardMenu({ req, answer }: { req: ChoicesRequest; answer: Answer }) {
  return (
    <PointMenu x={req.atX ?? 0} y={req.atY ?? 0} cls="card-menu" label={req.message ?? t('lblAbilities')} title={req.message} dismiss={() => answer([])}>
      {req.options.map((o, i) => (
        <button key={i} type="button" role="menuitem" class="card-menu-item" onClick={() => answer([i])}>
          {i < 9 ? <kbd>{i + 1}</kbd> : <span class="card-menu-gap" />}
          <span><SymbolText text={o.label} /></span>
        </button>
      ))}
    </PointMenu>
  );
}

/** Desktop's stack menu for a right-clicked item, once the host has said what applies to it: auto-yield, always accept or decline an optional trigger, and the yields. */
export function StackMenu({ model, actions }: { model: Model; actions: Actions }) {
  const at = ui.stackMenuAt;
  const answer = model.stackMenu;
  if (!at || !answer || answer.key !== at.key) return null;
  const close = () => changeUi(u => { u.stackMenuAt = null; });
  const items: StackItemView[] = derefAll(model, game(model)?.Stack);
  const source = deref(model, items.find(i => i.$key === at.key)?.SourceCard);
  // A choice the game remembers carries a tick, shown or not, so every label starts at the same place as a pass's
  const item = (label: string, action: YieldAction, checked?: boolean) => (
    <button type="button" class="card-menu-item" role={checked === undefined ? 'menuitem' : 'menuitemcheckbox'} aria-checked={checked}
      onClick={() => { actions.stackYield(answer.key, action); close(); }}>
      <MenuIcon d={checked === undefined ? ICONS.yields : ICONS.check} cls={checked === undefined ? undefined : 'tick'} />{label}
    </button>
  );
  const remembered = answer.autoYield !== undefined || answer.trigger !== undefined;
  return (
    <PointMenu x={at.x} y={at.y} id="stack-menu" cls="card-menu game-menu" label={t('lblWebPortraitStackOptions')}
      title={source && stateOf(model, source).Name} dismiss={close}>
      {answer.autoYield !== undefined && item(t('lblWebStackAutoYield'), 'autoYield', answer.autoYield)}
      {answer.trigger !== undefined && item(t('lblWebStackAlwaysAccept'), 'alwaysYes', answer.trigger === 'ACCEPT')}
      {answer.trigger !== undefined && item(t('lblWebStackAlwaysDecline'), 'alwaysNo', answer.trigger === 'DECLINE')}
      {remembered && <div class="card-menu-sep" role="separator" />}
      {item(t('lblWebStackYieldUntilResolves'), 'yieldToStack')}
      {item(t('lblWebStackYieldUntilEmpty'), 'yieldToEntireStack')}
    </PointMenu>
  );
}

/** The questions that lay out cards, which can fold away to show the board. */
const FOLDABLE = new Set<Request['kind']>(['choices', 'reveal', 'order', 'manipulate', 'distribute']);

/** The open question's answer as it stands, for the OK key to give; null while it cannot be given yet. */
let shownAnswer: { id: number; give: (() => void) | null } | null = null;

/** Called as a question is drawn, with how to give the answer shown, or null when it is not ready. */
function offerAnswer(id: number, give: (() => void) | null): void {
  shownAnswer = { id, give };
}

/** Gives the answer the open question shows, when it is that question and the answer is ready. */
export function confirmShown(id: number): void {
  if (shownAnswer?.id === id) shownAnswer.give?.();
}

/** The way to fold a question laid out on the board, which goes beside its answer rather than in a heading. */
const FoldButton = createContext<ComponentChildren>(null);

function RequestDialog({ req, model, answer }: { req: Request; model: Model; answer: Answer }) {
  const body = (() => {
    switch (req.kind) {
      case 'choices': return onlyWords(req)
        ? <WordChoices req={req} model={model} answer={answer} />
        : <Choices req={req} model={model} answer={answer} />;
      case 'reveal': return <Choices req={req} model={model} answer={answer} />;
      case 'order': return <Order req={req} model={model} answer={answer} />;
      case 'manipulate': return <Manipulate req={req} model={model} answer={answer} />;
      case 'option': return <Option req={req} model={model} answer={answer} />;
      case 'text': return <Text req={req} answer={answer} />;
      case 'number': return <NumberPick req={req} answer={answer} />;
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
  // Folded, the dialog stays drawn but hidden, so what was set in it is still there when it opens again
  const folded = ui.requestFolded === req.id;
  const setFolded = (on: boolean) => changeUi(u => { u.requestFolded = on ? req.id : null; });
  useEffect(() => () => {
    if (shownAnswer?.id === req.id) shownAnswer = null;
  }, []);
  const foldable = !at && FOLDABLE.has(req.kind);
  // A question about cards sits on the dimmed board with no box round it; a phone keeps its sheet
  const onBoard = !isPortrait() && cardPrompt(req);
  const title = onBoard ? <QuestionLine model={model} req={req} text={heading || message || ''} /> : <SymbolText text={heading || message || ''} />;
  const fold = foldable && <button class="zone-fold" onClick={() => setFolded(true)}>{t('lblWebZoneShowBoard')}</button>;
  return <>
    <div class={at ? 'backdrop anchored' : onBoard ? 'backdrop on-board' : 'backdrop'} hidden={folded}>
      <div class={at ? 'dialog at-card' : 'dialog'} style={at ? { left: `${at.x}px`, top: `${at.y}px` } : undefined}>
        <div class="dialog-head">
          <h3>{title}</h3>
          {!onBoard && fold}
        </div>
        {heading && message && <p><SymbolText text={message} /></p>}
        {onBoard && <WhereFrom model={model} req={req} />}
        <FoldButton.Provider value={onBoard && fold}>{body}</FoldButton.Provider>
      </div>
    </div>
    {folded && (
      <div class="reveal-back minimised">
        <section class="zone-bar">
          <span class="zone-dot" aria-hidden="true" /><b>{title}</b>
          <button class="zone-unfold primary" onClick={() => setFolded(false)}>{t('lblWebZoneShowCards')}</button>
        </section>
      </div>
    )}
  </>;
}

/** A question on the board as one line: the card asking, then what it asks, without the engine's rules text and bookkeeping. */
function QuestionLine({ model, req, text }: { model: Model; req: Request; text: string }) {
  const asked = splitQuestion(text);
  const from = deref(model, askingCard(model, req)) as CardView | undefined;
  const source = asked.source || (from ? stateOf(model, from).Name ?? '' : '');
  return <>{source && <><span class="ask-source">{source}</span><span class="ask-sep" aria-hidden="true">·</span></>}<SymbolText text={asked.ask} /></>;
}

/** Whose cards a choice is among and how many, when they all lie in one player's zone, which the dim hides on the board. */
function WhereFrom({ model, req }: { model: Model; req: Request }) {
  if (req.kind !== 'choices') return null;
  const cards = req.options.map(o => (o.card ? model.objects.get(o.card.ref) as CardView | undefined : undefined));
  const first = cards[0];
  if (!first?.Zone || !cards.every(c => c?.Zone === first.Zone && c?.Owner?.ref === first.Owner?.ref)) return null;
  const owner = deref(model, first.Owner) as PlayerView | undefined;
  return <p class="ask-where">{`${owner?.Name ?? ''} \u00b7 ${zoneTitle(first.Zone)} \u00b7 ${t(cards.length === 1 ? 'lblWebOneCard' : 'lblWebNCards', cards.length)}`}</p>;
}

/** The player's library as a pile in their sleeve, with the ends of the library either side of it. */
function LibraryPile({ model, count }: { model: Model; count: number }) {
  return (
    <div class="lib-pile" style={{ '--sleeve': cssUrl(playerSleeveUrl(me(model))) }} aria-hidden="true">
      <i /><i /><i />
      <span class="lib-count">{count}</span>
      <span class="lib-name">{t('lblLibrary')}</span>
    </div>
  );
}

/** The key that gives a question's answer, as the player has chosen it. */
function OkKey() {
  return <kbd>{keyName(boundKeys().ok)}</kbd>;
}

function Button({ primary, disabled, onClick, children }: {
  primary?: boolean; disabled?: boolean; onClick: () => void; children: ComponentChildren;
}) {
  return <button class={primary ? 'primary' : ''} disabled={disabled} onClick={onClick}>{children}</button>;
}

function ButtonRow({ children }: { children: ComponentChildren }) {
  return <div class="actions">{useContext(FoldButton)}{children}</div>;
}

// A deck list arrives with its sections marked out as entries of their own, which read as headings, not choices
const SECTION = /^=+\s*(.*?)\s*=+$/;

/**
 * Cards laid out to be looked at, in a window that folds to a bar so the board under it can be read before answering.
 * With aiSkipsOf it is the warning that a computer will not play some of its deck, worded and drawn as one.
 */
function RevealWindow({ model, title: asSent, cards, close, aiSkipsOf }: {
  model: Model; title: string; cards: RequestOption[]; close: () => void; aiSkipsOf?: PlayerView;
}) {
  const [folded, setFolded] = useState(false);
  const isSection = (c: RequestOption) => !c.card && !c.imageKey && SECTION.test(c.label ?? '');
  const count = cards.filter(c => !isSection(c)).length;
  const counted = t(count === 1 ? 'lblWebOneCard' : 'lblWebNCards', count);
  const who = aiSkipsOf?.Name ?? '';
  const title = !aiSkipsOf ? asSent : count === 1 ? t('lblWebRevealAiSkipsOne', who) : t('lblWebRevealAiSkipsN', who, count);
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
  if (aiSkipsOf) {
    // A heading is worth its line only when the cards come from more than one part of the deck
    const headed = cards.filter(isSection).length > 1;
    let dealt = 0;
    return (
      <Backdrop layer="reveal-back" dismiss={close}>
        {/* Many cards are dealt faster, so the last still lands within half a second of the first */}
        <section class="zone-panel reveal-panel ai-skips" role="dialog" aria-label={title} style={`--deal-step: ${Math.min(60, Math.round(500 / Math.max(1, count)))}ms`}>
          <header>
            <span class="warn-mark" aria-hidden="true">!</span>
            <div class="ai-skips-words">
              <b class="zone-who">{title}</b>
              <p>{t(count === 1 ? 'lblWebRevealAiSkipsWhyOne' : 'lblWebRevealAiSkipsWhyN', who)}</p>
            </div>
            <button class="zone-fold" onClick={() => setFolded(true)}>{t('lblWebZoneShowBoard')}</button>
          </header>
          <div class="cards">
            {cards.map((o, i) => (isSection(o)
              ? headed && <OptionView key={i} model={model} opt={o} />
              : <figure key={i} style={`--i: ${dealt++}`}><OptionView model={model} opt={o} /><figcaption>{o.name ?? o.label}</figcaption></figure>))}
          </div>
          <footer><span class="zone-hint" /><button class="zone-answer ok primary" onClick={close}><span class="label">{t('lblOK')}</span><OkKey /></button></footer>
        </section>
      </Backdrop>
    );
  }
  const few = count === 1 ? 2.6 : count <= 3 ? 2.15 : count <= 5 ? 1.7 : 1.45;
  return (
    <Backdrop layer="reveal-back" cls={isPortrait() ? undefined : 'on-board'} dismiss={close}>
      <section class="zone-panel reveal-panel bare" role="dialog" aria-label={title} style={`--few: ${few}`}>
        <header><b class="zone-who"><SymbolText text={title} /></b><span class="zone-count">{counted}</span></header>
        <div class="cards">{cards.map((o, i) => <OptionView key={i} model={model} opt={o} />)}</div>
        <footer><button class="zone-fold" onClick={() => setFolded(true)}>{t('lblWebZoneShowBoard')}</button>
          <button class="zone-answer ok primary" onClick={close}><span class="label">{t('lblOK')}</span><OkKey /></button></footer>
      </section>
    </Backdrop>
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
  // A sticker offered by name shows the piece of its sheet it is
  const sticker = cutFor(model, opt.label);
  return (
    <button class={picked ? 'text-option picked' : 'text-option'} onClick={onClick}>
      {sticker && <span class="stk-cut" style={sticker} />}<SymbolText text={opt.label} />
    </button>
  );
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
    c.tabIndex = onClick ? 0 : -1;
    updateCard(c, model, card);
    c.classList.toggle('picked', !!picked);
    // A mark as well as the ring, so being picked is told by shape, not colour alone
    if (picked && !c.querySelector(':scope > .pick-mark')) c.append(make('span', 'pick-mark'));
    else if (!picked) c.querySelector(':scope > .pick-mark')?.remove();
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
    <div class={`card inline${broken ? ' noimg' : ''}${picked ? ' picked' : ''}`} title={title ?? ''} data-zoom={src} tabIndex={onClick ? 0 : -1}
      onClick={onClick} onMouseEnter={e => hoverCard(e.currentTarget)} onMouseLeave={() => hoverCard(null)}>
      <img alt="" loading="lazy" src={src} onError={() => setBroken(true)} />
      <div class="frame">{name}</div>
      {picked && <span class="pick-mark" />}
    </div>
  );
}

// Naming a card offers every card face, so long lists get a search box and draw only the first matches
const SEARCH_FROM = 20;
const SHOW_AT_MOST = 200;
// A line of text costs little to draw, so every creature type can be scrolled to without typing
const WORDS_AT_MOST = 1000;

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
  offerAnswer(req.id, ready ? () => answer(reveal ? [] : [...picked]) : null);
  const colours = !reveal && req.options.every(o => o.symbol);
  // Choices that are only words read down a list, as a menu does, rather than across the page
  const words = onlyWords(req);
  // The arrows move between the cards and Enter picks the one reached, leaving the OK key to answer
  const onKey = (e: KeyboardEvent) => {
    const cards = [...(e.currentTarget as HTMLElement).querySelectorAll<HTMLElement>('.card[tabindex="0"]')];
    const at = cards.indexOf(document.activeElement as HTMLElement);
    if (at < 0) return;
    const by = e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : 0;
    if (by) cards[Math.max(0, Math.min(cards.length - 1, at + by))].focus();
    // A card focused by a click was picked by that click, so Enter after it gives the answer instead
    else if (e.key === 'Enter' && cards[at].matches(':focus-visible')) cards[at].click();
    else return;
    e.preventDefault();
    e.stopPropagation();
  };
  // The bottom edge fades while more cards lie below it, and only then
  const list = useRef<HTMLDivElement>(null);
  const [more, setMore] = useState(false);
  const measure = () => {
    const el = list.current;
    if (el) setMore(el.scrollTop + el.clientHeight < el.scrollHeight - 4);
  };
  useLayoutEffect(measure, [shown.length]);
  useCardFit(list, words || colours ? 0 : shown.length, 14, 0, true);
  return (
    <>
      {searchable && <input ref={search} class="choice-search" placeholder={t('lblWebDialogSearchOptions', req.options.length)}
        value={typed} onInput={e => setTyped(e.currentTarget.value)} />}
      <div ref={list} onScroll={measure} onKeyDown={onKey}
        class={`${reveal && !words ? 'options grid' : colours ? 'options colours' : words ? 'options words' : 'options'}${more ? ' more' : ''}`}>
        {shown.map(i => colours
          ? <ColourOption key={i} symbol={req.options[i].symbol ?? ''} picked={picked.has(i)} onClick={() => toggle(i)} />
          : <OptionView key={i} model={model} opt={req.options[i]} picked={picked.has(i)} onClick={() => toggle(i)} />)}
      </div>
      <p class="hint">{note}</p>
      <ButtonRow>
        {/* Picking nothing is an answer the question allows, so it is offered rather than left to an empty Confirm */}
        {!reveal && req.min === 0 && <Button onClick={() => answer([])}>{t('lblWebDialogChooseNone')}</Button>}
        <Button primary disabled={!ready} onClick={() => answer(reveal ? [] : [...picked])}>{reveal ? t('lblOK') : t('lblWebDialogConfirm')} <OkKey /></Button>
      </ButtonRow>
    </>
  );
}

/** A colour to choose, as its mana symbol from the skin with its name under it. */
function ColourOption({ symbol, picked, onClick }: { symbol: string; picked: boolean; onClick: () => void }) {
  const name = COLOURS.find(([letter]) => letter === symbol)?.[1];
  return (
    <button class={picked ? 'colour-option picked' : 'colour-option'} aria-pressed={picked} onClick={onClick}>
      <img alt="" src={symbolUrl(symbol)} /><span>{name ? t(name) : symbol}</span>
    </button>
  );
}

/** Every option is a line of text, with no card, player or mana symbol to draw. */
function onlyWords(req: ChoicesRequest): boolean {
  return req.options.every(o => !o.card && !o.imageKey && !o.name && !o.player && !o.symbol);
}

/** A label with the typed text picked out, unless it draws mana symbols, which a mark would split. */
function Marked({ label, typed }: { label: string; typed: string }) {
  const span = label.includes('{') ? null : matchedSpan(label, typed);
  if (!span) return <SymbolText text={label} />;
  return <span>{label.slice(0, span[0])}<mark>{label.slice(...span)}</mark>{label.slice(span[1])}</span>;
}

/**
 * Words to choose from, as one searchable list worked from the keyboard: the highlight moves with the arrows,
 * Enter answers, and on a single pick a double-click, or a second tap on a phone, answers too.
 */
function WordChoices({ req, model, answer }: { req: ChoicesRequest; model: Model; answer: Answer }) {
  const single = req.max === 1;
  const labels = req.options.map(o => String(o.label ?? ''));
  const [typed, setTyped] = useState('');
  const [chosen, setChosen] = useState<ReadonlySet<number>>(() => new Set(single ? [] : req.selected));
  const matches = rankByName(labels, typed);
  const shown = matches.slice(0, WORDS_AT_MOST);
  const [hi, setHi] = useState(() => (single && req.selected.length ? Math.max(0, matches.indexOf(req.selected[0])) : 0));
  const tapped = useRef(-1);
  const search = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // On a phone the keyboard would cover the list, so it waits for a tap on the box
    if (!isPortrait()) search.current?.focus();
  }, []);
  useEffect(() => {
    list.current?.querySelector('.hi')?.scrollIntoView({ block: 'nearest' });
  }, [hi, typed]);
  const narrow = (text: string) => {
    setTyped(text);
    setHi(0);
    tapped.current = -1;
  };
  const toggle = (i: number) => setChosen(old => {
    const next = new Set(old);
    if (old.has(i)) next.delete(i);
    else if (req.max < 0 || old.size < req.max) next.add(i);
    return next;
  });
  const ready = single ? shown.length > 0 : chosen.size >= req.min && (req.max < 0 || chosen.size <= req.max);
  const confirm = () => {
    if (ready) answer(single ? [shown[hi]] : [...chosen]);
  };
  const onKey = (e: KeyboardEvent) => {
    const typing = e.target === search.current;
    // Cancel and a chip's remove button answer their own keys
    const other = e.target instanceof HTMLButtonElement && !e.target.classList.contains('word');
    if (other && (e.key === 'Enter' || e.key === ' ')) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      setHi(h => Math.max(0, Math.min(shown.length - 1, h + (e.key === 'ArrowDown' ? 1 : -1))));
    } else if (e.key === 'Enter') {
      confirm();
    } else if (e.key === 'Escape' && typed) {
      narrow('');
    } else if (e.key === ' ' && !single && shown.length && !(typing && typed)) {
      toggle(shown[hi]);
    } else {
      return;
    }
    e.preventDefault();
    e.stopPropagation();
  };
  const click = (pos: number) => {
    setHi(pos);
    const i = shown[pos];
    if (!single) {
      toggle(i);
    } else if (isPortrait()) {
      if (tapped.current === i) answer([i]);
      tapped.current = i;
    }
  };
  const count = !single ? (req.max > 1 ? t('lblWebDialogChosenOfMax', chosen.size, req.max) : t('lblWebDialogSelected', chosen.size))
    : typed ? t('lblWebDialogMatchesOf', matches.length, labels.length) : '';
  return (
    <div class="word-choices" onKeyDown={onKey}>
      <div class="word-search">
        <input ref={search} placeholder={t('lblWebDialogSearchOptions', labels.length)} value={typed}
          onInput={e => narrow(e.currentTarget.value)} />
        {typed && <button class="word-clear" title={t('lblWebDialogClearSearch')} onClick={() => { narrow(''); search.current?.focus(); }}>×</button>}
      </div>
      {!single && chosen.size > 0 && (
        <div class="word-chips">
          {[...chosen].map(i => (
            <span key={i} class="word-chip"><SymbolText text={labels[i]} />
              <button title={t('lblRemove')} onClick={() => toggle(i)}>×</button></span>
          ))}
        </div>
      )}
      <div ref={list} class="word-list" role="listbox" aria-multiselectable={!single}>
        {shown.length === 0 && <p class="word-empty">{t('lblWebDialogNoMatch', typed)}</p>}
        {shown.map((i, pos) => {
          const on = chosen.has(i);
          const sticker = cutFor(model, labels[i]);
          return (
            <button key={i} type="button" tabIndex={-1} role="option" aria-selected={single ? pos === hi : on}
              class={`word${pos === hi ? ' hi' : ''}${on ? ' on' : ''}`}
              onClick={() => click(pos)} onDblClick={() => { if (single) answer([i]); }}>
              {!single && <span class="word-box" aria-hidden="true" />}
              {sticker && <span class="stk-cut" style={sticker} />}
              <Marked label={labels[i]} typed={typed} />
            </button>
          );
        })}
      </div>
      {matches.length > WORDS_AT_MOST && <p class="hint">{t('lblWebDialogShowingFirst', WORDS_AT_MOST, matches.length)}</p>}
      <div class="word-foot">
        <span class="word-count">{count}</span>
        {single && req.min === 0 && <Button onClick={() => answer([])}>{t('lblCancel')}</Button>}
        <Button primary disabled={!ready} onClick={confirm}>{t('lblWebDialogConfirm')} <kbd>Enter</kbd></Button>
      </div>
      <p class="word-keys">{t(single ? 'lblWebDialogKeysOne' : 'lblWebDialogKeysMany')}</p>
    </div>
  );
}

/** The engine ends a triggered ability's text with what set it off, in square brackets. */
const SET_OFF_BY = /\s*\[([^\]]*)\]$/;

/** An ability to be put in order: the card it comes from, pictured and named, over its text. */
function AbilityRow({ model, opt, text, detail, tag, onClick }: {
  model: Model; opt: RequestOption; text: string; detail: string; tag?: string; onClick: () => void;
}) {
  const source = opt.source ? model.objects.get(opt.source.ref) as CardView | undefined : undefined;
  return (
    <>
      {source && <BoardCard model={model} card={source} />}
      <button class="ability" onClick={onClick}>
        {(source || tag) && <span class="ability-source">{source ? stateOf(model, source).Name : ''}{tag && <span class="order-tag">{tag}</span>}</span>}
        <span><SymbolText text={text} /></span>
        {detail && <span class="ability-detail">{detail}</span>}
      </button>
    </>
  );
}

const CHEVRON = { up: 'M6.5 14.5l5.5-5.5 5.5 5.5', down: 'M6.5 9.5l5.5 5.5 5.5-5.5' };

/** How long a card takes to slide to a new place in the order, and to settle when it is put down. */
const CARRY_MS = 170;
const CARRY_EASE = 'cubic-bezier(.2,.8,.2,1)';

function Order({ req, model, answer }: { req: OrderRequest; model: Model; answer: Answer }) {
  const [chosen, setChosen] = useState<number[]>(() => [...req.selected]);
  const [remember, setRemember] = useState(false);
  /** The card tapped to be moved, by its place among the options. */
  const [held, setHeld] = useState<number | null>(null);
  /** The card the pointer is carrying, by its place among the options. */
  const [carried, setCarried] = useState<number | null>(null);
  const row = useRef<HTMLDivElement>(null);
  const order = useRef(chosen);
  order.current = chosen;
  /** Where each card stood before the order changed, so it slides from there to its new place. */
  const stood = useRef<Map<Element, DOMRect> | null>(null);
  const ghost = useRef<HTMLElement | null>(null);
  // Options that are only words, such as abilities, are listed down the dialog; cards keep their row
  const listed = req.options.every(o => !o.card && !o.imageKey && !o.name && !o.player);
  useCardFit(row, listed ? 0 : chosen.length, 22, 38, false);
  const words = req.options.map(o => {
    const found = SET_OFF_BY.exec(o.label ?? '');
    return found ? { text: o.label.slice(0, found.index), detail: found[1] } : { text: o.label ?? '', detail: '' };
  });
  // What set an ability off is worth its line only where it tells two of them apart
  const tellsApart = new Set(words.map(w => w.detail)).size > 1;
  const move = (from: number, to: number) => {
    if (row.current) stood.current = new Map([...row.current.children].map(el => [el, el.getBoundingClientRect()]));
    setChosen(list => {
      const next = [...list];
      next.splice(to, 0, next.splice(from, 1)[0]);
      return next;
    });
  };
  useLayoutEffect(() => {
    const was = stood.current;
    stood.current = null;
    if (!was || reducedMotion()) return;
    for (const [el, from] of was) {
      el.getAnimations().forEach(a => a.cancel());
      const to = el.getBoundingClientRect();
      if (el.classList.contains('carried') || (from.left === to.left && from.top === to.top)) continue;
      el.animate([{ translate: `${from.left - to.left}px ${from.top - to.top}px` }, { translate: '0 0' }], { duration: CARRY_MS, easing: CARRY_EASE });
    }
  }, [chosen]);
  useEffect(() => () => ghost.current?.remove(), []);
  // A finger scrolls the dialog, so only a mouse or pen carries a card; a tap holds it for the arrows
  const carry = (e: PointerEvent, i: number) => {
    if (e.pointerType === 'touch' || e.button !== 0 || ghost.current) return;
    const el = e.currentTarget as HTMLElement;
    const start = { x: e.clientX, y: e.clientY };
    let from: DOMRect | null = null;
    let slots: DOMRect[] = [];
    let last = e.clientX;
    let lean = 0;
    const lift = () => {
      if (!row.current) return false;
      from = el.getBoundingClientRect();
      slots = [...row.current.children].map(c => c.getBoundingClientRect());
      // The copy sits under the same classes as the row, so the card in it is drawn as the row draws it
      const lifted = make('div', `options ordered order-ghost${listed ? ' listed' : ''}`);
      lifted.style.cssText = `left: ${from.left}px; top: ${from.top}px;${listed ? ` width: ${from.width}px; min-width: 0;` : ''}`;
      lifted.append(el.cloneNode(true));
      row.current.after(lifted);
      ghost.current = lifted;
      setHeld(null);
      setCarried(i);
      return true;
    };
    const onMove = (m: PointerEvent) => {
      const dx = m.clientX - start.x;
      const dy = m.clientY - start.y;
      const lifted = ghost.current;
      if (!lifted || !from) return;
      // The card leans the way it is moving, and settles upright when it stops
      lean += (Math.max(-9, Math.min(9, (m.clientX - last) * 0.9)) - lean) * 0.35;
      last = m.clientX;
      lifted.style.translate = `${dx}px ${dy}px`;
      if (!listed) lifted.style.rotate = `${lean.toFixed(2)}deg`;
      const x = from.left + from.width / 2 + dx;
      const y = from.top + from.height / 2 + dy;
      const far = (r: DOMRect) => Math.hypot(r.left + r.width / 2 - x, r.top + r.height / 2 - y);
      let best = 0;
      slots.forEach((s, n) => {
        if (far(s) < far(slots[best])) best = n;
      });
      const at = order.current.indexOf(i);
      if (at >= 0 && best !== at) move(at, best);
    };
    const onUp = () => {
      const lifted = ghost.current;
      if (!lifted || !from) return;
      const done = () => {
        lifted.remove();
        ghost.current = null;
        setCarried(null);
      };
      if (reducedMotion()) {
        done();
        return;
      }
      const to = el.getBoundingClientRect();
      lifted.animate([{ translate: lifted.style.translate || '0 0', rotate: lifted.style.rotate || '0deg', scale: getComputedStyle(lifted).scale },
        { translate: `${to.left - from.left}px ${to.top - from.top}px`, rotate: '0deg', scale: '1' }],
      { duration: CARRY_MS, easing: CARRY_EASE, fill: 'forwards' }).finished.then(done, done);
    };
    // The click that ends a carry is not a tap on the card, which trackDrag sees to
    trackDrag(e, 5, { start: lift, move: onMove, end: onUp });
  };
  const at = held === null ? -1 : chosen.indexOf(held);
  const step = (by: number) => {
    if (at >= 0 && at + by >= 0 && at + by < chosen.length) move(at, at + by);
  };
  // The arrow keys carry the card that is held along the row, or the ability up and down its list
  const stepRef = useRef(step);
  stepRef.current = step;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const by = e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : 0;
      if (!by) return;
      e.preventDefault();
      stepRef.current(by);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);
  const waiting = req.options.some((_, i) => !chosen.includes(i));
  offerAnswer(req.id, chosen.length >= req.min && chosen.length <= req.max ? () => answer({ indices: chosen, remember }) : null);
  // A card may leave the row only when the question lets some be left out
  const optional = req.min < req.options.length;
  const rememberBox = req.remember
    && <label><input type="checkbox" checked={remember} onChange={e => setRemember(e.currentTarget.checked)} /> {t('lblWebDialogRememberOrder')}</label>;
  // A library's order is named at both ends; any other order by what goes first
  const library = !listed && (req.top === t('lblClosestToTop') || req.top === t('lblClosestToBottom'));
  const otherEnd = req.top === t('lblClosestToTop') ? t('lblClosestToBottom') : t('lblClosestToTop');
  const railed = !listed && !optional && !!req.top && !library;
  const nudge = (by: number, pos: number) => (
    <button class={by < 0 ? 'earlier' : 'later'} disabled={by < 0 ? pos === 0 : pos === chosen.length - 1}
      aria-label={t(by < 0 ? 'lblWebDialogMoveEarlier' : 'lblWebDialogMoveLater')} onClick={() => step(by)}>{by < 0 ? '‹' : '›'}</button>
  );
  const stepButton = (by: number, pos: number) => (
    <button class={by < 0 ? 'earlier' : 'later'} disabled={by < 0 ? pos === 0 : pos === chosen.length - 1}
      aria-label={t(by < 0 ? 'lblWebDialogMoveEarlier' : 'lblWebDialogMoveLater')} onClick={() => step(by)}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d={by < 0 ? CHEVRON.up : CHEVRON.down} /></svg>
    </button>
  );
  return (
    <>
      {waiting && <>
        <p class="hint">{t('lblWebDialogPickInOrder')}</p>
        <div class="options">
          {req.options.map((o, i) => chosen.includes(i) ? null
            : <OptionView key={i} model={model} opt={o} onClick={() => setChosen(list => [...list, i])} />)}
        </div>
      </>}
      <div class={listed ? 'options ordered listed' : 'options ordered'} ref={row}>
        {chosen.map((i, pos) => {
          const hold = () => {
            setHeld(h => (h === i ? null : i));
          };
          return (
            <div key={i} class={`ordered-item${carried === i ? ' carried' : ''}${held === i ? ' selected' : ''}`} onPointerDown={e => carry(e, i)}>
              {listed
                ? <AbilityRow model={model} opt={req.options[i]} text={words[i].text} detail={tellsApart ? words[i].detail : ''}
                    tag={pos === 0 ? req.top : undefined} onClick={hold} />
                : <OptionView model={model} opt={req.options[i]} onClick={hold} />}
              {/* The number is the order they go in, which is the question the dialog is asking */}
              <span class="order-number">{pos + 1}</span>
              {!listed && !railed && pos === 0 && req.top && <span class="order-end">{req.top}</span>}
              {library && pos === chosen.length - 1 && pos > 0 && <span class="order-end">{otherEnd}</span>}
              {/* Under every card, shown for the one that is held, so holding a card moves nothing else in the dialog */}
              {!listed && <span class="order-nudge">{nudge(-1, pos)}{nudge(1, pos)}</span>}
              {/* The held ability's arrows take the place of its grip, so they are where the eye already is */}
              {listed && (held === i ? <span class="order-step">{stepButton(-1, pos)}{stepButton(1, pos)}</span> : <span class="order-grip" aria-hidden="true" />)}
            </div>
          );
        })}
      </div>
      {railed && <p class="order-rail"><b>{req.top}</b><i /></p>}
      {!listed && rememberBox}
      <ButtonRow>
        <span class="order-move">
          {listed && rememberBox}
          {optional && <button disabled={at < 0} onClick={() => {
            setChosen(list => list.filter(c => c !== held));
            setHeld(null);
          }}>{t('lblRemove')}</button>}
        </span>
        <Button primary disabled={chosen.length < req.min || chosen.length > req.max}
          onClick={() => answer({ indices: chosen, remember })}>{t('lblWebDialogConfirm')} <OkKey /></Button>
      </ButtonRow>
    </>
  );
}

// The host passes the whole library with only the top cards movable, and those placed after the rest go to the bottom
type Shelf = 'top' | 'bottom';

/** The top of the library and its bottom either side of the pile; the further a card lies from the pile, the nearer it is to that end. */
function Manipulate({ req, model, answer }: { req: ManipulateRequest; model: Model; answer: Answer }) {
  const all = req.options.map((_, i) => i);
  const rest = all.filter(i => !req.movable.includes(i));
  const [piles, setPiles] = useState(() => ({ top: all.filter(i => req.movable.includes(i)), bottom: [] as number[] }));
  const held = useRef<number | null>(null);
  const bothEnds = req.toBottom || req.toTop;
  const row = useRef<HTMLDivElement>(null);
  // Each end's cards, or the empty place where they would go, and the pile between them, in one row
  useCardFit(row, Math.max(piles.top.length, 1) + (bothEnds ? Math.max(piles.bottom.length, 1) : 0) + 1, 18, 0, false);

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
  // A card sent across lands beside the pile, the middle of its new end
  const send = (from: Shelf, pos: number) => setPiles(p => {
    const card = p[from][pos];
    const next = { top: [...p.top], bottom: [...p.bottom] };
    next[from] = next[from].filter((_, k) => k !== pos);
    if (from === 'top') next.bottom = [card, ...next.bottom];
    else next.top = [...next.top, card];
    return next;
  });

  offerAnswer(req.id, () => answer([...piles.top, ...rest, ...piles.bottom]));

  // The top runs from its end to the pile, and the bottom from the pile to its end, so each end's card is the one furthest out
  const side = (name: Shelf) => (
    <div class={`lib-side ${name}`} onDragOver={e => e.preventDefault()} onDrop={() => place(name, piles[name].length)}>
      <div class="lib-cards">
        {piles[name].map((i, pos) => (
          <div key={i} class="lib-card" draggable onDragStart={() => { held.current = i; }}
            onDragOver={e => e.preventDefault()} onDrop={e => { e.stopPropagation(); place(name, pos); }}>
            <OptionView model={model} opt={req.options[i]} onClick={bothEnds ? () => send(name, pos) : undefined} />
            {name === 'top' && <span class="order-number">{pos + 1}</span>}
            {name === 'top' && pos === 0 && <span class="order-end">{t('lblClosestToTop')}</span>}
            {name === 'bottom' && pos === piles.bottom.length - 1 && <span class="order-end">{t('lblClosestToBottom')}</span>}
          </div>
        ))}
        {piles[name].length === 0 && <div class="lib-card"><span class="lib-slot" /><span class="order-end">{t(name === 'top' ? 'lblClosestToTop' : 'lblClosestToBottom')}</span></div>}
      </div>
    </div>
  );

  return (
    <div class="manipulate">
      <div class="lib-row" ref={row}>
        {side('top')}
        <LibraryPile model={model} count={rest.length} />
        {bothEnds && side('bottom')}
      </div>
      <p class="hint">{t(bothEnds ? 'lblWebDialogMoveAcross' : 'lblWebDialogReorderHint')}</p>
      <ButtonRow>
        <Button primary onClick={() => answer([...piles.top, ...rest, ...piles.bottom])}>{t('lblWebDialogConfirm')} <OkKey /></Button>
      </ButtonRow>
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

/** How many of the first numbers are offered as buttons. */
const QUICK_PICKS = 10;

/** A whole number: stepped, picked from the first few, or typed. */
function NumberPick({ req, answer }: { req: NumberRequest; answer: Answer }) {
  const top = req.max ?? 999_999_999;
  const [typed, setTyped] = useState(String(req.min));
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    input.current?.select();
  }, []);
  const parsed = parseInt(typed, 10);
  const value = Number.isNaN(parsed) ? req.min : Math.max(req.min, Math.min(top, parsed));
  const set = (n: number) => setTyped(String(Math.max(req.min, Math.min(top, n))));
  const quick = Array.from({ length: Math.min(QUICK_PICKS, top - req.min + 1) }, (_, i) => req.min + i);
  return (
    <>
      <div class="number-step">
        <button type="button" aria-label={t('lblWebDialogOneLess')} disabled={value <= req.min} onClick={() => set(value - 1)}>&minus;</button>
        <input ref={input} type="number" inputMode="numeric" min={req.min} max={req.max} value={typed} onInput={e => setTyped(e.currentTarget.value)}
          onBlur={() => set(value)} onKeyDown={e => { if (e.key === 'Enter') answer(value); }} />
        <button type="button" aria-label={t('lblWebDialogOneMore')} disabled={value >= top} onClick={() => set(value + 1)}>+</button>
      </div>
      <div class="number-quick">
        {quick.map(n => <button key={n} type="button" class={n === value ? 'picked' : ''} onClick={() => set(n)}>{n}</button>)}
      </div>
      <ButtonRow>
        {req.maySkip && <Button onClick={() => answer(null)}>{t('lblCancel')}</Button>}
        <Button primary onClick={() => answer(value)}>{t('lblWebDialogConfirm')}</Button>
      </ButtonRow>
    </>
  );
}

function Distribute({ req, model, answer }: { req: DistributeRequest; model: Model; answer: Answer }) {
  // It opens on desktop's Auto split, lethal damage to each in turn and the rest onward, for the player to change
  const [values, setValues] = useState(() => [...req.default]);
  const step = (i: number, delta: number) => setValues(v => {
    if (v[i] + delta < req.perMin) return v;
    const next = [...v];
    next[i] += delta;
    return next;
  });
  const left = req.amount - values.reduce((a, b) => a + b, 0);
  offerAnswer(req.id, left === 0 ? () => answer(values) : null);
  // Players first, opponents before you, then the cards; each keeps its own place in the answer
  const rank = (i: number) => {
    const ref = req.options[i].player;
    const player = ref ? model.objects.get(ref.ref) as PlayerView | undefined : undefined;
    return !player ? 2 : isLocal(model, player) ? 1 : 0;
  };
  const order = req.options.map((_, i) => i).sort((a, b) => rank(a) - rank(b) || a - b);
  // What combat damage comes to on each target: whether a blocker's is lethal, and the life a player is left on
  const note = (i: number): { text: string; lethal?: boolean } => {
    const ref = req.options[i].player;
    const player = ref ? model.objects.get(ref.ref) as PlayerView | undefined : undefined;
    if (player) {
      return { text: values[i] ? t('lblWebDialogLifeAfter', player.Life ?? 0, (player.Life ?? 0) - values[i]) : '' };
    }
    const short = (req.lethal?.[i] ?? 0) - values[i];
    return short <= 0 ? { text: t('lblLethal'), lethal: true } : { text: values[i] ? t('lblWebDialogShortOfLethal', short) : '' };
  };
  const row = useRef<HTMLDivElement>(null);
  useCardFit(row, order.length, 26, 60, true);
  // Side by side, each with its count under it, as the cards stand on the table
  return (
    <>
      <div class="dist-row" ref={row}>
        {order.map(i => (
          <div key={i} class="dist-target">
            <OptionView model={model} opt={req.options[i]} />
            <div class="dist-step">
              <Button onClick={() => step(i, -1)}>−</Button>
              <b>{values[i]}</b>
              <Button onClick={() => step(i, 1)}>+</Button>
            </div>
            {req.lethal && <p class={note(i).lethal ? 'dist-note lethal' : 'dist-note'}>{note(i).text}</p>}
          </div>
        ))}
      </div>
      <ButtonRow>
        <span class={left ? 'dist-left' : 'dist-left done'}>
          {/* A pip for each point, filled as it is placed, while there are few enough to count at a glance */}
          {req.amount <= 20 && <span class="dist-pips">{Array.from({ length: req.amount }, (_, k) => <i key={k} class={k < req.amount - left ? 'on' : ''} />)}</span>}
          {left ? t('lblWebDialogLeftToAssign', left) : t('lblWebDialogAllAssigned', req.amount)}
          {/* Beside what it resets, and away from the answer */}
          <Button onClick={() => setValues([...req.default])}>{t('lblReset')}</Button>
        </span>
        {req.maySkip && <Button onClick={() => answer(null)}>{t('lblSkip')}</Button>}
        <Button primary disabled={left !== 0} onClick={() => answer(values)}>{t('lblWebDialogConfirm')} <OkKey /></Button>
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
