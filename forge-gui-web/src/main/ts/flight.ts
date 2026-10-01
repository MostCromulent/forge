// A card lifted from where it was chosen and flown to where it lands: a pick from a draft pack to the picks, a card
// from the deck editor's catalogue to its line in the deck. The card is lifted as it looked when chosen, and flies once
// the place it lands on has been drawn.

const FLY_MS = 380;

/** The card lifted, where it stood, and what it is flying for, until its place is drawn. */
let inFlight: { face: HTMLElement; from: DOMRect; key: string } | null = null;

const reduced = () => document.documentElement.dataset.motion === 'reduced';

/** Lifts a card's image where it stands. A card showing only its text, or with no image yet, stays put. */
export function lift(img: HTMLImageElement | null | undefined, key: string): void {
  inFlight = !reduced() && img?.complete && img.naturalWidth
    ? { face: img.cloneNode() as HTMLElement, from: img.getBoundingClientRect(), key } : null;
}

/** Lifts a card from a line with no picture of it, such as a table row: its image, card-shaped, at the line's start. */
export function liftFromLine(src: string, line: DOMRect, key: string): void {
  if (reduced()) {
    inFlight = null;
    return;
  }
  const face = new Image();
  face.src = src;
  face.alt = '';
  // An image still loading flies as a dark card rather than nothing
  face.style.background = '#1c1f26';
  const height = Math.max(line.height * 1.6, 56);
  const width = height * 63 / 88;
  inFlight = { face, from: new DOMRect(line.left, line.top + (line.height - height) / 2, width, height), key };
}

/** What the card in flight is flying for, if one is. */
export function flyingFor(): string | null {
  return inFlight?.key ?? null;
}

/** Drops a lifted card that will not fly, such as one a drag has already carried. */
export function cancelFlight(): void {
  inFlight = null;
}

/**
 * Flies the lifted card onto its place, which shows once it arrives. A place out of sight in its scrolled list (view)
 * is flown to the list's heading instead, fading as it goes in.
 */
export function land(place: HTMLElement | null, view: Element | null | undefined, heading: Element | null | undefined): void {
  const flight = inFlight;
  inFlight = null;
  if (!flight || !place) return;
  const box = view?.getBoundingClientRect();
  let to = place.getBoundingClientRect();
  const hidden = !box || to.bottom < box.top || to.top > box.bottom;
  if (hidden) to = heading?.getBoundingClientRect() ?? to;
  const { face, from } = flight;
  Object.assign(face.style, { position: 'fixed', left: `${from.left}px`, top: `${from.top}px`, width: `${from.width}px`,
    height: `${from.height}px`, margin: '0', zIndex: '60', pointerEvents: 'none', transformOrigin: '0 0', borderRadius: '6px',
    boxShadow: '0 12px 28px #000b' });
  document.body.append(face);
  // Scaled to fit its place, so a card shrinks into a line of a list
  const scale = Math.min(to.width / from.width, to.height / from.height);
  const dy = to.top - from.top + (to.height - from.height * scale) / 2;
  if (!hidden) place.style.visibility = 'hidden';
  const glide = face.animate([
    { transform: 'none', opacity: 1 },
    { transform: `translate(${to.left - from.left}px, ${dy}px) scale(${scale})`, opacity: hidden ? 0 : 1 },
  ], { duration: FLY_MS, easing: 'cubic-bezier(.3,.1,.2,1)' });
  glide.onfinish = glide.oncancel = () => {
    face.remove();
    place.style.visibility = '';
  };
}
