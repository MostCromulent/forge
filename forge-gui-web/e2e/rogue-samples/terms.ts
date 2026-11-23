import { HOME, type Sample } from '../rogue-sample';

// The Traits page is the mockup's own markup, and its Fellow is tapped
export default {
  messages: [HOME],
  under: true,
  tap: '#campaign [data-open]',
} satisfies Sample;
