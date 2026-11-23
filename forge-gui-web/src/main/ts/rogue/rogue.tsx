import { CampaignShell } from '../campaign/campaign';
import type { Actions } from '../actions';
import type { Model } from '../model';

/** Rogue Commander's page. The mode has no shelf of saved games, so the shell is always open. */
export function RogueHub({ model, actions }: { model: Model; actions: Actions }) {
  return <CampaignShell model={model} actions={actions} icons={[]} always class="rg-shell" open={() => null} />;
}
