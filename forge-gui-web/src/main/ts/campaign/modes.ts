// The campaign modes the page has. Each adds itself here, and register.tsx joins them into the campaigns' hooks

import type { FunctionComponent, VNode } from 'preact';
import type { Actions } from '../actions';
import type { CampaignDraft, EndingPanel } from '../campaigns';
import type { Model } from '../model';
import type { CampaignResult, Hello, Send } from '../protocol';
import type { TextKey } from '../text';

/** What a mode adds. The hooks with the names of CampaignHooks' do the same for this mode alone. */
export interface CampaignModeHooks {
  /** The name the server uses for the mode. */
  mode: string;
  fields(): Partial<Model>;
  hello(model: Model, msg: Hello): void;
  /** The messages that are only stored in the mode's field of the same name. */
  stored: string[];
  actions(send: Send, model: Model, schedule: () => void): object;
  Page: FunctionComponent<{ model: Model; actions: Actions }>;
  /** The start page's entry. art is the card picture behind it. */
  tile: { icon: VNode; art: string; name: TextKey; blurb: TextKey; resumeLabel(save: string): string; order: number };
  draft?(model: Model): CampaignDraft | null;
  /** What the result's buttons of the mode's own do, by their action. redraw shows the result's buttons again after a question. */
  ending?(model: Model, actions: Actions, result: CampaignResult, panel: EndingPanel, redraw: () => void): Record<string, () => void>;
}

const registered: CampaignModeHooks[] = [];

export function registerMode(m: CampaignModeHooks): void {
  registered.push(m);
}

export function modes(): CampaignModeHooks[] {
  return registered;
}
