// The finished runs, each drawn with the planes and nodes its path went through

import { artUrl } from '../images';
import { isPortrait } from '../form';
import { t, type TextKey } from '../text';
import { ROGUE_ICONS } from './icons';
import { Sprite, ringClass, sealGlyph, type SpriteName } from './kit';
import type { HistoryRun, RogueHistoryPage } from '../protocol';

/** Each outcome's frame, icon and word. An unknown outcome is drawn plain, with the word it was saved as. */
const OUTCOMES: Record<string, [string, SpriteName, TextKey]> = {
  VICTORY: ['win', 'award', 'lblWebRogueVictory'],
  DEFEAT: ['loss', 'xmark', 'lblRogueDefeat'],
  ABANDONED: ['left', 'skull', 'lblWebRogueAbandoned'],
};
/** The icon of each side node by its kind. An unknown kind is an empty mark. */
const SIDES: Record<string, keyof typeof ROGUE_ICONS> = { SANCTUM: 'sanctum', BAZAAR: 'bazaar', EVENT: 'event', CHEST: 'chest' };
const background = (key: string | null | undefined) => (key ? { backgroundImage: `url("${artUrl(key)}")` } : undefined);

/** A phone leaves out the date, and the traits by its stylesheet. */
function Run({ run, deck }: { run: HistoryRun; deck: (id: number) => void }) {
  const outcome = OUTCOMES[run.outcome];
  return (
    <div class={run.outcome === 'VICTORY' ? 'rg-run' : 'rg-run lost'}>
      <div class="a" style={background(run.image)} />
      <div class="t">
        <b>{run.commander}</b>
        <span>{run.line}</span>
        <div class="rg-steps">
          {run.steps.map((s, i) => (s.kind === 'PLANEBOUND' ? <i key={i} title={s.name ?? undefined} style={background(s.image)} />
            : <i key={i} class={`s${SIDES[s.kind] ? ringClass(SIDES[s.kind]) : ''}`}>{SIDES[s.kind] && sealGlyph(SIDES[s.kind], 16)}</i>))}
        </div>
      </div>
      <div class="traits">{run.traits.map((tr, i) => <span key={i} class="tn" title={tr.tip ?? undefined}>{tr.name}</span>)}</div>
      <div class="end">
        {outcome ? <span class={`rg-out ${outcome[0]}`}><Sprite k={outcome[1]} />{t(outcome[2])}</span> : <span class="rg-out">{run.outcome}</span>}
        <span>{isPortrait() ? run.time : `${run.date} · ${run.time}`}</span>
        {run.deck && <button onClick={() => deck(run.id)}>{t('lblViewDeck')}</button>}
      </div>
    </div>
  );
}

/** History's page. reset asks before the history is cleared, which a phone offers in its header's menu instead. */
export function HistoryPage({ page, reset, deck }: { page: RogueHistoryPage | null; reset: () => void; deck: (id: number) => void }) {
  const runs = page?.runs ?? [];
  return (
    <div class="rg-hpage">
      <span class="rg-haze" style={background(page?.art)} />
      <div class="rg-scroll">
        {!isPortrait() && (
          <div class="rg-head">
            <h2>{t('lblRogueRunHistory')}</h2>
            <span class="sp" />
            <button disabled={!runs.length} onClick={reset}>{t('lblRogueResetHistory')}</button>
          </div>
        )}
        {page && (runs.length ? <div class="rg-hist">{runs.map(r => <Run key={r.id} run={r} deck={deck} />)}</div>
          : <p class="rg-none">{t('lblRogueNoCompletedRunsYet')}</p>)}
      </div>
    </div>
  );
}
