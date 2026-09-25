// Setting up a Limited event: one question at a time, each answered one folding into a line that can be reopened, and
// the questions still to come listed faintly so the length of the form shows from the start. Desktop asks the same
// questions as a chain of dialogs; which ones are asked depends on the answers before them, here as there.

import type { JSX } from 'preact';
import { useState } from 'preact/hooks';
import type { LimitedOptions, SealedBlock } from './protocol';

export interface Step<V> {
  id: string;
  label: string;
  /** What the question is about, shown while it is still to come. */
  hint: string;
  /** The answers this step owns, cleared when it or an earlier step is answered again. */
  fields: (keyof V)[];
  applies?(v: V): boolean;
  /** The answer as the folded line shows it, or null while unanswered. */
  answer(v: V): string | null;
  render(v: V, set: (patch: Partial<V>) => void): JSX.Element;
}

const applies = <V,>(step: Step<V>, v: V) => step.applies?.(v) ?? true;

/** The first step that applies and has no answer, or null when the form is complete. */
export function openStep<V>(steps: Step<V>[], v: V): string | null {
  return steps.find(s => applies(s, v) && s.answer(v) === null)?.id ?? null;
}

/** Answers a step: every later step's answers are dropped, since they may no longer fit, then the patch applies. */
export function choose<V>(steps: Step<V>[], v: V, id: string, patch: Partial<V>): V {
  const next = { ...v };
  const at = steps.findIndex(s => s.id === id);
  for (const step of steps.slice(at + 1)) {
    for (const field of step.fields) delete next[field];
  }
  return { ...next, ...patch };
}

/** Reopens a step: its answer and every later one are dropped. */
function reopen<V>(steps: Step<V>[], v: V, id: string): V {
  const next = { ...v };
  for (const step of steps.slice(steps.findIndex(s => s.id === id))) {
    for (const field of step.fields) delete next[field];
  }
  return next;
}

export function StepForm<V>({ title, steps, value, onChange, sentence, action, submit, problem, busy }: {
  title: string; steps: Step<V>[]; value: V; onChange: (v: V) => void; sentence: (v: V) => string; action: string;
  submit: () => void; problem?: JSX.Element | null; busy?: boolean;
}) {
  const open = openStep(steps, value);
  const shown = steps.filter(s => applies(s, value));
  const openAt = open === null ? shown.length : shown.findIndex(s => s.id === open);
  return (
    <div class="wiz">
      <h3 class="wiz-title">{title}</h3>
      {shown.map((step, i) => {
        if (i < openAt) {
          return (
            <div key={step.id} class="stp done">
              <span class="num" aria-hidden="true">✓</span>
              <span class="lab">{step.label}</span>
              <span class="val">{step.answer(value)}</span>
              <button class="link" onClick={() => onChange(reopen(steps, value, step.id))}>Edit</button>
            </div>
          );
        }
        if (i === openAt) {
          return (
            <div key={step.id} class="stp-open">
              <div class="q"><span class="num">{i + 1}</span><b>{step.label}</b></div>
              {step.render(value, patch => onChange(choose(steps, value, step.id, patch)))}
            </div>
          );
        }
        return (
          <div key={step.id} class="stp todo">
            <span class="num">{i + 1}</span><span class="lab">{step.label}</span><span class="val">{step.hint}</span>
          </div>
        );
      })}
      {open === null && (
        <div class="wfoot">
          {problem ?? <span class="sentence">{sentence(value)}</span>}
          <button class="primary" disabled={busy} onClick={submit}>{busy ? 'Opening…' : action}</button>
        </div>
      )}
    </div>
  );
}

// ---- Sealed ------------------------------------------------------------------------------------------------------

export interface SealedValue {
  product?: string;
  block?: string;
  combo?: string;
  edition?: string;
  template?: string;
  cubeId?: string;
  packs?: number;
  name?: string;
}

/** The sealed products desktop offers, with a line each; LimitedPoolType's names are the ids. */
const PRODUCTS: [string, string, string][] = [
  ['Full', 'Full card pool', 'Booster packs drawn from every card in Forge.'],
  ['Block', 'Block', 'Packs from a block or a set, in the mix desktop offers.'],
  ['FantasyBlock', 'Fantasy block', 'A made-up block from Forge\'s list.'],
  ['Prerelease', 'Prerelease', 'An edition\'s prerelease kit: its packs and a promo.'],
  ['Custom', 'Custom pool', 'A sealed pool file saved in Forge.'],
  ['Import', 'CubeCobra', 'Any cube by its CubeCobra link or ID.'],
];

const productName = (id?: string) => PRODUCTS.find(p => p[0] === id)?.[1] ?? '';
const isBlock = (v: SealedValue) => v.product === 'Block' || v.product === 'FantasyBlock';
const blocksFor = (options: LimitedOptions, product?: string) => product === 'FantasyBlock' ? options.fantasyBlocks : options.blocks;
const hasPackCount = (v: SealedValue) => v.product === 'Full' || v.product === 'Custom' || v.product === 'Import';

function blockOf(options: LimitedOptions, v: SealedValue): SealedBlock | undefined {
  return blocksFor(options, v.product).find(b => b.name === v.block);
}

/** Choosing a block: a block with one set combination has it filled in, since there is nothing to ask. */
export function sealedBlockChoice(options: LimitedOptions, product: string, name: string): Partial<SealedValue> {
  const combos = blocksFor(options, product).find(b => b.name === name)?.combos ?? [];
  return combos.length === 1 ? { block: name, combo: combos[0] } : { block: name };
}

export function sealedSteps(options: LimitedOptions): Step<SealedValue>[] {
  return [
    {
      id: 'product', label: 'Product', hint: 'What the packs are', fields: ['product'],
      answer: v => v.product ? productName(v.product) : null,
      render: (_, set) => (
        <div class="tiles">
          {PRODUCTS.map(([id, name, line]) => (
            <button key={id} class="tile-choice" onClick={() => set({ product: id })}><b>{name}</b><span>{line}</span></button>
          ))}
        </div>
      ),
    },
    {
      id: 'block', label: 'Block', hint: 'Which block', fields: ['block'], applies: isBlock,
      answer: v => v.block ?? null,
      render: (v, set) => <Pick items={blocksFor(options, v.product).map(b => [b.name, b.name])} placeholder="Find a block"
        pick={name => set(sealedBlockChoice(options, v.product!, name))} />,
    },
    {
      id: 'combo', label: 'Packs', hint: 'Which sets', fields: ['combo'],
      applies: v => isBlock(v) && !!v.block && (blockOf(options, v)?.combos.length ?? 0) > 1,
      answer: v => v.combo ?? null,
      render: (v, set) => (
        <div class="tiles">
          {blockOf(options, v)?.combos.map(c => <button key={c} class="tile-choice" onClick={() => set({ combo: c })}><b>{c}</b></button>)}
        </div>
      ),
    },
    {
      id: 'edition', label: 'Edition', hint: 'Which prerelease', fields: ['edition'], applies: v => v.product === 'Prerelease',
      answer: v => options.prereleases.find(e => e.code === v.edition)?.name ?? null,
      render: (_, set) => <Pick items={options.prereleases.map(e => [e.code, e.name])} placeholder="Find an edition"
        pick={code => set({ edition: code })} />,
    },
    {
      id: 'template', label: 'Pool', hint: 'Which saved pool', fields: ['template'], applies: v => v.product === 'Custom',
      answer: v => v.template ?? null,
      render: (_, set) => options.templates.length
        ? <Pick items={options.templates.map(t => [t, t])} placeholder="Find a pool" pick={t => set({ template: t })} />
        : <p class="hint">Forge has no custom sealed pools saved.</p>,
    },
    {
      id: 'cubeId', label: 'Cube', hint: 'A CubeCobra link or ID', fields: ['cubeId'], applies: v => v.product === 'Import',
      answer: v => v.cubeId ?? null,
      render: (_, set) => <TextStep placeholder="CubeCobra link or ID" initial="" done={id => set({ cubeId: id })} />,
    },
    {
      id: 'packs', label: 'Packs', hint: 'How many to open', fields: ['packs'], applies: hasPackCount,
      answer: v => v.packs === undefined ? null : `${v.packs} packs`,
      render: (v, set) => <PackCount extra={v.product === 'Import'} done={n => set({ packs: n })} />,
    },
    {
      id: 'name', label: 'Pool name', hint: 'What to save it as', fields: ['name'],
      answer: v => v.name ?? null,
      render: (v, set) => <TextStep placeholder="Pool name" initial={defaultName(v)} done={name => set({ name })} />,
    },
  ];
}

function defaultName(v: SealedValue): string {
  const what = v.product === 'Prerelease' ? 'Prerelease' : isBlock(v) ? v.block ?? '' : productName(v.product);
  return `${what} ${new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}`;
}

export function sealedSentence(options: LimitedOptions, v: SealedValue): string {
  switch (v.product) {
    case 'Full': return `${v.packs} booster packs from the full card pool.`;
    case 'Prerelease': return `A ${options.prereleases.find(e => e.code === v.edition)?.name} prerelease pool.`;
    case 'Custom': return `${v.packs} packs of ${v.template}.`;
    case 'Import': return `${(v.packs ?? 0) + 1} packs of the CubeCobra cube ${v.cubeId}.`;
    default: return `${v.block}: ${v.combo}.`;
  }
}

// ---- Draft -------------------------------------------------------------------------------------------------------

export interface DraftValue {
  product?: string;
  block?: string;
  /** A preset combination, or a single set's code. */
  combo?: string;
  /** One set per pack, for a block with no presets. */
  packs?: string[];
  cube?: string;
  theme?: string;
  cubeId?: string;
  /** An online draft's table rules: pod seats (0 for the set's own), a DoublePick name, and the timer and grace in seconds. */
  podSize?: number;
  pickRule?: string;
  timer?: number;
  grace?: number;
}

/** An online draft's table: how many players are seated, which the pod can never be smaller than. */
export interface DraftTable {
  seated: number;
}

/** The pod sizes on offer: from the players seated, and never under two, up to eight. */
export function podChoices(seated: number): number[] {
  const out: number[] = [];
  for (let n = Math.max(2, seated); n <= 8; n++) out.push(n);
  return out;
}

/** Where the seats start: the product's own pod, raised to the players seated, as desktop's pod choice starts. */
export function podStart(recommended: number, seated: number): number {
  return Math.min(8, Math.max(recommended, Math.max(2, seated)));
}

/** The pod a product drafts at: a single-set block's own, and a full pod of eight for everything else. */
function productPod(options: LimitedOptions, v: DraftValue): number {
  return (v.product === 'Block' || v.product === 'FantasyBlock') ? draftBlockOf(options, v)?.podSize ?? 8 : 8;
}

const PICK_RULES: [string, string][] = [['NEVER', 'One pick per pass'], ['FIRST_PICK', 'Two on the first pick'], ['ALWAYS', 'Two every pass']];
const TIMERS = [0, 30, 45, 60, 90];
const GRACES = [0, 60, 120, 300];
const seconds = (n: number, none: string) => (n === 0 ? none : `${n} s`);

/** The draft products desktop offers; LimitedPoolType's names are the ids. */
const DRAFT_PRODUCTS: [string, string, string][] = [
  ['Full', 'Full card pool', 'Three packs drawn from every card in Forge.'],
  ['Block', 'Block', 'Packs from a block or a set.'],
  ['FantasyBlock', 'Fantasy block', "A made-up block from Forge's list."],
  ['Custom', 'Cube', 'A cube saved in Forge.'],
  ['Chaos', 'Chaos', 'Each pack from a random set, within a theme.'],
  ['Import', 'CubeCobra', 'Any cube by its CubeCobra link or ID.'],
];

const draftBlocksFor = (options: LimitedOptions, product?: string) => product === 'FantasyBlock' ? options.draftFantasyBlocks : options.draftBlocks;
const draftBlockOf = (options: LimitedOptions, v: DraftValue) => draftBlocksFor(options, v.product).find(b => b.name === v.block);

/** Choosing a block to draft: a single-set block needs no more questions, since there is nothing to choose. */
export function draftBlockChoice(options: LimitedOptions, product: string, name: string): Partial<DraftValue> {
  const sets = draftBlocksFor(options, product).find(b => b.name === name)?.sets ?? [];
  return sets.length === 1 ? { block: name, combo: sets[0] } : { block: name };
}

/** The packs' sets as the server takes them: "A/B/C", or a single set's code. */
export function draftCombo(v: DraftValue): string | undefined {
  return v.combo ?? v.packs?.join('/');
}

/** The draft's questions; an online draft adds the table rules desktop's host sets before dealing. */
export function draftSteps(options: LimitedOptions, table?: DraftTable): Step<DraftValue>[] {
  const isBlock = (v: DraftValue) => (v.product === 'Block' || v.product === 'FantasyBlock') && !!v.block;
  return [
    {
      id: 'product', label: 'Product', hint: 'What the packs are', fields: ['product'],
      answer: v => DRAFT_PRODUCTS.find(p => p[0] === v.product)?.[1] ?? null,
      render: (_, set) => (
        <div class="tiles">
          {DRAFT_PRODUCTS.map(([id, name, line]) => (
            <button key={id} class="tile-choice" onClick={() => set({ product: id })}><b>{name}</b><span>{line}</span></button>
          ))}
        </div>
      ),
    },
    {
      id: 'block', label: 'Block', hint: 'Which block', fields: ['block'], applies: v => v.product === 'Block' || v.product === 'FantasyBlock',
      answer: v => v.block ?? null,
      render: (v, set) => <Pick items={draftBlocksFor(options, v.product).map(b => [b.name, b.name])} placeholder="Find a block"
        pick={name => set(draftBlockChoice(options, v.product!, name))} />,
    },
    {
      id: 'combo', label: 'Packs', hint: 'Which sets', fields: ['combo'],
      applies: v => isBlock(v) && (draftBlockOf(options, v)?.combos.length ?? 0) > 0,
      answer: v => v.combo ?? null,
      render: (v, set) => (
        <div class="tiles">
          {draftBlockOf(options, v)?.combos.map(c => <button key={c} class="tile-choice" onClick={() => set({ combo: c })}><b>{c}</b></button>)}
        </div>
      ),
    },
    {
      id: 'packs', label: 'Packs', hint: 'A set for each pack', fields: ['packs'],
      applies: v => isBlock(v) && (draftBlockOf(options, v)?.sets.length ?? 0) > 1 && (draftBlockOf(options, v)?.combos.length ?? 0) === 0,
      answer: v => v.packs?.join(' / ') ?? null,
      render: (v, set) => {
        const block = draftBlockOf(options, v)!;
        return <PackSets sets={block.sets} packs={block.packs} done={packs => set({ packs })} />;
      },
    },
    {
      id: 'cube', label: 'Cube', hint: 'Which cube', fields: ['cube'], applies: v => v.product === 'Custom',
      answer: v => v.cube ?? null,
      render: (_, set) => options.cubes.length
        ? <Pick items={options.cubes.map(c => [c, c])} placeholder="Find a cube" pick={c => set({ cube: c })} />
        : <p class="hint">Forge has no cubes saved.</p>,
    },
    {
      id: 'theme', label: 'Theme', hint: 'Which sets the packs come from', fields: ['theme'], applies: v => v.product === 'Chaos',
      answer: v => v.theme ?? null,
      render: (_, set) => <Pick items={options.themes.map(t => [t, t])} placeholder="Find a theme" pick={t => set({ theme: t })} />,
    },
    {
      id: 'cubeId', label: 'Cube', hint: 'A CubeCobra link or ID', fields: ['cubeId'], applies: v => v.product === 'Import',
      answer: v => v.cubeId ?? null,
      render: (_, set) => <TextStep placeholder="CubeCobra link or ID" initial={options.lastCube ?? ''} done={id => set({ cubeId: id })} />,
    },
    {
      id: 'rules', label: 'Table rules', hint: 'Seats, picks and the pick timer', fields: ['podSize', 'pickRule', 'timer', 'grace'],
      applies: () => !!table,
      answer: v => v.timer === undefined ? null : rulesLine(v),
      render: (v, set) => <TableRules seated={table?.seated ?? 2} recommended={productPod(options, v)} done={set} />,
    },
  ];
}

/** The table rules as the folded step and the event panel say them. */
export function rulesLine(v: DraftValue): string {
  const picks = PICK_RULES.find(p => p[0] === v.pickRule)?.[1] ?? PICK_RULES[0][1];
  return `${v.podSize} seats · ${picks.toLowerCase()} · ${v.timer ? `${v.timer} s to pick` : 'no pick timer'}`;
}

function TableRules({ seated, recommended, done }: { seated: number; recommended: number; done: (v: Partial<DraftValue>) => void }) {
  const pods = podChoices(seated);
  const [pod, setPod] = useState(() => podStart(recommended, seated));
  const [pickRule, setPickRule] = useState('NEVER');
  const [timer, setTimer] = useState(0);
  const [grace, setGrace] = useState(60);
  const at = pods.indexOf(pod);
  return (
    <div class="table-rules">
      <span class="tr-label">Seats</span>
      <div class="tr-field">
        <span class="stepper">
          <button class="step" disabled={at <= 0} aria-label="Fewer seats" onClick={() => setPod(pods[at - 1])}>&minus;</button>
          <span class="n">{pod}</span>
          <button class="step" disabled={at >= pods.length - 1} aria-label="More seats" onClick={() => setPod(pods[at + 1])}>+</button>
        </span>
        <span class="hint">{pod === recommended ? 'As the product drafts. ' : ''}Empty seats draft as computers, which do not play the matches.</span>
      </div>
      <label class="tr-label" for="tr-picks">Picks</label>
      <span class="pill-select"><select id="tr-picks" value={pickRule} onChange={e => setPickRule(e.currentTarget.value)}>
        {PICK_RULES.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
      </select></span>
      <label class="tr-label" for="tr-timer">Pick timer</label>
      <span class="pill-select"><select id="tr-timer" value={timer} onChange={e => setTimer(Number(e.currentTarget.value))}>
        {TIMERS.map(n => <option key={n} value={n}>{seconds(n, 'None')}</option>)}
      </select></span>
      <label class="tr-label" for="tr-grace">Time to come back</label>
      <div class="tr-field">
        <span class="pill-select"><select id="tr-grace" value={grace} onChange={e => setGrace(Number(e.currentTarget.value))}>
          {GRACES.map(n => <option key={n} value={n}>{n === 0 ? 'None' : `${n / 60} min`}</option>)}
        </select></span>
        <span class="hint">How long a player who drops out has before the draft picks for them.</span>
      </div>
      <button class="primary" onClick={() => done({ podSize: pod, pickRule, timer, grace })}>Continue</button>
    </div>
  );
}

/** What the draft is. Online, the table rules say who drafts, so the computer drafters are not counted here. */
export function draftSentence(v: DraftValue, online = false): string {
  switch (v.product) {
    case 'Full': return online ? 'Three packs from the full card pool.' : 'Three packs from the full card pool, with seven computer drafters.';
    case 'Custom': return `A draft of ${v.cube}.`;
    case 'Chaos': return `A chaos draft: ${v.theme}.`;
    case 'Import': return `A draft of the CubeCobra cube ${v.cubeId}.`;
    default: return `${v.block}: ${draftCombo(v)}.`;
  }
}

function PackSets({ sets, packs, done }: { sets: string[]; packs: number; done: (packs: string[]) => void }) {
  const [chosen, setChosen] = useState<string[]>(() => Array.from({ length: packs }, () => sets[0]));
  return (
    <div class="rows">
      {chosen.map((code, i) => (
        <label key={i} class="pack-set">Pack {i + 1}
          <select value={code} onChange={e => setChosen(chosen.map((c, j) => (j === i ? e.currentTarget.value : c)))}>
            {sets.map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
      ))}
      <button class="primary" onClick={() => done(chosen)}>Continue</button>
    </div>
  );
}

function Pick({ items, placeholder, pick }: { items: [string, string][]; placeholder: string; pick: (id: string) => void }) {
  const [filter, setFilter] = useState('');
  const shown = items.filter(([, label]) => label.toLowerCase().includes(filter.trim().toLowerCase()));
  return (
    <div class="pick">
      <input type="search" placeholder={placeholder} value={filter} onInput={e => setFilter(e.currentTarget.value)} />
      <div class="pick-list">
        {shown.map(([id, label]) => <button key={id} onClick={() => pick(id)}>{label}</button>)}
        {shown.length === 0 && <p class="hint">Nothing matches.</p>}
      </div>
    </div>
  );
}

function PackCount({ extra, done }: { extra: boolean; done: (n: number) => void }) {
  const [n, setN] = useState(6);
  return (
    <div class="rows">
      <span class="stepper">
        <button class="step" disabled={n <= 3} aria-label="One fewer pack" onClick={() => setN(n - 1)}>&minus;</button>
        <span class="n">{n}</span>
        <button class="step" disabled={n >= 12} aria-label="One more pack" onClick={() => setN(n + 1)}>+</button>
      </span>
      <span class="hint">{extra ? 'You\'ll open one more pack than this, as Forge\'s desktop does.' : '3 to 12'}</span>
      <button class="primary" onClick={() => done(n)}>Continue</button>
    </div>
  );
}

function TextStep({ placeholder, initial, done }: { placeholder: string; initial: string; done: (text: string) => void }) {
  const [text, setText] = useState(initial);
  const ok = text.trim().length > 0;
  return (
    <form class="rows" onSubmit={e => { e.preventDefault(); if (ok) done(text.trim()); }}>
      <input type="text" placeholder={placeholder} value={text} onInput={e => setText(e.currentTarget.value)} />
      <button class="primary" type="submit" disabled={!ok}>Continue</button>
    </form>
  );
}
