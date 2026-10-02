// The page's text comes from the server first, so the modules that draw the page are loaded only once it has arrived

import { loadText } from './text';

await loadText();
await import('./main');
