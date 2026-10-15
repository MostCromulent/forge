// Where the campaign modes plug into the page. campaign/register.ts fills it in, so a build can leave the campaigns out

import type { FunctionComponent, VNode } from 'preact';
import type { Actions, PageActions } from './actions';
import type { Model } from './model';
import type { DraftState, Hello, Send, ServerMessage } from './protocol';
import type { TextKey } from './text';

/** How a campaign's game ends: what the result says and the buttons it offers. */
export interface CampaignEnding {
  /** Tells one result from another, so a panel drawn before it arrived is drawn again. Null while the result is still to come. */
  key: string | null;
  line: string | null;
  buttons(panel: EndingPanel): void;
}

/** The result panel's foot, where an ending puts its buttons. */
export interface EndingPanel {
  add(label: string, primary: boolean, onClick: () => void): void;
  /** A line above the buttons, such as a question they answer. */
  say(text: string): void;
  /** Empties the foot, for another set of buttons. */
  clear(): void;
  /** Shows the battlefield in place of the result. */
  view(): void;
}

/** A draft played for a campaign, which ends its own way and says what leaving it costs. */
export interface CampaignDraft {
  Done: FunctionComponent<{ state: DraftState; actions: Actions }>;
  leaveText: TextKey;
}

export interface CampaignHooks {
  /** The model fields the campaigns add to the page's, at their starting values. */
  fields(): Partial<Model>;
  /** Every hello: a campaign clears what belongs to a page that is no longer showing. */
  hello(model: Model, msg: Hello): void;
  /** Stores a message of the campaigns'. Answers false for any other. */
  message(model: Model, msg: ServerMessage): boolean;
  /** The campaigns' actions, added to the page's. */
  actions(send: Send, model: Model, schedule: () => void): Omit<Actions, keyof PageActions>;
  /** The open mode's pages. */
  Page: FunctionComponent<{ model: Model; actions: Actions }>;
  /** The start page's entries for the campaigns. */
  Menu: FunctionComponent<{ model: Model; actions: Actions }>;
  /** The draft running for a campaign, or null when the draft is the Limited pages'. */
  draft(model: Model): CampaignDraft | null;
  /** How the game just ended ends for a campaign, or null when its match is not a campaign's. */
  ending(model: Model, actions: Actions): CampaignEnding | null;
}

let hooks: CampaignHooks | null = null;

export function registerCampaigns(h: CampaignHooks): void {
  hooks = h;
}

export function campaigns(): CampaignHooks | null {
  return hooks;
}

/** The start page's campaign entries, or nothing in a build without campaigns. */
export function CampaignMenu(props: { model: Model; actions: Actions }): VNode | null {
  const Menu = hooks?.Menu;
  return Menu ? <Menu {...props} /> : null;
}
