// Starting a conquest: four questions, with what it will be beside them. The planes, a plane's commanders and a
// commander's planeswalkers are the server's to say, asked for as each answer is given.

import { useEffect, useState } from 'preact/hooks';
import { artUrl } from './sleeves';
import { imageUrl } from './images';
import { Pip, Pips } from './symbols';
import { StepForm, TextStep, type Step, type Ticket } from './setup';
import type { Actions } from './actions';
import type { Model } from './model';
import type { ConquestCardOption, ConquestOptions, ConquestPlaneOption } from './protocol';
import { t } from './text';

interface NewValue { plane?: string; commander?: string; planeswalker?: string; name?: string }

export function NewConquest({ model, actions }: { model: Model; actions: Actions }) {
  const [value, setValue] = useState<NewValue>({});
  // Making a conquest builds its first deck; until the map or an error answers, a second click would make another
  const [busy, setBusy] = useState(false);
  // An error from before the form was opened is the shelf's, and is not this form's to show
  const [sent, setSent] = useState(false);
  useEffect(() => { actions.conquestOptions(value.plane, value.commander); }, [value.plane, value.commander]);
  useEffect(() => setBusy(false), [model.error]);
  const options = model.conquestOptions;
  if (!options) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  // A list answers the plane or the commander it was asked for, and an older answer is not shown for a newer choice
  const commanders = options.plane === value.plane ? options.commanders ?? null : null;
  const walkers = options.plane === value.plane && options.commander === value.commander ? options.planeswalkers ?? null : null;
  const steps: Step<NewValue>[] = [
    {
      id: 'plane', label: t('lblWebConquestStartingPlane'), short: t('lblPlane'), hint: '', fields: ['plane'],
      answer: v => v.plane ?? null,
      render: (_, set) => <PlaneTiles planes={options.planes} pick={plane => set({ plane })} />,
    },
    {
      id: 'commander', label: t('lblSelectStartingCommander'), short: t('lblCommander'), hint: '', fields: ['commander'],
      answer: v => v.commander ?? null,
      render: (_, set) => <CardPick rows={commanders} pick={commander => set({ commander })} />,
    },
    {
      id: 'planeswalker', label: t('lblSelectStartingPlaneswalker'), short: t('lblPlaneswalker'), hint: '', fields: ['planeswalker'],
      answer: v => v.planeswalker ?? null,
      render: (_, set) => <CardPick rows={walkers} pick={planeswalker => set({ planeswalker })} />,
    },
    {
      id: 'name', label: t('lblHistoriiansWillRecallYourConquestAs'), short: t('lblConquestName'), hint: '', fields: ['name'],
      answer: v => v.name ?? null,
      render: (v, set) => <TextStep placeholder={t('lblConquestName')} initial={v.name ?? ''} done={name => set({ name })} />,
    },
  ];
  const ticket: Ticket<NewValue> = {
    kicker: t('lblWebConquestNew'),
    title: v => v.name ?? v.plane ?? t('lblPlanarConquest'),
    picture: v => <Picture value={v} options={options} />,
    rows: v => [[t('lblPlane'), v.plane ?? null], [t('lblCommander'), v.commander ?? null], [t('lblPlaneswalker'), v.planeswalker ?? null],
      [t('lblStartingShards'), options.startShards.toLocaleString('en-GB')]],
    note: '',
  };
  return (
    <div class="setup cq-new">
      <StepForm steps={steps} value={value} onChange={setValue} ticket={ticket} action={t('lblStart')} busy={busy}
        problem={sent && model.error ? <span class="sentence taken">{model.error}</span> : null}
        submit={() => {
          setBusy(true);
          setSent(true);
          actions.conquestCreate({ name: value.name!, plane: value.plane!, commander: value.commander!, planeswalker: value.planeswalker! });
        }} />
    </div>
  );
}

/** What the ticket shows of the choices so far: the plane's art, then the commander's card, then both cards. */
function Picture({ value, options }: { value: NewValue; options: ConquestOptions }) {
  const commander = options.commanders?.find(c => c.name === value.commander);
  const walker = options.planeswalkers?.find(c => c.name === value.planeswalker);
  if (commander) {
    return (
      <div class="cq-ticket-cards">
        <img alt={commander.name} src={imageUrl(commander.image)} />
        {walker && <img alt={walker.name} src={imageUrl(walker.image)} />}
      </div>
    );
  }
  const plane = options.planes.find(p => p.name === value.plane);
  return plane ? <div class="cq-ticket-art" style={{ backgroundImage: `url("${artUrl(plane.art)}")` }} /> : null;
}

/** The planes as tiles; the one pointed at says what it is underneath. */
function PlaneTiles({ planes, pick }: { planes: ConquestPlaneOption[]; pick: (plane: string) => void }) {
  const [looking, setLooking] = useState<ConquestPlaneOption | null>(null);
  return (
    <>
      <div class="cq-plane-grid">
        {planes.map(p => (
          <button key={p.name} class="cq-plane" onPointerEnter={() => setLooking(p)} onFocus={() => setLooking(p)} onClick={() => pick(p.name)}>
            <div class="a" style={{ backgroundImage: `url("${artUrl(p.art)}")` }} />
            <b>{p.name}</b>
            <span>{t('lblWebConquestEvents', p.events)}</span>
          </button>
        ))}
      </div>
      <p class="cq-plane-desc">{looking?.description}</p>
    </>
  );
}

/** Commanders or planeswalkers in one column, narrowed by a search. rows is null until the server has answered. */
function CardPick({ rows, pick }: { rows: ConquestCardOption[] | null; pick: (name: string) => void }) {
  const [typed, setTyped] = useState('');
  if (!rows) return <p class="hint">{t('lblWebConquestReading')}</p>;
  const words = typed.trim().toLowerCase();
  const shown = rows.filter(r => r.name.toLowerCase().includes(words));
  return (
    <div class="cq-pick">
      <input type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
      <div class="cq-pick-list">
        {shown.map(r => (
          <button key={r.name} class="cq-pick-row" onClick={() => pick(r.name)}>
            <i style={{ backgroundImage: `url("${artUrl(r.image)}")` }} />
            <b>{r.name}</b>
            <span class="pips">{r.colors ? <Pips colors={r.colors} /> : <Pip letter="C" />}</span>
            {r.region && <span class="r">{r.region}</span>}
          </button>
        ))}
        {shown.length === 0 && <p class="hint">{t('lblWebSetupNothingMatches')}</p>}
      </div>
    </div>
  );
}
