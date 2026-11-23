// Questions drawn as a scene over the page: an NPC's boon or story, an event's choices, a result. View Map folds a scene to a bar

import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { Icon } from '../icons';
import { artUrl, imageUrl } from '../images';
import { avatarUrl } from '../avatarsleeves';
import { playSound } from '../audio';
import { isPortrait } from '../form';
import { reducedMotion } from '../dom';
import { changeUi, ui } from '../ui';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { Words } from './terms';
import { CardPick, CommanderPick, LootPick, RewardPick } from './pick';
import { Gains } from './gains';
import { Bazaar } from './bazaar';
import { Backdrop } from '../dialog';
import { CardGroup, Curve } from '../deck/deckview';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { AskChoice, DeckDetails, Option, Part, TellOmen } from '../protocol';

/** What the number keys and the OK key do in the scene on screen. */
let keyed: { pick: (i: number) => void; ok: () => void } | null = null;

/** Answers the scene on screen as a number key does, or as the OK key does when choice is null. */
export function pressScene(choice: number | null): void {
  if (choice === null) keyed?.ok();
  else keyed?.pick(choice);
}

export function useSceneKeys(pick: (i: number) => void, ok: () => void): void {
  useEffect(() => {
    const own = { pick, ok };
    keyed = own;
    return () => { if (keyed === own) keyed = null; };
  });
}

/**
 * The scene that fills the body under the bar. Focus moves to it, to its main button if it has one, and back when it closes.
 * fold, when set, adds View Map, which folds the scene, and viewDeck adds View Deck. tip is drawn beside the scene's text.
 * onScroll is told when the scene's text and cards scroll.
 */
export function Scene({ art, foot, children, fold, viewDeck, tip, onScroll }: {
  art?: string | null; foot: ComponentChildren; children: ComponentChildren; fold?: () => void; viewDeck?: () => void; tip?: ComponentChildren;
  onScroll?: (e: Event) => void;
}) {
  const root = useRef<HTMLDivElement>(null);
  // The phone's buttons are words only, as the mockups draw them
  const phone = isPortrait();
  useEffect(() => {
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    (root.current!.querySelector<HTMLElement>('.ft .primary') ?? root.current!).focus({ preventScroll: true });
    return () => { if (before?.isConnected) before.focus({ preventScroll: true }); };
  }, []);
  return (
    <div class="rg-scene" ref={root} tabIndex={-1}>
      <div class="bg" style={art ? { backgroundImage: `url("${artUrl(art)}")` } : undefined} />
      <div class={tip ? 'in tip' : 'in'} onScroll={onScroll}>{children}{tip}</div>
      <div class="ft">
        {fold && (phone ? <button onClick={fold}>{t('lblRogueViewMap')}</button>
          : <button onClick={fold}><Icon d={ROGUE_ICONS.trail} cls="rg-ic" />{` ${t('lblRogueViewMap')}`}</button>)}
        {viewDeck && (phone ? <button onClick={viewDeck}>{t('lblViewDeck')}</button>
          : <button onClick={viewDeck}><Icon d={ROGUE_ICONS.deck} cls="rg-ic" />{` ${t('lblViewDeck')}`}</button>)}
        {!phone && fold && foot && <span class="sp" />}
        {foot}
      </div>
    </div>
  );
}

/**
 * The options, each a box with a button laid over it so a tap on a term in its text explains the term. hot is the option last pointed at or reached by keyboard.
 * picked holds the options chosen so far where several are chosen, which are drawn as hot too.
 */
export function Choices({ options, onPick, hot, setHot, picked }: {
  options: Option[]; onPick: (i: number) => void; hot: number | null; setHot: (i: number) => void; picked?: number[];
}) {
  return (
    <div class="rg-opts">
      {options.map((o, i) => (
        <div key={i} class={['rg-opt', (i === hot || picked?.includes(i)) && 'hot', !o.enabled && 'no'].filter(Boolean).join(' ')} onPointerEnter={() => setHot(i)}>
          <button class="rg-opt-b" aria-label={o.label} aria-pressed={picked ? picked.includes(i) : undefined} disabled={!o.enabled} onClick={() => onPick(i)}
            onFocus={e => { if (e.currentTarget.matches(':focus-visible')) setHot(i); }} />
          <b>{o.label}</b>
          <span class="d"><Words parts={o.text} /></span>
          {o.reason && <span class="why"><Icon d={ROGUE_ICONS.lock} cls="rg-ic" />{o.reason}</span>}
          <span class="k">{i + 1}</span>
        </div>
      ))}
    </div>
  );
}

/** Parts split at their line breaks, one list per line, without empty lines. */
function lines(parts: Part[]): Part[][] {
  const all: Part[][] = [[]];
  for (const p of parts) {
    p.text.split('\n').forEach((text, i) => {
      if (i) all.push([]);
      if (text) all.at(-1)!.push({ ...p, text });
    });
  }
  return all.filter(line => line.length);
}

/**
 * Text that types in, a paragraph per line. The time it takes is an animation, so finishing the page's animations finishes it too.
 * What is not typed yet keeps its place, so the text does not move as it types.
 */
export function Typed({ text, done }: { text: string; done: () => void }) {
  const first = useRef<HTMLParagraphElement>(null);
  const [shown, setShown] = useState(0);
  useEffect(() => {
    // Two characters every 15 ms, as desktop types
    const clock = first.current!.animate([], { duration: text.length * 7.5 });
    let frame = 0;
    const tick = () => {
      setShown(Math.floor(text.length * Number(clock.currentTime ?? 0) / Number(clock.effect!.getComputedTiming().duration)));
      frame = requestAnimationFrame(tick);
    };
    tick();
    clock.onfinish = done;
    return () => { cancelAnimationFrame(frame); clock.onfinish = null; clock.cancel(); };
  }, []);
  let at = 0;
  return <>{text.split('\n').filter(Boolean).map((line, i) => {
    const start = text.indexOf(line, at);
    at = start + line.length;
    const typed = Math.max(0, Math.min(line.length, shown - start));
    return <p key={i} class="flav" ref={i ? undefined : first}>{line.slice(0, typed)}<span style={{ visibility: 'hidden' }}>{line.slice(typed)}</span></p>;
  })}</>;
}

/** The open question drawn as a scene, or as the bar it folds to. View Map is offered only in a run, where there is a map. */
export function Question({ model, actions }: { model: Model; actions: Actions }) {
  const asked = model.rogueQuestion!;
  const answer = (value: unknown) => actions.rogueAnswer(asked.id, value);
  const title = asked.choice?.title ?? asked.cards?.title ?? asked.commander?.title ?? asked.omen?.title ?? asked.shop?.title ?? asked.tell!.title;
  if (ui.rogueFolded === asked.id) {
    return (
      <div class="rg-folded">
        <span>{isPortrait() ? title : t('lblWebRogueWaiting', title)}</span>
        <button class="primary" onClick={() => changeUi(u => { u.rogueFolded = null; })}>{t('lblShow')}</button>
      </div>
    );
  }
  const inRun = model.campaignSave !== null;
  const fold = inRun && (asked.choice?.options.length || asked.cards || asked.shop) ? () => changeUi(u => { u.rogueFolded = asked.id; }) : undefined;
  if (asked.cards) {
    const Pick = asked.cards.kind === 'REWARD' ? RewardPick : CardPick;
    return <Pick key={asked.id} asked={asked.cards} answer={answer} fold={fold} viewDeck={() => changeUi(u => { u.rogueDeckView = true; })} />;
  }
  if (asked.commander) return <CommanderPick key={asked.id} asked={asked.commander} model={model} actions={actions} answer={answer} />;
  if (asked.omen) return <Omens key={asked.id} omen={asked.omen} ok={() => answer(null)} />;
  const viewDeck = fold && (() => changeUi(u => { u.rogueDeckView = true; }));
  if (asked.shop) return <Bazaar key={asked.id} asked={asked.shop} answer={answer} fold={fold} viewDeck={viewDeck || undefined} />;
  if (!asked.choice) return <Gains key={asked.id} tell={asked.tell!} ok={() => answer(null)} />;
  // Options that are traits or rewards to take are drawn as cards
  return asked.choice.options.length > 0 && asked.choice.options.every(o => o.trait)
    ? <LootPick key={asked.id} choice={asked.choice} answer={answer} fold={fold} viewDeck={viewDeck || undefined} />
    : <Choosing key={asked.id} choice={asked.choice} answer={answer} fold={fold} viewDeck={viewDeck || undefined} />;
}

/** A deck, read only: a commander's start deck, or the run's deck over a decision. */
export function DeckView({ details, line, button, close }: { details: DeckDetails; line: string; button: string; close: () => void }) {
  return (
    <Backdrop layer="rg-dialog" dismiss={close}>
      <div class="dialog" role="dialog" aria-label={details.name}>
        <h3>{details.name}</h3>
        <p>{line}</p>
        <Curve curve={details.stats.curve} spells={details.stats.spells} px={44} />
        <div class="dk-cards" data-list style={{ maxHeight: '55vh', overflowY: 'auto' }}>{details.main.map(g => <CardGroup key={g.heading} heading={g.heading} cards={g.cards} />)}</div>
        <div class="actions"><button class="primary" onClick={close}>{button}</button></div>
      </div>
    </Backdrop>
  );
}

/**
 * Someone speaking, an event or a Sanctum, with its text in parts that each end on Continue, and the options after the last. A story has no options,
 * and its last Continue answers it. Where count options are to be chosen, as the dev pickers ask, a pick toggles and more than one is confirmed.
 * A phone names the scene by its title beside the face and leaves out the heading.
 */
function Choosing({ choice, answer, fold, viewDeck }: { choice: AskChoice; answer: (value: unknown) => void; fold?: () => void; viewDeck?: () => void }) {
  const [part, setPart] = useState(0);
  const [typing, setTyping] = useState(!reducedMotion());
  const [hot, setHot] = useState<number | null>(null);
  const [picked, setPicked] = useState<number[]>([]);
  const phone = isPortrait();
  const last = part >= choice.paragraphs.length - 1;
  const options = last ? choice.options : [];
  const next = () => {
    if (last) answer(null);
    else {
      setPart(part + 1);
      setTyping(!reducedMotion());
    }
  };
  const pick = (i: number) => {
    playSound({ t: 'sound', name: 'token', sync: true });
    if (!choice.count) answer(i);
    else if (choice.count === 1) answer([i]);
    else setPicked(p => (p.includes(i) ? p.filter(x => x !== i) : p.length < choice.count ? [...p, i] : p));
  };
  const short = choice.count - picked.length;
  useSceneKeys(i => { if (options[i]?.enabled) pick(i); }, () => { if (!options.length) next(); });
  const words = lines(choice.paragraphs[part] ?? []);
  const shown = hot === null || phone ? [] : options[hot]?.text ?? [];
  const terms = shown.filter((p, i) => p.kind === 'term' && shown.findIndex(q => q.term === p.term) === i);
  const cards = shown.filter((p, i) => p.kind === 'card' && p.image && shown.findIndex(q => q.image === p.image) === i);
  const tip = (terms.length > 0 || cards.length > 0) && (
    <div class="rg-tip">
      {terms.map(p => <div key={p.term} class="kw"><b>{p.term}</b>{p.tip}</div>)}
      {cards.map(p => <img key={p.image} alt="" src={imageUrl(p.image!)} />)}
    </div>
  );
  const buttons = [
    !options.length && <button key="go" class="primary" onClick={next}>{t('lblContinue')}</button>,
    options.length > 0 && choice.rerolls > 0 && (
      <button key="reroll" onClick={() => answer('reroll')}><Icon d={ROGUE_ICONS.reroll} cls="rg-ic" />{` ${t('lblRogueRerollsRemaining', choice.rerolls)}`}</button>
    ),
    options.length > 0 && choice.skip && <button key="skip" onClick={() => answer('skip')}>{t('lblSkip')}</button>,
    options.length > 0 && choice.count > 1 && (
      <button key="pick" class="primary" disabled={short > 0} onClick={() => answer(picked)}>{short > 0 ? t('lblWebRogueChooseMore', short) : t('lblChoose')}</button>
    ),
  ].filter(Boolean);
  const face = choice.face ?? null;
  return (
    <Scene art={choice.art} fold={fold} viewDeck={viewDeck} tip={tip} foot={buttons.length ? buttons : null}>
      <div class="rg-tale" onClick={() => setTyping(false)}>
        <div class="rg-face">
          {face !== null ? <span class="pic" style={{ backgroundImage: `url("${avatarUrl(face)}")` }} />
            : <span class="pic icon">{choice.kind in ROGUE_ICONS && <Icon d={ROGUE_ICONS[choice.kind as keyof typeof ROGUE_ICONS]} cls="rg-ic" />}</span>}
          <b>{phone ? choice.title : choice.label ?? choice.title}</b>
        </div>
        <div class="rg-words">
          {!phone && choice.heading && <h3>{choice.heading}</h3>}
          {typing && words.length > 0 ? <Typed key={part} text={words.map(line => line.map(p => p.text).join('')).join('\n')} done={() => setTyping(false)} />
            : words.map((line, i) => <p key={i} class="flav"><Words parts={line} /></p>)}
          {options.length > 0 && <Choices options={options} onPick={pick} hot={hot} setHot={setHot} picked={choice.count > 1 ? picked : undefined} />}
        </div>
      </div>
    </Scene>
  );
}

/** The Wrathful and Cursed effects of the battle about to start, over its plane. A flame or a pentagram marks each, and Cursed has a double border. */
function Omens({ omen, ok }: { omen: TellOmen; ok: () => void }) {
  useSceneKeys(() => {}, ok);
  return (
    <Scene art={omen.plane} foot={<button class="primary" onClick={ok}>{t('lblBattle')}</button>}>
      <div class="rg-omen">
        <span class="opp" style={omen.art ? { backgroundImage: `url("${artUrl(omen.art)}")` } : undefined} />
        <h3>{omen.title}</h3>
        <ul>
          {omen.omens.map((o, i) => (
            <li key={i} class={o.kind === 'cursed' ? 'curse' : undefined}>
              {o.kind === 'wrathful' && <Icon d={ROGUE_ICONS.flame} cls="rg-ic" />}
              {o.kind === 'cursed' && <Icon d={ROGUE_ICONS.curse} cls="rg-ic" />}
              <b><small>{o.label}</small>{o.name}</b>
              <span><Words parts={o.text} /></span>
            </li>
          ))}
        </ul>
      </div>
    </Scene>
  );
}
