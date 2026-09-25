// Mana symbols and colour pips as Preact components, for the screens around the board.

import { symbolParts, symbolUrl } from './images';

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

/** One colour's pip: W, U, B, R, G or C. */
export const Pip = ({ letter }: { letter: string }) => <i class={`pip pip-${letter}`}>{letter}</i>;
