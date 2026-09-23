import { stateOf, type Model } from './model';
import type { CardView } from './protocol';

export const imageUrl = (key: string): string => `img?key=${encodeURIComponent(key)}`;

// Empty for a card the viewer may not see, so the element falls back to its back or its frame
export function cardImageSrc(model: Model, card: CardView | null | undefined): string {
  const key = card && model.visible.has(card.$key) ? stateOf(model, card).ImageKey : null;
  return key ? imageUrl(key) : '';
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
export function setSymbolText(el: HTMLElement, text: string | null | undefined, className?: string): void {
  el.replaceChildren();
  appendSymbolText(el, text ?? '', className);
}

export function appendSymbolText(el: HTMLElement, text: string, className?: string): void {
  for (const part of symbolParts(text)) {
    if (part.symbol) {
      const img = document.createElement('img');
      img.className = 'sym';
      img.alt = part.text;
      img.src = symbolUrl(part.symbol);
      el.append(img);
    } else {
      const span = document.createElement('span');
      if (className) {
        span.className = className;
      }
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

export function hideOnError(img: HTMLImageElement): void {
  img.addEventListener('error', () => { img.hidden = true; });
}
