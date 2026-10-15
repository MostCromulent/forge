// A form of questions asked one at a time, each answer deciding which come next, beside a ticket that sums up the answers

import type { JSX } from 'preact';
import { useState } from 'preact/hooks';
import { t } from './text';

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

/** A list to choose from, narrowed by what is typed. */
export function Pick({ items, placeholder, pick }: { items: [string, string][]; placeholder: string; pick: (id: string) => void }) {
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
