// Starting a conquest: the planes, a plane's commanders and a commander's planeswalkers are asked of the server as each answer is given

import { useEffect, useState } from 'preact/hooks';
import { artUrl } from '../sleeves';
import { imageUrl } from '../images';
import { peekAt } from '../deck/deckfinder';
import { Pip, Pips } from '../symbols';
import { StepForm, type Step, type Ticket } from '../setup';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { ConquestCardOption, ConquestOptions, ConquestPlaneOption } from '../protocol';
import { t } from '../text';

interface NewValue { plane?: string; commander?: string; planeswalker?: string }

export function NewConquest({ model, actions }: { model: Model; actions: Actions }) {
  const [value, setValue] = useState<NewValue>({});
  // The name is the last thing asked and is typed, not picked: Start takes it as it stands, with no step to close first
  const [name, setName] = useState('');
  // Making a conquest builds its first deck; until the map or an error answers, a second click would make another
  const [busy, setBusy] = useState(false);
  // An error from before the form was opened is the shelf's, and is not this form's to show
  const [sent, setSent] = useState(false);
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);
  useEffect(() => { actions.conquestOptions(value.plane, value.commander); }, [value.plane, value.commander]);
  useEffect(() => setBusy(false), [model.error]);
  const options = model.conquestOptions;
  if (!options) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  // A list answers the plane or the commander it was asked for, and an older answer is not shown for a newer choice
  const commanders = options.plane === value.plane ? options.commanders ?? null : null;
  const walkers = options.plane === value.plane && options.commander === value.commander ? options.planeswalkers ?? null : null;
  const ready = !!value.planeswalker && name.trim().length > 0;
  const start = () => {
    if (!ready || busy) return;
    setBusy(true);
    setSent(true);
    actions.conquestCreate({ name: name.trim(), plane: value.plane!, commander: value.commander!, planeswalker: value.planeswalker! });
  };
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
      id: 'name', label: t('lblHistoriiansWillRecallYourConquestAs'), short: t('lblConquestName'), hint: '', fields: [],
      answer: () => null,
      render: () => (
        <form class="rows" onSubmit={e => { e.preventDefault(); start(); }}>
          <input type="text" placeholder={t('lblConquestName')} maxLength={60} autoFocus value={name} onInput={e => setName(e.currentTarget.value)} />
        </form>
      ),
    },
  ];
  const ticket: Ticket<NewValue> = {
    kicker: t('lblWebConquestNew'),
    title: v => name.trim() || v.plane || t('lblPlanarConquest'),
    picture: v => <Picture value={v} options={options} />,
    rows: v => [[t('lblPlane'), v.plane ?? null], [t('lblCommander'), v.commander ?? null], [t('lblPlaneswalker'), v.planeswalker ?? null],
      [t('lblStartingShards'), options.startShards.toLocaleString('en-GB')]],
    note: '',
  };
  return (
    <div class="setup cq-new" onPointerOver={e => setPeek(peekAt(e, '.cq-new'))} onPointerLeave={() => setPeek(null)}>
      <StepForm steps={steps} value={value} onChange={setValue} ticket={ticket} action={t('lblStart')} busy={busy} ready={ready}
        problem={sent && model.error ? <span class="sentence taken">{model.error}</span> : null} submit={start} />
      {peek && <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}><img alt="" src={imageUrl(peek.image)} /></div>}
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
        <TicketCard card={commander} />
        {walker && <TicketCard card={walker} />}
      </div>
    );
  }
  const plane = options.planes.find(p => p.name === value.plane);
  return plane ? <div class="cq-ticket-art" style={{ backgroundImage: `url("${artUrl(plane.art)}")` }} /> : null;
}

/** A chosen card on the ticket. Its name stands in its place until its picture arrives, and when it has none. */
function TicketCard({ card }: { card: ConquestCardOption }) {
  return (
    <span class="cq-pic" key={card.name}>
      <span class="nm">{card.name}</span>
      <img alt="" src={imageUrl(card.image)} onError={e => { e.currentTarget.hidden = true; }} />
    </span>
  );
}

/** One of the choices shown, picked by chance. */
const anyOf = <T,>(shown: T[]) => shown[Math.floor(Math.random() * shown.length)];

/** The planes as tiles, narrowed by a search; the one pointed at says what it is underneath. */
function PlaneTiles({ planes, pick }: { planes: ConquestPlaneOption[]; pick: (plane: string) => void }) {
  const [looking, setLooking] = useState<ConquestPlaneOption | null>(null);
  const [typed, setTyped] = useState('');
  const words = typed.trim().toLowerCase();
  const shown = planes.filter(p => p.name.toLowerCase().includes(words));
  return (
    <div class="cq-pick">
      <input type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
      <div class="cq-plane-grid">
        {shown.length > 0 && (
          <button class="cq-plane random" onPointerEnter={() => setLooking(null)} onFocus={() => setLooking(null)} onClick={() => pick(anyOf(shown).name)}>
            <div class="a">?</div>
            <b>{t('lblRandom')}</b>
            <span>{t('lblWebConquestRandomPlane')}</span>
          </button>
        )}
        {shown.map(p => (
          <button key={p.name} class="cq-plane" onPointerEnter={() => setLooking(p)} onFocus={() => setLooking(p)} onClick={() => pick(p.name)}>
            <div class="a" style={{ backgroundImage: `url("${artUrl(p.art)}")` }} />
            <b>{p.name}</b>
            <span>{t('lblWebConquestEvents', p.events)}</span>
          </button>
        ))}
        {shown.length === 0 && <p class="hint">{t('lblWebSetupNothingMatches')}</p>}
      </div>
      <p class="cq-plane-desc">{looking?.description}</p>
    </div>
  );
}

/** Commanders or planeswalkers in one column, narrowed by a search; pointing at one shows its card. rows is null until the server has answered. */
function CardPick({ rows, pick }: { rows: ConquestCardOption[] | null; pick: (name: string) => void }) {
  const [typed, setTyped] = useState('');
  if (!rows) return <p class="hint">{t('lblWebConquestReading')}</p>;
  const words = typed.trim().toLowerCase();
  const shown = rows.filter(r => r.name.toLowerCase().includes(words));
  return (
    <div class="cq-pick">
      <input type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
      <div class="cq-pick-list">
        {shown.length > 0 && (
          <button class="cq-pick-row random" onClick={() => pick(anyOf(shown).name)}>
            <i>?</i>
            <b>{t('lblRandom')}</b>
          </button>
        )}
        {shown.map(r => (
          <button key={r.name} class="cq-pick-row" data-image={r.image} onClick={() => pick(r.name)}>
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
