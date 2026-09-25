// Dev mode: Forge's developer cheats for the host's own seat, as a submenu of the game menu, and the dialog that sets
// up a game state from text. Each cheat asks what it needs (which card, how much life) as the game's own prompts.

import { useEffect, useRef, useState } from 'preact/hooks';
import { OptionsDialog } from './options';
import type { Actions } from './actions';
import type { Model } from './model';
import type { DevAction } from './protocol';

// Each group is listed alphabetically when drawn, so an entry can go anywhere in its group here
const GROUPS: [string, [DevAction, string][]][] = [
  ['Cards', [
    ['addCardToHand', 'Add a card to hand'], ['addCardToBattlefield', 'Put a card onto the battlefield'],
    ['addTokenToBattlefield', 'Create a token'], ['addCardToLibrary', 'Add a card to a library'],
    ['addCardToGraveyard', 'Add a card to a graveyard'], ['addCardToExile', 'Add a card to exile'],
    ['repeatLastAddition', 'Repeat the last addition'], ['castASpell', 'Cast a spell or play a land'],
    ['tutorForCard', 'Tutor a card from the library'],
  ]],
  ['Remove', [
    ['exileCardsFromHand', 'Exile cards from hand'], ['exileCardsFromBattlefield', 'Exile cards from the battlefield'],
    ['removeCardsFromGame', 'Remove cards from the game'],
  ]],
  ['Permanents', [
    ['addCountersToPermanent', 'Add counters'], ['removeCountersFromPermanent', 'Remove counters'],
    ['tapPermanents', 'Tap permanents'], ['untapPermanents', 'Untap permanents'],
  ]],
  ['Game', [
    ['generateMana', 'Add mana'], ['setPlayerLife', 'Set a player\'s life'], ['rollbackPhase', 'Go back to the start of the phase'],
    ['winGame', 'Win the game'], ['dumpGameState', 'Save the game state'],
  ]],
  ['Planechase', [['riggedPlanarRoll', 'Roll the planar die to a chosen face'], ['planeswalkTo', 'Planeswalk to a chosen plane']]],
];

/** The cheats, in place of the game menu's own items. */
export function DevItems({ model, actions, back, close, setUp }: {
  model: Model; actions: Actions; back: () => void; close: () => void; setUp: () => void;
}) {
  useEffect(() => {
    actions.dev('state');
  }, []);
  const run = (action: DevAction) => {
    actions.dev(action);
    close();
  };
  const toggle = (action: DevAction, label: string, on: boolean | undefined) => (
    <button type="button" role="menuitemcheckbox" aria-checked={!!on} class="card-menu-item" onClick={() => actions.dev(action)}>
      {on ? '✓ ' : ''}{label}
    </button>
  );
  return (
    <>
      <button type="button" role="menuitem" class="card-menu-item dev-back" onClick={back}>‹ Dev mode</button>
      <div class="dev-top">
        {toggle('unlimitedLands', 'Play any number of lands', model.devState?.unlimitedLands)}
        {toggle('viewAll', 'See every card', model.devState?.viewAll)}
        <button type="button" role="menuitem" class="card-menu-item" onClick={setUp}>Set up a game state…</button>
      </div>
      <div class="dev-groups">
        {GROUPS.map(([heading, items]) => (
          <div key={heading} class="dev-group" role="group" aria-label={heading}>
            <h5>{heading}</h5>
            {[...items].sort((a, b) => a[1].localeCompare(b[1])).map(([action, label]) => (
              <button key={action} type="button" role="menuitem" class="card-menu-item" onClick={() => run(action)}>{label}</button>
            ))}
          </div>
        ))}
      </div>
    </>
  );
}

/** A game state to set up, pasted or read from a file, as desktop's dev mode reads one from its games folder. */
export function DevSetupDialog({ actions, close }: { actions: Actions; close: () => void }) {
  const [text, setText] = useState('');
  const file = useRef<HTMLInputElement>(null);
  return (
    <OptionsDialog title="Set up a game state" kind="dev-setup" close={close} footer={<>
      <span class="hint">The same text desktop Forge saves as a game state.</span>
      <button onClick={() => file.current?.click()}>Load a file</button>
      <button class="primary" disabled={!text.trim()} onClick={() => { actions.dev('setupGameState', text); close(); }}>Set up</button>
    </>}>
      <textarea class="dev-state" spellcheck={false} rows={14} value={text} placeholder={'activeplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\nhumanbattlefield=Grizzly Bears;Forest'}
        onInput={e => setText(e.currentTarget.value)} />
      <input ref={file} type="file" accept=".txt,text/plain" hidden onChange={async e => {
        const input = e.currentTarget;
        const chosen = input.files?.[0];
        if (chosen) setText(await chosen.text());
        input.value = '';
      }} />
    </OptionsDialog>
  );
}
