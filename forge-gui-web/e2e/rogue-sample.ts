// What zz-rogue-match sends for one Rogue Commander mockup. Each screen has a file rogue-samples/<id>.ts, named by the mockup's id

import type { Page } from '@playwright/test';
import type { ServerMessage } from '../src/main/ts/protocol';

export interface Sample {
  /** Sent in order after the hello, with the mockup's own content. */
  messages: ServerMessage[];
  /** The clicks the screen needs once the messages are drawn, such as opening a menu. */
  steps?: (page: Page) => Promise<void>;
  /** Selectors of the parts the client owns (design section 8), left out of both comparisons. */
  owned?: string[];
}
