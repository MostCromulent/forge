// Adventure mode: the saved games, the new-game form, and an open save's world map

import { useEffect, useState } from 'preact/hooks';
import { Shelf as Cards } from '../shelf';
import { StepForm, type Step, type Ticket } from '../stepform';
import { CampaignShelf, CampaignShell } from '../campaign/campaign';
import { World } from './world';
import { DialogBox, Hud, TownNotice } from './hud';
import { Inventory } from './inventory';
import { Reveal } from '../campaign/reward';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { AdventureModeOption, AdventureSave } from '../protocol';
import { t, shortDay } from '../text';

/** Text with mobile's markup taken out: colour and icon tags, and the bar a save's name puts before its place. */
export const plain = (text: string) => text.replace(/\[[^\]]*\]/g, '').replace(/│/g, ' · ').replace(/[ \t]+/g, ' ').trim();

export function Adventure({ model, actions }: { model: Model; actions: Actions }) {
  const saves = model.adventureSaves;
  return (
    <CampaignShell model={model} actions={actions} icons={[]}
      shelf={(creating, setCreating) => (
        <CampaignShelf model={model} actions={actions} creating={creating} setCreating={setCreating}
          words={{ mode: t('lblAdventure'), new: t('lblWebAdventureNew'), yours: t('lblWebAdventureYours'), reading: t('lblWebAdventureReading') }}
          form={<NewAdventure model={model} actions={actions} />}
          saves={saves && <Saves saves={saves.saves} current={saves.current ?? null} actions={actions} create={() => setCreating(true)} />} />
      )}
      open={() => <Open model={model} actions={actions} />} />
  );
}

/** Which slot a save is in, as mobile's save screen names them. */
const slotName = (slot: number) => (slot === -1 ? t('lblWebAdventureAutoSave') : slot === -2 ? t('lblWebAdventureQuickSave') : t('lblWebAdventureSlot', slot));

function Saves({ saves, current, actions, create }: { saves: AdventureSave[]; current: string | null; actions: Actions; create: () => void }) {
  const rows = saves.map(s => ({ ...s, name: plain(s.name) }));
  return (
    <Cards rows={rows} createLast cls={s => (s.file === current ? 'ev qu-save current' : 'ev qu-save')}
      create={{ title: t('lblWebAdventureNew'), line: t('lblWebAdventureNewLine'), go: create }}
      remove={{ item: t('lblDelete'), ask: s => t('lblAreYouSuerDeleteConquest', s.name), keep: t('lblCancel'), go: s => actions.campaignDelete(s.file) }}
      sub={s => <span class="sub">{slotName(s.slot)}</span>}
      mid={() => null}
      foot={s => <>
        <button class="primary" onClick={() => actions.campaignLoad(s.file)}>{t('lblPlay')}</button>
        {s.saved && <span class="ev-ask">{t('lblWebLimitedSaved', shortDay(s.saved))}</span>}
      </>} />
  );
}

interface NewValue {
  difficulty?: number;
  mode?: string;
  choice?: number;
  race?: number;
  female?: boolean;
  edition?: number;
}

export function NewAdventure({ model, actions }: { model: Model; actions: Actions }) {
  const [value, setValue] = useState<NewValue>({});
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  useEffect(() => { actions.adventureOptions(); }, []);
  useEffect(() => setBusy(false), [model.error]);
  const options = model.adventureOptions;
  if (!options) return <p class="muted pools-wait">{t('lblWebAdventureReading')}</p>;
  const mode: AdventureModeOption | undefined = options.modes.find(m => m.mode === value.mode);
  // Mobile offers a starter edition only for Standard decks
  const wantsEdition = (v: NewValue) => v.mode === 'Standard' && options.editions.length > 0;
  const ready = value.choice !== undefined && value.race !== undefined && value.female !== undefined
    && (!wantsEdition(value) || value.edition !== undefined) && name.trim().length > 0;
  const start = () => {
    if (!ready || busy || !mode) return;
    setBusy(true);
    setSent(true);
    actions.adventureCreate({
      name: name.trim(), female: !!value.female, race: value.race!, avatar: 0,
      colors: mode.colorIds ? mode.colorIds[value.choice!] : '', difficulty: value.difficulty ?? options.defaultDifficulty,
      mode: mode.mode, customDeck: value.choice!, edition: undefined, seed: 0,
    });
  };
  const steps: Step<NewValue>[] = [
    {
      id: 'difficulty', label: t('lblDifficulty'), hint: '', fields: ['difficulty'],
      answer: v => (v.difficulty === undefined ? null : options.difficulties[v.difficulty]),
      render: (_, set) => (
        <div class="tiles">
          {options.difficulties.map((d, i) => <button key={d} class="tile-choice" onClick={() => set({ difficulty: i })}><b>{d}</b></button>)}
        </div>
      ),
    },
    {
      id: 'mode', label: t('lblMode').replace(':', ''), hint: '', fields: ['mode'],
      answer: v => { const m = options.modes.find(o => o.mode === v.mode); return m ? plain(m.label) : null; },
      render: (_, set) => (
        <div class="tiles">
          {options.modes.map(m => <button key={m.mode} class="tile-choice" onClick={() => set({ mode: m.mode, choice: undefined })}><b>{plain(m.label)}</b></button>)}
        </div>
      ),
    },
    {
      id: 'choice', label: mode?.choiceLabel ?? t('lblDeck'), hint: '', fields: ['choice'], applies: v => v.mode !== undefined,
      answer: v => (v.choice === undefined || !mode ? null : plain(mode.choices[v.choice])),
      render: (_, set) => (
        <div class="tiles">
          {(mode?.choices ?? []).map((c, i) => <button key={c} class="tile-choice" onClick={() => set({ choice: i })}><b>{plain(c)}</b></button>)}
        </div>
      ),
    },
    {
      id: 'edition', label: t('lblWebAdventureEdition'), hint: '', fields: ['edition'], applies: wantsEdition,
      answer: v => (v.edition === undefined ? null : options.editions[v.edition]),
      render: (_, set) => (
        <div class="tiles">
          {options.editions.map((e, i) => <button key={e} class="tile-choice" onClick={() => set({ edition: i })}><b>{e}</b></button>)}
        </div>
      ),
    },
    {
      id: 'race', label: t('lblRace'), hint: '', fields: ['race', 'female'],
      answer: v => (v.race === undefined ? null : `${options.races[v.race]} · ${t(v.female ? 'lblFemale' : 'lblMale')}`),
      render: (v, set) => (
        <div class="qu-dist">
          <div class="tiles">
            <button class="tile-choice" aria-pressed={v.female === false} onClick={() => setValue({ ...v, female: false })}><b>{t('lblMale')}</b></button>
            <button class="tile-choice" aria-pressed={v.female === true} onClick={() => setValue({ ...v, female: true })}><b>{t('lblFemale')}</b></button>
          </div>
          <div class="tiles">
            {options.races.map((r, i) => <button key={r} class="tile-choice" onClick={() => set({ race: i, female: !!v.female })}><b>{r}</b></button>)}
          </div>
        </div>
      ),
    },
    {
      id: 'name', label: t('lblName'), hint: '', fields: [],
      answer: () => null,
      render: () => (
        <form class="rows" onSubmit={e => { e.preventDefault(); start(); }}>
          <input type="text" placeholder={t('lblName')} aria-label={t('lblName')} maxLength={40} autoFocus value={name} onInput={e => setName(e.currentTarget.value)} />
        </form>
      ),
    },
  ];
  const ticket: Ticket<NewValue> = {
    kicker: t('lblWebAdventureNew'),
    title: () => name.trim() || t('lblAdventure'),
    rows: v => [
      [t('lblDifficulty'), options.difficulties[v.difficulty ?? options.defaultDifficulty]],
      [t('lblMode').replace(':', ''), mode ? plain(mode.label) : null],
      [mode?.choiceLabel ?? t('lblDeck'), v.choice === undefined || !mode ? null : plain(mode.choices[v.choice])],
      [t('lblRace'), v.race === undefined ? null : options.races[v.race]],
    ],
    note: t('lblWebAdventureTicketNote'),
  };
  return (
    <div class="setup qu-new-quest">
      <StepForm steps={steps} value={value} onChange={setValue} ticket={ticket} action={t('lblStart')} busy={busy} ready={ready}
        problem={sent && model.error ? <span class="sentence taken">{model.error}</span> : null} submit={start} />
    </div>
  );
}

/** How long the encounter shows before the battle starts, as WorldStage pauses for the attack animations. */
const ENCOUNTER_MS = 900;

/** An open save: its world map, with the HUD over it. The map holds still while a dialog, a town or an encounter is shown. */
function Open({ model, actions }: { model: Model; actions: Actions }) {
  const state = model.adventureState;
  const encounter = model.adventureEncounter;
  const [inventory, setInventory] = useState(false);
  useEffect(() => {
    if (!encounter) return;
    const timer = setTimeout(() => actions.adventureFight(), ENCOUNTER_MS);
    return () => clearTimeout(timer);
  }, [encounter]);
  const busy = !!model.adventurePlaceholder || !!model.adventureDialog || !!encounter || !!model.reward;
  // KeyBinding's Inventory and Deck keys. The inventory closes itself on its own key
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (busy || inventory || (e.target as HTMLElement)?.closest('input, textarea')) return;
      if (e.code === 'KeyI') { e.preventDefault(); setInventory(true); }
      if (e.code === 'KeyE' && state) { e.preventDefault(); actions.adventureEditDeck(model.adventureInventory?.selectedDeck ?? 0); }
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [busy, inventory, state, model.adventureInventory]);
  if (!state) return <p class="muted pools-wait">{t('lblWebAdventureReading')}</p>;
  const tick = model.adventureTick;
  const hud = tick?.hud ?? state.hud;
  const frozen = busy || inventory;
  return (
    <div class="adv-page">
      <World state={state} enemies={tick?.enemies ?? state.enemies} speed={tick?.speed ?? state.speed} frozen={frozen}
        navAngle={hud.navAngle ?? null} encounter={encounter?.enemyId ?? null} actions={actions} />
      <Hud hud={hud} actions={actions} openInventory={() => setInventory(true)} />
      {model.adventureDialog ? <DialogBox dialog={model.adventureDialog} actions={actions} />
        : model.adventurePlaceholder ? <TownNotice name={model.adventurePlaceholder.name} actions={actions} /> : null}
      {inventory && <Inventory page={model.adventureInventory} actions={actions} close={() => setInventory(false)} />}
      {model.reward && <Reveal key={model.reward.steps.length} reward={model.reward} onOwed={() => {}} done={() => actions.rewardClaim()} />}
    </div>
  );
}
