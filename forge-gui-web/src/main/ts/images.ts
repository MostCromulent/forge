// Where card, mana symbol and ability icon images come from, and how text with {symbols} in it is drawn.

import { stateOf, type Model } from './model';
import type { CardView } from './protocol';
import { make } from './dom';

export const imageUrl = (key: string): string => `img?key=${encodeURIComponent(key)}`;

/** The image shrunk by the server to its one width, because a browser shrinking the full scan on a moving card breaks up the text. */
export const smallImage = (src: string): string => (src ? `${src}&w=256` : '');

/** Forge's foil mark on the name in a card's image key, which is what tells the server to paint the foil onto the picture. */
const foilKey = (key: string): string => key.replace(/^c:[^|$]*/, '$&+');

// Empty for a card the viewer may not see, so the element falls back to its back or its frame
export function cardImageSrc(model: Model, card: CardView | null | undefined): string {
  if (!card || !model.visible.has(card.$key)) return '';
  const state = stateOf(model, card);
  if (!state.ImageKey) return '';
  // Foil as the server's Foil.key has it: the paper card is one, or the match made this card one at random
  return imageUrl(card.PaperFoil || state.FoilIndex ? foilKey(state.ImageKey) : state.ImageKey);
}

// Assigning the same src again restarts the request; the answer says whether the image changed
export function setImage(img: HTMLImageElement, src: string): boolean {
  if ((img.getAttribute('src') ?? '') === src) return false;
  if (src) img.src = src;
  else img.removeAttribute('src');
  return true;
}

// A card with no image keeps its frame, so the container carries the mark rather than the image
export function noImageOnError(container: HTMLElement, img: HTMLImageElement): void {
  img.addEventListener('error', () => container.classList.add('noimg'));
}

// {2}{B} and {T} are drawn from the skin's icon sheet; a symbol the sheet has no image for keeps its text
export function setSymbolText(el: HTMLElement, text: string | null | undefined): void {
  el.replaceChildren();
  appendSymbolText(el, text ?? '');
}

export function appendSymbolText(el: HTMLElement, text: string): void {
  for (const part of symbolParts(text)) {
    if (part.symbol) {
      const img = make('img', 'sym');
      img.alt = part.text;
      img.src = symbolUrl(part.symbol);
      el.append(img);
    } else {
      const span = document.createElement('span');
      span.textContent = part.text;
      el.append(span);
    }
  }
}

/** Text cut into its plain runs and its {symbols}; symbol is the symbol's name for a symbol, null for text. */
export function symbolParts(text: string): { text: string; symbol: string | null }[] {
  return String(text).split(/(\{[^}]{1,6}\})/).filter(part => part).map(part => ({
    text: part,
    symbol: part.length > 2 && part.startsWith('{') && part.endsWith('}') ? part.slice(1, -1) : null,
  }));
}

// A hybrid or Phyrexian shard is named without its slash, as the skin's icon sheet keys them
export const symbolUrl = (symbol: string): string => `mana?s=${encodeURIComponent(symbol.replace(/\//g, ''))}`;

/** A keyword's icon, cut from the skin's ability sheet. The host names the icon; see FSkinProp.iconFromKeyword. */
export const abilityUrl = (icon: string): string => `ability?k=${encodeURIComponent(icon)}`;
/** One of the skin icons a campaign shows, by its skin name, such as IMG_AETHER_SHARD. */
export const skinIconUrl = (icon: string): string => `skinicon?k=${encodeURIComponent(icon)}`;

export function hideOnError(img: HTMLImageElement): void {
  img.addEventListener('error', () => { img.hidden = true; });
}
