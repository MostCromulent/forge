// Desktop asks the same questions as a chain of dialogs, and here as there the answers decide which questions are asked

import type { JSX } from 'preact';
import { useState } from 'preact/hooks';
import type { LimitedOptions, SealedBlock } from './protocol';
import { t, type TextKey } from './text';

export interface Step<V> {
  id: string;
  label: string;
  /** What the step is called once it is folded or still to come, when the question is too long for a line's label. */
  short?: string;
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

/** What the event will be, shown beside the questions and filled in as they are answered. */
export interface Ticket<V> {
  /** The small words over the title; "Your event" when left out. */
  kicker?: string;
  title: string | ((v: V) => string);
  /** A picture of the choices so far, between the title and the rows. */
  picture?: (v: V) => JSX.Element | null;
  rows: (v: V) => [string, string | null][];
  /** What the button does, under it. */
  note: string;
}

/** ready, when given, says the form can be sent although its last step is still open: a name typed but not yet entered. */
export function StepForm<V>({ title, steps, value, onChange, sentence, action, submit, problem, busy, ticket, ready }: {
  title?: string; steps: Step<V>[]; value: V; onChange: (v: V) => void; sentence?: (v: V) => string;
  action: string | ((v: V) => string); submit: () => void; problem?: JSX.Element | null; busy?: boolean; ticket?: Ticket<V>;
  ready?: boolean;
}) {
  const open = openStep(steps, value);
  const label = typeof action === 'string' ? action : action(value);
  const button = <button class={ticket ? 'primary big' : 'primary'} disabled={busy || !(ready ?? open === null)} onClick={submit}>{busy ? t('lblWebSetupOpening') : label}</button>;
  const questions = <Questions steps={steps} value={value} onChange={onChange} title={title} />;
  if (ticket) {
    return (
      <div class="wiz-ticket">
        {questions}
        <aside class="ticket" aria-label={ticket.kicker ?? t('lblWebSetupYourEvent')}>
          <div class="ticket-head">
            <span class="ticket-kicker">{ticket.kicker ?? t('lblWebSetupYourEvent')}</span>
            <b>{typeof ticket.title === 'string' ? ticket.title : ticket.title(value)}</b>
          </div>
          {ticket.picture?.(value)}
          <dl>
            {ticket.rows(value).map(([name, v]) => <>
              <dt key={name}>{name}</dt><dd class={v === null ? 'pending' : undefined}>{v ?? t('lblWebSetupNotChosenYet')}</dd>
            </>)}
          </dl>
          <div class="ticket-foot">
            {problem}
            {button}
            {ticket.note && <small>{ticket.note}</small>}
          </div>
        </aside>
      </div>
    );
  }
  return (
    <div class="wiz">
      {questions}
      {open === null && (
        <div class="wfoot">
          {problem ?? <span class="sentence">{sentence?.(value)}</span>}
          {button}
        </div>
      )}
    </div>
  );
}

/** The questions: answered ones folded to a line, the open one, then those still to come. */
function Questions<V>({ steps, value, onChange, title }: { steps: Step<V>[]; value: V; onChange: (v: V) => void; title?: string }) {
  const open = openStep(steps, value);
  const shown = steps.filter(s => applies(s, value));
  const openAt = open === null ? shown.length : shown.findIndex(s => s.id === open);
  return (
    <div class="wiz-steps">
      {title && <h3 class="wiz-title">{title}</h3>}
      {shown.map((step, i) => {
        if (i < openAt) {
          return (
            <div key={step.id} class="stp done">
              <span class="num" aria-hidden="true">✓</span>
              <span class="lab">{step.short ?? step.label}</span>
              <span class="val">{step.answer(value)}</span>
              <button class="link" onClick={() => onChange(reopen(steps, value, step.id))}>{t('lblEdit')}</button>
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
            <span class="num">{i + 1}</span><span class="lab">{step.short ?? step.label}</span><span class="val">{step.hint}</span>
          </div>
        );
      })}
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
const PRODUCTS: [string, TextKey, TextKey][] = [
  ['Full', 'lblWebSetupProductFull', 'lblWebSetupProductFullLine'],
  ['Block', 'lblBlock', 'lblWebSetupProductBlockLine'],
  ['FantasyBlock', 'lblWebSetupProductFantasy', 'lblWebSetupProductFantasyLine'],
  ['Prerelease', 'lblLimitedPrerelease', 'lblWebSetupProductPrereleaseLine'],
  ['Custom', 'lblWebSetupProductCustom', 'lblWebSetupProductCustomLine'],
  ['Import', 'lblWebSetupProductCubeCobra', 'lblWebSetupProductCubeCobraLine'],
];

const productName = (id?: string) => {
  const key = PRODUCTS.find(p => p[0] === id)?.[1];
  return key ? t(key) : '';
};
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
      id: 'product', label: t('lblProduct'), hint: t('lblWebSetupHintWhichPacks'), fields: ['product'],
      answer: v => v.product ? productName(v.product) : null,
      render: (_, set) => (
        <div class="tiles">
          {PRODUCTS.map(([id, name, line]) => (
            <button key={id} class="tile-choice" onClick={() => set({ product: id })}><b>{t(name)}</b><span>{t(line)}</span></button>
          ))}
        </div>
      ),
    },
    {
      id: 'block', label: t('lblBlock'), hint: t('lblWebSetupHintWhichBlock'), fields: ['block'], applies: isBlock,
      answer: v => v.block ?? null,
      render: (v, set) => <Pick items={blocksFor(options, v.product).map(b => [b.name, b.name])} placeholder={t('lblWebSetupFindBlock')}
        pick={name => set(sealedBlockChoice(options, v.product!, name))} />,
    },
    {
      id: 'combo', label: t('lblDraftOverlayPacks'), hint: t('lblWebSetupHintWhichSets'), fields: ['combo'],
      applies: v => isBlock(v) && !!v.block && (blockOf(options, v)?.combos.length ?? 0) > 1,
      answer: v => v.combo ?? null,
      render: (v, set) => (
        <div class="tiles">
          {blockOf(options, v)?.combos.map(c => <button key={c} class="tile-choice" onClick={() => set({ combo: c })}><b>{c}</b></button>)}
        </div>
      ),
    },
    {
      id: 'edition', label: t('lblWebSetupEdition'), hint: t('lblWebSetupHintWhichPrerelease'), fields: ['edition'], applies: v => v.product === 'Prerelease',
      answer: v => options.prereleases.find(e => e.code === v.edition)?.name ?? null,
      render: (_, set) => <Pick items={options.prereleases.map(e => [e.code, e.name])} placeholder={t('lblWebSetupFindEdition')}
        pick={code => set({ edition: code })} />,
    },
    {
      id: 'template', label: t('lblWebSetupPool'), hint: t('lblWebSetupHintWhichSavedPool'), fields: ['template'], applies: v => v.product === 'Custom',
      answer: v => v.template ?? null,
      render: (_, set) => options.templates.length
        ? <Pick items={options.templates.map(p => [p, p])} placeholder={t('lblWebSetupFindPool')} pick={p => set({ template: p })} />
        : <p class="hint">{t('lblWebSetupNoPools')}</p>,
    },
    {
      id: 'cubeId', label: t('lblWebSetupCube'), hint: t('lblWebSetupHintCubeCobra'), fields: ['cubeId'], applies: v => v.product === 'Import',
      answer: v => v.cubeId ?? null,
      render: (_, set) => <TextStep placeholder={t('lblWebSetupCubeCobraField')} initial="" done={id => set({ cubeId: id })} />,
    },
    {
      id: 'packs', label: t('lblDraftOverlayPacks'), hint: t('lblWebSetupHintHowMany'), fields: ['packs'], applies: hasPackCount,
      answer: v => v.packs === undefined ? null : t('lblWebSetupNPacks', v.packs),
      render: (v, set) => <PackCount extra={v.product === 'Import'} done={n => set({ packs: n })} />,
    },
    {
      id: 'name', label: t('lblName'), hint: t('lblWebSetupHintPoolName'), fields: ['name'],
      answer: v => v.name ?? null,
      render: (v, set) => <TextStep placeholder={t('lblWebSetupPoolName')} initial={defaultName(v)} done={name => set({ name })} />,
    },
  ];
}

function defaultName(v: SealedValue): string {
  const what = v.product === 'Prerelease' ? t('lblLimitedPrerelease') : isBlock(v) ? v.block ?? '' : productName(v.product);
  return t('lblWebSetupDefaultName', what, new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }));
}

export function sealedSentence(options: LimitedOptions, v: SealedValue): string {
  switch (v.product) {
    case 'Full': return t('lblWebSetupSealedFullSentence', v.packs ?? 0);
    case 'Prerelease': return t('lblWebSetupSealedPrereleaseSentence', options.prereleases.find(e => e.code === v.edition)?.name ?? '');
    case 'Custom': return t('lblWebSetupSealedCustomSentence', v.packs ?? 0, v.template ?? '');
    case 'Import': return t('lblWebSetupSealedImportSentence', (v.packs ?? 0) + 1, v.cubeId ?? '');
    default: return t('lblWebSetupBlockSentence', v.block ?? '', v.combo ?? '');
  }
}

/** How many packs a sealed pool opens, where the form asks; a CubeCobra pool opens one more than it asks for. */
function packCount(v: SealedValue): number | undefined {
  return hasPackCount(v) && v.packs !== undefined ? v.packs + (v.product === 'Import' ? 1 : 0) : undefined;
}

function sealedProduct(options: LimitedOptions, v: SealedValue): string | null {
  if (!v.product) return null;
  if (isBlock(v)) return v.block ?? productName(v.product);
  if (v.product === 'Prerelease') return options.prereleases.find(e => e.code === v.edition)?.name ?? productName(v.product);
  if (v.product === 'Custom') return v.template ?? productName(v.product);
  if (v.product === 'Import') return v.cubeId ? t('lblWebSetupCubeCobraId', v.cubeId) : productName(v.product);
  return productName(v.product);
}

function sealedPacks(v: SealedValue): string | null {
  if (v.product === 'Prerelease') return v.edition ? t('lblWebSetupPrereleaseKit') : null;
  if (isBlock(v)) return v.combo ?? null;
  const n = packCount(v);
  return n === undefined ? null : String(n);
}

/** The offline sealed event beside its questions. Desktop builds seven opponents from the same packs. */
export function sealedTicket(options: LimitedOptions): Ticket<SealedValue> {
  return {
    title: t('lblSealed'),
    rows: v => [
      [t('lblProduct'), sealedProduct(options, v)],
      [t('lblDraftOverlayPacks'), sealedPacks(v)],
      [t('lblName'), v.name ?? null],
      [t('lblWebSetupYourDeck'), t('lblWebSetupFortyOrMore')],
      [t('lblWebSetupOpponents'), t('lblWebSetupSevenSamePacks')],
    ],
    note: t('lblWebSetupSealedNote'),
  };
}

/** What opening the pool says it does: the number of packs when the form asked for it. */
export function sealedAction(v: SealedValue): string {
  const n = packCount(v);
  return n ? t('lblWebSetupOpenNPacks', n) : v.product === 'Prerelease' ? t('lblWebSetupOpenKit') : t('lblWebSetupOpenPacks');
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

/** Each pick rule's name, and the same in the middle of a line. */
const PICK_RULES: [string, TextKey, TextKey][] = [
  ['NEVER', 'lblWebSetupPickOne', 'lblWebSetupPickOneInline'],
  ['FIRST_PICK', 'lblWebSetupPickFirstTwo', 'lblWebSetupPickFirstTwoInline'],
  ['ALWAYS', 'lblWebSetupPickAlwaysTwo', 'lblWebSetupPickAlwaysTwoInline'],
];
const TIMERS = [0, 30, 45, 60, 90];
const GRACES = [0, 60, 120, 300];
const seconds = (n: number, none: string) => (n === 0 ? none : t('lblWebEventSeconds', n));

/** The draft products desktop offers; LimitedPoolType's names are the ids. */
const DRAFT_PRODUCTS: [string, TextKey, TextKey][] = [
  ['Full', 'lblWebSetupProductFull', 'lblWebSetupDraftFullLine'],
  ['Block', 'lblBlock', 'lblWebSetupProductBlockLine'],
  ['FantasyBlock', 'lblWebSetupProductFantasy', 'lblWebSetupProductFantasyLine'],
  ['Custom', 'lblWebSetupCube', 'lblWebSetupDraftCubeLine'],
  ['Chaos', 'lblChaos', 'lblWebSetupDraftChaosLine'],
  ['Import', 'lblWebSetupProductCubeCobra', 'lblWebSetupProductCubeCobraLine'],
];

const draftProductName = (id?: string) => {
  const key = DRAFT_PRODUCTS.find(p => p[0] === id)?.[1];
  return key ? t(key) : undefined;
};

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
      id: 'product', label: t('lblProduct'), hint: t('lblWebSetupHintWhichPacks'), fields: ['product'],
      answer: v => draftProductName(v.product) ?? null,
      render: (_, set) => (
        <div class="tiles">
          {DRAFT_PRODUCTS.map(([id, name, line]) => (
            <button key={id} class="tile-choice" onClick={() => set({ product: id })}><b>{t(name)}</b><span>{t(line)}</span></button>
          ))}
        </div>
      ),
    },
    {
      id: 'block', label: t('lblBlock'), hint: t('lblWebSetupHintWhichBlock'), fields: ['block'], applies: v => v.product === 'Block' || v.product === 'FantasyBlock',
      answer: v => v.block ?? null,
      render: (v, set) => <Pick items={draftBlocksFor(options, v.product).map(b => [b.name, b.name])} placeholder={t('lblWebSetupFindBlock')}
        pick={name => set(draftBlockChoice(options, v.product!, name))} />,
    },
    {
      id: 'combo', label: t('lblDraftOverlayPacks'), hint: t('lblWebSetupHintWhichSets'), fields: ['combo'],
      applies: v => isBlock(v) && (draftBlockOf(options, v)?.combos.length ?? 0) > 0,
      answer: v => v.combo ?? null,
      render: (v, set) => (
        <div class="tiles">
          {draftBlockOf(options, v)?.combos.map(c => <button key={c} class="tile-choice" onClick={() => set({ combo: c })}><b>{c}</b></button>)}
        </div>
      ),
    },
    {
      id: 'packs', label: t('lblDraftOverlayPacks'), hint: t('lblWebSetupHintSetEachPack'), fields: ['packs'],
      applies: v => isBlock(v) && (draftBlockOf(options, v)?.sets.length ?? 0) > 1 && (draftBlockOf(options, v)?.combos.length ?? 0) === 0,
      answer: v => v.packs?.join(' / ') ?? null,
      render: (v, set) => {
        const block = draftBlockOf(options, v)!;
        return <PackSets sets={block.sets} packs={block.packs} done={packs => set({ packs })} />;
      },
    },
    {
      id: 'cube', label: t('lblWebSetupCube'), hint: t('lblWebSetupHintWhichCube'), fields: ['cube'], applies: v => v.product === 'Custom',
      answer: v => v.cube ?? null,
      render: (_, set) => options.cubes.length
        ? <Pick items={options.cubes.map(c => [c, c])} placeholder={t('lblWebSetupFindCube')} pick={c => set({ cube: c })} />
        : <p class="hint">{t('lblWebSetupNoCubes')}</p>,
    },
    {
      id: 'theme', label: t('lblWebSetupTheme'), hint: t('lblWebSetupHintWhichSets'), fields: ['theme'], applies: v => v.product === 'Chaos',
      answer: v => v.theme ?? null,
      render: (_, set) => <Pick items={options.themes.map(th => [th, th])} placeholder={t('lblWebSetupFindTheme')} pick={th => set({ theme: th })} />,
    },
    {
      id: 'cubeId', label: t('lblWebSetupCube'), hint: t('lblWebSetupHintCubeCobra'), fields: ['cubeId'], applies: v => v.product === 'Import',
      answer: v => v.cubeId ?? null,
      render: (_, set) => <TextStep placeholder={t('lblWebSetupCubeCobraField')} initial={options.lastCube ?? ''} done={id => set({ cubeId: id })} />,
    },
    {
      id: 'rules', label: t('lblWebSetupTableRules'), hint: t('lblWebSetupHintRules'), fields: ['podSize', 'pickRule', 'timer', 'grace'],
      applies: () => !!table,
      answer: v => v.timer === undefined ? null : rulesLine(v),
      render: (v, set) => <TableRules seated={table?.seated ?? 2} recommended={productPod(options, v)} done={set} />,
    },
  ];
}

/** A pick rule's name, as the form and the event panel say it: on its own, or in the middle of a line. */
export function pickRuleName(rule?: string, inline = false): string {
  const names = PICK_RULES.find(p => p[0] === rule) ?? PICK_RULES[0];
  return t(inline ? names[2] : names[1]);
}

/** The table rules as the folded step and the event panel say them. */
export function rulesLine(v: DraftValue): string {
  const picks = pickRuleName(v.pickRule, true);
  return t('lblWebSetupRulesLine', v.podSize ?? 0, picks, v.timer ? t('lblWebSetupSecondsToPick', v.timer) : t('lblWebEventNoPickTimer'));
}

function TableRules({ seated, recommended, done }: { seated: number; recommended: number; done: (v: Partial<DraftValue>) => void }) {
  const pods = podChoices(seated);
  const [pod, setPod] = useState(() => podStart(recommended, seated));
  const [pickRule, setPickRule] = useState('NEVER');
  const [timer, setTimer] = useState(60);
  const [grace, setGrace] = useState(120);
  const at = pods.indexOf(pod);
  return (
    <div class="table-rules">
      <span class="tr-label">{t('lblWebSetupSeats')}</span>
      <div class="tr-field">
        <span class="stepper">
          <button class="step" disabled={at <= 0} aria-label={t('lblWebSetupFewerSeats')} onClick={() => setPod(pods[at - 1])}>&minus;</button>
          <span class="n">{pod}</span>
          <button class="step" disabled={at >= pods.length - 1} aria-label={t('lblWebSetupMoreSeats')} onClick={() => setPod(pods[at + 1])}>+</button>
        </span>
        <span class="hint">{t('lblWebSetupComputersFill')}</span>
      </div>
      <label class="tr-label" for="tr-picks">{t('lblWebEventPicks')}</label>
      <span class="pill-select"><select id="tr-picks" value={pickRule} onChange={e => setPickRule(e.currentTarget.value)}>
        {PICK_RULES.map(([id, name]) => <option key={id} value={id}>{t(name)}</option>)}
      </select></span>
      <label class="tr-label" for="tr-timer">{t('lblNetworkPickTimerCaption')}</label>
      <span class="pill-select"><select id="tr-timer" value={timer} onChange={e => setTimer(Number(e.currentTarget.value))}>
        {TIMERS.map(n => <option key={n} value={n}>{seconds(n, t('lblNone'))}</option>)}
      </select></span>
      <label class="tr-label" for="tr-grace">{t('lblWebSetupTimeToRejoin')}</label>
      <div class="tr-field">
        <span class="pill-select"><select id="tr-grace" value={grace} onChange={e => setGrace(Number(e.currentTarget.value))}>
          {GRACES.map(n => <option key={n} value={n}>{n === 0 ? t('lblNone') : t('lblWebSetupMinutes', n / 60)}</option>)}
        </select></span>
        <span class="hint">{t('lblWebSetupRejoinHint')}</span>
      </div>
      <button class="primary" onClick={() => done({ podSize: pod, pickRule, timer, grace })}>{t('lblContinue')}</button>
    </div>
  );
}

/** What the draft is. Online, the table rules say who drafts, so the computer drafters are not counted here. */
export function draftSentence(v: DraftValue, online = false): string {
  switch (v.product) {
    case 'Full': return online ? t('lblWebSetupDraftFullSentence') : t('lblWebSetupDraftFullSentenceAi');
    case 'Custom': return t('lblWebSetupDraftOf', v.cube ?? '');
    case 'Chaos': return t('lblWebSetupChaosSentence', v.theme ?? '');
    case 'Import': return t('lblWebSetupDraftCubeCobraSentence', v.cubeId ?? '');
    default: return t('lblWebSetupBlockSentence', v.block ?? '', draftCombo(v) ?? '');
  }
}

function draftProduct(v: DraftValue): string | null {
  const name = draftProductName(v.product);
  if (!name) return null;
  switch (v.product) {
    case 'Block': case 'FantasyBlock': {
      const sets = draftCombo(v);
      return v.block ? (sets ? t('lblWebSetupBlockSets', v.block, sets) : v.block) : name;
    }
    case 'Custom': return v.cube ?? name;
    case 'Chaos': return v.theme ? t('lblWebSetupChaosTheme', v.theme) : name;
    case 'Import': return v.cubeId ? t('lblWebSetupCubeCobraId', v.cubeId) : name;
    default: return name;
  }
}

/** The offline draft beside its questions: seven computers draft beside you, and their decks are your opponents. */
export function draftTicket(): Ticket<DraftValue> {
  return {
    title: t('lblWebDraftBoosterDraft'),
    rows: v => [
      [t('lblProduct'), draftProduct(v)],
      [t('lblWebSetupDrafters'), t('lblWebSetupYouAndSevenAi')],
      [t('lblWebSetupYourDeck'), t('lblWebSetupFortyOrMore')],
      [t('lblWebSetupOpponents'), t('lblWebSetupSevenDecksBeside')],
    ],
    note: t('lblWebSetupDraftNote'),
  };
}

function PackSets({ sets, packs, done }: { sets: string[]; packs: number; done: (packs: string[]) => void }) {
  const [chosen, setChosen] = useState<string[]>(() => Array.from({ length: packs }, () => sets[0]));
  return (
    <div class="rows">
      {chosen.map((code, i) => (
        <label key={i} class="pack-set">{t('lblPackN', i + 1)}
          <select value={code} onChange={e => setChosen(chosen.map((c, j) => (j === i ? e.currentTarget.value : c)))}>
            {sets.map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
      ))}
      <button class="primary" onClick={() => done(chosen)}>{t('lblContinue')}</button>
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
        {shown.length === 0 && <p class="hint">{t('lblWebSetupNothingMatches')}</p>}
      </div>
    </div>
  );
}

function PackCount({ extra, done }: { extra: boolean; done: (n: number) => void }) {
  const [n, setN] = useState(6);
  return (
    <div class="rows">
      <span class="stepper">
        <button class="step" disabled={n <= 3} aria-label={t('lblWebSetupOneFewerPack')} onClick={() => setN(n - 1)}>&minus;</button>
        <span class="n">{n}</span>
        <button class="step" disabled={n >= 12} aria-label={t('lblWebSetupOneMorePack')} onClick={() => setN(n + 1)}>+</button>
      </span>
      <span class="hint">{extra ? t('lblWebSetupCubeCobraExtra') : t('lblWebSetupRange', 3, 12)}</span>
      <button class="primary" onClick={() => done(n)}>{t('lblContinue')}</button>
    </div>
  );
}

function TextStep({ placeholder, initial, done }: { placeholder: string; initial: string; done: (text: string) => void }) {
  const [text, setText] = useState(initial);
  const ok = text.trim().length > 0;
  return (
    <form class="rows" onSubmit={e => { e.preventDefault(); if (ok) done(text.trim()); }}>
      <input type="text" placeholder={placeholder} value={text} onInput={e => setText(e.currentTarget.value)} />
      <button class="primary" type="submit" disabled={!ok}>{t('lblContinue')}</button>
    </form>
  );
}
