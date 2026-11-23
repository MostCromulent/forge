// The Codex: statistics, and what has been seen and acquired of the commanders, planebounds and traits, and the journal

import { Fragment } from 'preact';
import { Figures } from '../campaign/campaign';
import { artUrl, imageUrl } from '../images';
import { avatarUrl } from '../avatarsleeves';
import { isPortrait } from '../form';
import { t, type TextKey } from '../text';
import { Seal, TraitCard } from './traits';
import { Words } from './terms';
import type { CodexEntry, CodexFact, CodexItem, RogueCodexPage } from '../protocol';

export type CodexTab = 'stats' | 'commanders' | 'planebounds' | 'traits' | 'journal';

/** Each tab with its name on a wide screen and on a phone. */
const TABS: [CodexTab, TextKey, TextKey][] = [['stats', 'lblRogueGlobalStats', 'lblWebRogueStats'], ['commanders', 'lblRogueCommanders', 'lblCommanders'],
  ['planebounds', 'lblRoguePlanebounds', 'lblRoguePlanebounds'], ['traits', 'lblRogueTraits', 'lblRogueTraits'], ['journal', 'lblRogueJournal', 'lblRogueJournal']];
const FACTS: Record<string, TextKey> = {
  runs: 'lblWebRogueRuns', wins: 'lblWins', descension: 'lblWebRogueMaxDescension', endless: 'lblWebRogueMaxEndless', slain: 'lblWebRogueSlain', defeats: 'lblWebRogueDefeatedBy',
};
const background = (key: string | null | undefined) => (key ? { backgroundImage: `url("${artUrl(key)}")` } : undefined);

/** A phone names the cards seen and acquired shortly, and leaves out the highest levels won. */
function Facts({ facts, tab }: { facts: CodexFact[]; tab: CodexTab }) {
  const phone = isPortrait() && tab !== 'traits';
  const label = (kind: string): string => {
    if (kind === 'seen') return t(tab === 'traits' ? 'lblWebRogueTraitsSeen' : phone ? 'lblWebRogueSeen' : 'lblWebRogueCardsSeen');
    if (kind === 'acquired') return t(tab === 'traits' ? 'lblWebRogueTraitsAcquired' : phone ? 'lblWebRogueAcquired' : 'lblWebRogueCardsAcquired');
    return FACTS[kind] ? t(FACTS[kind]) : kind;
  };
  const shown = phone ? facts.filter(f => f.kind !== 'descension' && f.kind !== 'endless') : facts;
  return <div class="rg-facts">{shown.map(f => <div key={f.kind}><b>{f.value}</b><span>{label(f.kind)}</span></div>)}</div>;
}

/** An unknown card is a back, a seen one grey, an acquired one in full colour. */
function Dex({ item }: { item: CodexItem }) {
  if (item.state === 'unknown') return <div><div class="unk" /></div>;
  return <div class={item.state === 'seen' ? 'seen' : ''}><img alt="" data-image={item.image ?? undefined} src={imageUrl(item.image!)} /></div>;
}

function TraitItem({ item }: { item: CodexItem }) {
  if (isPortrait()) {
    if (item.state === 'unknown') return <div class="rg-prow unk" />;
    const seen = item.state === 'seen';
    return (
      <div class={seen ? 'rg-prow seen' : 'rg-prow'}>
        <Seal trait={item.trait!} />
        <span><b>{item.trait!.name}</b><em><Words parts={item.trait!.text} /></em></span>
        {seen && <small>{t('lblWebRogueSeen')}</small>}
      </div>
    );
  }
  if (item.state === 'unknown') return <div><div class="unk" /></div>;
  return <div class={item.state === 'seen' ? 'seen' : ''}><TraitCard trait={item.trait!} small /></div>;
}

/** The entry shown: a commander's or planebound's cards, or a journal entry's text. back returns a phone to the list. */
function Entry({ entry, tab, back }: { entry: CodexEntry; tab: CodexTab; back: () => void }) {
  const phone = isPortrait();
  const link = phone && <button class="link" onClick={back}>{`‹ ${t(TABS.find(x => x[0] === tab)![2])}`}</button>;
  const face = (size?: string) => entry.face != null && (
    <span class="rg-face"><span class="pic" style={{ width: size, height: size, backgroundImage: `url("${avatarUrl(entry.face)}")` }} /></span>
  );
  const text = entry.paragraphs.map((p, i) => <p key={i}>{p}</p>);
  if (tab === 'journal') {
    return (
      <div class="rg-scroll">
        {phone ? <div class="rg-read">{link}<div class="jh">{face()}<h2>{entry.title}</h2></div>{text}</div>
          : <div class="rg-top">{face('104px')}<div class="t rg-read"><h2>{entry.title}</h2>{text}</div></div>}
      </div>
    );
  }
  return (
    <div class="rg-scroll">
      <div class="rg-top">
        {entry.image && <img alt="" data-image={entry.image} src={imageUrl(entry.image)} />}
        {entry.plane && <span class="rg-plane pl"><img alt={entry.planeName ?? ''} data-image={entry.plane} src={imageUrl(entry.plane)} /></span>}
        <div class="t">{link}<h2>{entry.title}</h2>{!phone && <Facts facts={entry.facts} tab={tab} />}</div>
      </div>
      {phone && <Facts facts={entry.facts} tab={tab} />}
      {entry.groups.map(g => <div key={g.title} class="rg-group"><h4>{g.title}</h4><div class="rg-dex">{g.items.map((item, i) => <Dex key={i} item={item} />)}</div></div>)}
    </div>
  );
}

/**
 * The Codex's page. id is the entry the player chose, which on a phone opens it in place of the list, and choose picks another or none.
 * reset asks before a reset, and is left out where resets cannot be made, such as in a run.
 */
export function CodexPage({ page, tab, id, setTab, choose, reset }: {
  page: RogueCodexPage | null; tab: CodexTab; id: string | null; setTab: (tab: CodexTab) => void; choose: (id: string | null) => void; reset?: (kind: string) => void;
}) {
  const phone = isPortrait();
  const shown = page?.tab === tab ? page : null;
  const rows = shown && (
    <div class="rg-list">
      {shown.rows.map((r, i) => <Fragment key={i}>
        {r.group && <div class="rg-ls-h">{r.group}</div>}
        {tab === 'journal' ? <button class="line" aria-pressed={!phone && r.id === shown.id} onClick={() => choose(r.id ?? null)}><b>{r.name}</b>{r.line && <span>{r.line}</span>}</button>
          : (
            <button class={r.id ? '' : 'q'} aria-pressed={!phone && !!r.id && r.id === shown.id} onClick={() => { if (r.id) choose(r.id); }}>
              {r.id ? <i style={background(r.art)} /> : <i>?</i>}<b>{r.name}</b><span>{r.line}</span>
            </button>
          )}
      </Fragment>)}
    </div>
  );
  let body = null;
  if (shown?.tab === 'stats') {
    body = (
      <div class="camp-stats">
        {shown.stats.map(g => <Fragment key={g.title}><div class="camp-stats-head"><div><h2>{g.title}</h2></div></div><Figures figures={g.figures} /></Fragment>)}
      </div>
    );
  } else if (shown?.tab === 'traits' && shown.entry) {
    const traits = (
      <div class="rg-scroll">
        <Facts facts={shown.entry.facts} tab={tab} />
        {shown.entry.groups.map(g => (
          <div key={g.title} class="rg-group">
            <h4>{g.title}</h4>
            <div class={phone ? 'rg-pstock' : 'rg-traits'}>{g.items.map((item, i) => <TraitItem key={i} item={item} />)}</div>
          </div>
        ))}
      </div>
    );
    body = phone ? traits : <div class="rg-codex full">{traits}</div>;
  } else if (shown) {
    const entry = shown.entry && <Entry entry={shown.entry} tab={tab} back={() => choose(null)} />;
    body = !phone ? <div class="rg-codex">{rows}{entry}</div> : id ? entry : rows;
  }
  return (
    <div class="rg-cx">
      <span class="rg-haze" style={background(shown?.art)} />
      <div class="rg-sub-tabs">
        <span class="seg">{TABS.map(([to, wide, short]) => <button key={to} aria-pressed={to === tab} onClick={() => setTab(to)}>{t(phone ? short : wide)}</button>)}</span>
        {!phone && <span class="sp" />}
        {!phone && tab === 'stats' && reset && <>
          <button onClick={() => reset('tutorials')}>{t('lblRogueResetTutorials')}</button>
          <button style={{ color: 'var(--concede)' }} onClick={() => reset('progress')}>{t('lblRogueResetProgress')}</button>
        </>}
      </div>
      {body}
    </div>
  );
}
