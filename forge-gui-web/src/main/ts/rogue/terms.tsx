// Text with card names and terms in it, and the box that explains a term on hover, keyboard focus or tap

import { useEffect, useRef } from 'preact/hooks';
import { createPortal } from 'preact/compat';
import { Icon } from '../icons';
import { ROGUE_ICONS } from './icons';
import type { Part } from '../protocol';

/** The terms that are kinds of card or effect, which their icon leads. */
const TERM_ICONS: Record<string, string> = { Trait: ROGUE_ICONS.trait, Item: ROGUE_ICONS.item, Fellow: ROGUE_ICONS.fellow, Scroll: ROGUE_ICONS.scroll };

/** Plain text, with its line breaks. */
const lines = (text: string) => text.split('\n').flatMap((line, i) => (i ? [<br key={i} />, line] : [line]));

/** Draws parts: a card's name previews the card, a term is explained by Terms. A part that only previews has no text and is left out. */
export function Words({ parts }: { parts: Part[] }) {
  return <>{parts.map((p, i) => {
    if (p.kind === 'term') {
      const icon = TERM_ICONS[p.term!];
      return <span key={i} class={icon ? 'rg-kw ico' : 'rg-kw'} data-term={p.term} data-tip={p.tip} tabIndex={0}>{icon && <Icon d={icon} cls="rg-ic" />}{p.text}</span>;
    }
    return p.kind === 'card' && p.text ? <span key={i} class="rg-cn" data-image={p.image}>{p.text}</span> : lines(p.text);
  })}</>;
}

/** The page's one term box. Pointing at, focusing or tapping an element with data-tip explains it, and anything else hides the box. */
export function Terms() {
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const explain = (e: Event) => {
      const el = e.target instanceof Element ? e.target.closest<HTMLElement>('[data-tip]') : null;
      const tip = box.current!;
      tip.hidden = !el;
      if (!el) return;
      const name = document.createElement('b');
      name.textContent = el.dataset.term ?? '';
      tip.replaceChildren(name, el.dataset.tip!);
      // Centred under the term and kept on screen, or above it when there is no room below
      const r = el.getBoundingClientRect(), w = tip.offsetWidth, h = tip.offsetHeight;
      tip.style.left = `${Math.max(8, Math.min(innerWidth - w - 8, r.left + r.width / 2 - w / 2))}px`;
      tip.style.top = `${r.bottom + 10 + h > innerHeight ? r.top - h - 10 : r.bottom + 10}px`;
    };
    const events = ['mouseover', 'focusin', 'click'];
    for (const ev of events) document.addEventListener(ev, explain);
    return () => { for (const ev of events) document.removeEventListener(ev, explain); };
  }, []);
  return createPortal(<div id="rg-tipbox" ref={box} hidden />, document.body);
}
