// Questions drawn as a scene over the page: an NPC's boon or story, an event's choices, a result. View Map folds a scene to a bar

import { Fragment, type ComponentChildren } from 'preact';
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
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { AskChoice, Option, Part, Tell } from '../protocol';

/** What the number keys and the OK key do in the scene on screen. */
let keyed: { pick: (i: number) => void; ok: () => void } | null = null;

/** Answers the scene on screen as a number key does, or as the OK key does when choice is null. */
export function pressScene(choice: number | null): void {
  if (choice === null) keyed?.ok();
  else keyed?.pick(choice);
}

function useSceneKeys(pick: (i: number) => void, ok: () => void): void {
  useEffect(() => {
    const own = { pick, ok };
    keyed = own;
    return () => { if (keyed === own) keyed = null; };
  });
}

/**
 * The scene that fills the body under the bar. Focus moves to it, to its main button if it has one, and back when it closes.
 * fold, when set, adds View Map, which folds the scene. tip is drawn beside the scene's text.
 */
export function Scene({ art, foot, children, fold, tip }: { art?: string | null; foot: ComponentChildren; children: ComponentChildren; fold?: () => void; tip?: ComponentChildren }) {
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    (root.current!.querySelector<HTMLElement>('.ft .primary') ?? root.current!).focus({ preventScroll: true });
    return () => { if (before?.isConnected) before.focus({ preventScroll: true }); };
  }, []);
  return (
    <div class="rg-scene" ref={root} tabIndex={-1}>
      <div class="bg" style={art ? { backgroundImage: `url("${artUrl(art)}")` } : undefined} />
      <div class={tip ? 'in tip' : 'in'}>{children}{tip}</div>
      <div class="ft">
        {fold && <button onClick={fold}><Icon d={ROGUE_ICONS.trail} cls="rg-ic" />{` ${t('lblRogueViewMap')}`}</button>}
        {fold && foot && <span class="sp" />}
        {foot}
      </div>
    </div>
  );
}

/** The options, each a box with a button laid over it so a tap on a term in its text explains the term. hot is the option last pointed at or reached by keyboard. */
export function Choices({ options, onPick, hot, setHot }: { options: Option[]; onPick: (i: number) => void; hot: number | null; setHot: (i: number) => void }) {
  return (
    <div class="rg-opts">
      {options.map((o, i) => (
        <div key={i} class={['rg-opt', i === hot && 'hot', !o.enabled && 'no'].filter(Boolean).join(' ')} onPointerEnter={() => setHot(i)}>
          <button class="rg-opt-b" aria-label={o.label} disabled={!o.enabled} onClick={() => onPick(i)}
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
  const title = asked.choice?.title ?? asked.tell!.title;
  if (ui.rogueFolded === asked.id) {
    return (
      <div class="rg-folded">
        <span>{isPortrait() ? title : t('lblWebRogueWaiting', title)}</span>
        <button class="primary" onClick={() => changeUi(u => { u.rogueFolded = null; })}>{t('lblShow')}</button>
      </div>
    );
  }
  const fold = model.campaignSave !== null && asked.choice?.options.length ? () => changeUi(u => { u.rogueFolded = asked.id; }) : undefined;
  return asked.choice ? <Choosing key={asked.id} choice={asked.choice} answer={answer} fold={fold} /> : <Gains key={asked.id} tell={asked.tell!} ok={() => answer(null)} />;
}

/**
 * Someone speaking, or an event, with its text in parts that each end on Continue, and the options after the last. A story has no options,
 * and its last Continue answers it.
 */
function Choosing({ choice, answer, fold }: { choice: AskChoice; answer: (value: unknown) => void; fold?: () => void }) {
  const [part, setPart] = useState(0);
  const [typing, setTyping] = useState(!reducedMotion());
  const [hot, setHot] = useState<number | null>(null);
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
    answer(i);
  };
  useSceneKeys(i => { if (options[i]?.enabled) pick(i); }, () => { if (!options.length) next(); });
  const words = lines(choice.paragraphs[part] ?? []);
  const shown = hot === null || isPortrait() ? [] : options[hot]?.text ?? [];
  const terms = shown.filter((p, i) => p.kind === 'term' && shown.findIndex(q => q.term === p.term) === i);
  const cards = shown.filter((p, i) => p.kind === 'card' && p.image && shown.findIndex(q => q.image === p.image) === i);
  const tip = (terms.length > 0 || cards.length > 0) && (
    <div class="rg-tip">
      {terms.map(p => <div key={p.term} class="kw"><b>{p.term}</b>{p.tip}</div>)}
      {cards.map(p => <img key={p.image} alt="" src={imageUrl(p.image!)} />)}
    </div>
  );
  const foot = <>
    {!options.length && <button class="primary" onClick={next}>{t('lblContinue')}</button>}
    {options.length > 0 && choice.rerolls > 0 && (
      <button onClick={() => answer('reroll')}><Icon d={ROGUE_ICONS.reroll} cls="rg-ic" />{` ${t('lblRogueRerollsRemaining', choice.rerolls)}`}</button>
    )}
    {options.length > 0 && choice.skip && <button onClick={() => answer('skip')}>{t('lblSkip')}</button>}
  </>;
  const face = choice.face ?? null;
  return (
    <Scene art={choice.art} fold={fold} tip={tip} foot={foot}>
      <div class="rg-tale" onClick={() => setTyping(false)}>
        <div class="rg-face">
          {face !== null ? <span class="pic" style={{ backgroundImage: `url("${avatarUrl(face)}")` }} />
            : <span class="pic icon">{choice.kind in ROGUE_ICONS && <Icon d={ROGUE_ICONS[choice.kind as keyof typeof ROGUE_ICONS]} cls="rg-ic" />}</span>}
          <b>{choice.title}</b>
        </div>
        <div class="rg-words">
          {choice.heading && <h3>{choice.heading}</h3>}
          {typing && words.length > 0 ? <Typed key={part} text={words.map(line => line.map(p => p.text).join('')).join('\n')} done={() => setTyping(false)} />
            : words.map((line, i) => <p key={i} class="flav"><Words parts={line} /></p>)}
          {options.length > 0 && <Choices options={options} onPick={pick} hot={hot} setHot={setHot} />}
        </div>
      </div>
    </Scene>
  );
}

/** A result: what was gained, as text and groups of cards. */
function Gains({ tell, ok }: { tell: Tell; ok: () => void }) {
  useSceneKeys(() => {}, ok);
  return (
    <Scene art={tell.art} foot={<button class="primary" onClick={ok}>{t('lblContinue')}</button>}>
      <div class="rg-gains">
        <h3>{tell.title}</h3>
        <p><Words parts={tell.text} /></p>
        {tell.sections.map(s => <Fragment key={s.label}>
          <span class="rg-sub">{s.label}</span>
          {s.text.length > 0 && <p><Words parts={s.text} /></p>}
          <div class="rg-cards">
            {s.cards.map(c => <button key={c.image} class="rg-card" type="button" data-image={c.image}><img alt={c.name} src={imageUrl(c.image)} /></button>)}
          </div>
        </Fragment>)}
      </div>
    </Scene>
  );
}
