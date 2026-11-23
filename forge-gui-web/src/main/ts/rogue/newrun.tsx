// Start New Run: the carousel of commanders beside the chosen one's record, Descension levels and run options

import { useEffect, useRef, useState } from 'preact/hooks';
import { Backdrop } from '../dialog';
import { Icon } from '../icons';
import { artUrl, imageUrl } from '../images';
import { isPortrait } from '../form';
import { t } from '../text';
import { CardGroup, Curve } from '../deck/deckview';
import { ROGUE_ICONS } from './icons';
import { place, rolling } from './carousel';
import { CustomRun } from './custom';
import type { DeckDetails, DescensionStep, Modification, RogueCommander, RogueRoster } from '../protocol';

/** What the player has chosen so far. pick is the place of the commander in front, or null for the first unlocked one. */
export interface Choice {
  pick: number | null;
  level: number;
  custom: boolean;
  /** The Custom Run page is open in place of the carousel. */
  customOpen: boolean;
  mods: string[];
  /** The phone's Descension sheet is open. */
  sheet: boolean;
  /** The deck whose start deck is shown. */
  deck: string | null;
}

export const NO_CHOICE: Choice = { pick: null, level: 0, custom: false, customOpen: false, mods: [], sheet: false, deck: null };

export type SetChoice = (change: (c: Choice) => Choice) => void;

const generates = (roster: RogueRoster, c: Choice) => c.custom && roster.modifications.some(m => m.generatesCommander && c.mods.includes(m.id));

/** The roster the carousel shows: the experimental one while a custom run has Experiment. */
export function shown(roster: RogueRoster, c: Choice): RogueCommander[] {
  return c.custom && roster.modifications.some(m => m.experimental && c.mods.includes(m.id)) ? roster.experimental : roster.commanders;
}

export function picked(list: RogueCommander[], c: Choice): number {
  if (c.pick !== null && c.pick < list.length) return c.pick;
  return Math.max(0, list.findIndex(x => !x.unlock));
}

/** The level a run with the commander starts at: outside a custom run, the chosen one lowered to the highest the commander may start. */
export const levelFor = (commander: RogueCommander | undefined, c: Choice) => (c.custom ? c.level : Math.min(c.level, commander?.startable ?? 0));

/**
 * Whether Start Run may be pressed. Replace with shared code from the mobile branch: a copy of RogueStart.canBegin as desktop's start page
 * applies it, which the server checks again.
 */
export function ready(roster: RogueRoster, c: Choice): boolean {
  const list = shown(roster, c);
  const commander = list[picked(list, c)];
  if (c.custom && (!roster.custom || c.mods.length === 0)) return false;
  if (levelFor(commander, c) > 0 && !roster.descension) return false;
  return generates(roster, c) || !!commander && !commander.unlock;
}

/**
 * Ticks or unticks a modification. Replace with shared code from the mobile branch: a copy of desktop's start page, where ticking one
 * unticks those it excludes, and a modification that generates the commander ticks and locks those it forces.
 */
export function toggle(mods: Modification[], on: string[], id: string): string[] {
  const m = mods.find(x => x.id === id);
  if (!m) return on;
  const next = new Set(on);
  const wasGenerated = mods.some(x => x.generatesCommander && next.has(x.id));
  if (next.has(id)) next.delete(id);
  else {
    next.add(id);
    m.excludes.forEach(x => next.delete(x));
  }
  const generated = mods.some(x => x.generatesCommander && next.has(x.id));
  if (generated || wasGenerated || m.generatesCommander) {
    for (const x of mods) if (x.forcedByGenerated) { if (generated) next.add(x.id); else next.delete(x.id); }
  }
  return mods.filter(x => next.has(x.id)).map(x => x.id);
}

/** What Back does on these pages, or null when it leaves them. Leaving Custom Run unticks everything, as on desktop. */
export function backFrom(roster: RogueRoster, c: Choice): Choice | null {
  if (c.deck) return { ...c, deck: null };
  if (c.sheet) return { ...c, sheet: false };
  if (!c.customOpen) return null;
  return { ...c, custom: false, customOpen: false, mods: [], pick: shown(roster, c) === roster.commanders ? c.pick : null };
}

export const descensionLabel = (level: number) => t('lblRogueDescensionLevel', level ? String(level) : t('lblNone'));

/** runs goes back to the unfinished run, and is null with none. begin is given the deck, or an empty name when the commander is generated, and the level. */
export function NewRun({ roster, choice, set, runs, begin }: {
  roster: RogueRoster; choice: Choice; set: SetChoice; runs: (() => void) | null; begin: (deck: string, level: number) => void;
}) {
  const list = shown(roster, choice);
  const commander = list[picked(list, choice)];
  const go = () => begin(generates(roster, choice) ? '' : commander.deck, levelFor(commander, choice));
  return choice.customOpen
    ? <CustomRun roster={roster} choice={choice} set={set} begin={go} />
    : <Carousel roster={roster} choice={choice} set={set} runs={runs} begin={go} />;
}

function Carousel({ roster, choice, set, runs, begin }: {
  roster: RogueRoster; choice: Choice; set: SetChoice; runs: (() => void) | null; begin: () => void;
}) {
  const list = shown(roster, choice);
  const n = list.length;
  const at = picked(list, choice);
  const phone = isPortrait();
  const [stop, setStop] = useState<(() => void) | null>(null);
  const press = useRef<number | null>(null);
  const swiped = useRef(false);
  useEffect(() => () => stop?.(), [stop]);
  const choose = (i: number) => set(c => ({ ...c, pick: i }));
  const step = (by: number) => choose((at + by + n) % n);
  const random = () => {
    if (stop) {
      stop();
      return;
    }
    const others = list.map((x, i) => i).filter(i => i !== at && !list[i].unlock);
    if (!others.length) return;
    const to = others[Math.floor(Math.random() * others.length)];
    const end = rolling(at, to, n, choose, () => setStop(null));
    setStop(() => end);
  };
  const hv = (i: number) => (i === at ? 'hv on' : 'hv');
  const can = (i: number) => ready(roster, { ...choice, pick: i });
  const commander = list[at];
  const go = phone && !choice.sheet && (
    <div class="go" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '8px' }}>
      <button disabled={!commander || !!commander.unlock} onClick={() => set(c => ({ ...c, sheet: true }))}>{descensionLabel(levelFor(commander, choice))}</button>
      <button disabled={!roster.custom} onClick={() => set(c => ({ ...c, custom: true, customOpen: true }))}>{t('lblRogueCustomRun')}</button>
      <button disabled={!commander || !!commander.unlock} onClick={() => set(c => ({ ...c, deck: commander.deck }))}>{t('lblRogueStartDeck')}</button>
    </div>
  );
  return (
    <div class="rgm" data-pick={at} data-swipe="" data-rolling={stop ? '' : undefined}
      onPointerDown={e => { press.current = e.clientX; }}
      onPointerUp={e => {
        if (press.current === null) return;
        const dx = e.clientX - press.current;
        press.current = null;
        swiped.current = Math.abs(dx) > 60;
        if (swiped.current) step(dx < 0 ? 1 : -1);
      }}
      onClickCapture={e => {
        // The click that ends a swipe is not a choice
        if (!swiped.current) return;
        swiped.current = false;
        e.stopPropagation();
      }}>
      <div class="rgm-stage rgc">
        {list.map((c, i) => (
          <div key={c.deck} class={hv(i)} data-v={i}>
            {c.image ? <div class="rgsh-haze" style={{ backgroundImage: `url("${artUrl(c.image)}")` }} /> : <div class="rgsh-haze lockhaze" />}
          </div>
        ))}
        {runs && <div class="rgl-crumb"><button class="link" onClick={runs}>{t('lblWebRogueRuns')}</button><span>›</span><b>{t('lblRogueStartNewRun')}</b></div>}
        <div class="rgc-ring">
          {list.map((c, i) => {
            const p = place(i, at, n);
            const cls = ['rgc-c', c.unlock ? 'lock' : '', p.o === 0 ? 'mid' : p.abs === 1 ? 'near' : ''].filter(Boolean).join(' ');
            return (
              <button key={c.deck} class={cls} data-i={i} aria-pressed={i === at} aria-label={c.name ?? '???'} onClick={() => choose(i)}
                style={{ backgroundImage: c.image ? `url("${imageUrl(c.image)}")` : undefined, '--o': p.o, '--abs': p.abs, '--sg': p.sign }}>
                {c.unlock && <Icon d={ROGUE_ICONS.lock} cls="rg-ic" />}
              </button>
            );
          })}
        </div>
        <div class="rgx-bar">
          <button class="rgx-rand" data-random="" onClick={random}><Icon d={ROGUE_ICONS.dice} cls="rg-ic" /> {t('lblRandom')}</button>
          <div class="rgx-mid">
            <button data-step="-1" aria-label={t('lblRoguePreviousCommander')} onClick={() => step(-1)}>‹</button>
            <div class="rgx-dots">
              {list.map((c, i) => <button key={c.deck} class={c.unlock ? 'lk' : ''} data-i={i} aria-label={c.name ?? '???'} aria-pressed={i === at} onClick={() => choose(i)} />)}
            </div>
            <button data-step="1" aria-label={t('lblRogueNextCommander')} onClick={() => step(1)}>›</button>
          </div>
          <span />
        </div>
      </div>
      <aside class="rgm-side">
        <div class="rgm-body">
          {list.map((c, i) => <div key={c.deck} class={hv(i)} data-v={i}><Words roster={roster} commander={c} choice={choice} set={set} /></div>)}
        </div>
        <div class="rgm-end">
          {go}
          {list.map((c, i) => (
            <div key={c.deck} class={hv(i)} data-v={i}>
              {c.unlock ? <button class="primary rg-start-btn" disabled>{t('lblRogueStartRun')}</button> : <>
                <div class="rgm-opt" hidden={phone}>
                  {roster.custom
                    ? <button class={choice.custom ? 'rg-toggle on' : 'rg-toggle'} onClick={() => set(x => ({ ...x, custom: true, customOpen: true }))}><span class="sw" />{t('lblRogueCustomRun')}</button>
                    : <span />}
                  <button onClick={() => set(x => ({ ...x, deck: c.deck }))}><Icon d={ROGUE_ICONS.deck} cls="rg-ic" /> {t('lblRogueStartDeck')}</button>
                </div>
                <button class="primary rg-start-btn" disabled={!can(i)} onClick={begin}>{t('lblRogueStartRun')}</button>
              </>}
            </div>
          ))}
        </div>
      </aside>
    </div>
  );
}

function Words({ roster, commander: c, choice, set }: { roster: RogueRoster; commander: RogueCommander; choice: Choice; set: SetChoice }) {
  const level = levelFor(c, choice);
  if (c.unlock) {
    return <div class="rgm-words"><span class="camp-kicker">{c.record}</span><h1>???</h1><p>{c.unlock}</p></div>;
  }
  return (
    <div class="rgm-words">
      <span class="camp-kicker">{c.record}</span>
      <h1>{c.name}</h1>
      <span class="rg-pips">{[...c.colors].map((x, i) => <i key={i} class={`pip pip-${x}`}>{x}</i>)}</span>
      <p>{c.text}</p>
      <div class="camp-chips">{c.themes.map(x => <span key={x} class="camp-chip">{x}</span>)}</div>
      <div class="rgm-facts">
        <div><b>{c.runs}</b><span>{t('lblWebRogueRuns')}</span></div>
        <div><b>{c.wins}</b><span>{t('lblWins')}</span></div>
        <div><b>{c.maxDescension}</b><span>{t('lblWebRogueMaxDescension')}</span></div>
        <div><b>{c.seen}</b><span>{t('lblWebRogueCardsSeen')}</span></div>
      </div>
      {!roster.descension ? (
        <div class="rg-sec">
          <Lock text={t('lblRogueUnlockDescensionHint')} />
          {!roster.custom && <Lock text={t('lblRogueCustomRunWin2RunsWithAnyCommanderToUnlock')} />}
        </div>
      ) : (
        <div class="rg-sec">
          <h4>{t('lblRogueDescensionMode')}</h4>
          {c.startable === 0 && !choice.custom ? <Lock text={t('lblRogueUnlockCommanderDescensionHint', c.name ?? '')} /> : <>
            <Levels levels={roster.levels} level={level} startable={choice.custom ? roster.levels.length : c.startable} tipFor={c.name}
              choose={to => set(x => ({ ...x, level: to }))} />
            {level > 0 && <p>{roster.levels[level - 1].text}</p>}
          </>}
        </div>
      )}
    </div>
  );
}

export const Lock = ({ text }: { text: string }) => <div class="rg-lock"><Icon d={ROGUE_ICONS.lock} cls="rg-ic" /><span>{text}</span></div>;

/**
 * None and the levels, those above startable locked. tipFor names the commander in the next level's lock text.
 * Replace with shared code from the mobile branch: the lock text and the levels a commander may start, as desktop's start page words them.
 */
export function Levels({ levels, level, startable, tipFor, choose }: {
  levels: DescensionStep[]; level: number; startable: number; tipFor?: string; choose: (level: number) => void;
}) {
  return (
    <div class="rgm-lv">
      {[t('lblNone'), ...levels.map(l => l.name)].map((name, i) => {
        const locked = i > startable;
        const tip = tipFor && i === startable + 1 ? { 'data-term': t('lblRogueLevel', i), 'data-tip': t('lblRogueUnlockNextDescensionHint', tipFor, i - 1, i) } : {};
        return (
          <button key={i} aria-pressed={i === level} disabled={locked} {...tip} onClick={() => choose(i)}>
            <b>{i || '–'}</b>{name}{locked && <Icon d={ROGUE_ICONS.lock} cls="rg-ic" />}
          </button>
        );
      })}
    </div>
  );
}

/** The phone's Descension levels, in a sheet over the page. */
export function DescensionSheet({ roster, choice, set }: { roster: RogueRoster; choice: Choice; set: SetChoice }) {
  const list = shown(roster, choice);
  const c = list[picked(list, choice)];
  const close = () => set(x => ({ ...x, sheet: false }));
  const startable = choice.custom ? roster.levels.length : c?.startable ?? 0;
  const level = levelFor(c, choice);
  return (
    <Backdrop layer="rg-psheet" dismiss={close}>
      <div class="box">
        <i class="grab" />
        <div class="bd">
          <h3>{t('lblRogueDescensionMode')}</h3>
          {!roster.descension ? <Lock text={t('lblRogueUnlockDescensionHint')} />
            : startable === 0 ? <Lock text={t('lblRogueUnlockCommanderDescensionHint', c?.name ?? '')} />
            : <Levels levels={roster.levels} level={level} startable={startable} choose={to => set(x => ({ ...x, level: to }))} />}
          {roster.descension && level > 0 && <p>{roster.levels[level - 1].text}</p>}
        </div>
        <div class="ft2"><button class="primary" onClick={close}>{t('lblDone')}</button></div>
      </div>
    </Backdrop>
  );
}

/** A commander's start deck, read only. */
export function StartDeck({ details, close }: { details: DeckDetails; close: () => void }) {
  return (
    <Backdrop layer="rg-dialog" dismiss={close}>
      <div class="dialog" role="dialog" aria-label={details.name}>
        <h3>{details.name}</h3>
        <p>{t('lblRogueStartingDeck')}</p>
        <Curve curve={details.stats.curve} spells={details.stats.spells} px={44} />
        <div class="dk-cards" data-list style={{ maxHeight: '55vh', overflowY: 'auto' }}>{details.main.map(g => <CardGroup key={g.heading} heading={g.heading} cards={g.cards} />)}</div>
        <div class="actions"><button class="primary" onClick={close}>{t('lblClose')}</button></div>
      </div>
    </Backdrop>
  );
}
