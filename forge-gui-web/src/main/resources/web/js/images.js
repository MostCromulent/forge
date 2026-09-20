import { stateOf } from './model.js';

export const imageUrl = key => `img?key=${encodeURIComponent(key)}`;

// Empty for a card the viewer may not see, so the element falls back to its back or its frame
export function cardImageSrc(model, card) {
  const key = card && model.visible.has(card.$key) ? stateOf(model, card).ImageKey : null;
  return key ? imageUrl(key) : '';
}

// Assigning the same src again restarts the request; the answer says whether the image changed
export function setImage(img, src) {
  if ((img.getAttribute('src') ?? '') === src) return false;
  if (src) img.src = src;
  else img.removeAttribute('src');
  return true;
}

// A card with no image keeps its frame, so the container carries the mark rather than the image
export function noImageOnError(container, img) {
  img.addEventListener('error', () => container.classList.add('noimg'));
}

// {2}{B} and {T} are drawn from the skin's icon sheet; a symbol the sheet has no image for keeps its text
export function setSymbolText(el, text, className) {
  el.replaceChildren();
  appendSymbolText(el, text ?? '', className);
}

export function appendSymbolText(el, text, className) {
  for (const part of String(text).split(/(\{[^}]{1,6}\})/)) {
    if (!part) {
      continue;
    }
    const symbol = part.length > 2 && part.startsWith('{') && part.endsWith('}') ? part.slice(1, -1) : null;
    if (symbol) {
      const img = document.createElement('img');
      img.className = 'sym';
      img.alt = part;
      // A hybrid or Phyrexian shard is named without its slash, as the skin's icon sheet keys them
      img.src = `mana?s=${encodeURIComponent(symbol.replace(/\//g, ''))}`;
      el.append(img);
    } else {
      const span = document.createElement('span');
      if (className) {
        span.className = className;
      }
      span.textContent = part;
      el.append(span);
    }
  }
}

export function hideOnError(img) {
  img.addEventListener('error', () => { img.hidden = true; });
}
