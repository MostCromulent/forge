import { onMap, press, type Sample } from '../rogue-sample';

// The phone mockup shows the plane's first paragraph
export default {
  messages: onMap(undefined, { text: { 'selesnya-loft-gardens': 'If an effect would create one or more tokens, it creates twice that many instead. The same is true of counters put on a permanent.' } }),
  save: 'run',
  steps: page => press(page, '#campaign .rg-sheetbar .go button:nth-child(2)'),
  owned: ['.ph'],
} satisfies Sample;
