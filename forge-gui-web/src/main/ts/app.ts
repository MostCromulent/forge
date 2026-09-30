// The page's entry. Its text comes from the server before anything runs that might show some (text.ts), so the
// modules that draw the page are loaded only once it has arrived.

import { loadText } from './text';

await loadText();
await import('./main');
