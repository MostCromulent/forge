// Mana symbols and colour pips as Preact components, for the screens around the board.

import { symbolParts, symbolUrl } from './images';
import { t, type TextKey } from './text';

/** Text with its {2}{B} and {T} drawn from the skin's icon sheet, as setSymbolText draws it on the board. */
export function SymbolText({ text, muted }: { text: string | null | undefined; muted?: boolean }) {
  return <>{symbolParts(text ?? '').map((part, i) => part.symbol
    ? <img key={i} class="sym" alt={part.text} src={symbolUrl(part.symbol)} />
    : <span key={i} class={muted ? 'muted' : undefined}>{part.text}</span>)}</>;
}

/** A deck's colours as the pips the lobby and the deck finder show. */
export function Pips({ colors }: { colors: string | null | undefined }) {
  return <>{[...(colors ?? '')].map(c => <Pip key={c} letter={c} />)}</>;
}

/** One colour's pip: the mana symbol of W, U, B, R, G or C, from the skin as a card's cost draws it. */
export const Pip = ({ letter }: { letter: string }) => <img class="pip" alt={letter} src={symbolUrl(letter)} />;

/** The colours in the order Magic writes them, then colourless, each with the key of its name. */
export const COLOURS: [string, TextKey][] = [['W', 'lblWhite'], ['U', 'lblBlue'], ['B', 'lblBlack'], ['R', 'lblRed'], ['G', 'lblGreen'],
  ['C', 'lblColorless']];
export const FIVE_COLOURS = COLOURS.slice(0, 5);

/** A set with one item put in, or taken out if it was there. */
export function toggled<T>(set: ReadonlySet<T>, item: T): Set<T> {
  const next = new Set(set);
  if (!next.delete(item)) next.add(item);
  return next;
}

/** A row of colour buttons, each pressed or not. colourless adds C, and title says more about a colour on hover. */
export function ColourToggles({ pressed, toggle, label, colourless, title }: {
  pressed: (letter: string) => boolean; toggle: (letter: string) => void; label: string; colourless?: boolean;
  title?: (letter: string, name: string) => string;
}) {
  return (
    <div class="colours" role="group" aria-label={label}>
      {(colourless ? COLOURS : FIVE_COLOURS).map(([letter, name]) => (
        <button key={letter} class="colour" aria-label={t(name)} aria-pressed={pressed(letter)} title={title?.(letter, t(name))}
          onClick={() => toggle(letter)}><Pip letter={letter} /></button>
      ))}
    </div>
  );
}
