// A deck shown outside the editor, as the deck finder, the importer, Quest and Conquest show one: its curve and its cards by heading

import { imageUrl } from '../images';
import type { EditorCard } from '../protocol';
import { t } from '../text';
import { headingText } from './decklist';

/** A deck's mana curve as bars px tall at most, with the average beside the heading when it is given. */
// Bar heights are pixels because a percentage would resolve against an auto-sized row and collapse
export function Curve({ curve, creatures, average, px }: { curve: number[]; creatures: number[]; average?: number; px: number }) {
  const tallest = Math.max(1, ...curve);
  return (
    <div class="curve">
      <h4>{t('lblManaCurve')}{average !== undefined && <> <span>{t('lblWebEditorAverage', average)}</span></>}</h4>
      <div class="bars">
        {curve.map((n, i) => {
          // The last bucket holds everything at that mana value and above
          const label = i === curve.length - 1 ? `${i}+` : `${i}`;
          const beasts = creatures[i] ?? 0;
          return (
            <span key={i} class="bar" title={t(beasts === 1 ? 'lblWebEditorCurveBarOne' : 'lblWebEditorCurveBar', label, beasts, n - beasts)}>
              <span class={n ? 'stack' : 'stack empty'} style={{ height: `${n ? Math.max(3, Math.round((n / tallest) * px)) : 2}px` }}>
                {n > beasts && <i class="other" style={{ flexGrow: n - beasts }} />}
                {beasts > 0 && <i class="creature" style={{ flexGrow: beasts }} />}
              </span>
              <em>{label}</em>
            </span>
          );
        })}
      </div>
      <div class="curve-key"><span><i class="creature" />{t('lblCreatures')}</span><span><i class="other" />{t('lblWebEditorOtherSpells')}</span></div>
    </div>
  );
}

/** One heading of a deck list and its cards, as lines or as stacked pictures, marking the Commander game changers. */
export function CardGroup({ heading, cards, marked, stacked }: {
  heading: string; cards: EditorCard[]; marked?: ReadonlySet<string>; stacked?: boolean;
}) {
  return (
    <div class="group">
      <h4>{headingText(heading)}<span>{cards.reduce((n, c) => n + c.count, 0)}</span></h4>
      {cards.map(c => stacked ? (
        <div key={c.name} class={`deck-stack${c.problem ? ' bad' : ''}`} data-image={c.image} title={c.problem ? `${c.name}: ${c.problem}` : c.name}>
          <img alt={c.name} src={imageUrl(c.image)} draggable={false} />
          {c.count > 1 && <span class="deck-count">×{c.count}</span>}
          {marked?.has(c.name) && <span class="gc" title={t('lblWebBracketGameChanger')}>GC</span>}
        </div>
      ) : (
        <div key={c.name} class={`dk-line ed-line${c.problem ? ' bad' : ''}${marked?.has(c.name) ? ' changer' : ''}`} data-image={c.image}>
          <span class="n">{c.count}</span><span class="nm">{c.name}</span>{c.problem && <span class="flag">! {c.problem}</span>}
          {marked?.has(c.name) && <span class="gc" title={t('lblWebBracketGameChanger')}>GC</span>}
        </div>
      ))}
    </div>
  );
}
