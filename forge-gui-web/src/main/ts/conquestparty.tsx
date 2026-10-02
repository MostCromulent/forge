// A conquest's commanders and planeswalkers on one page: every one found, which leads and which is travelled as,
// and beside them the one being looked at, with its deck. A deck is edited in the deck editor, over the cards owned.

import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { artUrl } from './sleeves';
import { imageUrl } from './images';
import { FIVE_COLOURS, Pip, Pips } from './symbols';
import { Curve } from './deckhalf';
import { CardGroup } from './importer';
import { peekAt } from './deckfinder';
import { AnyOf, FilterBar, type FilterKind, OneOf } from './filters';
import type { Actions } from './actions';
import type { Model } from './model';
import type { ConquestCommanderRow, ConquestWalkerRow } from './protocol';
import { t } from './text';


/** What both lists are narrowed by: any of these colours, and for a commander the plane it comes from. A planeswalker has no origin. */
interface PartyFilter { colours: string[]; plane: string | null }
const NO_FILTER: PartyFilter = { colours: [], plane: null };

/** A commander's origin is its plane and region; the plane is what the filter offers. */
const planeOf = (origin: string): string => origin.split(' - ')[0];

export function Party({ model, actions }: { model: Model; actions: Actions }) {
  const party = model.conquestParty;
  const [chosen, setChosen] = useState<{ walker: boolean; name: string } | null>(null);
  const [typed, setTyped] = useState('');
  const [filter, setFilter] = useState<PartyFilter>(NO_FILTER);
  const [reading, setReading] = useState(false);
  const [peek, setPeek] = useState<{ image: string; left: number; top: number } | null>(null);
  useEffect(() => { actions.conquestParty(); }, []);

  const lead = party?.commanders.find(c => c.selected);
  const commander = chosen?.walker ? undefined : party?.commanders.find(c => c.name === chosen?.name) ?? lead;
  const walker = chosen?.walker ? party?.planeswalkers.find(w => w.name === chosen.name) : undefined;
  // The panel's curve and list are the deck's own details, asked for whenever the commander shown or its deck changes
  useEffect(() => { if (commander) actions.conquestViewDeck(commander.name); }, [commander?.name, commander?.deckSize]);
  useEffect(() => setReading(false), [commander?.name, walker?.name]);
  if (!party) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;

  const words = typed.trim().toLowerCase();
  const shown = (name: string, colors: string, origin?: string): boolean => name.toLowerCase().includes(words)
    && (!filter.colours.length || filter.colours.some(c => colors.includes(c)))
    && (!filter.plane || origin === undefined || planeOf(origin) === filter.plane);
  const planes = [...new Set(party.commanders.map(c => planeOf(c.origin)).filter(Boolean))].sort();
  const kinds: FilterKind<PartyFilter>[] = [
    {
      id: 'identity', group: t('lblCommanders'), label: t('lblWebFilterIdentity'),
      chip: f => (f.colours.length ? f.colours.join(' ') : null),
      clear: f => ({ ...f, colours: [] }),
      panel: (f, set, done) => <AnyOf options={FIVE_COLOURS.map(([c, name]) => [c, t(name)] as const)} value={f.colours}
        apply={colours => { set({ ...f, colours }); done(); }} />,
    },
    {
      id: 'origin', group: t('lblCommanders'), label: t('lblOrigin'),
      chip: f => f.plane,
      clear: f => ({ ...f, plane: null }),
      panel: (f, set, done) => <OneOf options={planes.map(p => [p, p] as const)} value={f.plane} pick={plane => { set({ ...f, plane }); done(); }} />,
    },
  ];
  const details = commander && model.deckDetails?.key === `conquest:${commander.name}` ? model.deckDetails : null;
  const commanders = party.commanders.filter(c => shown(c.name, c.colors, c.origin));
  const walkers = party.planeswalkers.filter(w => shown(w.name, w.colors));
  return (
    <div class="cq-party" onPointerOver={e => setPeek(peekAt(e, '.cq-party'))} onPointerLeave={() => setPeek(null)}>
      <div class="cq-party-list">
        <FilterBar kinds={kinds} filter={filter} set={setFilter} narrowed={!!typed || filter.colours.length > 0 || !!filter.plane}
          clearAll={() => { setTyped(''); setFilter(NO_FILTER); }}>
          <input class="find" type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
        </FilterBar>
        <section>
          <h4>{t('lblCommanders')}<span>{party.commanders.length}</span></h4>
          <div class="cq-cmd-grid">
            {commanders.map(c => <Plate key={c.name} name={c.name} image={c.image} colors={c.colors} selected={c.selected}
              pressed={c === commander} pick={() => setChosen({ walker: false, name: c.name })}
              lines={[`${c.origin} (${c.wins}W / ${c.losses}L)`, <DeckLine commander={c} />]} />)}
          </div>
        </section>
        <section>
          <h4>{t('lblPlaneswalkers')}<span>{party.planeswalkers.length}</span></h4>
          <div class="cq-cmd-grid">
            {walkers.map(w => <Plate key={w.name} name={w.name} image={w.image} colors={w.colors} selected={w.selected}
              pressed={w === walker} pick={() => setChosen({ walker: true, name: w.name })} lines={[t('lblPlaneswalker')]} />)}
          </div>
        </section>
      </div>
      {walker ? <WalkerPanel walker={walker} actions={actions} />
        : commander && (
          <aside class="cq-side">
            <div class="cq-cmd-detail">
              <img class="cq-card" alt={commander.name} src={imageUrl(commander.image)} />
              <h3>{commander.name}</h3>
              <div class="cq-chips">
                {commander.origin && <span class="cq-chip">{commander.origin}</span>}
                <span class="cq-chip">{commander.wins}W / {commander.losses}L</span>
                <span class="cq-chip"><DeckLine commander={commander} /></span>
              </div>
              {commander.problem && commander.deckSize > 0 && <p class="cq-warn">{commander.problem}</p>}
              {details && commander.deckSize > 0 && <Curve curve={details.stats.curve} creatures={details.stats.creatures} px={44} />}
              {reading && details && <div class="dk-cards">{details.main.map(g => <CardGroup key={g.heading} heading={g.heading} cards={g.cards} />)}</div>}
            </div>
            <div class="cq-foot">
              <div class="cq-two">
                <button aria-pressed={reading} disabled={!commander.deckSize} onClick={() => setReading(!reading)}>{t('lblViewDeck')}</button>
                <button onClick={() => actions.conquestEditDeck(commander.name)}>{t('btnEditDeck')}</button>
              </div>
              <button class="primary cq-big" disabled={commander.selected} onClick={() => actions.conquestLead(commander.name)}>
                {commander.selected ? t('lblSelected') : t('lblSelectCommander')}
              </button>
            </div>
          </aside>
        )}
      {peek && <div class="deck-peek" style={{ left: `${peek.left}px`, top: `${peek.top}px` }}><img alt="" src={imageUrl(peek.image)} /></div>}
    </div>
  );
}

/** A commander's deck in a few words: none, too small to play, or its size. */
function DeckLine({ commander }: { commander: ConquestCommanderRow }) {
  if (!commander.deckSize) return <>{t('lblNoDeck')}</>;
  return commander.problem
    ? <span class="cq-warn" title={commander.problem}>{t('lblWebConquestInvalidDeck')} · {commander.deckSize}</span>
    : <>{t('lblWebConquestCards', commander.deckSize)}</>;
}

function Plate({ name, image, colors, selected, pressed, pick, lines }: {
  name: string; image: string; colors: string; selected: boolean; pressed: boolean; pick: () => void; lines: ComponentChildren[];
}) {
  return (
    <button class="cq-cmd" aria-pressed={pressed} onClick={pick}>
      <div class="a" style={{ backgroundImage: `url("${artUrl(image)}")` }} />
      {selected && <span class="cq-chip brass tag">{t('lblSelected')}</span>}
      <div class="t">
        <b>{name}</b>
        <span class="l"><span class="pips">{colors ? <Pips colors={colors} /> : <Pip letter="C" />}</span>{lines[0]}</span>
        {lines[1] && <span class="l">{lines[1]}</span>}
      </div>
    </button>
  );
}

function WalkerPanel({ walker, actions }: { walker: ConquestWalkerRow; actions: Actions }) {
  return (
    <aside class="cq-side">
      <div class="cq-cmd-detail">
        <img class="cq-card" alt={walker.name} src={imageUrl(walker.image)} />
        <h3>{walker.name}</h3>
        <div class="cq-chips"><span class="cq-chip">{t('lblPlaneswalker')}</span></div>
      </div>
      <div class="cq-foot">
        <button class="primary cq-big" disabled={walker.selected} onClick={() => actions.conquestWalker(walker.name)}>
          {walker.selected ? t('lblSelected') : t('lblWebConquestSelectWalker')}
        </button>
      </div>
    </aside>
  );
}
