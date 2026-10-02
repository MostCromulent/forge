// Dev mode: Forge's developer cheats for the host's own seat, as a submenu of the game menu, and the dialog that sets
// up a game state from text. Each cheat asks what it needs (which card, how much life) as the game's own prompts.

import { useEffect, useRef, useState } from 'preact/hooks';
import { OptionsDialog } from './options';
import type { Actions } from './actions';
import type { Model } from './model';
import type { DevAction } from './protocol';
import { t, type TextKey } from './text';

// Each group is listed alphabetically when drawn, so an entry can go anywhere in its group here
const GROUPS: [TextKey, [DevAction, TextKey][]][] = [
  ['lblCards', [
    ['addCardToHand', 'lblWebDevAddCardToHand'], ['addCardToBattlefield', 'lblWebDevAddCardToBattlefield'],
    ['addTokenToBattlefield', 'lblWebDevAddToken'], ['addCardToLibrary', 'lblWebDevAddCardToLibrary'],
    ['addCardToGraveyard', 'lblWebDevAddCardToGraveyard'], ['addCardToExile', 'lblWebDevAddCardToExile'],
    ['repeatLastAddition', 'lblWebDevRepeatLastAddition'], ['castASpell', 'lblWebDevCastSpell'],
    ['tutorForCard', 'lblWebDevTutor'],
  ]],
  ['lblRemove', [
    ['exileCardsFromHand', 'lblWebDevExileFromHand'], ['exileCardsFromBattlefield', 'lblWebDevExileFromBattlefield'],
    ['removeCardsFromGame', 'lblWebDevRemoveFromGame'],
  ]],
  ['lblPermanents', [
    ['addCountersToPermanent', 'lblWebDevAddCounters'], ['removeCountersFromPermanent', 'lblWebDevRemoveCounters'],
    ['tapPermanents', 'lblWebDevTap'], ['untapPermanents', 'lblWebDevUntap'],
  ]],
  ['lblGame', [
    ['generateMana', 'lblWebDevAddMana'], ['setPlayerLife', 'lblWebDevSetLife'], ['rollbackPhase', 'lblWebDevRollback'],
    ['winGame', 'lblWebDevWinGame'], ['dumpGameState', 'lblWebDevDumpState'],
  ]],
  ['lblPlanechase', [['riggedPlanarRoll', 'lblWebDevRiggedPlanarRoll'], ['planeswalkTo', 'lblWebDevPlaneswalkTo']]],
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
      <button type="button" role="menuitem" class="card-menu-item dev-back" onClick={back}>‹ {t('lblWebDevMode')}</button>
      <div class="dev-top">
        {toggle('unlimitedLands', t('lblWebDevUnlimitedLands'), model.devState?.unlimitedLands)}
        {toggle('viewAll', t('lblWebDevViewAll'), model.devState?.viewAll)}
        <button type="button" role="menuitem" class="card-menu-item" onClick={setUp}>{t('lblWebDevSetUpState')}</button>
      </div>
      <div class="dev-groups">
        {GROUPS.map(([heading, items]) => (
          <div key={heading} class="dev-group" role="group" aria-label={t(heading)}>
            <h5>{t(heading)}</h5>
            {items.map(([action, key]): [DevAction, string] => [action, t(key)]).sort((x, y) => x[1].localeCompare(y[1])).map(([action, label]) => (
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
    <OptionsDialog title={t('lblWebDevSetUpTitle')} kind="dev-setup" close={close} footer={<>
      <span class="hint">{t('lblWebDevSetUpHint')}</span>
      <button onClick={() => file.current?.click()}>{t('lblWebDevLoadFile')}</button>
      <button class="primary" disabled={!text.trim()} onClick={() => { actions.dev('setupGameState', text); close(); }}>{t('lblWebDevSetUp')}</button>
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
