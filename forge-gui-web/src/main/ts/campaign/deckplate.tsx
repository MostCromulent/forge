// A deck's plate in a list of a campaign's decks, and the panel beside the list for the deck being viewed

import type { ComponentChildren } from 'preact';
import { artUrl } from '../sleeves';
import { imageUrl } from '../images';
import { Pip, Pips } from '../symbols';
import { t } from '../text';

export function Plate({ name, image, colors, selected, pressed, pick, lines, tag }: {
  name: string; image?: string | null; colors: string; selected: boolean; pressed: boolean; pick: () => void; lines: ComponentChildren[];
  /** The text of the tag on a selected plate. It defaults to Selected. */
  tag?: string;
}) {
  return (
    <button class="cq-cmd" aria-pressed={pressed} onClick={pick}>
      <div class="a" style={{ backgroundImage: image ? `url("${artUrl(image)}")` : undefined }} />
      {selected && <span class="cq-chip brass tag">{tag ?? t('lblSelected')}</span>}
      <div class="t">
        <b>{name}</b>
        <span class="l"><span class="pips">{colors ? <Pips colors={colors} /> : <Pip letter="C" />}</span>{lines[0]}</span>
        {lines[1] && <span class="l">{lines[1]}</span>}
      </div>
    </button>
  );
}

/** The panel beside a list of decks. It shows the deck's card, name and chips, any reason it cannot be played, its details and the foot buttons. */
export function DeckPanel({ image, title, chips, problem, details, children }: {
  image?: string | null; title: string; chips: ComponentChildren; problem?: string | null; details?: ComponentChildren; children: ComponentChildren;
}) {
  return (
    <aside class="cq-side">
      <div class="cq-cmd-detail">
        {image && <img class="cq-card" alt={title} src={imageUrl(image)} />}
        <h3>{title}</h3>
        <div class="cq-chips">{chips}</div>
        {problem && <p class="cq-warn">{problem}</p>}
        {details}
      </div>
      <div class="cq-foot">{children}</div>
    </aside>
  );
}
