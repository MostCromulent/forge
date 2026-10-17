// Which layout the page uses. Portrait is a phone or tablet held upright. Everything else uses the desktop layout.

const QUERY = '(orientation: portrait) and (max-width: 899px)';

let portrait = false;

const typing = () => {
  const el = document.activeElement;
  return el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
};

export function initForm(onChange: () => void): void {
  const media = matchMedia(QUERY);
  const apply = () => {
    // An on-screen keyboard can make a portrait phone look landscape, so the layout does not change while a field has focus
    if (typing() || media.matches === portrait) return false;
    portrait = media.matches;
    if (portrait) document.documentElement.dataset.form = 'portrait';
    else delete document.documentElement.dataset.form;
    return true;
  };
  const changed = () => { if (apply()) onChange(); };
  media.addEventListener('change', changed);
  document.addEventListener('focusout', () => setTimeout(changed));
  // The page is not drawn yet, so the first check does not need a redraw
  apply();
}

export function isPortrait(): boolean {
  return portrait;
}
